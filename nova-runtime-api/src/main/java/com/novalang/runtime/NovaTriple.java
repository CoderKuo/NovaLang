package com.novalang.runtime;

/**
 * Nova Triple 值 — 三元组
 *
 * <p>与 NovaPair 同模式，支持 .first/.second/.third 成员访问和解构。</p>
 */
public final class NovaTriple extends AbstractNovaValue {

    private final Object first;
    private final Object second;
    private final Object third;

    public NovaTriple(Object first, Object second, Object third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public static NovaTriple of(Object first, Object second, Object third) {
        return new NovaTriple(first, second, third);
    }

    public Object getFirst() { return first; }
    public Object getSecond() { return second; }
    public Object getThird() { return third; }

    public NovaList toList() {
        NovaList list = new NovaList();
        list.add(AbstractNovaValue.fromJava(first));
        list.add(AbstractNovaValue.fromJava(second));
        list.add(AbstractNovaValue.fromJava(third));
        return list;
    }

    @Override
    public NovaValue resolveMember(String name) {
        switch (name) {
            case "first":  return AbstractNovaValue.fromJava(first);
            case "second": return AbstractNovaValue.fromJava(second);
            case "third":  return AbstractNovaValue.fromJava(third);
            default: return null;
        }
    }

    @Override
    public NovaValue componentN(int n) {
        if (n == 1) return AbstractNovaValue.fromJava(first);
        if (n == 2) return AbstractNovaValue.fromJava(second);
        if (n == 3) return AbstractNovaValue.fromJava(third);
        throw new IndexOutOfBoundsException("Triple only has component1, component2, component3");
    }

    @Override public String getTypeName() { return "Triple"; }
    @Override public String getNovaTypeName() { return "Triple"; }

    @Override
    public Object toJavaValue() {
        Object f = first instanceof NovaValue ? ((NovaValue) first).toJavaValue() : first;
        Object s = second instanceof NovaValue ? ((NovaValue) second).toJavaValue() : second;
        Object t = third instanceof NovaValue ? ((NovaValue) third).toJavaValue() : third;
        return new Object[]{f, s, t};
    }

    @Override
    public String asString() {
        String f = first instanceof NovaValue ? ((NovaValue) first).asString() : String.valueOf(first);
        String s = second instanceof NovaValue ? ((NovaValue) second).asString() : String.valueOf(second);
        String t = third instanceof NovaValue ? ((NovaValue) third).asString() : String.valueOf(third);
        return "(" + f + ", " + s + ", " + t + ")";
    }

    @Override public String toString() { return asString(); }

    @Override
    public boolean equals(NovaValue other) {
        if (!(other instanceof NovaTriple)) return false;
        NovaTriple o = (NovaTriple) other;
        return java.util.Objects.equals(first, o.first)
                && java.util.Objects.equals(second, o.second)
                && java.util.Objects.equals(third, o.third);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(first, second, third);
    }
}
