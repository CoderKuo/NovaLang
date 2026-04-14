package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.reflect.NovaClassInfo;
import com.novalang.runtime.resolution.MethodNameCanonicalizer;
import com.novalang.runtime.types.*;
import com.novalang.runtime.stdlib.StructuredConcurrencyHelper;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.ParseException;
import com.novalang.compiler.parser.Parser;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.compiler.hirtype.*;
import com.novalang.ir.lowering.AstToHirLowering;
import com.novalang.ir.mir.MirModule;
import com.novalang.ir.pass.PassPipeline;
import com.novalang.ir.pass.hir.HirConstantFolding;
import com.novalang.ir.pass.hir.HirDeadCodeElimination;
import com.novalang.ir.pass.hir.HirInlineExpansion;
import com.novalang.ir.pass.mir.*;
import com.novalang.runtime.interpreter.reflect.*;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * NovaLang 解释器。
 *
 * <p>基于 AST 的树遍历解释器，支持完整的 Nova 语言特性。</p>
 */
public class Interpreter implements ExecutionContext {

    /** 全局环境 */
    private final Environment globals;

    /** 当前环境 */
    protected Environment environment;

    /** 是否为 REPL 模式 */
    protected boolean replMode = false;

    /** 扩展函数/属性注册表 */
    final ExtensionRegistry extensionRegistry;

    /** 当前执行的源代码（用于错误报告） */
    protected String currentSource;

    /** 当前执行的源代码行数组（惰性分割，仅错误报告时创建） */
    private String[] sourceLines;


    /** 注解处理器注册表（注解名 -> 处理器列表） */
    protected final Map<String, List<NovaAnnotationProcessor>> annotationProcessors;

    /** 脚本级 ClassLoader（用于隔离脚本的 Maven 依赖） */
    private ClassLoader scriptClassLoader;

    /** 安全策略 */
    final NovaSecurityPolicy securityPolicy;
    /** 安全策略是否有循环/超时限制（快速路径标志，避免虚方法调用） */
    final boolean hasSecurityLimits;

    /** 当前调用深度（递归检查用） */
    protected int callDepth = 0;

    /** 当前调用栈（用于错误堆栈跟踪） */
    protected final NovaCallStack callStack = new NovaCallStack();

    /** 当前执行的文件名（用于堆栈跟踪源码行解析） */
    protected String currentFileName;

    /** 尾位置标志：当前表达式是否处于尾调用位置 */
    boolean inTailPosition = false;
    /** 标识当前是否在求值 CallExpr 的 callee（区分 size 属性访问和 size() 方法调用） */
    protected boolean evaluatingCallee = false;

    /** 记录主线程 ID（用于 SAM 回调检测是否在外部线程） */
    private final long ownerThreadId = getThreadId(Thread.currentThread());

    /** 外部线程的子 Interpreter 缓存（每个线程一个，避免与主线程共享可变状态） */
    private final ThreadLocal<Interpreter> threadLocalChild = new ThreadLocal<>();

    /** 总循环迭代计数（循环限制用） */
    protected long totalLoopIterations = 0;

    /** 执行起始时间纳秒（超时检查用） */
    protected long executionStartNanos = 0;

    /** 兼容 Java 19 以下（getId）和 Java 19+（threadId）的线程 ID 获取 */
    @SuppressWarnings("deprecation")
    private static long getThreadId(Thread t) { return t.getId(); }

    /** 标准输出流 */
    private PrintStream stdout = System.out;

    /** 标准错误流 */
    private PrintStream stderr = System.err;

    /** 命令行参数 */
    private String[] cliArgs;

    /** 标准输入流 */
    private InputStream stdin = System.in;


    /** 模块加载器（脚本模式下初始化） */
    protected ModuleLoader moduleLoader;

    // ============ HIR 元数据字段 ============
    /** 类名 → 所有 HirField（用于反射 API 获取字段信息） */
    final Map<String, List<HirField>> hirClassFields = new HashMap<>();
    /** 类注解缓存（类名 → 注解列表） */
    final Map<String, List<HirAnnotation>> hirClassAnnotations = new HashMap<>();
    /** return 信号字段（替代 ControlFlow 异常，性能提升） */
    private boolean hasReturn = false;
    private NovaValue returnValue;

    boolean getHasReturn() { return hasReturn; }
    void setHasReturn(boolean v) { hasReturn = v; }
    NovaValue getReturnValue() { return returnValue; }
    void setReturnValue(NovaValue v) { returnValue = v; }

    /** HIR 循环信号 */
    enum HirLoopSignal { NORMAL, BREAK, CONTINUE }

    /** Java 通配符导入的包前缀（import java java.util.* 时记录；默认含 java.lang） */
    protected final Set<String> wildcardJavaImports = new HashSet<>(Collections.singleton("java.lang"));

    /** 类型解析器 */
    final TypeResolver typeResolver;

    /** 成员解析辅助 */
    final MemberResolver memberResolver;

    /** 函数执行辅助 */
    final FunctionExecutor functionExecutor;

    /** Java 互操作辅助 */
    private final JavaInteropHelper javaInteropHelper;

    /** 可插拔宿主调度器（Bukkit 等环境注入） */
    private NovaScheduler scheduler;

    // ============ MIR 解释器字段 ============

    /** MIR 解释器实例 */
    MirInterpreter mirInterpreter;

    /** MIR 优化管线 */
    private PassPipeline mirPipeline;
    /** 测试用：获取 MIR 管线（分阶段计时） */
    PassPipeline getMirPipeline() { return mirPipeline; }

    public Interpreter() {
        this(NovaSecurityPolicy.unrestricted());
    }

    @SuppressWarnings("this-escape")
    public Interpreter(NovaSecurityPolicy policy) {
        this.securityPolicy = policy;
        this.hasSecurityLimits = policy.getMaxLoopIterations() > 0 || policy.getMaxExecutionTimeMs() > 0;
        this.globals = new Environment();
        this.environment = globals;
        this.extensionRegistry = new ExtensionRegistry();
        this.annotationProcessors = new HashMap<String, List<NovaAnnotationProcessor>>();

        // 安全策略：设置 setAccessible 守卫（统一使用 LambdaUtils 的 ThreadLocal）
        com.novalang.runtime.stdlib.LambdaUtils.setAllowSetAccessible(policy.isSetAccessibleAllowed());
        MethodHandleCache.setAllowSetAccessible(policy.isSetAccessibleAllowed());

        // 注册 NovaValue 回退转换器（将未知 Java 对象包装为 NovaExternalObject）
        AbstractNovaValue.setFallbackConverter(NovaExternalObject::new);

        // 注册内置注解处理器（直接操作 map 避免 this-escape 警告）
        NovaAnnotationProcessor dataProc = new com.novalang.runtime.interpreter.builtin.DataAnnotationProcessor();
        NovaAnnotationProcessor builderProc = new com.novalang.runtime.interpreter.builtin.BuilderAnnotationProcessor();
        this.annotationProcessors.computeIfAbsent(dataProc.getAnnotationName(), k -> new ArrayList<>()).add(dataProc);
        this.annotationProcessors.computeIfAbsent(builderProc.getAnnotationName(), k -> new ArrayList<>()).add(builderProc);

        // 注册内置函数（传入策略，条件注册）
        Builtins.register(globals, securityPolicy);

        // 注册 Java 互操作（传入策略，条件注册）
        JavaInterop.register(globals, securityPolicy);

        // 标记内置变量边界（用户定义同名变量时允许 shadowing，但 var 重定义会报错）
        globals.sealBuiltins();

        // MIR 编译管线初始化
        this.mirPipeline = createDefaultPipeline();

        // 注册 Any 类型扩展函数
        this.extensionRegistry.novaExtensions.put("Any", createAnyMethods());

        // 重写 classOf 以支持 HIR 路径
        environment.redefine("classOf", new NovaNativeFunction("classOf", 1, (interp, args) -> {
            NovaValue arg = args.get(0);
            if (arg instanceof NovaClass) return buildHirClassInfo((NovaClass) arg);
            if (arg instanceof NovaObject) return buildHirClassInfo(((NovaObject) arg).getNovaClass());
            if (arg instanceof ScalarizedNovaObject) return buildHirClassInfo(((ScalarizedNovaObject) arg).getNovaClass());
            if (arg instanceof NovaExternalObject) {
                Object javaVal = ((NovaExternalObject) arg).getJavaObject();
                if (javaVal instanceof Class) return NovaClassInfo.fromJavaClass((Class<?>) javaVal);
                return NovaClassInfo.fromJavaClass(javaVal.getClass());
            }
            throw new NovaRuntimeException(NovaException.ErrorKind.TYPE_MISMATCH, "classOf() 需要类或对象参数", null);
        }), false);

        // Helper 委托对象（放在构造函数末尾，避免 this-escape 警告）
        this.typeResolver = new TypeResolver(this);
        this.memberResolver = new MemberResolver(this);
        this.functionExecutor = new FunctionExecutor(this);

        this.javaInteropHelper = new JavaInteropHelper(this);
        this.mirInterpreter = new MirInterpreter(this);
    }

    /**
     * 子 Interpreter 构造器（供外部线程 SAM 回调使用）。
     * 共享父级的只读状态（globals、扩展注册表等）。
     * 拥有独立的可变执行状态（environment、callDepth 等）。
     */
    Interpreter(Interpreter parent) {
        this.securityPolicy = parent.securityPolicy;
        this.hasSecurityLimits = parent.hasSecurityLimits;
        // 子线程的 ThreadLocal 需独立初始化
        com.novalang.runtime.stdlib.LambdaUtils.setAllowSetAccessible(securityPolicy.isSetAccessibleAllowed());
        MethodHandleCache.setAllowSetAccessible(securityPolicy.isSetAccessibleAllowed());
        this.globals = parent.globals;
        this.environment = new Environment(parent.globals);
        this.extensionRegistry = parent.extensionRegistry;
        this.annotationProcessors = parent.annotationProcessors;
        this.stdout = parent.stdout;
        this.stderr = parent.stderr;
        this.stdin = parent.stdin;
        // MIR 管线：子解释器创建独立实例，避免与父线程共享可变状态
        this.mirPipeline = createDefaultPipeline();
        this.hirClassFields.putAll(parent.hirClassFields);
        this.scheduler = parent.scheduler;

        // Helper 委托对象
        this.typeResolver = new TypeResolver(this, parent.typeResolver);
        this.memberResolver = new MemberResolver(this);
        this.functionExecutor = new FunctionExecutor(this);

        this.javaInteropHelper = new JavaInteropHelper(this);
        this.mirInterpreter = new MirInterpreter(this, parent.mirInterpreter);
    }

    /**
     * 释放 Interpreter 持有的 ThreadLocal 和缓存资源。
     * 长期运行场景（服务端、REPL）在 Interpreter 不再使用时应调用此方法。
     */
    public void cleanup() {
        threadLocalChild.remove();
    }

    // ============ I/O 流配置============

    public PrintStream getStdout() { return stdout; }

    public void setStdout(PrintStream stdout) {
        this.stdout = stdout;
        NovaPrint.setOut(stdout);
    }

    public PrintStream getStderr() { return stderr; }

    public void setStderr(PrintStream stderr) { this.stderr = stderr; }

    public InputStream getStdin() { return stdin; }

    public String[] getCliArgs() { return cliArgs; }

    public void setCliArgs(String[] args) { this.cliArgs = args; }

    public void setStdin(InputStream stdin) { this.stdin = stdin; }

    // ============ 调度器 ============

    public NovaScheduler getScheduler() { return scheduler; }

    public void setScheduler(NovaScheduler scheduler) {
        this.scheduler = scheduler;
        // 编译路径：全局静态持有者 + Dispatchers.Main
        SchedulerHolder.set(scheduler);
        if (scheduler != null && scheduler.mainExecutor() != null) {
            com.novalang.runtime.stdlib.StructuredConcurrencyHelper.DISPATCHERS.Main = scheduler.mainExecutor();
        } else {
            com.novalang.runtime.stdlib.StructuredConcurrencyHelper.DISPATCHERS.Main = null;
        }
        // 解释器路径：动态注入 Dispatchers["Main"]
        if (scheduler != null && scheduler.mainExecutor() != null) {
            NovaValue dispatchers = globals.tryGet("Dispatchers");
            if (dispatchers instanceof NovaMap) {
                ((NovaMap) dispatchers).put(NovaString.of("Main"),
                        new NovaExternalObject(scheduler.mainExecutor()));
            }
        }
    }

    /** 重置全局调度器状态（用于测试清理或多实例场景） */
    public static void resetGlobalSchedulerState() {
        SchedulerHolder.clear();
        StructuredConcurrencyHelper.resetGlobalState();
    }

    // ============ 模块系统 ============

    public void setScriptBasePath(Path basePath) {
        this.moduleLoader = new ModuleLoader(basePath);
    }

    /** 在指定环境中执行模块（供 ModuleLoader 调用）*/
    public void executeModule(String source, String fileName, Environment moduleEnv) {
        withEnvironment(moduleEnv, () -> {
            Lexer lexer = new Lexer(source, fileName);
            Parser parser = new Parser(lexer, fileName);
            Program program = parser.parse();
            mirPipeline.setScriptMode(true);
            MirModule mir = mirPipeline.executeToMir(program);
            mirInterpreter.resetState();
            mirInterpreter.executeModule(mir);
            return NovaNull.UNIT;
        });
    }

    /**
     * 获取全局环境
     */
    public Environment getGlobals() {
        return globals;
    }

    /**
     * 在 SAM 回调中安全执行 NovaCallable（处理多线程状态隔离）。
     * 当 SAM lambda 在外部线程（如 ExecutorService）中被调用时，
     * 使用独立的子 Interpreter 避免与主线程竞争可变状态。
     */
    public NovaValue executeSamCallback(NovaCallable callable, List<NovaValue> args) {
        if (getThreadId(Thread.currentThread()) != ownerThreadId) {
            // 外部线程：使用 ThreadLocal 缓存的子 Interpreter
            Interpreter child = threadLocalChild.get();
            if (child == null) {
                child = new Interpreter(this);
                threadLocalChild.set(child);
            }
            return callable.call(child, args);
        }
        // 主线程：直接执行
        return callable.call(this, args);
    }

    /**
     * 获取安全策略
     */
    public NovaSecurityPolicy getSecurityPolicy() {
        return securityPolicy;
    }

    /**
     * 批量注册带有 @NovaFunc 注解的 public static 方法到全局环境。
     */
    public void registerAll(Class<?> clazz) {
        NovaRegistry.registerAll(globals, clazz);
    }

    // ============ 注解处理器 ============

    /**
     * 注册注解处理器
     */
    @Override
    public void registerAnnotationProcessor(NovaAnnotationProcessor processor) {
        annotationProcessors.computeIfAbsent(processor.getAnnotationName(), k -> new ArrayList<>())
                .add(processor);
    }

    /**
     * 注销注解处理器
     */
    @Override
    public void unregisterAnnotationProcessor(NovaAnnotationProcessor processor) {
        List<NovaAnnotationProcessor> list = annotationProcessors.get(processor.getAnnotationName());
        if (list != null) list.remove(processor);
    }

    /** 设置脚本级 ClassLoader */
    public void setScriptClassLoader(ClassLoader cl) {
        this.scriptClassLoader = cl;
    }

    /** 获取脚本级 ClassLoader */
    public ClassLoader getScriptClassLoader() {
        return scriptClassLoader;
    }

    /**
     * 获取当前环境
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * 设置 REPL 模式
     */
    public void setReplMode(boolean replMode) {
        this.replMode = replMode;
    }

    public boolean isReplMode() {
        return replMode;
    }

    // ============ 扩展注册表委托方法 ============

    ExtensionRegistry extensions() { return extensionRegistry; }

    public void registerExtension(Class<?> targetType, String methodName, NovaCallable method) {
        extensionRegistry.registerExtension(targetType, methodName, method);
    }

    public NovaCallable findExtension(NovaValue receiver, String methodName) {
        NovaCallable ext = extensionRegistry.findExtension(receiver, methodName);
        if (ext != null) return ext;
        // 回退到 shared() 全局扩展注册表（com.novalang.runtime.ExtensionRegistry）
        com.novalang.runtime.ExtensionRegistry sharedReg = NovaRuntime.shared().getExtensionRegistry();
        if (sharedReg != null) {
            Object javaTarget = receiver.toJavaValue();
            Class<?> targetClass = javaTarget != null ? javaTarget.getClass() : Object.class;
            com.novalang.runtime.ExtensionRegistry.RegisteredExtension sharedExt =
                    sharedReg.lookupAny(targetClass, methodName);
            if (sharedExt != null) {
                final Object finalTarget = javaTarget;
                return new NovaNativeFunction(methodName, -1, (ctx, args) -> {
                    Object[] javaArgs = new Object[args.size() - 1];
                    for (int i = 1; i < args.size(); i++) javaArgs[i - 1] = args.get(i).toJavaValue();
                    try {
                        Object result = sharedExt.invoke(finalTarget, javaArgs);
                        return AbstractNovaValue.fromJava(result);
                    } catch (Exception e) {
                        throw new NovaRuntimeException(e.getMessage(), e);
                    }
                });
            }
        }
        return null;
    }

    public NovaCallable getExtension(Class<?> targetType, String methodName) {
        return extensionRegistry.getExtension(targetType, methodName);
    }

    public void registerNovaExtension(String typeName, String methodName, NovaCallable function) {
        extensionRegistry.registerNovaExtension(typeName, methodName, function);
    }

    public NovaCallable findNovaExtension(NovaValue receiver, String methodName) {
        return extensionRegistry.findNovaExtension(receiver, methodName);
    }


    public void registerExtensionProperty(String typeName, String propertyName, NovaCallable getter) {
        extensionRegistry.registerExtensionProperty(typeName, propertyName, getter);
    }

    public ExtensionRegistry.ExtensionProperty findNovaExtensionProperty(NovaValue receiver, String propertyName) {
        return extensionRegistry.findNovaExtensionProperty(receiver, propertyName);
    }

    /**
     * Environment save/restore 辅助方法：在临时环境中执行代码块，自动恢复原环境。
     * 避免重复的 try-finally 模式。
     */
    protected NovaValue withEnvironment(Environment env, java.util.function.Supplier<NovaValue> body) {
        Environment saved = environment;
        environment = env;
        try {
            return body.get();
        } finally {
            environment = saved;
        }
    }

    /**
     * 安全类型转换辅助方法：将 NovaValue 转换为 NovaCallable，失败时抛出友好错误。
     */
    public NovaCallable asCallable(NovaValue value, String context) {
        if (value instanceof NovaCallable) {
            return (NovaCallable) value;
        }
        // MIR lambda: NovaObject with invoke method
        if (value instanceof NovaObject) {
            NovaBoundMethod bound = ((NovaObject) value).getBoundMethod("invoke");
            if (bound != null) return bound;
        }
        throw error("Expected callable (function/lambda) in " + context + ", got " + value.getTypeName(), (SourceLocation) null);
    }

    public NovaValue executeExtensionPropertyGetter(ExtensionRegistry.ExtensionProperty prop, NovaValue receiver) {
        return extensionRegistry.executeExtensionPropertyGetter(prop, receiver, this);
    }

    /**
     * 解析并执行源代码
     */
    public NovaValue eval(String source) {
        return eval(source, "<eval>");
    }

    /**
     * 解析并执行源代码（MIR 编译管线）
     */
    public NovaValue eval(String source, String fileName) {
        resetExecutionState();
        this.currentSource = source;
        this.currentFileName = fileName;
        this.sourceLines = null;

        try {
            Lexer lexer = new Lexer(source, fileName);
            Parser parser = new Parser(lexer, fileName);
            return executeMirPipeline(parser.parse());
        } catch (NovaRuntimeException | ParseException e) {
            throw e;
        } catch (Exception e) {
            NovaRuntimeException wrapped = new NovaRuntimeException(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    /**
     * 执行预编译的 Program（供 JSR-223 CompiledScript 文件模式使用）
     */
    public NovaValue eval(String source, String fileName, Program program) {
        resetExecutionState();
        this.currentSource = source;
        this.currentFileName = fileName;
        this.sourceLines = null;

        try {
            return executeMirPipeline(program);
        } catch (NovaRuntimeException | ParseException e) {
            throw e;
        } catch (Exception e) {
            NovaRuntimeException wrapped = new NovaRuntimeException(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    /**
     * REPL 模式执行（MIR 管线）
     */
    public NovaValue evalRepl(String source) {
        resetExecutionState();
        this.currentSource = source;
        this.currentFileName = "<repl>";
        this.sourceLines = null;

        try {
            Lexer lexer = new Lexer(source, "<repl>");
            Parser parser = new Parser(lexer, "<repl>");
            return executeMirPipeline(parser.parse());
        } catch (NovaRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new NovaRuntimeException(
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 评估 AST Expression：降级为 HIR 后执行。
     * 用于注解参数求值等仍持有 AST Expression 的路径。
     */
    NovaValue evaluate(Expression expr) {
        return MirClassRegistrar.foldExpression(expr, this);
    }

    /** 预编译源码为 MIR 模块（基准测试用，不执行） */
    MirModule precompileToMir(String source) {
        Lexer lexer = new Lexer(source, "<bench>");
        Parser parser = new Parser(lexer, "<bench>");
        Program program = parser.parse();
        mirPipeline.setScriptMode(true);
        return mirPipeline.executeToMir(program);
    }

    static final class PreparedMirModule {
        final MirModule mir;
        final MirInterpreter.PreparedModule prepared;

        PreparedMirModule(MirModule mir, MirInterpreter.PreparedModule prepared) {
            this.mir = mir;
            this.prepared = prepared;
        }
    }

    PreparedMirModule prepareMirForReuse(MirModule mir) {
        mirInterpreter.resetState();
        return new PreparedMirModule(mir, mirInterpreter.prepareModule(mir));
    }

    NovaValue executePreparedMir(PreparedMirModule prepared) {
        resetExecutionState();
        mirInterpreter.resetExecutionState();
        return mirInterpreter.executePreparedModule(prepared != null ? prepared.prepared : null);
    }

    /** ?????? MIR ????????? */
    NovaValue executeMir(MirModule mir) {
        resetExecutionState();
        mirInterpreter.resetState();
        return mirInterpreter.executeModule(mir);
    }

    private void resetExecutionState() {
        this.executionStartNanos = System.nanoTime();
        this.totalLoopIterations = 0;
        this.callDepth = 0;
        this.callStack.clear();
        // 设置编译模式安全策略上下文
        NovaSecurityPolicy.setCurrent(securityPolicy);
        if (securityPolicy.getLevel() != NovaSecurityPolicy.Level.UNRESTRICTED) {
            securityPolicy.resetCounters();
        }
    }

    /** 创建 Any 类型扩展方法（toString/hashCode/equals + 作用域函数） */
    private static Map<String, com.novalang.runtime.NovaCallable> createAnyMethods() {
        Map<String, com.novalang.runtime.NovaCallable> m = new HashMap<>();
        m.put("toString", new NovaNativeFunction("toString", 1,
            (ctx, args) -> NovaString.of(args.get(0).asString())));
        m.put("hashCode", new NovaNativeFunction("hashCode", 1,
            (ctx, args) -> NovaInt.of(args.get(0).hashCode())));
        m.put("equals", new NovaNativeFunction("equals", 2,
            (ctx, args) -> NovaBoolean.of(anyEquals(args.get(0), args.get(1)))));
        m.put("let", new NovaNativeFunction("let", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            com.novalang.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            return block.call(ctx, Collections.singletonList(self));
        }));
        m.put("also", new NovaNativeFunction("also", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            com.novalang.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            block.call(ctx, Collections.singletonList(self));
            return self;
        }));
        m.put("run", new NovaNativeFunction("run", 2, (ctx, args) -> {
            if (args.size() < 2) {
                NovaValue self = args.get(0);
                if (self instanceof NovaExternalObject) {
                    return ((NovaExternalObject) self).invokeMethod("run", Collections.emptyList());
                }
                if (self.isCallable()) {
                    return ((com.novalang.runtime.NovaCallable) self).call(ctx, Collections.emptyList());
                }
                return NovaNull.UNIT;
            }
            NovaValue self = args.get(0);
            com.novalang.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            NovaBoundMethod bound = new NovaBoundMethod(self, block);
            return ctx.executeBoundMethod(bound, Collections.<NovaValue>emptyList(), null);
        }));
        m.put("apply", new NovaNativeFunction("apply", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            com.novalang.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            NovaBoundMethod bound = new NovaBoundMethod(self, block);
            ctx.executeBoundMethod(bound, Collections.<NovaValue>emptyList(), null);
            return self;
        }));
        m.put("takeIf", new NovaNativeFunction("takeIf", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            com.novalang.runtime.NovaCallable predicate = ctx.asCallable(args.get(1), "Any.method");
            NovaValue result = predicate.call(ctx, Collections.singletonList(self));
            return result.asBoolean() ? self : NovaNull.NULL;
        }));
        m.put("takeUnless", new NovaNativeFunction("takeUnless", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            com.novalang.runtime.NovaCallable predicate = ctx.asCallable(args.get(1), "Any.method");
            NovaValue result = predicate.call(ctx, Collections.singletonList(self));
            return result.asBoolean() ? NovaNull.NULL : self;
        }));
        return m;
    }

    private static boolean anyEquals(NovaValue a, NovaValue b) {
        if (a == b) return true;
        if (a == null || a instanceof NovaNull) return b == null || b instanceof NovaNull;
        if (b == null || b instanceof NovaNull) return false;
        return a.equals(b) || b.equals(a);
    }

    /** 创建默认 MIR 优化管线（主构造器和子构造器共用） */
    private static PassPipeline createDefaultPipeline() {
        PassPipeline p = new PassPipeline();
        p.addHirPass(new HirInlineExpansion());
        p.addHirPass(new HirConstantFolding());
        p.addHirPass(new HirDeadCodeElimination());
        p.addMirPass(new DeadBlockElimination());
        p.addMirPass(new LoopInvariantCodeMotion());
        p.addMirPass(new LoopDeadStoreElimination());
        p.addMirPass(new TailCallElimination());
        p.addMirPass(new StrengthReduction());
        p.addMirPass(new MirLocalCSE());
        p.addMirPass(new MirPeepholeOptimization());
        p.addMirPass(new BlockMerging());
        p.addMirPass(new DeadBlockElimination());
        p.setEnableSemanticAnalysis(true);
        p.setStrictSemanticMode(true);
        p.setInterpreterMode(true);
        return p;
    }

    /**
     * 统一的 MIR 管线执行流程（eval/evalRepl/executeModule 共用）。
     * 配置管线 → 编译 AST → 重置 MIR 状态 → 执行 MIR 模块。
     */
    private NovaValue executeMirPipeline(Program program) {
        mirPipeline.setScriptMode(true);
        mirPipeline.setExternalClassNames(mirInterpreter.getKnownClassNames());
        mirPipeline.setExternalInterfaceNames(mirInterpreter.getKnownInterfaceNames());
        MirModule mir = mirPipeline.executeToMir(program);

        // 处理文件注解（在执行前）
        processFileAnnotations(mir);

        mirInterpreter.resetState();
        NovaRuntime.setCurrentContext(this);
        if (scriptClassLoader != null) {
            JavaInterop.setScriptClassLoader(scriptClassLoader);
        }
        try {
            return mirInterpreter.executeModule(mir);
        } finally {
            JavaInterop.setScriptClassLoader(null);
            NovaRuntime.clearCurrentContext();
        }
    }

    /**
     * 处理文件级注解：遍历 MirModule 中的文件注解，调用已注册的处理器。
     */
    private void processFileAnnotations(MirModule mir) {
        if (mir.getFileAnnotations().isEmpty()) return;
        for (MirModule.FileAnnotationInfo ann : mir.getFileAnnotations()) {
            List<NovaAnnotationProcessor> processors = annotationProcessors.get(ann.name);
            if (processors != null) {
                for (NovaAnnotationProcessor proc : processors) {
                    proc.processFile(ann.args);
                }
            }
        }
    }

    /**
     * 获取当前执行的源代码
     */
    public String getCurrentSource() {
        return currentSource;
    }

    /**
     * 惰性获取源代码行数组（仅错误报告时分割）
     */
    private String[] getSourceLines() {
        if (sourceLines == null && currentSource != null) {
            sourceLines = currentSource.split("\n", -1);
        }
        return sourceLines;
    }

    /**
     * 获取指定行的源代码
     */
    String getSourceLine(int lineNumber) {
        String[] lines = getSourceLines();
        if (lines != null && lineNumber > 0 && lineNumber <= lines.length) {
            return lines[lineNumber - 1];
        }
        return null;
    }

    /**
     * 获取当前调用栈的格式化字符串列表
     */
    @Override
    public List<String> captureStackTrace() {
        String formatted = callStack.formatStackTrace(getSourceLines(), currentFileName);
        if (formatted == null || formatted.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(formatted.split("\n"));
    }

    /**
     * 获取当前调用栈的格式化字符串（便捷方法）
     */
    String captureStackTraceString() {
        return callStack.formatStackTrace(getSourceLines(), currentFileName);
    }

    /**
     * 创建带位置信息的运行时异常
     */
    protected NovaRuntimeException error(String message, AstNode node) {
        if (node != null) {
            SourceLocation loc = node.getLocation();
            String sourceLine = getSourceLine(loc.getLine());
            NovaRuntimeException ex = new NovaRuntimeException(message, loc, sourceLine);
            ex.setNovaStackTrace(captureStackTraceString());
            return ex;
        }
        NovaRuntimeException ex = new NovaRuntimeException(message);
        ex.setNovaStackTrace(captureStackTraceString());
        return ex;
    }

    /**
     * 创建带位置信息的运行时异常
     */
    protected NovaRuntimeException error(String message, SourceLocation loc) {
        if (loc != null) {
            String sourceLine = getSourceLine(loc.getLine());
            NovaRuntimeException ex = new NovaRuntimeException(message, loc, sourceLine);
            ex.setNovaStackTrace(captureStackTraceString());
            return ex;
        }
        NovaRuntimeException ex = new NovaRuntimeException(message);
        ex.setNovaStackTrace(captureStackTraceString());
        return ex;
    }

    // ============ Function Execution (delegated to FunctionExecutor) ============


    public NovaValue executeBoundMethod(NovaBoundMethod bound, List<NovaValue> args,
                                         Map<String, NovaValue> namedArgs) {
        return functionExecutor.executeBoundMethod(bound, args, namedArgs);
    }

    public NovaValue instantiate(NovaClass novaClass, List<NovaValue> args,
                                  Map<String, NovaValue> namedArgs) {
        return functionExecutor.instantiate(novaClass, args, namedArgs);
    }

    /** ExecutionContext 接口方法：支持 NovaValue 参数 */
    @Override
    public NovaValue instantiate(NovaValue novaClass, List<NovaValue> args,
                                  Map<String, NovaValue> namedArgs) {
        return functionExecutor.instantiate((NovaClass) novaClass, args, namedArgs);
    }

    /** ExecutionContext 接口方法：创建子上下文 */
    @Override
    public ExecutionContext createChild() {
        return new Interpreter(this);
    }

    /** ExecutionContext 接口方法：执行绑定方法 */
    @Override
    public NovaValue executeBoundMethod(NovaValue boundMethod, List<NovaValue> args,
                                          Map<String, NovaValue> namedArgs) {
        return functionExecutor.executeBoundMethod((NovaBoundMethod) boundMethod, args, namedArgs);
    }

    /** ExecutionContext 接口方法：执行 HIR 函数（MIR 路径不使用） */
    @Override
    public NovaValue executeHirFunction(NovaValue function, List<NovaValue> args,
                                         Map<String, NovaValue> namedArgs) {
        throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "MIR 路径不支持 HIR 函数执行", null);
    }

    /** ExecutionContext 接口方法：执行 HIR Lambda（MIR 路径不使用） */
    @Override
    public NovaValue executeHirLambda(NovaValue lambda, List<NovaValue> args) {
        throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "MIR 路径不支持 HIR Lambda 执行", null);
    }


    // ============ 循环辅助 ============

    /** 检查循环/超时安全限制 */
    protected void checkLoopLimits() {
        long maxIter = securityPolicy.getMaxLoopIterations();
        if (maxIter > 0) {
            totalLoopIterations++;
            if (totalLoopIterations > maxIter) {
                throw NovaSecurityPolicy.denied(
                        "Maximum loop iterations exceeded (" + maxIter + ")");
            }
        }
        long maxMs = securityPolicy.getMaxExecutionTimeMs();
        if (maxMs > 0 && executionStartNanos > 0) {
            long elapsedMs = (System.nanoTime() - executionStartNanos) / 1_000_000;
            if (elapsedMs > maxMs) {
                throw NovaSecurityPolicy.denied(
                        "Execution timeout exceeded (" + maxMs + "ms)");
            }
        }
    }


    protected Class<?> resolveJavaClass(String fullName) {
        return typeResolver.resolveJavaClass(fullName);
    }

    protected boolean isValueOfType(NovaValue value, String typeName) {
        return typeResolver.isValueOfType(value, typeName);
    }

    /**
     * 内置类型（String/Int/List/Range/Map 等）的隐式 this 成员解析。
     * 用于 run/apply 等作用域函数中，this 绑定到内置类型后支持直接调用成员方法。
     */
    protected NovaValue resolveBuiltinImplicitThis(NovaValue thisVal, String name) {
        return memberResolver.resolveBuiltinImplicitThis(thisVal, name);
    }

    /**
     * 获取待处理的类型参数名称
     */
    public String getPendingTypeArgName(int index) {
        return null;
    }

    protected Class<?> resolveClass(String typeName) {
        return typeResolver.resolveClass(typeName);
    }

    // ============ Java 委托对象创建 ============

    /**
     * 为 NovaObject 创建 Java 委托对象
     */
    protected Object createJavaDelegate(NovaObject instance, NovaClass novaClass,
                                       List<NovaValue> superCtorArgs) {
        return javaInteropHelper.createJavaDelegate(instance, novaClass, superCtorArgs);
    }

    protected NovaValue getMemberMethod(NovaValue receiver, String methodName) {
        // 所有类型共有的方法
        if ("toString".equals(methodName)) {
            return NovaNativeFunction.create("toString", () -> NovaString.of(receiver.asString()));
        }
        // StdlibRegistry 扩展方法 fallback
        return memberResolver.tryStdlibFallback(receiver, methodName);
    }


    // ============ Super 代理器============

    /**
     * 获取当前执行上下文所在的类
     * 通过检查环境中是否有 this 变量来确定
     */
    protected NovaClass getCurrentClass() {
        NovaValue thisVal = environment.tryGet("this");
        if (thisVal instanceof NovaObject) {
            return ((NovaObject) thisVal).getNovaClass();
        }
        return null;
    }

    /**
     * Super 代理，用于访问父类方法
     */
    protected static class NovaSuperProxy extends AbstractNovaValue {
        private final NovaObject instance;
        private final NovaClass superclass;
        private final Class<?> javaSuperclass;

        NovaSuperProxy(NovaObject instance, NovaClass superclass) {
            this(instance, superclass, null);
        }

        NovaSuperProxy(NovaObject instance, NovaClass superclass, Class<?> javaSuperclass) {
            this.instance = instance;
            this.superclass = superclass;
            this.javaSuperclass = javaSuperclass;
        }

        @Override
        public String getTypeName() {
            return "Super";
        }

        @Override
        public Object toJavaValue() {
            return this;
        }

        public NovaObject getInstance() {
            return instance;
        }

        public NovaClass getSuperclass() {
            return superclass;
        }

        public Class<?> getJavaSuperclass() {
            return javaSuperclass;
        }
    }



    protected NovaRuntimeException createError(String message, AstNode node) {
        if (node != null && node.getLocation() != null) {
            SourceLocation loc = node.getLocation();
            String sourceLine = getSourceLine(loc.getLine());
            NovaRuntimeException ex = new NovaRuntimeException(message, loc, sourceLine);
            ex.setNovaStackTrace(captureStackTraceString());
            return ex;
        }
        NovaRuntimeException ex = new NovaRuntimeException(message);
        ex.setNovaStackTrace(captureStackTraceString());
        return ex;
    }

    String getHirTypeName(HirType type) {
        return functionExecutor.getHirTypeName(type);
    }

    /**
     * 获取 data class 的字段名列表（按声明顺序）
     */
    List<String> getDataClassFieldNames(NovaClass cls) {
        return memberResolver.getDataClassFieldNames(cls);
    }

    NovaClassInfo buildHirClassInfo(NovaClass cls) {
        return memberResolver.buildHirClassInfo(cls);
    }

    // ============ ExecutionContext 接口实现 ============

    @Override
    public int getMaxRecursionDepth() {
        return securityPolicy.getMaxRecursionDepth();
    }

    @Override
    public int getCallDepth() {
        return callDepth;
    }

    @Override
    public void incrementCallDepth() {
        callDepth++;
    }

    @Override
    public void decrementCallDepth() {
        callDepth--;
    }

    @Override
    public void pushCallFrame(String functionName, List<NovaValue> args) {
        callStack.push(NovaCallFrame.fromMirCallable(functionName, args));
    }

    @Override
    public void popCallFrame() {
        callStack.pop();
    }

    @Override
    public Object getMirInterpreter() {
        return mirInterpreter;
    }

    /**
     * 以 receiver 为作用域接收者调用 NovaCallable。
     * receiver 的成员在 block 内可直接访问。
     */
    public NovaValue invokeWithReceiver(NovaCallable callable, NovaValue receiver,
                                         java.util.List<NovaValue> args) {
        return mirInterpreter.callDispatcher.withScopeReceiver(receiver,
                () -> callable.call(this, args));
    }

    @Override
    public NovaValue invokeWithScopeReceiver(NovaCallable callable, NovaValue receiver,
                                              java.util.List<NovaValue> args) {
        return invokeWithReceiver(callable, receiver, args);
    }

    /**
     * 从 NovaValue 提取 NovaCallable（直接 callable 或含 invoke 方法的对象）。
     */
    @Override
    public NovaCallable extractCallable(NovaValue value) {
        return mirInterpreter.callDispatcher.extractCallable(value);
    }

    @Override
    public NovaValue callFunction(String name, java.util.List<NovaValue> args) {
        NovaValue func = environment.tryGet(name);
        if (func instanceof NovaCallable) {
            return ((NovaCallable) func).call(this, args);
        }
        return null;
    }

    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
        NovaScheduler sched = getScheduler();
        if (sched != null) {
            java.util.concurrent.Executor async = sched.asyncExecutor();
            if (async != null) return async;
        }
        return java.util.concurrent.ForkJoinPool.commonPool();
    }

    @Override
    public Object runInScope(Object block, boolean supervisor) {
        NovaCallable callable = asCallable(AbstractNovaValue.fromJava(block), "coroutineScope");
        NovaScope scope = new NovaScope(this, NovaDispatchers.DEFAULT, supervisor);
        NovaValue result = callable.call(this, java.util.Collections.singletonList(scope));
        scope.joinAll();
        return result instanceof NovaValue ? ((NovaValue) result).toJavaValue() : result;
    }

}
