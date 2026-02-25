package com.novalang.compiler;

/**
 * Nova 类型名与 JVM 类型名的映射工具。
 * 集中管理 Nova 原始类型名到 JVM 装箱类名、类型描述符的映射，
 * 避免在 CodeGenerator、TypeHelper、HirToMirLowering 等多处重复。
 */
public final class NovaTypeNames {

    private NovaTypeNames() {}

    /**
     * Nova 原始类型名 → JVM 装箱类型内部名（用于 INSTANCEOF / CHECKCAST）。
     * 返回 null 表示不是原始类型。
     */
    public static String toBoxedInternalName(String novaName) {
        switch (novaName) {
            case "Int":     return "java/lang/Integer";
            case "Long":    return "java/lang/Long";
            case "Float":   return "java/lang/Float";
            case "Double":  return "java/lang/Double";
            case "Boolean": return "java/lang/Boolean";
            case "Char":    return "java/lang/Character";
            case "Byte":    return "java/lang/Byte";
            case "Short":   return "java/lang/Short";
            case "Any":
            case "Object":  return "java/lang/Object";
            case "String":  return "java/lang/String";
            case "List":    return "java/util/List";
            case "Map":     return "java/util/Map";
            case "Set":     return "java/util/Set";
            case "Result":  return "nova/runtime/NovaResult";
            default:        return null;
        }
    }

    /**
     * JVM 装箱类型内部名 → Nova 原始类型名（toBoxedInternalName 的反向映射）。
     * 返回 null 表示无对应 Nova 类型名。
     */
    public static String fromBoxedInternalName(String jvmName) {
        switch (jvmName) {
            case "java/lang/Integer":   return "Int";
            case "java/lang/Long":      return "Long";
            case "java/lang/Float":     return "Float";
            case "java/lang/Double":    return "Double";
            case "java/lang/Boolean":   return "Boolean";
            case "java/lang/Character": return "Char";
            case "java/lang/Byte":      return "Byte";
            case "java/lang/Short":     return "Short";
            case "java/lang/Object":    return "Any";
            case "java/lang/String":    return "String";
            case "java/util/List":      return "List";
            case "java/util/Map":       return "Map";
            case "java/util/Set":       return "Set";
            case "nova/runtime/NovaResult": return "Result";
            default:                    return null;
        }
    }

    /**
     * Nova 原始类型名 → JVM 类型描述符（I/J/F/D/Z/C/B/S）。
     * 返回 null 表示不是原始类型。
     */
    public static String toDescriptor(String novaName) {
        switch (novaName) {
            case "Int":     return "I";
            case "Long":    return "J";
            case "Float":   return "F";
            case "Double":  return "D";
            case "Boolean": return "Z";
            case "Char":    return "C";
            case "Byte":    return "B";
            case "Short":   return "S";
            case "Unit":    return "V";
            case "String":  return "Ljava/lang/String;";
            case "Any":
            case "Object":  return "Ljava/lang/Object;";
            default:        return null;
        }
    }
}
