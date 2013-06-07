/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.spi.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.ClassUtil;
import com.intellij.spi.SPIFileType;
import com.intellij.lang.spi.SPILanguage;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User: anna
 */
public class SPIFile extends PsiFileBase {
  public SPIFile(@NotNull FileViewProvider viewProvider) {
    super(viewProvider, SPILanguage.INSTANCE);
  }

  @Override
  public PsiReference getReference() {
    return new SPIFileName2ClassReference(this, ApplicationManager.getApplication().runReadAction(new Computable<PsiClass>() {
      @Override
      public PsiClass compute() {
        return ClassUtil.findPsiClass(getManager(), getName(), null, true, getResolveScope()); 
      }
    }));
  }

  @NotNull
  @Override
  public FileType getFileType() {
    return SPIFileType.INSTANCE;
  }
  
  private static class SPIFileName2ClassReference extends PsiReferenceBase<PsiFile> {
    private final PsiClass myClass;

    public SPIFileName2ClassReference(PsiFile file, PsiClass aClass) {
      super(file, new TextRange(0, 0), false);
      myClass = aClass;
    }

    @Nullable
    @Override
    public PsiElement resolve() {
      return myClass;
    }

    @Override
    public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
      final String className = ClassUtil.getJVMClassName(myClass);
      if (className != null) {
        final String newFileName = className.substring(0, className.lastIndexOf(myClass.getName())) + newElementName;
        return getElement().setName(newFileName);
      }
      return getElement();
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
      if (element instanceof PsiClass) {
        final String className = ClassUtil.getJVMClassName((PsiClass)element);
        if (className != null) {
          return getElement().setName(className);
        }
      }
      return getElement();
    }

    @NotNull
    @Override
    public Object[] getVariants() {
      return ArrayUtil.EMPTY_OBJECT_ARRAY;
    }
  }
}
