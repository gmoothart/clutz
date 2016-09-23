package com.google.javascript.gents;

import static com.google.javascript.rhino.TypeDeclarationsIR.anyType;
import static com.google.javascript.rhino.TypeDeclarationsIR.arrayType;
import static com.google.javascript.rhino.TypeDeclarationsIR.booleanType;
import static com.google.javascript.rhino.TypeDeclarationsIR.functionType;
import static com.google.javascript.rhino.TypeDeclarationsIR.namedType;
import static com.google.javascript.rhino.TypeDeclarationsIR.numberType;
import static com.google.javascript.rhino.TypeDeclarationsIR.parameterizedType;
import static com.google.javascript.rhino.TypeDeclarationsIR.recordType;
import static com.google.javascript.rhino.TypeDeclarationsIR.stringType;
import static com.google.javascript.rhino.TypeDeclarationsIR.undefinedType;
import static com.google.javascript.rhino.TypeDeclarationsIR.unionType;
import static com.google.javascript.rhino.TypeDeclarationsIR.voidType;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.javascript.gents.CollectModuleMetadata.FileModule;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfo.Visibility;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Node.TypeDeclarationNode;
import com.google.javascript.rhino.Token;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Converts JavaScript code into JavaScript code annotated with TypeScript {@code
 * TypeDeclarationNode} information provided by the corresponding jsdoc.
 *
 * This compiler pass is based off of the {@code JsdocToEs6TypedConverter} compiler pass.
 */
public final class TypeAnnotationPass implements CompilerPass {

  private final AbstractCompiler compiler;
  private final PathUtil pathUtil;
  private final NameUtil nameUtil;
  private final NodeComments nodeComments;

  // symbolName -> fileModule
  private final Map<String, FileModule> symbolToModule;
  // filename, namespace -> local name
  private final Table<String, String, String> typeRewrite;
  // filename -> extra imports needed to be added
  private final Multimap<String, Node> importsNeeded = LinkedHashMultimap.create();
  // extern -> typing map for when extern and TS typing names differ
  private Map<String, String> externsMap;

  public TypeAnnotationPass(AbstractCompiler compiler, PathUtil pathUtil, NameUtil nameUtil,
      Map<String, FileModule> symbolMap, Table<String, String, String> typeRewrite,
      NodeComments nodeComments, Map<String, String> externsMap) {
    this.compiler = compiler;
    this.pathUtil = pathUtil;
    this.nameUtil = nameUtil;
    this.nodeComments = nodeComments;

    this.symbolToModule = new HashMap<>(symbolMap);
    this.typeRewrite = HashBasedTable.create(typeRewrite);
    this.externsMap = externsMap;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, new TypeAnnotationConverter());
    NodeTraversal.traverseEs6(compiler, root, new AccessModifierConverter());
    for (Node script : root.children()) {
      addTypeOnlyImports(script);
    }
  }

  /** Annotates variables and functions with their corresponding TypeScript type. */
  private class TypeAnnotationConverter extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      JSDocInfo bestJSDocInfo = NodeUtil.getBestJSDocInfo(n);
      switch (n.getToken()) {
        // Fields default to any type
        case MEMBER_VARIABLE_DEF:
          if (bestJSDocInfo != null && bestJSDocInfo.getType() != null) {
            setTypeExpression(n, bestJSDocInfo.getType(), false);
          } else {
            n.setDeclaredTypeExpression(anyType());
          }
          break;
        // Functions are annotated with their return type
        case FUNCTION:
          if (bestJSDocInfo != null) {
            setTypeExpression(n, bestJSDocInfo.getReturnType(), true);
          }
          break;
        // Names and properties are annotated with their types
        case NAME:
        case GETPROP:
          if (parent == null) {
            break;
          }
          if (NodeUtil.isNameDeclaration(parent)) { // Variable declaration
            if (bestJSDocInfo != null) {
              setTypeExpression(n, bestJSDocInfo.getType(), false);
            }
          } else if (parent.isParamList()) { // Function parameters
            JSDocInfo parentDocInfo = NodeUtil.getBestJSDocInfo(parent.getParent());
            if (parentDocInfo == null) {
              break;
            }
            JSTypeExpression parameterType = parentDocInfo.getParameterType(n.getString());
            if (parameterType != null) {
              Node attachTypeExpr = n;
              // Modify the primary AST to represent a function parameter as a
              // REST node, if the type indicates it is a rest parameter.
              if (parameterType.getRoot().getToken() == Token.ELLIPSIS) {
                attachTypeExpr = IR.rest(n.getString());
                nodeComments.replaceWithComment(n, attachTypeExpr);
                compiler.reportCodeChange();
              }
              // Modify the AST to represent an optional parameter
              if (parameterType.getRoot().getToken() == Token.EQUALS) {
                attachTypeExpr = IR.name(n.getString());
                attachTypeExpr.putBooleanProp(Node.OPT_ES6_TYPED, true);
                nodeComments.replaceWithComment(n, attachTypeExpr);
                compiler.reportCodeChange();
              }
              setTypeExpression(attachTypeExpr, parameterType, false);
            }
          }
          break;
        case CAST:
          setTypeExpression(n, n.getJSDocInfo().getType(), false);
          break;
        default:
          break;
      }
    }
  }

  /** Annotates variables with their corresponding modifiers. (ie. private, protected) */
  private class AccessModifierConverter extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      JSDocInfo bestJSDocInfo = NodeUtil.getBestJSDocInfo(n);
      if (bestJSDocInfo == null) {
        return;
      }

      // Add visibility for private and protected.
      if (Visibility.PRIVATE.equals(bestJSDocInfo.getVisibility())) {
        n.putProp(Node.ACCESS_MODIFIER, Visibility.PRIVATE);
      } else if (Visibility.PROTECTED.equals(bestJSDocInfo.getVisibility())) {
        n.putProp(Node.ACCESS_MODIFIER, Visibility.PROTECTED);
      }

      // Change variable declarations to constants
      if (bestJSDocInfo.isConstant() && (n.isVar() || n.isLet())) {
        n.setToken(Token.CONST);
      }
    }
  }

  /**
   * Adds nodes for new imports required to resolve type declarations.
   */
  void addTypeOnlyImports(Node script) {
    if (!script.isScript() || !importsNeeded.containsKey(script.getSourceFileName())) {
      return;
    }

    Node body = script.getFirstChild().isModuleBody() ? script.getFirstChild() : script;

    for (Node n : body.children()) {
      if (n.isImport()) {
        // Prepends new imports to the first import
        for (Node newImport : importsNeeded.get(script.getSourceFileName())) {
          body.addChildBefore(newImport, n);
        }
        return;
      }
    }
    for (Node newImport : importsNeeded.get(script.getSourceFileName())) {
      script.addChildToFront(newImport);
    }
  }

  /**
   * Sets the annotated type expression corresponding to Node {@code n}.
   */
  private void setTypeExpression(Node n, JSTypeExpression type, boolean isReturnType) {
    TypeDeclarationNode node = convert(type, isReturnType);
    if (node != null) {
      n.setDeclaredTypeExpression(node);
      compiler.reportCodeChange();
    }
  }

  // Allow functional-style Iterables.transform over collections of nodes.
  private final Function<Node, TypeDeclarationNode> CONVERT_TYPE_NODE =
      new Function<Node, TypeDeclarationNode>() {
        @Override
        public TypeDeclarationNode apply(Node node) {
          return convertTypeNodeAST(node);
        }
      };

  private final Function<Node, TypeDeclarationNode> CAST_TYPE_NODE =
      new Function<Node, TypeDeclarationNode>() {
        @Override
        public TypeDeclarationNode apply(Node node) {
          return (TypeDeclarationNode) node;
        }
      };

  @Nullable
  public TypeDeclarationNode convert(@Nullable JSTypeExpression typeExpr,
      boolean isReturnType) {
    if (typeExpr == null) {
      return null;
    }
    return convertTypeNodeAST(typeExpr.getRoot(), isReturnType);
  }

  /**
   * The root of a JSTypeExpression is very different from an AST node, even though we use the
   * same Java class to represent them. This function converts root nodes of JSTypeExpressions
   * into TypeDeclaration ASTs, to make them more similar to ordinary AST nodes.
   *
   * @return the root node of a TypeDeclaration AST, or null if no type is available for the node.
   */
  @Nullable
  public TypeDeclarationNode convertTypeNodeAST(Node n) {
    return convertTypeNodeAST(n, false);
  }

  @Nullable
  public TypeDeclarationNode convertTypeNodeAST(Node n, boolean isReturnType) {
    switch (n.getToken()) {
      // for function types that don't declare a return type
      // ex. /** @return */ var f = function() {};
      case EMPTY:
        return null;
      // TODO(renez): re-evaluate whether or not we want to convert {*} to the any type.
      case STAR:
        return anyType();
      case VOID:
        return isReturnType ? voidType() : undefinedType();
      // TypeScript types are non-nullable by default with --strictNullChecks
      case BANG:
        return convertTypeNodeAST(n.getFirstChild());
      case QMARK:
        Node child = n.getFirstChild();
        if (child == null) {
          return anyType();
        } else {
          ImmutableList<TypeDeclarationNode> types = ImmutableList.of(
              new TypeDeclarationNode(Token.NULL),
              convertTypeNodeAST(child)
          );
          return flatUnionType(types);
        }
      case STRING:
        String typeName = n.getString();
        switch (typeName) {
          case "boolean":
            return booleanType();
          case "number":
            return numberType();
          case "string":
            return stringType();
          case "null":
            // TODO(renez): refactor this once Token.NULL_TYPE exists
            return new TypeDeclarationNode(Token.NULL);
          // Both undefined and void are converted to undefined for all non-return types.
          // In closure, "void" and "undefined" are type aliases and thus, equivalent types.
          // However, in TS, it is more ideomatic to emit "void" for return types.
          // Additionally, there is semantic difference to note: TS "undefined" return types require
          // a return statement, while "void" does not.
          case "undefined":
          case "void":
            return isReturnType ? voidType() : undefinedType();
          default:
            String newTypeName = convertTypeName(n.getSourceFileName(), typeName);
            newTypeName = convertExternNameToTypingName(newTypeName);
            TypeDeclarationNode root = namedType(newTypeName);
            if (n.getChildCount() > 0 && n.getFirstChild().isBlock()) {
              Node block = n.getFirstChild();
              // Convert {Array<t>} to t[]
              if ("Array".equals(typeName)) {
                return arrayType(convertTypeNodeAST(block.getFirstChild()));
              }
              // Convert generic types
              return parameterizedType(root,
                  Iterables.filter(
                      Iterables.transform(block.children(), CONVERT_TYPE_NODE),
                      Predicates.notNull()));
            }
            return root;
        }
      // Convert records
      case LC:
        LinkedHashMap<String, TypeDeclarationNode> properties = new LinkedHashMap<>();
        for (Node field : n.getFirstChild().children()) {
          boolean isFieldTypeDeclared = field.getToken() == Token.COLON;
          Node fieldNameNode = isFieldTypeDeclared ? field.getFirstChild() : field;
          String fieldName = fieldNameNode.getString();
          if (fieldName.startsWith("'") || fieldName.startsWith("\"")) {
            fieldName = fieldName.substring(1, fieldName.length() - 1);
          }
          TypeDeclarationNode fieldType = isFieldTypeDeclared
              ? convertTypeNodeAST(field.getLastChild()) : null;
          properties.put(fieldName, fieldType);
        }
        return recordType(properties);
      // Convert unions
      case PIPE:
        ImmutableList<TypeDeclarationNode> types = FluentIterable
            .from(n.children()).transform(CONVERT_TYPE_NODE)
            .filter(Predicates.notNull()).toList();
        switch (types.size()) {
          case 0:
            return null;
          case 1:
            return types.get(0);
          default:
            return flatUnionType(types);
        }
      // Convert function types
      case FUNCTION:
        Node returnType = anyType();
        LinkedHashMap<String, TypeDeclarationNode> requiredParams = new LinkedHashMap<>();
        LinkedHashMap<String, TypeDeclarationNode> optionalParams = new LinkedHashMap<>();
        String restName = null;
        TypeDeclarationNode restType = null;
        for (Node child2 : n.children()) {
          if (child2.isParamList()) {
            int paramIdx = 1;
            for (Node param : child2.children()) {
              String paramName = "p" + paramIdx++;
              if (param.getToken() == Token.ELLIPSIS) {
                if (param.getFirstChild() != null) {
                  restType = convertTypeNodeAST(param);
                }
                restName = paramName;
              } else if (param.getToken() == Token.EQUALS) {
                optionalParams.put(paramName, convertTypeNodeAST(param));
              } else {
                requiredParams.put(paramName, convertTypeNodeAST(param));
              }
            }
          } else if (child2.isNew()) {
            // keep the constructor signatures on the tree, and emit them following
            // the syntax in TypeScript 1.8 spec, section 3.8.9 Constructor Type Literals
          } else if (child2.isThis()) {
            // Not expressible in TypeScript syntax, so we omit them from the tree.
            // They could be added as properties on the result node.
          } else {
            returnType = convertTypeNodeAST(child2, true);
            if (returnType == null) {
              returnType = anyType();
            }
          }
        }
        return functionType(returnType, requiredParams, optionalParams, restName, restType);
      // Variable function parameters are encoded as an array.
      case ELLIPSIS:
        Node arrType = convertTypeNodeAST(n.getFirstChild());
        if (arrType == null) {
          arrType = anyType();
        }
        return arrayType(arrType);
      // Optional parameters are entirely encoded within the parameter name while the type
      // remains the same.
      case EQUALS:
        return convertTypeNodeAST(n.getFirstChild());
      default:
        throw new IllegalArgumentException(
            "Unsupported node type:\n" + n.toStringTree());
    }
  }

  /**
   * Converts the global type name to the local type name.
   */
  String convertTypeName(String sourceFile, String typeName) {
    Map<String, String> rewriteMap = typeRewrite.containsRow(sourceFile) ?
        typeRewrite.rowMap().get(sourceFile) :
        new HashMap<String, String>();

    // All type symbols declared anywhere in the compilation unit
    Set<String> allTypes = new HashSet<>();
    allTypes.addAll(rewriteMap.keySet());
    allTypes.addAll(symbolToModule.keySet());

    String importedNamespace = nameUtil.findLongestNamePrefix(typeName, allTypes);
    if (importedNamespace == null) {
      return typeName;
    }

    if (rewriteMap.containsKey(importedNamespace)) {
      // Rewrite already imported types
      String symbol = rewriteMap.get(importedNamespace);
      return nameUtil.replacePrefixInName(typeName, importedNamespace, symbol);
    } else {
      // Rewrite ALL types in compilation unit
      FileModule module = symbolToModule.get(importedNamespace);
      String symbol = module.importedNamespacesToSymbols.get(importedNamespace);

      // Create new import statement
      Node importSpec;
      Node importFile;
      if (module.shouldUseOldSyntax()) {
        importSpec = Node.newString(Token.NAME, symbol);
        importFile = Node.newString("goog:" + importedNamespace);
      } else {
        importSpec = new Node(Token.IMPORT_SPECS, new Node(Token.IMPORT_SPEC, IR.name(symbol)));
        String referencedFile = pathUtil.getImportPath(sourceFile, module.file);
        importFile = Node.newString(referencedFile);
      }
      Node importNode = new Node(Token.IMPORT, IR.empty(), importSpec, importFile);
      importsNeeded.put(sourceFile, importNode);

      typeRewrite.put(sourceFile, importedNamespace, symbol);
      return nameUtil.replacePrefixInName(typeName, importedNamespace, symbol);
    }
  }

  /**
   * If an extern-to-typing map is provided, try to look up the extern type name and replace it with
   * the TypeScript version.
   */
  String convertExternNameToTypingName(String externTypeName) {
    String typingName = this.externsMap.get(externTypeName);
    if (typingName != null) {
      return typingName;
    } else {
      return externTypeName;
    }
  }

  /**
   * Helper function to recursively flatten union types.
   */
  void flatten(Iterable<TypeDeclarationNode> types,
      List<TypeDeclarationNode> result, boolean hasNull) {
    for (TypeDeclarationNode t : types) {
      switch (t.getToken()) {
        case NULL:
          if (!hasNull) {
            result.add(new TypeDeclarationNode(Token.NULL));
            hasNull = true;
          }
          break;
        case UNION_TYPE:
          Iterable<TypeDeclarationNode> children = FluentIterable
              .from(t.children())
              .transform(CAST_TYPE_NODE)
              .toList();
          // We had to invoke .toList() as detachChildren() breaks the Iterable.
          t.detachChildren();
          flatten(children, result, hasNull);
          break;
        default:
          result.add(t);
          break;
      }
    }
  }

  /**
   * Deep flattens a list of types into a single union type.
   *
   * @return A flattened union type with at most 1 null.
   */
  TypeDeclarationNode flatUnionType(Iterable<TypeDeclarationNode> types) {
    List<TypeDeclarationNode> flatTypes = Lists.newArrayList();
    flatten(types, flatTypes, false);
    return unionType(flatTypes);
  }
}
