package nova.runtime.interpreter;
import nova.runtime.*;

import nova.runtime.stdlib.Ext;
import nova.runtime.stdlib.ExtProperty;

import nova.runtime.stdlib.Ext;
import nova.runtime.stdlib.ExtProperty;

/**
 * NovaPair 类型扩展方法。
 */
@Ext("nova/Pair")
public final class PairExtensions {

    private PairExtensions() {}

    @ExtProperty
    public static Object first(Object pair) {
        return ((NovaPair) pair).getFirst();
    }

    @ExtProperty
    public static Object key(Object pair) {
        return ((NovaPair) pair).getFirst();
    }

    @ExtProperty
    public static Object second(Object pair) {
        return ((NovaPair) pair).getSecond();
    }

    @ExtProperty
    public static Object value(Object pair) {
        return ((NovaPair) pair).getSecond();
    }

    public static Object toList(Object pair) {
        return ((NovaPair) pair).toList();
    }
}
