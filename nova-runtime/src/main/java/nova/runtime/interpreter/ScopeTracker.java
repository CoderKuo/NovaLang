package nova.runtime.interpreter;

import java.util.*;

/**
 * 作用域栈管理器：为变量解析器提供统一的作用域管理和变量查找功能。
 *
 * <p>用于静态变量解析（VariableResolver、HirVariableResolver），
 * 计算变量的 (depth, slot) 索引对，实现运行时 O(1) 变量访问。</p>
 *
 * @param <T> 作用域附加上下文类型（可为 Void 表示无上下文）
 */
public final class ScopeTracker<T> {

    /**
     * 作用域：变量名 → slot 索引 + 可选上下文
     */
    public static final class Scope<T> {
        private final Map<String, Integer> variables = new LinkedHashMap<>();
        private int nextSlot = 0;
        private T context;

        /**
         * 定义变量，返回分配的 slot。如果变量已存在，返回已有 slot。
         */
        public int define(String name) {
            Integer existing = variables.get(name);
            if (existing != null) return existing;
            int slot = nextSlot++;
            variables.put(name, slot);
            return slot;
        }

        /**
         * 查找变量，返回 slot（仅当前作用域）。
         */
        public Integer lookup(String name) {
            return variables.get(name);
        }

        /**
         * 获取作用域附加上下文数据。
         */
        public T getContext() {
            return context;
        }

        /**
         * 设置作用域附加上下文数据。
         */
        public void setContext(T context) {
            this.context = context;
        }

        /**
         * 获取当前作用域的变量数量（用于调试）。
         */
        public int size() {
            return nextSlot;
        }

        /**
         * 获取所有已定义变量名（用于调试）。
         */
        public Set<String> getVariableNames() {
            return Collections.unmodifiableSet(variables.keySet());
        }
    }

    private final Deque<Scope<T>> scopes = new ArrayDeque<>();

    // ========== 作用域管理 ==========

    /**
     * 进入新作用域。
     */
    public void beginScope() {
        scopes.push(new Scope<>());
    }

    /**
     * 退出当前作用域。
     */
    public void endScope() {
        if (scopes.isEmpty()) {
            throw new IllegalStateException("Cannot pop scope: stack is empty");
        }
        scopes.pop();
    }

    /**
     * 在当前作用域定义变量。如果没有作用域（全局），忽略。
     */
    public void defineVariable(String name) {
        if (!scopes.isEmpty()) {
            scopes.peek().define(name);
        }
    }

    /**
     * 在当前作用域定义变量并返回 slot（强制要求有作用域）。
     */
    public int defineVariableStrict(String name) {
        if (scopes.isEmpty()) {
            throw new IllegalStateException("Cannot define variable in empty scope stack");
        }
        return scopes.peek().define(name);
    }

    // ========== 变量解析 ==========

    /**
     * 解析变量：从栈顶向外查找，返回 [depth, slot] 索引对。
     * 如果未找到（全局变量或未定义），返回 null。
     */
    public int[] resolveVariable(String name) {
        if (scopes.isEmpty()) return null;  // 全局作用域，跳过
        int depth = 0;
        for (Scope<T> scope : scopes) {
            Integer slot = scope.lookup(name);
            if (slot != null) {
                return new int[]{depth, slot};
            }
            depth++;
        }
        return null;  // 未找到
    }

    /**
     * 检查变量是否在当前作用域定义（不递归查找父作用域）。
     */
    public boolean isDefinedInCurrentScope(String name) {
        if (scopes.isEmpty()) return false;
        return scopes.peek().lookup(name) != null;
    }

    /**
     * 检查作用域栈是否为空（全局作用域）。
     */
    public boolean isEmpty() {
        return scopes.isEmpty();
    }

    /**
     * 获取当前作用域深度。
     */
    public int getDepth() {
        return scopes.size();
    }

    /**
     * 获取当前作用域（不为空时）。
     */
    public Scope<T> getCurrentScope() {
        if (scopes.isEmpty()) {
            throw new IllegalStateException("Scope stack is empty");
        }
        return scopes.peek();
    }

    /**
     * 检查是否有当前作用域。
     */
    public boolean hasCurrentScope() {
        return !scopes.isEmpty();
    }

    // ========== 调试辅助 ==========

    /**
     * 获取作用域栈的快照（用于调试）。
     */
    public List<Scope<T>> getScopesSnapshot() {
        return new ArrayList<>(scopes);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ScopeTracker[depth=").append(scopes.size()).append("]{\n");
        int depth = 0;
        for (Scope<T> scope : scopes) {
            sb.append("  [").append(depth++).append("] ")
              .append(scope.getVariableNames())
              .append(" (").append(scope.size()).append(" slots)\n");
        }
        sb.append("}");
        return sb.toString();
    }
}
