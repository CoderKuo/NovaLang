package nova.runtime.stdlib;

/**
 * 标准库函数的基类。
 *
 * <p>子类型：</p>
 * <ul>
 *   <li>{@link StdlibRegistry.ReceiverLambdaInfo} — 接收者 Lambda 函数（buildString / buildList …）</li>
 *   <li>{@link StdlibRegistry.NativeFunctionInfo} — 原生函数（min / max / abs …）</li>
 *   <li>{@link StdlibRegistry.SupplierLambdaInfo} — Supplier Lambda 函数（async …）</li>
 * </ul>
 */
public abstract class StdlibFunction {

    /** 函数名，如 "buildString"、"min" */
    public final String name;

    /** 参数数量，-1 表示变参 */
    public final int arity;

    protected StdlibFunction(String name, int arity) {
        this.name = name;
        this.arity = arity;
    }
}
