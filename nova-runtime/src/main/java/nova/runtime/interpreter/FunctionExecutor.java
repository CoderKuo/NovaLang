package nova.runtime.interpreter;
import nova.runtime.*;
import nova.runtime.types.*;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
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
     * Execute a bound method (HIR + fallback).
     */
    NovaValue executeBoundMethod(NovaBoundMethod bound, List<NovaValue> args,
                                  Map<String, NovaValue> namedArgs) {
        int maxDepth = interp.getSecurityPolicy().getMaxRecursionDepth();
        if (maxDepth > 0 && interp.callDepth >= maxDepth) {
            throw NovaSecurityPolicy.denied("Maximum recursion depth exceeded (" + maxDepth + ")");
        }
        NovaValue receiver = bound.getReceiver();
        NovaCallable method = bound.getMethod();

        // HIR function
        if (method instanceof HirFunctionValue) {
            HirFunctionValue func = (HirFunctionValue) method;
            Environment funcEnv = new Environment(func.getClosure());
            funcEnv.defineValFast("this", receiver);

            HirFunction decl = func.getDeclaration();
            List<HirParam> params = decl.getParams();

            // Bind parameters to environment
            bindParams(funcEnv, params, args, namedArgs);

            // Non-delegating constructor: params are also set as instance fields
            if (decl.isConstructor() && !decl.hasDelegation()
                    && receiver instanceof NovaObject) {
                NovaObject instance = (NovaObject) receiver;
                for (HirParam param : params) {
                    instance.setField(param.getName(), funcEnv.tryGet(param.getName()));
                }
            }

            // Secondary constructor delegation: this(...)
            if (decl.isConstructor() && decl.hasDelegation()
                    && receiver instanceof NovaObject) {
                NovaObject instance = (NovaObject) receiver;
                NovaClass novaClass = instance.getNovaClass();
                Environment saved = interp.environment;
                interp.environment = funcEnv;
                List<NovaValue> delegateArgs = new ArrayList<>();
                try {
                    for (Expression darg : decl.getDelegationArgs()) {
                        delegateArgs.add(interp.evaluateHir(darg));
                    }
                } finally {
                    interp.environment = saved;
                }
                List<NovaCallable> hirCtors = novaClass.getHirConstructors();
                int delegateCount = delegateArgs.size();
                NovaCallable targetCtor = null;
                for (NovaCallable c : hirCtors) {
                    if (c == method) continue;
                    int arity = c.getArity();
                    if (arity == delegateCount || arity == -1) {
                        targetCtor = c;
                        break;
                    }
                }
                if (targetCtor == null && !hirCtors.isEmpty()) {
                    targetCtor = hirCtors.get(0);
                }
                if (targetCtor != null) {
                    NovaBoundMethod delegateBound = new NovaBoundMethod(instance, targetCtor);
                    executeBoundMethod(delegateBound, delegateArgs, null);
                }
                interp.callStack.push(NovaCallFrame.fromBoundMethod(bound, args));
                interp.callDepth++;
                try {
                    return runInEnv(funcEnv, decl.getBody());
                } finally {
                    interp.callDepth--;
                    interp.callStack.pop();
                }
            }

            // Bind reified type parameters
            if (interp.pendingHirTypeArgs != null && !decl.getTypeParams().isEmpty()) {
                List<String> typeParams = decl.getTypeParams();
                for (int i = 0; i < typeParams.size() && i < interp.pendingHirTypeArgs.size(); i++) {
                    HirType typeArg = interp.pendingHirTypeArgs.get(i);
                    String typeName = null;
                    if (typeArg instanceof ClassType) typeName = ((ClassType) typeArg).getName();
                    else if (typeArg instanceof UnresolvedType) typeName = ((UnresolvedType) typeArg).getName();
                    if (typeName != null) {
                        funcEnv.defineValFast("__reified_" + typeParams.get(i), NovaString.of(typeName));
                    }
                }
            }

            // Primary constructor: call Nova superclass constructor
            if (decl.isConstructor() && !decl.hasDelegation()
                    && receiver instanceof NovaObject) {
                NovaObject instance = (NovaObject) receiver;
                NovaClass novaClass = instance.getNovaClass();
                boolean isOwnConstructor = novaClass.getHirConstructors().contains(method);
                NovaClass superclass = isOwnConstructor ? novaClass.getSuperclass() : null;
                if (superclass != null) {
                    List<Expression> superArgExprs = interp.hirSuperCtorArgs.get(novaClass.getName());
                    if (superArgExprs != null && !superArgExprs.isEmpty()) {
                        Environment saved2 = interp.environment;
                        interp.environment = funcEnv;
                        List<NovaValue> superArgs = new ArrayList<>();
                        try {
                            for (Expression sarg : superArgExprs) {
                                superArgs.add(interp.evaluateHir(sarg));
                            }
                        } finally {
                            interp.environment = saved2;
                        }
                        List<NovaCallable> superCtors = superclass.getHirConstructors();
                        if (!superCtors.isEmpty()) {
                            NovaBoundMethod superBound = new NovaBoundMethod(instance, superCtors.get(0));
                            executeBoundMethod(superBound, superArgs, null);
                        }
                    }
                }
            }

            // Primary constructor: execute property initialization
            if (decl.isConstructor() && receiver instanceof NovaObject) {
                NovaObject instance = (NovaObject) receiver;
                NovaClass novaClass = instance.getNovaClass();
                initHirProperties(novaClass, instance, funcEnv);
            }

            interp.callStack.push(NovaCallFrame.fromBoundMethod(bound, args));
            interp.callDepth++;
            try {
                return runInEnv(funcEnv, decl.getBody());
            } finally {
                interp.callDepth--;
                interp.callStack.pop();
            }
        }

        // HirLambdaValue: bind this to receiver (scope functions run/apply etc.)
        if (method instanceof HirLambdaValue) {
            HirLambdaValue lambda = (HirLambdaValue) method;
            Environment lambdaEnv = new Environment(lambda.getClosure());
            HirLambda expr = lambda.getExpression();
            List<HirParam> params = expr.getParams();
            // "this" at slot 0 (consistent with HirVariableResolver.resolveLambda slot layout)
            lambdaEnv.defineValFast("this", receiver);
            bindLambdaParams(lambdaEnv, params, args);

            interp.callStack.push(NovaCallFrame.fromBoundMethod(bound, args));
            interp.callDepth++;
            try {
                return runInEnv(lambdaEnv, expr.getBody());
            } finally {
                interp.callDepth--;
                interp.callStack.pop();
            }
        }

        // Other callable types (NovaNativeFunction, extension methods, etc.)
        // Extension methods need the receiver as the first argument
        List<NovaValue> argsWithReceiver = new ArrayList<NovaValue>();
        argsWithReceiver.add(receiver);
        argsWithReceiver.addAll(args);
        return method.call(interp, argsWithReceiver);
    }

    // ============ Instantiation ============

    /**
     * Instantiate a class.
     */
    NovaValue instantiate(NovaClass novaClass, List<NovaValue> args,
                           Map<String, NovaValue> namedArgs) {
        // First instantiation validation is cached; subsequent calls skip all checks
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
                    if (c instanceof HirFunctionValue) {
                        HirFunctionValue hfv = (HirFunctionValue) c;
                        int requiredCount = 0;
                        int totalCount = hfv.getDeclaration().getParams().size();
                        for (com.novalang.ir.hir.decl.HirParam p : hfv.getDeclaration().getParams()) {
                            if (!p.hasDefaultValue()) requiredCount++;
                        }
                        if (argCount >= requiredCount && argCount <= totalCount) {
                            matched = c;
                            break;
                        }
                    }
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
                        callArgs.add(nova.runtime.NovaNull.NULL);
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
     * Initialize instance fields declared in class body (HIR path).
     */
    void initializeInstanceFields(NovaObject instance, NovaClass cls) {
        // 如果有 HIR 构造器，字段初始化 + init 块将由 initHirProperties 在构造器 env 中执行
        // 此处只需跳过，避免重复执行
        boolean hasHirConstructor = !cls.getHirConstructors().isEmpty();

        List<AstNode> initializers = interp.classInstanceInitializers.get(cls.getName());
        if (initializers != null && !initializers.isEmpty()) {
            // 有主构造器时跳过（由 initHirProperties 处理）
            if (hasHirConstructor) return;
            // 无主构造器：执行有序初始化列表（字段初始化器 + init 块）
            Environment initEnv = new Environment(interp.environment);
            initEnv.defineVal("this", instance);
            interp.withEnvironment(initEnv, () -> {
                for (AstNode node : initializers) {
                    if (node instanceof HirField) {
                        HirField field = (HirField) node;
                        if (field.hasInitializer()) {
                            instance.setField(field.getName(), interp.evaluateHir(field.getInitializer()));
                        }
                    } else {
                        interp.executeAstNode(node);
                    }
                }
                return NovaNull.UNIT;
            });
            return;
        }
        // 回退：无 instanceInitializers 的旧路径
        List<HirField> fields = interp.classInstanceFields.get(cls.getName());
        if (fields == null) return;
        // 有主构造器时跳过（由 initHirProperties 处理）
        if (hasHirConstructor) return;
        Environment initEnv = new Environment(interp.environment);
        initEnv.defineVal("this", instance);
        interp.withEnvironment(initEnv, () -> {
            for (HirField field : fields) {
                if (field.hasInitializer()) {
                    NovaValue value = interp.evaluateHir(field.getInitializer());
                    instance.setField(field.getName(), value);
                }
            }
            return NovaNull.UNIT;
        });
    }

    // ============ HIR Function Execution ============

    ExtensionRegistry.HirExtProp findHirExtensionProperty(NovaValue receiver, String propertyName) {
        return interp.extensionRegistry.findHirExtensionProperty(receiver, propertyName);
    }

    NovaValue executeHirExtensionPropertyGetter(ExtensionRegistry.HirExtProp prop, NovaValue receiver) {
        return interp.extensionRegistry.executeHirExtensionPropertyGetter(prop, receiver, interp);
    }

    /**
     * Find HirField with custom getter in a class.
     */
    HirField findHirFieldWithGetter(String className, String fieldName) {
        Map<String, HirField> getters = interp.customGetterCache.get(className);
        return getters != null ? getters.get(fieldName) : null;
    }

    /**
     * Find HirField with custom setter in a class.
     */
    HirField findHirFieldWithSetter(String className, String fieldName) {
        Map<String, HirField> setters = interp.customSetterCache.get(className);
        return setters != null ? setters.get(fieldName) : null;
    }

    /**
     * Execute custom setter body, binding this and setter parameter.
     */
    void executeHirCustomSetter(HirField field, NovaObject obj, NovaValue value) {
        Environment setterEnv = new Environment(interp.environment);
        setterEnv.defineVal("this", obj);
        String paramName = field.getSetterParam() != null ? field.getSetterParam().getName() : "value";
        setterEnv.defineVal(paramName, value);
        runInEnv(setterEnv, field.getSetterBody());
    }

    /**
     * Execute custom getter body, binding this to target object.
     */
    NovaValue executeHirCustomGetter(HirField field, NovaObject obj) {
        Environment getterEnv = new Environment(interp.environment);
        getterEnv.defineVal("this", obj);
        return runInEnv(getterEnv, field.getGetterBody());
    }

    void initHirProperties(NovaClass novaClass, NovaObject instance, Environment ctorEnv) {
        // 优先使用有序初始化列表（字段初始化器 + init 块按声明顺序）
        List<AstNode> initializers = interp.classInstanceInitializers.get(novaClass.getName());
        if (initializers != null && !initializers.isEmpty()) {
            interp.withEnvironment(ctorEnv, () -> {
                for (AstNode node : initializers) {
                    if (node instanceof HirField) {
                        HirField field = (HirField) node;
                        if (field.hasInitializer()) {
                            instance.setField(field.getName(), interp.evaluateHir(field.getInitializer()));
                        }
                    } else {
                        // init 块体（Block）— 直接执行
                        interp.executeAstNode(node);
                    }
                }
                return NovaNull.UNIT;
            });
            return;
        }
        // 回退：无 instanceInitializers 的旧路径
        List<HirField> fields = interp.classInstanceFields.get(novaClass.getName());
        if (fields == null) return;
        interp.withEnvironment(ctorEnv, () -> {
            for (HirField field : fields) {
                if (field.hasInitializer()) {
                    NovaValue value = interp.evaluateHir(field.getInitializer());
                    instance.setField(field.getName(), value);
                }
            }
            return NovaNull.UNIT;
        });
    }

    NovaValue executeHirFunction(HirFunctionValue function, List<NovaValue> args,
                                  Map<String, NovaValue> namedArgs) {
        int maxDepth = interp.getSecurityPolicy().getMaxRecursionDepth();
        if (maxDepth > 0 && interp.callDepth >= maxDepth) {
            throw NovaSecurityPolicy.denied("Maximum recursion depth exceeded (" + maxDepth + ")");
        }
        interp.callStack.push(NovaCallFrame.fromHirFunction(function, args));
        interp.callDepth++;
        try {
            return executeHirFunctionBody(function, args, namedArgs);
        } finally {
            interp.callDepth--;
            interp.callStack.pop();
        }
    }

    private NovaValue executeHirFunctionBody(HirFunctionValue function, List<NovaValue> args,
                                              Map<String, NovaValue> namedArgs) {
        Environment funcEnv = new Environment(function.getClosure());
        HirFunction decl = function.getDeclaration();
        List<HirParam> params = decl.getParams();

        // Bind parameters
        bindParams(funcEnv, params, args, namedArgs);

        // Bind reified type parameters
        if (interp.pendingHirTypeArgs != null && !decl.getTypeParams().isEmpty()) {
            List<String> typeParams = decl.getTypeParams();
            for (int i = 0; i < typeParams.size() && i < interp.pendingHirTypeArgs.size(); i++) {
                String typeName = getHirTypeName(interp.pendingHirTypeArgs.get(i));
                if (typeName != null) {
                    funcEnv.defineValFast("__reified_" + typeParams.get(i), NovaString.of(typeName));
                }
            }
        }

        return runInEnv(funcEnv, decl.getBody());
    }

    NovaValue executeHirLambda(HirLambdaValue lambda, List<NovaValue> args) {
        int maxDepth = interp.getSecurityPolicy().getMaxRecursionDepth();
        if (maxDepth > 0 && interp.callDepth >= maxDepth) {
            throw NovaSecurityPolicy.denied("Maximum recursion depth exceeded (" + maxDepth + ")");
        }
        NovaCallFrame parentFrame = interp.callStack.peek();
        String parentName = parentFrame != null ? parentFrame.getFunctionName() : null;
        interp.callStack.push(NovaCallFrame.fromHirLambda(lambda, args, parentName));
        interp.callDepth++;
        try {
            return executeHirLambdaBody(lambda, args);
        } finally {
            interp.callDepth--;
            interp.callStack.pop();
        }
    }

    private NovaValue executeHirLambdaBody(HirLambdaValue lambda, List<NovaValue> args) {
        Environment lambdaEnv = new Environment(lambda.getClosure());
        HirLambda expr = lambda.getExpression();
        List<HirParam> params = expr.getParams();

        // "this" at slot 0 (consistent with HirVariableResolver.resolveLambda slot layout)
        NovaValue closureThis = lambda.getClosure().tryGet("this");
        lambdaEnv.defineValFast("this", closureThis != null ? closureThis : NovaNull.NULL);

        for (int i = 0; i < params.size(); i++) {
            String paramName = params.get(i).getName();
            NovaValue value = i < args.size() ? args.get(i) : NovaNull.NULL;
            lambdaEnv.defineValFast(paramName, value);
        }

        // No-param lambda: always bind 'it' (consistent with HirVariableResolver slot layout)
        if (params.isEmpty()) {
            lambdaEnv.defineValFast("it", args.isEmpty() ? NovaNull.NULL : args.get(0));
        }

        return runInEnv(lambdaEnv, expr.getBody());
    }

    // ============ 统一 body 执行辅助方法 ============

    /**
     * 统一执行 HIR 函数/Lambda body 节点。
     * 处理 null body、Block inline（含 isTransparent 检查）、单节点执行。
     */
    NovaValue executeHirBody(AstNode body) {
        if (body == null) return NovaNull.UNIT;
        if (body instanceof Block && !((Block) body).isTransparent()) {
            NovaValue r = NovaNull.UNIT;
            for (Statement stmt : ((Block) body).getStatements()) {
                r = interp.executeHirStmt(stmt);
                if (interp.getHasReturn()) return interp.getReturnValue();
            }
            return r;
        }
        NovaValue r = interp.executeAstNode(body);
        if (interp.getHasReturn()) return interp.getReturnValue();
        return r;
    }

    /**
     * 在指定环境中执行 body，自动保存/恢复 hasReturn + returnValue + environment，
     * 并捕获 ControlFlow.RETURN 信号。
     */
    private NovaValue runInEnv(Environment env, AstNode body) {
        boolean savedHR = interp.getHasReturn();
        NovaValue savedRV = interp.getReturnValue();
        interp.setHasReturn(false);
        Environment saved = interp.environment;
        interp.environment = env;
        try {
            return executeHirBody(body);
        } catch (ControlFlow cf) {
            if (cf.getType() == ControlFlow.Type.RETURN) return cf.getValue();
            throw cf;
        } finally {
            interp.environment = saved;
            interp.setHasReturn(savedHR);
            interp.setReturnValue(savedRV);
        }
    }

    // HIR Block execution
    NovaValue executeBlock(Block block, Environment blockEnv) {
        Environment saved = interp.environment;
        interp.environment = blockEnv;
        try {
            NovaValue result = NovaNull.UNIT;
            for (Statement stmt : block.getStatements()) {
                result = interp.executeHirStmt(stmt);
                if (interp.getHasReturn()) return interp.getReturnValue();
            }
            return result;
        } finally {
            interp.environment = saved;
        }
    }

    Interpreter.HirLoopSignal executeHirLoopBody(Statement body) {
        interp.checkLoopLimits();
        try {
            interp.executeHirStmt(body);
            if (interp.getHasReturn()) return Interpreter.HirLoopSignal.BREAK;
            return Interpreter.HirLoopSignal.NORMAL;
        } catch (ControlFlow cf) {
            if (cf.getType() == ControlFlow.Type.BREAK && cf.getLabel() == null) return Interpreter.HirLoopSignal.BREAK;
            if (cf.getType() == ControlFlow.Type.CONTINUE && cf.getLabel() == null) return Interpreter.HirLoopSignal.CONTINUE;
            throw cf; // labeled break/continue rethrown, handled by matching outer loop
        }
    }

    Interpreter.HirLoopSignal executeHirLoopBody(Environment loopEnv, Statement body) {
        interp.checkLoopLimits();
        Environment saved = interp.environment;
        interp.environment = loopEnv;
        try {
            if (body instanceof Block && !((Block) body).isTransparent()) {
                for (Statement stmt : ((Block) body).getStatements()) {
                    interp.executeHirStmt(stmt);
                    if (interp.getHasReturn()) return Interpreter.HirLoopSignal.BREAK;
                }
            } else {
                interp.executeHirStmt(body);
                if (interp.getHasReturn()) return Interpreter.HirLoopSignal.BREAK;
            }
            return Interpreter.HirLoopSignal.NORMAL;
        } catch (ControlFlow cf) {
            if (cf.getType() == ControlFlow.Type.BREAK && cf.getLabel() == null) return Interpreter.HirLoopSignal.BREAK;
            if (cf.getType() == ControlFlow.Type.CONTINUE && cf.getLabel() == null) return Interpreter.HirLoopSignal.CONTINUE;
            throw cf; // labeled break/continue rethrown
        } finally {
            interp.environment = saved;
        }
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

    // ========== 参数绑定辅助方法 ==========

    /**
     * 绑定 Lambda 参数（支持 it 参数自动绑定）
     */
    private void bindLambdaParams(Environment env, List<HirParam> params, List<NovaValue> args) {
        for (int i = 0; i < params.size(); i++) {
            env.defineValFast(params.get(i).getName(), i < args.size() ? args.get(i) : NovaNull.NULL);
        }
        if (params.isEmpty()) {
            env.defineValFast("it", args.isEmpty() ? NovaNull.NULL : args.get(0));
        }
    }

    /**
     * 通用参数绑定：将实参/命名参数/默认值绑定到环境中。
     * @param env 目标环境
     * @param params 形参列表
     * @param args 位置实参
     * @param namedArgs 命名实参（可为 null）
     */
    private void bindParams(Environment env, List<HirParam> params,
                            List<NovaValue> args, Map<String, NovaValue> namedArgs) {
        int argIndex = 0;
        for (HirParam param : params) {
            NovaValue value = null;
            if (namedArgs != null && namedArgs.containsKey(param.getName())) {
                value = namedArgs.get(param.getName());
            } else if (argIndex < args.size()) {
                if (param.isVararg()) {
                    List<NovaValue> varargs = new ArrayList<>();
                    while (argIndex < args.size()) {
                        varargs.add(args.get(argIndex++));
                    }
                    value = new NovaList(varargs);
                } else {
                    value = args.get(argIndex++);
                }
            } else if (param.hasDefaultValue()) {
                value = interp.withEnvironment(env, () -> interp.evaluateHir(param.getDefaultValue()));
            } else if (param.isVararg()) {
                value = new NovaList();
            } else {
                throw new NovaRuntimeException("Missing argument: " + param.getName());
            }
            env.defineValFast(param.getName(), value);
        }
        // 检查多余位置参数
        if (argIndex < args.size()) {
            throw new NovaRuntimeException(
                    "Too many arguments: expected " + params.size()
                    + " but got " + args.size());
        }
        // 检查未知命名参数
        if (namedArgs != null) {
            for (String key : namedArgs.keySet()) {
                boolean found = false;
                for (HirParam p : params) {
                    if (p.getName().equals(key)) { found = true; break; }
                }
                if (!found) {
                    throw new NovaRuntimeException("Unknown named argument: " + key);
                }
            }
        }
    }
}
