package com.novalang.ir.mir;

/**
 * MIR 类型，直接映射 JVM 类型系统。
 */
public class MirType {

    public enum Kind {
        INT, LONG, FLOAT, DOUBLE, BOOLEAN, CHAR, VOID, OBJECT, ARRAY
    }

    private final Kind kind;
    private final String className;     // OBJECT 时使用（JVM 内部名）
    private final MirType elementType;  // ARRAY 时使用

    private MirType(Kind kind, String className, MirType elementType) {
        this.kind = kind;
        this.className = className;
        this.elementType = elementType;
    }

    public static MirType ofInt()     { return new MirType(Kind.INT, null, null); }
    public static MirType ofLong()    { return new MirType(Kind.LONG, null, null); }
    public static MirType ofFloat()   { return new MirType(Kind.FLOAT, null, null); }
    public static MirType ofDouble()  { return new MirType(Kind.DOUBLE, null, null); }
    public static MirType ofBoolean() { return new MirType(Kind.BOOLEAN, null, null); }
    public static MirType ofChar()    { return new MirType(Kind.CHAR, null, null); }
    public static MirType ofVoid()    { return new MirType(Kind.VOID, null, null); }

    public static MirType ofObject(String className) {
        return new MirType(Kind.OBJECT, className, null);
    }

    public static MirType ofArray(MirType elementType) {
        return new MirType(Kind.ARRAY, null, elementType);
    }

    public Kind getKind() { return kind; }
    public String getClassName() { return className; }
    public MirType getElementType() { return elementType; }

    public boolean isPrimitive() {
        switch (kind) {
            case INT: case LONG: case FLOAT: case DOUBLE: case BOOLEAN: case CHAR: return true;
            default: return false;
        }
    }

    /**
     * 返回装箱后的字段描述符。
     * 原始类型 → 包装类描述符（因为值全部装箱为对象引用）。
     * 引用类型 → 原样。
     */
    public String getFieldDescriptor() {
        switch (kind) {
            case INT:     return "Ljava/lang/Integer;";
            case LONG:    return "Ljava/lang/Long;";
            case FLOAT:   return "Ljava/lang/Float;";
            case DOUBLE:  return "Ljava/lang/Double;";
            case BOOLEAN: return "Ljava/lang/Boolean;";
            case CHAR:    return "Ljava/lang/Character;";
            case OBJECT:  return "L" + className + ";";
            case ARRAY:   return "[" + elementType.getDescriptor();
            default:      return "Ljava/lang/Object;";
        }
    }

    /**
     * 返回 JVM 类型描述符。
     */
    public String getDescriptor() {
        switch (kind) {
            case INT:     return "I";
            case LONG:    return "J";
            case FLOAT:   return "F";
            case DOUBLE:  return "D";
            case BOOLEAN: return "Z";
            case CHAR:    return "C";
            case VOID:    return "V";
            case OBJECT:  return "L" + className + ";";
            case ARRAY:   return "[" + elementType.getDescriptor();
            default: return "Ljava/lang/Object;";
        }
    }

    @Override
    public String toString() {
        switch (kind) {
            case OBJECT: return className;
            case ARRAY: return elementType + "[]";
            default: return kind.name().toLowerCase();
        }
    }
}
