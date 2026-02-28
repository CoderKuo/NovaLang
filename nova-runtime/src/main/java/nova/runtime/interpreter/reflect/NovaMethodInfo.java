package nova.runtime.interpreter.reflect;
import nova.runtime.*;

import java.util.List;

/**
 * 方法反射信息
 */
public final class NovaMethodInfo extends AbstractNovaValue {

    public final String name;
    public final String visibility;

    private final List<NovaParamInfo> paramInfos;

    // 解释器模式（NovaCallable，如 HirFunctionValue）
    final nova.runtime.NovaCallable novaCallable;
    // 编译模式
    final java.lang.reflect.Method javaMethod;

    public NovaMethodInfo(String name, String visibility, List<NovaParamInfo> paramInfos,
                          nova.runtime.NovaCallable novaCallable, java.lang.reflect.Method javaMethod) {
        this.name = name;
        this.visibility = visibility;
        this.paramInfos = paramInfos;
        this.novaCallable = novaCallable;
        this.javaMethod = javaMethod;
    }

    public NovaMethodInfo(String name, String visibility, List<NovaParamInfo> paramInfos,
                          nova.runtime.NovaCallable novaCallable) {
        this(name, visibility, paramInfos, novaCallable, null);
    }

    public nova.runtime.NovaCallable getNovaCallable() {
        return novaCallable;
    }

    public List<NovaParamInfo> getParamInfos() {
        return paramInfos;
    }

    public java.lang.reflect.Method getJavaMethod() {
        return javaMethod;
    }

    // 编译模式方法
    public Object call(Object instance, Object... args) {
        if (javaMethod != null) {
            try {
                javaMethod.setAccessible(true);
                return javaMethod.invoke(instance, args);
            } catch (Exception e) {
                throw new RuntimeException("Failed to call method " + name + ": " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("Cannot call method in this mode");
    }

    @Override
    public String getTypeName() {
        return "MethodInfo";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "MethodInfo(" + name + ")";
    }
}
