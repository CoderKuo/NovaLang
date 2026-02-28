package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * NovaMap 的 java.util.Map 视图，委托到原始 NovaMap。
 * 用于 StdlibRegistry 扩展方法调用时保持可变操作穿透到原始映射。
 */
final class NovaMapView extends AbstractMap<Object, Object> {

    final NovaMap delegate;

    NovaMapView(NovaMap delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object get(Object key) {
        NovaValue result = delegate.get(AbstractNovaValue.fromJava(key));
        if (result instanceof NovaNull) return null;
        return elemToJava(result);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object put(Object key, Object value) {
        NovaValue nKey = AbstractNovaValue.fromJava(key);
        NovaValue old = delegate.get(nKey);
        delegate.put(nKey, AbstractNovaValue.fromJava(value));
        return (old instanceof NovaNull) ? null : elemToJava(old);
    }

    @Override
    public Object remove(Object key) {
        NovaValue nKey = AbstractNovaValue.fromJava(key);
        NovaValue old = delegate.remove(nKey);
        return (old instanceof NovaNull) ? null : elemToJava(old);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(AbstractNovaValue.fromJava(key));
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        return new AbstractSet<Map.Entry<Object, Object>>() {
            @Override
            public Iterator<Map.Entry<Object, Object>> iterator() {
                final Iterator<Map.Entry<NovaValue, NovaValue>> it =
                        delegate.getEntries().entrySet().iterator();
                return new Iterator<Map.Entry<Object, Object>>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Map.Entry<Object, Object> next() {
                        Map.Entry<NovaValue, NovaValue> e = it.next();
                        return new SimpleEntry<>(
                                elemToJava(e.getKey()),
                                elemToJava(e.getValue()));
                    }
                };
            }

            @Override
            public int size() {
                return delegate.size();
            }
        };
    }

    private static Object elemToJava(NovaValue v) {
        return v instanceof NovaObject ? v : v.toJavaValue();
    }
}
