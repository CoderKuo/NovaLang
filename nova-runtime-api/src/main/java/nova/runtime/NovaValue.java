package nova.runtime;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * NovaLang 运行时值的基类
 *
 * <p>所有 Nova 值都继承自此类，提供统一的值操作接口。</p>
 */
public abstract class NovaValue {

    /**
     * 回退转换器 — 用于处理未知 Java 对象（如 NovaExternalObject）。
     * 由 nova-runtime 的 Interpreter 初始化时注册。
     */
    private static Function<Object, NovaValue> fallbackConverter;

    /**
     * 注册回退转换器
     */
    public static void setFallbackConverter(Function<Object, NovaValue> converter) {
        fallbackConverter = converter;
    }

    /**
     * 将 Java 值转换为 NovaValue
     *
     * @param javaValue Java 对象
     * @return 对应的 NovaValue
     */
    @SuppressWarnings("unchecked")
    public static NovaValue fromJava(Object javaValue) {
        if (javaValue == null) {
            return NovaNull.NULL;
        }
        if (javaValue instanceof NovaValue) {
            return (NovaValue) javaValue;
        }
        if (javaValue instanceof Integer) {
            return NovaInt.of((Integer) javaValue);
        }
        if (javaValue instanceof Long) {
            return NovaLong.of((Long) javaValue);
        }
        if (javaValue instanceof Double) {
            return NovaDouble.of((Double) javaValue);
        }
        if (javaValue instanceof Float) {
            return NovaFloat.of((Float) javaValue);
        }
        if (javaValue instanceof Boolean) {
            return NovaBoolean.of((Boolean) javaValue);
        }
        if (javaValue instanceof String) {
            return NovaString.of((String) javaValue);
        }
        if (javaValue instanceof Character) {
            return NovaChar.of((Character) javaValue);
        }
        if (javaValue instanceof int[])    return new NovaArray(NovaArray.ElementType.INT, javaValue, ((int[]) javaValue).length);
        if (javaValue instanceof long[])   return new NovaArray(NovaArray.ElementType.LONG, javaValue, ((long[]) javaValue).length);
        if (javaValue instanceof double[]) return new NovaArray(NovaArray.ElementType.DOUBLE, javaValue, ((double[]) javaValue).length);
        if (javaValue instanceof float[])  return new NovaArray(NovaArray.ElementType.FLOAT, javaValue, ((float[]) javaValue).length);
        if (javaValue instanceof boolean[]) return new NovaArray(NovaArray.ElementType.BOOLEAN, javaValue, ((boolean[]) javaValue).length);
        if (javaValue instanceof char[])   return new NovaArray(NovaArray.ElementType.CHAR, javaValue, ((char[]) javaValue).length);
        if (javaValue instanceof String[]) return new NovaArray(NovaArray.ElementType.STRING, javaValue, ((String[]) javaValue).length);
        if (javaValue instanceof Object[]) return new NovaArray(NovaArray.ElementType.OBJECT, javaValue, ((Object[]) javaValue).length);
        if (javaValue instanceof List) {
            NovaList list = new NovaList();
            for (Object item : (List<?>) javaValue) {
                list.add(fromJava(item));
            }
            return list;
        }
        if (javaValue instanceof Map) {
            NovaMap map = new NovaMap();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) javaValue).entrySet()) {
                map.put(fromJava(entry.getKey()), fromJava(entry.getValue()));
            }
            return map;
        }
        // 回退转换器（由 Interpreter 注册为 NovaExternalObject::new）
        if (fallbackConverter != null) {
            return fallbackConverter.apply(javaValue);
        }
        throw new NovaException("Cannot convert Java object to NovaValue: " + javaValue.getClass().getName());
    }

    /**
     * 获取任意 Java 对象的 Nova 类型名。
     * NovaValue 子类走 getTypeName()，原始 Java 类型走 fromJava 转换，
     * 未知类型回退到 Java 类简名。
     */
    public static String typeNameOf(Object value) {
        if (value == null) return "Null";
        if (value instanceof NovaValue) return ((NovaValue) value).getTypeName();
        // 原始 Java 类型 → Nova 类型名（与 fromJava 识别的类型一致）
        if (value instanceof Integer) return "Int";
        if (value instanceof String) return "String";
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof Long) return "Long";
        if (value instanceof Double) return "Double";
        if (value instanceof Float) return "Float";
        if (value instanceof Character) return "Char";
        if (value instanceof List) return "List";
        if (value instanceof Map) return "Map";
        // @NovaType 注解（编译路径的 CompileScope/CompileDeferred/CompileJob 等）
        NovaType ann = value.getClass().getAnnotation(NovaType.class);
        if (ann != null) return ann.name();
        // 编译路径自定义类 / 其他
        return value.getClass().getSimpleName();
    }

    /**
     * 获取值的类型名称
     */
    public abstract String getTypeName();

    /**
     * 获取 Nova 类型名，用于扩展函数查找的类型映射。
     * 默认返回 null，表示无对应 Nova 类型名。
     * runtime-api 子类覆写返回具体类型名（如 "String"/"Int"/"List"）。
     */
    public String getNovaTypeName() {
        return null;
    }

    /**
     * 获取底层 Java 值
     */
    public abstract Object toJavaValue();

    /**
     * 转换为布尔值（用于条件判断）
     */
    public boolean isTruthy() {
        return true;
    }

    /**
     * 编译器路径的 truthy 检查：兼容 NovaValue 和原生 Java 类型（String/Boolean/Collection 等）。
     */
    public static boolean truthyCheck(Object value) {
        if (value == null) return false;
        if (value instanceof NovaValue) return ((NovaValue) value).isTruthy();
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof java.util.Collection) return !((java.util.Collection<?>) value).isEmpty();
        if (value instanceof java.util.Map) return !((java.util.Map<?, ?>) value).isEmpty();
        return true;
    }

    /**
     * 是否为 null
     */
    public boolean isNull() {
        return false;
    }

    /**
     * 是否为数值类型
     */
    public boolean isNumber() {
        return false;
    }

    /**
     * 是否为整数类型
     */
    public boolean isInteger() {
        return false;
    }

    /**
     * 是否为整数类型（别名）
     */
    public boolean isInt() {
        return isInteger();
    }

    /**
     * 是否为浮点数类型
     */
    public boolean isDouble() {
        return false;
    }

    /**
     * 是否为字符串
     */
    public boolean isString() {
        return false;
    }

    /**
     * 是否为布尔值
     */
    public boolean isBoolean() {
        return false;
    }

    /**
     * 是否为布尔值（别名）
     */
    public boolean isBool() {
        return isBoolean();
    }

    /**
     * 是否为可调用对象（函数、Lambda）
     */
    public boolean isCallable() {
        return false;
    }

    /**
     * 编译模式动态调用：不依赖 Interpreter 的函数调用入口。
     * 可调用的 NovaValue 子类（如 NovaNativeFunction）应覆写此方法。
     *
     * @param args 参数数组（NovaValue 类型）
     * @return 返回值
     */
    public Object dynamicInvoke(NovaValue[] args) {
        throw new NovaException("Not callable: " + getTypeName());
    }

    /**
     * 是否为列表
     */
    public boolean isList() {
        return false;
    }

    /**
     * 是否为 Map
     */
    public boolean isMap() {
        return false;
    }

    /**
     * 是否为对象实例
     */
    public boolean isObject() {
        return false;
    }

    /**
     * 转换为 int
     */
    public int asInt() {
        throw new NovaException("Cannot convert " + getTypeName() + " to Int");
    }

    /**
     * 转换为 long
     */
    public long asLong() {
        throw new NovaException("Cannot convert " + getTypeName() + " to Long");
    }

    /**
     * 转换为 double
     */
    public double asDouble() {
        throw new NovaException("Cannot convert " + getTypeName() + " to Double");
    }

    /**
     * 转换为字符串
     */
    public String asString() {
        return toString();
    }

    /**
     * 转换为布尔值
     */
    public boolean asBoolean() {
        return isTruthy();
    }

    /**
     * 转换为布尔值（别名）
     */
    public boolean asBool() {
        return asBoolean();
    }

    /**
     * 相等性比较
     */
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other == this) return true;
        Object thisVal = toJavaValue();
        Object otherVal = other.toJavaValue();
        if (thisVal == null) return otherVal == null;
        // 防止 toJavaValue() 返回 this 时无限递归
        if (thisVal == this || otherVal == other) return thisVal == otherVal;
        return thisVal.equals(otherVal);
    }

    /**
     * 引用相等性比较
     */
    public boolean refEquals(NovaValue other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        Object val = toJavaValue();
        return val != null ? val.hashCode() : 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NovaValue) {
            return equals((NovaValue) obj);
        }
        return false;
    }
}
