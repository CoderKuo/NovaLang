package com.novalang.compiler.parser;

import com.novalang.compiler.ast.stmt.Block;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.Declaration;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.lexer.Token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.novalang.compiler.lexer.TokenType.*;

/**
 * 表达式解析辅助类
 */
class ExprParser {

    final Parser parser;

    ExprParser(Parser parser) {
        this.parser = parser;
    }

    Expression parseExpression() {
        return parseAssignExpr();
    }

    // 赋值表达式（最低优先级，右结合）
    private Expression parseAssignExpr() {
        Expression left = parseTernaryExpr();

        // 检查赋值运算符
        if (parser.checkAny(ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, MUL_ASSIGN, DIV_ASSIGN,
                     MOD_ASSIGN, NULL_COALESCE_ASSIGN, OR_ASSIGN, AND_ASSIGN)) {
            Token op = parser.advance();
            SourceLocation loc = parser.previousLocation();
            Expression right = parseAssignExpr();  // 右结合

            AssignExpr.AssignOp assignOp;
            switch (op.getType()) {
                case ASSIGN: assignOp = AssignExpr.AssignOp.ASSIGN; break;
                case PLUS_ASSIGN: assignOp = AssignExpr.AssignOp.ADD_ASSIGN; break;
                case MINUS_ASSIGN: assignOp = AssignExpr.AssignOp.SUB_ASSIGN; break;
                case MUL_ASSIGN: assignOp = AssignExpr.AssignOp.MUL_ASSIGN; break;
                case DIV_ASSIGN: assignOp = AssignExpr.AssignOp.DIV_ASSIGN; break;
                case MOD_ASSIGN: assignOp = AssignExpr.AssignOp.MOD_ASSIGN; break;
                case NULL_COALESCE_ASSIGN: assignOp = AssignExpr.AssignOp.NULL_COALESCE; break;
                case OR_ASSIGN: assignOp = AssignExpr.AssignOp.OR_ASSIGN; break;
                case AND_ASSIGN: assignOp = AssignExpr.AssignOp.AND_ASSIGN; break;
                default: throw new ParseException("Unexpected assignment operator", op);
            }
            return new AssignExpr(loc, left, assignOp, right);
        }

        return left;
    }

    // 三元表达式 condition ? thenExpr : elseExpr（右结合，优先级介于赋值和管道之间）
    private Expression parseTernaryExpr() {
        Expression condition = parsePipelineExpr();

        if (parser.match(QUESTION)) {
            SourceLocation loc = parser.previousLocation();
            Expression thenExpr = parseTernaryExpr();  // 右结合
            parser.expect(COLON, "Expected ':' in ternary expression");
            Expression elseExpr = parseTernaryExpr();  // 右结合
            return new ConditionalExpr(loc, condition, thenExpr, elseExpr);
        }

        return condition;
    }

    // 管道 |>
    private Expression parsePipelineExpr() {
        Expression left = parseDisjunctionExpr();

        while (true) {
            parser.mark();
            parser.skipNewlines();
            if (parser.match(PIPELINE)) {
                parser.commitMark(); // 提交（不回溯）
                SourceLocation loc = parser.previousLocation();
                Expression right = parseDisjunctionExpr();
                left = new PipelineExpr(loc, left, right);
            } else {
                parser.reset();
                break;
            }
        }

        return left;
    }

    // 逻辑或 ||
    private Expression parseDisjunctionExpr() {
        Expression left = parseConjunctionExpr();

        while (parser.match(OR)) {
            SourceLocation loc = parser.previousLocation();
            Expression right = parseConjunctionExpr();
            left = new BinaryExpr(loc, left, BinaryExpr.BinaryOp.OR, right);
        }

        return left;
    }

    // 逻辑与 &&
    private Expression parseConjunctionExpr() {
        Expression left = parseEqualityExpr();

        while (parser.match(AND)) {
            SourceLocation loc = parser.previousLocation();
            Expression right = parseEqualityExpr();
            left = new BinaryExpr(loc, left, BinaryExpr.BinaryOp.AND, right);
        }

        return left;
    }

    // 相等性 == != === !== （支持链式比较 a == b == c -> a == b && b == c）
    private Expression parseEqualityExpr() {
        Expression left = parseComparisonExpr();

        if (!parser.checkAny(EQ, NE, REF_EQ, REF_NE)) {
            return left;
        }

        // 收集所有相等性比较
        Expression result = null;
        Expression prevRight = left;

        while (parser.checkAny(EQ, NE, REF_EQ, REF_NE)) {
            Token op = parser.advance();
            SourceLocation loc = parser.previousLocation();
            Expression right = parseComparisonExpr();
            BinaryExpr.BinaryOp binOp;
            switch (op.getType()) {
                case EQ: binOp = BinaryExpr.BinaryOp.EQ; break;
                case NE: binOp = BinaryExpr.BinaryOp.NE; break;
                case REF_EQ: binOp = BinaryExpr.BinaryOp.REF_EQ; break;
                case REF_NE: binOp = BinaryExpr.BinaryOp.REF_NE; break;
                default: throw new ParseException("Unexpected operator", op);
            }

            Expression comparison = new BinaryExpr(loc, prevRight, binOp, right);

            if (result == null) {
                result = comparison;
            } else {
                // 链式比较：用 AND 连接
                result = new BinaryExpr(loc, result, BinaryExpr.BinaryOp.AND, comparison);
            }

            prevRight = right;
        }

        return result;
    }

    // 比较 < > <= >= （支持链式比较 a < b < c -> a < b && b < c）
    private Expression parseComparisonExpr() {
        Expression left = parseTypeCheckExpr();

        if (!parser.checkAny(LT, GT, LE, GE)) {
            return left;
        }

        // 收集所有比较
        Expression result = null;
        Expression prevRight = left;

        while (parser.checkAny(LT, GT, LE, GE)) {
            Token op = parser.advance();
            SourceLocation loc = parser.previousLocation();
            Expression right = parseTypeCheckExpr();
            BinaryExpr.BinaryOp binOp;
            switch (op.getType()) {
                case LT: binOp = BinaryExpr.BinaryOp.LT; break;
                case GT: binOp = BinaryExpr.BinaryOp.GT; break;
                case LE: binOp = BinaryExpr.BinaryOp.LE; break;
                case GE: binOp = BinaryExpr.BinaryOp.GE; break;
                default: throw new ParseException("Unexpected operator", op);
            }

            Expression comparison = new BinaryExpr(loc, prevRight, binOp, right);

            if (result == null) {
                result = comparison;
            } else {
                // 链式比较：用 AND 连接
                result = new BinaryExpr(loc, result, BinaryExpr.BinaryOp.AND, comparison);
            }

            prevRight = right;
        }

        return result;
    }

    // 类型检查 is / !is 和类型转换 as / as?
    private Expression parseTypeCheckExpr() {
        Expression left = parseElvisExpr();

        while (true) {
            SourceLocation loc = parser.location();

            if (parser.match(KW_IS)) {
                // is 类型检查
                TypeRef type = parser.parseType();
                left = new TypeCheckExpr(loc, left, type, false);
            } else if (parser.check(NOT) && parser.checkAhead(KW_IS)) {
                // !is 类型检查 - 先检查再消费
                parser.advance();  // consume '!'
                parser.advance();  // consume 'is'
                TypeRef type = parser.parseType();
                left = new TypeCheckExpr(loc, left, type, true);
            } else if (parser.match(KW_AS)) {
                // as 类型转换
                boolean isSafe = parser.match(QUESTION);  // as?
                TypeRef type = parser.parseType();
                left = new TypeCastExpr(loc, left, type, isSafe);
            } else {
                break;
            }
        }

        return left;
    }

    // Elvis ?:
    private Expression parseElvisExpr() {
        Expression left = parseInfixToExpr();

        if (parser.match(ELVIS)) {
            SourceLocation loc = parser.previousLocation();
            Expression right = parseElvisExpr();  // 右结合
            left = new ElvisExpr(loc, left, right);
        }

        return left;
    }

    // 中缀 to（Pair 创建）
    private Expression parseInfixToExpr() {
        Expression left = parseRangeExpr();

        if (parser.check(IDENTIFIER) && "to".equals(parser.current.getLexeme())) {
            SourceLocation loc = parser.location();
            parser.advance(); // consume "to"
            Expression right = parseRangeExpr();
            left = new BinaryExpr(loc, left, BinaryExpr.BinaryOp.TO, right);
        }

        return left;
    }

    // 范围 .. ..<
    private Expression parseRangeExpr() {
        Expression left = parseAdditiveExpr();

        if (parser.matchAny(RANGE, RANGE_EXCLUSIVE)) {
            SourceLocation loc = parser.previousLocation();
            boolean isExclusive = parser.previous.getType() == RANGE_EXCLUSIVE;
            Expression right = parseAdditiveExpr();

            Expression step = null;
            if (parser.check(IDENTIFIER) && "step".equals(parser.current.getLexeme())) {
                parser.advance(); // 消费 "step" 软关键词
                step = parseAdditiveExpr();
            }

            left = new RangeExpr(loc, left, right, step, isExclusive);
        }

        return left;
    }

    // 加减 + -
    Expression parseAdditiveExpr() {
        Expression left = parseMultiplicativeExpr();

        while (parser.checkAny(PLUS, MINUS)) {
            Token op = parser.advance();
            SourceLocation loc = parser.previousLocation();
            Expression right = parseMultiplicativeExpr();
            BinaryExpr.BinaryOp binOp = op.getType() == PLUS ?
                    BinaryExpr.BinaryOp.ADD : BinaryExpr.BinaryOp.SUB;
            left = new BinaryExpr(loc, left, binOp, right);
        }

        return left;
    }

    // 乘除余 * / %
    private Expression parseMultiplicativeExpr() {
        Expression left = parsePrefixExpr();

        while (parser.checkAny(MUL, DIV, MOD)) {
            Token op = parser.advance();
            SourceLocation loc = parser.previousLocation();
            Expression right = parsePrefixExpr();
            BinaryExpr.BinaryOp binOp;
            switch (op.getType()) {
                case MUL: binOp = BinaryExpr.BinaryOp.MUL; break;
                case DIV: binOp = BinaryExpr.BinaryOp.DIV; break;
                case MOD: binOp = BinaryExpr.BinaryOp.MOD; break;
                default: throw new ParseException("Unexpected operator", op);
            }
            left = new BinaryExpr(loc, left, binOp, right);
        }

        return left;
    }

    // 前缀 - + ! ++ --
    private Expression parsePrefixExpr() {
        if (parser.checkAny(MINUS, PLUS, NOT, INC, DEC)) {
            Token op = parser.advance();
            SourceLocation loc = parser.previousLocation();

            // 对于 ++ 和 -- 后面跟字面量的情况，解析为两个单独的运算
            // 例如: --5 -> -(-5), ++5 -> +(+5)
            if ((op.getType() == INC || op.getType() == DEC) && isLiteralStart()) {
                Expression operand = parsePrefixExpr();
                UnaryExpr.UnaryOp singleOp = (op.getType() == INC)
                    ? UnaryExpr.UnaryOp.POS : UnaryExpr.UnaryOp.NEG;
                Expression inner = new UnaryExpr(loc, singleOp, operand, true);
                return new UnaryExpr(loc, singleOp, inner, true);
            }

            Expression operand = parsePrefixExpr();  // 右结合
            UnaryExpr.UnaryOp unaryOp;
            switch (op.getType()) {
                case MINUS: unaryOp = UnaryExpr.UnaryOp.NEG; break;
                case PLUS: unaryOp = UnaryExpr.UnaryOp.POS; break;
                case NOT: unaryOp = UnaryExpr.UnaryOp.NOT; break;
                case INC: unaryOp = UnaryExpr.UnaryOp.INC; break;
                case DEC: unaryOp = UnaryExpr.UnaryOp.DEC; break;
                default: throw new ParseException("Unexpected operator", op);
            }
            return new UnaryExpr(loc, unaryOp, operand, true);
        }

        return parsePostfixExpr();
    }

    // 检查当前 token 是否是字面量的开始
    private boolean isLiteralStart() {
        return parser.checkAny(INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL,
                       CHAR_LITERAL, STRING_LITERAL, RAW_STRING, MULTILINE_STRING,
                       KW_TRUE, KW_FALSE, KW_NULL);
    }

    // 后缀 ++ -- !! ? . ?. () [] :: trailing-lambda
    private Expression parsePostfixExpr() {
        Expression expr = parsePrimaryExpr();

        while (true) {
            // 前瞻：换行后紧跟 . 或 ?. ，视为表达式延续（方法链换行）
            if (parser.check(NEWLINE)) {
                parser.mark();
                parser.skipNewlines();
                if (parser.checkAny(DOT, SAFE_DOT)) {
                    parser.commitMark();
                } else {
                    parser.reset();
                }
            }

            SourceLocation loc = parser.location();

            if (parser.match(INC)) {
                expr = new UnaryExpr(parser.previousLocation(), UnaryExpr.UnaryOp.INC, expr, false);
            } else if (parser.match(DEC)) {
                expr = new UnaryExpr(parser.previousLocation(), UnaryExpr.UnaryOp.DEC, expr, false);
            } else if (parser.match(NOT_NULL)) {
                expr = new NotNullExpr(parser.previousLocation(), expr);
            } else if (parser.check(QUESTION)) {
                // 区分：三元表达式 (? expr : expr) vs 错误传播 (postfix ?)
                if (isTernaryQuestion()) {
                    break; // 退出后缀循环，由 parseTernaryExpr 处理
                }
                parser.advance(); // consume ?
                expr = new ErrorPropagationExpr(parser.previousLocation(), expr);
            } else if (parser.match(DOT)) {
                expr = parseDotMember(loc, expr);
            } else if (parser.match(SAFE_DOT)) {
                expr = parseSafeDotPostfix(loc, expr);
            } else if (parser.match(DOUBLE_COLON)) {
                expr = parseMethodRefPostfix(loc, expr);
            } else if (parser.check(LT) && parser.typeParser.tryParseCallTypeArgs()) {
                List<TypeRef> typeArgs = parser.parseTypeRefList();
                List<CallExpr.Argument> args = parseCallArgs();
                expr = new CallExpr(loc, expr, typeArgs, args, null);
            } else if (parser.check(LPAREN)) {
                List<CallExpr.Argument> args = parseCallArgs();
                expr = new CallExpr(loc, expr, Collections.<TypeRef>emptyList(), args, null);
            } else if (parser.match(LBRACKET)) {
                expr = parseIndexOrSlice(loc, expr);
            } else if (parser.match(SAFE_LBRACKET)) {
                Expression index = parseExpression();
                parser.expect(RBRACKET, "Expected ']' after index");
                expr = new SafeIndexExpr(loc, expr, index);
            } else if (parser.check(LBRACE) && canBeTrailingLambda()) {
                expr = parseTrailingLambdaCall(loc, expr);
            } else {
                break;
            }
        }

        return expr;
    }

    // .member（允许关键字作为成员名，如 s.launch）
    private Expression parseDotMember(SourceLocation loc, Expression target) {
        String member = parser.expectMemberName();
        MemberExpr memberExpr = new MemberExpr(loc, target, member);
        memberExpr.setMemberLocation(parser.previousLocation());
        return memberExpr;
    }

    // ?.member / ?.method(args) / ?.{ scope }
    private Expression parseSafeDotPostfix(SourceLocation loc, Expression target) {
        if (parser.check(LBRACE)) {
            Block block = parser.parseBlock();
            return new ScopeShorthandExpr(loc, target, block);
        }
        String member = parser.expectMemberName();
        List<CallExpr.Argument> args = null;
        if (parser.check(LPAREN)) {
            args = parseCallArgs();
        }
        if (parser.check(LBRACE) && canBeTrailingLambda()) {
            LambdaExpr lambda = parseTrailingLambda();
            if (args == null) args = new ArrayList<CallExpr.Argument>();
            args.add(new CallExpr.Argument(loc, null, lambda, false));
        }
        return new SafeCallExpr(loc, target, member, args);
    }

    // ::method / ::new / ::class
    private Expression parseMethodRefPostfix(SourceLocation loc, Expression target) {
        if (parser.check(IDENTIFIER) && "new".equals(parser.current.getLexeme())) {
            parser.advance();
            return new MethodRefExpr(loc, target, null, "new", true);
        } else if (parser.check(KW_CLASS)) {
            parser.advance();
            return new MethodRefExpr(loc, target, null, "class", false);
        } else {
            String methodName = parser.expect(IDENTIFIER, "Expected method name").getLexeme();
            return new MethodRefExpr(loc, target, null, methodName, false);
        }
    }

    // expr { trailing lambda }
    private Expression parseTrailingLambdaCall(SourceLocation loc, Expression target) {
        LambdaExpr lambda = parseTrailingLambda();
        if (target instanceof CallExpr) {
            CallExpr call = (CallExpr) target;
            return new CallExpr(call.getLocation(), call.getCallee(), call.getTypeArgs(),
                    call.getArgs(), lambda);
        }
        return new CallExpr(loc, target, Collections.<TypeRef>emptyList(),
                Collections.<CallExpr.Argument>emptyList(), lambda);
    }

    private boolean canBeTrailingLambda() {
        // 前一个 token 表示表达式可能是可调用对象时，{ } 视为尾随 Lambda
        // IDENTIFIER: run { }, RPAREN: func() { }, NOT_NULL: obj!! { }, RBRACKET: list[i] { }
        // isKeyword: s.launch { }（关键字作为成员名后的尾随 Lambda）
        if (parser.previous == null) return false;
        switch (parser.previous.getType()) {
            case IDENTIFIER:
            case RPAREN:
            case NOT_NULL:
            case RBRACKET:
                return true;
            default:
                return parser.previous.getType().isKeyword();
        }
    }

    /**
     * 检查当前 QUESTION token 是否是三元表达式的一部分（? expr : expr）
     * 而非错误传播后缀操作符。
     * 通过 mark/reset 向前扫描，在括号深度 0 处寻找 COLON。
     */
    private boolean isTernaryQuestion() {
        parser.mark();
        parser.advance(); // consume ?

        // ? 后紧跟语句终止符/关闭括号/逗号/冒号 → 错误传播
        if (parser.isAtEnd() || parser.checkAny(NEWLINE, SEMICOLON,
                RPAREN, RBRACKET, RBRACE, COMMA, COLON)) {
            parser.reset();
            return false;
        }

        // 向前扫描，在括号深度 0 处寻找 :
        int depth = 0;
        while (!parser.isAtEnd()) {
            if (parser.check(COLON) && depth == 0) {
                parser.reset();
                return true;
            }
            if (parser.checkAny(LPAREN, LBRACKET, LBRACE)) {
                depth++;
            } else if (parser.checkAny(RPAREN, RBRACKET, RBRACE)) {
                if (depth == 0) break; // 不匹配的关闭括号
                depth--;
            } else if (depth == 0 && parser.checkAny(NEWLINE, SEMICOLON)) {
                break; // 语句边界
            }
            parser.advance();
        }
        parser.reset();
        return false;
    }

    List<CallExpr.Argument> parseCallArgs() {
        parser.expect(LPAREN, "Expected '('");
        List<CallExpr.Argument> args = new ArrayList<CallExpr.Argument>();
        parser.skipNewlines();

        if (!parser.check(RPAREN)) {
            do {
                parser.skipNewlines();
                args.add(parseCallArg());
                parser.skipNewlines();
            } while (parser.match(COMMA) && !parser.check(RPAREN));
        }

        parser.expect(RPAREN, "Expected ')'");
        return args;
    }

    private CallExpr.Argument parseCallArg() {
        SourceLocation loc = parser.location();
        boolean isSpread = parser.match(MUL);

        String name = null;
        // 检查是否是命名参数
        if (parser.check(IDENTIFIER) && parser.checkAhead(ASSIGN)) {
            name = parser.advance().getLexeme();
            parser.advance();  // consume '='
        }

        Expression value = parseExpression();
        return new CallExpr.Argument(loc, name, value, isSpread);
    }

    private Expression parseIndexOrSlice(SourceLocation loc, Expression target) {
        // 已经消费了 '['

        // 检查是否是切片
        Expression start = null;
        Expression end = null;
        boolean isSlice = false;
        boolean isExclusive = false;

        // 注意：这里不能用 parseExpression()，因为它会把 "1..3" 解析为 RangeExpr
        // 需要用不包含 range 解析的方法
        if (!parser.check(RANGE) && !parser.check(RANGE_EXCLUSIVE)) {
            start = parseAdditiveExpr();
        }

        if (parser.matchAny(RANGE, RANGE_EXCLUSIVE)) {
            isSlice = true;
            isExclusive = parser.previous.getType() == RANGE_EXCLUSIVE;

            if (!parser.check(RBRACKET)) {
                end = parseAdditiveExpr();
            }
        }

        parser.expect(RBRACKET, "Expected ']'");

        if (isSlice) {
            return new SliceExpr(loc, target, start, end, isExclusive);
        } else {
            return new IndexExpr(loc, target, start);
        }
    }

    // 基础表达式
    private Expression parsePrimaryExpr() {
        SourceLocation loc = parser.location();

        // 数字字面量
        if (parser.checkAny(INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL)) {
            return parseNumericLiteral();
        }

        // 字符/字符串字面量
        if (parser.checkAny(CHAR_LITERAL, STRING_LITERAL, RAW_STRING, MULTILINE_STRING)) {
            return parseStringOrCharLiteral();
        }

        if (parser.match(KW_TRUE)) {
            return new Literal(parser.previousLocation(), true, Literal.LiteralKind.BOOLEAN);
        }
        if (parser.match(KW_FALSE)) {
            return new Literal(parser.previousLocation(), false, Literal.LiteralKind.BOOLEAN);
        }
        if (parser.match(KW_NULL)) {
            return new Literal(parser.previousLocation(), null, Literal.LiteralKind.NULL);
        }

        // 全局函数/构造器引用 ::funcName 或 ::ClassName
        if (parser.match(DOUBLE_COLON)) {
            if (parser.check(IDENTIFIER) && "new".equals(parser.current.getLexeme())) {
                throw new ParseException("Constructor reference requires a type: Type::new", parser.current);
            }
            String name = parser.expect(IDENTIFIER, "Expected function name after '::'").getLexeme();
            return new MethodRefExpr(loc, null, null, name, false);
        }

        // this / super
        if (parser.match(KW_THIS)) {
            String label = null;
            if (parser.match(AT)) {
                label = parser.expect(IDENTIFIER, "Expected label").getLexeme();
            }
            return new ThisExpr(parser.previousLocation(), label);
        }
        if (parser.match(KW_SUPER)) {
            String label = null;
            if (parser.match(AT)) {
                label = parser.expect(IDENTIFIER, "Expected label").getLexeme();
            }
            return new SuperExpr(parser.previousLocation(), label);
        }

        // "it" 现在是软关键词，作为 IDENTIFIER 被 lexer 识别，
        // 会被下方的标识符分支自然处理为 Identifier("it")

        // 占位符 _
        if (parser.match(UNDERSCORE)) {
            return new PlaceholderExpr(parser.previousLocation());
        }

        // 内置类型关键字在表达式位置作为标识符使用（如 String::uppercase, Array<Int>(5)）
        if (parser.matchAny(KW_INT, KW_LONG, KW_FLOAT, KW_DOUBLE, KW_BOOLEAN, KW_CHAR, KW_STRING,
                     KW_ARRAY, KW_ANY, KW_UNIT, KW_NOTHING)) {
            return new Identifier(parser.previousLocation(), parser.previous.getLexeme());
        }

        // 标识符（含软关键字如 internal, open 等）
        if (parser.check(IDENTIFIER) || parser.isSoftKeyword()) {
            String name = parser.advance().getLexeme();
            return new Identifier(parser.previousLocation(), name);
        }

        // 括号表达式
        if (parser.match(LPAREN)) {
            Expression expr = parseExpression();
            parser.expect(RPAREN, "Expected ')'");
            return expr;
        }

        // 列表字面量 [...]
        if (parser.check(LBRACKET)) {
            return parseListLiteral();
        }

        // Map 字面量 #{...}
        if (parser.check(HASH) && parser.checkAhead(LBRACE)) {
            parser.advance();  // consume #
            return parseMapLiteralFull();
        }

        // Lambda / 代码块 {...}
        if (parser.check(LBRACE)) {
            return parseLambda();
        }

        // when / if / try 表达式
        if (parser.check(KW_WHEN)) return parseWhenExpr();
        if (parser.check(KW_IF)) return parseIfExpr();
        if (parser.check(KW_TRY)) return parseTryExpr();

        // await 表达式
        if (parser.match(KW_AWAIT)) {
            Expression operand = parseExpression();
            return new AwaitExpr(parser.previousLocation(), operand);
        }

        // 匿名对象表达式
        if (parser.match(KW_OBJECT)) {
            return parseObjectExpr();
        }

        // return/throw/break/continue 作为表达式（Nothing 类型）
        if (parser.checkAny(KW_RETURN, KW_THROW, KW_BREAK, KW_CONTINUE)) {
            return parseJumpExpr();
        }

        throw new ParseException("Expected expression", parser.current);
    }

    // 数字字面量: INT, LONG, FLOAT, DOUBLE
    private Expression parseNumericLiteral() {
        Token tok = parser.advance();
        SourceLocation loc = parser.previousLocation();
        switch (tok.getType()) {
            case INT_LITERAL:
                return new Literal(loc, tok.getLiteral(), Literal.LiteralKind.INT);
            case LONG_LITERAL:
                return new Literal(loc, tok.getLiteral(), Literal.LiteralKind.LONG);
            case FLOAT_LITERAL:
                return new Literal(loc, tok.getLiteral(), Literal.LiteralKind.FLOAT);
            case DOUBLE_LITERAL:
                return new Literal(loc, tok.getLiteral(), Literal.LiteralKind.DOUBLE);
            default:
                throw new ParseException("Unexpected numeric literal", tok);
        }
    }

    // 字符/字符串字面量
    private Expression parseStringOrCharLiteral() {
        Token tok = parser.advance();
        String value = tok.getLexeme();
        SourceLocation loc = parser.previousLocation();

        if (tok.getType() == CHAR_LITERAL) {
            return new Literal(loc, parser.literalHelper.parseCharValue(value), Literal.LiteralKind.CHAR);
        }

        // 原始字符串不支持插值
        if (tok.getType() == RAW_STRING) {
            return new Literal(loc, parser.literalHelper.parseStringValue(value), Literal.LiteralKind.STRING);
        }

        // 去掉引号得到原始内容
        String content;
        if (value.startsWith("\"\"\"")) {
            content = value.substring(3, value.length() - 3);
        } else {
            content = value.substring(1, value.length() - 1);
        }

        // 检查是否包含插值
        if (parser.literalHelper.hasInterpolation(content)) {
            return parser.literalHelper.buildStringInterpolation(loc, content);
        }
        return new Literal(loc, parser.literalHelper.parseStringValue(value), Literal.LiteralKind.STRING);
    }

    // 匿名对象表达式 object : Interface { ... }
    private Expression parseObjectExpr() {
        List<TypeRef> superTypes = Collections.emptyList();
        List<Expression> superConstructorArgs = Collections.emptyList();
        if (parser.match(COLON)) {
            superTypes = new ArrayList<TypeRef>();
            superTypes.add(parser.parseType());
            if (parser.check(LPAREN)) {
                parser.advance();
                if (!parser.check(RPAREN)) {
                    superConstructorArgs = parseExpressionList();
                }
                parser.expect(RPAREN, "Expected ')'");
            }
            while (parser.match(COMMA)) {
                superTypes.add(parser.parseType());
            }
        }
        List<Declaration> members = Collections.emptyList();
        if (parser.check(LBRACE)) {
            members = parser.declParser.parseClassBody();
        }
        return new ObjectLiteralExpr(parser.previousLocation(), superTypes, members, superConstructorArgs);
    }

    // return/throw/break/continue 作为表达式（Nothing 类型，用于 ?: return 等场景）
    private Expression parseJumpExpr() {
        SourceLocation loc = parser.location();
        switch (parser.current.getType()) {
            case KW_RETURN:
                return new JumpExpr(loc, parser.stmtParser.parseReturnStmt());
            case KW_THROW:
                return new JumpExpr(loc, parser.stmtParser.parseThrowStmt());
            case KW_BREAK:
                return new JumpExpr(loc, parser.stmtParser.parseBreakStmt());
            case KW_CONTINUE:
                return new JumpExpr(loc, parser.stmtParser.parseContinueStmt());
            default:
                throw new ParseException("Expected jump statement", parser.current);
        }
    }

    private Expression parseListLiteral() {
        SourceLocation loc = parser.location();
        parser.expect(LBRACKET, "Expected '['");

        List<Expression> elements = Collections.emptyList();
        if (!parser.check(RBRACKET)) {
            elements = parseExpressionList();
        }

        parser.expect(RBRACKET, "Expected ']'");
        return new CollectionLiteral(loc, CollectionLiteral.CollectionKind.LIST, elements, null);
    }

    private Expression parseLambda() {
        SourceLocation loc = parser.location();
        parser.expect(LBRACE, "Expected '{'");
        parser.skipNewlines();

        // 空 Lambda
        if (parser.check(RBRACE)) {
            parser.advance();
            return new LambdaExpr(loc, Collections.<LambdaExpr.LambdaParam>emptyList(),
                    new Block(loc, Collections.<Statement>emptyList()));
        }

        // Lambda 参数列表判断
        if (parser.check(IDENTIFIER) || parser.check(UNDERSCORE)) {
            if (parser.checkAhead(ARROW)) {
                // Lambda: { x -> ... }
                String paramName = parser.advance().getLexeme();  // consume identifier
                parser.advance();  // consume ->
                List<LambdaExpr.LambdaParam> params = new ArrayList<LambdaExpr.LambdaParam>();
                params.add(new LambdaExpr.LambdaParam(loc, paramName, null));
                return parseLambdaBody(loc, params);
            } else if (parser.checkAhead(COMMA)) {
                // 多参数 Lambda: { x, y -> ... }
                List<LambdaExpr.LambdaParam> params = new ArrayList<LambdaExpr.LambdaParam>();
                params.add(new LambdaExpr.LambdaParam(loc, parser.advance().getLexeme(), null));

                while (parser.match(COMMA)) {
                    String name = parser.expect(IDENTIFIER, "Expected parameter name").getLexeme();
                    params.add(new LambdaExpr.LambdaParam(parser.location(), name, null));
                }

                if (parser.match(ARROW)) {
                    return parseLambdaBody(loc, params);
                }
                throw new ParseException("Expected '->' after lambda parameters", parser.current);
            } else if (parser.checkAhead(COLON)) {
                // { x: Int -> body } — 带类型的 Lambda 参数
                return parseTypedParamLambda(loc);
            }
        }

        // 默认作为无参数 Lambda 处理（隐式 it）
        return parseLambdaBody(loc, Collections.<LambdaExpr.LambdaParam>emptyList());
    }

    /** 解析 #{key: value, ...} (Map) 或 #{elem, ...} (Set) 或 #{} (空Map) */
    private CollectionLiteral parseMapLiteralFull() {
        SourceLocation loc = parser.location();
        parser.expect(LBRACE, "Expected '{'");
        parser.skipNewlines();

        // #{} → 空 Map
        if (parser.check(RBRACE)) {
            parser.advance();
            return new CollectionLiteral(loc, CollectionLiteral.CollectionKind.MAP,
                    null, Collections.<CollectionLiteral.MapEntry>emptyList());
        }

        // 解析第一个表达式，根据是否紧跟 : 判断 Map 还是 Set
        Expression first = parseExpression();

        if (parser.check(COLON)) {
            // Map: #{key: value, ...}
            parser.advance(); // consume ':'
            Expression value = parseExpression();
            List<CollectionLiteral.MapEntry> entries = new ArrayList<CollectionLiteral.MapEntry>();
            entries.add(new CollectionLiteral.MapEntry(parser.location(), first, value));

            while (parser.match(COMMA)) {
                parser.skipNewlines();
                if (parser.check(RBRACE)) break;
                Expression key = parseExpression();
                parser.expect(COLON, "Expected ':'");
                value = parseExpression();
                entries.add(new CollectionLiteral.MapEntry(parser.location(), key, value));
            }

            parser.expect(RBRACE, "Expected '}'");
            return new CollectionLiteral(loc, CollectionLiteral.CollectionKind.MAP, null, entries);
        } else {
            // Set: #{elem, ...}
            List<Expression> elements = new ArrayList<Expression>();
            elements.add(first);

            while (parser.match(COMMA)) {
                parser.skipNewlines();
                if (parser.check(RBRACE)) break;
                elements.add(parseExpression());
            }

            parser.expect(RBRACE, "Expected '}'");
            return new CollectionLiteral(loc, CollectionLiteral.CollectionKind.SET, elements, null);
        }
    }

    private LambdaExpr parseTrailingLambda() {
        SourceLocation loc = parser.location();
        parser.expect(LBRACE, "Expected '{'");
        parser.skipNewlines();

        // 解析参数
        List<LambdaExpr.LambdaParam> params = Collections.emptyList();

        if ((parser.check(IDENTIFIER) || parser.check(UNDERSCORE)) && parser.checkAhead(ARROW)) {
            params = new ArrayList<LambdaExpr.LambdaParam>();
            params.add(new LambdaExpr.LambdaParam(parser.location(), parser.advance().getLexeme(), null));
            parser.advance();  // consume ->
        } else if ((parser.check(IDENTIFIER) || parser.check(UNDERSCORE)) && parser.checkAhead(COMMA)) {
            params = new ArrayList<LambdaExpr.LambdaParam>();
            do {
                String paramName;
                if (parser.check(UNDERSCORE)) {
                    paramName = parser.advance().getLexeme();
                } else {
                    paramName = parser.expect(IDENTIFIER, "Expected parameter").getLexeme();
                }
                params.add(new LambdaExpr.LambdaParam(parser.location(), paramName, null));
            } while (parser.match(COMMA) && !parser.check(ARROW));

            if (parser.match(ARROW)) {
                // OK
            }
        }

        return parseLambdaBody(loc, params);
    }

    /** 解析带类型注解的 Lambda: { x: Int -> body } 或 { x: Int, y: String -> body } */
    private LambdaExpr parseTypedParamLambda(SourceLocation loc) {
        List<LambdaExpr.LambdaParam> params = new ArrayList<LambdaExpr.LambdaParam>();
        do {
            SourceLocation paramLoc = parser.location();
            String name = parser.expect(IDENTIFIER, "Expected parameter name").getLexeme();
            TypeRef type = null;
            if (parser.match(COLON)) {
                type = parser.parseType();
            }
            params.add(new LambdaExpr.LambdaParam(paramLoc, name, type));
        } while (parser.match(COMMA));

        parser.expect(ARROW, "Expected '->' after lambda parameters");
        return parseLambdaBody(loc, params);
    }

    private LambdaExpr parseLambdaBody(SourceLocation loc, List<LambdaExpr.LambdaParam> params) {
        parser.skipSeparators();

        List<Statement> statements = new ArrayList<Statement>();
        while (!parser.check(RBRACE) && !parser.isAtEnd()) {
            statements.add(parser.parseStatement());
            parser.skipSeparators();
        }

        parser.expect(RBRACE, "Expected '}'");
        return new LambdaExpr(loc, params, new Block(loc, statements));
    }

    private WhenExpr parseWhenExpr() {
        SourceLocation loc = parser.location();
        parser.expect(KW_WHEN, "Expected 'when'");

        Expression subject = null;
        String bindingName = null;
        if (parser.match(LPAREN)) {
            // when(val result = expr) — 带绑定的 subject
            if (parser.match(KW_VAL)) {
                bindingName = parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
                parser.expect(ASSIGN, "Expected '='");
            }
            subject = parseExpression();
            parser.expect(RPAREN, "Expected ')'");
        }

        parser.expect(LBRACE, "Expected '{'");
        parser.skipSeparators();

        List<WhenBranch> branches = new ArrayList<WhenBranch>();
        Expression elseExpr = null;

        while (!parser.check(RBRACE) && !parser.isAtEnd()) {
            parser.skipSeparators();
            if (parser.check(RBRACE)) break;
            if (parser.match(KW_ELSE)) {
                parser.expect(ARROW, "Expected '->'");
                elseExpr = parseExpression();
                parser.skipSeparators();
                break;
            }
            branches.add(parser.stmtParser.parseWhenBranch());
            parser.skipSeparators();
        }

        parser.expect(RBRACE, "Expected '}'");
        return new WhenExpr(loc, subject, bindingName, branches, elseExpr);
    }

    private IfExpr parseIfExpr() {
        SourceLocation loc = parser.location();
        parser.expect(KW_IF, "Expected 'if'");
        parser.expect(LPAREN, "Expected '('");

        String bindingName = null;
        Expression condition;

        if (parser.match(KW_VAL)) {
            bindingName = parser.expect(IDENTIFIER, "Expected variable name").getLexeme();
            parser.expect(ASSIGN, "Expected '='");
            condition = parseExpression();
        } else {
            condition = parseExpression();
        }

        parser.expect(RPAREN, "Expected ')'");
        Expression thenExpr = parseExpression();
        parser.expect(KW_ELSE, "Expected 'else'");
        Expression elseExpr = parseExpression();

        return new IfExpr(loc, condition, bindingName, thenExpr, elseExpr);
    }

    private TryExpr parseTryExpr() {
        SourceLocation loc = parser.location();
        parser.expect(KW_TRY, "Expected 'try'");
        Block tryBlock = parser.parseBlock();

        List<CatchClause> catchClauses = new ArrayList<CatchClause>();
        parser.skipNewlines();
        while (parser.check(KW_CATCH)) {
            catchClauses.add(parser.stmtParser.parseCatchClause());
            parser.skipNewlines();
        }

        Block finallyBlock = null;
        parser.skipNewlines();
        if (parser.match(KW_FINALLY)) {
            finallyBlock = parser.parseBlock();
        }

        return new TryExpr(loc, tryBlock, catchClauses, finallyBlock);
    }

    List<Expression> parseExpressionList() {
        List<Expression> exprs = new ArrayList<Expression>();
        do {
            parser.skipNewlines();
            if (parser.check(RBRACKET) || parser.check(RPAREN) || parser.check(RBRACE)) break;
            // 支持 Spread 操作符 *expr
            if (parser.match(MUL)) {
                SourceLocation loc = parser.previousLocation();
                Expression operand = parseExpression();
                exprs.add(new SpreadExpr(loc, operand));
            } else {
                exprs.add(parseExpression());
            }
        } while (parser.match(COMMA));
        return exprs;
    }
}
