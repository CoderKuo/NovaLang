package nova.lang;

/**
 * Unit 类型 - 表示没有有意义的返回值
 *
 * <p>类似于 Java 的 void，但它是一个真正的类型，可以用于泛型。</p>
 *
 * <pre>
 * fun log(msg: String): Unit {
 *     println(msg)
 * }
 *
 * // 作为泛型参数
 * val callback: () -&gt; Unit = { println("hello") }
 * </pre>
 */
public final class Unit {

    /**
     * Unit 的唯一实例
     */
    public static final Unit INSTANCE = new Unit();

    private Unit() {
        // 私有构造器，确保单例
    }

    @Override
    public String toString() {
        return "Unit";
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == INSTANCE || obj instanceof Unit;
    }
}
