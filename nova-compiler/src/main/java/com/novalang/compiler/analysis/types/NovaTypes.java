package com.novalang.compiler.analysis.types;

import java.util.Arrays;
import java.util.Collections;

/**
 * 预定义类型常量和工厂方法。
 */
public final class NovaTypes {

    private NovaTypes() {}

    // 原始类型
    public static final PrimitiveNovaType INT = new PrimitiveNovaType("Int", false);
    public static final PrimitiveNovaType LONG = new PrimitiveNovaType("Long", false);
    public static final PrimitiveNovaType FLOAT = new PrimitiveNovaType("Float", false);
    public static final PrimitiveNovaType DOUBLE = new PrimitiveNovaType("Double", false);
    public static final PrimitiveNovaType BOOLEAN = new PrimitiveNovaType("Boolean", false);
    public static final PrimitiveNovaType CHAR = new PrimitiveNovaType("Char", false);

    // 常用类类型
    public static final ClassNovaType STRING = new ClassNovaType("String", false);
    public static final ClassNovaType ANY = new ClassNovaType("Any", false);
    public static final ClassNovaType NUMBER = new ClassNovaType("Number", false);

    // 特殊类型
    public static final NothingType NOTHING = NothingType.INSTANCE;
    public static final NothingType NOTHING_NULLABLE = NothingType.NULLABLE;
    public static final UnitType UNIT = UnitType.INSTANCE;
    public static final ErrorType ERROR = ErrorType.INSTANCE;

    /** 创建 List&lt;elem&gt; 类型 */
    public static ClassNovaType listOf(NovaType elem) {
        return new ClassNovaType("List",
                Collections.singletonList(NovaTypeArgument.invariant(elem)), false);
    }

    /** 创建 Set&lt;elem&gt; 类型 */
    public static ClassNovaType setOf(NovaType elem) {
        return new ClassNovaType("Set",
                Collections.singletonList(NovaTypeArgument.invariant(elem)), false);
    }

    /** 创建 Map&lt;key, value&gt; 类型 */
    public static ClassNovaType mapOf(NovaType key, NovaType value) {
        return new ClassNovaType("Map",
                Arrays.asList(NovaTypeArgument.invariant(key), NovaTypeArgument.invariant(value)), false);
    }

    /** 包装为可空类型 */
    public static NovaType nullable(NovaType type) {
        if (type == null) return null;
        return type.withNullable(true);
    }

    /** 根据类型名查找预定义原始/内置类型 */
    public static NovaType fromName(String name) {
        switch (name) {
            case "Int": return INT;
            case "Long": return LONG;
            case "Float": return FLOAT;
            case "Double": return DOUBLE;
            case "Boolean": return BOOLEAN;
            case "Char": return CHAR;
            case "String": return STRING;
            case "Any": return ANY;
            case "Number": return NUMBER;
            case "Nothing": return NOTHING;
            case "Unit": return UNIT;
            default: return null;
        }
    }

    // ============ 数值类型工具 ============

    /** 数值类型名的提升等级：Int(0) < Long(1) < Float(2) < Double(3)，非数值返回 -1 */
    public static int numericRank(String name) {
        if (name == null) return -1;
        switch (name) {
            case "Int":    return 0;
            case "Long":   return 1;
            case "Float":  return 2;
            case "Double": return 3;
            default:       return -1;
        }
    }

    /** 类型名是否为数值类型 */
    public static boolean isNumericName(String name) {
        return numericRank(name) >= 0;
    }

    /** NovaType 是否为数值类型 */
    public static boolean isNumericType(NovaType type) {
        return type != null && isNumericName(type.getTypeName());
    }

    /** 数值提升：取两个数值类型中更宽的类型 */
    public static NovaType promoteNumeric(NovaType a, NovaType b) {
        if (DOUBLE.equals(a) || DOUBLE.equals(b)) return DOUBLE;
        if (FLOAT.equals(a) || FLOAT.equals(b)) return FLOAT;
        if (LONG.equals(a) || LONG.equals(b)) return LONG;
        return INT;
    }

    /** 字符串版本：宽化判断 (target rank >= source rank) */
    public static boolean isNumericWidening(String target, String source) {
        int tr = numericRank(target), sr = numericRank(source);
        return tr >= 0 && sr >= 0 && tr >= sr;
    }
}
