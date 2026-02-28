package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.*;
import nova.runtime.stdlib.StructuredConcurrencyHelper;
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
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.compiler.hirtype.*;
import com.novalang.ir.lowering.AstToHirLowering;
import com.novalang.ir.mir.MirModule;
import com.novalang.ir.pass.PassPipeline;
import com.novalang.ir.pass.hir.HirConstantFolding;
import com.novalang.ir.pass.hir.HirDeadCodeElimination;
import com.novalang.ir.pass.hir.HirInlineExpansion;
import com.novalang.ir.pass.mir.*;
import nova.runtime.interpreter.reflect.*;

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

    // ============ HIR 特有字段 ============
    /** 类名 → 实例字段列表（含初始化器），用于实例化时初始化属性 */
    final Map<String, List<HirField>> classInstanceFields = new HashMap<>();
    /** 类名 → 有序实例初始化列表（HirField + init 块 AstNode），按声明顺序 */
    final Map<String, List<AstNode>> classInstanceInitializers = new HashMap<>();
    /** 缓存: className → (fieldName → HirField)，仅含有自定义 getter 的字段 */
    final Map<String, Map<String, HirField>> customGetterCache = new HashMap<>();
    /** 缓存: className → (fieldName → HirField)，仅含有自定义 setter 的字段 */
    final Map<String, Map<String, HirField>> customSetterCache = new HashMap<>();
    /** 类名 → 所有 HirField（用于反射 API 获取字段信息） */
    final Map<String, List<HirField>> hirClassFields = new HashMap<>();
    /** 类名 → 超类构造器参数表达式（用于 Nova→Nova 继承时调用超类构造器） */
    final Map<String, List<Expression>> hirSuperCtorArgs = new HashMap<>();
    /** reified 类型参数传递（visitCall → executeBoundMethod） */
    List<HirType> pendingHirTypeArgs;
    /** 类注解缓存（类名 → 注解列表） */
    final Map<String, List<HirAnnotation>> hirClassAnnotations = new HashMap<>();
    /** 字段信号替代 ControlFlow 异常（unlabeled return）—— 4.7x 性能提升 */
    private final ThreadLocal<Boolean> tlHasReturn = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<NovaValue> tlReturnValue = new ThreadLocal<>();

    // 便捷访问器（减少对已有代码的改动量）
    boolean getHasReturn() { return tlHasReturn.get(); }
    void setHasReturn(boolean v) { tlHasReturn.set(v); }
    NovaValue getReturnValue() { return tlReturnValue.get(); }
    void setReturnValue(NovaValue v) { tlReturnValue.set(v); }

    /** HIR 多线程 SAM 回调：所有者线程 ID */
    private final long hirOwnerThreadId = getThreadId(Thread.currentThread());
    /** HIR 多线程 SAM 回调：线程隔离子解释器 */
    private final ThreadLocal<Interpreter> hirThreadLocalChild = new ThreadLocal<>();

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

        // 安全策略：设置 MethodHandleCache 的 setAccessible 守卫
        MethodHandleCache.setAllowSetAccessible(policy.isSetAccessibleAllowed());

        // 注册 NovaValue 回退转换器（将未知 Java 对象包装为 NovaExternalObject）
        AbstractNovaValue.setFallbackConverter(NovaExternalObject::new);

        // 注册内置注解处理器（直接操作 map 避免 this-escape 警告）
        NovaAnnotationProcessor dataProc = new nova.runtime.interpreter.builtin.DataAnnotationProcessor();
        NovaAnnotationProcessor builderProc = new nova.runtime.interpreter.builtin.BuilderAnnotationProcessor();
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
            NovaClass cls;
            if (arg instanceof NovaClass) cls = (NovaClass) arg;
            else if (arg instanceof NovaObject) cls = ((NovaObject) arg).getNovaClass();
            else throw new NovaRuntimeException("classOf() requires a class or object");
            return buildHirClassInfo(cls);
        }), false);

        // Helper 委托对象（放在构造函数末尾，避免 this-escape 警告）
        this.typeResolver = new TypeResolver(this);
        this.memberResolver = new MemberResolver(this);
        this.functionExecutor = new FunctionExecutor(this);
        this.hirEvaluator = new HirEvaluator(this);
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
        this.classInstanceFields.putAll(parent.classInstanceFields);
        this.classInstanceInitializers.putAll(parent.classInstanceInitializers);
        this.customGetterCache.putAll(parent.customGetterCache);
        this.customSetterCache.putAll(parent.customSetterCache);
        this.hirClassFields.putAll(parent.hirClassFields);
        this.hirSuperCtorArgs.putAll(parent.hirSuperCtorArgs);
        this.scheduler = parent.scheduler;

        // Helper 委托对象
        this.typeResolver = new TypeResolver(this, parent.typeResolver);
        this.memberResolver = new MemberResolver(this);
        this.functionExecutor = new FunctionExecutor(this);
        this.hirEvaluator = new HirEvaluator(this);
        this.javaInteropHelper = new JavaInteropHelper(this);
        this.mirInterpreter = new MirInterpreter(this, parent.mirInterpreter);
    }

    // ============ I/O 流配置============

    public PrintStream getStdout() { return stdout; }

    public void setStdout(PrintStream stdout) { this.stdout = stdout; }

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
            nova.runtime.stdlib.StructuredConcurrencyHelper.DISPATCHERS.Main = scheduler.mainExecutor();
        } else {
            nova.runtime.stdlib.StructuredConcurrencyHelper.DISPATCHERS.Main = null;
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
        if (getThreadId(Thread.currentThread()) != hirOwnerThreadId) {
            // 外部线程：使用 ThreadLocal 缓存的子 Interpreter
            Interpreter child = hirThreadLocalChild.get();
            if (child == null) {
                child = new Interpreter(this);
                hirThreadLocalChild.set(child);
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
        return extensionRegistry.findExtension(receiver, methodName);
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

    public void registerNovaExtensionProperty(String typeName, String propertyName,
                                               Expression getter, Environment closure) {
        extensionRegistry.registerNovaExtensionProperty(typeName, propertyName, getter, closure);
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
        AstToHirLowering lowering = new AstToHirLowering();
        com.novalang.ir.lowering.LoweringContext lctx = new com.novalang.ir.lowering.LoweringContext();
        AstNode hirNode = ((AstNode) expr).accept(lowering, lctx);
        return executeAstNode(hirNode);
    }

    /** 预编译源码为 MIR 模块（基准测试用，不执行） */
    MirModule precompileToMir(String source) {
        Lexer lexer = new Lexer(source, "<bench>");
        Parser parser = new Parser(lexer, "<bench>");
        Program program = parser.parse();
        mirPipeline.setScriptMode(true);
        return mirPipeline.executeToMir(program);
    }

    /** 执行预编译的 MIR 模块（基准测试用） */
    NovaValue executeMir(MirModule mir) {
        resetExecutionState();
        mirInterpreter.resetState();
        return mirInterpreter.executeModule(mir);
    }

    /** 重置每次 eval 的执行状态计数器 */
    private void resetExecutionState() {
        this.executionStartNanos = System.nanoTime();
        this.totalLoopIterations = 0;
        this.callDepth = 0;
        this.callStack.clear();
    }

    /** 创建 Any 类型扩展方法（toString/hashCode/equals + 作用域函数） */
    private static Map<String, nova.runtime.NovaCallable> createAnyMethods() {
        Map<String, nova.runtime.NovaCallable> m = new HashMap<>();
        m.put("toString", new NovaNativeFunction("toString", 1,
            (ctx, args) -> NovaString.of(args.get(0).asString())));
        m.put("hashCode", new NovaNativeFunction("hashCode", 1,
            (ctx, args) -> NovaInt.of(args.get(0).hashCode())));
        m.put("equals", new NovaNativeFunction("equals", 2,
            (ctx, args) -> NovaBoolean.of(args.get(0).equals(args.get(1)))));
        m.put("let", new NovaNativeFunction("let", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            nova.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            return block.call(ctx, Collections.singletonList(self));
        }));
        m.put("also", new NovaNativeFunction("also", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            nova.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
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
                    return ((nova.runtime.NovaCallable) self).call(ctx, Collections.emptyList());
                }
                return NovaNull.UNIT;
            }
            NovaValue self = args.get(0);
            nova.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            NovaBoundMethod bound = new NovaBoundMethod(self, block);
            return ctx.executeBoundMethod(bound, Collections.<NovaValue>emptyList(), null);
        }));
        m.put("apply", new NovaNativeFunction("apply", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            nova.runtime.NovaCallable block = ctx.asCallable(args.get(1), "Any.method");
            NovaBoundMethod bound = new NovaBoundMethod(self, block);
            ctx.executeBoundMethod(bound, Collections.<NovaValue>emptyList(), null);
            return self;
        }));
        m.put("takeIf", new NovaNativeFunction("takeIf", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            nova.runtime.NovaCallable predicate = ctx.asCallable(args.get(1), "Any.method");
            NovaValue result = predicate.call(ctx, Collections.singletonList(self));
            return result.asBoolean() ? self : NovaNull.NULL;
        }));
        m.put("takeUnless", new NovaNativeFunction("takeUnless", 2, (ctx, args) -> {
            NovaValue self = args.get(0);
            nova.runtime.NovaCallable predicate = ctx.asCallable(args.get(1), "Any.method");
            NovaValue result = predicate.call(ctx, Collections.singletonList(self));
            return result.asBoolean() ? NovaNull.NULL : self;
        }));
        return m;
    }

    /** 创建默认 MIR 优化管线（主构造器和子构造器共用） */
    private static PassPipeline createDefaultPipeline() {
        PassPipeline p = new PassPipeline();
        p.addHirPass(new HirInlineExpansion());
        p.addHirPass(new HirConstantFolding());
        p.addHirPass(new HirDeadCodeElimination());
        p.addMirPass(new DeadBlockElimination());
        p.addMirPass(new LoopInvariantCodeMotion());
        p.addMirPass(new TailCallElimination());
        p.addMirPass(new StrengthReduction());
        p.addMirPass(new MirLocalCSE());
        p.addMirPass(new MirPeepholeOptimization());
        p.addMirPass(new BlockMerging());
        p.addMirPass(new DeadBlockElimination());
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
        mirInterpreter.resetState();
        return mirInterpreter.executeModule(mir);
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

    /** ExecutionContext 接口方法：执行 HIR 函数 */
    @Override
    public NovaValue executeHirFunction(NovaValue function, List<NovaValue> args,
                                         Map<String, NovaValue> namedArgs) {
        return functionExecutor.executeHirFunction((HirFunctionValue) function, args, namedArgs);
    }

    /** ExecutionContext 接口方法：执行 HIR Lambda */
    @Override
    public NovaValue executeHirLambda(NovaValue lambda, List<NovaValue> args) {
        return functionExecutor.executeHirLambda((HirLambdaValue) lambda, args);
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
        if (pendingHirTypeArgs != null && index < pendingHirTypeArgs.size()) {
            return getHirTypeName(pendingHirTypeArgs.get(index));
        }
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


    // ============ HIR Evaluator (delegated to HirEvaluator) ============

    final HirEvaluator hirEvaluator;

    public NovaValue executeHirModule(HirModule module) {
        return hirEvaluator.executeHirModule(module);
    }

    public NovaValue evaluateHir(Expression expr) {
        return hirEvaluator.evaluateHir(expr);
    }

    NovaValue executeHirStmt(Statement stmt) {
        return hirEvaluator.executeHirStmt(stmt);
    }

    NovaValue executeAstNode(AstNode node) {
        return hirEvaluator.executeAstNode(node);
    }

    protected NovaRuntimeException hirError(String message, AstNode node) {
        return hirEvaluator.hirError(message, node);
    }

    // ============ HIR Function Execution (delegated to FunctionExecutor) ============

    ExtensionRegistry.HirExtProp findHirExtensionProperty(NovaValue receiver, String propertyName) {
        return extensionRegistry.findHirExtensionProperty(receiver, propertyName);
    }

    NovaValue executeHirExtensionPropertyGetter(ExtensionRegistry.HirExtProp prop, NovaValue receiver) {
        return extensionRegistry.executeHirExtensionPropertyGetter(prop, receiver, this);
    }

    HirField findHirFieldWithGetter(String className, String fieldName) {
        return functionExecutor.findHirFieldWithGetter(className, fieldName);
    }

    HirField findHirFieldWithSetter(String className, String fieldName) {
        return functionExecutor.findHirFieldWithSetter(className, fieldName);
    }

    void executeHirCustomSetter(HirField field, NovaObject obj, NovaValue value) {
        functionExecutor.executeHirCustomSetter(field, obj, value);
    }

    NovaValue executeHirCustomGetter(HirField field, NovaObject obj) {
        return functionExecutor.executeHirCustomGetter(field, obj);
    }

    public NovaValue executeHirFunction(HirFunctionValue function, List<NovaValue> args,
                                         Map<String, NovaValue> namedArgs) {
        return functionExecutor.executeHirFunction(function, args, namedArgs);
    }

    public NovaValue executeHirLambda(HirLambdaValue lambda, List<NovaValue> args) {
        return functionExecutor.executeHirLambda(lambda, args);
    }

    NovaValue executeBlock(Block block, Environment blockEnv) {
        return functionExecutor.executeBlock(block, blockEnv);
    }

    HirLoopSignal executeHirLoopBody(Statement body) {
        return functionExecutor.executeHirLoopBody(body);
    }

    HirLoopSignal executeHirLoopBody(Environment loopEnv, Statement body) {
        return functionExecutor.executeHirLoopBody(loopEnv, body);
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

}
