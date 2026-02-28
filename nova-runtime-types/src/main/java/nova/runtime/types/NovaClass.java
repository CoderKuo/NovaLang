package nova.runtime.types;

import nova.runtime.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nova 类
 */
public final class NovaClass extends AbstractNovaValue implements NovaCallable {

    private final String name;
    private final NovaClass superclass;
    private final List<NovaInterface> interfaces;
    private final Map<String, NovaCallable> methods;
    private final Map<String, NovaValue> staticFields;
    private final Map<String, Modifier> fieldVisibility;   // 字段可见性
    private final Map<String, Modifier> methodVisibility;  // 方法可见性
    private final Environment closure;
    private boolean isData = false;
    private boolean isBuilder = false;
    private List<String> dataFieldOrder;  // data class 字段顺序（主构造器参数名）
    private boolean isAnnotation = false;
    private boolean isAbstractFlag = false;
    private boolean isSealedFlag = false;
    /** 首次实例化验证通过后置 true，后续跳过 isAbstract / getUnimplementedMethods 检查 */
    private boolean instantiationValidated = false;

    // 字段布局（字段名 → 数组索引），null 表示未计算
    private Map<String, Integer> fieldLayout;
    // 有序字段名数组（用于 getFields() 重建 Map）
    private String[] fieldNames;

    // 可调用方法（HirFunctionValue 等 NovaCallable）
    private final Map<String, NovaCallable> callableMethods = new HashMap<String, NovaCallable>();

    // 构造器（NovaCallable）
    private NovaCallable constructorCallable;
    // 多构造器列表（支持重载）
    private final List<NovaCallable> hirConstructors = new ArrayList<NovaCallable>();
    // 按 arity 缓存构造器（O(1) 查找）
    private Map<Integer, NovaCallable> ctorByArity;

    // 方法查找缓存（含继承链）
    private Map<String, NovaCallable> methodCache;

    // 缓存的 ClassInfo（HIR 路径预构建，供 fromNovaClass 使用）
    private Object cachedClassInfo;

    // Java 类型支持
    private Class<?> javaSuperclass;
    private List<Class<?>> javaInterfaces = new ArrayList<Class<?>>();
    private List<NovaValue> javaSuperConstructorArgs;

    public NovaClass(String name, NovaClass superclass, Environment closure) {
        this.name = name;
        this.superclass = superclass;
        this.interfaces = new ArrayList<NovaInterface>();
        this.methods = new HashMap<String, NovaCallable>();
        this.staticFields = new HashMap<String, NovaValue>();
        this.fieldVisibility = new HashMap<String, Modifier>();
        this.methodVisibility = new HashMap<String, Modifier>();
        this.closure = closure;
    }

    public String getName() {
        return name;
    }

    public NovaClass getSuperclass() {
        return superclass;
    }

    public Environment getClosure() {
        return closure;
    }

    @Override
    public String getTypeName() {
        return "Class";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "class " + name;
    }

    @Override
    public int getArity() {
        if (constructorCallable != null) {
            return constructorCallable.getArity();
        }
        if (!hirConstructors.isEmpty()) {
            return hirConstructors.get(0).getArity();
        }
        return 0;
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        return ctx.instantiate(this, args, null);
    }

    @Override
    public boolean supportsNamedArgs() {
        return true;
    }

    @Override
    public NovaValue callWithNamed(ExecutionContext ctx, List<NovaValue> args,
                                    Map<String, NovaValue> namedArgs) {
        return ctx.instantiate(this, args, namedArgs);
    }

    // ============ 方法查找 ============

    public NovaCallable findMethod(String methodName) {
        // 快速路径：当前类直接命中
        NovaCallable method = methods.get(methodName);
        if (method != null) return method;
        if (superclass == null) return null;
        // 需要遍历继承链 → 查缓存
        if (methodCache != null) {
            method = methodCache.get(methodName);
            if (method != null) return method;
        }
        method = superclass.findMethod(methodName);
        if (method != null) {
            if (methodCache == null) methodCache = new HashMap<>();
            methodCache.put(methodName, method);
        }
        return method;
    }

    public void defineMethod(String methodName, NovaCallable method) {
        methods.put(methodName, method);
        methodCache = null; // 方法表变化，清除缓存
    }

    /**
     * 添加可调用方法（支持 HirFunctionValue 等 NovaCallable 类型）
     */
    public void addMethod(String methodName, NovaCallable method) {
        callableMethods.put(methodName, method);
    }

    /**
     * 查找可调用方法（先查 callableMethods，再查 methods 继承链）
     */
    public NovaCallable findCallableMethod(String methodName) {
        NovaCallable callable = callableMethods.get(methodName);
        if (callable != null) return callable;
        NovaCallable method = findMethod(methodName);
        if (method != null) return method;
        // 检查父类的 callableMethods
        if (superclass != null) {
            return superclass.findCallableMethod(methodName);
        }
        return null;
    }

    public void setConstructorCallable(NovaCallable ctor) {
        this.constructorCallable = ctor;
    }

    public NovaCallable getConstructorCallable() {
        return constructorCallable;
    }

    public void addHirConstructor(NovaCallable ctor) {
        hirConstructors.add(ctor);
    }

    public List<NovaCallable> getHirConstructors() {
        return hirConstructors;
    }

    /** 按参数数量 O(1) 查找构造器（首次调用时构建缓存） */
    public NovaCallable getConstructorByArity(int arity) {
        if (ctorByArity == null) {
            ctorByArity = new HashMap<>(hirConstructors.size());
            for (NovaCallable c : hirConstructors) {
                ctorByArity.putIfAbsent(c.getArity(), c);
            }
        }
        return ctorByArity.get(arity);
    }

    public Map<String, NovaCallable> getMethods() {
        return methods;
    }

    public Map<String, NovaCallable> getCallableMethods() {
        return callableMethods;
    }

    public void setCachedClassInfo(Object info) { this.cachedClassInfo = info; }
    public Object getCachedClassInfo() { return cachedClassInfo; }

    // ============ 静态成员 ============

    public NovaValue getStaticField(String fieldName) {
        NovaValue value = staticFields.get(fieldName);
        if (value != null) {
            return value;
        }
        if (superclass != null) {
            return superclass.getStaticField(fieldName);
        }
        return null;
    }

    public void setStaticField(String fieldName, NovaValue value) {
        staticFields.put(fieldName, value);
    }

    // ============ 接口实现 ============

    /**
     * 添加实现的接口
     */
    public void addInterface(NovaInterface iface) {
        interfaces.add(iface);
    }

    /**
     * 获取实现的接口列表
     */
    public List<NovaInterface> getInterfaces() {
        return interfaces;
    }

    /**
     * 检查是否实现了指定接口
     */
    public boolean implementsInterface(NovaInterface iface) {
        for (NovaInterface impl : interfaces) {
            if (impl == iface || impl.isSubInterfaceOf(iface)) {
                return true;
            }
        }
        // 检查父类
        if (superclass != null) {
            return superclass.implementsInterface(iface);
        }
        return false;
    }

    /**
     * 查找接口的默认方法实现
     */
    public NovaCallable findInterfaceDefaultMethod(String methodName) {
        for (NovaInterface iface : interfaces) {
            NovaCallable defaultMethod = iface.getDefaultMethod(methodName);
            if (defaultMethod != null) {
                return defaultMethod;
            }
        }
        if (superclass != null) {
            return superclass.findInterfaceDefaultMethod(methodName);
        }
        return null;
    }

    // ============ Java 类型支持 ============

    public void setJavaSuperclass(Class<?> cls) {
        this.javaSuperclass = cls;
    }

    public Class<?> getJavaSuperclass() {
        return javaSuperclass;
    }

    public void addJavaInterface(Class<?> iface) {
        javaInterfaces.add(iface);
    }

    public List<Class<?>> getJavaInterfaces() {
        return javaInterfaces;
    }

    public boolean hasJavaSuperTypes() {
        return javaSuperclass != null || !javaInterfaces.isEmpty();
    }

    public void setJavaSuperConstructorArgs(List<NovaValue> args) {
        this.javaSuperConstructorArgs = args;
    }

    public List<NovaValue> getJavaSuperConstructorArgs() {
        return javaSuperConstructorArgs;
    }

    // ============ 可见性控制 ============

    /**
     * 设置字段的可见性修饰符
     */
    public void setFieldVisibility(String fieldName, Modifier visibility) {
        fieldVisibility.put(fieldName, visibility);
    }

    /**
     * 获取字段的可见性修饰符（默认为 PUBLIC）
     */
    public Modifier getFieldVisibility(String fieldName) {
        Modifier visibility = fieldVisibility.get(fieldName);
        return visibility != null ? visibility : Modifier.PUBLIC;
    }

    /**
     * 设置方法的可见性修饰符
     */
    public void setMethodVisibility(String methodName, Modifier visibility) {
        methodVisibility.put(methodName, visibility);
    }

    /**
     * 获取方法的可见性修饰符（默认为 PUBLIC）
     */
    public Modifier getMethodVisibility(String methodName) {
        Modifier visibility = methodVisibility.get(methodName);
        if (visibility != null) {
            return visibility;
        }
        // 检查父类
        if (superclass != null) {
            return superclass.getMethodVisibility(methodName);
        }
        return Modifier.PUBLIC;
    }

    /**
     * 检查成员是否可以从给定类访问
     * @param memberVisibility 成员的可见性修饰符
     * @param accessingClass 正在访问的类（可以为 null 表示外部访问）
     * @return 是否允许访问
     */
    public boolean isAccessible(Modifier memberVisibility, NovaClass accessingClass) {
        switch (memberVisibility) {
            case PUBLIC:
                return true;
            case PRIVATE:
                // private 只能在同一个类内访问
                return accessingClass == this;
            case PROTECTED:
                // protected 可以在同一个类或子类中访问
                return accessingClass != null &&
                       (accessingClass == this || accessingClass.isSubclassOf(this));
            case INTERNAL:
                // internal 在同一个包内访问（简化处理：同一模块内都可访问）
                // 对于解释器模式，我们简单地允许访问
                return true;
            default:
                return true;
        }
    }

    /**
     * 检查字段是否可以从外部访问
     */
    public boolean isFieldAccessibleFrom(String fieldName, NovaClass accessingClass) {
        Modifier visibility = getFieldVisibility(fieldName);
        return isAccessible(visibility, accessingClass);
    }

    /**
     * 找到定义指定方法的类
     */
    public NovaClass findMethodDeclaringClass(String methodName) {
        if (methods.containsKey(methodName) || callableMethods.containsKey(methodName)) {
            return this;
        }
        if (superclass != null) {
            return superclass.findMethodDeclaringClass(methodName);
        }
        return null;
    }

    /**
     * 检查方法是否可以从外部访问
     */
    public boolean isMethodAccessibleFrom(String methodName, NovaClass accessingClass) {
        // 找到定义该方法的类
        NovaClass declaringClass = findMethodDeclaringClass(methodName);
        if (declaringClass == null) {
            return true;  // 方法不存在，让后续代码处理
        }

        Modifier visibility = declaringClass.methodVisibility.get(methodName);
        if (visibility == null) {
            visibility = Modifier.PUBLIC;
        }

        // 在定义方法的类上检查可见性
        return declaringClass.isAccessible(visibility, accessingClass);
    }

    // ============ @data / @builder 注解 ============

    public boolean isData() { return isData; }
    public void setData(boolean data) { this.isData = data; }
    public List<String> getDataFieldOrder() { return dataFieldOrder; }
    public void setDataFieldOrder(List<String> order) { this.dataFieldOrder = order; }
    public boolean isBuilder() { return isBuilder; }
    public void setBuilder(boolean builder) { this.isBuilder = builder; }
    public boolean isAnnotation() { return isAnnotation; }
    public void setAnnotation(boolean annotation) { this.isAnnotation = annotation; }

    // ============ 字段布局 ============

    /** 计算字段布局（含继承字段）。在类注册时调用一次。 */
    public void computeFieldLayout(List<String> ownFieldNames) {
        fieldLayout = new HashMap<>();
        if (superclass != null && superclass.fieldLayout != null) {
            fieldLayout.putAll(superclass.fieldLayout);
        }
        for (String name : ownFieldNames) {
            if (!fieldLayout.containsKey(name)) {
                fieldLayout.put(name, fieldLayout.size());
            }
        }
        fieldNames = new String[fieldLayout.size()];
        for (Map.Entry<String, Integer> e : fieldLayout.entrySet()) {
            fieldNames[e.getValue()] = e.getKey();
        }
    }

    /** 获取字段索引，-1 表示未知字段 */
    public int getFieldIndex(String name) {
        if (fieldLayout == null) return -1;
        Integer idx = fieldLayout.get(name);
        return idx != null ? idx : -1;
    }

    /** 获取字段总数 */
    public int getFieldCount() {
        return fieldLayout != null ? fieldLayout.size() : 0;
    }

    /** 获取有序字段名数组 */
    public String[] getFieldNames() {
        return fieldNames;
    }

    // ============ 继承检查 ============

    public boolean isSubclassOf(NovaClass other) {
        NovaClass current = this;
        while (current != null) {
            if (current == other) {
                return true;
            }
            current = current.superclass;
        }
        return false;
    }

    // ============ 抽象类检查 ============

    /**
     * 检查是否为抽象类
     */
    public boolean isAbstract() {
        return isAbstractFlag;
    }

    public void setAbstract(boolean isAbstract) {
        this.isAbstractFlag = isAbstract;
    }

    public boolean isSealed() { return isSealedFlag; }

    /** sealed class 隐式 abstract（不可直接实例化） */
    public void setSealed(boolean sealed) {
        this.isSealedFlag = sealed;
        if (sealed) this.isAbstractFlag = true;
    }

    public boolean isInstantiationValidated() { return instantiationValidated; }
    public void markInstantiationValidated() { this.instantiationValidated = true; }

    /**
     * 获取所有未实现的抽象方法
     */
    public List<String> getUnimplementedMethods() {
        List<String> unimplemented = new ArrayList<String>();

        // 检查 Nova 接口的抽象方法
        for (NovaInterface iface : interfaces) {
            for (String abstractMethod : iface.getAllAbstractMethods()) {
                if (findCallableMethod(abstractMethod) == null &&
                    findInterfaceDefaultMethod(abstractMethod) == null) {
                    unimplemented.add(abstractMethod);
                }
            }
        }

        // 检查 Java 接口的抽象方法
        for (Class<?> javaIface : javaInterfaces) {
            for (Method m : javaIface.getMethods()) {
                if (!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                if (m.getDeclaringClass() == Object.class) continue;
                // 跳过 Object 已提供实现的方法（如 Comparator.equals）
                try {
                    Object.class.getMethod(m.getName(), m.getParameterTypes());
                    continue;
                } catch (NoSuchMethodException e) {
                    // 非 Object 方法，需要检查
                }
                String methodName = m.getName();
                if (findCallableMethod(methodName) == null &&
                    findInterfaceDefaultMethod(methodName) == null &&
                    !unimplemented.contains(methodName)) {
                    unimplemented.add(methodName);
                }
            }
        }

        return unimplemented;
    }
}
