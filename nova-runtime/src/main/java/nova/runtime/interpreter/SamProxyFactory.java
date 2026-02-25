package nova.runtime.interpreter;
import nova.runtime.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * SAM (Single Abstract Method) 代理工厂：统一创建 NovaCallable → Java 函数式接口的代理对象。
 *
 * <p>用于 Java 互操作，将 Nova 函数/lambda 转换为 Java 函数式接口（如 Runnable, Consumer, Function 等）。
 */
public final class SamProxyFactory {

    private SamProxyFactory() {} // 工具类，禁止实例化

    /**
     * 创建 SAM 接口代理：将 NovaCallable 适配为 Java 函数式接口。
     */
    public static Object create(Class<?> interfaceClass, NovaCallable callable, Interpreter interpreter) {
        // 通用 NovaCallable → SAM 代理
        return Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[] { interfaceClass },
            createInvocationHandler(callable, interpreter)
        );
    }

    /**
     * 创建 InvocationHandler：处理方法调用、参数转换、返回值转换。
     */
    private static InvocationHandler createInvocationHandler(NovaCallable callable, Interpreter interpreter) {
        return (proxy, method, methodArgs) -> {
            // 处理 Object 类的方法（toString/hashCode/equals）
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString":
                        return callable.toString();
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == methodArgs[0];
                    default:
                        return method.invoke(callable, methodArgs);
                }
            }

            // 转换参数：Java Object[] → List<NovaValue>
            List<NovaValue> novaArgs = new ArrayList<>();
            if (methodArgs != null) {
                for (Object arg : methodArgs) {
                    novaArgs.add(NovaValue.fromJava(arg));
                }
            }

            // 通过 executeSamCallback 安全执行（处理多线程状态隔离）
            NovaValue result = interpreter.executeSamCallback(callable, novaArgs);

            // 转换返回值：NovaValue → Java Object
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class) {
                return null;
            }
            // NovaObject 直接返回（避免有损转换为 HashMap）
            if (returnType == Object.class && result instanceof NovaObject) {
                return result;
            }
            return result.toJavaValue();
        };
    }
}
