package nova.runtime;

/**
 * Nova Pair 值 — 由中缀 to 运算符创建
 *
 * <p>字段类型为 Object，兼容编译路径和解释器路径。
 * 解释器通过 MemberResolver 访问 .first/.second 时用 NovaValue.fromJava() 包装。</p>
 */
public final class NovaPair extends NovaValue {

    private final Object first;
    private final Object second;

    public NovaPair(Object first, Object second) {
        this.first = first;
        this.second = second;
    }

    /** 静态工厂方法 */
    public static NovaPair of(Object first, Object second) {
        return new NovaPair(first, second);
    }

    public Object getFirst() {
        return first;
    }

    public Object getSecond() {
        return second;
    }

    public NovaList toList() {
        NovaList list = new NovaList();
        list.add(NovaValue.fromJava(first));
        list.add(NovaValue.fromJava(second));
        return list;
    }

    @Override
    public String getTypeName() {
        return "Pair";
    }

    @Override
    public String getNovaTypeName() {
        return "Pair";
    }

    @Override
    public Object toJavaValue() {
        Object f = first instanceof NovaValue ? ((NovaValue) first).toJavaValue() : first;
        Object s = second instanceof NovaValue ? ((NovaValue) second).toJavaValue() : second;
        return new Object[]{f, s};
    }

    @Override
    public String asString() {
        String f = first instanceof NovaValue ? ((NovaValue) first).asString() : String.valueOf(first);
        String s = second instanceof NovaValue ? ((NovaValue) second).asString() : String.valueOf(second);
        return "(" + f + ", " + s + ")";
    }

    @Override
    public String toString() {
        return asString();
    }

    @Override
    public boolean equals(NovaValue other) {
        if (!(other instanceof NovaPair)) return false;
        NovaPair o = (NovaPair) other;
        return java.util.Objects.equals(first, o.first) && java.util.Objects.equals(second, o.second);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(first, second);
    }
}
