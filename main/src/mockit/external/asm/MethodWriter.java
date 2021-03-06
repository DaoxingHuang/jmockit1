/*
 * ASM: a very small and fast Java bytecode manipulation framework
 * Copyright (c) 2000-2011 INRIA, France Telecom
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holders nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
package mockit.external.asm;

import static mockit.external.asm.Opcodes.*;

/**
 * A {@link MethodVisitor} that generates methods in bytecode form. Each visit method of this class appends the bytecode
 * corresponding to the visited instruction to a byte vector, in the order these methods are called.
 *
 * @author Eric Bruneton
 * @author Eugene Kuleshov
 */
public final class MethodWriter extends MethodVisitor
{
   /**
    * The class writer to which this method must be added.
    */
   final ClassWriter cw;

   /**
    * Access flags of this method.
    */
   final int access;

   /**
    * The index of the constant pool item that contains the name of this method.
    */
   private final int name;

   /**
    * The index of the constant pool item that contains the descriptor of this method.
    */
   private final int desc;

   /**
    * The descriptor of this method.
    */
   final String descriptor;

   /**
    * The signature of this method.
    */
   String signature;

   /**
    * If not zero, indicates that the code of this method must be copied from the ClassReader associated to this writer
    * in <code>cw.cr</code>. More precisely, this field gives the index of the first byte to copied from
    * <code>cw.cr.b</code>.
    */
   int classReaderOffset;

   /**
    * If not zero, indicates that the code of this method must be copied from the ClassReader associated to this writer
    * in <code>cw.cr</code>. More precisely, this field gives the number of bytes to copied from <code>cw.cr.b</code>.
    */
   int classReaderLength;

   final ThrowsClause throwsClause;

   /**
    * The annotation default attribute of this method. May be <tt>null</tt>.
    */
   private ByteVector annotationDefault;

   /**
    * The runtime visible parameter annotations of this method. May be <tt>null</tt>.
    */
   private AnnotationWriter[] parameterAnnotations;

   /**
    * The bytecode of this method.
    */
   final ByteVector code;

   private final FrameAndStackComputation frameAndStack;
   private final ExceptionHandling exceptionHandling;
   private final LocalVariables localVariables;
   private final LineNumbers lineNumbers;
   private final CFGAnalysis cfgAnalysis;
   private final boolean computeFrames;

   /**
    * Constructs a new {@link MethodWriter}.
    *
    * @param cw            the class writer in which the method must be added.
    * @param access        the method's access flags (see {@link Opcodes}).
    * @param name          the method's name.
    * @param desc          the method's descriptor (see {@link Type}).
    * @param signature     the method's signature. May be <tt>null</tt>.
    * @param exceptions    the internal names of the method's exceptions. May be <tt>null</tt>.
    * @param computeFrames {@code true} if the stack map tables must be recomputed from scratch.
    */
   MethodWriter(
      ClassWriter cw, int access, String name, String desc, String signature, String[] exceptions, boolean computeFrames
   ) {
      this.cw = cw;
      this.access = "<init>".equals(name) ? (access | Access.CONSTRUCTOR) : access;
      this.name = cw.newUTF8(name);
      this.desc = cw.newUTF8(desc);
      descriptor = desc;
      this.signature = signature;
      throwsClause = new ThrowsClause(cw, exceptions);
      code = new ByteVector();
      this.computeFrames = computeFrames;
      frameAndStack = new FrameAndStackComputation(this, access, desc);
      exceptionHandling = new ExceptionHandling(cw);
      localVariables = new LocalVariables(cw);
      lineNumbers = new LineNumbers(cw);
      cfgAnalysis = new CFGAnalysis(cw, code, computeFrames);
   }

   // ------------------------------------------------------------------------
   // Implementation of the MethodVisitor base class
   // ------------------------------------------------------------------------

   @Override
   public AnnotationVisitor visitAnnotationDefault() {
      annotationDefault = new ByteVector();
      return new AnnotationWriter(cw, false, annotationDefault, null, 0);
   }

   @Override
   public AnnotationVisitor visitAnnotation(String desc) {
      return visitAnnotation(cw, desc);
   }

   @Override
   public AnnotationVisitor visitParameterAnnotation(int parameter, String desc) {
      ByteVector bv = new ByteVector();

      // Write type, and reserve space for values count.
      bv.putShort(cw.newUTF8(desc)).putShort(0);

      AnnotationWriter aw = new AnnotationWriter(cw, true, bv, bv, 2);

      if (parameterAnnotations == null) {
         int numParameters = Type.getArgumentTypes(descriptor).length;
         parameterAnnotations = new AnnotationWriter[numParameters];
      }

      aw.next = parameterAnnotations[parameter];
      parameterAnnotations[parameter] = aw;

      return aw;
   }

   @Override
   public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
      if (!computeFrames) {
         frameAndStack.readFrame(type, nLocal, local, nStack, stack);
      }
   }

   @Override
   public void visitInsn(int opcode) {
      // Adds the instruction to the bytecode of the method.
      code.putByte(opcode);

      cfgAnalysis.updateCurrentBlockForZeroOperandInstruction(opcode);
   }

   @Override
   public void visitIntInsn(int opcode, int operand) {
      cfgAnalysis.updateCurrentBlockForSingleIntOperandInstruction(opcode, operand);

      // Adds the instruction to the bytecode of the method.
      if (opcode == SIPUSH) {
         code.put12(opcode, operand);
      }
      else { // BIPUSH or NEWARRAY
         code.put11(opcode, operand);
      }
   }

   @Override
   public void visitVarInsn(int opcode, int var) {
      cfgAnalysis.updateCurrentBlockForLocalVariableInstruction(opcode, var);

      // Updates max locals.
      int n = opcode == LLOAD || opcode == DLOAD || opcode == LSTORE || opcode == DSTORE ? var + 2 : var + 1;
      frameAndStack.updateMaxLocals(n);

      // Adds the instruction to the bytecode of the method.
      if (var < 4 && opcode != RET) {
         int opt;

         if (opcode < ISTORE) { // ILOAD_0
            opt = 26 + ((opcode - ILOAD) << 2) + var;
         }
         else { // ISTORE_0
            opt = 59 + ((opcode - ISTORE) << 2) + var;
         }

         code.putByte(opt);
      }
      else if (var >= 256) {
         code.putByte(WIDE).put12(opcode, var);
      }
      else {
         code.put11(opcode, var);
      }

      if (opcode >= ISTORE && computeFrames && exceptionHandling.hasHandlers()) {
         visitLabel(new Label());
      }
   }

   @Override
   public void visitTypeInsn(int opcode, String type) {
      Item typeItem = cw.newClassItem(type);
      cfgAnalysis.updateCurrentBlockForTypeInstruction(opcode, typeItem);

      // Adds the instruction to the bytecode of the method.
      code.put12(opcode, typeItem.index);
   }

   @Override
   public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      Item fieldItem = cw.newFieldItem(owner, name, desc);
      cfgAnalysis.updateCurrentBlockForFieldInstruction(opcode, fieldItem, desc);

      // Adds the instruction to the bytecode of the method.
      code.put12(opcode, fieldItem.index);
   }

   @Override
   public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
      Item invokeItem = cw.constantPool.newMethodItem(owner, name, desc, itf);
      cfgAnalysis.updateCurrentBlockForInvokeInstruction(invokeItem, opcode, desc);

      // Adds the instruction to the bytecode of the method.
      code.put12(opcode, invokeItem.index);

      if (opcode == INVOKEINTERFACE) {
         int argSize = invokeItem.getArgSizeComputingIfNeeded(desc);
         code.put11(argSize >> 2, 0);
      }
   }

   @Override
   public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
      Item invokeItem = cw.newInvokeDynamicItem(name, desc, bsm, bsmArgs);
      cfgAnalysis.updateCurrentBlockForInvokeInstruction(invokeItem, INVOKEDYNAMIC, desc);

      // Adds the instruction to the bytecode of the method.
      code.put12(INVOKEDYNAMIC, invokeItem.index);
      code.putShort(0);
   }

   @Override
   public void visitJumpInsn(int opcode, Label label) {
      Label nextInsn = cfgAnalysis.updateCurrentBlockForJumpInstruction(opcode, label);

      // Adds the instruction to the bytecode of the method.
      if (label.isResolved() && label.position - code.length < Short.MIN_VALUE) {
         // Case of a backward jump with an offset < -32768. In this case we automatically replace GOTO with GOTO_W,
         // JSR with JSR_W and IFxxx <l> with IFNOTxxx <l'> GOTO_W <l>, where IFNOTxxx is the "opposite" opcode of IFxxx
         // (i.e., IFNE for IFEQ) and where <l'> designates the instruction just after the GOTO_W.
         if (opcode == GOTO) {
            code.putByte(GOTO_W);
         }
         else if (opcode == JSR) {
            code.putByte(JSR_W);
         }
         else {
            // If the IF instruction is transformed into IFNOT GOTO_W the next instruction becomes the target of the
            // IFNOT instruction.
            if (nextInsn != null) {
               nextInsn.markAsTarget();
            }

            code.putByte(opcode <= 166 ? ((opcode + 1) ^ 1) - 1 : opcode ^ 1);
            code.putShort(8); // jump offset
            code.putByte(GOTO_W);
         }

         label.put(code, code.length - 1, true);
      }
      else {
         // Case of a backward jump with an offset >= -32768, or of a forward jump with, of course, an unknown offset.
         // In these cases we store the offset in 2 bytes (which will be increased in resizeInstructions, if needed).
         code.putByte(opcode);
         label.put(code, code.length - 1, false);
      }

      cfgAnalysis.updateCurrentBlockForJumpTarget(opcode, nextInsn);
   }

   @Override
   public void visitLabel(Label label) {
      cfgAnalysis.updateCurrentBlockForLabelBeforeNextInstruction(label);
   }

   @Override
   public void visitLdcInsn(Object cst) {
      Item constItem = cw.newConstItem(cst);
      cfgAnalysis.updateCurrentBlockForLDCInstruction(constItem);

      // Adds the instruction to the bytecode of the method.
      int index = constItem.index;

      if (constItem.isDoubleSized()) {
         code.put12(LDC2_W, index);
      }
      else if (index >= 256) {
         code.put12(LDC_W, index);
      }
      else {
         code.put11(LDC, index);
      }
   }

   @Override
   public void visitIincInsn(int var, int increment) {
      cfgAnalysis.updateCurrentBlockForIINCInstruction(var);

      // Updates max locals.
      int n = var + 1;
      frameAndStack.updateMaxLocals(n);

      // Adds the instruction to the bytecode of the method.
      if (var > 255 || increment > 127 || increment < -128) {
         code.putByte(WIDE).put12(IINC, var).putShort(increment);
      }
      else {
         code.putByte(IINC).put11(var, increment);
      }
   }

   @Override
   public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
      // Adds the instruction to the bytecode of the method.
      int source = code.length;
      code.putByte(TABLESWITCH);
      code.increaseLengthBy((4 - code.length % 4) % 4);
      dflt.put(code, source, true);
      code.putInt(min).putInt(max);

      for (int i = 0; i < labels.length; ++i) {
         labels[i].put(code, source, true);
      }

      cfgAnalysis.updateCurrentBlockForSwitchInsn(dflt, labels);
   }

   @Override
   public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
      // Adds the instruction to the bytecode of the method.
      int source = code.length;
      code.putByte(LOOKUPSWITCH);
      code.increaseLengthBy((4 - code.length % 4) % 4);
      dflt.put(code, source, true);
      code.putInt(labels.length);

      for (int i = 0; i < labels.length; ++i) {
         code.putInt(keys[i]);
         labels[i].put(code, source, true);
      }

      cfgAnalysis.updateCurrentBlockForSwitchInsn(dflt, labels);
   }

   @Override
   public void visitMultiANewArrayInsn(String desc, int dims) {
      Item arrayTypeItem = cw.newClassItem(desc);
      cfgAnalysis.updateCurrentBlockForMULTIANEWARRAYInstruction(arrayTypeItem, dims);

      // Adds the instruction to the bytecode of the method.
      code.put12(MULTIANEWARRAY, arrayTypeItem.index).putByte(dims);
   }

   @Override
   public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      exceptionHandling.addHandler(start, end, handler, type);
   }

   @Override
   public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
      int localsCount = localVariables.addLocalVariable(name, desc, signature, start, end, index);
      frameAndStack.updateMaxLocals(localsCount);
   }

   @Override
   public void visitLineNumber(int line, Label start) {
      lineNumbers.addLineNumber(line, start);
   }

   @Override
   public void visitMaxStack(int maxStack) {
      int computedMaxStack;

      if (computeFrames) {
         exceptionHandling.completeControlFlowGraphWithExceptionHandlerBlocksFromComputedFrames();
         frameAndStack.createAndVisitFirstFrame(cfgAnalysis.getFirstFrame());

         computedMaxStack = cfgAnalysis.computeMaxStackSizeFromComputedFrames();
         computedMaxStack = visitAllFramesToBeStoredInStackMap(computedMaxStack);

         exceptionHandling.countNumberOfHandlers();
      }
      else {
         exceptionHandling.completeControlFlowGraphWithExceptionHandlerBlocks();
         cfgAnalysis.completeControlFlowGraphWithRETSuccessors();

         computedMaxStack = cfgAnalysis.computeMaxStackSize();
         computedMaxStack = Math.max(maxStack, computedMaxStack);
      }

      frameAndStack.setMaxStack(computedMaxStack);
   }

   // Visits all the frames that must be stored in the stack map.
   private int visitAllFramesToBeStoredInStackMap(int max) {
      Label label = cfgAnalysis.getLabelForFirstBasicBlock();
      Frame frame;

      while (label != null) {
         frame = label.frame;

         if (label.isStoringFrame()) {
            frameAndStack.visitFrame(frame);
         }

         if (!label.isReachable()) {
            // Finds start and end of dead basic block.
            Label k = label.successor;
            int start = label.position;
            int end = (k == null ? code.length : k.position) - 1;

            // If non empty basic block.
            if (end >= start) {
               max = Math.max(max, 1);

               // Replaces instructions with NOP ... NOP ATHROW.
               for (int i = start; i < end; ++i) {
                  code.data[i] = NOP;
               }

               code.data[end] = (byte) ATHROW;

               frameAndStack.emitFrameForUnreachableBlock(start);
               exceptionHandling.removeStartEndRange(label, k);
            }
         }

         label = label.successor;
      }

      return max;
   }

   // ------------------------------------------------------------------------
   // Utility methods: dump bytecode array
   // ------------------------------------------------------------------------

   /**
    * Returns the size of the bytecode of this method.
    */
   int getSize() {
      if (classReaderOffset != 0) {
         return 6 + classReaderLength;
      }

      int size = 8;
      int codeLength = code.length;

      if (codeLength > 0) {
         if (codeLength > 65536) {
            throw new RuntimeException("Method code too large!");
         }

         cw.newUTF8("Code");

         size += 18 + codeLength + exceptionHandling.getSize();
         size += localVariables.getSizeWhileAddingConstantPoolItems();
         size += lineNumbers.getSizeWhileAddingConstantPoolItem();
         size += frameAndStack.getSizeWhileAddingConstantPoolItem();
      }

      size += throwsClause.getSize();

      if (cw.isSynthetic(access)) {
         cw.newUTF8("Synthetic");
         size += 6;
      }

      if (Access.isDeprecated(access)) {
         cw.newUTF8("Deprecated");
         size += 6;
      }

      if (signature != null) {
         cw.newUTF8("Signature");
         cw.newUTF8(signature);
         size += 8;
      }

      if (annotationDefault != null) {
         cw.newUTF8("AnnotationDefault");
         size += 6 + annotationDefault.length;
      }

      size += getAnnotationsSize(cw);
      size += getSizeOfParameterAnnotations();

      return size;
   }

   private int getSizeOfParameterAnnotations() {
      int size = 0;

      if (parameterAnnotations != null) {
         cw.newUTF8("RuntimeVisibleParameterAnnotations");

         int n = parameterAnnotations.length;
         size += 7 + 2 * n;

         for (int i = n - 1; i >= 0; --i) {
            AnnotationWriter parameterAnnotation = parameterAnnotations[i];
            size += parameterAnnotation == null ? 0 : parameterAnnotation.getSize();
         }
      }

      return size;
   }

   /**
    * Puts the bytecode of this method in the given byte vector.
    *
    * @param out the byte vector into which the bytecode of this method must be copied.
    */
   void put(ByteVector out) {
      int accessFlag = Access.computeFlag(access, Access.CONSTRUCTOR);
      out.putShort(accessFlag);

      out.putShort(name);
      out.putShort(desc);

      if (classReaderOffset != 0) {
         out.putByteArray(cw.cr.b, classReaderOffset, classReaderLength);
         return;
      }

      int attributeCount = 0;

      if (code.length > 0) {
         attributeCount++;
      }

      if (throwsClause.hasExceptions()) {
         attributeCount++;
      }

      boolean synthetic = cw.isSynthetic(access);

      if (synthetic) {
         attributeCount++;
      }

      boolean deprecated = Access.isDeprecated(access);

      if (deprecated) {
         attributeCount++;
      }

      if (signature != null) {
         attributeCount++;
      }

      if (annotationDefault != null) {
         attributeCount++;
      }

      if (annotations != null) {
         attributeCount++;
      }

      if (parameterAnnotations != null) {
         attributeCount++;
      }

      out.putShort(attributeCount);

      if (code.length > 0) {
         int size = 12 + code.length + exceptionHandling.getSize();
         size += localVariables.getSize();
         size += lineNumbers.getSize();
         size += frameAndStack.getSize();

         out.putShort(cw.newUTF8("Code")).putInt(size);
         frameAndStack.putMaxStackAndLocals(out);
         out.putInt(code.length).putByteVector(code);
         exceptionHandling.put(out);

         attributeCount = localVariables.getAttributeCount();

         if (lineNumbers.hasLineNumbers()) {
            attributeCount++;
         }

         if (frameAndStack.hasStackMap()) {
            attributeCount++;
         }

         out.putShort(attributeCount);
         localVariables.put(out);
         lineNumbers.put(out);
         frameAndStack.put(out);
      }

      throwsClause.put(out);

      if (synthetic) {
         out.putShort(cw.newUTF8("Synthetic")).putInt(0);
      }

      if (deprecated) {
         out.putShort(cw.newUTF8("Deprecated")).putInt(0);
      }

      if (signature != null) {
         out.putShort(cw.newUTF8("Signature")).putInt(2).putShort(cw.newUTF8(signature));
      }

      putAnnotationAttributes(out);
   }

   private void putAnnotationAttributes(ByteVector out) {
      if (annotationDefault != null) {
         out.putShort(cw.newUTF8("AnnotationDefault"));
         out.putInt(annotationDefault.length);
         out.putByteVector(annotationDefault);
      }

      putAnnotations(out, cw);

      if (parameterAnnotations != null) {
         out.putShort(cw.newUTF8("RuntimeVisibleParameterAnnotations"));
         AnnotationWriter.put(parameterAnnotations, out);
      }
   }

   public Label getCurrentBlock() { return cfgAnalysis.getLabelForCurrentBasicBlock(); }
}
