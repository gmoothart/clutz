package com.google.javascript.gents;

import com.google.javascript.jscomp.CodeConsumer;
import com.google.javascript.jscomp.CodeGenerator;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Code generator for gents to add TypeScript specific code generation.
 */
public class GentsCodeGenerator extends CodeGenerator {
  private final NodeComments nodeComments;

  protected GentsCodeGenerator(CodeConsumer consumer, CompilerOptions options,
      NodeComments nodeComments) {
    super(consumer, options);
    this.nodeComments = nodeComments;
  }

  @Override
  protected void add(Node n, Context ctx) {
    String comment = nodeComments.getComment(n);
    if (comment != null) {
      add(comment);
      // temporary new line
      add("\n");
    }

    // Pre-codegen modifications go here (i.e. syntax not compatible with jscompiler)
    switch (n.getType()) {
      case CAST:
        add("(");
        add(n.getFirstChild());
        add(" as ");
        add(n.getDeclaredTypeExpression());
        add(")");
        break;
    }

    if (maybeOverrideCodeGen(n, ctx)) {
      return;
    }
    super.add(n, ctx);

    // Post-codegen modifications go here (i.e. adding to the end of a line)
    switch (n.getType()) {
      case MEMBER_VARIABLE_DEF:
        // Default field values
        if (n.hasChildren()) {
          add(" = ");
          add(n.getLastChild());
        }
        break;
      case NEW:
        // The Closure Compiler code generator drops off the extra () for new statements.
        // We add them back in to maintain a consistent style.
        if (n.hasOneChild()) {
          add("()");
        }
        break;
      default:
        break;
    }
  }

  /**
   * Attempts to seize control of code generation if necessary.
   * @return true if no further code generation on this node is needed.
   */
  boolean maybeOverrideCodeGen(Node n, Context ctx) {
    if (n.getType().equals(Token.UNDEFINED_TYPE)) {
      add("undefined");
      return true;
    }
    if (n.getType() == Token.CAST) {
      return true;
    }
    return false;
  }
}
