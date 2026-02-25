package com.novalang.compiler.parser;

import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.stmt.Block;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.type.NullableType;
import com.novalang.compiler.ast.type.SimpleType;
import com.novalang.compiler.ast.type.TypeParameter;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.novalang.compiler.lexer.TokenType.*;

/**
 * 声明解析辅助类
 */
class DeclParser {

    final Parser parser;

    /** parseNameWithOptionalReceiver 的返回结果 */
    static final class ParsedName {
        final String name;
        final TypeRef receiverType;
        ParsedName(String name, TypeRef receiverType) {
            this.name = name;
            this.receiverType = receiverType;
        }
    }

    DeclParser(Parser parser) {
        this.parser = parser;
    }

    Declaration parseDeclaration() {
        parser.skipNewlines();

        // 解析注解和修饰符
        List<Annotation> annotations = parseAnnotations();
        List<Modifier> modifiers = parseModifiers();

        // annotation class — 软关键词
        if (parser.check(IDENTIFIER) && "annotation".equals(parser.current.getLexeme()) && parser.checkAhead(KW_CLASS)) {
            parser.advance(); // consume 'annotation'
            return parseClassDecl(annotations, modifiers, true);
        }

        if (parser.check(KW_CLASS)) {
            return parseClassDecl(annotations, modifiers);
        } else if (parser.check(KW_INTERFACE)) {
            return parseInterfaceDecl(annotations, modifiers);
        } else if (parser.check(KW_OBJECT) || parser.check(KW_COMPANION)) {
            return parseObjectDecl(annotations, modifiers);
        } else if (parser.check(KW_ENUM)) {
            return parseEnumDecl(annotations, modifiers);
        } else if (parser.check(IDENTIFIER) && "constructor".equals(parser.current.getLexeme())) {
            return parseConstructorDecl(annotations, modifiers);
        } else if (parser.check(IDENTIFIER) && "init".equals(parser.current.getLexeme())) {
            return parseInitBlockDecl();
        } else if (parser.check(KW_FUN)) {
            return parseFunDecl(annotations, modifiers);
        } else if (parser.checkAny(KW_VAL, KW_VAR)) {
            // 检查是否是解构声明: val (a, b) = expr
            if (parser.checkAhead(LPAREN)) {
                return parseDestructuringDecl();
            }
            return parsePropertyDecl(annotations, modifiers);
        } else if (parser.check(KW_TYPEALIAS)) {
            return parseTypeAliasDecl(annotations, modifiers);
        } else {
            throw new ParseException("Expected declaration", parser.current);
        }
    }

    List<Annotation> parseAnnotations() {
        List<Annotation> annotations = new ArrayList<Annotation>();
        while (parser.check(AT)) {
            annotations.add(parseAnnotation());
        }
        return annotations;
    }

    private Annotation parseAnnotation() {
        SourceLocation loc = parser.location();
        parser.expect(AT, "Expected '@'");
        String name = parser.expect(IDENTIFIER, "Expected annotation name").getLexeme();

        List<Annotation.AnnotationArg> args = Collections.emptyList();
        if (parser.match(LPAREN)) {
            args = parseAnnotationArgs();
            parser.expect(RPAREN, "Expected ')'");
        }

        return new Annotation(loc, name, args);
    }

    private List<Annotation.AnnotationArg> parseAnnotationArgs() {
        List<Annotation.AnnotationArg> args = new ArrayList<Annotation.AnnotationArg>();
        if (!parser.check(RPAREN)) {
            do {
                args.add(parseAnnotationArg());
            } while (parser.match(COMMA));
        }
        return args;
    }

    private Annotation.AnnotationArg parseAnnotationArg() {
        SourceLocation loc = parser.location();
        String name = null;

        // 检查是否是命名参数
        if (parser.check(IDENTIFIER) && parser.checkAhead(ASSIGN)) {
            name = parser.advance().getLexeme();
            parser.advance();  // consume '='
        }

        Expression value = parser.parseExpression();
        return new Annotation.AnnotationArg(loc, name, value);
    }

    List<Modifier> parseModifiers() {
        List<Modifier> modifiers = new ArrayList<Modifier>();

        while (true) {
            Modifier mod = null;
            if (parser.match(KW_PUBLIC)) mod = Modifier.PUBLIC;
            else if (parser.match(KW_PRIVATE)) mod = Modifier.PRIVATE;
            else if (parser.match(KW_PROTECTED)) mod = Modifier.PROTECTED;
            else if (parser.match(KW_INTERNAL)) mod = Modifier.INTERNAL;
            else if (parser.match(KW_ABSTRACT)) mod = Modifier.ABSTRACT;
            else if (parser.match(KW_SEALED)) mod = Modifier.SEALED;
            else if (parser.match(KW_OPEN)) mod = Modifier.OPEN;
            else if (parser.match(KW_FINAL)) mod = Modifier.FINAL;
            else if (parser.match(KW_OVERRIDE)) mod = Modifier.OVERRIDE;
            else if (parser.match(KW_CONST)) mod = Modifier.CONST;
            else if (parser.match(KW_INLINE)) mod = Modifier.INLINE;
            else if (parser.match(KW_SUSPEND)) mod = Modifier.SUSPEND;
            else if (parser.match(KW_OPERATOR)) mod = Modifier.OPERATOR;
            else break;

            if (modifiers.contains(mod)) {
                throw new ParseException("Duplicate modifier '" + mod.name().toLowerCase() + "'", parser.previous);
            }
            modifiers.add(mod);
        }

        // Validate mutual exclusivity
        int visCount = 0;
        boolean hasAbstract = false, hasFinal = false;
        for (Modifier m : modifiers) {
            if (m == Modifier.PUBLIC || m == Modifier.PRIVATE ||
                m == Modifier.PROTECTED || m == Modifier.INTERNAL) visCount++;
            if (m == Modifier.ABSTRACT) hasAbstract = true;
            if (m == Modifier.FINAL) hasFinal = true;
        }
        if (visCount > 1) {
            throw new ParseException("Conflicting visibility modifiers", parser.previous);
        }
        if (hasAbstract && hasFinal) {
            throw new ParseException("'abstract' and 'final' modifiers are incompatible", parser.previous);
        }

        return modifiers;
    }

    // ============ 类声明 ============

    private ClassDecl parseClassDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        return parseClassDecl(annotations, modifiers, false);
    }

    private ClassDecl parseClassDecl(List<Annotation> annotations, List<Modifier> modifiers, boolean isAnnotation) {
        SourceLocation loc = parser.location();
        parser.expect(KW_CLASS, "Expected 'class'");
        String name = parser.expect(IDENTIFIER, "Expected class name").getLexeme();
        SourceLocation classNameLoc = parser.previousLocation();

        // 类型参数
        List<TypeParameter> typeParams = Collections.emptyList();
        if (parser.check(LT)) {
            typeParams = parser.parseTypeParams();
        }

        // 主构造器
        List<Parameter> primaryConstructorParams = null;
        if (parser.check(LPAREN)) {
            primaryConstructorParams = parsePrimaryConstructor();
        }

        // 超类型
        List<TypeRef> superTypes = Collections.emptyList();
        List<Expression> superConstructorArgs = Collections.emptyList();
        if (!isAnnotation && parser.match(COLON)) {
            superTypes = new ArrayList<TypeRef>();
            superTypes.add(parser.parseType());
            // 超类构造器参数: e.g. class Child : Base(1, 2)
            if (parser.check(LPAREN)) {
                parser.advance();
                if (!parser.check(RPAREN)) {
                    superConstructorArgs = parser.parseExpressionList();
                }
                parser.expect(RPAREN, "Expected ')'");
            }
            while (parser.match(COMMA)) {
                superTypes.add(parser.parseType());
            }
        }

        // 类体（注解类不允许有类体）
        List<Declaration> members = Collections.emptyList();
        if (!isAnnotation && parser.check(LBRACE)) {
            members = parseClassBody();
        }

        boolean isSealed = modifiers.contains(Modifier.SEALED);
        boolean isAbstract = modifiers.contains(Modifier.ABSTRACT);
        boolean isOpen = modifiers.contains(Modifier.OPEN);

        ClassDecl decl = new ClassDecl(loc, annotations, modifiers, name, typeParams,
                primaryConstructorParams, superTypes, superConstructorArgs, members,
                isSealed, isAbstract, isOpen, isAnnotation);
        decl.setNameLocation(classNameLoc);
        return decl;
    }

    List<Parameter> parsePrimaryConstructor() {
        parser.expect(LPAREN, "Expected '('");
        List<Parameter> params = Collections.emptyList();
        if (!parser.check(RPAREN)) {
            params = parseParamList();
        }
        parser.expect(RPAREN, "Expected ')'");
        return params;
    }

    List<TypeRef> parseSuperTypes() {
        List<TypeRef> types = new ArrayList<TypeRef>();
        types.add(parser.parseType());
        while (parser.match(COMMA)) {
            types.add(parser.parseType());
        }
        return types;
    }

    List<Declaration> parseClassBody() {
        parser.expect(LBRACE, "Expected '{'");
        List<Declaration> members = new ArrayList<Declaration>();
        parser.skipSeparators();

        while (!parser.check(RBRACE) && !parser.isAtEnd()) {
            members.add(parseDeclaration());
            parser.skipSeparators();
        }

        parser.expect(RBRACE, "Expected '}'");
        return members;
    }

    // ============ 次级构造器 ============

    private ConstructorDecl parseConstructorDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        parser.expect(IDENTIFIER, "Expected 'constructor'"); // 软关键词

        // 解析参数列表
        parser.expect(LPAREN, "Expected '('");
        List<Parameter> params = Collections.emptyList();
        if (!parser.check(RPAREN)) {
            params = parseParamList();
        }
        parser.expect(RPAREN, "Expected ')'");

        // 可选：委托调用 : this(args)
        List<Expression> delegationArgs = null;
        if (parser.match(COLON)) {
            parser.expect(KW_THIS, "Expected 'this' after ':'");
            parser.expect(LPAREN, "Expected '(' after 'this'");
            delegationArgs = new ArrayList<Expression>();
            if (!parser.check(RPAREN)) {
                do {
                    delegationArgs.add(parser.parseExpression());
                } while (parser.match(COMMA));
            }
            parser.expect(RPAREN, "Expected ')'");
        }

        // 可选：构造器体
        Block body = null;
        if (parser.check(LBRACE)) {
            body = parser.parseBlock();
        }

        return new ConstructorDecl(loc, annotations, modifiers, params, delegationArgs, body);
    }

    // ============ init 块 ============

    private InitBlockDecl parseInitBlockDecl() {
        SourceLocation loc = parser.location();
        parser.expect(IDENTIFIER, "Expected 'init'"); // 软关键词
        Block body = parser.parseBlock();
        return new InitBlockDecl(loc, body);
    }

    // ============ 接口声明 ============

    private InterfaceDecl parseInterfaceDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        parser.expect(KW_INTERFACE, "Expected 'interface'");
        String name = parser.expect(IDENTIFIER, "Expected interface name").getLexeme();
        SourceLocation ifaceNameLoc = parser.previousLocation();

        List<TypeParameter> typeParams = Collections.emptyList();
        if (parser.check(LT)) {
            typeParams = parser.parseTypeParams();
        }

        List<TypeRef> superTypes = Collections.emptyList();
        if (parser.match(COLON)) {
            superTypes = parseSuperTypes();
        }

        List<Declaration> members = Collections.emptyList();
        if (parser.check(LBRACE)) {
            members = parseClassBody();
        }

        InterfaceDecl decl = new InterfaceDecl(loc, annotations, modifiers, name, typeParams, superTypes, members);
        decl.setNameLocation(ifaceNameLoc);
        return decl;
    }

    // ============ 对象声明 ============

    private ObjectDecl parseObjectDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        boolean isCompanion = parser.match(KW_COMPANION);
        parser.expect(KW_OBJECT, "Expected 'object'");

        String name = "";
        SourceLocation objNameLoc = null;
        if (parser.check(IDENTIFIER)) {
            name = parser.advance().getLexeme();
            objNameLoc = parser.previousLocation();
        }

        List<TypeRef> superTypes = Collections.emptyList();
        if (parser.match(COLON)) {
            superTypes = parseSuperTypes();
        }

        List<Declaration> members = Collections.emptyList();
        if (parser.check(LBRACE)) {
            members = parseClassBody();
        }

        ObjectDecl decl = new ObjectDecl(loc, annotations, modifiers, name, superTypes, members, isCompanion);
        if (objNameLoc != null) decl.setNameLocation(objNameLoc);
        return decl;
    }

    // ============ 枚举声明 ============

    private EnumDecl parseEnumDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        parser.expect(KW_ENUM, "Expected 'enum'");
        parser.expect(KW_CLASS, "Expected 'class'");
        String name = parser.expect(IDENTIFIER, "Expected enum name").getLexeme();
        SourceLocation enumNameLoc = parser.previousLocation();

        List<Parameter> primaryConstructorParams = null;
        if (parser.check(LPAREN)) {
            primaryConstructorParams = parsePrimaryConstructor();
        }

        List<TypeRef> superTypes = Collections.emptyList();
        if (parser.match(COLON)) {
            superTypes = parseSuperTypes();
        }

        // 枚举体
        List<EnumDecl.EnumEntry> entries = Collections.emptyList();
        List<Declaration> members = Collections.emptyList();

        if (parser.match(LBRACE)) {
            parser.skipNewlines();
            entries = parseEnumEntries();
            parser.skipNewlines();

            if (parser.match(SEMICOLON)) {
                parser.skipNewlines();
                members = new ArrayList<Declaration>();
                while (!parser.check(RBRACE) && !parser.isAtEnd()) {
                    members.add(parseDeclaration());
                    parser.skipNewlines();
                }
            }

            parser.expect(RBRACE, "Expected '}'");
        }

        EnumDecl decl = new EnumDecl(loc, annotations, modifiers, name, primaryConstructorParams,
                superTypes, entries, members);
        decl.setNameLocation(enumNameLoc);
        return decl;
    }

    private List<EnumDecl.EnumEntry> parseEnumEntries() {
        List<EnumDecl.EnumEntry> entries = new ArrayList<EnumDecl.EnumEntry>();

        while (parser.check(IDENTIFIER)) {
            entries.add(parseEnumEntry());
            if (!parser.match(COMMA)) break;
            parser.skipNewlines();
        }

        return entries;
    }

    private EnumDecl.EnumEntry parseEnumEntry() {
        SourceLocation loc = parser.location();
        String name = parser.expect(IDENTIFIER, "Expected enum entry name").getLexeme();

        List<Expression> args = Collections.emptyList();
        if (parser.match(LPAREN)) {
            if (!parser.check(RPAREN)) {
                args = parser.parseExpressionList();
            }
            parser.expect(RPAREN, "Expected ')'");
        }

        List<Declaration> members = Collections.emptyList();
        if (parser.check(LBRACE)) {
            members = parseClassBody();
        }

        return new EnumDecl.EnumEntry(loc, name, args, members);
    }

    // ============ 函数声明 ============

    private FunDecl parseFunDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        parser.expect(KW_FUN, "Expected 'fun'");

        // 类型参数
        List<TypeParameter> typeParams = Collections.emptyList();
        if (parser.check(LT)) {
            typeParams = parser.parseTypeParams();
        }

        // 接收者类型和函数名
        ParsedName parsed = parseNameWithOptionalReceiver("Expected function name");
        String name = parsed.name;
        TypeRef receiverType = parsed.receiverType;
        SourceLocation funNameLoc = parser.previousLocation();

        // 参数
        parser.expect(LPAREN, "Expected '('");
        List<Parameter> params = Collections.emptyList();
        if (!parser.check(RPAREN)) {
            params = parseParamList();
        }
        parser.expect(RPAREN, "Expected ')'");

        // 返回类型
        TypeRef returnType = null;
        if (parser.match(COLON)) {
            returnType = parser.parseType();
        }

        // 函数体
        AstNode body = null;
        if (parser.match(ASSIGN)) {
            body = parser.parseExpression();
        } else if (parser.check(LBRACE)) {
            body = parser.parseBlock();
        }

        boolean isInline = modifiers.contains(Modifier.INLINE);
        boolean isOperator = modifiers.contains(Modifier.OPERATOR);
        boolean isSuspend = modifiers.contains(Modifier.SUSPEND);

        FunDecl decl = new FunDecl(loc, annotations, modifiers, name, typeParams, receiverType,
                params, returnType, body, isInline, isOperator, isSuspend);
        decl.setNameLocation(funNameLoc);
        return decl;
    }

    List<Parameter> parseParamList() {
        List<Parameter> params = new ArrayList<Parameter>();
        do {
            params.add(parseParameter());
        } while (parser.match(COMMA) && !parser.check(RPAREN));
        return params;
    }

    Parameter parseParameter() {
        SourceLocation loc = parser.location();
        List<Annotation> annotations = parseAnnotations();

        // 解析可见性修饰符（用于主构造器的属性参数）
        List<Modifier> modifiers = new ArrayList<Modifier>();
        if (parser.match(KW_PUBLIC)) modifiers.add(Modifier.PUBLIC);
        else if (parser.match(KW_PRIVATE)) modifiers.add(Modifier.PRIVATE);
        else if (parser.match(KW_PROTECTED)) modifiers.add(Modifier.PROTECTED);
        else if (parser.match(KW_INTERNAL)) modifiers.add(Modifier.INTERNAL);

        // 可选的修饰符（用于主构造器的属性参数）
        boolean isVal = parser.match(KW_VAL);
        boolean isVar = !isVal && parser.match(KW_VAR);
        if (isVal) modifiers.add(Modifier.FINAL);
        boolean isVararg = parser.match(KW_VARARG);

        String name = parser.expect(IDENTIFIER, "Expected parameter name").getLexeme();
        SourceLocation paramNameLoc = parser.previousLocation();

        // 类型注解是可选的（用于动态类型脚本和REPL）
        TypeRef type = null;
        if (parser.match(COLON)) {
            type = parser.parseType();
        }

        Expression defaultValue = null;
        if (parser.match(ASSIGN)) {
            defaultValue = parser.parseExpression();
        }

        Parameter param = new Parameter(loc, annotations, modifiers, name, type, defaultValue, isVararg, isVal || isVar);
        param.setNameLocation(paramNameLoc);
        return param;
    }

    // ============ 公共辅助方法 ============

    /**
     * 解析可能带有接收者类型的名称 (用于扩展函数/属性)
     * 支持简单类型（String.foo）、泛型类型（List&lt;Int&gt;.foo）和可空类型（String?.foo）
     */
    ParsedName parseNameWithOptionalReceiver(String errorMsg) {

        // 先尝试完整类型解析（支持泛型、可空等复杂接收者类型）
        parser.mark();
        try {
            TypeRef type = parser.parseType();
            if (parser.check(DOT) && parser.checkAhead(IDENTIFIER)) {
                parser.advance(); // consume '.'
                String name = parser.expect(IDENTIFIER, errorMsg).getLexeme();
                parser.commitMark();
                return new ParsedName(name, type);
            }
            // SAFE_DOT ('?.') = 可空类型 + 点：Type?.name
            if (parser.check(SAFE_DOT) && parser.checkAhead(IDENTIFIER)) {
                parser.advance(); // consume '?.'
                String name = parser.expect(IDENTIFIER, errorMsg).getLexeme();
                parser.commitMark();
                return new ParsedName(name, new NullableType(type.getLocation(), type));
            }
        } catch (ParseException e) {
            // 类型解析失败，回退到简单解析
        }
        parser.reset();

        // 简单解析：IDENTIFIER、内置类型关键字或软关键字 + 可选的 .name
        if (parser.check(IDENTIFIER) || parser.isBuiltinTypeKeyword() || parser.isSoftKeyword()) {
            SourceLocation idLoc = parser.location();
            com.novalang.compiler.lexer.Token id = parser.advance();
            if (parser.match(DOT)) {
                String name = parser.expect(IDENTIFIER, errorMsg).getLexeme();
                TypeRef receiverType = new SimpleType(idLoc,
                        new QualifiedName(idLoc, Collections.singletonList(id.getLexeme())));
                return new ParsedName(name, receiverType);
            } else {
                return new ParsedName(id.getLexeme(), null);
            }
        } else {
            throw new ParseException(errorMsg, parser.current);
        }
    }

    /**
     * 解析解构名称列表 (a, b, _) 中的内容，不含括号
     */
    List<String> parseDestructuringNames() {
        List<String> names = new ArrayList<String>();
        do {
            if (parser.match(UNDERSCORE)) {
                names.add(null);
            } else if (parser.check(IDENTIFIER)) {
                names.add(parser.advance().getLexeme());
            } else {
                throw new ParseException("Expected variable name in destructuring", parser.current);
            }
        } while (parser.match(COMMA));
        return names;
    }

    // ============ 属性声明 ============

    private PropertyDecl parsePropertyDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        boolean isVal = parser.match(KW_VAL);
        if (!isVal) {
            parser.expect(KW_VAR, "Expected 'val' or 'var'");
        }
        return parsePropertyDeclBody(loc, annotations, modifiers, isVal);
    }

    /**
     * 解析属性声明的主体（val/var 已被消费后）
     */
    PropertyDecl parsePropertyDeclBody(SourceLocation loc, List<Annotation> annotations,
                                               List<Modifier> modifiers, boolean isVal) {
        // 类型参数
        List<TypeParameter> typeParams = Collections.emptyList();
        if (parser.check(LT)) {
            typeParams = parser.parseTypeParams();
        }

        // 接收者类型和名称
        ParsedName parsed = parseNameWithOptionalReceiver("Expected property name");
        String name = parsed.name;
        TypeRef receiverType = parsed.receiverType;
        SourceLocation nameLoc = parser.previousLocation();

        // 类型
        TypeRef type = null;
        if (parser.match(COLON)) {
            type = parser.parseType();
        }

        // 初始化器
        Expression initializer = null;
        if (parser.match(ASSIGN)) {
            initializer = parser.parseExpression();
        }

        // 解析访问器（get/set）
        PropertyAccessor getter = null;
        PropertyAccessor setter = null;
        for (int i = 0; i < 2; i++) {
            parser.mark();
            try {
                parser.skipNewlines();
                PropertyAccessor accessor = tryParseAccessor();
                if (accessor != null) {
                    parser.commitMark();
                    if (accessor.isGetter()) getter = accessor;
                    else setter = accessor;
                    continue;
                }
            } catch (ParseException e) {
                // 不是访问器，回退
            }
            parser.reset();
            break;
        }

        boolean isConst = modifiers.contains(Modifier.CONST);
        boolean isLazy = false;  // TODO: 检测 lazy 委托

        PropertyDecl decl = new PropertyDecl(loc, annotations, modifiers, name, isVal, typeParams,
                receiverType, type, initializer, getter, setter, isConst, isLazy);
        decl.setNameLocation(nameLoc);
        return decl;
    }

    // ============ 解构声明 ============

    private DestructuringDecl parseDestructuringDecl() {
        SourceLocation loc = parser.location();
        boolean isVal = parser.match(KW_VAL);
        if (!isVal) {
            parser.expect(KW_VAR, "Expected 'val' or 'var'");
        }
        return parseDestructuringBody(loc, isVal);
    }

    /**
     * 解析解构声明主体（val/var 已消费后）
     */
    DestructuringDecl parseDestructuringBody(SourceLocation loc, boolean isVal) {
        parser.expect(LPAREN, "Expected '(' for destructuring");
        List<String> names = parseDestructuringNames();
        parser.expect(RPAREN, "Expected ')' after destructuring names");
        parser.expect(ASSIGN, "Expected '=' in destructuring declaration");
        Expression initializer = parser.parseExpression();
        return new DestructuringDecl(loc, isVal, names, initializer);
    }

    // ============ 属性访问器 ============

    /**
     * 尝试解析属性访问器（get/set 软关键词）
     * 返回 null 表示当前位置不是访问器
     */
    private PropertyAccessor tryParseAccessor() {
        SourceLocation loc = parser.location();
        List<Modifier> mods = new ArrayList<Modifier>();

        // 可选的可见性修饰符（如 private set）
        if (parser.match(KW_PRIVATE)) mods.add(Modifier.PRIVATE);
        else if (parser.match(KW_PROTECTED)) mods.add(Modifier.PROTECTED);
        else if (parser.match(KW_INTERNAL)) mods.add(Modifier.INTERNAL);
        else if (parser.match(KW_PUBLIC)) mods.add(Modifier.PUBLIC);

        if (!parser.check(IDENTIFIER)) return null;
        String keyword = parser.current.getLexeme();
        if (!"get".equals(keyword) && !"set".equals(keyword)) return null;

        boolean isGetter = "get".equals(keyword);

        // 消歧义：get/set 后面必须跟 (, =, {, 或行结束符
        if (!parser.checkAhead(LPAREN) && !parser.checkAhead(ASSIGN) &&
            !parser.checkAhead(LBRACE) && !parser.checkAhead(NEWLINE) &&
            !parser.checkAhead(SEMICOLON) && !parser.checkAhead(RBRACE) &&
            !parser.checkAhead(EOF)) {
            return null;
        }

        parser.advance(); // consume "get"/"set"

        Parameter param = null;
        AstNode body = null;

        if (parser.match(LPAREN)) {
            if (!isGetter && !parser.check(RPAREN)) {
                param = parseParameter();
            }
            parser.expect(RPAREN, "Expected ')'");
        }

        if (parser.match(ASSIGN)) {
            body = parser.parseExpression();
        } else if (parser.check(LBRACE)) {
            body = parser.parseBlock();
        }

        return new PropertyAccessor(loc, isGetter, mods, param, body);
    }

    // ============ 类型别名 ============

    private TypeAliasDecl parseTypeAliasDecl(List<Annotation> annotations, List<Modifier> modifiers) {
        SourceLocation loc = parser.location();
        parser.expect(KW_TYPEALIAS, "Expected 'typealias'");
        String name = parser.expect(IDENTIFIER, "Expected type alias name").getLexeme();

        List<TypeParameter> typeParams = Collections.emptyList();
        if (parser.check(LT)) {
            typeParams = parser.parseTypeParams();
        }

        parser.expect(ASSIGN, "Expected '='");
        TypeRef aliasedType = parser.parseType();

        return new TypeAliasDecl(loc, annotations, modifiers, name, typeParams, aliasedType);
    }
}
