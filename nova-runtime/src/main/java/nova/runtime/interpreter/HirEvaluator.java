package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.Environment;
import nova.runtime.types.NovaClass;
import nova.runtime.types.NovaInterface;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.expr.BinaryExpr.BinaryOp;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.parser.ParseException;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.compiler.hirtype.*;
import nova.runtime.interpreter.reflect.*;

import java.util.*;

/**
 * HIR node evaluator: implements HirVisitor to evaluate all HIR nodes.
 *
 * <p>Package-private class that holds a reference to the owning Interpreter
 * and delegates field/method access through it.</p>
 */
final class HirEvaluator implements HirVisitor<NovaValue, Void> {

    final Interpreter interp;

    HirEvaluator(Interpreter interp) {
        this.interp = interp;
    }

    // ============ HIR 执行辅助 ============

    public NovaValue executeHirModule(HirModule module) {
        // 合并 import 和声明，按源码行号排序，保证按源码顺序执行
        // （循环依赖要求 fun/class 声明先于后续 import 注册到环境）
        List<HirDecl> all = new ArrayList<>();
        all.addAll(module.getImports());
        all.addAll(module.getDeclarations());
        all.sort((a, b) -> {
            int la = a.getLocation() != null ? a.getLocation().getLine() : 0;
            int lb = b.getLocation() != null ? b.getLocation().getLine() : 0;
            return Integer.compare(la, lb);
        });
        NovaValue result = NovaNull.UNIT;
        for (HirDecl item : all) {
            if (item instanceof HirImport) {
                item.accept(this, null);
            } else {
                result = executeHirDecl(item);
            }
        }
        return result;
    }

    private NovaValue executeHirDecl(HirDecl decl) {
        if (decl instanceof HirField) return visitField((HirField) decl, null);
        return decl.accept(this, null);
    }

    NovaValue executeHirStmt(Statement stmt) {
        // 直接分派热路径：跳过 accept() 虚分派开销
        if (stmt instanceof ExpressionStmt) {
            return evaluateHir(((ExpressionStmt) stmt).getExpression());
        }
        if (stmt instanceof HirDeclStmt) {
            return executeHirDecl(((HirDeclStmt) stmt).getDeclaration());
        }
        if (stmt instanceof ReturnStmt) {
            return visitReturn((ReturnStmt) stmt, null);
        }
        if (stmt instanceof ThrowStmt) {
            return visitThrow((ThrowStmt) stmt, null);
        }
        if (stmt instanceof ForStmt) {
            return visitFor((ForStmt) stmt, null);
        }
        if (stmt instanceof IfStmt) {
            return visitIf((IfStmt) stmt, null);
        }
        if (stmt instanceof BreakStmt) {
            return visitBreak((BreakStmt) stmt);
        }
        if (stmt instanceof ContinueStmt) {
            return visitContinue((ContinueStmt) stmt);
        }
        if (stmt instanceof Block) {
            return visitBlock((Block) stmt, null);
        }
        // stmt 实际是 HirStmt 子类，需要转型以调用 HirVisitor.accept
        return ((HirStmt) stmt).accept(this, null);
    }

    public NovaValue evaluateHir(Expression expr) {
        // 直接分派热路径：跳过 accept() 虚分派 + try-catch 开销
        if (expr instanceof Identifier) {
            Identifier ref = (Identifier) expr;
            if (ref.isResolved()) {
                return interp.environment.getAtSlot(ref.getResolvedDepth(), ref.getResolvedSlot());
            }
            NovaValue v = interp.environment.tryGet(ref.getName());
            if (v != null && !(v instanceof NovaNativeFunction)) return v;
            return visitVarRef(ref, null);
        }
        if (expr instanceof Literal) {
            return visitLiteral((Literal) expr, null);
        }
        if (expr instanceof BinaryExpr) {
            return visitBinary((BinaryExpr) expr, null);
        }
        if (expr instanceof UnaryExpr) {
            return visitUnary((UnaryExpr) expr, null);
        }
        if (expr instanceof HirCall) {
            return visitCall((HirCall) expr, null);
        }
        if (expr instanceof MemberExpr) {
            return visitFieldAccess((MemberExpr) expr, null);
        }
        if (expr instanceof AssignExpr) {
            return visitAssign((AssignExpr) expr, null);
        }
        // 以下类型不会抛出未包装异常，可安全跳过 try-catch
        if (expr instanceof ConditionalExpr) {
            return visitConditional((ConditionalExpr) expr, null);
        }
        if (expr instanceof BlockExpr) {
            return visitBlockExpr((BlockExpr) expr, null);
        }
        if (expr instanceof HirLambda) {
            return visitLambda((HirLambda) expr, null);
        }
        if (expr instanceof HirCollectionLiteral) {
            return visitCollectionLiteral((HirCollectionLiteral) expr, null);
        }
        if (expr instanceof RangeExpr) {
            return visitRange((RangeExpr) expr, null);
        }
        if (expr instanceof ThisExpr) {
            return visitThisRef((ThisExpr) expr, null);
        }
        if (expr instanceof TypeCheckExpr) {
            return visitTypeCheck((TypeCheckExpr) expr, null);
        }
        if (expr instanceof TypeCastExpr) {
            return visitTypeCast((TypeCastExpr) expr, null);
        }
        if (expr instanceof MethodRefExpr) {
            return visitMethodRef((MethodRefExpr) expr, null);
        }
        if (expr instanceof NotNullExpr) {
            return visitNullCheck((NotNullExpr) expr, null);
        }
        if (expr instanceof IndexExpr) {
            return visitIndex((IndexExpr) expr, null);
        }
        if (expr instanceof AwaitExpr) {
            return visitAwait((AwaitExpr) expr, null);
        }
        if (expr instanceof ErrorPropagationExpr) {
            return visitErrorPropagation((ErrorPropagationExpr) expr, null);
        }
        // 其他低频表达式类型走 accept 路径（含错误位置包装）
        // expr 实际是 HirExpr 子类，需要转型以调用 HirVisitor.accept
        try {
            return ((HirExpr) expr).accept(this, null);
        } catch (NovaRuntimeException | ControlFlow | ParseException e) {
            throw e;
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            NovaRuntimeException wrapped = hirError(msg, (AstNode) expr);
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    NovaValue executeAstNode(AstNode node) {
        if (node instanceof HirDecl) return executeHirDecl((HirDecl) node);
        if (node instanceof Expression) return evaluateHir((Expression) node);
        if (node instanceof Statement) return executeHirStmt((Statement) node);
        return NovaNull.UNIT;
    }

    protected NovaRuntimeException hirError(String message, AstNode node) {
        if (node != null) {
            SourceLocation loc = node.getLocation();
            String sourceLine = getSourceLineInternal(loc.getLine());
            NovaRuntimeException ex = new NovaRuntimeException(message, loc, sourceLine);
            ex.setNovaStackTrace(interp.captureStackTraceString());
            return ex;
        }
        NovaRuntimeException ex = new NovaRuntimeException(message);
        ex.setNovaStackTrace(interp.captureStackTraceString());
        return ex;
    }

    private String getSourceLineInternal(int lineNumber) {
        return interp.getSourceLine(lineNumber);
    }
    private nova.runtime.types.Modifier extractHirVisibility(Set<Modifier> modifiers) {
        if (modifiers.contains(Modifier.PRIVATE)) return nova.runtime.types.Modifier.PRIVATE;
        if (modifiers.contains(Modifier.PROTECTED)) return nova.runtime.types.Modifier.PROTECTED;
        if (modifiers.contains(Modifier.INTERNAL)) return nova.runtime.types.Modifier.INTERNAL;
        return nova.runtime.types.Modifier.PUBLIC;
    }
    // ============ 声明的 visit 方法 (9) ============

    @Override
    public NovaValue visitModule(HirModule node, Void ctx) {
        return executeHirModule(node);
    }

    @Override
    public NovaValue visitClass(HirClass node, Void ctx) {
        NovaClass superclass = null;
        List<NovaInterface> interfaces = new ArrayList<>();
        Class<?> javaSuperclassRef = null;
        List<Class<?>> javaInterfacesList = new ArrayList<>();

        // 解析超类型（superClass 可能实际是接口）
        if (node.getSuperClass() != null) {
            String superName = interp.getHirTypeName(node.getSuperClass());
            if (superName != null) {
                NovaValue superValue = interp.environment.tryGet(superName);
                if (superValue instanceof NovaClass) {
                    superclass = (NovaClass) superValue;
                } else if (superValue instanceof NovaInterface) {
                    interfaces.add((NovaInterface) superValue);
                } else if (superValue instanceof JavaInterop.NovaJavaClass) {
                    Class<?> javaClass = ((JavaInterop.NovaJavaClass) superValue).getJavaClass();
                    if (javaClass.isInterface()) javaInterfacesList.add(javaClass);
                    else javaSuperclassRef = javaClass;
                } else {
                    // 回退到 resolveClass 解析 Java 类型
                    Class<?> javaClass = interp.resolveClass(superName);
                    if (javaClass != null) {
                        if (javaClass.isInterface()) javaInterfacesList.add(javaClass);
                        else javaSuperclassRef = javaClass;
                    }
                }
            }
        }
        for (HirType ifaceType : node.getInterfaces()) {
            String ifaceName = interp.getHirTypeName(ifaceType);
            if (ifaceName != null) {
                NovaValue ifaceValue = interp.environment.tryGet(ifaceName);
                if (ifaceValue instanceof NovaInterface) {
                    interfaces.add((NovaInterface) ifaceValue);
                } else if (ifaceValue instanceof JavaInterop.NovaJavaClass) {
                    Class<?> javaClass = ((JavaInterop.NovaJavaClass) ifaceValue).getJavaClass();
                    if (javaClass.isInterface()) javaInterfacesList.add(javaClass);
                    else if (javaSuperclassRef == null) javaSuperclassRef = javaClass;
                } else {
                    // 回退到 resolveClass 解析 Java 类型
                    Class<?> javaClass = interp.resolveClass(ifaceName);
                    if (javaClass != null) {
                        if (javaClass.isInterface()) javaInterfacesList.add(javaClass);
                        else if (javaSuperclassRef == null) javaSuperclassRef = javaClass;
                    }
                }
            }
        }

        // 处理不同 ClassKind
        switch (node.getClassKind()) {
            case INTERFACE:
                return visitHirInterface(node);
            case ENUM:
                return visitHirEnum(node);
            case OBJECT:
                return visitHirObject(node);
            case ANNOTATION:
                return visitHirAnnotationClass(node);
            default:
                break; // CLASS
        }

        // 创建类（使用 null declaration，因为我们不使用 AST ClassDecl 了）
        NovaClass novaClass = new NovaClass(node.getName(), superclass, interp.environment);
        if (node.getModifiers().contains(Modifier.ABSTRACT)) {
            novaClass.setAbstract(true);
        }
        if (node.getModifiers().contains(Modifier.SEALED)) {
            novaClass.setSealed(true);
        }

        // 注册 HirFunctionValue 作为方法
        for (HirFunction method : node.getMethods()) {
            HirFunctionValue func = new HirFunctionValue(method.getName(), method, interp.environment);
            if (method.getModifiers().contains(Modifier.STATIC)) {
                // 静态方法（如 companion object 方法）：注册为静态字段
                novaClass.setStaticField(method.getName(), func);
            } else {
                novaClass.addMethod(method.getName(), func);
            }
            novaClass.setMethodVisibility(method.getName(), extractHirVisibility(method.getModifiers()));
        }

        // 添加接口
        for (NovaInterface iface : interfaces) {
            novaClass.addInterface(iface);
        }

        // 设置 Java 类型
        if (javaSuperclassRef != null) {
            if (java.lang.reflect.Modifier.isFinal(javaSuperclassRef.getModifiers())) {
                throw hirError("Cannot extend final class: " + javaSuperclassRef.getName(), node);
            }
            novaClass.setJavaSuperclass(javaSuperclassRef);
        }
        for (Class<?> javaIface : javaInterfacesList) {
            novaClass.addJavaInterface(javaIface);
        }

        // 评估并存储超类构造器参数（如 Thread("worker")）
        if (novaClass.hasJavaSuperTypes() && node.getSuperConstructorArgs() != null
                && !node.getSuperConstructorArgs().isEmpty()) {
            List<NovaValue> superCtorArgs = new ArrayList<>();
            for (Expression arg : node.getSuperConstructorArgs()) {
                superCtorArgs.add(evaluateHir(arg));
            }
            novaClass.setJavaSuperConstructorArgs(superCtorArgs);
        }

        // Nova→Nova 继承：保存超类构造器参数表达式（延迟到实例化时在构造器环境中求值）
        if (superclass != null && node.getSuperConstructorArgs() != null
                && !node.getSuperConstructorArgs().isEmpty()) {
            interp.hirSuperCtorArgs.put(node.getName(), node.getSuperConstructorArgs());
        }

        // 注册到环境
        if (interp.replMode) {
            interp.environment.redefine(node.getName(), novaClass, false);
        } else {
            interp.environment.defineVal(node.getName(), novaClass);
        }

        // 处理构造器（包装为 HirFunctionValue 存储到 hirConstructors 列表）
        for (HirFunction ctor : node.getConstructors()) {
            HirFunctionValue ctorFunc = new HirFunctionValue("<init>", ctor, interp.environment);
            novaClass.addHirConstructor(ctorFunc);
        }
        // 兼容：设置主构造器（第一个）为 constructorCallable
        if (!node.getConstructors().isEmpty()) {
            novaClass.setConstructorCallable(novaClass.getHirConstructors().get(0));
        }

        // 保存所有 HirField 供反射 API 使用
        interp.hirClassFields.put(node.getName(), node.getFields());

        // 处理字段可见性和收集实例字段
        List<HirField> instanceFields = new ArrayList<>();
        for (HirField field : node.getFields()) {
            novaClass.setFieldVisibility(field.getName(), extractHirVisibility(field.getModifiers()));

            // 静态字段
            if (field.getModifiers().contains(Modifier.STATIC) ||
                field.getModifiers().contains(Modifier.CONST)) {
                if (field.hasInitializer()) {
                    NovaValue value = evaluateHir(field.getInitializer());
                    novaClass.setStaticField(field.getName(), value);
                }
            } else {
                instanceFields.add(field);
            }
        }
        if (!instanceFields.isEmpty()) {
            interp.classInstanceFields.put(node.getName(), instanceFields);
        }
        // 计算字段布局
        {
            List<String> fieldNameList = new ArrayList<>();
            for (HirField f : instanceFields) {
                fieldNameList.add(f.getName());
            }
            novaClass.computeFieldLayout(fieldNameList);
        }
        // 存储有序实例初始化列表（字段初始化器 + init 块）
        if (!node.getInstanceInitializers().isEmpty()) {
            interp.classInstanceInitializers.put(node.getName(), node.getInstanceInitializers());
        }
        if (!instanceFields.isEmpty()) {
            // 构建自定义 getter/setter 缓存（O(1) 查找替代线性扫描）
            Map<String, HirField> getters = null;
            Map<String, HirField> setters = null;
            for (HirField f : instanceFields) {
                if (f.hasCustomGetter()) {
                    if (getters == null) getters = new HashMap<>();
                    getters.put(f.getName(), f);
                }
                if (f.hasCustomSetter()) {
                    if (setters == null) setters = new HashMap<>();
                    setters.put(f.getName(), f);
                }
            }
            if (getters != null) interp.customGetterCache.put(node.getName(), getters);
            if (setters != null) interp.customSetterCache.put(node.getName(), setters);
        }

        // 处理注解
        if (node.getClassKind() == ClassKind.ANNOTATION) {
            novaClass.setAnnotation(true);
        }

        // 保存注解到 interp.hirClassAnnotations 供运行时查询
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            interp.hirClassAnnotations.put(node.getName(), node.getAnnotations());
        }

        // 预构建并缓存 ClassInfo（供注解处理器和 classOf 使用）
        novaClass.setCachedClassInfo(interp.buildHirClassInfo(novaClass));

        // 注解处理器
        processHirClassAnnotations(node.getAnnotations(), novaClass);

        // data class：设置字段顺序（主构造器参数名）
        if (novaClass.isData() && !node.getConstructors().isEmpty()) {
            List<String> order = new ArrayList<>();
            for (com.novalang.ir.hir.decl.HirParam p : node.getConstructors().get(0).getParams()) {
                order.add(p.getName());
            }
            novaClass.setDataFieldOrder(order);
        }

        return novaClass;
    }

    private NovaValue visitHirInterface(HirClass node) {
        NovaInterface novaInterface = new NovaInterface(node.getName());

        // 超接口
        for (HirType superType : node.getInterfaces()) {
            String superName = interp.getHirTypeName(superType);
            if (superName != null) {
                NovaValue superValue = interp.environment.tryGet(superName);
                if (superValue instanceof NovaInterface) {
                    novaInterface.addSuperInterface((NovaInterface) superValue);
                }
            }
        }

        // 方法
        for (HirFunction method : node.getMethods()) {
            if (method.getBody() != null) {
                HirFunctionValue func = new HirFunctionValue(method.getName(), method, interp.environment);
                novaInterface.addDefaultMethod(method.getName(), func);
            } else {
                novaInterface.addAbstractMethod(method.getName());
            }
        }

        if (interp.replMode) {
            interp.environment.redefine(node.getName(), novaInterface, false);
        } else {
            interp.environment.defineVal(node.getName(), novaInterface);
        }
        return novaInterface;
    }

    private NovaValue visitHirObject(HirClass node) {
        NovaClass objectClass = new NovaClass(node.getName(), null, interp.environment);
        for (HirFunction method : node.getMethods()) {
            HirFunctionValue func = new HirFunctionValue(method.getName(), method, interp.environment);
            objectClass.addMethod(method.getName(), func);
        }

        NovaObject instance = new NovaObject(objectClass);

        // 初始化属性
        for (HirField field : node.getFields()) {
            if (field.hasInitializer()) {
                Environment tempEnv = new Environment(interp.environment);
                tempEnv.defineVal("this", instance);
                NovaValue value = interp.withEnvironment(tempEnv, () ->
                    evaluateHir(field.getInitializer()));
                instance.setField(field.getName(), value);
            } else {
                instance.setField(field.getName(), NovaNull.NULL);
            }
        }

        if (interp.replMode) {
            interp.environment.redefine(node.getName(), instance, false);
        } else {
            interp.environment.defineVal(node.getName(), instance);
        }
        return instance;
    }

    private NovaValue visitHirEnum(HirClass node) {
        NovaEnum novaEnum = new NovaEnum(node.getName());

        // 枚举类方法
        Map<String, NovaCallable> enumMethods = new HashMap<>();
        for (HirFunction method : node.getMethods()) {
            HirFunctionValue func = new HirFunctionValue(method.getName(), method, interp.environment);
            enumMethods.put(method.getName(), func);
            novaEnum.addMethod(method.getName(), func);
        }

        // 枚举条目
        List<HirParam> ctorParams = new ArrayList<>();
        for (HirFunction ctor : node.getConstructors()) {
            ctorParams.addAll(ctor.getParams());
        }
        // 也检查 fields 作为构造器参数
        if (ctorParams.isEmpty()) {
            for (HirField field : node.getFields()) {
                // 每个非静态字段可以作为构造器参数
            }
        }

        int ordinal = 0;
        for (HirEnumEntry entry : node.getEnumEntries()) {
            Map<String, NovaValue> fields = new HashMap<>();

            // 求值构造器参数
            List<Expression> entryArgs = entry.getArgs();
            for (int i = 0; i < entryArgs.size() && i < ctorParams.size(); i++) {
                fields.put(ctorParams.get(i).getName(), evaluateHir(entryArgs.get(i)));
            }

            // 复制枚举类方法
            Map<String, NovaCallable> entryMethods = new HashMap<>(enumMethods);

            // 条目特有方法
            for (HirDecl member : entry.getMembers()) {
                if (member instanceof HirFunction) {
                    HirFunction method = (HirFunction) member;
                    HirFunctionValue func = new HirFunctionValue(method.getName(), method, interp.environment);
                    entryMethods.put(method.getName(), func);
                } else if (member instanceof HirField) {
                    HirField field = (HirField) member;
                    if (field.hasInitializer()) {
                        fields.put(field.getName(), evaluateHir(field.getInitializer()));
                    }
                }
            }

            NovaEnumEntry enumEntry = new NovaEnumEntry(
                entry.getName(), ordinal++, novaEnum, fields, entryMethods);
            novaEnum.addEntry(enumEntry);
        }

        if (interp.replMode) {
            interp.environment.redefine(node.getName(), novaEnum, false);
        } else {
            interp.environment.defineVal(node.getName(), novaEnum);
        }
        return novaEnum;
    }

    private NovaValue visitHirAnnotationClass(HirClass node) {
        NovaClass novaClass = new NovaClass(node.getName(), null, interp.environment);
        novaClass.setAnnotation(true);

        if (interp.replMode) {
            interp.environment.redefine(node.getName(), novaClass, false);
        } else {
            interp.environment.defineVal(node.getName(), novaClass);
        }
        return novaClass;
    }

    private void processHirClassAnnotations(List<HirAnnotation> annotations, NovaClass target) {
        for (HirAnnotation ann : annotations) {
            List<NovaAnnotationProcessor> processors = interp.annotationProcessors.get(ann.getName());
            if (processors != null) {
                Map<String, NovaValue> args = evaluateHirAnnotationArgs(ann);
                for (NovaAnnotationProcessor proc : processors) {
                    proc.processClass(target, args, interp);
                }
            }
        }
    }

    private Map<String, NovaValue> evaluateHirAnnotationArgs(HirAnnotation ann) {
        Map<String, NovaValue> result = new HashMap<>();
        for (Map.Entry<String, Expression> entry : ann.getArgs().entrySet()) {
            result.put(entry.getKey(), evaluateHir(entry.getValue()));
        }
        return result;
    }

    @Override
    public NovaValue visitFunction(HirFunction node, Void ctx) {
        HirFunctionValue func = new HirFunctionValue(node.getName(), node, interp.environment);

        // 扩展函数
        if (node.isExtensionFunction()) {
            String typeName = interp.getHirTypeName(node.getReceiverType());
            if (typeName != null) {
                interp.registerNovaExtension(typeName, node.getName(), func);
            }
            return NovaNull.UNIT;
        }

        // 普通函数注册到环境
        if (interp.replMode) {
            interp.environment.redefine(node.getName(), func, false);
        } else {
            interp.environment.defineVal(node.getName(), func);
        }
        return func;
    }

    @Override
    public NovaValue visitField(HirField node, Void ctx) {
        // 扩展属性
        if (node.isExtensionProperty() && node.hasInitializer()) {
            String typeName = interp.getHirTypeName(node.getReceiverType());
            if (typeName != null) {
                Map<String, ExtensionRegistry.HirExtProp> props = interp.extensionRegistry.hirExtensionProperties
                        .computeIfAbsent(typeName, k -> new HashMap<>());
                props.put(node.getName(), new ExtensionRegistry.HirExtProp(node.getInitializer(), interp.environment));
            }
            return NovaNull.UNIT;
        }

        NovaValue value = NovaNull.NULL;
        if (node.hasInitializer()) {
            value = evaluateHir(node.getInitializer());
        }

        // SAM 隐式转换：Lambda/Callable 赋值给 Java 接口类型时自动转换
        if (value instanceof NovaCallable && node.getType() != null) {
            String typeName = interp.getHirTypeName(node.getType());
            if (typeName != null) {
                try {
                    Class<?> targetClass = interp.resolveClass(typeName);
                    if (targetClass != null && targetClass.isInterface()) {
                        Object samProxy = createSamProxy(targetClass, (NovaCallable) value);
                        if (samProxy != null) {
                            value = new NovaExternalObject(samProxy);
                        }
                    }
                } catch (Exception e) {
                    // SAM conversion failed, keep original value
                }
            }
        }

        if (interp.replMode) {
            interp.environment.redefine(node.getName(), value, !node.isVal());
        } else {
            if (node.isVal()) {
                interp.environment.defineVal(node.getName(), value);
            } else {
                interp.environment.defineVar(node.getName(), value);
            }
        }
        return value;
    }

    @Override
    public NovaValue visitParam(HirParam node, Void ctx) {
        return NovaNull.UNIT;
    }

    @Override
    public NovaValue visitEnumEntry(HirEnumEntry node, Void ctx) {
        // 在 visitHirEnum 中处理
        return NovaNull.UNIT;
    }

    @Override
    public NovaValue visitTypeAlias(HirTypeAlias node, Void ctx) {
        // 类型别名在解释器中暂不处理
        return NovaNull.UNIT;
    }

    @Override
    public NovaValue visitAnnotation(HirAnnotation node, Void ctx) {
        return NovaNull.UNIT;
    }

    @Override
    public NovaValue visitImport(HirImport node, Void ctx) {
        String qualifiedName = node.getQualifiedName();

        // import static java.lang.Integer.MAX_VALUE
        if (node.isStatic()) {
            return importStaticHir(node);
        }

        // import java java.util.ArrayList / import java java.util.*
        if (node.isJava()) {
            return importJavaHir(node);
        }

        // Nova 模块导入
        return importNovaModuleHir(node);
    }

    private NovaValue importJavaHir(HirImport node) {
        String qualifiedName = node.getQualifiedName();

        if (node.isWildcard()) {
            interp.wildcardJavaImports.add(qualifiedName);
            return NovaNull.UNIT;
        }

        Class<?> clazz = interp.resolveJavaClass(qualifiedName);
        if (clazz == null) {
            throw hirError("Java class not found: " + qualifiedName, node);
        }
        if (!interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
            throw hirError("Cannot access class: " + clazz.getName(), node);
        }
        String simpleName = node.hasAlias() ? node.getAlias() : clazz.getSimpleName();
        interp.environment.defineVal(simpleName, new JavaInterop.NovaJavaClass(clazz));
        return NovaNull.UNIT;
    }

    private NovaValue importStaticHir(HirImport node) {
        String qualifiedName = node.getQualifiedName();
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            throw hirError("Invalid static import: " + qualifiedName, node);
        }
        String className = qualifiedName.substring(0, lastDot);
        String memberName = qualifiedName.substring(lastDot + 1);

        Class<?> clazz = interp.resolveJavaClass(className);
        if (clazz == null) {
            throw hirError("Class not found: " + className, node);
        }

        // 尝试静态字段
        try {
            java.lang.reflect.Field field = clazz.getField(memberName);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                Object value = field.get(null);
                String name = node.hasAlias() ? node.getAlias() : memberName;
                interp.environment.defineVal(name, AbstractNovaValue.fromJava(value));
                return NovaNull.UNIT;
            }
        } catch (NoSuchFieldException e) {
            // 不是字段，尝试方法
        } catch (NovaRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw hirError("Cannot access static field: " + memberName, node);
        }

        // 静态方法
        String name = node.hasAlias() ? node.getAlias() : memberName;
        JavaInterop.NovaJavaClass javaClass = new JavaInterop.NovaJavaClass(clazz);
        interp.environment.defineVal(name, javaClass.getBoundStaticMethod(memberName));
        return NovaNull.UNIT;
    }

    private NovaValue importNovaModuleHir(HirImport node) {
        String qualifiedName = node.getQualifiedName();
        String[] parts = qualifiedName.split("\\.");

        // 检查内置模块（nova.io, nova.json 等）
        String builtinModule = BuiltinModuleRegistry.resolveModuleName(Arrays.asList(parts));
        if (builtinModule != null) {
            BuiltinModuleRegistry.load(builtinModule, interp.environment, interp);
            return NovaNull.UNIT;
        }

        // 先尝试作为 Java 类导入（非 java 前缀但碰巧是 Java 类）
        Class<?> clazz = interp.resolveJavaClass(qualifiedName);
        if (clazz != null) {
            if (!interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                throw hirError("Cannot access class: " + clazz.getName(), node);
            }
            String simpleName = node.hasAlias() ? node.getAlias() : clazz.getSimpleName();
            interp.environment.defineVal(simpleName, new JavaInterop.NovaJavaClass(clazz));
            return NovaNull.UNIT;
        }

        if (interp.moduleLoader == null) {
            throw hirError("Cannot resolve module: " + qualifiedName + " (no module loader)", node);
        }

        List<String> pathParts;
        String symbolName;
        if (node.isWildcard()) {
            pathParts = new ArrayList<>(Arrays.asList(parts));
            symbolName = null;
        } else {
            if (parts.length < 2) {
                throw hirError("Invalid module import: " + qualifiedName, node);
            }
            pathParts = new ArrayList<>();
            for (int i = 0; i < parts.length - 1; i++) {
                pathParts.add(parts[i]);
            }
            symbolName = parts[parts.length - 1];
        }

        java.nio.file.Path modulePath = interp.moduleLoader.resolveModulePath(pathParts);
        if (modulePath == null) {
            throw hirError("Module not found: " + qualifiedName, node);
        }

        Environment moduleEnv = interp.moduleLoader.loadModule(modulePath, interp);
        if (symbolName == null) {
            // 通配符导入
            moduleEnv.exportAll(interp.environment);
        } else {
            // 指定符号导入
            NovaValue value = moduleEnv.tryGet(symbolName);
            if (value == null) {
                throw hirError("Symbol '" + symbolName + "' not found in module", node);
            }
            String localName = node.hasAlias() ? node.getAlias() : symbolName;
            interp.environment.defineVal(localName, value);
        }
        return NovaNull.UNIT;
    }

    // ============ 语句的 visit 方法 (10) ============

    public NovaValue visitBlock(Block node, Void ctx) {
        if (node.isTransparent()) {
            // transparent block: 变量定义在当前作用域（guard-let, destructuring）
            NovaValue result = NovaNull.UNIT;
            for (Statement stmt : node.getStatements()) {
                result = executeHirStmt(stmt);
                if (interp.getHasReturn()) return interp.getReturnValue();
            }
            return result;
        }
        Environment blockEnv = new Environment(interp.environment);
        return interp.executeBlock(node, blockEnv);
    }

    public NovaValue visitBlockExpr(BlockExpr node, Void ctx) {
        for (Statement stmt : node.getStatements()) {
            executeHirStmt(stmt);
            if (interp.getHasReturn()) return interp.getReturnValue();
        }
        return evaluateHir(node.getResult());
    }

    // No @Override: ExpressionStmt is AST type, dispatched via executeHirStmt inline
    public NovaValue visitExprStmt(ExpressionStmt node, Void ctx) {
        return evaluateHir(node.getExpression());
    }

    @Override
    public NovaValue visitDeclStmt(HirDeclStmt node, Void ctx) {
        return executeHirDecl(node.getDeclaration());
    }

    public NovaValue visitIf(IfStmt node, Void ctx) {
        NovaValue condition = evaluateHir(node.getCondition());
        if (condition.isTruthy()) {
            return executeHirStmt(node.getThenBranch());
        } else if (node.hasElse()) {
            return executeHirStmt(node.getElseBranch());
        }
        return NovaNull.UNIT;
    }

    public NovaValue visitConditional(ConditionalExpr node, Void ctx) {
        NovaValue condition = evaluateHir(node.getCondition());
        if (condition.isTruthy()) {
            return evaluateHir(node.getThenExpr());
        } else {
            return evaluateHir(node.getElseExpr());
        }
    }

    @Override
    public NovaValue visitLoop(HirLoop node, Void ctx) {
        String label = node.getLabel();
        if (node.isDoWhile()) {
            do {
                try {
                    Interpreter.HirLoopSignal signal = interp.executeHirLoopBody(node.getBody());
                    if (signal == Interpreter.HirLoopSignal.BREAK) break;
                } catch (ControlFlow cf) {
                    if (label != null && label.equals(cf.getLabel())) {
                        if (cf.getType() == ControlFlow.Type.BREAK) return NovaNull.UNIT;
                        if (cf.getType() == ControlFlow.Type.CONTINUE) continue;
                    }
                    throw cf;
                }
            } while (evaluateHir(node.getCondition()).isTruthy());
        } else {
            while (evaluateHir(node.getCondition()).isTruthy()) {
                try {
                    Interpreter.HirLoopSignal signal = interp.executeHirLoopBody(node.getBody());
                    if (signal == Interpreter.HirLoopSignal.BREAK) break;
                } catch (ControlFlow cf) {
                    if (label != null && label.equals(cf.getLabel())) {
                        if (cf.getType() == ControlFlow.Type.BREAK) return NovaNull.UNIT;
                        if (cf.getType() == ControlFlow.Type.CONTINUE) continue;
                    }
                    throw cf;
                }
            }
        }
        return NovaNull.UNIT;
    }

    public NovaValue visitFor(ForStmt node, Void ctx) {
        NovaValue iterable = evaluateHir(node.getIterable());
        List<String> variables = node.getVariables();
        String label = node.getLabel();

        // NovaRange 特殊优化：内联 Block 体，避免每次迭代创建 blockEnv
        if (iterable instanceof NovaRange) {
            NovaRange range = (NovaRange) iterable;
            int rStart = range.getStart();
            int rLimit = rStart + range.size();
            String varName = variables.size() == 1 ? variables.get(0) : null;
            Environment loopEnv = new Environment(interp.environment);
            Statement body = node.getBody();
            // 内联 Block 体：直接用 loopEnv 执行语句，避免每次迭代创建 blockEnv
            if (body instanceof Block && !((Block) body).isTransparent()) {
                List<Statement> stmts = ((Block) body).getStatements();
                Environment savedEnv = interp.environment;
                interp.environment = loopEnv;
                try {
                    rangeLoop:
                    for (int idx = rStart; idx < rLimit; idx++) {
                        loopEnv.resetForLoop();
                        if (varName != null) {
                            loopEnv.defineValFast(varName, NovaInt.of(idx));
                        } else {
                            bindForStmtVariables(loopEnv, variables, NovaInt.of(idx));
                        }
                        interp.checkLoopLimits();
                        for (Statement stmt : stmts) {
                            try {
                                executeHirStmt(stmt);
                            } catch (ControlFlow cf) {
                                if (cf.getLabel() == null) {
                                    if (cf.getType() == ControlFlow.Type.BREAK) break rangeLoop;
                                    if (cf.getType() == ControlFlow.Type.CONTINUE) continue rangeLoop;
                                }
                                if (label != null && label.equals(cf.getLabel())) {
                                    if (cf.getType() == ControlFlow.Type.BREAK) break rangeLoop;
                                    if (cf.getType() == ControlFlow.Type.CONTINUE) continue rangeLoop;
                                }
                                throw cf;
                            }
                            if (interp.getHasReturn()) break rangeLoop;
                        }
                    }
                } finally {
                    interp.environment = savedEnv;
                }
            } else {
                for (int idx = rStart; idx < rLimit; idx++) {
                    loopEnv.resetForLoop();
                    if (varName != null) {
                        loopEnv.defineValFast(varName, NovaInt.of(idx));
                    } else {
                        bindForStmtVariables(loopEnv, variables, NovaInt.of(idx));
                    }
                    try {
                        Interpreter.HirLoopSignal signal = interp.executeHirLoopBody(loopEnv, body);
                        if (signal == Interpreter.HirLoopSignal.BREAK) break;
                    } catch (ControlFlow cf) {
                        if (label != null && label.equals(cf.getLabel())) {
                            if (cf.getType() == ControlFlow.Type.BREAK) return NovaNull.UNIT;
                            if (cf.getType() == ControlFlow.Type.CONTINUE) continue;
                        }
                        throw cf;
                    }
                }
            }
        }
        // 统一处理所有 Iterable 类型（NovaList, NovaString, NovaMap, NovaArray 等）
        else if (iterable instanceof Iterable) {
            Environment loopEnv = new Environment(interp.environment);
            boolean isMap = iterable instanceof NovaMap;

            @SuppressWarnings("unchecked")
            Iterable<NovaValue> iter = (Iterable<NovaValue>) iterable;
            for (NovaValue element : iter) {
                loopEnv.resetForLoop();

                // NovaMap 特殊处理：element 是 Pair，可以解构为 (key, value)
                if (isMap && variables.size() >= 2 && element instanceof NovaPair) {
                    NovaPair pair = (NovaPair) element;
                    loopEnv.defineValFast(variables.get(0), AbstractNovaValue.fromJava(pair.getFirst()));
                    loopEnv.defineValFast(variables.get(1), AbstractNovaValue.fromJava(pair.getSecond()));
                } else {
                    bindForStmtVariables(loopEnv, variables, element);
                }

                try {
                    Interpreter.HirLoopSignal signal = interp.executeHirLoopBody(loopEnv, node.getBody());
                    if (signal == Interpreter.HirLoopSignal.BREAK) break;
                } catch (ControlFlow cf) {
                    if (label != null && label.equals(cf.getLabel())) {
                        if (cf.getType() == ControlFlow.Type.BREAK) return NovaNull.UNIT;
                        if (cf.getType() == ControlFlow.Type.CONTINUE) continue;
                    }
                    throw cf;
                }
            }
        }
        else {
            throw hirError("Value is not iterable: " + iterable.getTypeName(), node);
        }
        return NovaNull.UNIT;
    }

    private void bindForStmtVariables(Environment env, List<String> variables, NovaValue element) {
        if (variables.size() == 1) {
            env.defineValFast(variables.get(0), element);
        } else {
            // 解构绑定
            for (int i = 0; i < variables.size(); i++) {
                String varName = variables.get(i);
                if (varName != null && !"_".equals(varName)) {
                    NovaValue component = extractComponent(element, i);
                    env.defineValFast(varName, component);
                }
            }
        }
    }

    private NovaValue extractComponent(NovaValue value, int index) {
        if (value instanceof NovaPair) {
            return AbstractNovaValue.fromJava(index == 0 ? ((NovaPair) value).getFirst() : ((NovaPair) value).getSecond());
        }
        if (value instanceof NovaList) {
            NovaList list = (NovaList) value;
            return index < list.size() ? list.get(index) : NovaNull.NULL;
        }
        // 尝试调用 componentN
        String methodName = "component" + (index + 1);
        if (value instanceof NovaObject) {
            NovaObject obj = (NovaObject) value;
            NovaCallable callable = obj.getNovaClass().findCallableMethod(methodName);
            if (callable != null) {
                return new NovaBoundMethod(value, callable).call(interp, Collections.emptyList());
            }
            // @data 类自动生成 componentN
            if (obj.getNovaClass().isData()) {
                List<String> fieldNames = interp.getDataClassFieldNames(obj.getNovaClass());
                if (index < fieldNames.size()) {
                    return obj.getField(fieldNames.get(index));
                }
            }
        }
        return NovaNull.NULL;
    }

    @Override
    public NovaValue visitTry(HirTry node, Void ctx) {
        NovaValue result = NovaNull.UNIT;
        Throwable pendingException = null;
        boolean savedHasReturn = interp.getHasReturn();

        try {
            result = executeHirStmt(node.getTryBlock());
        } catch (ControlFlow cf) {
            if (cf.getType() == ControlFlow.Type.THROW) {
                // Nova throw 语句：尝试 catch 匹配
                NovaValue exValue = cf.getValue() != null ? cf.getValue() : NovaNull.NULL;
                pendingException = attemptCatch(node, exValue);
                if (pendingException == null) {
                    result = tryCatchResult;
                }
            } else {
                // break/continue/return：不进 catch，但必须执行 finally
                pendingException = cf;
            }
        } catch (NovaRuntimeException e) {
            // 包装为 NovaExternalObject 保留异常对象
            NovaValue exValue = new NovaExternalObject(e);
            pendingException = attemptCatch(node, exValue);
            if (pendingException == null) {
                result = tryCatchResult;
            }
        } catch (Throwable e) {
            // Java 异常：也尝试 catch 匹配
            NovaValue exValue = new NovaExternalObject(e);
            pendingException = attemptCatch(node, exValue);
            if (pendingException == null) {
                result = tryCatchResult;
            }
        }

        // finally 块：保存/恢复 hasReturn 状态，防止 finally 体内的副作用干扰控制流
        if (node.hasFinally()) {
            boolean returnBeforeFinally = interp.getHasReturn();
            NovaValue returnValueBeforeFinally = interp.getReturnValue();
            interp.setHasReturn(false);
            try {
                executeHirStmt(node.getFinallyBlock());
            } catch (Throwable finallyEx) {
                // finally 异常优先（与 JVM 语义一致）
                throw (finallyEx instanceof RuntimeException)
                    ? (RuntimeException) finallyEx : new RuntimeException(finallyEx);
            }
            // 如果 finally 没有自己的 return，恢复之前的 return 状态
            if (!interp.getHasReturn()) {
                interp.setHasReturn(returnBeforeFinally);
                interp.setReturnValue(returnValueBeforeFinally);
            }
        }

        if (pendingException != null) {
            if (pendingException instanceof RuntimeException) {
                throw (RuntimeException) pendingException;
            }
            throw new RuntimeException(pendingException);
        }
        return result;
    }

    /** catch 匹配结果暂存（避免多返回值） */
    private NovaValue tryCatchResult;

    /**
     * 尝试将异常值与 catch 子句匹配。
     * @return null 表示已被 catch 处理（结果存入 tryCatchResult），非 null 表示未匹配的 pending 异常
     */
    private Throwable attemptCatch(HirTry node, NovaValue exValue) {
        for (HirTry.CatchClause catchClause : node.getCatches()) {
            // 按 exceptionType 匹配
            HirType declaredType = catchClause.getExceptionType();
            if (declaredType != null && declaredType instanceof ClassType) {
                String typeName = ((ClassType) declaredType).getName();
                if (!matchesCatchType(exValue, typeName)) {
                    continue; // 类型不匹配，尝试下一个 catch
                }
            }
            // 类型匹配（或无类型声明 = catch-all）
            Environment catchEnv = new Environment(interp.environment);
            catchEnv.defineVal(catchClause.getParamName(), exValue);
            Environment saved = interp.environment;
            interp.environment = catchEnv;
            try {
                tryCatchResult = executeHirStmt(catchClause.getBody());
                return null; // 已处理
            } catch (ControlFlow cf) {
                throw cf;
            } catch (Throwable catchEx) {
                return catchEx; // catch 体内抛出新异常
            } finally {
                interp.environment = saved;
            }
        }
        // 没有 catch 匹配：将 exValue 转回 Throwable
        if (exValue instanceof NovaExternalObject) {
            Object javaObj = exValue.toJavaValue();
            if (javaObj instanceof Throwable) return (Throwable) javaObj;
        }
        String msg = exValue.asString();
        return new NovaRuntimeException(msg != null ? msg : "Uncaught exception");
    }

    /** 检查异常值是否匹配 catch 声明的类型名 */
    private boolean matchesCatchType(NovaValue exValue, String typeName) {
        // "Exception" 匹配所有异常类型的值
        if ("Exception".equals(typeName)) {
            return isExceptionValue(exValue);
        }
        // 委托给 TypeOps
        return TypeOps.isInstanceOf(exValue, typeName,
                interp::resolveClass, null);
    }

    /** 判断值是否为异常类型 */
    private boolean isExceptionValue(NovaValue value) {
        if (value instanceof NovaExternalObject) {
            return value.toJavaValue() instanceof Throwable;
        }
        // NovaObject 且继承自 Exception 类
        if (value instanceof NovaObject) {
            return TypeOps.isInstanceOf(value, "Exception",
                    interp::resolveClass, null);
        }
        return true; // Nova throw 可以抛出任意值
    }

    public NovaValue visitReturn(ReturnStmt node, Void ctx) {
        NovaValue value = node.hasValue() ? evaluateHir(node.getValue()) : NovaNull.UNIT;
        if (node.getLabel() != null) {
            throw ControlFlow.returnWithLabel(value, node.getLabel());
        }
        // 无标签 return：字段信号替代 ControlFlow 异常
        interp.setHasReturn(true);
        interp.setReturnValue(value);
        return value;
    }

    public NovaValue visitThrow(ThrowStmt node, Void ctx) {
        NovaValue exception = evaluateHir(node.getException());
        String message = exception.asString();
        SourceLocation loc = node.getLocation();
        String sourceLine = getSourceLineInternal(loc.getLine());
        NovaRuntimeException ex = new NovaRuntimeException(message, loc, sourceLine);
        ex.setNovaStackTrace(interp.captureStackTraceString());
        throw ex;
    }

    public NovaValue visitBreak(BreakStmt node) {
        throw node.getLabel() != null ? ControlFlow.breakLoop(node.getLabel()) : ControlFlow.breakLoop();
    }

    public NovaValue visitContinue(ContinueStmt node) {
        throw node.getLabel() != null ? ControlFlow.continueLoop(node.getLabel()) : ControlFlow.continueLoop();
    }

    // ============ 表达式类 visit 方法 (19) ============

    public NovaValue visitLiteral(Literal node, Void ctx) {
        Object value = node.getValue();
        switch (node.getKind()) {
            case INT:    return NovaInt.of(((Number) value).intValue());
            case LONG:   return NovaLong.of(((Number) value).longValue());
            case FLOAT:  return NovaDouble.of(((Number) value).doubleValue());
            case DOUBLE: return NovaDouble.of(((Number) value).doubleValue());
            case STRING: return NovaString.of((String) value);
            case BOOLEAN: return NovaBoolean.of((Boolean) value);
            case CHAR:   return NovaChar.of(value instanceof Character ? (Character) value : String.valueOf(value).charAt(0));
            case NULL:   return NovaNull.NULL;
            default:     return NovaNull.NULL;
        }
    }

    
    public NovaValue visitVarRef(Identifier node, Void ctx) {
        NovaValue value = interp.environment.tryGet(node.getName());
        if (value != null) {
            // 当 this 是内置类型时，隐式 this 成员应优先于全局内置函数
            // 例如：2.run { toString() } 中 toString 应调用 Int 的 toString 而非全局 toString(value)
            if (value instanceof NovaNativeFunction) {
                NovaValue thisVal = interp.environment.tryGet("this");
                if (thisVal != null && !(thisVal instanceof NovaObject) && !(thisVal instanceof NovaEnumEntry)) {
                    NovaValue builtinMethod = interp.resolveBuiltinImplicitThis(thisVal, node.getName());
                    if (builtinMethod != null) {
                        return builtinMethod;
                    }
                }
            }
            return value;
        }

        // 隐式 this 查找
        NovaValue thisVal = interp.environment.tryGet("this");
        if (thisVal instanceof NovaObject) {
            NovaObject obj = (NovaObject) thisVal;
            // 自定义 getter 优先
            HirField gf = interp.findHirFieldWithGetter(obj.getNovaClass().getName(), node.getName());
            if (gf != null) {
                return interp.executeHirCustomGetter(gf, obj);
            }
            if (obj.hasField(node.getName())) {
                return obj.getField(node.getName());
            }
            NovaCallable callable = obj.getNovaClass().findCallableMethod(node.getName());
            if (callable != null) {
                return new NovaBoundMethod(obj, callable);
            }
        }
        if (thisVal instanceof NovaEnumEntry) {
            NovaEnumEntry entry = (NovaEnumEntry) thisVal;
            if (entry.hasField(node.getName())) {
                return entry.getField(node.getName());
            }
            NovaCallable method = entry.getMethod(node.getName());
            if (method != null) {
                return new NovaBoundMethod(entry, method);
            }
        }

        // 内置类型（String/Int/List/Range/Map 等）的隐式 this 成员访问
        if (thisVal != null && !(thisVal instanceof NovaObject) && !(thisVal instanceof NovaEnumEntry)) {
            NovaValue builtinMethod = interp.resolveBuiltinImplicitThis(thisVal, node.getName());
            if (builtinMethod != null) {
                return builtinMethod;
            }
        }

        // Java 通配符导入（带正/负缓存）
        for (String pkg : interp.wildcardJavaImports) {
            String fullName = pkg + "." + node.getName();
            if (interp.typeResolver.classNotFoundCache.contains(fullName)) continue;
            Class<?> cachedClass = interp.typeResolver.resolvedClassCache.get(fullName);
            if (cachedClass != null) {
                if (interp.getSecurityPolicy().isClassAllowed(cachedClass.getName())) {
                    JavaInterop.NovaJavaClass javaClass = new JavaInterop.NovaJavaClass(cachedClass);
                    Environment root = interp.environment;
                    while (root.getParent() != null) root = root.getParent();
                    root.defineVal(node.getName(), javaClass);
                    return javaClass;
                }
                continue;
            }
            try {
                Class<?> clazz = Class.forName(fullName);
                interp.typeResolver.resolvedClassCache.put(fullName, clazz);
                if (interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                    JavaInterop.NovaJavaClass javaClass = new JavaInterop.NovaJavaClass(clazz);
                    Environment root = interp.environment;
                    while (root.getParent() != null) root = root.getParent();
                    root.defineVal(node.getName(), javaClass);
                    return javaClass;
                }
            } catch (ClassNotFoundException e) {
                interp.typeResolver.classNotFoundCache.add(fullName);
            }
        }

        // Java 包路径解析：已知包根（java, javax, com, org, net, io）
        String varName = node.getName();
        if (interp.getSecurityPolicy().isJavaInteropAllowed()
                && ("java".equals(varName) || "javax".equals(varName)
                    || "com".equals(varName) || "org".equals(varName)
                    || "net".equals(varName) || "io".equals(varName))) {
            return new NovaJavaPackage(node.getName());
        }

        throw hirError("Undefined variable: " + node.getName(), node);
    }

        public NovaValue visitThisRef(ThisExpr node, Void ctx) {
        if (node.isSuper()) {
            NovaValue thisVal = interp.environment.tryGet("this");
            if (thisVal instanceof NovaObject) {
                NovaObject obj = (NovaObject) thisVal;
                NovaClass superClass = obj.getNovaClass().getSuperclass();
                Class<?> javaSuperclass = obj.getNovaClass().getJavaSuperclass();
                if (superClass != null || javaSuperclass != null) {
                    return new Interpreter.NovaSuperProxy(obj, superClass, javaSuperclass);
                }
            }
            throw hirError("Cannot use 'super' here", node);
        }
        NovaValue thisVal = interp.environment.tryGet("this");
        if (thisVal != null) return thisVal;
        throw hirError("Cannot use 'this' outside of a class", node);
    }

    public NovaValue visitBinary(BinaryExpr node, Void ctx) {
        // 短路求值
        if (node.getOperator() == BinaryOp.AND) {
            NovaValue left = evaluateHir(node.getLeft());
            if (!left.isTruthy()) return NovaBoolean.FALSE;
            return NovaBoolean.of(evaluateHir(node.getRight()).isTruthy());
        }
        if (node.getOperator() == BinaryOp.OR) {
            NovaValue left = evaluateHir(node.getLeft());
            if (left.isTruthy()) return NovaBoolean.TRUE;
            return NovaBoolean.of(evaluateHir(node.getRight()).isTruthy());
        }

        NovaValue left = evaluateHir(node.getLeft());
        NovaValue right = evaluateHir(node.getRight());

        // Int×Int 快速路径：完全绕过 try-catch 包装和父类 instanceof 级联
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int lv = ((NovaInt) left).getValue();
            int rv = ((NovaInt) right).getValue();
            switch (node.getOperator()) {
                case ADD: return NovaInt.of(lv + rv);
                case SUB: return NovaInt.of(lv - rv);
                case MUL: return NovaInt.of(lv * rv);
                case DIV:
                    if (rv == 0) throw hirError("Division by zero", node);
                    return NovaInt.of(lv / rv);
                case MOD:
                    if (rv == 0) throw hirError("Modulo by zero", node);
                    return NovaInt.of(lv % rv);
                case EQ:  return NovaBoolean.of(lv == rv);
                case NE:  return NovaBoolean.of(lv != rv);
                case LT:  return NovaBoolean.of(lv < rv);
                case GT:  return NovaBoolean.of(lv > rv);
                case LE:  return NovaBoolean.of(lv <= rv);
                case GE:  return NovaBoolean.of(lv >= rv);
                default: break; // REF_EQ, IN, TO 等走通用路径
            }
        }

        // Double×Double 快速路径
        if (left instanceof NovaDouble && right instanceof NovaDouble) {
            double lv = ((NovaDouble) left).getValue();
            double rv = ((NovaDouble) right).getValue();
            switch (node.getOperator()) {
                case ADD: return NovaDouble.of(lv + rv);
                case SUB: return NovaDouble.of(lv - rv);
                case MUL: return NovaDouble.of(lv * rv);
                case DIV:
                    if (rv == 0) throw hirError("Division by zero", node);
                    return NovaDouble.of(lv / rv);
                case MOD:
                    if (rv == 0) throw hirError("Modulo by zero", node);
                    return NovaDouble.of(lv % rv);
                case EQ:  return NovaBoolean.of(lv == rv);
                case NE:  return NovaBoolean.of(lv != rv);
                case LT:  return NovaBoolean.of(lv < rv);
                case GT:  return NovaBoolean.of(lv > rv);
                case LE:  return NovaBoolean.of(lv <= rv);
                case GE:  return NovaBoolean.of(lv >= rv);
                default: break;
            }
        }

        // String + any 快速路径
        if (node.getOperator() == BinaryOp.ADD && left instanceof NovaString) {
            String rs = (right instanceof NovaString) ? ((NovaString) right).getValue() : right.toString();
            return NovaString.of(((NovaString) left).getValue() + rs);
        }

        // NovaObject 运算符重载快速路径：跳过 BinaryOps.add 中的 isString/isList/isNumber 级联
        if (left instanceof NovaObject) {
            String opMethod = BinaryOps.operatorMethodName(node.getOperator().name());
            if (opMethod != null) {
                NovaValue result = BinaryOps.tryOperatorOverload(left, opMethod, interp, right);
                if (result != null) return result;
            }
        }

        try {
            switch (node.getOperator()) {
                case ADD: return BinaryOps.add(left, right, interp);
                case SUB: return BinaryOps.sub(left, right, interp);
                case MUL: return BinaryOps.mul(left, right, interp);
                case DIV: return BinaryOps.div(left, right, interp);
                case MOD: return BinaryOps.mod(left, right, interp);
                case EQ:  return NovaBoolean.of(left.equals(right));
                case NE:  return NovaBoolean.of(!left.equals(right));
                case REF_EQ: return NovaBoolean.of(left.refEquals(right));
                case REF_NE: return NovaBoolean.of(!left.refEquals(right));
                case LT:  return NovaBoolean.of(BinaryOps.compare(left, right, interp) < 0);
                case GT:  return NovaBoolean.of(BinaryOps.compare(left, right, interp) > 0);
                case LE:  return NovaBoolean.of(BinaryOps.compare(left, right, interp) <= 0);
                case GE:  return NovaBoolean.of(BinaryOps.compare(left, right, interp) >= 0);
                case IN:  return NovaBoolean.of(checkContains(right, left));
                case NOT_IN: return NovaBoolean.of(!checkContains(right, left));
                case TO:  return new NovaPair(left, right);
                default:
                    throw hirError("Unknown binary operator: " + node.getOperator(), node);
            }
        } catch (NovaRuntimeException e) {
            throw hirError(e.getMessage(), node);
        }
    }


    private boolean checkContains(NovaValue collection, NovaValue element) {
        if (collection instanceof NovaList) return ((NovaList) collection).contains(element);
        if (collection instanceof NovaRange && element instanceof NovaInt) {
            return ((NovaRange) collection).contains(((NovaInt) element).getValue());
        }
        if (collection instanceof NovaString && element instanceof NovaString) {
            return ((NovaString) collection).getValue().contains(((NovaString) element).getValue());
        }
        if (collection instanceof NovaMap) return ((NovaMap) collection).containsKey(element);
        return false;
    }

    public NovaValue visitUnary(UnaryExpr node, Void ctx) {
        NovaValue operand = evaluateHir(node.getOperand());

        switch (node.getOperator()) {
            case NEG:
                if (operand instanceof NovaInt) return NovaInt.of(-((NovaInt) operand).getValue());
                if (operand instanceof NovaLong) return NovaLong.of(-((NovaLong) operand).getValue());
                if (operand instanceof NovaDouble) return NovaDouble.of(-((NovaDouble) operand).getValue());
                // 运算符重载
                return tryHirOperatorOverload(operand, "unaryMinus", node);

            case POS:
                if (operand instanceof NovaInt || operand instanceof NovaLong || operand instanceof NovaDouble) {
                    return operand;
                }
                return tryHirOperatorOverload(operand, "unaryPlus", node);

            case NOT:
                return NovaBoolean.of(!operand.isTruthy());

            case INC: {
                NovaValue result;
                if (operand instanceof NovaInt) result = NovaInt.of(((NovaInt) operand).getValue() + 1);
                else if (operand instanceof NovaLong) result = NovaLong.of(((NovaLong) operand).getValue() + 1);
                else result = tryHirOperatorOverload(operand, "inc", node);
                // 赋值回变量
                updateHirIncDec(node.getOperand(), result);
                return node.isPrefix() ? result : operand;
            }

            case DEC: {
                NovaValue result;
                if (operand instanceof NovaInt) result = NovaInt.of(((NovaInt) operand).getValue() - 1);
                else if (operand instanceof NovaLong) result = NovaLong.of(((NovaLong) operand).getValue() - 1);
                else result = tryHirOperatorOverload(operand, "dec", node);
                updateHirIncDec(node.getOperand(), result);
                return node.isPrefix() ? result : operand;
            }

            default:
                throw hirError("Unknown unary operator: " + node.getOperator(), node);
        }
    }

    private NovaValue tryHirOperatorOverload(NovaValue target, String methodName, AstNode node, NovaValue... args) {
        NovaCallable method = null;
        if (target instanceof NovaObject) {
            NovaObject obj = (NovaObject) target;
            method = obj.getNovaClass().findCallableMethod(methodName);
            if (method == null) method = obj.getNovaClass().findMethod(methodName);
            if (method != null) {
                return new NovaBoundMethod(obj, method).call(interp, Arrays.asList(args));
            }
        }
        if (target instanceof NovaEnumEntry) {
            NovaEnumEntry entry = (NovaEnumEntry) target;
            NovaCallable m = entry.getMethod(methodName);
            if (m != null) {
                return new NovaBoundMethod(entry, m).call(interp, Arrays.asList(args));
            }
        }
        throw hirError("Operator method '" + methodName + "' not found on " + target.getTypeName(), node);
    }

    private void updateHirIncDec(Expression target, NovaValue newValue) {
        if (target instanceof Identifier) {
            interp.environment.assign(((Identifier) target).getName(), newValue);
        } else if (target instanceof MemberExpr) {
            MemberExpr fa = (MemberExpr) target;
            NovaValue obj = evaluateHir(fa.getTarget());
            if (obj instanceof NovaObject) {
                ((NovaObject) obj).setField(fa.getMember(), newValue);
            }
        }
    }

    @Override
    public NovaValue visitCall(HirCall node, Void ctx) {
        // Pipeline 回退: a |> f 降级为 f(a)，但 f 可能是 a 的方法而非全局函数
        // 当 callee 是 VarRef 且该名称不在环境中时，尝试作为第一个参数的方法调用
        if (node.getCallee() instanceof Identifier && !node.getArgs().isEmpty()) {
            String name = ((Identifier) node.getCallee()).getName();
            if (interp.environment.tryGet(name) == null) {
                // 名称不在环境中，尝试解析为第一个参数的方法
                NovaValue firstArg = evaluateHir(node.getArgs().get(0));
                try {
                    NovaValue method = interp.memberResolver.resolveMemberOnValue(firstArg, name, node);
                    if (method instanceof NovaCallable) {
                        List<NovaValue> remainingArgs = evaluateHirArgs(
                            node.getArgs().subList(1, node.getArgs().size()));
                        if (method instanceof NovaBoundMethod) {
                            return interp.executeBoundMethod((NovaBoundMethod) method, remainingArgs, null);
                        }
                        return ((NovaCallable) method).call(interp, remainingArgs);
                    }
                } catch (NovaRuntimeException e) {
                    // 方法解析也失败，落入正常路径报错
                }
            }
        }

        // 求值 callee（保存/恢复避免嵌套调用干扰）
        boolean savedEvaluatingCallee = interp.evaluatingCallee;
        interp.evaluatingCallee = true;
        NovaValue callee;
        try {
            callee = evaluateHir(node.getCallee());
        } finally {
            interp.evaluatingCallee = savedEvaluatingCallee;
        }

        // 命名参数
        Map<String, NovaValue> namedArgs = null;
        if (node.hasNamedArgs()) {
            namedArgs = new LinkedHashMap<>();
            for (Map.Entry<String, Expression> entry : node.getNamedArgs().entrySet()) {
                namedArgs.put(entry.getKey(), evaluateHir(entry.getValue()));
            }
        }

        if (!(callee instanceof NovaCallable)) {
            // 可能是 NovaClass 构造器调用
            if (callee instanceof NovaClass) {
                List<NovaValue> args = evaluateHirArgs(node.getArgs());
                return interp.instantiate((NovaClass) callee, args, namedArgs);
            }
            throw hirError("Value is not callable: " + callee.getTypeName(), node);
        }

        // 部分应用：检查是否有占位符 _
        boolean hasPlaceholder = false;
        for (Expression argExpr : node.getArgs()) {
            if (argExpr instanceof Identifier && "_".equals(((Identifier) argExpr).getName())) {
                hasPlaceholder = true;
                break;
            }
        }
        if (hasPlaceholder) {
            List<Object> partialArgs = new ArrayList<>();
            for (Expression argExpr : node.getArgs()) {
                if (argExpr instanceof Identifier && "_".equals(((Identifier) argExpr).getName())) {
                    partialArgs.add(NovaPartialApplication.PLACEHOLDER);
                } else {
                    partialArgs.add(evaluateHir(argExpr));
                }
            }
            return new NovaPartialApplication((NovaCallable) callee, partialArgs);
        }

        // reified 类型参数
        List<HirType> typeArgs = node.getTypeArgs();
        if (typeArgs != null && !typeArgs.isEmpty()) {
            interp.pendingHirTypeArgs = typeArgs;
        }

        try {
            List<NovaValue> args = evaluateHirArgsWithSpread(node);
            NovaCallable callable = (NovaCallable) callee;
            if (namedArgs != null && callable.supportsNamedArgs()) {
                return callable.callWithNamed(interp, args, namedArgs);
            }
            if (callee instanceof NovaBoundMethod) {
                return interp.executeBoundMethod((NovaBoundMethod) callee, args, namedArgs);
            }
            return callable.call(interp, args);
        } finally {
            interp.pendingHirTypeArgs = null;
        }
    }

    private List<NovaValue> evaluateHirArgs(List<Expression> argExprs) {
        switch (argExprs.size()) {
            case 0: return Collections.emptyList();
            case 1: return Collections.singletonList(evaluateHir(argExprs.get(0)));
            case 2: return Arrays.asList(evaluateHir(argExprs.get(0)), evaluateHir(argExprs.get(1)));
            case 3: return Arrays.asList(evaluateHir(argExprs.get(0)), evaluateHir(argExprs.get(1)),
                                         evaluateHir(argExprs.get(2)));
            default:
                List<NovaValue> args = new ArrayList<>(argExprs.size());
                for (Expression expr : argExprs) {
                    args.add(evaluateHir(expr));
                }
                return args;
        }
    }

    /**
     * 求值参数列表，展开 spread 参数。
     */
    private List<NovaValue> evaluateHirArgsWithSpread(HirCall node) {
        List<Expression> argExprs = node.getArgs();
        if (!node.hasSpread()) return evaluateHirArgs(argExprs);
        List<NovaValue> result = new ArrayList<>();
        for (int i = 0; i < argExprs.size(); i++) {
            NovaValue val = evaluateHir(argExprs.get(i));
            if (node.isSpread(i) && val instanceof NovaList) {
                NovaList list = (NovaList) val;
                for (int j = 0; j < list.size(); j++) {
                    result.add(list.get(j));
                }
            } else {
                result.add(val);
            }
        }
        return result;
    }

        public NovaValue visitFieldAccess(MemberExpr node, Void ctx) {
        NovaValue obj = evaluateHir(node.getTarget());
        String memberName = node.getMember();

        // 委托给父类的成员解析逻辑（非常复杂，735 行）
        // 通过 resolveMember 统一处理
        return interp.memberResolver.resolveMemberOnValue(obj, memberName, node);
    }
    
    public NovaValue visitIndex(IndexExpr node, Void ctx) {
        NovaValue target = evaluateHir(node.getTarget());
        NovaValue index = evaluateHir(node.getIndex());
        return interp.memberResolver.performIndex(target, index, node);
    }

    public NovaValue visitAssign(AssignExpr node, Void ctx) {
        NovaValue value = evaluateHir(node.getValue());
        Expression target = node.getTarget();

        if (target instanceof Identifier) {
            Identifier ref = (Identifier) target;
            if (ref.isResolved()) {
                interp.environment.assignAtSlot(ref.getResolvedDepth(), ref.getResolvedSlot(), value);
            } else {
                String varName = ref.getName();
                // tryAssign 合并 contains + assign 为单次环境链遍历
                if (!interp.environment.tryAssign(varName, value)) {
                    // 隐式 this 字段赋值
                    NovaValue thisVal = interp.environment.tryGet("this");
                    if (thisVal instanceof NovaObject && ((NovaObject) thisVal).hasField(varName)) {
                        NovaObject thisObj = (NovaObject) thisVal;
                        HirField sf = interp.findHirFieldWithSetter(thisObj.getNovaClass().getName(), varName);
                        if (sf != null) {
                            interp.executeHirCustomSetter(sf, thisObj, value);
                        } else {
                            thisObj.setField(varName, value);
                        }
                    } else {
                        throw hirError("Undefined variable: " + varName, node);
                    }
                }
            }
        } else if (target instanceof MemberExpr) {
            MemberExpr fa = (MemberExpr) target;
            NovaValue obj = evaluateHir(fa.getTarget());
            if (obj instanceof NovaObject) {
                NovaObject novaObj = (NovaObject) obj;
                HirField sf = interp.findHirFieldWithSetter(novaObj.getNovaClass().getName(), fa.getMember());
                if (sf != null) {
                    interp.executeHirCustomSetter(sf, novaObj, value);
                } else {
                    novaObj.setField(fa.getMember(), value);
                }
            } else if (obj instanceof NovaMap) {
                ((NovaMap) obj).put(NovaString.of(fa.getMember()), value);
            } else if (obj instanceof NovaExternalObject) {
                ((NovaExternalObject) obj).setField(fa.getMember(), value);
            }
        } else if (target instanceof IndexExpr) {
            IndexExpr idx = (IndexExpr) target;
            NovaValue obj = evaluateHir(idx.getTarget());
            NovaValue index = evaluateHir(idx.getIndex());
            interp.memberResolver.performIndexSet(obj, index, value, idx);
        }
        return value;
    }

    @Override
    public NovaValue visitLambda(HirLambda node, Void ctx) {
        Set<String> freeVars = new HashSet<>();
        collectHirVarRefs(node.getBody(), freeVars);
        return new HirLambdaValue(node, createHirMinimalClosure(freeVars));
    }

    /**
     * 创建最小闭包环境，只包含 freeVars 中实际引用的变量。
     * 如果任何捕获变量。var（可变），回退到原始 interp.environment 以保持共享可变状态语义。
     */
    private Environment createHirMinimalClosure(Set<String> freeVars) {
        for (String name : freeVars) {
            if ("this".equals(name)) continue;
            NovaValue value = interp.environment.tryGet(name);
            if (value != null) {
                try {
                    if (!interp.environment.isVal(name)) {
                        return interp.environment;
                    }
                } catch (NovaRuntimeException e) {
                    return interp.environment;
                }
            }
        }
        Environment minimal = new Environment(interp.getGlobals());
        NovaValue thisVal = interp.environment.tryGet("this");
        if (thisVal != null) {
            minimal.defineVal("this", thisVal);
        }
        for (String name : freeVars) {
            if ("this".equals(name)) continue;
            NovaValue value = interp.environment.tryGet(name);
            if (value != null) {
                minimal.defineVal(name, value);
            }
        }
        return minimal;
    }

    /**
     * 递归收集 HIR 节点中所有 Identifier 名称（不递归进入嵌套 HirLambda body）。
     */
    private static void collectHirVarRefs(AstNode node, Set<String> refs) {
        if (node == null) return;
        if (node instanceof Identifier) {
            refs.add(((Identifier) node).getName());
        } else if (node instanceof HirLambda) {
            // 不递归进入嵌套 lambda body
            for (HirParam param : ((HirLambda) node).getParams()) {
                if (param.getDefaultValue() != null) {
                    collectHirVarRefs(param.getDefaultValue(), refs);
                }
            }
        } else if (node instanceof BinaryExpr) {
            collectHirVarRefs(((BinaryExpr) node).getLeft(), refs);
            collectHirVarRefs(((BinaryExpr) node).getRight(), refs);
        } else if (node instanceof UnaryExpr) {
            collectHirVarRefs(((UnaryExpr) node).getOperand(), refs);
        } else if (node instanceof HirCall) {
            collectHirVarRefs(((HirCall) node).getCallee(), refs);
            for (Expression arg : ((HirCall) node).getArgs()) collectHirVarRefs(arg, refs);
        } else if (node instanceof MemberExpr) {
            collectHirVarRefs(((MemberExpr) node).getTarget(), refs);
        } else if (node instanceof IndexExpr) {
            collectHirVarRefs(((IndexExpr) node).getTarget(), refs);
            collectHirVarRefs(((IndexExpr) node).getIndex(), refs);
        } else if (node instanceof AssignExpr) {
            collectHirVarRefs(((AssignExpr) node).getTarget(), refs);
            collectHirVarRefs(((AssignExpr) node).getValue(), refs);
        } else if (node instanceof Block) {
            for (Statement stmt : ((Block) node).getStatements()) collectHirVarRefs(stmt, refs);
        } else if (node instanceof ExpressionStmt) {
            collectHirVarRefs(((ExpressionStmt) node).getExpression(), refs);
        } else if (node instanceof HirDeclStmt) {
            HirDecl decl = ((HirDeclStmt) node).getDeclaration();
            if (decl instanceof HirField && ((HirField) decl).hasInitializer()) {
                collectHirVarRefs(((HirField) decl).getInitializer(), refs);
            }
        } else if (node instanceof ReturnStmt) {
            collectHirVarRefs(((ReturnStmt) node).getValue(), refs);
        } else if (node instanceof IfStmt) {
            collectHirVarRefs(((IfStmt) node).getCondition(), refs);
            collectHirVarRefs(((IfStmt) node).getThenBranch(), refs);
            collectHirVarRefs(((IfStmt) node).getElseBranch(), refs);
        } else if (node instanceof ConditionalExpr) {
            collectHirVarRefs(((ConditionalExpr) node).getCondition(), refs);
            collectHirVarRefs(((ConditionalExpr) node).getThenExpr(), refs);
            collectHirVarRefs(((ConditionalExpr) node).getElseExpr(), refs);
        } else if (node instanceof BlockExpr) {
            for (Statement stmt : ((BlockExpr) node).getStatements()) collectHirVarRefs(stmt, refs);
            collectHirVarRefs(((BlockExpr) node).getResult(), refs);
        } else if (node instanceof TypeCheckExpr) {
            collectHirVarRefs(((TypeCheckExpr) node).getOperand(), refs);
        } else if (node instanceof TypeCastExpr) {
            collectHirVarRefs(((TypeCastExpr) node).getOperand(), refs);
        } else if (node instanceof NotNullExpr) {
            collectHirVarRefs(((NotNullExpr) node).getOperand(), refs);
        } else if (node instanceof ForStmt) {
            collectHirVarRefs(((ForStmt) node).getIterable(), refs);
            collectHirVarRefs(((ForStmt) node).getBody(), refs);
        } else if (node instanceof HirLoop) {
            collectHirVarRefs(((HirLoop) node).getCondition(), refs);
            collectHirVarRefs(((HirLoop) node).getBody(), refs);
        } else if (node instanceof ThrowStmt) {
            collectHirVarRefs(((ThrowStmt) node).getException(), refs);
        } else if (node instanceof HirTry) {
            HirTry ht = (HirTry) node;
            collectHirVarRefs(ht.getTryBlock(), refs);
            for (HirTry.CatchClause cc : ht.getCatches()) collectHirVarRefs(cc.getBody(), refs);
            collectHirVarRefs(ht.getFinallyBlock(), refs);
        } else if (node instanceof RangeExpr) {
            collectHirVarRefs(((RangeExpr) node).getStart(), refs);
            collectHirVarRefs(((RangeExpr) node).getEnd(), refs);
        } else if (node instanceof HirCollectionLiteral) {
            for (Expression e : ((HirCollectionLiteral) node).getElements()) collectHirVarRefs(e, refs);
        } else if (node instanceof ErrorPropagationExpr) {
            collectHirVarRefs(((ErrorPropagationExpr) node).getOperand(), refs);
        } else if (node instanceof AwaitExpr) {
            collectHirVarRefs(((AwaitExpr) node).getOperand(), refs);
        } else if (node instanceof HirNew) {
            for (Expression arg : ((HirNew) node).getArgs()) collectHirVarRefs(arg, refs);
        } else if (node instanceof MethodRefExpr) {
            collectHirVarRefs(((MethodRefExpr) node).getTarget(), refs);
        } else if (node instanceof HirObjectLiteral) {
            for (Expression arg : ((HirObjectLiteral) node).getSuperConstructorArgs()) collectHirVarRefs(arg, refs);
        }
        // Literal, ThisExpr, BreakStmt, ContinueStmt: 叶节点，无需处理
    }

        public NovaValue visitTypeCheck(TypeCheckExpr node, Void ctx) {
        NovaValue operand = evaluateHir(node.getOperand());
        String typeName = interp.getHirTypeName(node.getHirTargetType());
        // reified 类型参数解析
        if (typeName != null) {
            NovaValue reified = interp.environment.tryGet("__reified_" + typeName);
            if (reified != null) typeName = reified.asString();
        }
        boolean matches = interp.isValueOfType(operand, typeName != null ? typeName : "");
        return NovaBoolean.of(node.isNegated() ? !matches : matches);
    }

        public NovaValue visitTypeCast(TypeCastExpr node, Void ctx) {
        NovaValue operand = evaluateHir(node.getOperand());
        String typeName = interp.getHirTypeName(node.getHirTargetType());

        // SAM 转换：Lambda/Callable 转 Java 函数式接口
        if (operand instanceof NovaCallable && typeName != null) {
            try {
                Class<?> targetClass = interp.resolveClass(typeName);
                if (targetClass != null && targetClass.isInterface()) {
                        NovaCallable callable = (NovaCallable) operand;
                    Object samProxy = createSamProxy(targetClass, callable);
                    if (samProxy != null) {
                        return new NovaExternalObject(samProxy);
                    }
                    if (node.isSafe()) return NovaNull.NULL;
                }
            } catch (Exception e) {
                if (node.isSafe()) return NovaNull.NULL;
                throw hirError("Cannot cast to " + typeName + ": " + e.getMessage(), node);
            }
        }

        // 安全转换
        if (node.isSafe()) {
            if (interp.isValueOfType(operand, typeName != null ? typeName : "")) {
                return operand;
            }
            return NovaNull.NULL;
        }

        // 强制转换：验证类型兼容性
        if (typeName != null && !typeName.isEmpty()) {
            if (!interp.isValueOfType(operand, typeName)) {
                // 尝试解析目标类型以提供更好的错误信息
                try {
                    Class<?> targetClass = interp.resolveClass(typeName);
                    String operandType = operand.getNovaTypeName();
                    throw hirError("Cannot cast " + operandType + " to " + typeName, node);
                } catch (Exception e) {
                    throw hirError("Cannot cast to " + typeName + ": " + e.getMessage(), node);
                }
            }
        }
        return operand;
    }

    /**
     * 通用 SAM 代理创建（支持 HirLambdaValue 等任意 NovaCallable）
     */
    private Object createSamProxy(Class<?> interfaceClass, NovaCallable callable) {
        java.lang.reflect.Method samMethod = MethodHandleCache.getInstance().getSamMethod(interfaceClass);
        if (samMethod == null) return null;
        return JavaInteropHelper.createSamProxy(interfaceClass, callable, interp);
    }

    
    public NovaValue visitRange(RangeExpr node, Void ctx) {
        NovaValue start = evaluateHir(node.getStart());
        NovaValue end = evaluateHir(node.getEnd());
        int startVal = ((NovaInt) start).getValue();
        int endVal = ((NovaInt) end).getValue();
        return new NovaRange(startVal, endVal, !node.isEndExclusive());
    }

    @Override
    public NovaValue visitCollectionLiteral(HirCollectionLiteral node, Void ctx) {
        switch (node.getKind()) {
            case LIST: {
                List<Expression> elems = node.getElements();
                List<NovaValue> result = new ArrayList<>();
                for (int i = 0; i < elems.size(); i++) {
                    NovaValue val = evaluateHir(elems.get(i));
                    if (node.isSpread(i) && val instanceof NovaList) {
                        for (int j = 0; j < ((NovaList) val).size(); j++) {
                            result.add(((NovaList) val).get(j));
                        }
                    } else {
                        result.add(val);
                    }
                }
                return new NovaList(result);
            }
            case SET: {
                List<NovaValue> elements = evaluateHirArgs(node.getElements());
                // 去重：LinkedHashSet 保持插入顺序
                Set<NovaValue> seen = new LinkedHashSet<>(elements);
                return new NovaList(new ArrayList<>(seen));
            }
            case MAP: {
                NovaMap map = new NovaMap();
                List<Expression> elements = node.getElements();
                // Map 元素按 key, value, key, value 交替存储，或以 Pair 存储
                for (Expression elem : elements) {
                    NovaValue pair = evaluateHir(elem);
                    if (pair instanceof NovaPair) {
                        map.put(AbstractNovaValue.fromJava(((NovaPair) pair).getFirst()), AbstractNovaValue.fromJava(((NovaPair) pair).getSecond()));
                    }
                }
                return map;
            }
            default:
                return new NovaList();
        }
    }

    @Override
    public NovaValue visitObjectLiteral(HirObjectLiteral node, Void ctx) {
        // 匿名对象
        List<NovaInterface> interfaces = new ArrayList<>();
        List<Class<?>> javaInterfacesList = new ArrayList<>();
        Class<?> javaSuperclassRef = null;
        NovaClass novaSuperclass = null;

        for (HirType superType : node.getSuperTypes()) {
            String typeName = interp.getHirTypeName(superType);
            if (typeName != null) {
                NovaValue value = interp.environment.tryGet(typeName);
                if (value instanceof NovaInterface) {
                    interfaces.add((NovaInterface) value);
                } else if (value instanceof NovaClass) {
                    novaSuperclass = (NovaClass) value;
                } else if (value instanceof JavaInterop.NovaJavaClass) {
                    Class<?> javaClass = ((JavaInterop.NovaJavaClass) value).getJavaClass();
                    if (javaClass.isInterface()) javaInterfacesList.add(javaClass);
                    else javaSuperclassRef = javaClass;
                } else {
                    // 回退到 resolveClass 解析 Java 类型
                    Class<?> javaClass = interp.resolveClass(typeName);
                    if (javaClass != null) {
                        if (javaClass.isInterface()) javaInterfacesList.add(javaClass);
                        else javaSuperclassRef = javaClass;
                    }
                }
            }
        }

        // 检查 final 类不可继承
        if (javaSuperclassRef != null && java.lang.reflect.Modifier.isFinal(javaSuperclassRef.getModifiers())) {
            throw hirError("Cannot extend final class: " + javaSuperclassRef.getName(), node);
        }

        NovaClass anonClass = new NovaClass("$anon", novaSuperclass, interp.environment);
        for (NovaInterface iface : interfaces) {
            anonClass.addInterface(iface);
        }
        if (javaSuperclassRef != null) {
            anonClass.setJavaSuperclass(javaSuperclassRef);
        }
        for (Class<?> javaIface : javaInterfacesList) {
            anonClass.addJavaInterface(javaIface);
        }

        NovaObject instance = new NovaObject(anonClass);

        // 处理成员
        Environment objEnv = new Environment(interp.environment);
        objEnv.defineVal("this", instance);
        interp.withEnvironment(objEnv, () -> {
            for (HirDecl member : node.getMembers()) {
                if (member instanceof HirFunction) {
                    HirFunction method = (HirFunction) member;
                    HirFunctionValue func = new HirFunctionValue(method.getName(), method, objEnv);
                    anonClass.addMethod(method.getName(), func);
                } else if (member instanceof HirField) {
                    HirField field = (HirField) member;
                    NovaValue value = field.hasInitializer() ? evaluateHir(field.getInitializer()) : NovaNull.NULL;
                    instance.setField(field.getName(), value);
                }
            }
            return NovaNull.UNIT;
        });

        // 检查是否实现了所有抽象方法
        List<String> unimplemented = anonClass.getUnimplementedMethods();
        if (!unimplemented.isEmpty()) {
            throw hirError("Anonymous object must implement abstract methods: " +
                String.join(", ", unimplemented), node);
        }

        // 创建 Java 委托对象
        if (anonClass.hasJavaSuperTypes()) {
            List<NovaValue> superCtorArgs = Collections.emptyList();
            // 检查是否有超类型构造器参数
            if (node.getSuperConstructorArgs() != null && !node.getSuperConstructorArgs().isEmpty()) {
                superCtorArgs = new ArrayList<>();
                for (Expression arg : node.getSuperConstructorArgs()) {
                    superCtorArgs.add(evaluateHir(arg));
                }
            }
            Object delegate = interp.createJavaDelegate(instance, anonClass, superCtorArgs);
            instance.setJavaDelegate(delegate);
        }

        return instance;
    }

    
    public NovaValue visitAwait(AwaitExpr node, Void ctx) {
        NovaValue operand = evaluateHir(node.getOperand());
        // NovaFuture → 阻塞等待结果
        if (operand instanceof NovaFuture) {
            return ((NovaFuture) operand).get(interp);
        }
        // Java Future（通过 Java 互操作获得的）
        if (operand instanceof NovaExternalObject) {
            Object javaObj = ((NovaExternalObject) operand).getJavaObject();
            if (javaObj instanceof java.util.concurrent.Future) {
                try {
                    Object result = ((java.util.concurrent.Future<?>) javaObj).get();
                    return AbstractNovaValue.fromJava(result);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw hirError("await failed: " + e.getCause().getMessage(), node);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw hirError("await interrupted", node);
                }
            }
        }
        // Lambda/函数 → 直接调用
        if (operand instanceof NovaCallable) {
            return ((NovaCallable) operand).call(interp, Collections.emptyList());
        }
        return operand;
    }

    
    public NovaValue visitNullCheck(NotNullExpr node, Void ctx) {
        NovaValue operand = evaluateHir(node.getOperand());
        if (operand.isNull()) {
            throw hirError("Null assertion failed: value is null", node);
        }
        return operand;
    }

    
    public NovaValue visitErrorPropagation(ErrorPropagationExpr node, Void ctx) {
        NovaValue value = evaluateHir(node.getOperand());
        // Result 类型：Err 提前返回 Err，Ok 提取 value
        if (value instanceof NovaResult) {
            NovaResult result = (NovaResult) value;
            if (result.isErr()) {
                throw ControlFlow.returnValue(result);
            }
            return result.getInner();
        }
        // 可空类型：null 提前返回 null
        if (value.isNull()) {
            throw ControlFlow.returnValue(NovaNull.NULL);
        }
        return value;
    }

        public NovaValue visitMethodRef(MethodRefExpr node, Void ctx) {
        if (node.hasTarget()) {
            // T::class → reified 类型参数的类名引用
            if ("class".equals(node.getMethodName()) && node.getTarget() instanceof Identifier) {
                String varName = ((Identifier) node.getTarget()).getName();
                NovaValue reified = interp.environment.tryGet("__reified_" + varName);
                if (reified != null) return reified;
            }

            NovaValue target = evaluateHir(node.getTarget());

            if (node.isConstructor()) {
                // Type::new
                if (target instanceof NovaClass) return target;
                throw hirError("Constructor reference requires a class", node);
            }

            // obj::method
            String methodName = node.getMethodName();
            if (target instanceof NovaObject) {
                NovaCallable method = ((NovaObject) target).getNovaClass().findCallableMethod(methodName);
                if (method != null) return new NovaBoundMethod(target, method);
            }
            NovaCallable ext = interp.findExtension(target, methodName);
            if (ext != null) return new NovaBoundMethod(target, ext);
            throw hirError("Method '" + methodName + "' not found on " + target.getTypeName(), node);
        }

        // ::funcName（全局函数引用）
        NovaValue func = interp.environment.tryGet(node.getMethodName());
        if (func instanceof NovaCallable) return func;
        throw hirError("Function '" + node.getMethodName() + "' not found", node);
    }

    @Override
    public NovaValue visitNew(HirNew node, Void ctx) {
        NovaValue classValue = interp.environment.tryGet(node.getClassName());
        if (classValue instanceof NovaClass) {
            List<NovaValue> args = evaluateHirArgs(node.getArgs());
            return interp.instantiate((NovaClass) classValue, args, null);
        }
        throw hirError("Class not found: " + node.getClassName(), node);
    }

}
