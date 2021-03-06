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
package org.jetbrains.plugins.groovy.refactoring.introduce.constant;

import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.refactoring.HelpID;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.refactoring.util.RefactoringMessageUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.VisibilityUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariableDeclaration;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrEnumTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrEnumConstantList;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GroovyScriptClass;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;
import org.jetbrains.plugins.groovy.refactoring.GroovyRefactoringBundle;
import org.jetbrains.plugins.groovy.refactoring.GroovyRefactoringUtil;
import org.jetbrains.plugins.groovy.refactoring.introduce.GrIntroduceContext;
import org.jetbrains.plugins.groovy.refactoring.introduce.GrIntroduceHandlerBase;

import java.util.ArrayList;

import static org.jetbrains.plugins.groovy.refactoring.introduce.GrIntroduceHandlerBase.deleteLocalVar;
import static org.jetbrains.plugins.groovy.refactoring.introduce.GrIntroduceHandlerBase.processLiteral;

/**
 * @author Max Medvedev
 */
public class GrIntroduceConstantProcessor {
  private final GrIntroduceContext context;
  private final GrIntroduceConstantSettings settings;

  public GrIntroduceConstantProcessor(GrIntroduceContext context, GrIntroduceConstantSettings settings) {

    this.context = context;
    this.settings = settings;
  }

  @Nullable
  public GrField run() {
    final PsiClass targetClass = settings.getTargetClass();
    if (targetClass == null) return null;

    if (checkErrors(targetClass)) {
      return null;
    }

    final GrVariableDeclaration rawDeclaration = createField(targetClass);
    final GrVariableDeclaration declaration = addDeclaration(targetClass, rawDeclaration);
    final GrField field = (GrField)declaration.getVariables()[0];

    if (context.getVar() != null) {
      deleteLocalVar(context);
    }

    if (context.getStringPart() != null) {
      final GrExpression ref = processLiteral(field.getName(), context.getStringPart(), context.getProject());
      final PsiElement element = replaceOccurrence(field, ref, isEscalateVisibility());
      updateCaretPosition(element);
    }
    else {
      if (settings.replaceAllOccurrences()) {
        final PsiElement[] occurrences = context.getOccurrences();
        GroovyRefactoringUtil.sortOccurrences(occurrences);
        for (PsiElement occurrence : occurrences) {
          replaceOccurrence(field, occurrence, isEscalateVisibility());
        }
      }
      else {
        replaceOccurrence(field, context.getExpression(), isEscalateVisibility());
      }
    }
    return field;
  }

  private void updateCaretPosition(PsiElement element) {
    context.getEditor().getCaretModel().moveToOffset(element.getTextRange().getEndOffset());
    context.getEditor().getSelectionModel().removeSelection();
  }

  private static GrVariableDeclaration addDeclaration(PsiClass targetClass, GrVariableDeclaration declaration) {
    final GrVariableDeclaration added;
    if (targetClass instanceof GrEnumTypeDefinition) {
      final GrEnumConstantList enumConstants = ((GrEnumTypeDefinition)targetClass).getEnumConstantList();
      added = (GrVariableDeclaration)targetClass.addAfter(declaration, enumConstants);
    }
    else {
      added = ((GrVariableDeclaration)targetClass.add(declaration));
    }

    JavaCodeStyleManager.getInstance(added.getProject()).shortenClassReferences(added);
    return added;
  }

  private boolean checkErrors(@NotNull PsiClass targetClass) {
    String fieldName = settings.getName();
    String errorString = check(targetClass, fieldName);

    if (errorString != null) {
      String message = RefactoringBundle.getCannotRefactorMessage(errorString);
      CommonRefactoringUtil
        .showErrorMessage(GrIntroduceConstantHandler.REFACTORING_NAME, message, HelpID.INTRODUCE_CONSTANT, context.getProject());
      return true;
    }

    PsiField oldField = targetClass.findFieldByName(fieldName, true);
    if (oldField != null) {
      String message = RefactoringBundle.message("field.exists", fieldName, oldField.getContainingClass().getQualifiedName());
      int answer = Messages
        .showYesNoDialog(context.getProject(), message, GrIntroduceConstantHandler.REFACTORING_NAME, Messages.getWarningIcon());
      if (answer != 0) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private  String check(@NotNull PsiClass targetClass, @Nullable final String fieldName) {
    if (!GroovyFileType.GROOVY_LANGUAGE.equals(targetClass.getLanguage())) {
      return GroovyRefactoringBundle.message("class.language.is.not.groovy");
    }

    final JavaPsiFacade facade = JavaPsiFacade.getInstance(context.getProject());

    if (fieldName == null || fieldName.isEmpty()) {
      return RefactoringBundle.message("no.field.name.specified");
    }

    else if (!facade.getNameHelper().isIdentifier(fieldName)) {
      return RefactoringMessageUtil.getIncorrectIdentifierMessage(fieldName);
    }

    if (targetClass instanceof GroovyScriptClass) {
      return GroovyRefactoringBundle.message("target.class.must.not.be.script");
    }

    return null;
  }


  private PsiElement replaceOccurrence(@NotNull GrField field, @NotNull PsiElement occurrence, boolean escalateVisibility) {
    boolean isOriginal = occurrence == context.getExpression();
    final GrReferenceExpression newExpr = createRefExpression(field, occurrence);
    final PsiElement replaced = occurrence instanceof GrExpression
                                ? ((GrExpression)occurrence).replaceWithExpression(newExpr, false)
                                : occurrence.replace(newExpr);
    if (escalateVisibility) {
      PsiUtil.escalateVisibility(field, replaced);
    }
    if (replaced instanceof GrReferenceExpression) {
      org.jetbrains.plugins.groovy.codeStyle.GrReferenceAdjuster.shortenReference((GrReferenceExpression)replaced);
    }
    if (isOriginal) {
      updateCaretPosition(replaced);
    }
    return replaced;
  }

  @NotNull
  private static GrReferenceExpression createRefExpression(@NotNull GrField field, @NotNull PsiElement place) {
    final PsiClass containingClass = field.getContainingClass();
    assert containingClass != null;
    final String refText = containingClass.getQualifiedName() + "." + field.getName();
    return GroovyPsiElementFactory.getInstance(place.getProject()).createReferenceExpressionFromText(refText, place);
  }

  @NotNull
  private  GrVariableDeclaration createField(PsiClass targetClass) {
    final String name = settings.getName();
    final PsiType type = settings.getSelectedType();

    String[] modifiers = collectModifiers(targetClass);

    final GroovyPsiElementFactory factory = GroovyPsiElementFactory.getInstance(context.getProject());
    return factory.createFieldDeclaration(modifiers, name, getInitializer(), type);
  }

  @NotNull
  private GrExpression getInitializer() {
    final GrExpression expression = context.getExpression();
    if (expression != null) {
      return expression;
    }
    else {
      return GrIntroduceHandlerBase.generateExpressionFromStringPart(context.getStringPart(), context.getProject());
    }
  }

  @NotNull
  private String[] collectModifiers(PsiClass targetClass) {
    String modifier = isEscalateVisibility() ? PsiModifier.PRIVATE : settings.getVisibilityModifier();
    ArrayList<String> modifiers = new ArrayList<String>();
    if (modifier!= null && !PsiModifier.PACKAGE_LOCAL.equals(modifier)) {
      modifiers.add(modifier);
    }
    if (!targetClass.isInterface()) {
      modifiers.add(PsiModifier.STATIC);
      modifiers.add(PsiModifier.FINAL);
    }
    return ArrayUtil.toStringArray(modifiers);
  }

  private boolean isEscalateVisibility() {
    return VisibilityUtil.ESCALATE_VISIBILITY.equals(settings.getVisibilityModifier());
  }

}
