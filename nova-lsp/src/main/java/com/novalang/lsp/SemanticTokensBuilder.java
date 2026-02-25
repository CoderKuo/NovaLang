package com.novalang.lsp;

import com.google.gson.JsonArray;
import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;

import java.util.*;

/**
 * 语义令牌生成器
 *
 * <p>遍历 AST 为有语义意义的节点生成 LSP 语义令牌（相对编码格式）。</p>
 */
public class SemanticTokensBuilder implements AstVisitor<Void, Void> {

    // === 令牌类型 ===
    private static final String[] TOKEN_TYPES = {
            "namespace",    // 0
            "type",         // 1
            "class",        // 2
            "enum",         // 3
            "interface",    // 4
            "function",     // 5
            "variable",     // 6
            "parameter",    // 7
            "property",     // 8
            "keyword",      // 9
            "string",       // 10
            "number",       // 11
            "operator",     // 12
            "comment",      // 13
            "enumMember",   // 14
    };

    // === 令牌修饰符 ===
    private static final String[] TOKEN_MODIFIERS = {
            "declaration",  // 0 (bit 0)
            "definition",   // 1 (bit 1)
            "readonly",     // 2 (bit 2)
            "deprecated",   // 3 (bit 3)
            "modification", // 4 (bit 4)
    };

    public static JsonArray getTokenTypesJson() {
        JsonArray arr = new JsonArray();
        for (String t : TOKEN_TYPES) arr.add(t);
        return arr;
    }

    public static JsonArray getTokenModifiersJson() {
        JsonArray arr = new JsonArray();
        for (String m : TOKEN_MODIFIERS) arr.add(m);
        return arr;
    }

    /** 原始令牌条目（绝对位置） */
    private static class RawToken implements Comparable<RawToken> {
        final int line;      // 0-based
        final int startChar; // 0-based
        final int length;
        final int tokenType;
        final int modifiers;

        RawToken(int line, int startChar, int length, int tokenType, int modifiers) {
            this.line = line;
            this.startChar = startChar;
            this.length = length;
            this.tokenType = tokenType;
            this.modifiers = modifiers;
        }

        @Override
        public int compareTo(RawToken o) {
            int cmp = Integer.compare(this.line, o.line);
            return cmp != 0 ? cmp : Integer.compare(this.startChar, o.startChar);
        }
    }

    private final List<RawToken> tokens = new ArrayList<>();

    private void addToken(SourceLocation loc, int length, int tokenType, int modifiers) {
        if (loc == null || loc.getLine() <= 0) return;
        if (length <= 0) return;
        tokens.add(new RawToken(loc.getLine() - 1, loc.getColumn() - 1, length, tokenType, modifiers));
    }

    private void addToken(SourceLocation loc, String name, int tokenType, int modifiers) {
        if (name == null || name.isEmpty()) return;
        addToken(loc, name.length(), tokenType, modifiers);
    }

    /** 递归访问子节点的便捷方法 */
    private void visit(AstNode node) {
        if (node != null) node.accept(this, null);
    }

    /**
     * 从 AST 生成语义令牌数据数组
     */
    public int[] build(Program program) {
        tokens.clear();
        visit(program);

        // 排序 + 编码为相对格式
        Collections.sort(tokens);

        int[] data = new int[tokens.size() * 5];
        int prevLine = 0, prevChar = 0;
        for (int i = 0; i < tokens.size(); i++) {
            RawToken t = tokens.get(i);
            int deltaLine = t.line - prevLine;
            int deltaChar = deltaLine == 0 ? t.startChar - prevChar : t.startChar;
            data[i * 5] = deltaLine;
            data[i * 5 + 1] = deltaChar;
            data[i * 5 + 2] = t.length;
            data[i * 5 + 3] = t.tokenType;
            data[i * 5 + 4] = t.modifiers;
            prevLine = t.line;
            prevChar = t.startChar;
        }
        return data;
    }

    // ============ AstVisitor 实现 ============

    @Override
    public Void visitProgram(Program node, Void ctx) {
        for (ImportDecl imp : node.getImports()) {
            visit(imp);
        }
        for (Declaration decl : node.getDeclarations()) {
            visit(decl);
        }
        return null;
    }

    @Override
    public Void visitImportDecl(ImportDecl node, Void ctx) {
        if (node.getLocation() != null) {
            addToken(node.getLocation(), node.getLocation().getLength(), 0, 0);
        }
        return null;
    }

    @Override
    public Void visitClassDecl(ClassDecl node, Void ctx) {
        SourceLocation nameLoc = node.getNameLocation() != null ? node.getNameLocation() : node.getLocation();
        addToken(nameLoc, node.getName(), 2, 1); // class, declaration
        if (node.getPrimaryConstructorParams() != null) {
            for (Parameter p : node.getPrimaryConstructorParams()) {
                SourceLocation pLoc = p.getNameLocation() != null ? p.getNameLocation() : p.getLocation();
                addToken(pLoc, p.getName(), p.isProperty() ? 8 : 7, 1); // property/parameter, declaration
            }
        }
        for (Declaration member : node.getMembers()) {
            visit(member);
        }
        return null;
    }

    @Override
    public Void visitInterfaceDecl(InterfaceDecl node, Void ctx) {
        SourceLocation nameLoc = node.getNameLocation() != null ? node.getNameLocation() : node.getLocation();
        addToken(nameLoc, node.getName(), 4, 1); // interface, declaration
        for (Declaration member : node.getMembers()) {
            visit(member);
        }
        return null;
    }

    @Override
    public Void visitEnumDecl(EnumDecl node, Void ctx) {
        SourceLocation nameLoc = node.getNameLocation() != null ? node.getNameLocation() : node.getLocation();
        addToken(nameLoc, node.getName(), 3, 1); // enum, declaration
        for (EnumDecl.EnumEntry entry : node.getEntries()) {
            addToken(entry.getLocation(), entry.getName(), 14, 1); // enumMember, declaration
        }
        for (Declaration member : node.getMembers()) {
            visit(member);
        }
        return null;
    }

    @Override
    public Void visitObjectDecl(ObjectDecl node, Void ctx) {
        SourceLocation nameLoc = node.getNameLocation() != null ? node.getNameLocation() : node.getLocation();
        addToken(nameLoc, node.getName(), 2, 1); // class, declaration
        for (Declaration member : node.getMembers()) {
            visit(member);
        }
        return null;
    }

    @Override
    public Void visitFunDecl(FunDecl node, Void ctx) {
        SourceLocation nameLoc = node.getNameLocation() != null ? node.getNameLocation() : node.getLocation();
        addToken(nameLoc, node.getName(), 5, 1); // function, declaration
        for (Parameter p : node.getParams()) {
            SourceLocation pLoc = p.getNameLocation() != null ? p.getNameLocation() : p.getLocation();
            addToken(pLoc, p.getName(), 7, 1); // parameter, declaration
        }
        visit(node.getBody());
        return null;
    }

    @Override
    public Void visitPropertyDecl(PropertyDecl node, Void ctx) {
        int modifiers = 1; // declaration
        if (node.isVal()) modifiers |= 4; // readonly
        SourceLocation nameLoc = node.getNameLocation() != null ? node.getNameLocation() : node.getLocation();
        addToken(nameLoc, node.getName(), 6, modifiers); // variable
        visit(node.getInitializer());
        if (node.getGetter() != null && node.getGetter().getBody() != null) {
            visit(node.getGetter().getBody());
        }
        if (node.getSetter() != null && node.getSetter().getBody() != null) {
            visit(node.getSetter().getBody());
        }
        return null;
    }

    @Override
    public Void visitBlock(Block node, Void ctx) {
        for (Statement stmt : node.getStatements()) {
            visit(stmt);
        }
        return null;
    }

    @Override
    public Void visitExpressionStmt(ExpressionStmt node, Void ctx) {
        visit(node.getExpression());
        return null;
    }

    @Override
    public Void visitDeclarationStmt(DeclarationStmt node, Void ctx) {
        visit(node.getDeclaration());
        return null;
    }

    @Override
    public Void visitReturnStmt(ReturnStmt node, Void ctx) {
        visit(node.getValue());
        return null;
    }

    @Override
    public Void visitIfStmt(IfStmt node, Void ctx) {
        visit(node.getCondition());
        visit(node.getThenBranch());
        visit(node.getElseBranch());
        return null;
    }

    @Override
    public Void visitForStmt(ForStmt node, Void ctx) {
        if (node.hasLabel()) {
            addToken(node.getLocation(), node.getLabel(), 14, 1);
        }
        for (String varName : node.getVariables()) {
            if (varName != null && !"_".equals(varName)) {
                addToken(node.getLocation(), varName, 6, 1);
            }
        }
        visit(node.getIterable());
        visit(node.getBody());
        return null;
    }

    @Override
    public Void visitDoWhileStmt(DoWhileStmt node, Void ctx) {
        if (node.hasLabel()) {
            addToken(node.getLocation(), node.getLabel(), 14, 1);
        }
        visit(node.getBody());
        visit(node.getCondition());
        return null;
    }

    @Override
    public Void visitWhileStmt(WhileStmt node, Void ctx) {
        if (node.hasLabel()) {
            addToken(node.getLocation(), node.getLabel(), 14, 1);
        }
        visit(node.getCondition());
        visit(node.getBody());
        return null;
    }

    @Override
    public Void visitCallExpr(CallExpr node, Void ctx) {
        if (node.getCallee() instanceof Identifier) {
            String name = ((Identifier) node.getCallee()).getName();
            char first = name.isEmpty() ? 'a' : name.charAt(0);
            int type = Character.isUpperCase(first) ? 2 : 5; // class or function
            addToken(node.getCallee().getLocation(), name, type, 0);
        } else if (node.getCallee() instanceof MemberExpr) {
            MemberExpr memberCallee = (MemberExpr) node.getCallee();
            visit(memberCallee.getTarget());
            SourceLocation mLoc = memberCallee.getMemberLocation() != null
                    ? memberCallee.getMemberLocation() : memberCallee.getLocation();
            addToken(mLoc, memberCallee.getMember(), 5, 0); // function, not property
        } else {
            visit(node.getCallee());
        }
        for (CallExpr.Argument arg : node.getArgs()) {
            visit(arg.getValue());
        }
        visit(node.getTrailingLambda());
        return null;
    }

    @Override
    public Void visitMemberExpr(MemberExpr node, Void ctx) {
        visit(node.getTarget());
        SourceLocation mLoc = node.getMemberLocation() != null ? node.getMemberLocation() : node.getLocation();
        addToken(mLoc, node.getMember(), 8, 0);
        return null;
    }

    @Override
    public Void visitIdentifier(Identifier node, Void ctx) {
        String name = node.getName();
        char first = name.isEmpty() ? 'a' : name.charAt(0);
        int type = Character.isUpperCase(first) ? 2 : 6; // class or variable
        addToken(node.getLocation(), name, type, 0);
        return null;
    }

    @Override
    public Void visitLiteral(Literal node, Void ctx) {
        switch (node.getKind()) {
            case STRING:
            case CHAR:
                addToken(node.getLocation(), node.getLocation().getLength(), 10, 0); // string
                break;
            case INT: case LONG: case FLOAT: case DOUBLE:
                addToken(node.getLocation(), node.getLocation().getLength(), 11, 0); // number
                break;
            default:
                break;
        }
        return null;
    }

    @Override
    public Void visitLambdaExpr(LambdaExpr node, Void ctx) {
        if (node.hasExplicitParams()) {
            for (LambdaExpr.LambdaParam p : node.getParams()) {
                addToken(node.getLocation(), p.getName(), 7, 1); // parameter, declaration
            }
        }
        visit(node.getBody());
        return null;
    }

    @Override
    public Void visitBinaryExpr(BinaryExpr node, Void ctx) {
        visit(node.getLeft());
        visit(node.getRight());
        return null;
    }

    @Override
    public Void visitUnaryExpr(UnaryExpr node, Void ctx) {
        visit(node.getOperand());
        return null;
    }

    @Override
    public Void visitAssignExpr(AssignExpr node, Void ctx) {
        visit(node.getTarget());
        visit(node.getValue());
        return null;
    }

    @Override
    public Void visitIndexExpr(IndexExpr node, Void ctx) {
        visit(node.getTarget());
        visit(node.getIndex());
        return null;
    }

    @Override
    public Void visitWhenExpr(WhenExpr node, Void ctx) {
        visit(node.getSubject());
        for (WhenBranch branch : node.getBranches()) {
            visit(branch.getBody());
        }
        visit(node.getElseExpr());
        return null;
    }

    @Override
    public Void visitIfExpr(IfExpr node, Void ctx) {
        visit(node.getCondition());
        visit(node.getThenExpr());
        visit(node.getElseExpr());
        return null;
    }

    @Override
    public Void visitStringInterpolation(StringInterpolation node, Void ctx) {
        // 不为插值内部的表达式发射语义令牌——
        // 因为 Parser 创建的 Identifier/${}表达式 的 SourceLocation 指向字符串 token 而非
        // 表达式在字符串内的实际位置，发射令牌会导致高亮错位。
        // 字符串内插值的高亮由 TextMate 语法处理即可。
        return null;
    }

    @Override
    public Void visitTryStmt(TryStmt node, Void ctx) {
        visit(node.getTryBlock());
        for (CatchClause cc : node.getCatchClauses()) {
            visit(cc.getBody());
        }
        visit(node.getFinallyBlock());
        return null;
    }

    @Override
    public Void visitWhenStmt(WhenStmt node, Void ctx) {
        visit(node.getSubject());
        for (WhenBranch branch : node.getBranches()) {
            visit(branch.getBody());
        }
        visit(node.getElseBranch());
        return null;
    }

    @Override
    public Void visitCollectionLiteral(CollectionLiteral node, Void ctx) {
        if (node.getElements() != null) {
            for (Expression elem : node.getElements()) {
                visit(elem);
            }
        }
        if (node.getMapEntries() != null) {
            for (CollectionLiteral.MapEntry entry : node.getMapEntries()) {
                visit(entry.getKey());
                visit(entry.getValue());
            }
        }
        return null;
    }
}
