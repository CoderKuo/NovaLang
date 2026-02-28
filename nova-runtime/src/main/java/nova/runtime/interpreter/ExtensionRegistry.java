package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.*;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.HirExpr;
import java.util.*;

/**
 * 扩展函数和扩展属性注册表。
 */
final class ExtensionRegistry {

    /** 扩展方法注册表（Java Class 类型） */
    private final Map<Class<?>, Map<String, NovaCallable>> extensionMethods = new HashMap<>();

    /** Nova 扩展函数注册表（Nova 类型名 -> 方法名 -> 函数） */
    final Map<String, Map<String, NovaCallable>> novaExtensions = new HashMap<>();

    /** Nova 扩展属性注册表（Nova 类型名 -> 属性名 -> getter 表达式） */
    private final Map<String, Map<String, ExtensionProperty>> novaExtensionProperties = new HashMap<>();

    /** HIR 扩展属性：类型名 → { 属性名 → (Expression getter, Environment closure) } */
    final Map<String, Map<String, HirExtProp>> hirExtensionProperties = new HashMap<>();

    // ============ 扩展方法 ============

    /**
     * 注册扩展方法
     */
    void registerExtension(Class<?> targetType, String methodName, NovaCallable method) {
        extensionMethods.computeIfAbsent(targetType, k -> new HashMap<>())
                .put(methodName, method);
    }

    /**
     * 按精确类型和方法名查找已注册的扩展方法（不做继承链回退）。
     */
    NovaCallable getExtension(Class<?> targetType, String methodName) {
        Map<String, NovaCallable> methods = extensionMethods.get(targetType);
        return methods != null ? methods.get(methodName) : null;
    }

    /**
     * 查找扩展方法
     */
    NovaCallable findExtension(NovaValue receiver, String methodName) {
        // 1. 首先查找 Nova 定义的扩展函数
        NovaCallable novaExt = findNovaExtension(receiver, methodName);
        if (novaExt != null) {
            return novaExt;
        }

        // 2. 查找 Java API 注册的扩展方法
        Object javaValue = receiver.toJavaValue();
        if (javaValue == null) return null;

        // 2a. 精确类型直接查找
        Map<String, NovaCallable> exact = extensionMethods.get(javaValue.getClass());
        if (exact != null) {
            NovaCallable method = exact.get(methodName);
            if (method != null) return method;
        }

        // 2b. 继承/接口 fallback
        for (Map.Entry<Class<?>, Map<String, NovaCallable>> entry : extensionMethods.entrySet()) {
            Class<?> type = entry.getKey();
            if (type != javaValue.getClass() && type.isInstance(javaValue)) {
                NovaCallable method = entry.getValue().get(methodName);
                if (method != null) return method;
            }
        }
        return null;
    }

    // ============ Nova 扩展函数 ============

    /**
     * 注册 Nova 扩展函数
     */
    void registerNovaExtension(String typeName, String methodName, NovaCallable function) {
        novaExtensions.computeIfAbsent(typeName, k -> new HashMap<>())
                .put(methodName, function);
    }

    /**
     * 查找 Nova 扩展函数
     */
    NovaCallable findNovaExtension(NovaValue receiver, String methodName) {
        String typeName = receiver.getNovaTypeName();
        if (typeName != null) {
            Map<String, NovaCallable> methods = novaExtensions.get(typeName);
            if (methods != null) {
                NovaCallable func = methods.get(methodName);
                if (func != null) {
                    return func;
                }
            }
        }

        // 对于 NovaObject，也检查其类名
        if (receiver instanceof NovaObject) {
            String className = ((NovaObject) receiver).getNovaClass().getName();
            Map<String, NovaCallable> methods = novaExtensions.get(className);
            if (methods != null) {
                NovaCallable func = methods.get(methodName);
                if (func != null) {
                    return func;
                }
            }
        }

        // 检查 Any 类型的扩展（对所有类型生效）
        Map<String, NovaCallable> anyMethods = novaExtensions.get("Any");
        if (anyMethods != null) {
            NovaCallable func = anyMethods.get(methodName);
            if (func != null) {
                return func;
            }
        }

        return null;
    }

    // ============ Nova 扩展属性 ============

    /**
     * 注册 Nova 扩展属性
     */
    void registerNovaExtensionProperty(String typeName, String propertyName,
                                        Expression getter, Environment closure) {
        novaExtensionProperties.computeIfAbsent(typeName, k -> new HashMap<>())
                .put(propertyName, new ExtensionProperty(getter, closure));
    }

    /**
     * 注册 MIR 路径的扩展属性（callable-based getter）
     */
    void registerExtensionProperty(String typeName, String propertyName, NovaCallable getter) {
        novaExtensionProperties.computeIfAbsent(typeName, k -> new HashMap<>())
                .put(propertyName, new ExtensionProperty(getter));
    }

    /**
     * 查找 Nova 扩展属性
     */
    ExtensionProperty findNovaExtensionProperty(NovaValue receiver, String propertyName) {
        String typeName = receiver.getNovaTypeName();
        if (typeName != null) {
            Map<String, ExtensionProperty> props = novaExtensionProperties.get(typeName);
            if (props != null) {
                ExtensionProperty prop = props.get(propertyName);
                if (prop != null) {
                    return prop;
                }
            }
        }

        // 对于 NovaObject，也检查其类名
        if (receiver instanceof NovaObject) {
            String className = ((NovaObject) receiver).getNovaClass().getName();
            Map<String, ExtensionProperty> props = novaExtensionProperties.get(className);
            if (props != null) {
                ExtensionProperty prop = props.get(propertyName);
                if (prop != null) {
                    return prop;
                }
            }
        }

        // 检查 Any 类型的扩展属性
        Map<String, ExtensionProperty> anyProps = novaExtensionProperties.get("Any");
        if (anyProps != null) {
            ExtensionProperty prop = anyProps.get(propertyName);
            if (prop != null) {
                return prop;
            }
        }

        return null;
    }

    /**
     * 执行扩展属性 getter
     */
    NovaValue executeExtensionPropertyGetter(ExtensionProperty prop, NovaValue receiver, Interpreter interp) {
        // MIR 路径: 使用 callable getter
        if (prop.callableGetter != null) {
            return prop.callableGetter.call(interp, java.util.Collections.singletonList(receiver));
        }
        Environment getterEnv = new Environment(prop.closure);
        getterEnv.defineVal("this", receiver);

        return interp.withEnvironment(getterEnv, () -> interp.evaluate(prop.getter));
    }

    // ============ HIR 扩展属性 ============

    /**
     * 查找 HIR 扩展属性
     */
    HirExtProp findHirExtensionProperty(NovaValue receiver, String propertyName) {
        String typeName = receiver.getNovaTypeName();
        if (typeName != null) {
            Map<String, HirExtProp> props = hirExtensionProperties.get(typeName);
            if (props != null && props.containsKey(propertyName)) return props.get(propertyName);
        }
        // Any type wildcard
        Map<String, HirExtProp> anyProps = hirExtensionProperties.get("Any");
        if (anyProps != null && anyProps.containsKey(propertyName)) return anyProps.get(propertyName);
        return null;
    }

    /**
     * 执行 HIR 扩展属性 getter
     */
    NovaValue executeHirExtensionPropertyGetter(HirExtProp prop, NovaValue receiver, Interpreter interp) {
        Environment getterEnv = new Environment(prop.closure);
        getterEnv.defineVal("this", receiver);
        return interp.withEnvironment(getterEnv, () ->
            interp.evaluateHir(prop.getter));
    }

    // ============ 内部类 ============

    /**
     * 扩展属性信息
     */
    static class ExtensionProperty {
        final Expression getter;
        final Environment closure;
        final NovaCallable callableGetter; // MIR 路径: 可调用 getter（接收 receiver 作为参数）

        ExtensionProperty(Expression getter, Environment closure) {
            this.getter = getter;
            this.closure = closure;
            this.callableGetter = null;
        }

        ExtensionProperty(NovaCallable callableGetter) {
            this.getter = null;
            this.closure = null;
            this.callableGetter = callableGetter;
        }
    }

    /**
     * HIR 扩展属性信息
     */
    static final class HirExtProp {
        final Expression getter;
        final Environment closure;
        HirExtProp(Expression getter, Environment closure) {
            this.getter = getter;
            this.closure = closure;
        }
    }
}
