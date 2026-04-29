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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    boolean tolerantMode;
    private Token nextToken;  // 用于 lookahead 的缓冲
    private final Deque<Token> replayQueue = new ArrayDeque<Token>(); // 回放队列

    // mark/reset 回溯支持
    private final List<Token> markRecordBuffer = new ArrayList<Token>(8); // 预分配，复用
    private boolean marking;           // 是否处于回溯记录模式
    private Token markedPrevious;

    // 块内 import 提升到模块级
    final List<ImportDecl> hoistedImports = new ArrayList<ImportDecl>();
    final Map<String, InfixOperatorInfo> infixOperators = new HashMap<String, InfixOperatorInfo>();
    // 源码引用（用于 ParseException 显示出错行）
    String sourceForErrors;

    // === Helper 实例 ===
    final LiteralHelper literalHelper = new LiteralHelper(this);
    final TypeParser typeParser = new TypeParser(this);
    final DeclParser declParser = new DeclParser(this);
    final StmtParser stmtParser = new StmtParser(this);
    final ExprParser exprParser = new ExprParser(this);

    enum InfixAssociativity {
        LEFT,
        RIGHT,
        NONE
    }

    static final class InfixOperatorInfo {
        static final InfixOperatorInfo DEFAULT =
                new InfixOperatorInfo(0, InfixAssociativity.LEFT);

        final int precedence;
        final InfixAssociativity associativity;

        InfixOperatorInfo(int precedence, InfixAssociativity associativity) {
            this.precedence = precedence;
            this.associativity = associativity;
        }
    }

    InfixOperatorInfo getInfixOperatorInfo(String name) {
        InfixOperatorInfo info = infixOperators.get(name);
        return info != null ? info : InfixOperatorInfo.DEFAULT;
    }

    void registerInfixOperator(String name, int precedence, InfixAssociativity associativity) {
        infixOperators.put(name, new InfixOperatorInfo(precedence, associativity));
    }

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
        throw new ParseException(message, current, type.name()).withSource(lexer.getSource());
    }

    /**
     * 匹配 GT（>），支持拆分 SHR（>>）和 USHR（>>>）。
     * 泛型上下文中 List&lt;List&lt;Int&gt;&gt; 的 >> 应被视为两个 >。
     */
    boolean matchGT() {
        if (check(TokenType.GT)) {
            advance();
            return true;
        }
        // >> → 消费第一个 >，把第二个 > 推回
        if (check(TokenType.SHR)) {
            Token shr = current;
            advance(); // 消费 >>
            // 把第二个 > 作为 nextToken 推回
            nextToken = current;
            current = new Token(TokenType.GT, ">", null, shr.getLine(), shr.getColumn() + 1, shr.getOffset() + 1);
            return true;
        }
        // >>> → 消费第一个 >，把 >> 推回
        if (check(TokenType.USHR)) {
            Token ushr = current;
            advance(); // 消费 >>>
            nextToken = current;
            current = new Token(TokenType.SHR, ">>", null, ushr.getLine(), ushr.getColumn() + 1, ushr.getOffset() + 1);
            return true;
        }
        return false;
    }

    /**
     * 期望 GT（>），支持拆分 SHR/USHR。用于泛型关闭。
     */
    Token expectGT(String message) {
        if (matchGT()) return previous;
        throw new ParseException(message, current, TokenType.GT.name()).withSource(lexer.getSource());
    }

    /**
     * 解析成员名：标识符或关键字（.后允许关键字作为成员名，如 s.launch）
     */
    String expectMemberName() {
        if (check(TokenType.IDENTIFIER) || current.getType().isKeyword()) {
            return advance().getLexeme();
        }
        throw new ParseException("Expected member name", current, "IDENTIFIER").withSource(lexer.getSource());
    }

    String expectQualifiedNamePart(String message) {
        if (check(TokenType.IDENTIFIER) || current.getType().isKeyword()) {
            String lexeme = current.getLexeme();
            if (!lexeme.isEmpty() && Character.isJavaIdentifierStart(lexeme.charAt(0))) {
                for (int i = 1; i < lexeme.length(); i++) {
                    if (!Character.isJavaIdentifierPart(lexeme.charAt(i))) {
                        throw new ParseException(message, current, "IDENTIFIER").withSource(lexer.getSource());
                    }
                }
                return advance().getLexeme();
            }
        }
        throw new ParseException(message, current, "IDENTIFIER").withSource(lexer.getSource());
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
        // 预扫描括号配对，提供精确错误定位
        checkBracketBalance();
        try {
            return doParse();
        } catch (ParseException e) {
            throw e.withSource(lexer.getSource());
        }
    }

    /**
     * 预扫描源码中的括号配对。
     * 在递归下降解析前检测未闭合的 {}/[]/()，报告精确位置。
     */
    private void checkBracketBalance() {
        String source = lexer.getSource();
        if (source == null) return;

        // 简易括号扫描（跳过字符串和注释）
        java.util.Deque<int[]> stack = new java.util.ArrayDeque<>(); // [char, line, col]
        int line = 1, col = 1;
        boolean inString = false, inLineComment = false, inBlockComment = false;
        char stringChar = 0;
        boolean escape = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);

            if (escape) { escape = false; col++; continue; }
            if (c == '\\' && inString) { escape = true; col++; continue; }

            if (inLineComment) {
                if (c == '\n') { inLineComment = false; line++; col = 1; }
                else col++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    inBlockComment = false; i++; col += 2;
                } else if (c == '\n') { line++; col = 1; }
                else col++;
                continue;
            }
            if (inString) {
                if (c == stringChar) { inString = false; col++; continue; }
                // 字符串模板 ${...} 中的括号不参与匹配——跳过整个模板区域
                if (c == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                    // 找到匹配的 }（支持嵌套）
                    int depth = 0;
                    i++; col++; // skip $
                    for (; i < source.length(); i++) {
                        char tc = source.charAt(i);
                        if (tc == '{') depth++;
                        else if (tc == '}') { depth--; if (depth == 0) break; }
                        else if (tc == '\n') { line++; col = 0; }
                        col++;
                    }
                    col++;
                    continue;
                }
                if (c == '\n') { line++; col = 1; } else col++;
                continue;
            }

            // 非字符串/注释区域
            if (c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') { inLineComment = true; col++; continue; }
                if (next == '*') { inBlockComment = true; i++; col += 2; continue; }
            }
            if (c == '"') { inString = true; stringChar = c; col++; continue; }

            if (c == '{' || c == '(' || c == '[') {
                stack.push(new int[]{c, line, col});
            } else if (c == '}' || c == ')' || c == ']') {
                char expected = c == '}' ? '{' : c == ')' ? '(' : '[';
                if (stack.isEmpty()) {
                    // 多余的关闭括号
                    throw new ParseException("Unexpected '" + c + "' with no matching '"
                        + expected + "'", current)
                        .withSourceAt(source, line);
                }
                int[] top = stack.pop();
                if (top[0] != expected) {
                    throw new ParseException("Mismatched brackets: '" + (char) top[0]
                        + "' at line " + top[1] + " closed by '" + c + "' at line " + line,
                        current).withSourceAt(source, top[1]);
                }
            }

            if (c == '\n') { line++; col = 1; } else col++;
        }

        if (!stack.isEmpty()) {
            int[] unclosed = stack.pop();
            throw new ParseException("Unclosed '" + (char) unclosed[0]
                + "' (opened at line " + unclosed[1] + ", column " + unclosed[2]
                + "): missing matching close bracket", current)
                .withSourceAt(source, unclosed[1]);
        }
    }

    private Program doParse() {
        SourceLocation loc = location();
        skipSeparators();

        // 文件注解 (@file:AnnotationName)
        List<Annotation> fileAnnotations = parseFileAnnotations();

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
            skipSeparators();
            if (isAtEnd()) break;
            if (check(KW_IMPORT)) {
                imports.add(parseImportDecl());
            } else if (isDeclarationStart()) {
                Declaration decl = parseDeclaration();
                declarations.add(decl);
                // 函数声明 IIFE: fun name() { ... }(args) → 声明 + 立即调用
                if (decl instanceof FunDecl && check(LPAREN)) {
                    FunDecl funDecl = (FunDecl) decl;
                    List<CallExpr.Argument> args = exprParser.parseCallArgs();
                    Identifier callee = new Identifier(decl.getLocation(), funDecl.getName());
                    CallExpr call = new CallExpr(decl.getLocation(), callee,
                            Collections.<TypeRef>emptyList(), args, null);
                    topLevelStatements.add(new ExpressionStmt(decl.getLocation(), call));
                }
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

        // 合并块内提升的 import
        imports.addAll(hoistedImports);

        lexer.releaseSource(); // 解析完成，释放源码字符串
        return new Program(loc, fileAnnotations, packageDecl, imports, declarations);
    }

    /**
     * 容错解析：遇到错误时跳过到下一个声明继续解析。
     * 返回的 ParseResult 包含已成功解析的声明和收集到的错误列表。
     */
    public ParseResult parseTolerant() {
        boolean previousTolerantMode = tolerantMode;
        tolerantMode = true;
        SourceLocation loc = location();
        skipSeparators();
        List<ParseError> errors = new ArrayList<ParseError>();

        // 文件注解 (@file:AnnotationName)
        List<Annotation> fileAnnotations = parseFileAnnotations();

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
            skipSeparators();
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

        Program program = new Program(loc, fileAnnotations, packageDecl, imports, declarations);
        lexer.releaseSource(); // 容错解析完成，释放源码字符串
        tolerantMode = previousTolerantMode;
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
        // 软关键词: annotation class, infix fun
        if (check(IDENTIFIER) && "annotation".equals(current.getLexeme()) && checkAhead(KW_CLASS)) return true;
        if (check(IDENTIFIER) && "infix".equals(current.getLexeme())) return true;
        return false;
    }

    /**
     * 检测当前位置是否为 @file: 文件注解起始。
     * 使用 mark/reset 前看三个 token: @ "file" :
     */
    private boolean isFileAnnotationStart() {
        if (!check(AT)) return false;
        mark();
        try {
            advance(); // consume @
            if (check(IDENTIFIER) && "file".equals(current.getLexeme())) {
                advance(); // consume "file"
                return check(COLON);
            }
            return false;
        } finally {
            reset();
        }
    }

    /**
     * 解析文件级注解列表（@file:Name(args)）
     */
    private List<Annotation> parseFileAnnotations() {
        List<Annotation> fileAnnotations = new ArrayList<Annotation>();
        while (isFileAnnotationStart()) {
            fileAnnotations.add(declParser.parseFileAnnotation());
            skipSeparators();
        }
        return fileAnnotations;
    }

    // ============ 包和导入 ============

    private PackageDecl parsePackageDecl() {
        SourceLocation loc = location();
        expect(KW_PACKAGE, "Expected 'package'");
        QualifiedName name = parseQualifiedName();
        matchAny(NEWLINE, SEMICOLON);
        return new PackageDecl(loc, name);
    }

    ImportDecl parseImportDecl() {
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
        parts.add(expectQualifiedNamePart("Expected identifier"));

        while (check(DOT) && !checkAhead(MUL)) {
            advance();  // consume '.'
            parts.add(expectQualifiedNamePart("Expected identifier"));
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
