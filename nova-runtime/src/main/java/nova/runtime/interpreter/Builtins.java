package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import nova.runtime.stdlib.StdlibRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内置函数注册
 */
public final class Builtins {

    private Builtins() {}

    /** 检查 NovaValue 是否可调用（支持 NovaCallable 和 MIR lambda NovaObject with invoke） */
    private static boolean isCallableValue(NovaValue val) {
        if (val instanceof NovaCallable) return true;
        if (val instanceof NovaObject) {
            return ((NovaObject) val).getBoundMethod("invoke") != null;
        }
        return false;
    }

    /**
     * 注册所有内置函数到环境（默认无限制策略）
     */
    public static void register(Environment env) {
        register(env, NovaSecurityPolicy.unrestricted());
    }

    /**
     * 注册所有内置函数到环境（带安全策略）
     */
    public static void register(Environment env, NovaSecurityPolicy policy) {
        // ============ I/O 函数 ============

        if (policy.isStdioAllowed()) {
        // println(...) - 打印并换行
        env.defineVal("println", new NovaNativeFunction("println", -1,
            (interp, args) -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(" ");
                    sb.append(args.get(i).asString());
                }
                interp.getStdout().println(sb.toString());
                return NovaNull.UNIT;
            }));

        // print(...) - 打印不换行
        env.defineVal("print", new NovaNativeFunction("print", -1,
            (interp, args) -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(" ");
                    sb.append(args.get(i).asString());
                }
                interp.getStdout().print(sb.toString());
                return NovaNull.UNIT;
            }));

        // readLine() - 读取一行输入
        env.defineVal("readLine", new NovaNativeFunction("readLine", 0,
            (interp, args) -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(interp.getStdin()));
                    String line = reader.readLine();
                    return line != null ? NovaString.of(line) : NovaNull.NULL;
                } catch (IOException e) {
                    throw new NovaRuntimeException("Failed to read input: " + e.getMessage());
                }
            }));

        // readLine(prompt) - 带提示的读取
        env.defineVal("input", new NovaNativeFunction("input", 1,
            (interp, args) -> {
                interp.getStdout().print(args.get(0).asString());
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(interp.getStdin()));
                    String line = reader.readLine();
                    return line != null ? NovaString.of(line) : NovaNull.NULL;
                } catch (IOException e) {
                    throw new NovaRuntimeException("Failed to read input: " + e.getMessage());
                }
            }));
        } // end if (policy.isStdioAllowed())

        // ============ 类型转换函数 ============

        // toInt(value) - 转换为整数
        env.defineVal("toInt", NovaNativeFunction.create("toInt", (value) -> {
            if (value.isNumber()) {
                return NovaInt.of(value.asInt());
            }
            if (value.isString()) {
                try {
                    return NovaInt.of(Integer.parseInt(value.asString().trim()));
                } catch (NumberFormatException e) {
                    throw new NovaRuntimeException("Cannot convert to Int: " + value.asString());
                }
            }
            throw new NovaRuntimeException("Cannot convert " + value.getTypeName() + " to Int");
        }));

        // toLong(value) - 转换为长整数
        env.defineVal("toLong", NovaNativeFunction.create("toLong", (value) -> {
            if (value.isNumber()) {
                return NovaLong.of(value.asLong());
            }
            if (value.isString()) {
                try {
                    return NovaLong.of(Long.parseLong(value.asString().trim()));
                } catch (NumberFormatException e) {
                    throw new NovaRuntimeException("Cannot convert to Long: " + value.asString());
                }
            }
            throw new NovaRuntimeException("Cannot convert " + value.getTypeName() + " to Long");
        }));

        // toDouble(value) - 转换为浮点数
        env.defineVal("toDouble", NovaNativeFunction.create("toDouble", (value) -> {
            if (value.isNumber()) {
                return NovaDouble.of(value.asDouble());
            }
            if (value.isString()) {
                try {
                    return NovaDouble.of(Double.parseDouble(value.asString().trim()));
                } catch (NumberFormatException e) {
                    throw new NovaRuntimeException("Cannot convert to Double: " + value.asString());
                }
            }
            throw new NovaRuntimeException("Cannot convert " + value.getTypeName() + " to Double");
        }));

        // toString(value) - 转换为字符串
        env.defineVal("toString", NovaNativeFunction.create("toString",
            (value) -> NovaString.of(value.asString())));

        // toBoolean(value) - 转换为布尔值
        env.defineVal("toBoolean", NovaNativeFunction.create("toBoolean",
            (value) -> NovaBoolean.of(value.isTruthy())));

        // ============ 类型检查函数（依赖 NovaValue 的留在此处） ============

        // typeof(value) - 获取类型名（依赖 NovaValue.getTypeName()）
        env.defineVal("typeof", NovaNativeFunction.create("typeof",
            (value) -> NovaString.of(value.getTypeName())));

        // isCallable(value) - 依赖 NovaCallable JVM 类型检查
        env.defineVal("isCallable", NovaNativeFunction.create("isCallable",
            (value) -> NovaBoolean.of(value.isCallable())));

        // isNull / isNumber / isString / isList / isMap → StdlibTypeChecks

        // ============ 集合函数 ============

        // listOf / mutableListOf / mapOf / setOf / mutableSetOf / mutableMapOf → StdlibRegistry

        // Array<T>(size) 或 Array<T>(size, init) - 创建原生数组
        env.defineVal("Array", new NovaNativeFunction("Array", -1,
            (interp, args) -> {
                if (args.isEmpty()) {
                    throw new NovaRuntimeException("Array(size) requires at least 1 argument");
                }
                String typeName = interp.getPendingTypeArgName(0);
                NovaArray.ElementType elemType = NovaArray.ElementType.fromTypeName(
                        typeName != null ? typeName : "Any");
                int size = args.get(0).asInt();
                NovaArray arr = new NovaArray(elemType, size);
                if (args.size() >= 2 && isCallableValue(args.get(1))) {
                    NovaCallable initFn = interp.asCallable(args.get(1), "Builtins method");
                    for (int i = 0; i < size; i++) {
                        arr.set(i, initFn.call(interp,
                                java.util.Collections.singletonList(NovaInt.of(i))));
                    }
                }
                return arr;
            }));

        // arrayOf(...) - 创建原生数组
        env.defineVal("arrayOf", new NovaNativeFunction("arrayOf", -1,
            (interp, args) -> {
                if (args.isEmpty()) return new NovaArray(NovaArray.ElementType.OBJECT, 0);
                NovaArray.ElementType elemType = NovaArray.inferElementType(args.get(0));
                NovaArray arr = new NovaArray(elemType, args.size());
                for (int i = 0; i < args.size(); i++) arr.set(i, args.get(i));
                return arr;
            }));

        // Pair(first, second) - 创建 Pair
        env.defineVal("Pair", NovaNativeFunction.create("Pair",
            (first, second) -> new NovaPair(first, second)));

        // range(start, end) - 创建范围
        env.defineVal("range", NovaNativeFunction.create("range", (start, end) -> {
            int s = start.asInt();
            int e = end.asInt();
            NovaList list = new NovaList();
            if (s <= e) {
                for (int i = s; i < e; i++) {
                    list.add(NovaInt.of(i));
                }
            } else {
                for (int i = s; i > e; i--) {
                    list.add(NovaInt.of(i));
                }
            }
            return list;
        }));

        // rangeClosed(start, end) - 创建闭区间范围
        env.defineVal("rangeClosed", NovaNativeFunction.create("rangeClosed", (start, end) -> {
            int s = start.asInt();
            int e = end.asInt();
            NovaList list = new NovaList();
            if (s <= e) {
                for (int i = s; i <= e; i++) {
                    list.add(NovaInt.of(i));
                }
            } else {
                for (int i = s; i >= e; i--) {
                    list.add(NovaInt.of(i));
                }
            }
            return list;
        }));

        // sqrt / pow / floor / ceil / round / random
        // + sin / cos / tan / asin / acos / atan / atan2
        // + log / log10 / log2 / exp / sign / clamp
        // → StdlibMath（通过 NativeFunctionInfo 循环注册）

        // ============ 错误处理（依赖 NovaRuntimeException 的留在此处） ============

        // error(message) - 抛出错误（需要直接抛 NovaRuntimeException）
        env.defineVal("error", NovaNativeFunction.create("error", (message) -> {
            throw new NovaRuntimeException(message.asString());
        }));

        // todo / assert / require → StdlibErrors

        // ============ Java 互操作 ============

        if (policy.isJavaInteropAllowed()) {
        // javaClass(className) - 获取 Java 类引用
        env.defineVal("javaClass", NovaNativeFunction.create("javaClass", (className) -> {
            String name = className.asString();
            if (!policy.isClassAllowed(name)) {
                throw NovaSecurityPolicy.denied("Cannot access Java class: " + name);
            }
            try {
                Class<?> clazz = Class.forName(name);
                return new JavaInterop.NovaJavaClass(clazz);
            } catch (ClassNotFoundException e) {
                throw new NovaRuntimeException("Java class not found: " + name);
            }
        }));
        } // end if (policy.isJavaInteropAllowed())

        // ============ stdlib 注册表函数 ============

        // 接收者 Lambda 函数（buildString / buildList / buildMap / buildSet）
        for (StdlibRegistry.ReceiverLambdaInfo info : StdlibRegistry.getReceiverLambdas()) {
            final StdlibRegistry.ReceiverLambdaInfo capturedInfo = info;
            env.defineVal(info.name, new NovaNativeFunction(info.name, 1, (interp, args) -> {
                NovaValue arg = args.get(0);
                NovaCallable callable = interp.asCallable(arg, capturedInfo.name);
                Consumer<Object> consumer = receiver -> {
                    NovaBoundMethod bound = new NovaBoundMethod(
                            new NovaExternalObject(receiver), callable);
                    bound.call(interp, java.util.Collections.emptyList());
                };
                Object result = capturedInfo.impl.apply(consumer);
                return AbstractNovaValue.fromJava(result);
            }));
        }

        // 原生函数（min / max / abs …）
        // pairOf 需要解释器特有的 NovaPair 实现，跳过 StdlibRegistry 版本（返回 Object[]）
        // coroutineScope / supervisorScope 在下方显式注册（需要 Interpreter 访问权 + 多参数支持）
        for (StdlibRegistry.NativeFunctionInfo nf : StdlibRegistry.getNativeFunctions()) {
            if ("pairOf".equals(nf.name)) continue;
            if ("coroutineScope".equals(nf.name) || "supervisorScope".equals(nf.name)) continue;
            if ("schedule".equals(nf.name) || "scheduleRepeat".equals(nf.name)) continue; // 解释器需要子 Interpreter 执行 block
            if ("scope".equals(nf.name) || "sync".equals(nf.name)) continue; // 同上
            if ("launch".equals(nf.name) || "parallel".equals(nf.name) || "withTimeout".equals(nf.name)) continue; // 同上
            if ("AtomicInt".equals(nf.name) || "AtomicLong".equals(nf.name) || "AtomicRef".equals(nf.name)
                    || "Channel".equals(nf.name) || "Mutex".equals(nf.name)) continue; // 解释器需要 NovaMap 包装
            if ("awaitAll".equals(nf.name) || "awaitFirst".equals(nf.name) || "withContext".equals(nf.name)) continue; // 同上
            if ("typeof".equals(nf.name) || "isCallable".equals(nf.name)) continue; // 解释器保留 NovaValue 原生实现
            final StdlibRegistry.NativeFunctionInfo capturedNf = nf;
            env.defineVal(nf.name, new NovaNativeFunction(nf.name, nf.arity, (interp, args) -> {
                Object[] javaArgs = new Object[args.size()];
                for (int i = 0; i < args.size(); i++) {
                    javaArgs[i] = args.get(i).toJavaValue();
                }
                return AbstractNovaValue.fromJava(capturedNf.impl.apply(javaArgs));
            }));
        }

        // Supplier Lambda 函数（async）
        for (StdlibRegistry.SupplierLambdaInfo slInfo : StdlibRegistry.getSupplierLambdas()) {
            final String funcName = slInfo.name;
            env.defineVal(funcName, new NovaNativeFunction(funcName, 1, (interp, args) -> {
                NovaValue arg = args.get(0);
                NovaCallable callable = interp.asCallable(arg, funcName);
                return new NovaFuture(callable, (Interpreter) interp);
            }));
        }

        // ============ 常量（从 StdlibRegistry 加载） ============

        for (StdlibRegistry.ConstantInfo ci : StdlibRegistry.getConstants()) {
            if ("Dispatchers".equals(ci.name)) continue; // 解释器使用自己的 NovaMap 版本
            env.defineVal(ci.name, AbstractNovaValue.fromJava(ci.value));
        }

        // ============ 反射 API ============

        // classOf(ClassOrInstance) - 获取类反射信息
        env.defineVal("classOf", new NovaNativeFunction("classOf", 1, (interp, args) -> {
            NovaValue arg = args.get(0);
            if (arg instanceof NovaClass) {
                return NovaClassInfo.fromNovaClass((NovaClass) arg);
            } else if (arg instanceof NovaObject) {
                return NovaClassInfo.fromNovaClass(((NovaObject) arg).getNovaClass());
            }
            throw new NovaRuntimeException("classOf() requires a class or object");
        }));

        // ============ 注解处理器注册 ============

        // registerAnnotationProcessor(name, handler) - 注册 Nova 注解处理器，返回句柄
        env.defineVal("registerAnnotationProcessor", new NovaNativeFunction(
                "registerAnnotationProcessor", 2, (interp, args) -> {
            String name = args.get(0).asString();
            NovaValue handler = args.get(1);
            if (!handler.isCallable()) {
                throw new NovaRuntimeException("registerAnnotationProcessor: handler must be callable");
            }

            // 可变引用，支持 replace 替换 handler
            NovaValue[] handlerHolder = {handler};

            NovaAnnotationProcessor proc = new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() {
                    return name;
                }

                @Override
                public void processClass(NovaValue target, Map<String, NovaValue> annArgs, ExecutionContext ctx) {
                    NovaMap argsMap = new NovaMap();
                    for (Map.Entry<String, NovaValue> e : annArgs.entrySet()) {
                        argsMap.put(NovaString.of(e.getKey()), e.getValue());
                    }
                    NovaClassInfo info = NovaClassInfo.fromNovaClass((nova.runtime.types.NovaClass) target);
                    ((NovaCallable) handlerHolder[0]).call(ctx, Arrays.asList(info, argsMap));
                }

                @Override
                public void processFun(NovaValue target, Map<String, NovaValue> annArgs, ExecutionContext ctx) {
                    NovaMap argsMap = new NovaMap();
                    for (Map.Entry<String, NovaValue> e : annArgs.entrySet()) {
                        argsMap.put(NovaString.of(e.getKey()), e.getValue());
                    }
                    ((NovaCallable) handlerHolder[0]).call(ctx, Arrays.asList(target, argsMap));
                }

                @Override
                public void processProperty(String propertyName, NovaValue propertyValue,
                                             Map<String, NovaValue> annArgs, ExecutionContext ctx) {
                    NovaMap argsMap = new NovaMap();
                    for (Map.Entry<String, NovaValue> e : annArgs.entrySet()) {
                        argsMap.put(NovaString.of(e.getKey()), e.getValue());
                    }
                    ((NovaCallable) handlerHolder[0]).call(ctx, Arrays.asList(NovaString.of(propertyName), argsMap));
                }
            };

            interp.registerAnnotationProcessor(proc);

            // 构建句柄对象
            NovaMap handle = new NovaMap();
            handle.put(NovaString.of("unregister"), new NovaNativeFunction("unregister", 0, (interp2, args2) -> {
                interp2.unregisterAnnotationProcessor(proc);
                return NovaNull.UNIT;
            }));
            handle.put(NovaString.of("register"), new NovaNativeFunction("register", 0, (interp2, args2) -> {
                interp2.registerAnnotationProcessor(proc);
                return NovaNull.UNIT;
            }));
            handle.put(NovaString.of("replace"), new NovaNativeFunction("replace", 1, (interp2, args2) -> {
                handlerHolder[0] = args2.get(0);
                return NovaNull.UNIT;
            }));
            return handle;
        }));

        // ============ 额外顶层函数 ============

        // toChar(value) - 转换为字符
        env.defineVal("toChar", NovaNativeFunction.create("toChar", (value) -> {
            if (value instanceof NovaChar) return value;
            if (value instanceof NovaInt) return NovaChar.of((char) ((NovaInt) value).getValue());
            if (value instanceof NovaString) {
                String s = value.asString();
                if (s.length() == 1) return NovaChar.of(s.charAt(0));
                throw new NovaRuntimeException("Cannot convert multi-char string to Char");
            }
            throw new NovaRuntimeException("Cannot convert " + value.getTypeName() + " to Char");
        }));

        // toFloat(value) - 转换为浮点数
        env.defineVal("toFloat", NovaNativeFunction.create("toFloat", (value) -> {
            if (value.isNumber()) return NovaFloat.of((float) value.asDouble());
            if (value.isString()) {
                try { return NovaFloat.of(Float.parseFloat(value.asString().trim())); }
                catch (NumberFormatException e) { throw new NovaRuntimeException("Cannot convert to Float: " + value.asString()); }
            }
            throw new NovaRuntimeException("Cannot convert " + value.getTypeName() + " to Float");
        }));

        // emptyList / emptyMap / emptySet 已由 StdlibRegistry NativeFunctions 循环注册

        // pairOf(a, b) - 解释器需要 NovaPair（StdlibRegistry 版本返回 Object[]，不兼容）
        env.defineVal("pairOf", NovaNativeFunction.create("pairOf", (a, b) -> new NovaPair(a, b)));

        // List(size, init) - 按初始化函数创建列表
        env.defineVal("List", new NovaNativeFunction("List", 2, (interp, args) -> {
            int size = args.get(0).asInt();
            NovaCallable init = interp.asCallable(args.get(1), "Builtins method");
            NovaList result = new NovaList();
            for (int i = 0; i < size; i++) {
                result.add(init.call(interp, java.util.Collections.singletonList(NovaInt.of(i))));
            }
            return result;
        }));

        // with(receiver, block) - 作用域函数
        env.defineVal("with", new NovaNativeFunction("with", 2, (interp, args) -> {
            NovaValue receiver = args.get(0);
            NovaCallable block = interp.asCallable(args.get(1), "Builtins method");
            return block.call(interp, java.util.Collections.singletonList(receiver));
        }));

        // repeat(times, action) - 重复执行
        env.defineVal("repeat", new NovaNativeFunction("repeat", 2, (interp, args) -> {
            int times = args.get(0).asInt();
            NovaCallable action = interp.asCallable(args.get(1), "Builtins method");
            for (int i = 0; i < times; i++) {
                action.call(interp, java.util.Collections.singletonList(NovaInt.of(i)));
            }
            return NovaNull.UNIT;
        }));

        // measureTimeMillis(block) - 测量毫秒耗时
        env.defineVal("measureTimeMillis", new NovaNativeFunction("measureTimeMillis", 1, (interp, args) -> {
            NovaCallable block = interp.asCallable(args.get(0), "Builtins method");
            long start = System.currentTimeMillis();
            block.call(interp, java.util.Collections.emptyList());
            return NovaLong.of(System.currentTimeMillis() - start);
        }));

        // measureNanoTime(block) - 测量纳秒耗时
        env.defineVal("measureNanoTime", new NovaNativeFunction("measureNanoTime", 1, (interp, args) -> {
            NovaCallable block = interp.asCallable(args.get(0), "Builtins method");
            long start = System.nanoTime();
            block.call(interp, java.util.Collections.emptyList());
            return NovaLong.of(System.nanoTime() - start);
        }));

        // runCatching(block) - 执行 block，成功返回 Ok(result)，异常返回 Err(message)
        env.defineVal("runCatching", new NovaNativeFunction("runCatching", 1, (interp, args) -> {
            NovaCallable block = interp.asCallable(args.get(0), "runCatching");
            try {
                NovaValue result = block.call(interp, java.util.Collections.emptyList());
                return NovaResult.ok(result);
            } catch (NovaRuntimeException e) {
                String msg = e.getRawMessage();
                if (msg == null) msg = e.getClass().getSimpleName();
                return NovaResult.err(msg);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                return NovaResult.err(msg);
            }
        }));

        // ============ 结构化并发 ============

        // Dispatchers 对象：Dispatchers.IO / Dispatchers.Default / Dispatchers.Unconfined
        NovaMap dispatchers = new NovaMap();
        dispatchers.put(NovaString.of("IO"), new NovaExternalObject(NovaDispatchers.IO));
        dispatchers.put(NovaString.of("Default"), new NovaExternalObject(NovaDispatchers.DEFAULT));
        dispatchers.put(NovaString.of("Unconfined"), new NovaExternalObject(NovaDispatchers.UNCONFINED));
        env.defineVal("Dispatchers", dispatchers);

        // coroutineScope(block) 或 coroutineScope(dispatcher, block)
        env.defineVal("coroutineScope", new NovaNativeFunction("coroutineScope", -1, (interp, args) -> {
            java.util.concurrent.Executor exec = NovaDispatchers.DEFAULT;
            NovaCallable block;
            if (args.size() >= 2) {
                Object dispObj = args.get(0).toJavaValue();
                if (dispObj instanceof java.util.concurrent.Executor) {
                    exec = (java.util.concurrent.Executor) dispObj;
                }
                block = interp.asCallable(args.get(args.size() - 1), "coroutineScope");
            } else {
                block = interp.asCallable(args.get(0), "coroutineScope");
            }
            NovaScope scope = new NovaScope((Interpreter) interp, exec, false);
            NovaValue result = block.call(interp, java.util.Collections.singletonList(scope));
            scope.joinAll();
            return result;
        }));

        // supervisorScope(block) 或 supervisorScope(dispatcher, block)
        env.defineVal("supervisorScope", new NovaNativeFunction("supervisorScope", -1, (interp, args) -> {
            java.util.concurrent.Executor exec = NovaDispatchers.DEFAULT;
            NovaCallable block;
            if (args.size() >= 2) {
                Object dispObj = args.get(0).toJavaValue();
                if (dispObj instanceof java.util.concurrent.Executor) {
                    exec = (java.util.concurrent.Executor) dispObj;
                }
                block = interp.asCallable(args.get(args.size() - 1), "supervisorScope");
            } else {
                block = interp.asCallable(args.get(0), "supervisorScope");
            }
            NovaScope scope = new NovaScope((Interpreter) interp, exec, true);
            NovaValue result = block.call(interp, java.util.Collections.singletonList(scope));
            scope.joinAll();
            return result;
        }));

        // schedule(delayMs) { block } — 延迟调度，返回 Task
        env.defineVal("schedule", new NovaNativeFunction("schedule", 2, (interp, args) -> {
            NovaScheduler sched = interp.getScheduler();
            if (sched == null) throw new NovaRuntimeException("No scheduler configured. Use Nova.setScheduler() first.");
            long delayMs = args.get(0).asLong();
            NovaCallable block = interp.asCallable(args.get(1), "schedule");
            NovaScheduler.Cancellable handle = sched.scheduleLater(delayMs, () ->
                    block.call(interp.createChild(), java.util.Collections.emptyList()));
            return new NovaTask(handle);
        }));

        // scheduleRepeat(delayMs, periodMs) { block } — 重复调度，返回 Task
        env.defineVal("scheduleRepeat", new NovaNativeFunction("scheduleRepeat", 3, (interp, args) -> {
            NovaScheduler sched = interp.getScheduler();
            if (sched == null) throw new NovaRuntimeException("No scheduler configured. Use Nova.setScheduler() first.");
            long delayMs = args.get(0).asLong();
            long periodMs = args.get(1).asLong();
            NovaCallable block = interp.asCallable(args.get(2), "scheduleRepeat");
            NovaScheduler.Cancellable handle = sched.scheduleRepeat(delayMs, periodMs, () ->
                    block.call(interp.createChild(), java.util.Collections.emptyList()));
            return new NovaTask(handle);
        }));

        // scope { block } — 优先使用宿主异步调度器，回退内置线程池
        env.defineVal("scope", new NovaNativeFunction("scope", 1, (interp, args) -> {
            NovaCallable block = interp.asCallable(args.get(0), "scope");
            NovaScheduler sched = interp.getScheduler();
            if (sched != null && sched.isMainThread()) {
                throw new NovaRuntimeException("Cannot call scope() on the main thread (would block and cause deadlock). Use launch { } instead.");
            }
            java.util.concurrent.Executor asyncExec = null;
            if (sched != null) asyncExec = sched.asyncExecutor();
            java.util.concurrent.CompletableFuture<NovaValue> future = new java.util.concurrent.CompletableFuture<>();
            Runnable task = () -> {
                try {
                    future.complete(block.call(interp.createChild(), java.util.Collections.emptyList()));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            };
            if (asyncExec != null) {
                asyncExec.execute(task);
            } else {
                Thread t = new Thread(task, "nova-scope");
                t.setDaemon(true);
                t.start();
            }
            try {
                return future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
                throw new NovaRuntimeException(cause != null ? cause.getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NovaRuntimeException("scope interrupted");
            }
        }));

        // sync { block } — 提交到调度器主线程执行并等待结果
        env.defineVal("sync", new NovaNativeFunction("sync", 1, (interp, args) -> {
            NovaScheduler sched = interp.getScheduler();
            if (sched == null) throw new NovaRuntimeException("No scheduler configured. Use Nova.setScheduler() first.");
            NovaCallable block = interp.asCallable(args.get(0), "sync");
            // 已在主线程则直接执行
            if (sched.isMainThread()) {
                return block.call(interp, java.util.Collections.emptyList());
            }
            java.util.concurrent.CompletableFuture<NovaValue> future = new java.util.concurrent.CompletableFuture<>();
            sched.mainExecutor().execute(() -> {
                try {
                    future.complete(block.call(interp.createChild(), java.util.Collections.emptyList()));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            try {
                return future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
                throw new NovaRuntimeException(cause != null ? cause.getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NovaRuntimeException("sync interrupted");
            }
        }));

        // launch { block } — fire-and-forget 异步，返回 Job
        env.defineVal("launch", new NovaNativeFunction("launch", 1, (interp, args) -> {
            NovaCallable block = interp.asCallable(args.get(0), "launch");
            java.util.concurrent.Executor exec = getAsyncExecutor((Interpreter) interp);
            java.util.concurrent.CompletableFuture<NovaValue> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    block.call(interp.createChild(), java.util.Collections.emptyList()), exec);
            return new NovaJob(future);
        }));

        // parallel(tasks...) — 并行执行多个 lambda，返回结果列表
        env.defineVal("parallel", new NovaNativeFunction("parallel", -1, (interp, args) -> {
            java.util.concurrent.Executor exec = getAsyncExecutor((Interpreter) interp);
            java.util.List<java.util.concurrent.CompletableFuture<NovaValue>> futures = new java.util.ArrayList<>();
            for (NovaValue arg : args) {
                NovaCallable task = interp.asCallable(arg, "parallel");
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    task.call(interp.createChild(), java.util.Collections.emptyList()), exec));
            }
            NovaList results = new NovaList();
            for (java.util.concurrent.CompletableFuture<NovaValue> f : futures) {
                try {
                    results.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
                    throw new NovaRuntimeException("parallel task failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NovaRuntimeException("parallel interrupted");
                }
            }
            return results;
        }));

        // withTimeout(millis, block) — 带超时执行
        env.defineVal("withTimeout", new NovaNativeFunction("withTimeout", 2, (interp, args) -> {
            long timeout = args.get(0).asLong();
            NovaCallable block = interp.asCallable(args.get(1), "withTimeout");
            java.util.concurrent.Executor exec = getAsyncExecutor((Interpreter) interp);
            java.util.concurrent.CompletableFuture<NovaValue> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    block.call(interp.createChild(), java.util.Collections.emptyList()), exec);
            try {
                return future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new NovaRuntimeException("Timeout after " + timeout + "ms");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
                throw new NovaRuntimeException(cause != null ? cause.getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NovaRuntimeException("withTimeout interrupted");
            }
        }));

        // ============ 并发原语（全局） ============

        // AtomicInt(initial) — 原子整数
        env.defineVal("AtomicInt", NovaNativeFunction.create("AtomicInt", (initial) -> {
            AtomicInteger ai = new AtomicInteger(initial.asInt());
            NovaMap obj = new NovaMap();
            obj.put(NovaString.of("get"), NovaNativeFunction.create("get", () -> NovaInt.of(ai.get())));
            obj.put(NovaString.of("set"), NovaNativeFunction.create("set", (v) -> { ai.set(v.asInt()); return NovaNull.UNIT; }));
            obj.put(NovaString.of("incrementAndGet"), NovaNativeFunction.create("incrementAndGet", () -> NovaInt.of(ai.incrementAndGet())));
            obj.put(NovaString.of("decrementAndGet"), NovaNativeFunction.create("decrementAndGet", () -> NovaInt.of(ai.decrementAndGet())));
            obj.put(NovaString.of("addAndGet"), NovaNativeFunction.create("addAndGet", (v) -> NovaInt.of(ai.addAndGet(v.asInt()))));
            obj.put(NovaString.of("compareAndSet"), NovaNativeFunction.create("compareAndSet", (expect, update) ->
                NovaBoolean.of(ai.compareAndSet(expect.asInt(), update.asInt()))));
            obj.put(NovaString.of("toString"), NovaNativeFunction.create("toString", () -> NovaString.of("AtomicInt(" + ai.get() + ")")));
            return obj;
        }));

        // AtomicLong(initial) — 原子长整数
        env.defineVal("AtomicLong", NovaNativeFunction.create("AtomicLong", (initial) -> {
            AtomicLong al = new AtomicLong(initial.asLong());
            NovaMap obj = new NovaMap();
            obj.put(NovaString.of("get"), NovaNativeFunction.create("get", () -> NovaLong.of(al.get())));
            obj.put(NovaString.of("set"), NovaNativeFunction.create("set", (v) -> { al.set(v.asLong()); return NovaNull.UNIT; }));
            obj.put(NovaString.of("incrementAndGet"), NovaNativeFunction.create("incrementAndGet", () -> NovaLong.of(al.incrementAndGet())));
            obj.put(NovaString.of("decrementAndGet"), NovaNativeFunction.create("decrementAndGet", () -> NovaLong.of(al.decrementAndGet())));
            obj.put(NovaString.of("addAndGet"), NovaNativeFunction.create("addAndGet", (v) -> NovaLong.of(al.addAndGet(v.asLong()))));
            obj.put(NovaString.of("compareAndSet"), NovaNativeFunction.create("compareAndSet", (expect, update) ->
                NovaBoolean.of(al.compareAndSet(expect.asLong(), update.asLong()))));
            obj.put(NovaString.of("toString"), NovaNativeFunction.create("toString", () -> NovaString.of("AtomicLong(" + al.get() + ")")));
            return obj;
        }));

        // AtomicRef(initial) — 原子引用
        env.defineVal("AtomicRef", NovaNativeFunction.create("AtomicRef", (initial) -> {
            AtomicReference<NovaValue> ref = new AtomicReference<>(initial);
            NovaMap obj = new NovaMap();
            obj.put(NovaString.of("get"), NovaNativeFunction.create("get", () -> ref.get()));
            obj.put(NovaString.of("set"), NovaNativeFunction.create("set", (v) -> { ref.set(v); return NovaNull.UNIT; }));
            obj.put(NovaString.of("compareAndSet"), NovaNativeFunction.create("compareAndSet", (expect, update) ->
                NovaBoolean.of(ref.compareAndSet(expect, update))));
            obj.put(NovaString.of("toString"), NovaNativeFunction.create("toString", () -> NovaString.of("AtomicRef(" + ref.get().asString() + ")")));
            return obj;
        }));

        // Channel(capacity?) — 并发通道
        env.defineVal("Channel", new NovaNativeFunction("Channel", -1, (interp, args) -> {
            int capacity = args.isEmpty() ? Integer.MAX_VALUE : args.get(0).asInt();
            LinkedBlockingQueue<NovaValue> queue = capacity == Integer.MAX_VALUE
                ? new LinkedBlockingQueue<>()
                : new LinkedBlockingQueue<>(capacity);
            boolean[] closed = {false};
            NovaMap ch = new NovaMap();
            ch.put(NovaString.of("send"), NovaNativeFunction.create("send", (value) -> {
                if (closed[0]) throw new NovaRuntimeException("Cannot send to a closed channel");
                try { queue.put(value); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return NovaNull.UNIT;
            }));
            ch.put(NovaString.of("receive"), NovaNativeFunction.create("receive", () -> {
                try { return queue.take(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return NovaNull.NULL;
                }
            }));
            ch.put(NovaString.of("receiveTimeout"), NovaNativeFunction.create("receiveTimeout", (timeoutMs) -> {
                try {
                    NovaValue val = queue.poll(timeoutMs.asLong(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (val == null) throw new NovaRuntimeException("Channel receive timed out");
                    return val;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return NovaNull.NULL;
                }
            }));
            ch.put(NovaString.of("tryReceive"), NovaNativeFunction.create("tryReceive", () -> {
                NovaValue v = queue.poll();
                return v != null ? v : NovaNull.NULL;
            }));
            ch.put(NovaString.of("size"), NovaNativeFunction.create("size", () -> NovaInt.of(queue.size())));
            ch.put(NovaString.of("isEmpty"), NovaNativeFunction.create("isEmpty", () -> NovaBoolean.of(queue.isEmpty())));
            ch.put(NovaString.of("isClosed"), NovaNativeFunction.create("isClosed", () -> NovaBoolean.of(closed[0])));
            ch.put(NovaString.of("close"), NovaNativeFunction.create("close", () -> {
                closed[0] = true;
                return NovaNull.UNIT;
            }));
            ch.put(NovaString.of("iterator"), NovaNativeFunction.create("iterator", () -> {
                NovaMap iter = new NovaMap();
                iter.put(NovaString.of("hasNext"), NovaNativeFunction.create("hasNext", () ->
                    NovaBoolean.of(!closed[0] || !queue.isEmpty())));
                iter.put(NovaString.of("next"), NovaNativeFunction.create("next", () -> {
                    NovaValue val = queue.poll();
                    if (val != null) return val;
                    if (closed[0]) throw new NovaRuntimeException("Channel closed");
                    try { return queue.take(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NovaRuntimeException("interrupted");
                    }
                }));
                return iter;
            }));
            return ch;
        }));

        // Mutex() — 互斥锁
        env.defineVal("Mutex", NovaNativeFunction.create("Mutex", () -> {
            ReentrantLock lock = new ReentrantLock();
            NovaMap mutex = new NovaMap();
            mutex.put(NovaString.of("lock"), NovaNativeFunction.create("lock", () -> { lock.lock(); return NovaNull.UNIT; }));
            mutex.put(NovaString.of("unlock"), NovaNativeFunction.create("unlock", () -> { lock.unlock(); return NovaNull.UNIT; }));
            mutex.put(NovaString.of("tryLock"), NovaNativeFunction.create("tryLock", () -> NovaBoolean.of(lock.tryLock())));
            mutex.put(NovaString.of("isLocked"), NovaNativeFunction.create("isLocked", () -> NovaBoolean.of(lock.isLocked())));
            mutex.put(NovaString.of("withLock"), new NovaNativeFunction("withLock", 1, (interp2, args2) -> {
                NovaCallable block = (NovaCallable) args2.get(0);
                lock.lock();
                try {
                    return block.call(interp2, Collections.emptyList());
                } finally {
                    lock.unlock();
                }
            }));
            return mutex;
        }));

        // ============ awaitAll / awaitFirst / withContext ============

        // awaitAll(futures) — 等待所有 Future 完成
        env.defineVal("awaitAll", new NovaNativeFunction("awaitAll", 1, (interp, args) -> {
            NovaList futureList = (NovaList) args.get(0);
            NovaList results = new NovaList();
            for (NovaValue fv : futureList) {
                Object javaObj = fv.toJavaValue();
                if (javaObj instanceof java.util.concurrent.Future) {
                    try {
                        Object result = ((java.util.concurrent.Future<?>) javaObj).get();
                        results.add(AbstractNovaValue.fromJava(result));
                    } catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
                        throw new NovaRuntimeException("awaitAll failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NovaRuntimeException("awaitAll interrupted");
                    }
                } else {
                    results.add(fv);
                }
            }
            return results;
        }));

        // awaitFirst(futures) — 返回首个完成的结果
        env.defineVal("awaitFirst", new NovaNativeFunction("awaitFirst", 1, (interp, args) -> {
            NovaList futureList = (NovaList) args.get(0);
            List<java.util.concurrent.Future<Object>> submitted = new java.util.ArrayList<>();
            for (NovaValue fv : futureList) {
                Object javaObj = fv.toJavaValue();
                if (javaObj instanceof java.util.concurrent.Future) {
                    @SuppressWarnings("unchecked")
                    java.util.concurrent.Future<Object> f = (java.util.concurrent.Future<Object>) javaObj;
                    submitted.add(f);
                }
            }
            if (submitted.isEmpty()) return NovaNull.NULL;
            while (true) {
                for (java.util.concurrent.Future<Object> f : submitted) {
                    if (f.isDone()) {
                        try {
                            return AbstractNovaValue.fromJava(f.get());
                        } catch (Exception e) {
                            throw new NovaRuntimeException("awaitFirst failed: " + e.getMessage());
                        }
                    }
                }
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NovaRuntimeException("awaitFirst interrupted");
                }
            }
        }));

        // withContext(dispatcher, block) — 在指定 executor 上执行 block 并阻塞等待结果
        env.defineVal("withContext", new NovaNativeFunction("withContext", 2, (interp, args) -> {
            Object dispatcher = args.get(0).toJavaValue();
            NovaCallable block = interp.asCallable(args.get(1), "withContext");
            java.util.concurrent.Executor exec = dispatcher instanceof java.util.concurrent.Executor
                ? (java.util.concurrent.Executor) dispatcher
                : java.util.concurrent.ForkJoinPool.commonPool();
            java.util.concurrent.CompletableFuture<NovaValue> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    block.call(interp.createChild(), Collections.emptyList()), exec);
            try {
                return future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
                throw new NovaRuntimeException("withContext failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NovaRuntimeException("withContext interrupted");
            }
        }));
    }

    /** 获取异步执行器：优先宿主异步调度器，回退 ForkJoinPool */
    private static java.util.concurrent.Executor getAsyncExecutor(Interpreter interp) {
        NovaScheduler sched = interp.getScheduler();
        if (sched != null) {
            java.util.concurrent.Executor async = sched.asyncExecutor();
            if (async != null) return async;
        }
        return java.util.concurrent.ForkJoinPool.commonPool();
    }
}
