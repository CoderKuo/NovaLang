package com.novalang.runtime.interpreter.reflect;
import com.novalang.runtime.*;


/**
 * 参数反射信息
 */
public final class NovaParamInfo extends AbstractNovaValue {

    public final String name;
    public final String type;
    public final boolean hasDefault;

    public NovaParamInfo(String name, String type, boolean hasDefault) {
        this.name = name;
        this.type = type;
        this.hasDefault = hasDefault;
    }

    @Override
    public String getTypeName() {
        return "ParamInfo";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "ParamInfo(" + name + (type != null ? ": " + type : "") + ")";
    }
}
