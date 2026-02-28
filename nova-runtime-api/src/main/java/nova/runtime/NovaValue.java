package nova.runtime;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * NovaLang 运行时值的顶层接口
 *
 * <p>所有 Nova 值都实现此接口，提供统一的值操作契约。</p>
 *
 * <p>子接口：</p>
 * <ul>
 *   <li>{@link NovaNumber} - 数值类型（Int, Long, Double, Float）</li>
 *   <li>{@link NovaContainer} - 容器类型（List, Map, Array, Range）</li>
 *   <li>{@link NovaCallable} - 可调用类型（函数、Lambda、类构造器等）</li>
 * </ul>
 *
 * <p>默认实现基类：{@link AbstractNovaValue}</p>
 */
public interface NovaValue {

    // ============ 静态方法（委托给 AbstractNovaValue） ============

    /**
     * 注册回退转换器
     *
     * @param converter 转换器函数
     * @deprecated 使用 {@link AbstractNovaValue#setFallbackConverter(Function)} 代替
     */
    @Deprecated
    static void setFallbackConverter(Function<Object, NovaValue> converter) {
        AbstractNovaValue.setFallbackConverter(converter);
    }

    /**
     * 将 Java 值转换为 NovaValue
     *
     * @param javaValue Java 对象
     * @return 对应的 NovaValue
     * @deprecated 使用 {@link AbstractNovaValue#fromJava(Object)} 代替
     */
    @Deprecated
    static NovaValue fromJava(Object javaValue) {
        return AbstractNovaValue.fromJava(javaValue);
    }

    /**
     * 获取任意 Java 对象的 Nova 类型名
     *
     * @param value Java 对象
     * @return Nova 类型名
     * @deprecated 使用 {@link AbstractNovaValue#typeNameOf(Object)} 代替
     */
    @Deprecated
    static String typeNameOf(Object value) {
        return AbstractNovaValue.typeNameOf(value);
    }

    /**
     * 编译器路径的 truthy 检查
     *
     * @param value 要检查的值
     * @return 是否为真值
     * @deprecated 使用 {@link AbstractNovaValue#truthyCheck(Object)} 代替
     */
    @Deprecated
    static boolean truthyCheck(Object value) {
        return AbstractNovaValue.truthyCheck(value);
    }

    // ============ 核心抽象方法 ============

    /**
     * 获取值的类型名称
     *
     * @return 类型名称
     */
    String getTypeName();

    /**
     * 获取底层 Java 值
     *
     * @return Java 值
     */
    Object toJavaValue();

    // ============ 类型检查方法 ============

    /**
     * 获取 Nova 类型名，用于扩展函数查找的类型映射
     *
     * @return Nova 类型名，默认 null
     */
    default String getNovaTypeName() {
        return null;
    }

    /**
     * 转换为布尔值（用于条件判断）
     *
     * @return 是否为真值
     */
    default boolean isTruthy() {
        return true;
    }

    /**
     * 是否为 null
     *
     * @return 如果为 null 返回 true
     */
    default boolean isNull() {
        return false;
    }

    /**
     * 是否为数值类型
     *
     * @return 如果为数值类型返回 true
     */
    default boolean isNumber() {
        return this instanceof NovaNumber;
    }

    /**
     * 是否为整数类型
     *
     * @return 如果为整数类型返回 true
     */
    default boolean isInteger() {
        return false;
    }

    /**
     * 是否为整数类型（别名）
     *
     * @return 如果为整数类型返回 true
     */
    default boolean isInt() {
        return isInteger();
    }

    /**
     * 是否为浮点数类型
     *
     * @return 如果为浮点数类型返回 true
     */
    default boolean isDouble() {
        return false;
    }

    /**
     * 是否为字符串
     *
     * @return 如果为字符串返回 true
     */
    default boolean isString() {
        return false;
    }

    /**
     * 是否为布尔值
     *
     * @return 如果为布尔值返回 true
     */
    default boolean isBoolean() {
        return false;
    }

    /**
     * 是否为布尔值（别名）
     *
     * @return 如果为布尔值返回 true
     */
    default boolean isBool() {
        return isBoolean();
    }

    /**
     * 是否为可调用对象（函数、Lambda）
     *
     * @return 如果可调用返回 true
     */
    default boolean isCallable() {
        return this instanceof NovaCallable;
    }

    /**
     * 是否为列表
     *
     * @return 如果为列表返回 true
     */
    default boolean isList() {
        return false;
    }

    /**
     * 是否为 Map
     *
     * @return 如果为 Map 返回 true
     */
    default boolean isMap() {
        return false;
    }

    /**
     * 是否为对象实例
     *
     * @return 如果为对象实例返回 true
     */
    default boolean isObject() {
        return false;
    }

    // ============ 类型转换方法 ============

    /**
     * 编译模式动态调用：不依赖 Interpreter 的函数调用入口
     *
     * @param args 参数数组（NovaValue 类型）
     * @return 返回值
     */
    default Object dynamicInvoke(NovaValue[] args) {
        throw new NovaException("Not callable: " + getTypeName());
    }

    /**
     * 转换为 int
     *
     * @return int 值
     */
    default int asInt() {
        throw new NovaException("Cannot convert " + getTypeName() + " to Int");
    }

    /**
     * 转换为 long
     *
     * @return long 值
     */
    default long asLong() {
        throw new NovaException("Cannot convert " + getTypeName() + " to Long");
    }

    /**
     * 转换为 double
     *
     * @return double 值
     */
    default double asDouble() {
        throw new NovaException("Cannot convert " + getTypeName() + " to Double");
    }

    /**
     * 转换为字符串
     *
     * @return 字符串表示
     */
    default String asString() {
        return toString();
    }

    /**
     * 转换为布尔值
     *
     * @return 布尔值
     */
    default boolean asBoolean() {
        return isTruthy();
    }

    /**
     * 转换为布尔值（别名）
     *
     * @return 布尔值
     */
    default boolean asBool() {
        return asBoolean();
    }

    // ============ 相等性比较方法 ============

    /**
     * 相等性比较
     *
     * @param other 另一个 NovaValue
     * @return 是否相等
     */
    default boolean equals(NovaValue other) {
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
     *
     * @param other 另一个 NovaValue
     * @return 是否引用相等
     */
    default boolean refEquals(NovaValue other) {
        return this == other;
    }
}
