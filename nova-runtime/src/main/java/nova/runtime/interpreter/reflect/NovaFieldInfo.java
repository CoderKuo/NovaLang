package nova.runtime.interpreter.reflect;
import nova.runtime.*;

import nova.runtime.interpreter.NovaClass;

/**
 * 字段反射信息
 */
public final class NovaFieldInfo extends NovaValue {

    public final String name;
    public final String type;
    public final String visibility;
    public final boolean mutable;

    // 解释器模式
    final NovaClass ownerClass;
    // 编译模式
    final java.lang.reflect.Field javaField;

    public NovaFieldInfo(String name, String type, String visibility, boolean mutable,
                         NovaClass ownerClass, java.lang.reflect.Field javaField) {
        this.name = name;
        this.type = type;
        this.visibility = visibility;
        this.mutable = mutable;
        this.ownerClass = ownerClass;
        this.javaField = javaField;
    }

    public NovaClass getOwnerClass() {
        return ownerClass;
    }

    public java.lang.reflect.Field getJavaField() {
        return javaField;
    }

    // 编译模式方法
    public Object get(Object instance) {
        if (javaField != null) {
            try {
                javaField.setAccessible(true);
                return javaField.get(instance);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get field " + name + ": " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("Cannot get field in this mode");
    }

    public void set(Object instance, Object value) {
        if (javaField != null) {
            try {
                javaField.setAccessible(true);
                javaField.set(instance, value);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set field " + name + ": " + e.getMessage(), e);
            }
        } else {
            throw new RuntimeException("Cannot set field in this mode");
        }
    }

    @Override
    public String getTypeName() {
        return "FieldInfo";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "FieldInfo(" + name + (type != null ? ": " + type : "") + ")";
    }
}
