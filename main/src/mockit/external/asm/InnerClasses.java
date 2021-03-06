package mockit.external.asm;

final class InnerClasses
{
   private final ClassWriter cw;

   /**
    * The constant pool item that contains the name of the attribute to be produced.
    */
   private final int attributeName;

   /**
    * The InnerClasses attribute.
    */
   private final ByteVector innerClasses;

   /**
    * The number of entries in the InnerClasses attribute.
    */
   private int innerClassesCount;

   InnerClasses(ClassWriter cw) {
      this.cw = cw;
      attributeName = cw.newUTF8("InnerClasses");
      innerClasses = new ByteVector();
   }

   void add(String name, String outerName, String innerName, int access) {
      Item nameItem = cw.newClassItem(name);

      // Sec. 4.7.6 of the JVMS states "Every CONSTANT_Class_info entry in the constant_pool table which represents a
      // class or interface C that is not a package member must have exactly one corresponding entry in the classes
      // array". To avoid duplicates we keep track in the intVal field of the Item of each CONSTANT_Class_info entry C
      // whether an inner class entry has already been added for C (this field is unused for class entries, and
      // changing its value does not change the hashcode and equality tests). If so we store the index of this inner
      // class entry (plus one) in intVal. This hack allows duplicate detection in O(1) time.
      if (nameItem.intVal == 0) {
         innerClassesCount++;
         innerClasses.putShort(nameItem.index);
         innerClasses.putShort(outerName == null ? 0 : cw.newClass(outerName));
         innerClasses.putShort(innerName == null ? 0 : cw.newUTF8(innerName));
         innerClasses.putShort(access);
         nameItem.intVal = innerClassesCount;
      }
      else {
         // Compare the inner classes entry nameItem.intVal - 1 with the arguments of this method and throw an
         // exception if there is a difference?
      }
   }

   int getSize() { return 8 + innerClasses.length; }

   void put(ByteVector out) {
      out.putShort(attributeName);
      out.putInt(innerClasses.length + 2).putShort(innerClassesCount);
      out.putByteVector(innerClasses);
   }
}
