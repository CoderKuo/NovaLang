package com.novalang.compiler.parser;

import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.lexer.Token;
import com.novalang.compiler.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static com.novalang.compiler.lexer.TokenType.*;

/**
 * NovaLang 语法分析器（递归下降）
 */
@SuppressWarnings("this-escape")
public class Parser {

    final Lexer lexer;
    final String fileName;
    Token current;
    Token previous;
    private Token nextToken;  // 用于 lookahead 的缓冲
    private final Deque<Token> replayQueue = new ArrayDeque<Token>(); // 回放队列

    // mark/reset 回溯支持
    private final List<Token> markRecordBuffer = new ArrayList<Token>(8); // 预分配，复用
    private boolean marking;           // 是否处于回溯记录模式
    private Token markedPrevious;

    // === Helper 实例 ===
    final LiteralHelper literalHelper = new LiteralHelper(this);
    final TypeParser typeParser = new TypeParser(this);
    final DeclParser declParser = new DeclParser(this);
    final StmtParser stmtParser = new StmtParser(this);
    final ExprParser exprParser = new ExprParser(this);

    public Parser(Lexer lexer, String fileName) {
        this.lexer = lexer;
        this.fileName = fileName;
        advance();  // 读取第一个 token
    }

    // ============ 基础方法 ============

    /**
     * 前进到下一个 token
     */
    Token advance() {
        previous = current;
        if (nextToken != null) {
            current = nextToken;
            nextToken = null;
        } else if (!replayQueue.isEmpty()) {
            current = replayQueue.poll();
        } else {
            current = lexer.nextToken();
        }
        // 回溯模式下记录消费的 token
        if (marking && previous != null) {
            markRecordBuffer.add(previous);
        }
        return previous;
    }

    /**
     * 查看下一个 token（不消费当前）
     */
    Token peek() {
        if (nextToken == null) {
            if (!replayQueue.isEmpty()) {
                nextToken = replayQueue.poll();
            } else {
                nextToken = lexer.nextToken();
            }
        }
        return nextToken;
    }

    /**
     * 标记当前位置，用于回溯
     */
    void mark() {
        markRecordBuffer.clear();
        marking = true;
        markedPrevious = previous;
    }

    /**
     * 回溯到标记的位置
     */
    void reset() {
        // 将 nextToken + current 放回 replayQueue 前端，再把 markRecord 按逆序插入最前
        if (nextToken != null) {
            replayQueue.addFirst(nextToken);
            nextToken = null;
        }
        replayQueue.addFirst(current);
        for (int i = markRecordBuffer.size() - 1; i >= 0; i--) {
            replayQueue.addFirst(markRecordBuffer.get(i));
        }

        current = replayQueue.poll();
        previous = markedPrevious;
        marking = false;
    }

    /**
     * 提交标记（放弃回溯能力）
     */
    void commitMark() {
        marking = false;
    }

    /**
     * 检查当前 token 类型
     */
    boolean check(TokenType type) {
        return current.getType() == type;
    }

    /**
     * 检查当前 token 是否为给定类型之一
     */
    boolean checkAny(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) return true;
        }
        return false;
    }

    /**
     * 检查当前 token 是否为内置类型关键字
     */
    boolean isBuiltinTypeKeyword() {
        return checkAny(KW_INT, KW_LONG, KW_FLOAT, KW_DOUBLE,
                        KW_BOOLEAN, KW_CHAR, KW_STRING, KW_ARRAY, KW_ANY, KW_UNIT);
    }

    /**
     * 检查当前 token 是否为软关键字（修饰符等），可在标识符位置使用。
     */
    boolean isSoftKeyword() {
        return checkAny(KW_PUBLIC, KW_PRIVATE, KW_PROTECTED, KW_INTERNAL,
                        KW_OPEN, KW_OVERRIDE, KW_ABSTRACT, KW_FINAL, KW_SEALED,
                        KW_INLINE, KW_OPERATOR, KW_SUSPEND, KW_STATIC, KW_COMPANION,
                        KW_CONST, KW_REIFIED, KW_VARARG, KW_CROSSINLINE);
    }

    /**
     * 如果当前 token 匹配，则前进
     */
    boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * 如果当前 token 匹配任一类型，则前进
     */
    boolean matchAny(TokenType... types) {
        for (TokenType type : types) {
            if (match(type)) return true;
        }
        return false;
    }

    /**
     * 期望特定 token，否则报错
     */
    Token expect(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw new ParseException(message, current, type.name());
    }

    /**
     * 解析成员名：标识符或关键字（.后允许关键字作为成员名，如 s.launch）
     */
    String expectMemberName() {
        if (check(TokenType.IDENTIFIER) || current.getType().isKeyword()) {
            return advance().getLexeme();
        }
        throw new ParseException("Expected member name", current, "IDENTIFIER");
    }

    /**
     * 创建源码位置
     */
    SourceLocation location() {
        return new SourceLocation(fileName, current.getLine(), current.getColumn(),
                current.getOffset(), current.getLexeme().length());
    }

    /**
     * 从之前的 token 创建位置
     */
    SourceLocation previousLocation() {
        return new SourceLocation(fileName, previous.getLine(), previous.getColumn(),
                previous.getOffset(), previous.getLexeme().length());
    }

    /**
     * 是否到达文件末尾
     */
    boolean isAtEnd() {
        return check(EOF);
    }

    /**
     * 跳过换行
     */
    void skipNewlines() {
        while (match(NEWLINE)) {
            // 跳过
        }
    }

    void skipSeparators() {
        while (matchAny(NEWLINE, SEMICOLON)) {
            // 跳过换行符和分号
        }
    }

    // ============ 程序解析 ============

    /**
     * 解析程序
     */
    public Program parse() {
        SourceLocation loc = location();
        skipNewlines();

        // 包声明
        PackageDecl packageDecl = null;
        if (check(KW_PACKAGE)) {
            packageDecl = parsePackageDecl();
        }

        // 导入声明
        List<ImportDecl> imports = new ArrayList<ImportDecl>();
        while (check(KW_IMPORT)) {
            imports.add(parseImportDecl());
        }

        // 声明和顶层语句
        List<Declaration> declarations = new ArrayList<Declaration>();
        List<Statement> topLevelStatements = new ArrayList<Statement>();
        while (!isAtEnd()) {
            skipNewlines();
            if (isAtEnd()) break;
            if (check(KW_IMPORT)) {
                imports.add(parseImportDecl());
            } else if (isDeclarationStart()) {
                declarations.add(parseDeclaration());
            } else {
                Statement stmt = parseStatement();
                if (stmt instanceof DeclarationStmt) {
                    declarations.add(((DeclarationStmt) stmt).getDeclaration());
                } else {
                    topLevelStatements.add(stmt);
                }
            }
        }

        // 如果存在顶层语句，包装为合成 main 函数
        if (!topLevelStatements.isEmpty()) {
            Block body = new Block(loc, topLevelStatements);
            FunDecl mainDecl = new FunDecl(loc, Collections.<Annotation>emptyList(),
                    Collections.<Modifier>emptyList(), "main",
                    Collections.<com.novalang.compiler.ast.type.TypeParameter>emptyList(),
                    null, Collections.<Parameter>emptyList(), null,
                    body, false, false, false);
            declarations.add(mainDecl);
        }

        lexer.releaseSource(); // 解析完成，释放源码字符串
        return new Program(loc, packageDecl, imports, declarations);
    }

    /**
     * 容错解析：遇到错误时跳过到下一个声明继续解析。
     * 返回的 ParseResult 包含已成功解析的声明和收集到的错误列表。
     */
    public ParseResult parseTolerant() {
        SourceLocation loc = location();
        skipNewlines();
        List<ParseError> errors = new ArrayList<ParseError>();

        // 包声明
        PackageDecl packageDecl = null;
        try {
            if (check(KW_PACKAGE)) {
                packageDecl = parsePackageDecl();
            }
        } catch (ParseException e) {
            errors.add(new ParseError(e.getMessage(), e.getToken()));
            synchronize();
        }

        // 导入声明
        List<ImportDecl> imports = new ArrayList<ImportDecl>();
        while (check(KW_IMPORT)) {
            try {
                imports.add(parseImportDecl());
            } catch (ParseException e) {
                errors.add(new ParseError(e.getMessage(), e.getToken()));
                synchronize();
            }
        }

        // 声明和语句
        List<Declaration> declarations = new ArrayList<Declaration>();
        List<Statement> topLevelStatements = new ArrayList<Statement>();
        while (!isAtEnd()) {
            skipNewlines();
            if (isAtEnd()) break;
            try {
                if (isDeclarationStart()) {
                    declarations.add(parseDeclaration());
                } else {
                    Statement stmt = parseStatement();
                    if (stmt instanceof DeclarationStmt) {
                        declarations.add(((DeclarationStmt) stmt).getDeclaration());
                    } else {
                        topLevelStatements.add(stmt);
                    }
                }
            } catch (ParseException e) {
                errors.add(new ParseError(e.getMessage(), e.getToken()));
                synchronize();
            } catch (RuntimeException e) {
                errors.add(new ParseError(e.getMessage() != null ? e.getMessage() : e.getClass().getName(), current));
                synchronize();
            }
        }

        Program program = new Program(loc, packageDecl, imports, declarations);
        lexer.releaseSource(); // 容错解析完成，释放源码字符串
        return new ParseResult(program, errors, topLevelStatements);
    }

    /**
     * 错误恢复：跳过 token 直到找到下一个可能的声明起始点。
     */
    private void synchronize() {
        advance(); // 跳过触发错误的 token
        while (!isAtEnd()) {
            // 分号/换行后如果是声明起始，停止
            if (previous != null && (previous.getType() == SEMICOLON || previous.getType() == NEWLINE)) {
                if (isDeclarationStart()) return;
            }
            // 直接遇到声明起始 token 也停止
            if (isDeclarationStart()) return;
            advance();
        }
    }

    /**
     * 解析 REPL 输入（声明或语句）
     */
    public AstNode parseReplInput() {
        skipNewlines();
        if (isAtEnd()) {
            return null;
        }

        // 跳过可选的分号
        while (match(SEMICOLON)) {
            skipNewlines();
        }
        if (isAtEnd()) {
            return null;
        }

        // 处理 import 声明
        if (check(KW_IMPORT)) {
            return parseImportDecl();
        }

        // 检查是否是声明
        if (isDeclarationStart()) {
            return parseDeclaration();
        }

        // 否则作为语句解析
        return parseStatement();
    }

    /**
     * 检查当前 token 是否是声明开头
     */
    boolean isDeclarationStart() {
        // 跳过注解和修饰符检查主关键字
        if (check(AT)) return true;  // 注解
        if (checkAny(KW_PUBLIC, KW_PRIVATE, KW_PROTECTED, KW_INTERNAL)) return true;
        if (checkAny(KW_OPEN, KW_FINAL, KW_ABSTRACT, KW_SEALED)) return true;
        if (checkAny(KW_OVERRIDE, KW_STATIC, KW_CONST)) return true;
        if (checkAny(KW_INLINE, KW_SUSPEND)) return true;
        if (checkAny(KW_CLASS, KW_INTERFACE, KW_OBJECT, KW_ENUM)) return true;
        if (checkAny(KW_FUN, KW_VAL, KW_VAR, KW_TYPEALIAS)) return true;
        // annotation class — 软关键词
        if (check(IDENTIFIER) && "annotation".equals(current.getLexeme()) && checkAhead(KW_CLASS)) return true;
        return false;
    }

    // ============ 包和导入 ============

    private PackageDecl parsePackageDecl() {
        SourceLocation loc = location();
        expect(KW_PACKAGE, "Expected 'package'");
        QualifiedName name = parseQualifiedName();
        matchAny(NEWLINE, SEMICOLON);
        return new PackageDecl(loc, name);
    }

    private ImportDecl parseImportDecl() {
        SourceLocation loc = location();
        expect(KW_IMPORT, "Expected 'import'");

        // 软关键词：import java → Java 类导入
        boolean isJava = false;
        if (check(IDENTIFIER) && "java".equals(current.getLexeme()) && checkAhead(IDENTIFIER)) {
            isJava = true;
            advance(); // 消费 "java" 软关键词
        }

        boolean isStatic = match(KW_STATIC);
        QualifiedName name = parseQualifiedName();

        boolean isWildcard = false;
        String alias = null;

        if (match(DOT)) {
            if (match(MUL)) {
                isWildcard = true;
            } else {
                throw new ParseException("Expected '*' after '.'", current);
            }
        } else if (match(KW_AS)) {
            alias = expect(IDENTIFIER, "Expected alias name").getLexeme();
        }

        matchAny(NEWLINE, SEMICOLON);
        return new ImportDecl(loc, isJava, isStatic, name, isWildcard, alias);
    }

    QualifiedName parseQualifiedName() {
        SourceLocation loc = location();
        List<String> parts = new ArrayList<String>();
        parts.add(expect(IDENTIFIER, "Expected identifier").getLexeme());

        while (check(DOT) && !checkAhead(MUL)) {
            advance();  // consume '.'
            parts.add(expect(IDENTIFIER, "Expected identifier").getLexeme());
        }

        return new QualifiedName(loc, parts);
    }

    /**
     * 向前看一个 token（不消费当前）
     */
    boolean checkAhead(TokenType type) {
        return peek().getType() == type;
    }

    // ============ 声明解析委托 ============

    Declaration parseDeclaration() { return declParser.parseDeclaration(); }

    // ============ 类型解析委托 ============

    TypeRef parseType() { return typeParser.parseType(); }
    List<TypeParameter> parseTypeParams() { return typeParser.parseTypeParams(); }
    List<TypeRef> parseTypeRefList() { return typeParser.parseTypeRefList(); }
    List<TypeArgument> parseTypeArgs() { return typeParser.parseTypeArgs(); }

    // ============ 语句解析委托 ============

    Statement parseStatement() { return stmtParser.parseStatement(); }
    Block parseBlock() { return stmtParser.parseBlock(); }

    // ============ 表达式解析委托 ============

    Expression parseExpression() { return exprParser.parseExpression(); }
    List<Expression> parseExpressionList() { return exprParser.parseExpressionList(); }

}
