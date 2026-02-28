package nova.runtime;

/**
 * Result 类型 — 函数式错误处理
 *
 * <p>NovaLang 的 Result 类型，类似 Rust 的 Result/Kotlin 的 Result。
 * 有两种变体：Ok(value) 和 Err(error)。</p>
 *
 * <p>统一版本：同时用于编译路径和解释器路径。
 * 成员方法（.map/.unwrap 等）由 MemberResolver 处理。</p>
 */
public final class NovaResult extends AbstractNovaValue {

    private final boolean ok;
    private final Object value;  // Ok: success value, Err: error value

    private NovaResult(boolean ok, Object value) {
        this.ok = ok;
        this.value = value;
    }

    public static NovaResult ok(Object value) {
        return new NovaResult(true, value);
    }

    public static NovaResult err(Object error) {
        return new NovaResult(false, error);
    }

    public boolean isOk() {
        return ok;
    }

    public boolean isErr() {
        return !ok;
    }

    /** Ok 时返回 value，Err 时返回 error（原始 Object） */
    public Object getValue() {
        return value;
    }

    /** 等同于 getValue()，语义更明确 */
    public Object getError() {
        return value;
    }

    /** 获取内部值并转为 NovaValue */
    public NovaValue getInner() {
        return (value instanceof NovaValue) ? (NovaValue) value : AbstractNovaValue.fromJava(value);
    }

    /** unwrap：Ok 返回 value，Err 抛出异常 */
    public Object unwrap() {
        if (!ok) throw new NovaException("Called unwrap() on Err: " + value);
        return value;
    }

    /** unwrapOr：Ok 返回 value，Err 返回 defaultValue */
    public Object unwrapOr(Object defaultValue) {
        return ok ? value : defaultValue;
    }

    @Override
    public String getTypeName() {
        return ok ? "Ok" : "Err";
    }

    @Override
    public String getNovaTypeName() {
        return isOk() ? "Ok" : "Err";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public boolean isTruthy() {
        return ok;
    }

    @Override
    public String toString() {
        String inner = (value instanceof NovaValue) ? ((NovaValue) value).asString() : String.valueOf(value);
        return (ok ? "Ok(" : "Err(") + inner + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NovaResult)) return false;
        NovaResult other = (NovaResult) o;
        return ok == other.ok && java.util.Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(ok) + (value != null ? value.hashCode() : 0);
    }

    // ============ 编译模式辅助（INVOKESTATIC 目标） ============

    /** 检查任意对象是否为 Ok */
    public static boolean checkIsOk(Object value) {
        return value instanceof NovaResult && ((NovaResult) value).isOk();
    }

    /** 检查任意对象是否为 Err */
    public static boolean checkIsErr(Object value) {
        return value instanceof NovaResult && ((NovaResult) value).isErr();
    }

    /** 检查任意对象是否为 Result */
    public static boolean checkIsResult(Object value) {
        return value instanceof NovaResult;
    }
}
