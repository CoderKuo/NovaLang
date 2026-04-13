package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;
import com.novalang.runtime.NovaException.ErrorKind;
import com.novalang.runtime.types.Environment;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 互操作模块
 *
 * <p>提供从 Nova 代码调用 Java 的能力，使用 MethodHandle 实现高效调用。</p>
 * <p>支持通过 {@link #setScriptClassLoader} 设置脚本级 ClassLoader，
 * {@code javaClass()} 会优先在该 ClassLoader 中查找类。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * // 获取 Java 类
 * val ArrayList = Java.type("java.util.ArrayList")
 * val list = ArrayList()
 * list.add("hello")
 *
 * // 调用静态方法
 * val currentTime = Java.static("java.lang.System", "currentTimeMillis")
 *
 * // 获取静态字段
 * val PI = Java.field("java.lang.Math", "PI")
 * </pre>
 */
public final class JavaInterop {

    /** 脚本级 ClassLoader（ThreadLocal，线程安全） */
    private static final ThreadLocal<ClassLoader> SCRIPT_CLASS_LOADER = new ThreadLocal<>();

    /** 设置当前线程的脚本 ClassLoader（脚本执行前调用） */
    public static void setScriptClassLoader(ClassLoader cl) {
        if (cl != null) {
            SCRIPT_CLASS_LOADER.set(cl);
        } else {
            SCRIPT_CLASS_LOADER.remove();
        }
    }

    /** 获取当前线程的脚本 ClassLoader */
    public static ClassLoader getScriptClassLoader() {
        return SCRIPT_CLASS_LOADER.get();
    }

    /** 通过脚本 ClassLoader 或默认 ClassLoader 加载类 */
    public static Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassLoader cl = SCRIPT_CLASS_LOADER.get();
        if (cl != null) {
            try {
                return Class.forName(name, true, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            // 内部类回退：逐级尝试将最后的 . 替换为 $
            // java.util.Map.Entry → java.util.Map$Entry
            // java.util.concurrent.AbstractExecutorService.RunnableAdapter → ...
            String attempt = name;
            int lastDot;
            while ((lastDot = attempt.lastIndexOf('.')) > 0) {
                attempt = attempt.substring(0, lastDot) + "$" + attempt.substring(lastDot + 1);
                try {
                    Class<?> cls = cl != null ? Class.forName(attempt, true, cl) : Class.forName(attempt);
                    return cls;
                } catch (ClassNotFoundException ignored) {}
            }
            throw e; // 全部失败，抛原始异常
        }
    }

    private static final MethodHandleCache cache = MethodHandleCache.getInstance();

    private JavaInterop() {}

    /**
     * 注册 Java 互操作函数到环境（默认无限制策略）
     */
    public static void register(Environment env) {
        register(env, NovaSecurityPolicy.unrestricted());
    }

    /**
     * 注册 Java 互操作函数到环境（带安全策略）
     */
    public static void register(Environment env, NovaSecurityPolicy policy) {
        // STRICT 模式下完全不注册 Java 互操作
        if (!policy.isJavaInteropAllowed()) {
            return;
        }

        // 创建 Java 命名空间对象
        NovaMap javaNamespace = new NovaMap();

        // Java.type(className) - 获取 Java 类作为可调用对象
        javaNamespace.put(NovaString.of("type"), NovaNativeFunction.create("Java.type", (className) -> {
            String name = className.asString();
            Class<?> clazz = resolveClass(name, policy);
            if (clazz == null) {
                throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "找不到类: " + name, "请检查类名是否正确");
            }
            return new NovaJavaClass(clazz);
        }));

        // Java.static(className, methodName, args...) - 调用静态方法
        javaNamespace.put(NovaString.of("static"), new NovaNativeFunction("Java.static", -1,
            (interp, args) -> {
                if (args.size() < 2) {
                    throw new NovaRuntimeException(ErrorKind.ARGUMENT_MISMATCH, "Java.static 需要类名和方法名", null);
                }
                String className = args.get(0).asString();
                String methodName = args.get(1).asString();

                Class<?> clazz = resolveClass(className, policy);
                if (clazz == null) {
                    throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "找不到类: " + className, "请检查类名是否正确");
                }

                // 方法黑名单检查
                if (!policy.isMethodAllowed(clazz.getName(), methodName)) {
                    throw NovaSecurityPolicy.denied(
                            "Cannot call method '" + methodName + "' on " + clazz.getName());
                }

                // 准备参数
                Object[] javaArgs = new Object[args.size() - 2];
                for (int i = 2; i < args.size(); i++) {
                    javaArgs[i - 2] = args.get(i).toJavaValue();
                }

                try {
                    Object result = cache.invokeStatic(clazz, methodName, javaArgs);
                    return AbstractNovaValue.fromJava(result);
                } catch (Throwable e) {
                    throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "调用静态方法 " + className + "." + methodName + " 失败: " + e.getMessage(), null, e);
                }
            }));

        // Java.field(className, fieldName) - 获取静态字段值
        javaNamespace.put(NovaString.of("field"), NovaNativeFunction.create("Java.field", (className, fieldName) -> {
            String clsName = className.asString();
            String fldName = fieldName.asString();

            Class<?> clazz = resolveClass(clsName, policy);
            if (clazz == null) {
                throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "找不到类: " + clsName, "请检查类名是否正确");
            }

            try {
                java.lang.invoke.MethodHandle getter = cache.findStaticGetter(clazz, fldName);
                if (getter == null) {
                    throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "找不到静态字段: " + clsName + "." + fldName, null);
                }
                Object value = getter.invoke();
                return AbstractNovaValue.fromJava(value);
            } catch (NovaRuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "无法访问静态字段 " + clsName + "." + fldName + ": " + e.getMessage(), null, e);
            }
        }));

        // Java.new(className, args...) - 创建 Java 实例
        javaNamespace.put(NovaString.of("new"), new NovaNativeFunction("Java.new", -1,
            (interp, args) -> {
                if (args.isEmpty()) {
                    throw new NovaRuntimeException(ErrorKind.ARGUMENT_MISMATCH, "Java.new 需要类名参数", null);
                }
                String className = args.get(0).asString();

                Class<?> clazz = resolveClass(className, policy);
                if (clazz == null) {
                    throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "找不到类: " + className, "请检查类名是否正确");
                }

                // 准备构造器参数
                Object[] javaArgs = new Object[args.size() - 1];
                for (int i = 1; i < args.size(); i++) {
                    javaArgs[i - 1] = args.get(i).toJavaValue();
                }

                try {
                    Object instance = cache.newInstance(clazz, javaArgs);
                    return new NovaExternalObject(instance);
                } catch (Throwable e) {
                    throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "创建 " + className + " 实例失败: " + e.getMessage(), null, e);
                }
            }));

        // Java.isInstance(obj, className) - 检查对象是否是指定类的实例
        javaNamespace.put(NovaString.of("isInstance"), NovaNativeFunction.create("Java.isInstance", (obj, className) -> {
            String clsName = className.asString();
            Class<?> clazz = resolveClassUnchecked(clsName);
            if (clazz == null) {
                return NovaBoolean.FALSE;
            }

            Object javaObj = obj.toJavaValue();
            return NovaBoolean.of(javaObj != null && clazz.isInstance(javaObj));
        }));

        // Java.class(obj) - 获取对象的 Java 类名
        javaNamespace.put(NovaString.of("class"), NovaNativeFunction.create("Java.class", (obj) -> {
            Object javaObj = obj.toJavaValue();
            if (javaObj == null) {
                return NovaString.of("null");
            }
            return NovaString.of(javaObj.getClass().getName());
        }));

        // 注册 Java 命名空间
        env.defineVal("Java", javaNamespace);
    }

    /**
     * 解析类名为 Class 对象（带安全检查）
     */
    private static Class<?> resolveClass(String className, NovaSecurityPolicy policy) {
        Class<?> clazz = resolveClassUnchecked(className);
        if (clazz != null && !policy.isClassAllowed(clazz.getName())) {
            throw NovaSecurityPolicy.denied("Cannot access class: " + clazz.getName());
        }
        return clazz;
    }

    /**
     * 解析类名为 Class 对象（无安全检查，内部使用）
     */
    private static Class<?> resolveClassUnchecked(String className) {
        // 直接尝试加载
        try {
            return loadClass(className);
        } catch (ClassNotFoundException e) {
            // 继续尝试其他包
        }

        // 尝试常用包
        String[] packages = {
            "java.lang.",
            "java.util.",
            "java.io.",
            "java.nio.",
            "java.math.",
            "java.time.",
            "java.util.function.",
            "java.util.stream.",
            "java.util.concurrent."
        };

        for (String pkg : packages) {
            try {
                return loadClass(pkg + className);
            } catch (ClassNotFoundException e) {
                // 继续尝试
            }
        }

        return null;
    }

    /**
     * 包装 Java 类，使其可以作为构造函数调用
     */
    /**
     * 编译路径的 javaClass() 静态入口（StdlibRegistry 调用）。
     */
    public static Object javaClassImpl(Object classNameObj) {
        String name = classNameObj instanceof NovaValue
                ? ((NovaValue) classNameObj).asString()
                : String.valueOf(classNameObj);
        NovaSecurityPolicy policy = NovaSecurityPolicy.current();
        if (policy != null && !policy.isClassAllowed(name)) {
            throw NovaSecurityPolicy.denied("Cannot access Java class: " + name);
        }
        try {
            return new NovaJavaClass(loadClass(name));
        } catch (ClassNotFoundException e) {
            throw new NovaException("Java class not found: " + name);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static class NovaJavaClass extends AbstractNovaValue implements com.novalang.runtime.NovaCallable,
            com.novalang.runtime.Function0 {
        private final Class<?> javaClass;
        /** 绑定静态方法缓存：方法名 → NovaNativeFunction（避免热循环重复分配） */
        private final java.util.concurrent.ConcurrentHashMap<String, NovaNativeFunction> boundMethodCache
                = new java.util.concurrent.ConcurrentHashMap<>();

        public NovaJavaClass(Class<?> javaClass) {
            this.javaClass = javaClass;
        }

        public Class<?> getJavaClass() {
            return javaClass;
        }

        @Override
        public String getName() {
            return javaClass.getSimpleName();
        }

        @Override
        public String getTypeName() {
            return "JavaClass:" + javaClass.getSimpleName();
        }

        @Override
        public Object toJavaValue() {
            return javaClass;
        }

        private NovaExternalObject newInstance(Object[] javaArgs) {
            try {
                return new NovaExternalObject(cache.newInstance(javaClass, javaArgs));
            } catch (Throwable e) {
                throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "创建 " + javaClass.getName() + " 实例失败: " + e.getMessage(), null, e);
            }
        }

        @Override
        public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
            Interpreter interpreter = ctx != null ? (Interpreter) ctx : null;
            if (interpreter != null) {
                NovaSecurityPolicy policy = interpreter.getSecurityPolicy();
                if (!policy.isClassAllowed(javaClass.getName())) {
                    throw NovaSecurityPolicy.denied(
                            "Cannot instantiate Java class: " + javaClass.getName());
                }
            }
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                javaArgs[i] = args.get(i).toJavaValue();
            }
            return newInstance(javaArgs);
        }

        @Override
        public NovaValue dynamicInvoke(NovaValue[] args) {
            Object[] javaArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                javaArgs[i] = args[i].toJavaValue();
            }
            return newInstance(javaArgs);
        }

        @Override
        public Object invoke() {
            // Function0: 零参构造快速路径（跳过数组分配）
            return newInstance(EMPTY_JAVA_ARGS).toJavaValue();
        }

        @Override
        public boolean isCallable() {
            return true;
        }

        @Override
        public int getArity() {
            return -1;
        }

        /**
         * 调用静态方法
         */
        public NovaValue invokeStatic(String methodName, List<NovaValue> args) {
            return invokeStatic(methodName, args, null);
        }

        /**
         * 调用静态方法（带解释器引用，支持 SAM 自动转换）
         */
        public NovaValue invokeStatic(String methodName, List<NovaValue> args, Interpreter interpreter) {
            if (interpreter != null) {
                NovaSecurityPolicy policy = interpreter.getSecurityPolicy();
                if (!policy.isMethodAllowed(javaClass.getName(), methodName)) {
                    throw NovaSecurityPolicy.denied(
                            "Cannot call method '" + methodName + "' on " + javaClass.getName());
                }
            }
            try {
                Object[] javaArgs = new Object[args.size()];
                for (int i = 0; i < args.size(); i++) {
                    javaArgs[i] = args.get(i).toJavaValue();
                }

                if (interpreter != null) {
                    Class<?>[] argTypes = new Class<?>[javaArgs.length];
                    for (int i = 0; i < javaArgs.length; i++) {
                        argTypes[i] = javaArgs[i] != null ? javaArgs[i].getClass() : Object.class;
                    }
                    Class<?>[] paramTypes = cache.getMethodParamTypes(javaClass, methodName, argTypes, true);
                    if (paramTypes != null) {
                        for (int i = 0; i < javaArgs.length && i < paramTypes.length; i++) {
                            if (args.get(i) instanceof NovaCallable && cache.isSamInterface(paramTypes[i])) {
                                javaArgs[i] = SamProxyFactory.create(paramTypes[i], (NovaCallable) args.get(i), interpreter);
                            }
                        }
                    }
                }

                Object result = cache.invokeStatic(javaClass, methodName, javaArgs);
                return AbstractNovaValue.fromJava(result);
            } catch (Throwable e) {
                throw new NovaRuntimeException(ErrorKind.JAVA_INTEROP, "调用静态方法 " + javaClass.getName() + "." + methodName + " 失败: " + e.getMessage(), null, e);
            }
        }

        public NovaValue getStaticField(String memberName) {
            return resolveMember(memberName);
        }

        public NovaNativeFunction getBoundStaticMethod(String methodName) {
            return boundMethodCache.computeIfAbsent(methodName,
                    name -> new NovaNativeFunction(name, -1, (interp, args) ->
                            invokeStatic(name, args, (Interpreter) interp)));
        }

        /** 综合成员缓存：字段值 + 绑定方法，避免热循环重复解析 */
        private final java.util.concurrent.ConcurrentHashMap<String, NovaValue> memberCache
                = new java.util.concurrent.ConcurrentHashMap<>();
        private static final NovaValue MEMBER_MISS = new AbstractNovaValue() {
            @Override public String getTypeName() { return "$miss"; }
            @Override public Object toJavaValue() { return null; }
        };

        @Override
        public NovaValue resolveMember(String memberName) {
            NovaValue cached = memberCache.get(memberName);
            if (cached == MEMBER_MISS) {
                // 已知不是字段，直接返回绑定方法
                return boundMethodCache.computeIfAbsent(memberName,
                        name -> new NovaNativeFunction(name, -1, (interp, args) ->
                                invokeStatic(name, args, (Interpreter) interp)));
            }
            if (cached != null) return cached;

            // 首次解析：先尝试静态字段
            try {
                java.lang.invoke.MethodHandle getter = cache.findStaticGetter(javaClass, memberName);
                if (getter != null) {
                    NovaValue val = AbstractNovaValue.fromJava(getter.invoke());
                    memberCache.put(memberName, val);
                    return val;
                }
            } catch (Throwable e) {
                // 不是字段
            }
            // 标记为非字段，后续直接走方法缓存
            memberCache.put(memberName, MEMBER_MISS);
            return boundMethodCache.computeIfAbsent(memberName,
                    name -> new NovaNativeFunction(name, -1, (interp, args) ->
                            invokeStatic(name, args, (Interpreter) interp)));
        }

        @Override
        public String toString() {
            return "class " + javaClass.getName();
        }
    }

    private static final Object[] EMPTY_JAVA_ARGS = new Object[0];
}
