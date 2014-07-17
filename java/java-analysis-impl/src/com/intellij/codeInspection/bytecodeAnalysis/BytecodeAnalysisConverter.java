/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.bytecodeAnalysis;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.io.*;
import gnu.trove.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.org.objectweb.asm.Type;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.intellij.codeInspection.bytecodeAnalysis.ProjectBytecodeAnalysis.LOG;

/**
 * @author lambdamix
 */
public class BytecodeAnalysisConverter implements ApplicationComponent {

  private static final String LOGIC_VERSION_KEY = "BytecodeAnalysisConverter.Logic";
  private static final int LOGIC_VERSION = 1;
  private static final String ENUMERATORS_VERSION_KEY = "BytecodeAnalysisConverter.Enumerators";

  public static final int SHIFT = 4096;

  public static BytecodeAnalysisConverter getInstance() {
    return ApplicationManager.getApplication().getComponent(BytecodeAnalysisConverter.class);
  }

  private PersistentStringEnumerator myNamesEnumerator;
  private PersistentEnumeratorDelegate<int[]> myCompoundKeyEnumerator;
  private int version;

  @Override
  public void initComponent() {

    final File keysDir = new File(PathManager.getIndexRoot(), "bytecodekeys");
    final File namesFile = new File(keysDir, "names");
    final File compoundKeysFile = new File(keysDir, "compound");

    final int previousLogicVersion = PropertiesComponent.getInstance().getOrInitInt(LOGIC_VERSION_KEY, 0);
    version = PropertiesComponent.getInstance().getOrInitInt(ENUMERATORS_VERSION_KEY, 0);

    if (previousLogicVersion != LOGIC_VERSION) {
      IOUtil.deleteAllFilesStartingWith(keysDir);
      version++;
    }

    try {
      IOUtil.openCleanOrResetBroken(new ThrowableComputable<Void, IOException>() {
        @Override
        public Void compute() throws IOException {
          myNamesEnumerator = new PersistentStringEnumerator(namesFile, true);
          myCompoundKeyEnumerator = new IntArrayPersistentEnumerator(compoundKeysFile, new IntArrayKeyDescriptor());
          return null;
        }
      }, new Runnable() {
        @Override
        public void run() {
          LOG.info("Error during initialization of enumerators in bytecode analysis. Re-initializing.");
          IOUtil.deleteAllFilesStartingWith(keysDir);
          version++;
        }
      });
    }
    catch (IOException e) {
      LOG.error("Re-initialization of enumerators in bytecode analysis failed.", e);
    }
    PropertiesComponent.getInstance().setValue(ENUMERATORS_VERSION_KEY, String.valueOf(version));
    PropertiesComponent.getInstance().setValue(LOGIC_VERSION_KEY, String.valueOf(LOGIC_VERSION));
  }

  @Override
  public void disposeComponent() {
    try {
      myNamesEnumerator.close();
      myCompoundKeyEnumerator.close();
    }
    catch (IOException e) {
      LOG.debug(e);
    }
  }

  @NotNull
  @Override
  public String getComponentName() {
    return "BytecodeAnalysisConverter";
  }

  IdEquation convert(Equation<Key, Value> equation) throws IOException {
    ProgressManager.checkCanceled();

    Result<Key, Value> rhs = equation.rhs;
    IdResult result;
    if (rhs instanceof Final) {
      result = new IdFinal(((Final<Key, Value>)rhs).value);
    } else {
      Pending<Key, Value> pending = (Pending<Key, Value>)rhs;
      Set<Product<Key, Value>> sumOrigin = pending.sum;
      IntIdComponent[] components = new IntIdComponent[sumOrigin.size()];
      int componentI = 0;
      for (Product<Key, Value> prod : sumOrigin) {
        long[] intProd = new long[prod.ids.size()];
        int idI = 0;
        for (Key id : prod.ids) {
          long rawId = mkAsmKey(id);
          if (rawId <= 0) {
            LOG.error("raw key should be positive. rawId = " + rawId);
          }
          intProd[idI] = id.stable ? rawId : -rawId;
          idI++;
        }
        IntIdComponent intIdComponent = new IntIdComponent(prod.value, intProd);
        components[componentI] = intIdComponent;
        componentI++;
      }
      result = new IdPending(components);
    }

    long rawKey = mkAsmKey(equation.id);
    if (rawKey <= 0) {
      LOG.error("raw key should be positive. rawKey = " + rawKey);
    }

    long key = equation.id.stable ? rawKey : -rawKey;
    return new IdEquation(key, result);
  }

  public long mkAsmKey(@NotNull Key key) throws IOException {
    long baseKey = mkAsmSignatureKey(key.method);
    long directionKey = mkDirectionKey(key.direction);
    return baseKey * SHIFT + directionKey;
  }

  private static int mkDirectionKey(Direction dir) throws IOException {
    if (dir instanceof Out) {
      return 0;
    } else if (dir instanceof In) {
      In in = (In)dir;
      return 8 * in.paramId() + 1;
    } else {
      InOut inOut = (InOut)dir;
      return 8 * inOut.paramId() + 2 + inOut.valueId();
    }
  }

  @NotNull
  private static Direction extractDirection(int directionKey) {
    if (directionKey == 0) {
      return new Out();
    }
    else {
      int paramId = directionKey / 8;
      int subDirection = directionKey % 8;
      if (subDirection == 1) {
        return new In(paramId);
      }
      else {
        return new InOut(paramId, Value.values()[subDirection - 2]);
      }
    }
  }

  // class + short signature
  private int mkAsmSignatureKey(@NotNull Method method) throws IOException {
    int[] sigKey = new int[2];
    sigKey[0] = mkAsmTypeKey(Type.getObjectType(method.internalClassName));
    sigKey[1] = mkAsmShortSignatureKey(method);
    return myCompoundKeyEnumerator.enumerate(sigKey);
  }

  private int mkAsmShortSignatureKey(@NotNull Method method) throws IOException {
    Type[] argTypes = Type.getArgumentTypes(method.methodDesc);
    int arity = argTypes.length;
    int[] sigKey = new int[2 + arity];
    sigKey[0] = mkAsmTypeKey(Type.getReturnType(method.methodDesc));
    sigKey[1] = myNamesEnumerator.enumerate(method.methodName);
    for (int i = 0; i < argTypes.length; i++) {
      sigKey[2 + i] = mkAsmTypeKey(argTypes[i]);
    }
    return myCompoundKeyEnumerator.enumerate(sigKey);
  }

  private int mkAsmTypeKey(Type type) throws IOException {
    String className = type.getClassName();
    int dotIndex = className.lastIndexOf('.');
    String packageName;
    String simpleName;
    if (dotIndex > 0) {
      packageName = className.substring(0, dotIndex);
      simpleName = className.substring(dotIndex + 1);
    } else {
      packageName = "";
      simpleName = className;
    }
    int[] classKey = new int[]{myNamesEnumerator.enumerate(packageName), myNamesEnumerator.enumerate(simpleName)};
    return myCompoundKeyEnumerator.enumerate(classKey);
  }

  public long mkPsiKey(@NotNull PsiMethod psiMethod, Direction direction) throws IOException {
    final PsiClass psiClass = PsiTreeUtil.getParentOfType(psiMethod, PsiClass.class, false);
    if (psiClass == null) {
      LOG.debug("PsiClass was null for " + psiMethod.getName());
      return -1;
    }
    long sigKey = mkPsiSignatureKey(psiMethod);
    if (sigKey == -1) {
      return -1;
    }
    long directionKey = mkDirectionKey(direction);
    return sigKey * SHIFT + directionKey;
  }

  private int mkPsiSignatureKey(@NotNull PsiMethod psiMethod) throws IOException {
    final PsiClass psiClass = PsiTreeUtil.getParentOfType(psiMethod, PsiClass.class, false);
    if (psiClass == null) {
      LOG.debug("PsiClass was null for " + psiMethod.getName());
      return -1;
    }
    PsiClass outerClass = psiClass.getContainingClass();
    boolean isInnerClassConstructor = psiMethod.isConstructor() && (outerClass != null) && !psiClass.hasModifierProperty(PsiModifier.STATIC);
    PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
    PsiType returnType = psiMethod.getReturnType();

    final int shift = isInnerClassConstructor ? 1 : 0;
    final int arity = parameters.length + shift;
    int[] shortSigKey = new int[2 + arity];
    if (returnType == null) {
      shortSigKey[0] = mkPsiTypeKey(PsiType.VOID);
      shortSigKey[1] = myNamesEnumerator.enumerate("<init>");
    } else {
      shortSigKey[0] = mkPsiTypeKey(returnType);
      shortSigKey[1] = myNamesEnumerator.enumerate(psiMethod.getName());
    }
    if (isInnerClassConstructor) {
      shortSigKey[2] = mkPsiClassKey(outerClass, 0);
    }
    for (int i = 0; i < parameters.length; i++) {
      PsiParameter parameter = parameters[i];
      shortSigKey[2 + i + shift] = mkPsiTypeKey(parameter.getType());
    }
    for (int aShortSigKey : shortSigKey) {
      if (aShortSigKey == -1) {
        return -1;
      }
    }

    int[] sigKey = new int[2];
    int classKey = mkPsiClassKey(psiClass, 0);
    if (classKey == -1) {
      return -1;
    }
    sigKey[0] = classKey;
    sigKey[1] = myCompoundKeyEnumerator.enumerate(shortSigKey);

    return myCompoundKeyEnumerator.enumerate(sigKey);
  }

  public TLongArrayList mkInOutKeys(@NotNull PsiMethod psiMethod, long primaryKey) throws IOException {
    PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
    TLongArrayList keys = new TLongArrayList(parameters.length * 2 + 1);
    for (int i = 0; i < parameters.length; i++) {
      PsiParameter parameter = parameters[i];
      PsiType parameterType = parameter.getType();
      if (parameterType instanceof PsiPrimitiveType) {
        if (PsiType.BOOLEAN.equals(parameterType)) {
          keys.add(primaryKey + mkDirectionKey(new InOut(i, Value.False)));
          keys.add(primaryKey + mkDirectionKey(new InOut(i, Value.True)));
        }
      } else {
        keys.add(primaryKey + mkDirectionKey(new InOut(i, Value.NotNull)));
        keys.add(primaryKey + mkDirectionKey(new InOut(i, Value.Null)));
      }
    }
    return keys;
  }


  private int mkPsiClassKey(PsiClass psiClass, int dimensions) throws IOException {
    PsiFile containingFile = psiClass.getContainingFile();
    if (!(containingFile instanceof PsiClassOwner)) {
      LOG.debug("containingFile was not resolved for " + psiClass.getQualifiedName());
      return -1;
    }
    PsiClassOwner psiFile = (PsiClassOwner)containingFile;
    String packageName = psiFile.getPackageName();
    String qname = psiClass.getQualifiedName();
    if (qname == null) {
      return -1;
    }
    String className = qname;
    if (packageName.length() > 0) {
      className = qname.substring(packageName.length() + 1).replace('.', '$');
    }
    int[] classKey = new int[2];
    classKey[0] = myNamesEnumerator.enumerate(packageName);
    if (dimensions == 0) {
      classKey[1] = myNamesEnumerator.enumerate(className);
    } else {
      StringBuilder sb = new StringBuilder(className);
      for (int j = 0; j < dimensions; j++) {
        sb.append("[]");
      }
      classKey[1] = myNamesEnumerator.enumerate(sb.toString());
    }
    return myCompoundKeyEnumerator.enumerate(classKey);
  }

  private int mkPsiTypeKey(PsiType psiType) throws IOException {
    int dimensions = 0;
    psiType = TypeConversionUtil.erasure(psiType);
    if (psiType instanceof PsiArrayType) {
      PsiArrayType arrayType = (PsiArrayType)psiType;
      psiType = arrayType.getDeepComponentType();
      dimensions = arrayType.getArrayDimensions();
    }

    if (psiType instanceof PsiClassType) {
      // no resolve() -> no package/class split
      PsiClass psiClass = ((PsiClassType)psiType).resolve();
      if (psiClass != null) {
        return mkPsiClassKey(psiClass, dimensions);
      }
      else {
        LOG.debug("resolve was null for " + ((PsiClassType)psiType).getClassName());
        return -1;
      }
    }
    else if (psiType instanceof PsiPrimitiveType) {
      String packageName = "";
      String className = psiType.getPresentableText();
      int[] classKey = new int[2];
      classKey[0] = myNamesEnumerator.enumerate(packageName);
      if (dimensions == 0) {
        classKey[1] = myNamesEnumerator.enumerate(className);
      } else {
        StringBuilder sb = new StringBuilder(className);
        for (int j = 0; j < dimensions; j++) {
          sb.append("[]");
        }
        classKey[1] = myNamesEnumerator.enumerate(sb.toString());
      }
      return myCompoundKeyEnumerator.enumerate(classKey);
    }
    return -1;
  }

  public void addMethodAnnotations(TLongObjectHashMap<Value> internalIdSolutions, Annotations annotations, long methodKey, int arity) {

    List<String> clauses = new ArrayList<String>();
    TLongObjectIterator<Value> solutionsIterator = internalIdSolutions.iterator();

    TLongHashSet notNulls = annotations.notNulls;
    TLongObjectHashMap<String> contracts = annotations.contracts;
    for (int i = internalIdSolutions.size(); i-- > 0;) {
      solutionsIterator.advance();
      long key = Math.abs(solutionsIterator.key());
      Value value = solutionsIterator.value();
      if (value == Value.Top || value == Value.Bot) {
        continue;
      }
      Direction direction = extractDirection((int)(key % SHIFT));
      if (value == Value.NotNull && direction instanceof Out && key == methodKey) {
        notNulls.add(key);
      }
      else if (direction instanceof InOut) {
        long baseKey = key - (key % SHIFT);
        if (baseKey == methodKey) {
          clauses.add(contractElement(arity, (InOut)direction, value));
        }
      }
    }

    if (!notNulls.contains(methodKey) && !clauses.isEmpty()) {
      Collections.sort(clauses);
      StringBuilder sb = new StringBuilder("\"");
      StringUtil.join(clauses, ";", sb);
      sb.append('"');
      contracts.put(methodKey, sb.toString().intern());
    }
  }

  public void addParameterAnnotations(TLongObjectHashMap<Value> internalIdSolutions, Annotations annotations) {
    TLongObjectIterator<Value> solutionsIterator = internalIdSolutions.iterator();
    TLongHashSet notNulls = annotations.notNulls;
    for (int i = internalIdSolutions.size(); i-- > 0;) {
      solutionsIterator.advance();
      long key = Math.abs(solutionsIterator.key());
      Value value = solutionsIterator.value();
      if (value == Value.Top || value == Value.Bot) {
        continue;
      }
      Direction direction = extractDirection((int)(key % SHIFT));
      if (value == Value.NotNull && (direction instanceof In || direction instanceof Out)) {
        notNulls.add(key);
      }
    }
  }

  static String contractValueString(Value v) {
    switch (v) {
      case False: return "false";
      case True: return "true";
      case NotNull: return "!null";
      case Null: return "null";
      default: return "_";
    }
  }

  static String contractElement(int arity, InOut inOut, Value value) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < arity; i++) {
      Value currentValue = Value.Top;
      if (i == inOut.paramIndex) {
        currentValue = inOut.inValue;
      }
      if (i > 0) {
        sb.append(',');
      }
      sb.append(contractValueString(currentValue));
    }
    sb.append("->");
    sb.append(contractValueString(value));
    return sb.toString();
  }

  public int getVersion() {
    return version;
  }

  private static class IntArrayKeyDescriptor implements KeyDescriptor<int[]>, DifferentSerializableBytesImplyNonEqualityPolicy {

    @Override
    public void save(@NotNull DataOutput out, int[] value) throws IOException {
      DataInputOutputUtil.writeINT(out, value.length);
      for (int i : value) {
        DataInputOutputUtil.writeINT(out, i);
      }
    }

    @Override
    public int[] read(@NotNull DataInput in) throws IOException {
      int[] value = new int[DataInputOutputUtil.readINT(in)];
      for (int i = 0; i < value.length; i++) {
        value[i] = DataInputOutputUtil.readINT(in);
      }
      return value;
    }

    @Override
    public int getHashCode(int[] value) {
      return Arrays.hashCode(value);
    }

    @Override
    public boolean isEqual(int[] val1, int[] val2) {
      return Arrays.equals(val1, val2);
    }
  }

  private static class IntArrayPersistentEnumerator extends PersistentEnumeratorDelegate<int[]> {
    private final CachingEnumerator<int[]> myCache;

    public IntArrayPersistentEnumerator(File compoundKeysFile, IntArrayKeyDescriptor descriptor) throws IOException {
      super(compoundKeysFile, descriptor, 1024 * 4);
      myCache = new CachingEnumerator<int[]>(new DataEnumerator<int[]>() {
        @Override
        public int enumerate(@Nullable int[] value) throws IOException {
          return IntArrayPersistentEnumerator.super.enumerate(value);
        }

        @Nullable
        @Override
        public int[] valueOf(int idx) throws IOException {
          return IntArrayPersistentEnumerator.super.valueOf(idx);
        }
      }, descriptor);
    }

    @Override
    public int enumerate(@Nullable int[] value) throws IOException {
      return myCache.enumerate(value);
    }

    @Nullable
    @Override
    public int[] valueOf(int idx) throws IOException {
      return myCache.valueOf(idx);
    }
  }
}
