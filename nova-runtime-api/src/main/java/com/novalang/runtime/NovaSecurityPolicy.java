package com.novalang.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 安全策略配置类
 *
 * <p>控制 NovaLang 解释器和编译模式的安全行为，限制 Java 互操作、资源使用等。</p>
 *
 * <p>编译模式通过 ThreadLocal + 静态检查方法实现安全拦截：</p>
 * <ul>
 *   <li>{@link #checkLoop()} — 循环回边检查（MirCodeGenerator 织入）</li>
 *   <li>{@link #checkClass(String)} — 类访问检查（NovaBootstrap 调用）</li>
 *   <li>{@link #checkMethod(String, String)} — 方法黑名单检查（NovaBootstrap 调用）</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>
 * // 预定义级别
 * Interpreter interp = new Interpreter(NovaSecurityPolicy.strict());
 *
 * // 自定义策略
 * NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
 *     .allowPackage("java.util")
 *     .denyClass("java.lang.Runtime")
 *     .maxExecutionTime(5000)
 *     .build();
 * Interpreter interp = new Interpreter(policy);
 * </pre>
 */
public final class NovaSecurityPolicy {

    /** 安全级别 */
    public enum Level { UNRESTRICTED, STANDARD, STRICT, CUSTOM }

    private final Level level;

    // --- 类/包 控制 ---
    private final Set<String> allowedPackages;
    private final Set<String> deniedPackages;
    private final Set<String> allowedClasses;
    private final Set<String> deniedClasses;
    private final Set<String> deniedMethods;   // "className.methodName" 格式

    // --- 功能开关 ---
    private final boolean allowJavaInterop;
    private final boolean allowSetAccessible;
    private final boolean allowStdio;
    private final boolean allowFileIO;
    private final boolean allowNetwork;
    private final boolean allowProcessExec;

    // --- 资源限制 ---
    private final long maxExecutionTimeMs;   // 0=无限制
    private final int maxRecursionDepth;     // 0=无限制
    private final long maxLoopIterations;    // 0=无限制
    private final int maxAsyncTasks;         // 0=无限制

    // --- 编译模式运行时状态（per-policy 实例） ---
    private final AtomicLong loopCounter = new AtomicLong(0);
    private volatile long startNanos;

    private NovaSecurityPolicy(Builder builder) {
        this.level = builder.level;
        this.allowedPackages = Collections.unmodifiableSet(new HashSet<>(builder.allowedPackages));
        this.deniedPackages = Collections.unmodifiableSet(new HashSet<>(builder.deniedPackages));
        this.allowedClasses = Collections.unmodifiableSet(new HashSet<>(builder.allowedClasses));
        this.deniedClasses = Collections.unmodifiableSet(new HashSet<>(builder.deniedClasses));
        this.deniedMethods = Collections.unmodifiableSet(new HashSet<>(builder.deniedMethods));
        this.allowJavaInterop = builder.allowJavaInterop;
        this.allowSetAccessible = builder.allowSetAccessible;
        this.allowStdio = builder.allowStdio;
        this.allowFileIO = builder.allowFileIO;
        this.allowNetwork = builder.allowNetwork;
        this.allowProcessExec = builder.allowProcessExec;
        this.maxExecutionTimeMs = builder.maxExecutionTimeMs;
        this.maxRecursionDepth = builder.maxRecursionDepth;
        this.maxLoopIterations = builder.maxLoopIterations;
        this.maxAsyncTasks = builder.maxAsyncTasks;
    }

    // ============ ThreadLocal 上下文（编译模式用） ============

    private static final ThreadLocal<NovaSecurityPolicy> CURRENT = new ThreadLocal<>();

    /** 设置当前线程的安全策略（执行入口调用） */
    public static void setCurrent(NovaSecurityPolicy policy) {
        CURRENT.set(policy);
    }

    /** 获取当前线程的安全策略 */
    public static NovaSecurityPolicy current() {
        return CURRENT.get();
    }

    /** 清除当前线程的安全策略（执行结束时调用） */
    public static void clearCurrent() {
        CURRENT.remove();
    }

    // ============ 编译模式静态检查入口 ============

    /**
     * 循环回边检查（MirCodeGenerator 在回边处织入调用）。
     * UNRESTRICTED 或无策略时立即返回，零开销。
     */
    public static void checkLoop() {
        NovaSecurityPolicy p = CURRENT.get();
        if (p == null || p.level == Level.UNRESTRICTED) return;
        p.doCheckLoop();
    }

    /**
     * 类访问检查（NovaBootstrap fallback 调用）。
     */
    public static void checkClass(String className) {
        NovaSecurityPolicy p = CURRENT.get();
        if (p == null || p.level == Level.UNRESTRICTED) return;
        if (!p.isClassAllowed(className)) {
            throw denied("Cannot access class: " + className);
        }
    }

    /**
     * 方法黑名单检查（NovaBootstrap fallback 调用）。
     */
    public static void checkMethod(String className, String methodName) {
        NovaSecurityPolicy p = CURRENT.get();
        if (p == null || p.level == Level.UNRESTRICTED) return;
        if (!p.isMethodAllowed(className, methodName)) {
            throw denied("Cannot call method: " + className + "." + methodName);
        }
    }

    /** 重置运行时计数器（每次执行前调用） */
    public void resetCounters() {
        loopCounter.set(0);
        startNanos = System.nanoTime();
    }

    private void doCheckLoop() {
        // 迭代计数检查
        if (maxLoopIterations > 0) {
            long count = loopCounter.incrementAndGet();
            if (count > maxLoopIterations) {
                throw denied("Maximum loop iterations exceeded (" + maxLoopIterations + ")");
            }
        }
        // 执行时间检查
        if (maxExecutionTimeMs > 0 && startNanos > 0) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (elapsedMs > maxExecutionTimeMs) {
                throw denied("Execution timeout exceeded (" + maxExecutionTimeMs + "ms)");
            }
        }
    }

    // ============ 预定义工厂方法 ============

    /** 无限制模式（默认），零开销 */
    public static NovaSecurityPolicy unrestricted() {
        return new Builder(Level.UNRESTRICTED)
                .allowJavaInterop(true)
                .allowSetAccessible(true)
                .allowStdio(true)
                .allowFileIO(true)
                .allowNetwork(true)
                .allowProcessExec(true)
                .build();
    }

    /** 标准模式：允许受限的 Java 互操作，阻止危险类 */
    public static NovaSecurityPolicy standard() {
        return new Builder(Level.STANDARD)
                .allowJavaInterop(true)
                .allowSetAccessible(false)
                .allowStdio(true)
                // 安全包白名单
                .allowPackage("java.util")
                .allowPackage("java.math")
                .allowPackage("java.time")
                .allowPackage("java.text")
                .allowPackage("java.lang")
                // 危险包黑名单
                .denyPackage("java.io")
                .denyPackage("java.nio")
                .denyPackage("java.net")
                .denyPackage("java.lang.reflect")
                .denyPackage("java.lang.invoke")
                // 危险类黑名单
                .denyClass("java.lang.Runtime")
                .denyClass("java.lang.ProcessBuilder")
                .denyClass("java.lang.ClassLoader")
                .denyClass("java.lang.Thread")
                .denyClass("java.lang.ThreadGroup")
                // 危险方法黑名单
                .denyMethod("java.lang.System", "exit")
                .denyMethod("java.lang.System", "load")
                .denyMethod("java.lang.System", "loadLibrary")
                .denyMethod("java.lang.System", "setSecurityManager")
                // stdlib 模块权限
                .allowFileIO(true)
                .allowNetwork(true)
                .allowProcessExec(false)
                // 资源限制
                .maxExecutionTime(30_000)
                .maxRecursionDepth(256)
                .maxLoopIterations(10_000_000)
                .maxAsyncTasks(64)
                .build();
    }

    /** 严格模式：完全禁止 Java 互操作 */
    public static NovaSecurityPolicy strict() {
        return new Builder(Level.STRICT)
                .allowJavaInterop(false)
                .allowSetAccessible(false)
                .allowStdio(true)
                // stdlib 模块权限
                .allowFileIO(false)
                .allowNetwork(false)
                .allowProcessExec(false)
                // 资源限制
                .maxExecutionTime(10_000)
                .maxRecursionDepth(128)
                .maxLoopIterations(1_000_000)
                .maxAsyncTasks(16)
                .build();
    }

    /** 自定义模式 Builder */
    public static Builder custom() {
        return new Builder(Level.CUSTOM);
    }

    // ============ 查询方法 ============

    /**
     * 检查是否允许加载指定 Java 类
     *
     * <p>检查优先级（deny 优先于 allow）：</p>
     * <ol>
     *   <li>精确类黑名单 → 拒绝</li>
     *   <li>精确类白名单 → 允许</li>
     *   <li>包黑名单 → 拒绝</li>
     *   <li>包白名单非空 → 不在白名单中则拒绝</li>
     *   <li>其他 → 允许</li>
     * </ol>
     */
    public boolean isClassAllowed(String fullClassName) {
        if (level == Level.UNRESTRICTED) return true;
        if (!allowJavaInterop) return false;

        // 1. 精确类黑名单
        if (deniedClasses.contains(fullClassName)) return false;

        // 2. 精确类白名单
        if (allowedClasses.contains(fullClassName)) return true;

        // 3. 包黑名单（检查类名是否以被拒绝的包为前缀）
        for (String pkg : deniedPackages) {
            if (fullClassName.startsWith(pkg + ".")) return false;
        }

        // 4. 包白名单非空 → 不在白名单中则拒绝
        if (!allowedPackages.isEmpty()) {
            for (String pkg : allowedPackages) {
                if (fullClassName.startsWith(pkg + ".")) return true;
            }
            return false;  // 有白名单但不在其中
        }

        // 5. 其他 → 允许
        return true;
    }

    /**
     * 检查是否允许调用指定类的方法
     */
    public boolean isMethodAllowed(String className, String methodName) {
        if (level == Level.UNRESTRICTED) return true;
        if (!allowJavaInterop) return false;
        return !deniedMethods.contains(className + "." + methodName);
    }

    /** 是否有方法黑名单限制（用于 MirInterpreter 决定是否跳过集合快捷转换） */
    public boolean hasMethodRestrictions() {
        return !deniedMethods.isEmpty();
    }

    public boolean isJavaInteropAllowed() {
        return level == Level.UNRESTRICTED || allowJavaInterop;
    }

    public boolean isSetAccessibleAllowed() {
        return level == Level.UNRESTRICTED || allowSetAccessible;
    }

    public boolean isStdioAllowed() {
        return level == Level.UNRESTRICTED || allowStdio;
    }

    public boolean isFileIOAllowed() {
        return level == Level.UNRESTRICTED || allowFileIO;
    }

    public boolean isNetworkAllowed() {
        return level == Level.UNRESTRICTED || allowNetwork;
    }

    public boolean isProcessExecAllowed() {
        return level == Level.UNRESTRICTED || allowProcessExec;
    }

    public Level getLevel() {
        return level;
    }

    public long getMaxExecutionTimeMs() {
        return maxExecutionTimeMs;
    }

    public int getMaxRecursionDepth() {
        return maxRecursionDepth;
    }

    public long getMaxLoopIterations() {
        return maxLoopIterations;
    }

    public int getMaxAsyncTasks() {
        return maxAsyncTasks;
    }

    // ============ 错误工厂 ============

    /** 创建安全拒绝异常 */
    public static NovaException denied(String action) {
        return new NovaException(NovaException.ErrorKind.ACCESS_DENIED, "安全策略拒绝: " + action, null);
    }

    // ============ Builder ============

    public static final class Builder {
        private final Level level;
        private final Set<String> allowedPackages = new HashSet<>();
        private final Set<String> deniedPackages = new HashSet<>();
        private final Set<String> allowedClasses = new HashSet<>();
        private final Set<String> deniedClasses = new HashSet<>();
        private final Set<String> deniedMethods = new HashSet<>();
        private boolean allowJavaInterop = true;
        private boolean allowSetAccessible = true;
        private boolean allowStdio = true;
        private boolean allowFileIO = true;
        private boolean allowNetwork = true;
        private boolean allowProcessExec = true;
        private long maxExecutionTimeMs = 0;
        private int maxRecursionDepth = 0;
        private long maxLoopIterations = 0;
        private int maxAsyncTasks = 0;

        Builder(Level level) {
            this.level = level;
        }

        public Builder allowPackage(String packageName) {
            allowedPackages.add(packageName);
            return this;
        }

        public Builder denyPackage(String packageName) {
            deniedPackages.add(packageName);
            return this;
        }

        public Builder allowClass(String className) {
            allowedClasses.add(className);
            return this;
        }

        public Builder denyClass(String className) {
            deniedClasses.add(className);
            return this;
        }

        public Builder denyMethod(String className, String methodName) {
            deniedMethods.add(className + "." + methodName);
            return this;
        }

        public Builder allowJavaInterop(boolean allow) {
            this.allowJavaInterop = allow;
            return this;
        }

        public Builder allowSetAccessible(boolean allow) {
            this.allowSetAccessible = allow;
            return this;
        }

        public Builder allowStdio(boolean allow) {
            this.allowStdio = allow;
            return this;
        }

        public Builder allowFileIO(boolean allow) {
            this.allowFileIO = allow;
            return this;
        }

        public Builder allowNetwork(boolean allow) {
            this.allowNetwork = allow;
            return this;
        }

        public Builder allowProcessExec(boolean allow) {
            this.allowProcessExec = allow;
            return this;
        }

        public Builder maxExecutionTime(long ms) {
            this.maxExecutionTimeMs = ms;
            return this;
        }

        public Builder maxRecursionDepth(int depth) {
            this.maxRecursionDepth = depth;
            return this;
        }

        public Builder maxLoopIterations(long maxIter) {
            this.maxLoopIterations = maxIter;
            return this;
        }

        public Builder maxAsyncTasks(int max) {
            this.maxAsyncTasks = max;
            return this;
        }

        public NovaSecurityPolicy build() {
            return new NovaSecurityPolicy(this);
        }
    }
}
