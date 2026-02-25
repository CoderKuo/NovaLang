package com.novalang.compiler.parser;

import com.novalang.compiler.ast.decl.QualifiedName;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.novalang.compiler.lexer.TokenType.*;

/**
 * 类型解析辅助类
 */
class TypeParser {

    final Parser parser;

    TypeParser(Parser parser) {
        this.parser = parser;
    }

    List<TypeParameter> parseTypeParams() {
        parser.expect(LT, "Expected '<'");
        List<TypeParameter> params = new ArrayList<TypeParameter>();

        do {
            params.add(parseTypeParam());
        } while (parser.match(COMMA));

        parser.expect(GT, "Expected '>'");
        return params;
    }

    TypeParameter parseTypeParam() {
        SourceLocation loc = parser.location();

        boolean isReified = parser.match(KW_REIFIED);

        TypeArgument.Variance variance = TypeArgument.Variance.INVARIANT;
        if (parser.match(KW_IN)) {
            variance = TypeArgument.Variance.IN;
        } else if (parser.check(IDENTIFIER) && "out".equals(parser.current.getLexeme())) {
            parser.advance(); // 消费 "out" 软关键词
            variance = TypeArgument.Variance.OUT;
        }

        String name = parser.expect(IDENTIFIER, "Expected type parameter name").getLexeme();

        TypeRef upperBound = null;
        if (parser.match(COLON)) {
            upperBound = parseType();
        }

        return new TypeParameter(loc, name, variance, upperBound, isReified);
    }

    TypeRef parseType() {
        SourceLocation loc = parser.location();

        // 函数类型: (A, B) -> C
        if (parser.check(LPAREN)) {
            return parseFunctionTypeOrParenthesized();
        }

        // suspend 函数类型
        if (parser.match(KW_SUSPEND)) {
            return parseFunctionType(loc, true);
        }

        // 简单类型或泛型类型
        TypeRef type = parseSimpleType();

        // 带接收者的函数类型: Type.() -> R 或 Type.(A, B) -> R
        if (parser.check(DOT) && parser.checkAhead(LPAREN)) {
            parser.advance(); // consume '.'
            parser.expect(LPAREN, "Expected '('");
            List<TypeRef> paramTypes = new ArrayList<TypeRef>();
            if (!parser.check(RPAREN)) {
                do {
                    paramTypes.add(parseType());
                } while (parser.match(COMMA));
            }
            parser.expect(RPAREN, "Expected ')'");
            parser.expect(ARROW, "Expected '->'");
            TypeRef returnType = parseType();
            return new FunctionType(loc, type, paramTypes, returnType, false);
        }

        // 可空类型
        while (parser.match(QUESTION)) {
            type = new NullableType(loc, type);
        }

        return type;
    }

    private TypeRef parseSimpleType() {
        SourceLocation loc = parser.location();
        QualifiedName name = parseTypeName();

        // 泛型参数
        if (parser.check(LT)) {
            List<TypeArgument> typeArgs = parseTypeArgs();
            return new GenericType(loc, name, typeArgs);
        }

        return new SimpleType(loc, name);
    }

    /**
     * 解析类型名称（包括内置类型关键字和普通标识符）
     */
    private QualifiedName parseTypeName() {
        SourceLocation loc = parser.location();
        List<String> parts = new ArrayList<String>();

        // 第一部分：可以是标识符或内置类型关键字
        String firstName = parseTypeIdentifier();
        parts.add(firstName);

        // 后续部分：只能是标识符（遇到 .( 停止，可能是带接收者的函数类型）
        while (parser.check(DOT) && !parser.checkAhead(MUL) && !parser.checkAhead(LPAREN)) {
            parser.advance();  // consume '.'
            parts.add(parser.expect(IDENTIFIER, "Expected identifier").getLexeme());
        }

        return new QualifiedName(loc, parts);
    }

    /**
     * 解析类型标识符（可以是内置类型关键字或普通标识符）
     */
    private String parseTypeIdentifier() {
        // 内置类型关键字
        if (parser.matchAny(KW_INT, KW_LONG, KW_FLOAT, KW_DOUBLE, KW_BOOLEAN, KW_CHAR, KW_STRING,
                     KW_ARRAY, KW_ANY, KW_UNIT, KW_NOTHING)) {
            return parser.previous.getLexeme();
        }

        // 普通标识符
        return parser.expect(IDENTIFIER, "Expected type name").getLexeme();
    }

    /**
     * 试探性解析：当前 < 后面是否为类型参数列表，后面紧跟 (
     * 仅检测，不消费 token（使用 mark/reset）
     */
    boolean tryParseCallTypeArgs() {
        if (!parser.check(LT)) return false;
        parser.mark();
        try {
            parser.advance(); // consume '<'
            int depth = 1;
            while (depth > 0 && !parser.check(EOF)) {
                if (parser.check(LT)) depth++;
                else if (parser.check(GT)) depth--;
                if (depth > 0) parser.advance();
            }
            if (depth != 0) return false;
            parser.advance(); // consume final '>'
            boolean result = parser.check(LPAREN);
            return result;
        } catch (Exception e) {
            return false;
        } finally {
            parser.reset();
        }
    }

    /**
     * 解析 <Type1, Type2, ...> 类型引用列表（用于函数调用的类型参数）
     */
    List<TypeRef> parseTypeRefList() {
        parser.expect(LT, "Expected '<'");
        List<TypeRef> types = new ArrayList<TypeRef>();
        do {
            types.add(parseType());
        } while (parser.match(COMMA));
        parser.expect(GT, "Expected '>'");
        return types;
    }

    List<TypeArgument> parseTypeArgs() {
        parser.expect(LT, "Expected '<'");
        List<TypeArgument> args = new ArrayList<TypeArgument>();

        do {
            args.add(parseTypeArg());
        } while (parser.match(COMMA));

        parser.expect(GT, "Expected '>'");
        return args;
    }

    private TypeArgument parseTypeArg() {
        SourceLocation loc = parser.location();

        // 通配符 *
        if (parser.match(MUL)) {
            return new TypeArgument(loc, TypeArgument.Variance.INVARIANT, null, true);
        }

        TypeArgument.Variance variance = TypeArgument.Variance.INVARIANT;
        if (parser.match(KW_IN)) {
            variance = TypeArgument.Variance.IN;
        } else if (parser.check(IDENTIFIER) && "out".equals(parser.current.getLexeme())) {
            parser.advance(); // 消费 "out" 软关键词
            variance = TypeArgument.Variance.OUT;
        }

        TypeRef type = parseType();
        return new TypeArgument(loc, variance, type, false);
    }

    private TypeRef parseFunctionTypeOrParenthesized() {
        SourceLocation loc = parser.location();
        parser.expect(LPAREN, "Expected '('");

        // 收集括号内的类型
        List<TypeRef> paramTypes = new ArrayList<TypeRef>();
        if (!parser.check(RPAREN)) {
            do {
                paramTypes.add(parseType());
            } while (parser.match(COMMA));
        }
        parser.expect(RPAREN, "Expected ')'");

        // 检查是否是函数类型
        if (parser.match(ARROW)) {
            TypeRef returnType = parseType();
            return new FunctionType(loc, null, paramTypes, returnType, false);
        }

        // 括号类型（单个类型）
        if (paramTypes.size() == 1) {
            TypeRef inner = paramTypes.get(0);
            // 可空
            if (parser.match(QUESTION)) {
                return new NullableType(loc, inner);
            }
            return inner;
        }

        throw new ParseException("Tuple types (A, B) are not supported. Use a function type (A, B) -> C instead", parser.current);
    }

    private TypeRef parseFunctionType(SourceLocation loc, boolean isSuspend) {
        List<TypeRef> paramTypes = new ArrayList<TypeRef>();

        if (parser.check(LPAREN)) {
            parser.expect(LPAREN, "Expected '('");
            if (!parser.check(RPAREN)) {
                do {
                    paramTypes.add(parseType());
                } while (parser.match(COMMA));
            }
            parser.expect(RPAREN, "Expected ')'");
        }

        parser.expect(ARROW, "Expected '->'");
        TypeRef returnType = parseType();

        return new FunctionType(loc, null, paramTypes, returnType, isSuspend);
    }
}
