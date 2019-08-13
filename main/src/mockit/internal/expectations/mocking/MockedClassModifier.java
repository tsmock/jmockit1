/*
 * Copyright (c) 2006 JMockit developers
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.mocking;

import java.util.*;
import javax.annotation.*;
import static java.lang.reflect.Modifier.*;

import mockit.asm.classes.*;
import mockit.asm.controlFlow.*;
import mockit.asm.methods.*;
import mockit.asm.types.*;
import mockit.internal.*;
import mockit.internal.expectations.*;
import mockit.internal.util.*;
import static mockit.asm.jvmConstants.Access.SYNTHETIC;
import static mockit.asm.jvmConstants.Access.ENUM;
import static mockit.asm.jvmConstants.Opcodes.*;
import static mockit.internal.MockingFilters.*;
import static mockit.internal.util.ObjectMethods.isMethodFromObject;
import static mockit.internal.util.Utilities.*;

final class MockedClassModifier extends BaseClassModifier
{
   private static final boolean NATIVE_UNSUPPORTED = !HOTSPOT_VM;
   private static final int METHOD_ACCESS_MASK = PRIVATE + SYNTHETIC + ABSTRACT;
   private static final int PUBLIC_OR_PROTECTED = PUBLIC + PROTECTED;

   @Nullable private final MockedType mockedType;
   private final boolean classFromNonBootstrapClassLoader;
   private String className;
   @Nullable private String baseClassNameForCapturedInstanceMethods;
   @Nonnull private ExecutionMode executionMode;
   private boolean isProxy;
   @Nullable private String defaultFilters;
   @Nullable List<String> enumSubclasses;

   MockedClassModifier(@Nullable ClassLoader classLoader, @Nonnull ClassReader classReader, @Nullable MockedType typeMetadata) {
      super(classReader);
      mockedType = typeMetadata;
      classFromNonBootstrapClassLoader = classLoader != null;
      setUseClassLoadingBridge(classLoader);
      executionMode = typeMetadata != null && typeMetadata.injectable ? ExecutionMode.PerInstance : ExecutionMode.Regular;
   }

   void useDynamicMocking() {
      executionMode = ExecutionMode.Partial;
   }

   void setClassNameForCapturedInstanceMethods(@Nonnull String internalClassName) {
      baseClassNameForCapturedInstanceMethods = internalClassName;
   }

   @Override
   public void visit(int version, int access, @Nonnull String name, @Nonnull ClassInfo additionalInfo) {
      validateMockingOfJREClass(name);

      super.visit(version, access, name, additionalInfo);
      isProxy = "java/lang/reflect/Proxy".equals(additionalInfo.superName);

      if (isProxy) {
         className = additionalInfo.interfaces[0];
         defaultFilters = null;
      }
      else {
         className = name;
         defaultFilters = filtersForClass(name);

         if (defaultFilters != null && defaultFilters.isEmpty()) {
            throw VisitInterruptedException.INSTANCE;
         }
      }
   }

   private void validateMockingOfJREClass(@Nonnull String internalName) {
      if (internalName.startsWith("java/")) {
         validateAsMockable(internalName);

         if (executionMode == ExecutionMode.Regular && mockedType != null && isFullMockingDisallowed(internalName)) {
            String modifyingClassName = internalName.replace('/', '.');

            if (modifyingClassName.equals(mockedType.getClassType().getName())) {
               throw new IllegalArgumentException(
                  "Class " + internalName.replace('/', '.') + " cannot be @Mocked fully; instead, use @Injectable or partial mocking");
            }
         }
      }
   }

   @Override
   public void visitInnerClass(@Nonnull String name, @Nullable String outerName, @Nullable String innerName, int access) {
      cw.visitInnerClass(name, outerName, innerName, access);

      // The second condition is for classes compiled with Java 8 or older, which had a bug (as an anonymous class can never be static).
      if (access == ENUM + FINAL || access == ENUM + STATIC) {
         if (enumSubclasses == null) {
            enumSubclasses = new ArrayList<>();
         }

         enumSubclasses.add(name);
      }
   }

   @Nullable @Override
   public MethodVisitor visitMethod(
      int access, @Nonnull String name, @Nonnull String desc, @Nullable String signature, @Nullable String[] exceptions
   ) {
      if ((access & METHOD_ACCESS_MASK) != 0) {
         return unmodifiedBytecode(access, name, desc, signature, exceptions);
      }

      boolean visitingConstructor = "<init>".equals(name);
      String internalClassName = className;

      if (visitingConstructor) {
         if (isConstructorNotAllowedByMockingFilters(name)) {
            return unmodifiedBytecode(access, name, desc, signature, exceptions);
         }

         startModifiedMethodVersion(access, name, desc, signature, exceptions);
         generateCallToSuperConstructor();
      }
      else {
         if (isMethodNotToBeMocked(access, name, desc)) {
            return unmodifiedBytecode(access, name, desc, signature, exceptions);
         }

         if (stubOutFinalizeMethod(access, name, desc)) {
            return null;
         }

         if (isMethodNotAllowedByMockingFilters(access, name)) {
            return unmodifiedBytecode(access, name, desc, signature, exceptions);
         }

         startModifiedMethodVersion(access, name, desc, signature, exceptions);

         if (baseClassNameForCapturedInstanceMethods != null) {
            internalClassName = baseClassNameForCapturedInstanceMethods;
         }
      }

      if (useClassLoadingBridge) {
         return generateCallToHandlerThroughMockingBridge(signature, internalClassName, visitingConstructor);
      }

      generateDirectCallToHandler(internalClassName, access, name, desc, signature, executionMode);
      generateDecisionBetweenReturningOrContinuingToRealImplementation();

      // Constructors of non-JRE classes can't be modified (unless running with "-noverify") in a way that
      // "super(...)/this(...)" get called twice, so we disregard such calls when copying the original bytecode.
      return copyOriginalImplementationCode(visitingConstructor);
   }

   @Nonnull
   private MethodVisitor unmodifiedBytecode(
      int access, @Nonnull String name, @Nonnull String desc, @Nullable String signature, @Nullable String[] exceptions
   ) {
      return cw.visitMethod(access, name, desc, signature, exceptions);
   }

   private boolean isConstructorNotAllowedByMockingFilters(@Nonnull String name) {
      return isProxy || executionMode != ExecutionMode.Regular || isUnmockableInvocation(defaultFilters, name);
   }

   private boolean isMethodNotToBeMocked(int access, @Nonnull String name, @Nonnull String desc) {
      return
         "<clinit>".equals(name) ||
         isNative(access) && (NATIVE_UNSUPPORTED || (access & PUBLIC_OR_PROTECTED) == 0) ||
         (isProxy || executionMode == ExecutionMode.Partial) && (
            isMethodFromObject(name, desc) || "annotationType".equals(name) && "()Ljava/lang/Class;".equals(desc)
         );
   }

   private boolean stubOutFinalizeMethod(int access, @Nonnull String name, @Nonnull String desc) {
      if ("finalize".equals(name) && "()V".equals(desc)) {
         startModifiedMethodVersion(access, name, desc, null, null);
         generateEmptyImplementation();
         return true;
      }

      return false;
   }

   private boolean isMethodNotAllowedByMockingFilters(int access, @Nonnull String name) {
      return
         baseClassNameForCapturedInstanceMethods != null && (access & STATIC) != 0 ||
         executionMode.isMethodToBeIgnored(access) ||
         isUnmockableInvocation(defaultFilters, name);
   }

   @Nonnull
   private MethodVisitor generateCallToHandlerThroughMockingBridge(
      @Nullable String genericSignature, @Nonnull String internalClassName, boolean visitingConstructor
   ) {
      generateCodeToObtainInstanceOfClassLoadingBridge(MockedBridge.MB);

      // First and second "invoke" arguments:
      boolean isStatic = generateCodeToPassThisOrNullIfStaticMethod();
      mw.visitInsn(ACONST_NULL);

      // Create array for call arguments (third "invoke" argument):
      JavaType[] argTypes = JavaType.getArgumentTypes(methodDesc);
      generateCodeToCreateArrayOfObject(6 + argTypes.length);

      int i = 0;
      generateCodeToFillArrayElement(i++, methodAccess);
      generateCodeToFillArrayElement(i++, internalClassName);
      generateCodeToFillArrayElement(i++, methodName);
      generateCodeToFillArrayElement(i++, methodDesc);
      generateCodeToFillArrayElement(i++, genericSignature);
      generateCodeToFillArrayElement(i++, executionMode.ordinal());

      generateCodeToFillArrayWithParameterValues(argTypes, i, isStatic ? 0 : 1);
      generateCallToInvocationHandler();

      generateDecisionBetweenReturningOrContinuingToRealImplementation();

      // Copies the entire original implementation even for a constructor, in which case the complete bytecode inside
      // the constructor fails the strict verification activated by "-Xfuture". However, this is necessary to allow the
      // full execution of a bootstrap class constructor when the call was not meant to be mocked.
      return copyOriginalImplementationCode(visitingConstructor && classFromNonBootstrapClassLoader);
   }

   private void generateDecisionBetweenReturningOrContinuingToRealImplementation() {
      Label startOfRealImplementation = new Label();
      mw.visitInsn(DUP);
      mw.visitLdcInsn(VOID_TYPE);
      mw.visitJumpInsn(IF_ACMPEQ, startOfRealImplementation);
      generateReturnWithObjectAtTopOfTheStack(methodDesc);
      mw.visitLabel(startOfRealImplementation);
      mw.visitInsn(POP);
   }
}