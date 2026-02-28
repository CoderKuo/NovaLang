package nova.runtime.interpreter;

import java.util.*;

/**
 * 安全策略配置类
 *
 * <p>控制 NovaLang 解释器的安全行为，限制 Java 互操作、资源使用等。</p>
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
    public static NovaRuntimeException denied(String action) {
        return new NovaRuntimeException("Security policy denied: " + action);
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
