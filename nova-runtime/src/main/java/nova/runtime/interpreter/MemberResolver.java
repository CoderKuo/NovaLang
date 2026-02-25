package nova.runtime.interpreter;

import nova.runtime.*;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.compiler.hirtype.*;
import nova.runtime.interpreter.reflect.*;
import nova.runtime.stdlib.StdlibRegistry;

import java.util.*;

/**
 * 成员解析辅助类，从 Interpreter 提取的成员查找逻辑。
 */
final class MemberResolver {

    static {
        StdlibRegistry.registerExtensionMethods(RangeExtensions.class);
        StdlibRegistry.registerExtensionMethods(PairExtensions.class);
        StdlibRegistry.registerExtensionMethods(ArrayExtensions.class);
    }

    final Interpreter interp;

    MemberResolver(Interpreter interp) {
        this.interp = interp;
    }

    // ============ 共用辅助方法 ============

    /**
     * 内置类型（String/Int/List/Range/Map 等）的隐式 this 成员解析。
     * 用于 run/apply 等作用域函数中，this 绑定到内置类型后支持直接调用成员方法。
     */
    NovaValue resolveBuiltinImplicitThis(NovaValue thisVal, String name) {
        // 复用 getMemberMethod 统一查找内置方法（String/List/Map/Range + 通用方法）
        NovaValue method = interp.getMemberMethod(thisVal, name);
        if (method != null) {
            // 零参数内置方法作为属性使用（如 length、isEmpty）：自动调用返回值
            // 但在 callee 位置（如 size()）时不自动调用，让 visitCallExpr 处理
            if (method instanceof NovaNativeFunction && ((NovaNativeFunction) method).getArity() == 0
                    && !interp.evaluatingCallee) {
                return ((NovaNativeFunction) method).call(interp, Collections.emptyList());
            }
            return method;
        }
        // NovaExternalObject: 通过 Java 反射查找方法（用于 buildString 等接收者 lambda）
        if (thisVal instanceof NovaExternalObject) {
            NovaExternalObject ext = (NovaExternalObject) thisVal;
            if (ext.hasMethod(name)) {
                return ext.getBoundMethod(name);
            }
        }
        // 查找扩展函数
        NovaCallable extFunc = interp.findNovaExtension(thisVal, name);
        if (extFunc != null) {
            return new NovaBoundMethod(thisVal, extFunc);
        }
        // 查找扩展属性
        ExtensionRegistry.ExtensionProperty extProp = interp.findNovaExtensionProperty(thisVal, name);
        if (extProp != null) {
            return interp.executeExtensionPropertyGetter(extProp, thisVal);
        }
        return null;
    }

    NovaValue resolveCallableInScope(NovaValue receiver, String name) {
        NovaValue val = interp.environment.tryGet(name);
        if (val instanceof NovaCallable) {
            return new NovaBoundMethod(receiver, (NovaCallable) val);
        }
        // MIR lambda: NovaObject with invoke method
        if (val instanceof NovaObject) {
            NovaBoundMethod bound = ((NovaObject) val).getBoundMethod("invoke");
            if (bound != null) {
                return new NovaBoundMethod(receiver, bound);
            }
        }
        return null;
    }

    // ============ HIR 路径方法 ============

    /**
     * 统一的成员解析，复用 Interpreter 的大量逻辑。
     */
    NovaValue resolveMemberOnValue(NovaValue obj, String memberName, AstNode node) {
        if (obj instanceof NovaObject) {
            NovaValue r = resolveObjectMember((NovaObject) obj, memberName, node);
            if (r != null) return r;
        }
        if (obj instanceof NovaClass) {
            NovaValue r = resolveClassMember((NovaClass) obj, memberName);
            if (r != null) return r;
        }
        if (obj instanceof NovaEnum) {
            NovaValue r = resolveEnumMember((NovaEnum) obj, memberName);
            if (r != null) return r;
        }
        if (obj instanceof NovaEnumEntry) {
            NovaValue r = resolveEnumEntryMember((NovaEnumEntry) obj, memberName);
            if (r != null) return r;
        }
        if (obj instanceof NovaBuilder) return resolveHirBuilderMember((NovaBuilder) obj, memberName, node);
        { NovaValue d = MemberDispatcher.dispatch(obj, memberName, interp); if (d != null) return d; }
        if (obj instanceof NovaExternalObject) return resolveMemberOnExternal((NovaExternalObject) obj, memberName, node);
        if (obj instanceof JavaInterop.NovaJavaClass) return ((JavaInterop.NovaJavaClass) obj).getStaticField(memberName);
        if (obj instanceof NovaJavaPackage) {
            String fullPath = ((NovaJavaPackage) obj).getPath() + "." + memberName;
            Class<?> clazz = interp.resolveJavaClass(fullPath);
            return clazz != null ? new JavaInterop.NovaJavaClass(clazz) : new NovaJavaPackage(fullPath);
        }
        // 结构化并发类型
        if (obj instanceof NovaScope) {
            NovaScope s = (NovaScope) obj;
            switch (memberName) {
                case "isActive":    return NovaBoolean.of(s.isActive());
                case "isCancelled": return NovaBoolean.of(s.isCancelled());
                case "async":  return new NovaNativeFunction("async", 1, (i, a) -> s.async(i.asCallable(a.get(0), "async")));
                case "launch": return new NovaNativeFunction("launch", 1, (i, a) -> s.launch(i.asCallable(a.get(0), "launch")));
                case "cancel": return NovaNativeFunction.create("cancel", () -> { s.cancel(); return NovaNull.UNIT; });
            }
        }
        if (obj instanceof NovaDeferred) {
            NovaDeferred d = (NovaDeferred) obj;
            switch (memberName) {
                case "isDone":      return NovaBoolean.of(d.isDone());
                case "isCancelled": return NovaBoolean.of(d.isCancelled());
                case "await": return new NovaNativeFunction("await", 0, (i, a) -> d.await(i));
                case "get":   return new NovaNativeFunction("get", 0, (i, a) -> d.await(i));
                case "cancel": return NovaNativeFunction.create("cancel", () -> NovaBoolean.of(d.cancel()));
            }
        }
        if (obj instanceof NovaJob) {
            NovaJob j = (NovaJob) obj;
            switch (memberName) {
                case "isActive":    return NovaBoolean.of(j.isActive());
                case "isCompleted": return NovaBoolean.of(j.isCompleted());
                case "isCancelled": return NovaBoolean.of(j.isCancelled());
                case "join":   return new NovaNativeFunction("join", 0, (i, a) -> { j.join(); return NovaNull.UNIT; });
                case "cancel": return NovaNativeFunction.create("cancel", () -> NovaBoolean.of(j.cancel()));
            }
        }
        if (obj instanceof NovaTask) {
            NovaTask t = (NovaTask) obj;
            switch (memberName) {
                case "isCancelled": return NovaBoolean.of(t.isCancelled());
                case "cancel": return new NovaNativeFunction("cancel", 0, (i, a) -> { t.cancel(); return NovaNull.UNIT; });
            }
        }
        NovaValue builtinResult = resolveBuiltinMember(obj, memberName);
        if (builtinResult != null) return builtinResult;
        if (obj instanceof NovaResult) {
            NovaValue m = resolveResultMember((NovaResult) obj, memberName);
            if (m != null) return m;
        }
        if (obj instanceof Interpreter.NovaSuperProxy) {
            return resolveSuperProxyMember((Interpreter.NovaSuperProxy) obj, memberName, node);
        }
        if (obj instanceof NovaClassInfo) return resolveHirClassInfoMember((NovaClassInfo) obj, memberName, node);
        if (obj instanceof NovaFieldInfo) return resolveHirFieldInfoMember((NovaFieldInfo) obj, memberName, node);
        if (obj instanceof NovaMethodInfo) return resolveHirMethodInfoMember((NovaMethodInfo) obj, memberName, node);
        if (obj instanceof NovaParamInfo) return resolveHirParamInfoMember((NovaParamInfo) obj, memberName);
        if (obj instanceof NovaAnnotationInfo) return resolveHirAnnotationInfoMember((NovaAnnotationInfo) obj, memberName);
        return resolveExtensionFallback(obj, memberName, node);
    }

    private NovaValue resolveObjectMember(NovaObject novaObj, String memberName, AstNode node) {
        if (novaObj.hasField(memberName)) {
            if (!interp.evaluatingCallee && !interp.customGetterCache.containsKey(novaObj.getNovaClass().getName())
                    && novaObj.getNovaClass().isFieldAccessibleFrom(memberName, interp.getCurrentClass())) {
                return novaObj.getField(memberName);
            }
            if (interp.evaluatingCallee) {
                NovaCallable method = novaObj.getNovaClass().findCallableMethod(memberName);
                if (method != null) return new NovaBoundMethod(novaObj, method);
            }
            NovaClass objClass = novaObj.getNovaClass();
            if (!objClass.isFieldAccessibleFrom(memberName, interp.getCurrentClass())) {
                throw interp.hirError("Cannot access private field '" + memberName + "'", node);
            }
            HirField gf = interp.findHirFieldWithGetter(objClass.getName(), memberName);
            if (gf != null) return interp.executeHirCustomGetter(gf, novaObj);
            return novaObj.getField(memberName);
        }
        // getter-only 属性
        HirField gf = interp.findHirFieldWithGetter(novaObj.getNovaClass().getName(), memberName);
        if (gf != null) return interp.executeHirCustomGetter(gf, novaObj);
        // 方法查找（含接口默认方法）
        NovaCallable method = novaObj.getNovaClass().findCallableMethod(memberName);
        if (method == null) {
            for (NovaInterface iface : novaObj.getNovaClass().getInterfaces()) {
                NovaCallable defMethod = iface.getDefaultMethod(memberName);
                if (defMethod != null) { method = defMethod; break; }
            }
        }
        if (method != null) {
            if (!novaObj.getNovaClass().isMethodAccessibleFrom(memberName, interp.getCurrentClass())) {
                throw interp.hirError("Cannot access private method '" + memberName + "'", node);
            }
            if (!interp.evaluatingCallee && method.getArity() == 0) {
                return new NovaBoundMethod(novaObj, method).call(interp, Collections.emptyList());
            }
            return new NovaBoundMethod(novaObj, method);
        }
        // data class 成员
        if (novaObj.getNovaClass().isData()) {
            if ("copy".equals(memberName)) return createDataCopyFunction(novaObj);
            if (memberName.startsWith("component") && memberName.length() > 9) {
                try {
                    int n = Integer.parseInt(memberName.substring(9));
                    List<String> fieldNames = getDataClassFieldNames(novaObj.getNovaClass());
                    if (n >= 1 && n <= fieldNames.size()) {
                        NovaValue fieldValue = novaObj.getField(fieldNames.get(n - 1));
                        return NovaNativeFunction.create(memberName, () -> fieldValue);
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        // Java 委托回退
        if (novaObj.getJavaDelegate() != null) {
            try {
                NovaExternalObject ext = new NovaExternalObject(novaObj.getJavaDelegate());
                return resolveMemberOnExternal(ext, memberName, null);
            } catch (Exception e) { /* 继续其他查找 */ }
        }
        return null;
    }

    private NovaValue resolveClassMember(NovaClass cls, String memberName) {
        NovaValue staticField = cls.getStaticField(memberName);
        if (staticField != null) return staticField;
        if ("annotations".equals(memberName)) return getHirAnnotationsAsList(cls);
        return null;
    }

    private NovaValue resolveEnumMember(NovaEnum novaEnum, String memberName) {
        NovaEnumEntry entry = novaEnum.getEntry(memberName);
        if (entry != null) return entry;
        if ("values".equals(memberName)) return novaEnum.createValuesMethod();
        if ("valueOf".equals(memberName)) {
            return new NovaNativeFunction("valueOf", 1,
                (interpreter, args) -> novaEnum.valueOf(args.get(0).asString()));
        }
        NovaCallable method = novaEnum.getMethod(memberName);
        if (method != null) return (NovaValue) method;
        return null;
    }

    private NovaValue resolveEnumEntryMember(NovaEnumEntry entry, String memberName) {
        if ("name".equals(memberName)) {
            if (interp.evaluatingCallee) {
                return new NovaNativeFunction("name", 0, (interpreter, args) -> NovaString.of(entry.getName()));
            }
            return NovaString.of(entry.getName());
        }
        if ("ordinal".equals(memberName)) {
            if (interp.evaluatingCallee) {
                return new NovaNativeFunction("ordinal", 0, (interpreter, args) -> NovaInt.of(entry.ordinal()));
            }
            return NovaInt.of(entry.ordinal());
        }
        if (entry.hasField(memberName)) return entry.getField(memberName);
        NovaCallable method = entry.getMethod(memberName);
        if (method != null) {
            if (!interp.evaluatingCallee && method.getArity() == 0) {
                return new NovaBoundMethod(entry, method).call(interp, Collections.emptyList());
            }
            return new NovaBoundMethod(entry, method);
        }
        return null;
    }

    private NovaValue resolveSuperProxyMember(Interpreter.NovaSuperProxy proxy, String memberName, AstNode node) {
        if (proxy.getSuperclass() != null) {
            NovaCallable method = proxy.getSuperclass().findCallableMethod(memberName);
            if (method != null) return new NovaBoundMethod(proxy.getInstance(), method);
        }
        if (proxy.getJavaSuperclass() != null && proxy.getInstance().getJavaDelegate() != null) {
            NovaExternalObject ext = new NovaExternalObject(proxy.getInstance().getJavaDelegate());
            return resolveMemberOnExternal(ext, memberName, node);
        }
        throw interp.hirError("Unknown super member: " + memberName, node);
    }

    private NovaValue resolveExtensionFallback(NovaValue obj, String memberName, AstNode node) {
        NovaCallable ext = interp.findExtension(obj, memberName);
        if (ext != null) return new NovaBoundMethod(obj, ext);
        ExtensionRegistry.HirExtProp hirProp = interp.findHirExtensionProperty(obj, memberName);
        if (hirProp != null) return interp.executeHirExtensionPropertyGetter(hirProp, obj);
        ExtensionRegistry.ExtensionProperty extProp = interp.findNovaExtensionProperty(obj, memberName);
        if (extProp != null) return interp.executeExtensionPropertyGetter(extProp, obj);
        NovaValue envCallable = resolveCallableInScope(obj, memberName);
        if (envCallable != null) return envCallable;
        throw interp.hirError("Unknown member '" + memberName + "' on " + obj.getTypeName(), node);
    }

    NovaValue resolveMemberOnExternal(NovaExternalObject extObj, String memberName, AstNode node) {
        // 安全策略：类级 + 方法级检查
        Object javaObj = extObj.toJavaValue();
        if (javaObj != null) {
            String className = javaObj.getClass().getName();
            if (!interp.getSecurityPolicy().isClassAllowed(className)) {
                throw NovaSecurityPolicy.denied(
                        "Cannot access Java class: " + className);
            }
            if (!interp.getSecurityPolicy().isMethodAllowed(className, memberName)) {
                throw NovaSecurityPolicy.denied(
                        "Cannot call method '" + memberName + "' on " + className);
            }
        }

        // 直接方法匹配优先
        boolean hasM = extObj.hasMethod(memberName);
        if (hasM) {
            NovaValue bm = extObj.getBoundMethod(memberName);
            return bm;
        }

        // JavaBean getter 属性访问: .name → getName(), .alive → isAlive()
        if (!memberName.isEmpty()) {
            String cap = Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
            String getterName = "get" + cap;
            if (extObj.hasMethod(getterName)) {
                return extObj.invokeMethod(getterName, Collections.emptyList());
            }
            String isGetterName = "is" + cap;
            if (extObj.hasMethod(isGetterName)) {
                return extObj.invokeMethod(isGetterName, Collections.emptyList());
            }
        }

        // StdlibRegistry 扩展方法 fallback
        NovaValue stdlibResult = tryStdlibFallback(extObj, memberName);
        if (stdlibResult != null) return resolveAndAutoCall(stdlibResult);

        // 字段访问
        try {
            return extObj.getField(memberName);
        } catch (NovaRuntimeException e) {
            return extObj.getBoundMethod(memberName);
        }
    }

    /**
     * 零参数可调用值的自动调用辅助方法：
     * 如果方法是零参数可调用值且当前不在 callee 求值上下文中，则自动调用；
     * 否则返回方法本身。
     */
    private NovaValue resolveAndAutoCall(NovaValue method) {
        if (method == null) return null;
        if (!interp.evaluatingCallee && method instanceof NovaCallable && ((NovaCallable) method).getArity() == 0) {
            return ((NovaCallable) method).call(interp, Collections.emptyList());
        }
        return method;
    }

    /**
     * HIR 版内置类型成员解析（2 个参数版本）
     */
    NovaValue resolveBuiltinMember(NovaValue obj, String memberName) {
        // 扩展函数优先于内置方法（允许遮蔽）
        NovaCallable ext = interp.findExtension(obj, memberName);
        if (ext != null) return new NovaBoundMethod(obj, ext);

        // Map: 键查找逻辑独立保留
        if (obj instanceof NovaMap) {
            NovaMap map = (NovaMap) obj;
            NovaValue key = NovaString.of(memberName);
            if (map.containsKey(key)) return map.get(key);
        }
        // componentN 解构支持（List/Pair）
        if (memberName.startsWith("component") && memberName.length() > 9) {
            try {
                int n = Integer.parseInt(memberName.substring(9));
                if (n >= 1) {
                    if (obj instanceof NovaList) {
                        NovaList list = (NovaList) obj;
                        if (n <= list.size()) {
                            NovaValue val = list.get(n - 1);
                            return NovaNativeFunction.create(memberName, () -> val);
                        }
                    }
                    if (obj instanceof NovaPair) {
                        NovaPair pair = (NovaPair) obj;
                        if (n == 1) return NovaNativeFunction.create(memberName, () -> NovaValue.fromJava(pair.getFirst()));
                        if (n == 2) return NovaNativeFunction.create(memberName, () -> NovaValue.fromJava(pair.getSecond()));
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
        // 扩展属性 fallback
        ExtensionRegistry.ExtensionProperty extProp = interp.findNovaExtensionProperty(obj, memberName);
        if (extProp != null) return interp.executeExtensionPropertyGetter(extProp, obj);
        // StdlibRegistry 扩展方法 fallback
        NovaValue stdlibResult = tryStdlibFallback(obj, memberName);
        if (stdlibResult != null) return resolveAndAutoCall(stdlibResult);
        return null;
    }

    /** StdlibRegistry 扩展方法 fallback，供 getMemberMethod 等辅助路径使用 */
    NovaValue tryStdlibFallback(NovaValue obj, String memberName) {
        // 1. Java 类型查找
        Class<?> javaClass = getJavaClass(obj);
        if (javaClass != null) {
            StdlibRegistry.ExtensionMethodInfo extMethod =
                    StdlibRegistry.findExtensionMethod(javaClass, memberName, -1);
            if (extMethod != null) {
                // 检查是否有重载：有重载时创建动态分派包装
                List<StdlibRegistry.ExtensionMethodInfo> overloads =
                        StdlibRegistry.getExtensionMethodOverloads(extMethod.targetType, memberName);
                if (overloads.size() > 1 && !extMethod.isProperty) {
                    return createDynamicDispatcher(obj, javaClass, null, memberName, false);
                }
                return wrapStdlibExtensionMethod(obj, extMethod, memberName);
            }
        }
        // 2. Nova 内部类型查找
        String internalType = getInternalTypeName(obj);
        if (internalType != null) {
            StdlibRegistry.ExtensionMethodInfo extMethod =
                    StdlibRegistry.getExtensionMethod(internalType, memberName, -1);
            if (extMethod != null) {
                List<StdlibRegistry.ExtensionMethodInfo> overloads =
                        StdlibRegistry.getExtensionMethodOverloads(internalType, memberName);
                if (overloads.size() > 1 && !extMethod.isProperty) {
                    return createDynamicDispatcher(obj, null, internalType, memberName, true);
                }
                return wrapInternalExtensionMethod(obj, extMethod, memberName);
            }
        }
        return null;
    }

    /** 为存在重载的扩展方法创建动态分派包装（在调用时根据实际参数数量选择正确重载） */
    private NovaValue createDynamicDispatcher(NovaValue receiver, Class<?> javaClass,
                                               String internalType, String name, boolean rawReceiver) {
        // 创建时预解析所有重载，避免调用时重复查找
        String targetType = javaClass != null
                ? StdlibRegistry.findExtensionMethod(javaClass, name, -1).targetType
                : internalType;
        List<StdlibRegistry.ExtensionMethodInfo> overloads =
                StdlibRegistry.getExtensionMethodOverloads(targetType, name);

        return new NovaNativeFunction(name, -1, (interp2, args) -> {
            StdlibRegistry.ExtensionMethodInfo method = null;
            for (StdlibRegistry.ExtensionMethodInfo info : overloads) {
                if (info.arity == args.size()) { method = info; break; }
                if (info.isVarargs && method == null) method = info;
            }
            if (method == null) {
                throw new NovaRuntimeException("No matching overload for '" + name + "' with " + args.size() + " argument(s)");
            }
            Object[] fullArgs = new Object[args.size() + 1];
            fullArgs[0] = rawReceiver ? receiver : toShallowJavaValue(receiver);
            for (int i = 0; i < args.size(); i++) {
                NovaValue arg = args.get(i);
                fullArgs[i + 1] = argToJava(interp2, arg);
            }
            Object result = method.impl.apply(fullArgs);
            return NovaValue.fromJava(result);
        });
    }

    /** 获取 NovaValue 对应的 Java Class，用于 StdlibRegistry 查找 */
    private Class<?> getJavaClass(NovaValue obj) {
        if (obj instanceof NovaString) return String.class;
        if (obj instanceof NovaList) return java.util.List.class;
        if (obj instanceof NovaMap) return java.util.Map.class;
        if (obj instanceof NovaChar) return Character.class;
        if (obj instanceof NovaInt) return Integer.class;
        if (obj instanceof NovaLong) return Long.class;
        if (obj instanceof NovaDouble) return Double.class;
        if (obj instanceof NovaFloat) return Float.class;
        if (obj instanceof NovaExternalObject) return ((NovaExternalObject) obj).toJavaValue().getClass();
        return null;
    }

    /** 获取 Nova 内部类型（无 Java 对应类型）的注册键 */
    private String getInternalTypeName(NovaValue obj) {
        if (obj instanceof NovaRange) return "nova/Range";
        if (obj instanceof NovaPair) return "nova/Pair";
        if (obj instanceof NovaArray) return "nova/Array";
        return null;
    }

    /** 将 StdlibRegistry 扩展方法包装为 NovaNativeFunction */
    private NovaValue wrapStdlibExtensionMethod(NovaValue receiver, StdlibRegistry.ExtensionMethodInfo extMethod, String name) {
        return wrapExtensionMethod(receiver, extMethod, name, false);
    }

    /** 将内部类型扩展方法包装为 NovaNativeFunction（receiver 不做 toJavaValue 转换） */
    private NovaValue wrapInternalExtensionMethod(NovaValue receiver, StdlibRegistry.ExtensionMethodInfo extMethod, String name) {
        return wrapExtensionMethod(receiver, extMethod, name, true);
    }

    private NovaValue wrapExtensionMethod(NovaValue receiver, StdlibRegistry.ExtensionMethodInfo extMethod, String name, boolean rawReceiver) {
        // 属性：直接求值返回
        if (extMethod.isProperty) {
            Object[] fullArgs = new Object[]{ rawReceiver ? receiver : toShallowJavaValue(receiver) };
            Object result = extMethod.impl.apply(fullArgs);
            return NovaValue.fromJava(result);
        }
        // 方法：包装为 NovaNativeFunction
        return new NovaNativeFunction(name, extMethod.arity, (interp2, args) -> {
            Object[] fullArgs = new Object[args.size() + 1];
            fullArgs[0] = rawReceiver ? receiver : toShallowJavaValue(receiver);
            for (int i = 0; i < args.size(); i++) {
                NovaValue arg = args.get(i);
                fullArgs[i + 1] = argToJava(interp2, arg);
            }
            Object result = extMethod.impl.apply(fullArgs);
            return NovaValue.fromJava(result);
        });
    }

    /**
     * 浅转换：NovaList/NovaMap 返回 View（委托到原始对象，可变操作穿透），
     * 其他类型正常 toJavaValue 转换。
     */
    private Object toShallowJavaValue(NovaValue receiver) {
        if (receiver instanceof NovaList) {
            return new NovaListView((NovaList) receiver);
        }
        if (receiver instanceof NovaMap) {
            return new NovaMapView((NovaMap) receiver);
        }
        return receiver.toJavaValue();
    }

    /** 将 StdlibRegistry 方法参数从 NovaValue 转为 Java 值（保持 NovaObject/NovaPair 身份） */
    private Object argToJava(Interpreter interp2, NovaValue arg) {
        if (arg instanceof NovaCallable) {
            return wrapCallableAsFunction(interp2, (NovaCallable) arg);
        }
        // MIR lambda: NovaObject with invoke method → 包装为函数
        if (arg instanceof NovaObject) {
            NovaBoundMethod bound = ((NovaObject) arg).getBoundMethod("invoke");
            if (bound != null) {
                return wrapCallableAsFunction(interp2, bound);
            }
        }
        if (arg instanceof NovaList) {
            return new NovaListView((NovaList) arg);
        } else if (arg instanceof NovaMap) {
            return new NovaMapView((NovaMap) arg);
        } else if (arg instanceof NovaObject || arg instanceof NovaPair) {
            return arg; // 保持身份，避免 toJavaValue() 有损转换（NovaObject→Map, NovaPair→Object[]）
        } else {
            return arg.toJavaValue();
        }
    }

    /** 将 NovaCallable 包装为 Function1/Function2（使用 Nova 函数接口，避免跨模块 cast 问题） */
    private Object wrapCallableAsFunction(Interpreter interp2, NovaCallable callable) {
        int arity = callable.getArity();
        if (arity == 2) {
            return new CallableBridge.Arity2(callable, interp2);
        }
        if (arity == 0) {
            return new CallableBridge.Implicit(callable, interp2);
        }
        return new CallableBridge(callable, interp2);
    }

    // ============ HIR 反射类型成员解析 ============

    NovaValue resolveHirClassInfoMember(NovaClassInfo info, String memberName, AstNode node) {
        switch (memberName) {
            case "name": return NovaString.of(info.name);
            case "superclass": return info.superclass != null ? NovaString.of(info.superclass) : NovaNull.NULL;
            case "interfaces": {
                List<NovaValue> list = new ArrayList<>();
                for (String iface : info.interfaces) list.add(NovaString.of(iface));
                return new NovaList(list);
            }
            case "fields": return new NovaList(new ArrayList<>(info.getFieldInfos()));
            case "methods": return new NovaList(new ArrayList<>(info.getMethodInfos()));
            case "annotations": return new NovaList(new ArrayList<>(info.getAnnotationInfos()));
            case "field": return new NovaNativeFunction("field", 1, (interpreter, args) -> {
                NovaFieldInfo fi = info.findField(args.get(0).asString());
                if (fi == null) throw interp.hirError("Field not found: " + args.get(0).asString(), node);
                return fi;
            });
            case "method": return new NovaNativeFunction("method", 1, (interpreter, args) -> {
                NovaMethodInfo mi = info.findMethod(args.get(0).asString());
                if (mi == null) throw interp.hirError("Method not found: " + args.get(0).asString(), node);
                return mi;
            });
            case "isData": return NovaBoolean.of(info.getNovaClass() != null && info.getNovaClass().isData());
            case "isAbstract": return NovaBoolean.of(info.getNovaClass() != null && info.getNovaClass().isAbstract());
            case "isAnnotation": return NovaBoolean.of(info.getNovaClass() != null && info.getNovaClass().isAnnotation());
            case "toString": return NovaNativeFunction.create("toString", () -> NovaString.of(info.toString()));
            default:
                throw interp.hirError("Unknown ClassInfo member: " + memberName, node);
        }
    }

    NovaValue resolveHirFieldInfoMember(NovaFieldInfo fi, String memberName, AstNode node) {
        switch (memberName) {
            case "name": return NovaString.of(fi.name);
            case "type": return fi.type != null ? NovaString.of(fi.type) : NovaNull.NULL;
            case "visibility": return NovaString.of(fi.visibility);
            case "mutable": return NovaBoolean.of(fi.mutable);
            case "get": return new NovaNativeFunction("get", 1, (interpreter, args) -> {
                if (fi.getOwnerClass() != null) {
                    NovaObject obj = (NovaObject) args.get(0);
                    return obj.getField(fi.name);
                } else {
                    return NovaValue.fromJava(fi.get(args.get(0).toJavaValue()));
                }
            });
            case "set": return new NovaNativeFunction("set", 2, (interpreter, args) -> {
                if (fi.getOwnerClass() != null) {
                    NovaObject obj = (NovaObject) args.get(0);
                    obj.setField(fi.name, args.get(1));
                } else {
                    fi.set(args.get(0).toJavaValue(), args.get(1).toJavaValue());
                }
                return NovaNull.UNIT;
            });
            default:
                throw interp.hirError("Unknown FieldInfo member: " + memberName, node);
        }
    }

    NovaValue resolveHirMethodInfoMember(NovaMethodInfo mi, String memberName, AstNode node) {
        switch (memberName) {
            case "name": return NovaString.of(mi.name);
            case "visibility": return NovaString.of(mi.visibility);
            case "params": return new NovaList(new ArrayList<>(mi.getParamInfos()));
            case "call": return NovaNativeFunction.createVararg("call", (interpreter, args) -> {
                NovaValue instance = args.get(0);
                List<NovaValue> methodArgs = args.subList(1, args.size());
                if (mi.getNovaCallable() != null) {
                    NovaBoundMethod bound = new NovaBoundMethod(instance, mi.getNovaCallable());
                    return bound.call(interpreter, methodArgs);
                } else {
                    Object[] jArgs = new Object[methodArgs.size()];
                    for (int i = 0; i < methodArgs.size(); i++) jArgs[i] = methodArgs.get(i).toJavaValue();
                    return NovaValue.fromJava(mi.call(instance.toJavaValue(), jArgs));
                }
            });
            default:
                throw interp.hirError("Unknown MethodInfo member: " + memberName, node);
        }
    }

    NovaValue resolveHirParamInfoMember(NovaParamInfo pi, String memberName) {
        switch (memberName) {
            case "name": return NovaString.of(pi.name);
            case "type": return pi.type != null ? NovaString.of(pi.type) : NovaNull.NULL;
            case "hasDefault": return NovaBoolean.of(pi.hasDefault);
            default: return null;
        }
    }

    NovaValue resolveHirAnnotationInfoMember(NovaAnnotationInfo ai, String memberName) {
        switch (memberName) {
            case "name": return NovaString.of(ai.name);
            case "args": {
                if (ai.getArgs() != null) {
                    NovaMap map = new NovaMap();
                    for (Map.Entry<String, NovaValue> e : ai.getArgs().entrySet()) {
                        map.put(NovaString.of(e.getKey()), e.getValue());
                    }
                    return map;
                }
                return new NovaMap();
            }
            default: return null;
        }
    }

    // ============ HIR 注解列表 ============

    NovaValue getHirAnnotationsAsList(NovaClass cls) {
        List<HirAnnotation> anns = interp.hirClassAnnotations.get(cls.getName());
        if (anns == null || anns.isEmpty()) return new NovaList();
        List<NovaValue> result = new ArrayList<>();
        for (HirAnnotation ann : anns) {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("name"), NovaString.of(ann.getName()));
            NovaMap argsMap = new NovaMap();
            for (Map.Entry<String, Expression> entry : ann.getArgs().entrySet()) {
                argsMap.put(NovaString.of(entry.getKey()), interp.evaluateHir(entry.getValue()));
            }
            map.put(NovaString.of("args"), argsMap);
            result.add(map);
        }
        return new NovaList(result);
    }

    /**
     * 获取 data class 的字段名列表（按声明顺序）
     */
    List<String> getDataClassFieldNames(NovaClass cls) {
        List<String> names = new ArrayList<>();
        List<HirField> hirFields = interp.hirClassFields.get(cls.getName());
        if (hirFields != null) {
            for (HirField hf : hirFields) {
                if (!hf.getModifiers().contains(Modifier.STATIC)) {
                    names.add(hf.getName());
                }
            }
        }
        // MIR 路径后备：hirClassFields 未填充时使用 NovaClass.dataFieldOrder
        if (names.isEmpty() && cls.getDataFieldOrder() != null) {
            return cls.getDataFieldOrder();
        }
        return names;
    }

    // ============ HIR classOf 构建 ============

    NovaClassInfo buildHirClassInfo(NovaClass cls) {
        // 1. 字段信息：从 hirClassFields 获取
        List<NovaFieldInfo> fields = new ArrayList<>();
        List<HirField> hirFields = interp.hirClassFields.get(cls.getName());
        if (hirFields != null) {
            for (HirField hf : hirFields) {
                if (hf.getModifiers().contains(Modifier.STATIC)) continue;
                String typeName = interp.getHirTypeName(hf.getType());
                Modifier vis = cls.getFieldVisibility(hf.getName());
                String visStr = vis != null ? vis.name().toLowerCase() : "public";
                boolean isMutable = !hf.isVal();
                fields.add(new NovaFieldInfo(hf.getName(), typeName, visStr, isMutable, cls, null));
            }
        } else if (cls.getConstructorCallable() instanceof HirFunctionValue) {
            // 回退：从构造器参数推断字段
            HirFunctionValue ctorFunc = (HirFunctionValue) cls.getConstructorCallable();
            for (HirParam param : ctorFunc.getDeclaration().getParams()) {
                String typeName = interp.getHirTypeName(param.getType());
                Modifier vis = cls.getFieldVisibility(param.getName());
                String visStr = vis != null ? vis.name().toLowerCase() : "public";
                fields.add(new NovaFieldInfo(param.getName(), typeName, visStr, true, cls, null));
            }
        }

        // 2. 方法信息：从 callableMethods 获取
        List<NovaMethodInfo> methods = new ArrayList<>();
        for (Map.Entry<String, NovaCallable> entry : cls.getCallableMethods().entrySet()) {
            NovaCallable callable = entry.getValue();
            Modifier vis = cls.getMethodVisibility(entry.getKey());
            String visStr = vis != null ? vis.name().toLowerCase() : "public";
            List<NovaParamInfo> params = NovaClassInfo.extractParams(callable);
            methods.add(new NovaMethodInfo(entry.getKey(), visStr, params, callable));
        }

        // 3. 注解信息
        List<NovaAnnotationInfo> annotations = new ArrayList<>();
        List<HirAnnotation> anns = interp.hirClassAnnotations.get(cls.getName());
        if (anns != null) {
            for (HirAnnotation ann : anns) {
                Map<String, NovaValue> annArgs = new HashMap<>();
                for (Map.Entry<String, Expression> e : ann.getArgs().entrySet()) {
                    annArgs.put(e.getKey(), interp.evaluateHir(e.getValue()));
                }
                annotations.add(new NovaAnnotationInfo(ann.getName(), annArgs));
            }
        }

        // 4. 父类和接口
        String superName = cls.getSuperclass() != null ? cls.getSuperclass().getName() : null;
        List<String> ifaceNames = new ArrayList<>();
        for (NovaInterface iface : cls.getInterfaces()) {
            ifaceNames.add(iface.getName());
        }

        return NovaClassInfo.create(cls.getName(), superName, ifaceNames,
                fields, methods, annotations, cls);
    }

    NovaValue createDataCopyFunction(NovaObject obj) {
        return new HirDataCopyFunction(obj);
    }

    NovaValue callWithNamed(NovaCallable callable, List<NovaValue> args, Map<String, NovaValue> namedArgs) {
        if (callable.supportsNamedArgs()) {
            return callable.callWithNamed(interp, args, namedArgs);
        }
        return callable.call(interp, args);
    }

    NovaValue call(NovaCallable callable, List<NovaValue> args) {
        return callable.call(interp, args);
    }

    NovaValue resolveHirBuilderMember(NovaBuilder builder, String memberName, AstNode node) {
        NovaClass targetClass = builder.getTargetClass();

        // build() 方法
        if ("build".equals(memberName)) {
            return new NovaNativeFunction("build", 0, (interpreter, args) -> {
                // 从构造器参数中获取字段名列表
                NovaCallable ctor = targetClass.getConstructorCallable();
                if (ctor instanceof HirFunctionValue) {
                    List<HirParam> params = ((HirFunctionValue) ctor).getDeclaration().getParams();
                    List<NovaValue> ctorArgs = new ArrayList<>();
                    for (HirParam p : params) {
                        NovaValue val = builder.getField(p.getName());
                        if (val == null) {
                            if (p.hasDefaultValue()) {
                                val = interp.evaluateHir(p.getDefaultValue());
                            } else {
                                throw interp.hirError("Builder missing required field: " + p.getName(), node);
                            }
                        }
                        ctorArgs.add(val);
                    }
                    return interpreter.instantiate(targetClass, ctorArgs, null);
                }
                if (ctor instanceof MirCallable) {
                    List<com.novalang.ir.mir.MirParam> params = ((MirCallable) ctor).getFunction().getParams();
                    List<NovaValue> ctorArgs = new ArrayList<>();
                    for (com.novalang.ir.mir.MirParam p : params) {
                        NovaValue val = builder.getField(p.getName());
                        if (val == null) {
                            throw interp.hirError("Builder missing required field: " + p.getName(), node);
                        }
                        ctorArgs.add(val);
                    }
                    return interpreter.instantiate(targetClass, ctorArgs, null);
                }
                // 回退: 使用 builder 中的所有字段
                List<NovaValue> ctorArgs = new ArrayList<>(builder.getFields().values());
                return interpreter.instantiate(targetClass, ctorArgs, null);
            });
        }

        // fluent setter: 字段名作为方法名
        NovaCallable ctor = targetClass.getConstructorCallable();
        if (ctor instanceof HirFunctionValue) {
            for (HirParam p : ((HirFunctionValue) ctor).getDeclaration().getParams()) {
                if (p.getName().equals(memberName)) {
                    return NovaNativeFunction.create(memberName, (value) -> {
                        builder.setField(memberName, value);
                        return builder;
                    });
                }
            }
        }
        if (ctor instanceof MirCallable) {
            for (com.novalang.ir.mir.MirParam p : ((MirCallable) ctor).getFunction().getParams()) {
                if (p.getName().equals(memberName)) {
                    return NovaNativeFunction.create(memberName, (value) -> {
                        builder.setField(memberName, value);
                        return builder;
                    });
                }
            }
        }

        throw interp.hirError("Unknown builder member: " + memberName, node);
    }

    // ============ HIR performIndex ============

    NovaValue performIndex(NovaValue target, NovaValue index, AstNode node) {
        // Range 索引（切片）
        if (index instanceof NovaRange) {
            NovaRange range = (NovaRange) index;
            int start = range.getStart();
            // getEnd() 已经根据 inclusive 调整：返回最后一个包含的索引
            int exclusiveEnd = range.getEnd() + 1;
            if (target instanceof NovaList) {
                NovaList list = (NovaList) target;
                return list.slice(start, exclusiveEnd);
            }
            if (target instanceof NovaString) {
                String str = ((NovaString) target).getValue();
                return NovaString.of(str.substring(start, Math.min(exclusiveEnd, str.length())));
            }
        }
        if (target instanceof NovaList) {
            int i = ((NovaInt) index).getValue();
            return ((NovaList) target).get(i);
        }
        if (target instanceof NovaString) {
            int i = ((NovaInt) index).getValue();
            String str = ((NovaString) target).getValue();
            if (i < 0) i += str.length();
            return NovaChar.of(str.charAt(i));
        }
        if (target instanceof NovaMap) {
            return ((NovaMap) target).get(index);
        }
        if (target instanceof NovaRange) {
            int i = ((NovaInt) index).getValue();
            return ((NovaRange) target).get(i);
        }
        if (target instanceof NovaArray) {
            int i = ((NovaInt) index).getValue();
            return ((NovaArray) target).get(i);
        }
        if (target instanceof NovaPair) {
            int i = ((NovaInt) index).getValue();
            NovaPair pair = (NovaPair) target;
            if (i == 0) return NovaValue.fromJava(pair.getFirst());
            if (i == 1) return NovaValue.fromJava(pair.getSecond());
            throw interp.hirError("Pair index out of bounds: " + i, node);
        }
        // 运算符重载 get()
        if (target instanceof NovaObject) {
            NovaCallable getMethod = ((NovaObject) target).getNovaClass().findCallableMethod("get");
            if (getMethod != null) {
                return new NovaBoundMethod(target, getMethod).call(interp, Collections.singletonList(index));
            }
        }
        throw interp.hirError("Cannot index into " + target.getTypeName(), node);
    }

    // ============ HIR performIndexSet ============

    void performIndexSet(NovaValue target, NovaValue index, NovaValue value, AstNode node) {
        if (target instanceof NovaList) {
            ((NovaList) target).set(index.asInt(), value);
        } else if (target instanceof NovaArray) {
            ((NovaArray) target).set(index.asInt(), value);
        } else if (target instanceof NovaMap) {
            ((NovaMap) target).put(index, value);
        } else if (target instanceof NovaObject) {
            NovaCallable setMethod = ((NovaObject) target).getNovaClass().findCallableMethod("set");
            if (setMethod != null) {
                new NovaBoundMethod(target, setMethod).call(interp, Arrays.asList(index, value));
            } else {
                throw new NovaRuntimeException("Cannot index-set on " + target.getTypeName());
            }
        } else {
            throw new NovaRuntimeException("Cannot index-set on " + target.getTypeName());
        }
    }

    // ============ 内部类 ============

    /**
     * HIR 版 data copy 函数，支持命名参数。
     */
    static final class HirDataCopyFunction extends NovaValue implements NovaCallable {
        private final NovaObject source;

        HirDataCopyFunction(NovaObject source) {
            this.source = source;
        }

        @Override public String getName() { return "copy"; }
        @Override public int getArity() { return -1; }
        @Override public String getTypeName() { return "Function"; }
        @Override public Object toJavaValue() { return this; }
        @Override public boolean isCallable() { return true; }
        @Override public String toString() { return "<fun copy>"; }
        @Override public boolean supportsNamedArgs() { return true; }

        @Override
        public NovaValue callWithNamed(Interpreter interpreter, List<NovaValue> args,
                                        Map<String, NovaValue> namedArgs) {
            NovaObject copy = new NovaObject(source.getNovaClass());
            // 先复制所有字段
            for (Map.Entry<String, NovaValue> field : source.getFields().entrySet()) {
                copy.setField(field.getKey(), field.getValue());
            }
            // 按构造器参数顺序用位置参数覆盖
            List<String> fieldOrder = source.getNovaClass().getDataFieldOrder();
            if (fieldOrder != null && args != null && !args.isEmpty()) {
                for (int i = 0; i < args.size() && i < fieldOrder.size(); i++) {
                    copy.setField(fieldOrder.get(i), args.get(i));
                }
            }
            // 用命名参数覆盖
            if (namedArgs != null) {
                for (Map.Entry<String, NovaValue> entry : namedArgs.entrySet()) {
                    copy.setField(entry.getKey(), entry.getValue());
                }
            }
            return copy;
        }

        @Override
        public NovaValue call(Interpreter interpreter, List<NovaValue> args) {
            return callWithNamed(interpreter, args, null);
        }
    }

    // ============ NovaResult 成员解析 ============

    /**
     * 解析 NovaResult 的成员方法/属性。
     * 从原 NovaResult.getMember() 迁移到此处。
     */
    private NovaValue resolveResultMember(NovaResult result, String name) {
        switch (name) {
            case "value":
                if (result.isErr()) throw new NovaRuntimeException("Cannot access 'value' on Err result");
                return result.getInner();
            case "error":
                if (result.isOk()) throw new NovaRuntimeException("Cannot access 'error' on Ok result");
                return result.getInner();
            case "isOk":
                return NovaBoolean.of(result.isOk());
            case "isErr":
                return NovaBoolean.of(result.isErr());
            case "map":
                return new NovaNativeFunction("map", 1, (interp2, args) -> {
                    if (result.isErr()) return result;
                    NovaCallable func = interp2.asCallable(args.get(0), "Result method");
                    NovaValue mapped = func.call(interp2, java.util.Collections.singletonList(result.getInner()));
                    return NovaResult.ok(mapped);
                });
            case "mapErr":
                return new NovaNativeFunction("mapErr", 1, (interp2, args) -> {
                    if (result.isOk()) return result;
                    NovaCallable func = interp2.asCallable(args.get(0), "Result method");
                    NovaValue mapped = func.call(interp2, java.util.Collections.singletonList(result.getInner()));
                    return NovaResult.err(mapped);
                });
            case "unwrap":
                return NovaNativeFunction.create("unwrap", () -> {
                    if (result.isErr()) throw new NovaRuntimeException("Called unwrap() on Err: " + result.getInner().asString());
                    return result.getInner();
                });
            case "unwrapOr":
                return NovaNativeFunction.create("unwrapOr", (defaultVal) -> {
                    return result.isOk() ? result.getInner() : defaultVal;
                });
            case "unwrapOrElse":
                return new NovaNativeFunction("unwrapOrElse", 1, (interp2, args) -> {
                    if (result.isOk()) return result.getInner();
                    NovaCallable func = interp2.asCallable(args.get(0), "Result method");
                    return func.call(interp2, java.util.Collections.singletonList(result.getInner()));
                });
            case "getOrNull":
                return NovaNativeFunction.create("getOrNull", () -> {
                    return result.isOk() ? result.getInner() : NovaNull.NULL;
                });
            default:
                return null;
        }
    }
}
