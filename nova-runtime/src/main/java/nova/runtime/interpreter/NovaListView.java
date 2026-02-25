package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.AbstractList;

/**
 * NovaList 的 java.util.List 视图，委托到原始 NovaList。
 * 用于 StdlibRegistry 扩展方法调用时保持可变操作穿透到原始列表。
 */
final class NovaListView extends AbstractList<Object> {

    final NovaList delegate;

    NovaListView(NovaList delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object get(int index) {
        return elemToJava(delegate.get(index));
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Object set(int index, Object element) {
        NovaValue old = delegate.get(index);
        delegate.set(index, NovaValue.fromJava(element));
        return elemToJava(old);
    }

    @Override
    public void add(int index, Object element) {
        delegate.addAt(index, NovaValue.fromJava(element));
    }

    @Override
    public Object remove(int index) {
        return elemToJava(delegate.removeAt(index));
    }

    private static Object elemToJava(NovaValue v) {
        if (v instanceof NovaObject || v instanceof NovaPair) return v;
        return v.toJavaValue();
    }
}
