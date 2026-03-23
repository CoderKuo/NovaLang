package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;
import com.novalang.runtime.types.*;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.compiler.hirtype.*;

import java.util.*;

/**
 * Interpreter helper: function/lambda/constructor execution logic.
 *
 * <p>Package-private class that holds a reference to the owning Interpreter
 * and delegates field/method access through it.</p>
 */
final class FunctionExecutor {

    final Interpreter interp;

    FunctionExecutor(Interpreter interp) {
        this.interp = interp;
    }

    /**
     * Execute a bound method.
     */
    NovaValue executeBoundMethod(NovaBoundMethod bound, List<NovaValue> args,
                                  Map<String, NovaValue> namedArgs) {
        int maxDepth = interp.getSecurityPolicy().getMaxRecursionDepth();
        if (maxDepth > 0 && interp.callDepth >= maxDepth) {
            throw NovaSecurityPolicy.denied("Maximum recursion depth exceeded (" + maxDepth + ")");
        }
        NovaValue receiver = bound.getReceiver();
        NovaCallable method = bound.getMethod();

        // Other callable types (NovaNativeFunction, extension methods, etc.)
        // Extension methods need the receiver as the first argument
        List<NovaValue> argsWithReceiver = new ArrayList<NovaValue>();
        argsWithReceiver.add(receiver);
        argsWithReceiver.addAll(args);
        return method.call(interp, argsWithReceiver);
    }

    // ============ Instantiation ============

    private void validateInstantiation(NovaClass novaClass) {
        if (!novaClass.isInstantiationValidated()) {
            if (novaClass.isAnnotation()) {
                throw new NovaRuntimeException("Cannot instantiate annotation class: " + novaClass.getName());
            }
            if (novaClass.isAbstract()) {
                throw new NovaRuntimeException("Cannot instantiate abstract class: " + novaClass.getName());
            }
            List<String> unimplemented = novaClass.getUnimplementedMethods();
            if (!unimplemented.isEmpty()) {
                throw new NovaRuntimeException("Class " + novaClass.getName() +
                    " must implement abstract methods: " + String.join(", ", unimplemented));
            }
            novaClass.markInstantiationValidated();
        }
    }

    NovaValue instantiateMirFast(NovaClass novaClass, MirCallable ctor, MirFrame frame, int[] ops) {
        validateInstantiation(novaClass);
        if (novaClass.hasJavaSuperTypes()) {
            NovaValue[] ctorArgs = new NovaValue[ops.length];
            for (int i = 0; i < ops.length; i++) ctorArgs[i] = frame.get(ops[i]);
            return instantiate(novaClass, Arrays.asList(ctorArgs), null);
        }
        NovaObject instance = new NovaObject(novaClass);
        initializeInstanceFields(instance, novaClass);
        if (ctor != null) {
            switch (ops.length) {
                case 0:
                    ctor.callBoundDirect0(interp, instance);
                    break;
                case 1:
                    ctor.callBoundDirect1(interp, instance, frame.get(ops[0]));
                    break;
                case 2:
                    ctor.callBoundDirect2(interp, instance, frame.get(ops[0]), frame.get(ops[1]));
                    break;
                default:
                    NovaValue[] allArgs = new NovaValue[ops.length + 1];
                    allArgs[0] = instance;
                    for (int i = 0; i < ops.length; i++) allArgs[i + 1] = frame.get(ops[i]);
                    ctor.callDirect(interp, allArgs);
                    break;
            }
        }
        return instance;
    }

    /**
     * Instantiate a class.
     */
    NovaValue instantiate(NovaClass novaClass, List<NovaValue> args,
                           Map<String, NovaValue> namedArgs) {
        // First instantiation validation is cached; subsequent calls skip all checks
        validateInstantiation(novaClass);

        NovaObject instance = new NovaObject(novaClass);
        initializeInstanceFields(instance, novaClass);
        if (novaClass.hasJavaSuperTypes()) {
            List<NovaValue> superArgs = novaClass.getJavaSuperConstructorArgs();
            if (superArgs == null || superArgs.isEmpty()) {
                superArgs = args;
            }
            Object delegate = interp.createJavaDelegate(instance, novaClass, superArgs);
            if (delegate != null) instance.setJavaDelegate(delegate);
        }
        // If there are HIR constructors, match by parameter count
        List<NovaCallable> hirCtors = novaClass.getHirConstructors();
        if (!hirCtors.isEmpty()) {
            int argCount = args.size() + (namedArgs != null ? namedArgs.size() : 0);
            NovaCallable matched = null;
            // Try exact parameter count match
            for (NovaCallable c : hirCtors) {
                int arity = c.getArity();
                if (arity == argCount || arity == -1) {
                    matched = c;
                    break;
                }
            }
            // Try constructors with default values (arity may be larger)
            if (matched == null) {
                for (NovaCallable c : hirCtors) {
                    // MIR 路径：MirCallable 的默认参数通过 null 检查处理
                    // argCount < arity 时可能是默认参数情况，用 null 补齐
                    if (c instanceof MirCallable && argCount < c.getArity() && !((MirCallable) c).getFunction().hasDelegation()) {
                        matched = c;
                        break;
                    }
                }
            }
            if (matched == null) {
                if (!args.isEmpty() || (namedArgs != null && !namedArgs.isEmpty())) {
                    throw new NovaRuntimeException("No matching constructor for " + novaClass.getName()
                        + " with " + args.size() + " argument(s)");
                }
            }
            if (matched != null) {
                // MIR 路径默认参数：不足的参数用 null 补齐（MIR 函数体中有 null 检查 + 默认值赋值）
                List<NovaValue> callArgs = args;
                if (matched instanceof MirCallable && args.size() < matched.getArity()) {
                    callArgs = new java.util.ArrayList<>(args);
                    while (callArgs.size() < matched.getArity()) {
                        callArgs.add(com.novalang.runtime.NovaNull.NULL);
                    }
                }
                NovaBoundMethod bound = new NovaBoundMethod(instance, matched);
                executeBoundMethod(bound, callArgs, namedArgs);
            }
        } else {
            // No explicit constructor: no args accepted
            // 例外：有 Java 超类的匿名类，参数已在上方被传给 createJavaDelegate()
            if ((!args.isEmpty() || (namedArgs != null && !namedArgs.isEmpty()))
                    && !novaClass.hasJavaSuperTypes()) {
                throw new NovaRuntimeException("No constructor for " + novaClass.getName()
                    + " accepts " + args.size() + " argument(s)");
            }
            // Compatibility: use single constructorCallable (no-arg)
            NovaCallable ctor = novaClass.getConstructorCallable();
            if (ctor != null) {
                NovaBoundMethod bound = new NovaBoundMethod(instance, ctor);
                executeBoundMethod(bound, args, namedArgs);
            }
        }
        return instance;
    }

    /**
     * Initialize instance fields. MIR 路径：构造器处理所有字段初始化。
     */
    void initializeInstanceFields(NovaObject instance, NovaClass cls) {
        // MIR 路径：构造器总是存在，字段初始化在构造器中完成
        if (!cls.getHirConstructors().isEmpty()) return;
    }

    String getHirTypeName(HirType type) {
        if (type instanceof PrimitiveType) {
            switch (((PrimitiveType) type).getKind()) {
                case INT: return "Int";
                case LONG: return "Long";
                case FLOAT: return "Float";
                case DOUBLE: return "Double";
                case BOOLEAN: return "Boolean";
                case CHAR: return "Char";
                default: return null;
            }
        }
        if (type instanceof ClassType) {
            String name = ((ClassType) type).getName();
            // Convert fully-qualified name to simple name
            int lastSlash = name.lastIndexOf('/');
            return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
        }
        return null;
    }

}
