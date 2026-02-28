package nova.runtime;

import nova.runtime.interpreter.NovaNativeFunction;

import java.util.*;

/**
 * 解释器函数 → 编译器 FunctionN 的适配桥梁。
 *
 * <p>编译后的字节码对函数调用生成：
 * <ul>
 *   <li>arity 0-8 → {@code INVOKEINTERFACE FunctionN.invoke(args)}</li>
 *   <li>arity 9+ → {@code LambdaInvoker.invokeN(fn, args)}（缓存反射）</li>
 * </ul>
 * 本类负责将 {@link NovaNativeFunction}（解释器侧）适配为上述路径可调用的对象。
 *
 * <h3>用法</h3>
 * <pre>
 * // 单个函数适配
 * Object fn = NativeFunctionAdapter.adapt(myNativeFunction);
 *
 * // 任意值适配（NovaNativeFunction 自动转换，其他值透传）
 * Object val = NativeFunctionAdapter.toBindingValue(someValue);
 * </pre>
 */
public final class NativeFunctionAdapter {

    private NativeFunctionAdapter() {}

    /**
     * 将 NovaNativeFunction 适配为编译器可调用的对象。
     *
     * @return arity 0-8 返回 FunctionN 实例；arity 9+/varargs 返回带 invoke 重载的适配器
     */
    public static Object adapt(NovaNativeFunction nf) {
        switch (nf.getArity()) {
            case 0:
                return (Function0<Object>) () ->
                        callNative(nf, Collections.emptyList());
            case 1:
                return (Function1<Object, Object>) (a1) ->
                        callNative(nf, Collections.singletonList(AbstractNovaValue.fromJava(a1)));
            case 2:
                return (Function2<Object, Object, Object>) (a1, a2) ->
                        callNative(nf, Arrays.asList(AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2)));
            case 3:
                return (Function3<Object, Object, Object, Object>) (a1, a2, a3) ->
                        callNative(nf, Arrays.asList(
                                AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2), AbstractNovaValue.fromJava(a3)));
            case 4:
                return (Function4<Object, Object, Object, Object, Object>) (a1, a2, a3, a4) ->
                        callNative(nf, Arrays.asList(
                                AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2),
                                AbstractNovaValue.fromJava(a3), AbstractNovaValue.fromJava(a4)));
            case 5:
                return (Function5<Object, Object, Object, Object, Object, Object>) (a1, a2, a3, a4, a5) ->
                        callNative(nf, Arrays.asList(
                                AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2),
                                AbstractNovaValue.fromJava(a3), AbstractNovaValue.fromJava(a4),
                                AbstractNovaValue.fromJava(a5)));
            case 6:
                return (Function6<Object, Object, Object, Object, Object, Object, Object>) (a1, a2, a3, a4, a5, a6) ->
                        callNative(nf, Arrays.asList(
                                AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2),
                                AbstractNovaValue.fromJava(a3), AbstractNovaValue.fromJava(a4),
                                AbstractNovaValue.fromJava(a5), AbstractNovaValue.fromJava(a6)));
            case 7:
                return (Function7<Object, Object, Object, Object, Object, Object, Object, Object>) (a1, a2, a3, a4, a5, a6, a7) ->
                        callNative(nf, Arrays.asList(
                                AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2),
                                AbstractNovaValue.fromJava(a3), AbstractNovaValue.fromJava(a4),
                                AbstractNovaValue.fromJava(a5), AbstractNovaValue.fromJava(a6),
                                AbstractNovaValue.fromJava(a7)));
            case 8:
                return (Function8<Object, Object, Object, Object, Object, Object, Object, Object, Object>) (a1, a2, a3, a4, a5, a6, a7, a8) ->
                        callNative(nf, Arrays.asList(
                                AbstractNovaValue.fromJava(a1), AbstractNovaValue.fromJava(a2),
                                AbstractNovaValue.fromJava(a3), AbstractNovaValue.fromJava(a4),
                                AbstractNovaValue.fromJava(a5), AbstractNovaValue.fromJava(a6),
                                AbstractNovaValue.fromJava(a7), AbstractNovaValue.fromJava(a8)));
            default:
                return new HighArityAdapter(nf);
        }
    }

    /**
     * 将任意值转换为字节码模式可用的绑定值。
     * <ul>
     *   <li>{@link NovaNativeFunction} → 适配为 FunctionN</li>
     *   <li>其他 {@link NovaValue} → {@code toJavaValue()}</li>
     *   <li>普通 Java 对象 → 直接透传</li>
     * </ul>
     */
    public static Object toBindingValue(Object value) {
        if (value instanceof NovaNativeFunction) {
            return adapt((NovaNativeFunction) value);
        }
        if (value instanceof NovaValue) {
            Object jv = ((NovaValue) value).toJavaValue();
            if (jv instanceof NovaNativeFunction) {
                return adapt((NovaNativeFunction) jv);
            }
            return jv;
        }
        return value;
    }

    // ── 内部 ──

    private static Object callNative(NovaNativeFunction nf, List<NovaValue> args) {
        NovaValue result = nf.call(null, args);
        return result == NovaNull.UNIT ? null : result.toJavaValue();
    }

    /**
     * arity 9+ 适配器。提供 invoke 重载方法，
     * 由 {@code LambdaInvoker} 通过缓存反射调用。
     * 不实现 FunctionN 接口，避免 {@code andThen} 默认方法冲突。
     */
    static final class HighArityAdapter {

        private final NovaNativeFunction nf;

        HighArityAdapter(NovaNativeFunction nf) { this.nf = nf; }

        public Object invoke(Object a1, Object a2, Object a3, Object a4) { return callN(a1, a2, a3, a4); }
        public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) { return callN(a1, a2, a3, a4, a5); }
        public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) { return callN(a1, a2, a3, a4, a5, a6); }
        public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) { return callN(a1, a2, a3, a4, a5, a6, a7); }
        public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) { return callN(a1, a2, a3, a4, a5, a6, a7, a8); }

        private Object callN(Object... javaArgs) {
            List<NovaValue> args = new ArrayList<>(javaArgs.length);
            for (Object a : javaArgs) args.add(AbstractNovaValue.fromJava(a));
            return callNative(nf, args);
        }
    }
}
