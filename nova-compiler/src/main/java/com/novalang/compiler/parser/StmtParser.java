package com.novalang.compiler.parser;

import com.novalang.compiler.ast.stmt.Block;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.Declaration;
import com.novalang.compiler.ast.decl.DestructuringDecl;
import com.novalang.compiler.ast.decl.PropertyDecl;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.novalang.compiler.lexer.TokenType.*;

/**
 * 语句解析辅助类
 */
class StmtParser {

    final Parser parser;

    StmtParser(Parser parser) {
        this.parser = parser;
    }

    Statement parseStatement() {
        parser.skipNewlines();

        if (parser.check(LBRACE)) {
            return parseBlock();
        }
        if (parser.check(KW_IF)) {
            return parseIfStmt();
        }
        if (parser.check(KW_WHEN)) {
            return parseWhenStmt();
        }
        // 检测标签语法: label@ for/while/do
        if (parser.check(IDENTIFIER)) {
            parser.mark();
            String labelName = parser.advance().getLexeme();
            if (parser.match(AT) && parser.checkAny(KW_FOR, KW_WHILE, KW_DO)) {
                parser.commitMark();
                if (parser.check(KW_FOR)) return parseForStmt(labelName);
                if (parser.check(KW_WHILE)) return parseWhileStmt(labelName);
                return parseDoWhileStmt(labelName);
            }
            parser.reset();
        }
        if (parser.check(KW_FOR)) {
            return parseForStmt(null);
        }
        if (parser.check(KW_WHILE)) {
            return parseWhileStmt(null);
        }
        if (parser.check(KW_DO)) {
            return parseDoWhileStmt(null);
        }
        if (parser.check(KW_TRY)) {
            return parseTryStmt();
        }
        if (parser.check(KW_RETURN)) {
            return parseReturnStmt();
        }
        if (parser.check(KW_BREAK)) {
            return parseBreakStmt();
        }
        if (parser.check(KW_CONTINUE)) {
            return parseContinueStmt();
        }
        if (parser.check(KW_THROW)) {
            return parseThrowStmt();
        }
        if (parser.check(IDENTIFIER) && "guard".equals(parser.current.getLexeme())) {
            return parseGuardStmt();
        }
        if (parser.check(KW_USE)) {
            return parseUseStmt();
        }
        // 声明语句（fun, class, val, var, enum, interface, object 等）
        if (parser.isDeclarationStart()) {
            SourceLocation loc = parser.location();
            Declaration decl = parser.parseDeclaration();
            return new DeclarationStmt(loc, decl);
        }

        // 表达式语句
        return parseExpressionStmt();
    }

    Block parseBlock() {
        SourceLocation loc = parser.location();
        parser.expect(LBRACE, "Expected '{'");

        List<Statement> statements = new ArrayList<Statement>();
        parser.skipSeparators();

        while (!parser.check(RBRACE) && !parser.isAtEnd()) {
            try {
                statements.add(parseStatement());
            } catch (ParseException e) {
                // 块内容错恢复：跳到下一个语句边界，避免丢失整个外层声明
                synchronizeInBlock();
            }
            parser.skipSeparators();
        }

        parser.expect(RBRACE, "Expected '}'");
        return new Block(loc, statements);
    }

    /**
     * 块内错误恢复：跳过 token 直到找到语句边界（换行/分号）或块结束（}）
     */
    void synchronizeInBlock() {
        while (!parser.isAtEnd()) {
            if (parser.check(RBRACE)) return;
            if (parser.check(NEWLINE) || parser.check(SEMICOLON)) {
                parser.advance();
                return;
            }
            parser.advance();
        }
    }

    Statement parseExpressionStmt() {
        SourceLocation loc = parser.location();
        Expression expr = parser.parseExpression();
        parser.matchAny(NEWLINE, SEMICOLON);
        return new ExpressionStmt(loc, expr);
    }

    Statement parseLocalVariable() {
        SourceLocation loc = parser.location();
        boolean isVal = parser.match(KW_VAL);
        if (!isVal) {
            parser.expect(KW_VAR, "Expected 'val' or 'var'");
        }

        if (parser.check(LPAREN)) {
            // 解构声明: val (a, b) = expr
            DestructuringDecl decl = parser.declParser.parseDestructuringBody(loc, isVal);
            return new DeclarationStmt(loc, decl);
        }

        // 普通属性声明
        PropertyDecl decl = parser.declParser.parsePropertyDeclBody(loc, Collections.<com.novalang.compiler.ast.decl.Annotation>emptyList(),
                Collections.<com.novalang.compiler.ast.Modifier>emptyList(), isVal);
        return new DeclarationStmt(loc, decl);
    }

    private IfStmt parseIfStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_IF, "Expected 'if'");
        parser.expect(LPAREN, "Expected '('");

        String bindingName = null;
        Expression condition;

        // if-let: if (val x = expr)
        if (parser.match(KW_VAL)) {
            bindingName = parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
            parser.expect(ASSIGN, "Expected '='");
            condition = parser.parseExpression();
        } else {
            condition = parser.parseExpression();
        }

        parser.expect(RPAREN, "Expected ')'");

        Statement thenBranch;
        if (parser.check(LBRACE)) {
            thenBranch = parseBlock();
        } else {
            thenBranch = parseStatement();
        }

        Statement elseBranch = null;
        parser.skipNewlines();
        if (parser.match(KW_ELSE)) {
            if (parser.check(LBRACE)) {
                elseBranch = parseBlock();
            } else {
                elseBranch = parseStatement();
            }
        }

        return new IfStmt(loc, condition, bindingName, thenBranch, elseBranch);
    }

    private WhenStmt parseWhenStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_WHEN, "Expected 'when'");

        Expression subject = null;
        String bindingName = null;
        if (parser.match(LPAREN)) {
            // when-let: when (val x = expr)
            if (parser.match(KW_VAL)) {
                bindingName = parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
                parser.expect(ASSIGN, "Expected '='");
            }
            subject = parser.parseExpression();
            parser.expect(RPAREN, "Expected ')'");
        }

        parser.expect(LBRACE, "Expected '{'");
        parser.skipSeparators();

        List<WhenBranch> branches = new ArrayList<WhenBranch>();
        Statement elseBranch = null;

        while (!parser.check(RBRACE) && !parser.isAtEnd()) {
            parser.skipSeparators();
            if (parser.check(RBRACE)) break;
            if (parser.match(KW_ELSE)) {
                parser.expect(ARROW, "Expected '->'");
                if (parser.check(LBRACE)) {
                    elseBranch = parseBlock();
                } else {
                    elseBranch = parseExpressionStmt();
                }
                parser.skipSeparators();
                break;
            }
            branches.add(parseWhenBranch());
            parser.skipSeparators();
        }

        parser.expect(RBRACE, "Expected '}'");
        return new WhenStmt(loc, subject, bindingName, branches, elseBranch);
    }

    WhenBranch parseWhenBranch() {
        SourceLocation loc = parser.location();
        List<WhenBranch.WhenCondition> conditions = new ArrayList<WhenBranch.WhenCondition>();

        do {
            conditions.add(parseWhenCondition());
        } while (parser.match(COMMA));

        parser.expect(ARROW, "Expected '->'");

        Statement body;
        if (parser.check(LBRACE)) {
            body = parseBlock();
        } else {
            body = parseExpressionStmt();
        }

        return new WhenBranch(loc, conditions, body);
    }

    WhenBranch.WhenCondition parseWhenCondition() {
        SourceLocation loc = parser.location();

        if (parser.match(KW_IS)) {
            TypeRef type = parser.parseType();
            return new WhenBranch.TypeCondition(loc, type, false);
        }
        if (parser.check(NOT) && (parser.checkAhead(KW_IS) || parser.checkAhead(KW_IN))) {
            parser.advance(); // consume '!' only when next is 'is' or 'in'
            if (parser.match(KW_IS)) {
                TypeRef type = parser.parseType();
                return new WhenBranch.TypeCondition(loc, type, true);
            }
            if (parser.match(KW_IN)) {
                Expression range = parser.parseExpression();
                return new WhenBranch.RangeCondition(loc, range, true);
            }
        }
        if (parser.match(KW_IN)) {
            Expression range = parser.parseExpression();
            return new WhenBranch.RangeCondition(loc, range, false);
        }

        Expression expr = parser.parseExpression();
        return new WhenBranch.ExpressionCondition(loc, expr);
    }

    private ForStmt parseForStmt(String label) {
        SourceLocation loc = parser.location();
        parser.expect(KW_FOR, "Expected 'for'");
        parser.expect(LPAREN, "Expected '('");

        List<String> variables = new ArrayList<String>();
        if (parser.match(LPAREN)) {
            // 解构
            do {
                variables.add(parseForVariable());
            } while (parser.match(COMMA));
            parser.expect(RPAREN, "Expected ')'");
        } else {
            variables.add(parseForVariable());
        }

        parser.expect(KW_IN, "Expected 'in'");
        Expression iterable = parser.parseExpression();
        parser.expect(RPAREN, "Expected ')'");

        Statement body;
        if (parser.check(LBRACE)) {
            body = parseBlock();
        } else {
            body = parseStatement();
        }

        return new ForStmt(loc, label, variables, iterable, body);
    }

    private String parseForVariable() {
        if (parser.match(UNDERSCORE)) {
            return "_";
        }
        return parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
    }

    private WhileStmt parseWhileStmt(String label) {
        SourceLocation loc = parser.location();
        parser.expect(KW_WHILE, "Expected 'while'");
        parser.expect(LPAREN, "Expected '('");
        Expression condition = parser.parseExpression();
        parser.expect(RPAREN, "Expected ')'");

        Statement body;
        if (parser.check(LBRACE)) {
            body = parseBlock();
        } else {
            body = parseStatement();
        }

        return new WhileStmt(loc, label, condition, body);
    }

    private DoWhileStmt parseDoWhileStmt(String label) {
        SourceLocation loc = parser.location();
        parser.expect(KW_DO, "Expected 'do'");
        Block body = parseBlock();
        parser.expect(KW_WHILE, "Expected 'while'");
        parser.expect(LPAREN, "Expected '('");
        Expression condition = parser.parseExpression();
        parser.expect(RPAREN, "Expected ')'");

        return new DoWhileStmt(loc, label, body, condition);
    }

    private TryStmt parseTryStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_TRY, "Expected 'try'");
        Block tryBlock = parseBlock();

        List<CatchClause> catchClauses = new ArrayList<CatchClause>();
        parser.skipNewlines();
        while (parser.check(KW_CATCH)) {
            catchClauses.add(parseCatchClause());
            parser.skipNewlines();
        }

        Block finallyBlock = null;
        parser.skipNewlines();
        if (parser.match(KW_FINALLY)) {
            finallyBlock = parseBlock();
        }

        return new TryStmt(loc, tryBlock, catchClauses, finallyBlock);
    }

    CatchClause parseCatchClause() {
        SourceLocation loc = parser.location();
        parser.expect(KW_CATCH, "Expected 'catch'");
        parser.expect(LPAREN, "Expected '('");
        String paramName = parser.expect(IDENTIFIER, "Expected parameter name").getLexeme();
        TypeRef paramType = null;
        if (parser.match(COLON)) {
            paramType = parser.parseType();
        }
        parser.expect(RPAREN, "Expected ')'");
        Block body = parseBlock();

        return new CatchClause(loc, paramName, paramType, body);
    }

    ReturnStmt parseReturnStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_RETURN, "Expected 'return'");

        String label = null;
        if (parser.match(AT)) {
            label = parser.expect(IDENTIFIER, "Expected label").getLexeme();
        }

        Expression value = null;
        if (!parser.checkAny(NEWLINE, SEMICOLON, RBRACE, EOF)) {
            value = parser.parseExpression();
        }

        parser.matchAny(NEWLINE, SEMICOLON);
        return new ReturnStmt(loc, value, label);
    }

    BreakStmt parseBreakStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_BREAK, "Expected 'break'");

        String label = null;
        if (parser.match(AT)) {
            label = parser.expect(IDENTIFIER, "Expected label").getLexeme();
        }

        parser.matchAny(NEWLINE, SEMICOLON);
        return new BreakStmt(loc, label);
    }

    ContinueStmt parseContinueStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_CONTINUE, "Expected 'continue'");

        String label = null;
        if (parser.match(AT)) {
            label = parser.expect(IDENTIFIER, "Expected label").getLexeme();
        }

        parser.matchAny(NEWLINE, SEMICOLON);
        return new ContinueStmt(loc, label);
    }

    ThrowStmt parseThrowStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_THROW, "Expected 'throw'");
        Expression exception = parser.parseExpression();
        parser.matchAny(NEWLINE, SEMICOLON);
        return new ThrowStmt(loc, exception);
    }

    private GuardStmt parseGuardStmt() {
        SourceLocation loc = parser.location();
        parser.expect(IDENTIFIER, "Expected 'guard'"); // 软关键词
        parser.expect(KW_VAL, "Expected 'val'");
        String bindingName = parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
        parser.expect(ASSIGN, "Expected '='");
        Expression expression = parser.parseExpression();
        parser.expect(KW_ELSE, "Expected 'else'");

        Statement elseBody;
        if (parser.check(LBRACE)) {
            elseBody = parseBlock();
        } else if (parser.check(KW_RETURN)) {
            elseBody = parseReturnStmt();
        } else if (parser.check(KW_THROW)) {
            elseBody = parseThrowStmt();
        } else if (parser.check(KW_BREAK)) {
            elseBody = parseBreakStmt();
        } else if (parser.check(KW_CONTINUE)) {
            elseBody = parseContinueStmt();
        } else {
            throw new ParseException("Expected block, return, throw, break or continue", parser.current);
        }

        return new GuardStmt(loc, bindingName, expression, elseBody);
    }

    private UseStmt parseUseStmt() {
        SourceLocation loc = parser.location();
        parser.expect(KW_USE, "Expected 'use'");
        parser.expect(LPAREN, "Expected '('");

        List<UseStmt.UseBinding> bindings = new ArrayList<UseStmt.UseBinding>();
        do {
            bindings.add(parseUseBinding());
        } while (parser.match(COMMA));

        parser.expect(RPAREN, "Expected ')'");
        Block body = parseBlock();

        return new UseStmt(loc, bindings, body);
    }

    private UseStmt.UseBinding parseUseBinding() {
        SourceLocation loc = parser.location();
        parser.expect(KW_VAL, "Expected 'val'");
        String name = parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
        parser.expect(ASSIGN, "Expected '='");
        Expression initializer = parser.parseExpression();
        return new UseStmt.UseBinding(loc, name, initializer);
    }
}
