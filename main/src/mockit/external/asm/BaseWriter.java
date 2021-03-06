package mockit.external.asm;

class BaseWriter
{
   /**
    * The runtime visible annotations of this class/field/method. May be <tt>null</tt>.
    */
   AnnotationWriter annotations;

   final AnnotationVisitor visitAnnotation(ClassWriter cw, String desc) {
      ByteVector bv = new ByteVector();

      // Write type, and reserve space for values count.
      int type = cw.newUTF8(desc);
      bv.putShort(type).putShort(0);

      AnnotationWriter aw = new AnnotationWriter(cw, true, bv, bv, 2);
      aw.next = annotations;
      annotations = aw;
      return aw;
   }

   final int getAnnotationsSize(ClassWriter cw) {
      if (annotations != null) {
         getConstantPoolItemForRuntimeVisibleAnnotationsAttribute(cw);
         return 8 + annotations.getSize();
      }

      return 0;
   }

   private int getConstantPoolItemForRuntimeVisibleAnnotationsAttribute(ClassWriter cw) {
      return cw.newUTF8("RuntimeVisibleAnnotations");
   }

   final void putAnnotations(ByteVector out, ClassWriter cw) {
      if (annotations != null) {
         int item = getConstantPoolItemForRuntimeVisibleAnnotationsAttribute(cw);
         out.putShort(item);
         annotations.put(out);
      }
   }
}
