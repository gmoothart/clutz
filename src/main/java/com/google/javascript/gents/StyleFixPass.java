package com.google.javascript.gents;

import static com.google.javascript.rhino.TypeDeclarationsIR.anyType;
import static com.google.javascript.rhino.TypeDeclarationsIR.arrayType;

import com.google.common.collect.Iterables;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Node.TypeDeclarationNode;
import com.google.javascript.rhino.Token;

/**
 * Fixes the style of the final TypeScript code to be more idiomatic.
 */
public final class StyleFixPass extends AbstractPostOrderCallback implements CompilerPass {

  private final AbstractCompiler compiler;

  public StyleFixPass(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, this);
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getType()) {
      // Var is converted to let
      // This is to output more idiomatic TypeScript even if it slightly changes the semantics
      // of the original code.
      case VAR:
        n.setToken(Token.LET);
        // fall through
      case LET:
        if (hasGrandchildren(n)) {
          Node rhs = n.getFirstFirstChild();
          // ONLY convert classes (not functions) for var and let
          if (rhs.isClass()) {
            liftClassOrFunctionDefinition(n);
          }
        }
        break;
      case CONST:
        if (hasGrandchildren(n)) {
          Node rhs = n.getFirstFirstChild();
          if (rhs.isClass()) {
            liftClassOrFunctionDefinition(n);
          } else if (rhs.isFunction()) {
            // Convert const functions only
            TypeDeclarationNode type = n.getFirstChild().getDeclaredTypeExpression();
            // Untyped declarations are lifted
            if (type == null) {
              liftClassOrFunctionDefinition(n);
              break;
            }
            // Declarations that have invalid typings are ignored
            int numParams = Iterables.size(rhs.getSecondChild().children());
            if (numParams != Iterables.size(type.children()) - 1) {
              break;
            }

            // Annotate constant function return type and parameters
            Node newNode = type.getFirstChild();
            Node nextNode = newNode.getNext();
            newNode.detachFromParent();

            rhs.setDeclaredTypeExpression((TypeDeclarationNode) newNode);
            for (Node param : rhs.getSecondChild().children()) {
              // Replace params with their corresponding type
              newNode = nextNode;
              nextNode = nextNode.getNext();
              newNode.detachFromParent();

              if (newNode.isRest()) {
                newNode.getFirstChild().setString(param.getString());
                // Rest without types are automatically declared to be any[]
                if (newNode.getDeclaredTypeExpression() == null) {
                  newNode.setDeclaredTypeExpression(arrayType(anyType()));
                }
              } else {
                newNode.setString(param.getString());
              }
              param.getParent().replaceChild(param, newNode);
            }
            n.getFirstChild().setDeclaredTypeExpression(null);

            liftClassOrFunctionDefinition(n);
          }
        }
        break;
      default:
        break;
    }
  }

  /** Returns if a node has grandchildren */
  private boolean hasGrandchildren(Node n) {
    return n.hasChildren() && n.getFirstChild().hasChildren();
  }

  /**
   * Attempts to lift class or functions declarations of the form
   * 'var/let/const x = class/function {...}'
   * into
   * 'class/function x {...}'
   */
  void liftClassOrFunctionDefinition(Node n) {
    Node rhs = n.getFirstFirstChild();
    Node oldName = rhs.getFirstChild();
    Node newName = n.getFirstChild();

    // Replace name node with declared name
    rhs.detachFromParent();
    newName.detachFromParent();
    rhs.replaceChild(oldName, newName);

    n.getParent().replaceChild(n, rhs);
  }

}
