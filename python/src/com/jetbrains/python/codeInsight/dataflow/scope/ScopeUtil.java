package com.jetbrains.python.codeInsight.dataflow.scope;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyExceptPartNavigator;
import com.jetbrains.python.psi.impl.PyForStatementNavigator;
import com.jetbrains.python.psi.impl.PyListCompExpressionNavigator;
import org.jetbrains.annotations.Nullable;

/**
 * @author oleg
 */
public class ScopeUtil {
  private ScopeUtil() {
  }

  @Nullable
  public static PsiElement getParameterScope(final PsiElement element){
    if (element instanceof PyNamedParameter){
      final PyFunction function = PsiTreeUtil.getParentOfType(element, PyFunction.class, false);
      if (function != null){
        return function;
      }
    }

    final PyExceptPart exceptPart = PyExceptPartNavigator.getPyExceptPartByTarget(element);
    if (exceptPart != null){
      return exceptPart;
    }

    final PyForStatement forStatement = PyForStatementNavigator.getPyForStatementByIterable(element);
    if (forStatement != null){
      return forStatement;
    }

    final PyListCompExpression listCompExpression = PyListCompExpressionNavigator.getPyListCompExpressionByVariable(element);
    if (listCompExpression != null){
      return listCompExpression;
    }
    return null;
  }

  @Nullable
  public static ScopeOwner getDeclarationScopeOwner(PyReferenceExpression node) {
    final String name = node.getName();
    if (name != null) {
      ScopeOwner owner = PsiTreeUtil.getParentOfType(node, ScopeOwner.class);
      while (owner != null) {
        Scope scope = ControlFlowCache.getScope(owner);
        if (scope.containsDeclaration(name)) {
          return owner;
        }
        owner = PsiTreeUtil.getParentOfType(owner, ScopeOwner.class);
      }
    }
    return null;
  }
}
