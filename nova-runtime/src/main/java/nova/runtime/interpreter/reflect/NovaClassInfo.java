package nova.runtime.interpreter.reflect;
import nova.runtime.*;

import com.novalang.compiler.ast.Modifier;
import nova.runtime.interpreter.NovaCallable;
import nova.runtime.interpreter.NovaClass;
import nova.runtime.interpreter.NovaInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 类反射信息
 */
public final class NovaClassInfo extends NovaValue {

    // 公共字段（编译模式 NovaDynamic.getMember 可读）
    public final String name;
    public final String superclass;
    public final List<String> interfaces;

    private final List<NovaFieldInfo> fieldInfos;
    private final List<NovaMethodInfo> methodInfos;
    private final List<NovaAnnotationInfo> annotationInfos;

    // 来源引用（互斥）
    final NovaClass novaClass;
    final Class<?> javaClass;

    public NovaClass getNovaClass() { return novaClass; }
    public Class<?> getJavaClass() { return javaClass; }

    private NovaClassInfo(String name, String superclass, List<String> interfaces,
                          List<NovaFieldInfo> fieldInfos, List<NovaMethodInfo> methodInfos,
                          List<NovaAnnotationInfo> annotationInfos,
                          NovaClass novaClass, Class<?> javaClass) {
        this.name = name;
        this.superclass = superclass;
        this.interfaces = interfaces;
        this.fieldInfos = fieldInfos;
        this.methodInfos = methodInfos;
        this.annotationInfos = annotationInfos;
        this.novaClass = novaClass;
        this.javaClass = javaClass;
    }

    public List<NovaFieldInfo> getFieldInfos() {
        return fieldInfos;
    }

    public List<NovaMethodInfo> getMethodInfos() {
        return methodInfos;
    }

    public List<NovaAnnotationInfo> getAnnotationInfos() {
        return annotationInfos;
    }

    public NovaFieldInfo findField(String fieldName) {
        for (NovaFieldInfo fi : fieldInfos) {
            if (fi.name.equals(fieldName)) return fi;
        }
        return null;
    }

    public NovaMethodInfo findMethod(String methodName) {
        for (NovaMethodInfo mi : methodInfos) {
            if (mi.name.equals(methodName)) return mi;
        }
        return null;
    }

    // 编译模式方法（NovaDynamic.invokeMethod 可调用）
    public Object field(String fieldName) {
        return findField(fieldName);
    }

    public Object method(String methodName) {
        return findMethod(methodName);
    }

    /**
     * 公共工厂方法：从预构建的列表创建 ClassInfo。
     */
    public static NovaClassInfo create(String name, String superclass, List<String> interfaces,
                                        List<NovaFieldInfo> fields, List<NovaMethodInfo> methods,
                                        List<NovaAnnotationInfo> annotations, NovaClass novaClass) {
        return new NovaClassInfo(name, superclass, interfaces, fields, methods, annotations, novaClass, null);
    }

    // ============ 工厂方法 ============

    /** 从 NovaCallable 提取参数信息（兼容 HIR/MIR 两种路径） */
    public static List<NovaParamInfo> extractParams(NovaCallable callable) {
        // HIR 路径：HirFunctionValue 有完整的 HirParam 信息
        if (callable instanceof nova.runtime.interpreter.HirFunctionValue) {
            nova.runtime.interpreter.HirFunctionValue hfv = (nova.runtime.interpreter.HirFunctionValue) callable;
            if (hfv.getDeclaration() != null) {
                List<NovaParamInfo> params = new ArrayList<>();
                for (com.novalang.ir.hir.decl.HirParam p : hfv.getDeclaration().getParams()) {
                    String typeName = p.getType() != null ? p.getType().toString() : null;
                    params.add(new NovaParamInfo(p.getName(), typeName, p.getDefaultValue() != null));
                }
                return params;
            }
        }
        // MIR 路径（或通用回退）：通过 NovaCallable.getParamNames() 获取
        List<String> paramNames = callable.getParamNames();
        if (!paramNames.isEmpty()) {
            List<String> typeNames = callable.getParamTypeNames();
            // NovaCallable 接口提供 hasDefault 信息（MirCallable 等实现）
            java.util.List<Boolean> hasDefaults = callable.getParamHasDefaults();
            List<NovaParamInfo> params = new ArrayList<>(paramNames.size());
            for (int i = 0; i < paramNames.size(); i++) {
                String typeName = i < typeNames.size() ? typeNames.get(i) : null;
                boolean hasDef = hasDefaults != null && i < hasDefaults.size() && hasDefaults.get(i);
                params.add(new NovaParamInfo(paramNames.get(i), typeName, hasDef));
            }
            return params;
        }
        return Collections.emptyList();
    }

    public static NovaClassInfo fromNovaClass(NovaClass cls) {
        // 使用 HIR 路径预构建的缓存
        if (cls.getCachedClassInfo() instanceof NovaClassInfo) {
            return (NovaClassInfo) cls.getCachedClassInfo();
        }

        // 无缓存时从运行时数据构建
        List<NovaFieldInfo> fields = new ArrayList<NovaFieldInfo>();

        // 从 dataFieldOrder 提取字段（data class 的字段名列表）
        List<String> fieldOrder = cls.getDataFieldOrder();
        if (fieldOrder != null) {
            for (String fieldName : fieldOrder) {
                Modifier vis = cls.getFieldVisibility(fieldName);
                String visStr = vis != null ? vis.name().toLowerCase() : "public";
                fields.add(new NovaFieldInfo(fieldName, null, visStr, true, cls, null));
            }
        }

        // 提取方法
        List<NovaMethodInfo> methods = new ArrayList<NovaMethodInfo>();
        for (Map.Entry<String, NovaCallable> entry : cls.getMethods().entrySet()) {
            Modifier vis = cls.getMethodVisibility(entry.getKey());
            String visStr = vis != null ? vis.name().toLowerCase() : "public";
            List<NovaParamInfo> params = extractParams(entry.getValue());
            methods.add(new NovaMethodInfo(entry.getKey(), visStr, params, entry.getValue()));
        }
        // 也添加 callableMethods
        for (Map.Entry<String, NovaCallable> entry : cls.getCallableMethods().entrySet()) {
            if (cls.getMethods().containsKey(entry.getKey())) continue; // 避免重复
            Modifier vis = cls.getMethodVisibility(entry.getKey());
            String visStr = vis != null ? vis.name().toLowerCase() : "public";
            List<NovaParamInfo> params = extractParams(entry.getValue());
            methods.add(new NovaMethodInfo(entry.getKey(), visStr, params, entry.getValue()));
        }

        // 注解信息（无 AST 则为空）
        List<NovaAnnotationInfo> annotations = Collections.emptyList();

        // 父类和接口
        String superName = cls.getSuperclass() != null ? cls.getSuperclass().getName() : null;
        List<String> ifaceNames = new ArrayList<String>();
        for (NovaInterface iface : cls.getInterfaces()) {
            ifaceNames.add(iface.getName());
        }

        return new NovaClassInfo(cls.getName(), superName, ifaceNames,
                fields, methods, annotations, cls, null);
    }

    public static NovaClassInfo fromJavaClass(Class<?> cls) {
        List<NovaFieldInfo> fields = new ArrayList<NovaFieldInfo>();
        for (Field f : cls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String vis = java.lang.reflect.Modifier.isPublic(f.getModifiers()) ? "public" :
                         java.lang.reflect.Modifier.isPrivate(f.getModifiers()) ? "private" :
                         java.lang.reflect.Modifier.isProtected(f.getModifiers()) ? "protected" : "internal";
            boolean mutable = !java.lang.reflect.Modifier.isFinal(f.getModifiers());
            fields.add(new NovaFieldInfo(f.getName(), f.getType().getSimpleName(), vis, mutable, null, f));
        }

        List<NovaMethodInfo> methods = new ArrayList<NovaMethodInfo>();
        for (Method m : cls.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            String vis = java.lang.reflect.Modifier.isPublic(m.getModifiers()) ? "public" :
                         java.lang.reflect.Modifier.isPrivate(m.getModifiers()) ? "private" :
                         java.lang.reflect.Modifier.isProtected(m.getModifiers()) ? "protected" : "internal";
            List<NovaParamInfo> params = new ArrayList<NovaParamInfo>();
            for (java.lang.reflect.Parameter p : m.getParameters()) {
                params.add(new NovaParamInfo(p.getName(), p.getType().getSimpleName(), false));
            }
            methods.add(new NovaMethodInfo(m.getName(), vis, params, null, m));
        }

        String superName = cls.getSuperclass() != null && cls.getSuperclass() != Object.class
                ? cls.getSuperclass().getSimpleName() : null;
        List<String> ifaceNames = new ArrayList<String>();
        for (Class<?> iface : cls.getInterfaces()) {
            ifaceNames.add(iface.getSimpleName());
        }

        return new NovaClassInfo(cls.getSimpleName(), superName, ifaceNames,
                fields, methods, Collections.<NovaAnnotationInfo>emptyList(), null, cls);
    }

    @Override
    public String getTypeName() {
        return "ClassInfo";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "ClassInfo(" + name + ")";
    }
}
