package nova.runtime.interpreter;
import nova.runtime.*;
import nova.runtime.types.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nova 对象实例
 *
 * <p>字段存储使用紧凑的 NovaValue[] 数组，索引由 NovaClass.fieldLayout 预计算。
 * 布局外的动态字段（极少发生）使用惰性 overflowFields HashMap。</p>
 */
public final class NovaObject extends AbstractNovaValue {

    private final NovaClass novaClass;
    private final NovaValue[] fieldValues;
    private Map<String, NovaValue> overflowFields;
    private Object javaDelegate;

    public NovaObject(NovaClass novaClass) {
        this.novaClass = novaClass;
        this.fieldValues = new NovaValue[novaClass.getFieldCount()];
    }

    public NovaClass getNovaClass() {
        return novaClass;
    }

    @Override
    public String getTypeName() {
        return novaClass.getName();
    }

    @Override
    public String getNovaTypeName() {
        return novaClass.getName();
    }

    public void setJavaDelegate(Object delegate) {
        this.javaDelegate = delegate;
    }

    public Object getJavaDelegate() {
        return javaDelegate;
    }

    @Override
    public Object toJavaValue() {
        if (javaDelegate != null) return javaDelegate;
        // 转换为 Map 表示
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("__class__", novaClass.getName());
        for (Map.Entry<String, NovaValue> entry : getFields().entrySet()) {
            result.put(entry.getKey(), entry.getValue().toJavaValue());
        }
        return result;
    }

    @Override
    public boolean isObject() {
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(novaClass.getName()).append("(");

        // Data class: 按构造器参数顺序输出，字符串不加引号（Kotlin 风格）
        if (novaClass.isData() && novaClass.getDataFieldOrder() != null) {
            boolean first = true;
            for (String name : novaClass.getDataFieldOrder()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(name).append("=");
                NovaValue value = hasField(name) ? getField(name) : NovaNull.NULL;
                sb.append(value.asString());
            }
        } else {
            boolean first = true;
            for (Map.Entry<String, NovaValue> entry : getFields().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(entry.getKey()).append("=");
                NovaValue value = entry.getValue();
                if (value.isString()) {
                    sb.append("\"").append(value.asString()).append("\"");
                } else {
                    sb.append(value.toString());
                }
            }
        }

        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == this) return true;
        if (!(other instanceof NovaObject)) return false;
        NovaObject otherObj = (NovaObject) other;
        if (this.novaClass != otherObj.novaClass) return false;
        return Arrays.equals(this.fieldValues, otherObj.fieldValues);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fieldValues);
    }

    // ============ 字段操作 ============

    public NovaValue getField(String name) {
        int idx = novaClass.getFieldIndex(name);
        if (idx >= 0) {
            NovaValue val = fieldValues[idx];
            if (val != null) return val;
        } else if (overflowFields != null) {
            NovaValue val = overflowFields.get(name);
            if (val != null) return val;
        }
        NovaValue staticField = novaClass.getStaticField(name);
        if (staticField != null) return staticField;
        throw new NovaRuntimeException("Undefined property: " + novaClass.getName() + "." + name);
    }

    /** 直接按索引访问字段（热路径使用，跳过名称查找） */
    public NovaValue getFieldByIndex(int index) {
        return fieldValues[index];
    }

    public void setField(String name, NovaValue value) {
        int idx = novaClass.getFieldIndex(name);
        if (idx >= 0) {
            fieldValues[idx] = value;
        } else {
            if (overflowFields == null) overflowFields = new HashMap<>(4);
            overflowFields.put(name, value);
        }
    }

    public boolean hasField(String name) {
        int idx = novaClass.getFieldIndex(name);
        if (idx >= 0) return fieldValues[idx] != null;
        return overflowFields != null && overflowFields.containsKey(name);
    }

    public Map<String, NovaValue> getFields() {
        String[] names = novaClass.getFieldNames();
        Map<String, NovaValue> map = new LinkedHashMap<>();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (fieldValues[i] != null) {
                    map.put(names[i], fieldValues[i]);
                }
            }
        }
        if (overflowFields != null) map.putAll(overflowFields);
        return map;
    }

    // ============ 方法调用 ============

    public NovaCallable getMethod(String name) {
        // 先查找 callableMethods（含 HirFunctionValue 等）
        NovaCallable callable = novaClass.findCallableMethod(name);
        if (callable != null) {
            return callable;
        }
        // 查找接口的默认方法
        return novaClass.findInterfaceDefaultMethod(name);
    }

    public NovaBoundMethod getBoundMethod(String name) {
        NovaCallable method = getMethod(name);
        if (method != null) {
            return new NovaBoundMethod(this, method);
        }
        return null;
    }

    // ============ 类型检查 ============

    public boolean isInstanceOf(NovaClass cls) {
        return novaClass.isSubclassOf(cls);
    }

    /**
     * 检查是否实现了指定接口
     */
    public boolean implementsInterface(NovaInterface iface) {
        return novaClass.implementsInterface(iface);
    }
}
