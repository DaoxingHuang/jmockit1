package mockit.external.asm;

/**
 * Generates the "BootstrapMethods" attribute in a class file being written by a {@link ClassWriter}.
 */
final class BootstrapMethods
{
   private final ClassWriter cw;
   private final ConstantPoolGeneration constantPool;

   /**
    * The number of entries in the BootstrapMethods attribute.
    */
   private int bootstrapMethodsCount;

   /**
    * The BootstrapMethods attribute.
    */
   private ByteVector bootstrapMethods;

   BootstrapMethods(ClassWriter cw) {
      this.cw = cw;
      constantPool = cw.constantPool;
   }

   Item addInvokeDynamicReference(String name, String desc, Handle bsm, Object... bsmArgs) {
      ByteVector methods = bootstrapMethods;

      if (methods == null) {
         methods = bootstrapMethods = new ByteVector();
      }

      int position = methods.length; // record current position

      int hashCode = bsm.hashCode();
      Item handleItem = constantPool.newHandleItem(bsm);
      methods.putShort(handleItem.index);

      int argsLength = bsmArgs.length;
      methods.putShort(argsLength);

      hashCode = putBSMArgs(hashCode, bsmArgs);

      byte[] data = methods.data;
      int length = (1 + 1 + argsLength) << 1; // (bsm + argCount + arguments)
      hashCode &= 0x7FFFFFFF;

      Item bsmItem = getBSMItem(position, hashCode, data, length);
      int bsmIndex;

      if (bsmItem != null) {
         bsmIndex = bsmItem.index;
         methods.length = position; // revert to old position
      }
      else {
         bsmIndex = bootstrapMethodsCount++;
         bsmItem = new Item(bsmIndex);
         bsmItem.set(position, hashCode);
         constantPool.put(bsmItem);
      }

      // Now, create the InvokeDynamic constant.
      Item result = constantPool.createInvokeDynamicConstant(name, desc, bsmIndex);
      return result;
   }

   private int putBSMArgs(int hashCode, Object[] bsmArgs) {
      ByteVector methods = bootstrapMethods;

      for (int i = 0; i < bsmArgs.length; i++) {
         Object bsmArg = bsmArgs[i];
         hashCode ^= bsmArg.hashCode();

         Item constItem = cw.newConstItem(bsmArg);
         methods.putShort(constItem.index);
      }

      return hashCode;
   }

   private Item getBSMItem(int position, int hashCode, byte[] data, int length) {
      Item bsmItem = constantPool.getItem(hashCode);

   loop:
      while (bsmItem != null) {
         if (bsmItem.type != ConstantPoolItemType.BSM || bsmItem.hashCode != hashCode) {
            bsmItem = bsmItem.next;
            continue;
         }

         // Because the data encode the size of the argument we don't need to test if these size are equals.
         int resultPosition = bsmItem.intVal;

         for (int p = 0; p < length; p++) {
            if (data[position + p] != data[resultPosition + p]) {
               bsmItem = bsmItem.next;
               continue loop;
            }
         }

         break;
      }

      return bsmItem;
   }

   boolean hasMethods() { return bootstrapMethods != null; }

   int getSize() {
      cw.newUTF8("BootstrapMethods");
      return 8 + bootstrapMethods.length;
   }

   void put(ByteVector out) {
      if (hasMethods()) {
         out.putShort(cw.newUTF8("BootstrapMethods"));
         out.putInt(bootstrapMethods.length + 2).putShort(bootstrapMethodsCount);
         out.putByteVector(bootstrapMethods);
      }
   }

   /**
    * Copies the bootstrap method data into the {@link #cw ClassWriter}.
    */
   void copyBootstrapMethods(Item[] items, char[] c) {
      ClassReader cr = cw.cr;

      // Finds the "BootstrapMethods" attribute.
      int u = cr.getAttributesStartIndex();
      boolean found = false;

      for (int i = cr.readUnsignedShort(u); i > 0; i--) {
         String attrName = cr.readUTF8(u + 2, c);

         if ("BootstrapMethods".equals(attrName)) {
            found = true;
            break;
         }

         u += 6 + cr.readInt(u + 4);
      }

      if (!found) {
         return;
      }

      // Copies the bootstrap methods in the class writer.
      bootstrapMethodsCount = cr.readUnsignedShort(u + 8);

      for (int j = 0, v = u + 10; j < bootstrapMethodsCount; j++) {
         int position = v - u - 10;
         int hashCode = cr.readConst(cr.readUnsignedShort(v), c).hashCode();

         for (int k = cr.readUnsignedShort(v + 2); k > 0; k--) {
            hashCode ^= cr.readConst(cr.readUnsignedShort(v + 4), c).hashCode();
            v += 2;
         }

         v += 4;
         Item item = new Item(j);
         item.set(position, hashCode & 0x7FFFFFFF);
         int index = item.hashCode % items.length;
         item.next = items[index];
         items[index] = item;
      }

      int attrSize = cr.readInt(u + 4);
      bootstrapMethods = new ByteVector(attrSize + 62);
      bootstrapMethods.putByteArray(cr.b, u + 10, attrSize - 2);
   }
}
