package com.novalang.runtime.interpreter.reflect;
import com.novalang.runtime.*;

import java.util.List;

/**
 * 方法反射信息
 */
public final class NovaMethodInfo extends AbstractNovaValue {

    public final String name;
    public final String visibility;

    private final List<NovaParamInfo> paramInfos;

    // 解释器模式（NovaCallable，如 HirFunctionValue）
    final com.novalang.runtime.NovaCallable novaCallable;
    // 编译模式
    final java.lang.reflect.Method javaMethod;

    public NovaMethodInfo(String name, String visibility, List<NovaParamInfo> paramInfos,
                          com.novalang.runtime.NovaCallable novaCallable, java.lang.reflect.Method javaMethod) {
        this.name = name;
        this.visibility = visibility;
        this.paramInfos = paramInfos;
        this.novaCallable = novaCallable;
        this.javaMethod = javaMethod;
    }

    public NovaMethodInfo(String name, String visibility, List<NovaParamInfo> paramInfos,
                          com.novalang.runtime.NovaCallable novaCallable) {
        this(name, visibility, paramInfos, novaCallable, null);
    }

    public com.novalang.runtime.NovaCallable getNovaCallable() {
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
                com.novalang.runtime.stdlib.LambdaUtils.trySetAccessible(javaMethod);
                return javaMethod.invoke(instance, args);
            } catch (Exception e) {
                throw NovaErrors.wrap("调用方法 " + name + " 失败", e);
            }
        }
        throw new NovaException(NovaException.ErrorKind.JAVA_INTEROP, "当前模式无法调用方法 " + name);
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
