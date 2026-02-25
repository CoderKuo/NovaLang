package com.novalang.compiler.lexer;

/**
 * NovaLang 词法单元类型
 */
public enum TokenType {
    // === 字面量 ===
    INT_LITERAL,
    LONG_LITERAL,
    FLOAT_LITERAL,
    DOUBLE_LITERAL,
    CHAR_LITERAL,
    STRING_LITERAL,
    RAW_STRING,             // r"..."
    MULTILINE_STRING,       // """..."""

    // === 标识符 ===
    IDENTIFIER,

    // === 关键词 - 声明 ===
    KW_VAL, KW_VAR, KW_FUN, KW_CLASS, KW_INTERFACE,
    KW_OBJECT, KW_ENUM, KW_TYPEALIAS, KW_PACKAGE, KW_IMPORT,
    KW_MODULE, KW_CONSTRUCTOR, KW_INIT,

    // === 关键词 - 修饰符 ===
    KW_PUBLIC, KW_PRIVATE, KW_PROTECTED, KW_INTERNAL,
    KW_ABSTRACT, KW_SEALED, KW_OPEN, KW_OVERRIDE, KW_FINAL,
    KW_CONST, KW_INLINE, KW_CROSSINLINE, KW_REIFIED,
    KW_OPERATOR, KW_VARARG, KW_SUSPEND, KW_STATIC, KW_COMPANION,

    // === 关键词 - 控制流 ===
    KW_IF, KW_ELSE, KW_WHEN, KW_FOR, KW_WHILE,
    KW_DO, KW_RETURN, KW_BREAK, KW_CONTINUE, KW_THROW,
    KW_TRY, KW_CATCH, KW_FINALLY, KW_GUARD, KW_USE,
    KW_STEP,                // step (用于范围)

    // === 关键词 - 类型 ===
    KW_IS, KW_AS, KW_IN, KW_OUT, KW_WHERE,
    KW_TRUE, KW_FALSE, KW_NULL,

    // === 关键词 - 内置类型 ===
    KW_ANY, KW_UNIT, KW_NOTHING,
    KW_INT, KW_LONG, KW_FLOAT, KW_DOUBLE,
    KW_BOOLEAN, KW_CHAR, KW_STRING, KW_ARRAY,

    // === 关键词 - 特殊 ===
    KW_THIS, KW_SUPER, KW_IT, KW_AWAIT,

    // === 操作符 - 算术 ===
    PLUS,           // +
    MINUS,          // -
    MUL,            // *
    DIV,            // /
    MOD,            // %
    INC,            // ++
    DEC,            // --

    // === 操作符 - 比较 ===
    EQ,             // ==
    NE,             // !=
    REF_EQ,         // ===
    REF_NE,         // !==
    LT,             // <
    GT,             // >
    LE,             // <=
    GE,             // >=

    // === 操作符 - 逻辑 ===
    AND,            // &&
    OR,             // ||
    NOT,            // !

    // === 操作符 - 赋值 ===
    ASSIGN,                 // =
    PLUS_ASSIGN,            // +=
    MINUS_ASSIGN,           // -=
    MUL_ASSIGN,             // *=
    DIV_ASSIGN,             // /=
    MOD_ASSIGN,             // %=
    NULL_COALESCE_ASSIGN,   // ??=
    OR_ASSIGN,              // ||=
    AND_ASSIGN,             // &&=

    // === 操作符 - 空安全 ===
    QUESTION,           // ?
    SAFE_DOT,           // ?.
    SAFE_LBRACKET,      // ?[
    ELVIS,              // ?:
    NOT_NULL,           // !!

    // === 操作符 - 范围 ===
    RANGE,              // ..
    RANGE_EXCLUSIVE,    // ..<

    // === 操作符 - 特殊 ===
    PIPELINE,       // |>
    DOUBLE_COLON,   // ::
    ARROW,          // ->
    DOUBLE_ARROW,   // =>
    UNDERSCORE,     // _
    AT,             // @
    DOLLAR,         // $
    HASH,           // #

    // === 分隔符 ===
    LPAREN,         // (
    RPAREN,         // )
    LBRACE,         // {
    RBRACE,         // }
    LBRACKET,       // [
    RBRACKET,       // ]
    COMMA,          // ,
    DOT,            // .
    COLON,          // :
    SEMICOLON,      // ;

    // === 特殊 ===
    NEWLINE,
    EOF,
    ERROR;

    /**
     * 是否为关键词
     */
    public boolean isKeyword() {
        return name().startsWith("KW_");
    }

    /**
     * 是否为赋值操作符
     */
    public boolean isAssignmentOp() {
        switch (this) {
            case ASSIGN:
            case PLUS_ASSIGN:
            case MINUS_ASSIGN:
            case MUL_ASSIGN:
            case DIV_ASSIGN:
            case MOD_ASSIGN:
            case NULL_COALESCE_ASSIGN:
            case OR_ASSIGN:
            case AND_ASSIGN:
                return true;
            default:
                return false;
        }
    }

    /**
     * 是否为比较操作符
     */
    public boolean isComparisonOp() {
        switch (this) {
            case LT:
            case GT:
            case LE:
            case GE:
                return true;
            default:
                return false;
        }
    }
}
