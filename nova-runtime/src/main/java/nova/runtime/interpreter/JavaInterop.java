package nova.runtime.interpreter;
import nova.runtime.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 互操作模块
 *
 * <p>提供从 Nova 代码调用 Java 的能力，使用 MethodHandle 实现高效调用。</p>
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
                throw new NovaRuntimeException("Class not found: " + name);
            }
            return new NovaJavaClass(clazz);
        }));

        // Java.static(className, methodName, args...) - 调用静态方法
        javaNamespace.put(NovaString.of("static"), new NovaNativeFunction("Java.static", -1,
            (interp, args) -> {
                if (args.size() < 2) {
                    throw new NovaRuntimeException("Java.static requires class name and method name");
                }
                String className = args.get(0).asString();
                String methodName = args.get(1).asString();

                Class<?> clazz = resolveClass(className, policy);
                if (clazz == null) {
                    throw new NovaRuntimeException("Class not found: " + className);
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
                    return NovaValue.fromJava(result);
                } catch (Throwable e) {
                    throw new NovaRuntimeException("Failed to invoke static method " +
                            className + "." + methodName + ": " + e.getMessage(), e);
                }
            }));

        // Java.field(className, fieldName) - 获取静态字段值
        javaNamespace.put(NovaString.of("field"), NovaNativeFunction.create("Java.field", (className, fieldName) -> {
            String clsName = className.asString();
            String fldName = fieldName.asString();

            Class<?> clazz = resolveClass(clsName, policy);
            if (clazz == null) {
                throw new NovaRuntimeException("Class not found: " + clsName);
            }

            try {
                java.lang.invoke.MethodHandle getter = cache.findStaticGetter(clazz, fldName);
                if (getter == null) {
                    throw new NovaRuntimeException("Static field not found: " + clsName + "." + fldName);
                }
                Object value = getter.invoke();
                return NovaValue.fromJava(value);
            } catch (NovaRuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new NovaRuntimeException("Cannot access static field " +
                        clsName + "." + fldName + ": " + e.getMessage(), e);
            }
        }));

        // Java.new(className, args...) - 创建 Java 实例
        javaNamespace.put(NovaString.of("new"), new NovaNativeFunction("Java.new", -1,
            (interp, args) -> {
                if (args.isEmpty()) {
                    throw new NovaRuntimeException("Java.new requires class name");
                }
                String className = args.get(0).asString();

                Class<?> clazz = resolveClass(className, policy);
                if (clazz == null) {
                    throw new NovaRuntimeException("Class not found: " + className);
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
                    throw new NovaRuntimeException("Failed to create instance of " +
                            className + ": " + e.getMessage(), e);
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
            return Class.forName(className);
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
                return Class.forName(pkg + className);
            } catch (ClassNotFoundException e) {
                // 继续尝试
            }
        }

        return null;
    }

    /**
     * 包装 Java 类，使其可以作为构造函数调用
     */
    public static class NovaJavaClass extends NovaValue implements NovaCallable {
        private final Class<?> javaClass;

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

        @Override
        public NovaValue call(Interpreter interpreter, List<NovaValue> args) {
            // 类级安全检查
            if (interpreter != null) {
                NovaSecurityPolicy policy = interpreter.getSecurityPolicy();
                if (!policy.isClassAllowed(javaClass.getName())) {
                    throw NovaSecurityPolicy.denied(
                            "Cannot instantiate Java class: " + javaClass.getName());
                }
            }
            try {
                Object[] javaArgs = new Object[args.size()];
                for (int i = 0; i < args.size(); i++) {
                    javaArgs[i] = args.get(i).toJavaValue();
                }

                Object instance = cache.newInstance(javaClass, javaArgs);
                return new NovaExternalObject(instance);
            } catch (Throwable e) {
                throw new NovaRuntimeException("Failed to create instance of " +
                        javaClass.getName() + ": " + e.getMessage(), e);
            }
        }

        @Override
        public int getArity() {
            return -1;  // 可变参数
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
            // 方法级安全检查
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

                // SAM 自动转换
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
                return NovaValue.fromJava(result);
            } catch (Throwable e) {
                throw new NovaRuntimeException("Failed to invoke static method " +
                        javaClass.getName() + "." + methodName + ": " + e.getMessage(), e);
            }
        }

        /**
         * 获取静态字段或静态方法
         */
        public NovaValue getStaticField(String memberName) {
            // 先尝试获取静态字段（通过 MethodHandle 缓存）
            try {
                java.lang.invoke.MethodHandle getter = cache.findStaticGetter(javaClass, memberName);
                if (getter != null) {
                    Object value = getter.invoke();
                    return NovaValue.fromJava(value);
                }
            } catch (Throwable e) {
                // 获取失败，继续尝试静态方法
            }

            // 返回绑定的静态方法
            return getBoundStaticMethod(memberName);
        }

        /**
         * 获取绑定的静态方法
         */
        public NovaNativeFunction getBoundStaticMethod(String methodName) {
            return new NovaNativeFunction(methodName, -1, (interp, args) -> {
                return invokeStatic(methodName, args, interp);
            });
        }

        @Override
        public String toString() {
            return "class " + javaClass.getName();
        }
    }
}
