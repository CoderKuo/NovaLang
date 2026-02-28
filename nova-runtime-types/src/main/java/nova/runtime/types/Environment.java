package nova.runtime.types;

import nova.runtime.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 运行时环境（作用域）
 *
 * <p>管理变量绑定，支持嵌套作用域。</p>
 * <p>使用并行数组替代 HashMap，减少对象分配和 GC 压力。</p>
 */
public final class Environment {

    private static final int INITIAL_CAPACITY = 4;

    private final Environment parent;
    private String[] keys;
    private NovaValue[] vals;
    private int size;
    private long mutableBits;        // 前 64 个变量的可变性位标记
    private long[] mutableBitsExt;   // 第 65+ 个变量的可变性位标记（延迟分配）
    private Map<String, Integer> keyIndex;  // 延迟分配：变量数 > 阈值时启用哈希索引
    private int builtinCount;
    private boolean replMode = false;

    /** 变量数超过此阈值时，从线性扫描切换到 HashMap 查找 */
    private static final int HASH_THRESHOLD = 8;

    /**
     * 创建全局环境
     */
    public Environment() {
        this.parent = null;
        this.keys = new String[INITIAL_CAPACITY];
        this.vals = new NovaValue[INITIAL_CAPACITY];
        this.size = 0;
    }

    /**
     * 创建子环境
     */
    public Environment(Environment parent) {
        this.parent = parent;
        this.keys = new String[INITIAL_CAPACITY];
        this.vals = new NovaValue[INITIAL_CAPACITY];
        this.size = 0;
    }

    public Environment getParent() {
        return parent;
    }

    public void setReplMode(boolean replMode) {
        this.replMode = replMode;
    }

    /**
     * 标记当前已注册的变量为内置变量。此后再定义同名变量时会提示"Cannot redefine built-in"。
     */
    public void sealBuiltins() {
        this.builtinCount = size;
    }

    public boolean isReplMode() {
        return replMode;
    }

    /** 在当前作用域查找 key 的索引，未找到返回 -1 */
    private int indexOf(String name) {
        if (keyIndex != null) {
            Integer idx = keyIndex.get(name);
            return idx != null ? idx : -1;
        }
        for (int i = size - 1; i >= 0; i--) {
            if (name.equals(keys[i])) return i;
        }
        return -1;
    }

    /** 变量数超阈值时，构建哈希索引并切换查找策略 */
    private void upgradeToHash() {
        keyIndex = new HashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            keyIndex.put(keys[i], i);
        }
    }

    private void ensureCapacity() {
        if (size == keys.length) {
            int oldCap = keys.length;
            // 小容量线性增长（+4），大容量翻倍
            int newCap = oldCap < 16 ? oldCap + 4 : oldCap * 2;
            keys = Arrays.copyOf(keys, newCap);
            vals = Arrays.copyOf(vals, newCap);
        }
    }

    /** 检查第 idx 位是否为可变（var） */
    private boolean isMutableBit(int idx) {
        if (idx < 64) return (mutableBits & (1L << idx)) != 0;
        if (mutableBitsExt == null) return false;
        int ai = (idx - 64) >> 6;
        return ai < mutableBitsExt.length && (mutableBitsExt[ai] & (1L << ((idx - 64) & 63))) != 0;
    }

    /** 设置第 idx 位为可变 */
    private void setMutableBit(int idx) {
        if (idx < 64) { mutableBits |= (1L << idx); return; }
        int ai = (idx - 64) >> 6;
        if (mutableBitsExt == null) mutableBitsExt = new long[ai + 1];
        else if (ai >= mutableBitsExt.length) mutableBitsExt = Arrays.copyOf(mutableBitsExt, ai + 1);
        mutableBitsExt[ai] |= (1L << ((idx - 64) & 63));
    }

    /** 清除第 idx 位（设为不可变） */
    private void clearMutableBit(int idx) {
        if (idx < 64) { mutableBits &= ~(1L << idx); return; }
        if (mutableBitsExt == null) return;
        int ai = (idx - 64) >> 6;
        if (ai < mutableBitsExt.length) mutableBitsExt[ai] &= ~(1L << ((idx - 64) & 63));
    }

    /**
     * 定义新变量。允许 shadowing 内置定义（与 Kotlin 行为一致）。
     */
    public void define(String name, NovaValue value, boolean isMutable) {
        int idx = indexOf(name);
        if (idx >= 0 && !replMode) {
            if (idx < builtinCount) {
                // 允许用户代码覆盖内置定义（shadowing），与 Kotlin 行为一致
                vals[idx] = value;
                if (isMutable) setMutableBit(idx); else clearMutableBit(idx);
                return;
            }
            throw new NovaException("Variable already defined: " + name);
        }
        if (idx >= 0) {
            // REPL 重定义
            vals[idx] = value;
            if (isMutable) setMutableBit(idx); else clearMutableBit(idx);
            return;
        }
        ensureCapacity();
        if (keyIndex != null) keyIndex.put(name, size);
        keys[size] = name;
        vals[size] = value;
        if (isMutable) setMutableBit(size);
        size++;
        if (keyIndex == null && size > HASH_THRESHOLD) upgradeToHash();
    }

    /**
     * 定义不可变变量（val）
     */
    public void defineVal(String name, NovaValue value) {
        define(name, value, false);
    }

    /**
     * 定义可变变量（var）
     */
    public void defineVar(String name, NovaValue value) {
        define(name, value, true);
    }

    /**
     * 重置环境（清除所有局部变量，保留 parent 引用）。
     * 用于尾递归优化：复用同一帧，避免分配新 Environment。
     */
    public void reset() {
        // 清空引用帮助 GC
        for (int i = 0; i < size; i++) {
            keys[i] = null;
            vals[i] = null;
        }
        size = 0;
        mutableBits = 0;
        mutableBitsExt = null;
        keyIndex = null;
    }

    /**
     * 轻量级重置（for 循环复用）。
     * 清空旧引用帮助 GC 回收，重置可变性标记。
     */
    public void resetForLoop() {
        if (size > 0) {
            Arrays.fill(keys, 0, size, null);
            Arrays.fill(vals, 0, size, null);
        }
        size = 0;
        mutableBits = 0;
        mutableBitsExt = null;
        keyIndex = null;
    }

    /**
     * 快速定义不可变变量（跳过重复检查，仅供函数参数绑定等已知不重复的场景）
     */
    public void defineValFast(String name, NovaValue value) {
        ensureCapacity();
        if (keyIndex != null) keyIndex.put(name, size);
        keys[size] = name;
        vals[size] = value;
        size++;
        if (keyIndex == null && size > HASH_THRESHOLD) upgradeToHash();
    }

    /**
     * 快速定义可变变量（跳过重复检查）
     */
    public void defineVarFast(String name, NovaValue value) {
        ensureCapacity();
        if (keyIndex != null) keyIndex.put(name, size);
        keys[size] = name;
        vals[size] = value;
        setMutableBit(size);
        size++;
        if (keyIndex == null && size > HASH_THRESHOLD) upgradeToHash();
    }

    /**
     * 重新定义变量（用于 REPL 等场景）
     */
    public void redefine(String name, NovaValue value, boolean isMutable) {
        int idx = indexOf(name);
        if (idx >= 0) {
            vals[idx] = value;
            if (isMutable) setMutableBit(idx); else clearMutableBit(idx);
            return;
        }
        ensureCapacity();
        if (keyIndex != null) keyIndex.put(name, size);
        keys[size] = name;
        vals[size] = value;
        if (isMutable) setMutableBit(size);
        size++;
        if (keyIndex == null && size > HASH_THRESHOLD) upgradeToHash();
    }

    /**
     * 获取变量值
     */
    public NovaValue get(String name) {
        int idx = indexOf(name);
        if (idx >= 0) return vals[idx];
        if (parent != null) return parent.get(name);
        throw new NovaException("Undefined variable: " + name);
    }

    /**
     * 尝试获取变量值（不抛异常）
     */
    public NovaValue tryGet(String name) {
        int idx = indexOf(name);
        if (idx >= 0) return vals[idx];
        if (parent != null) return parent.tryGet(name);
        return null;
    }

    /**
     * 检查变量是否存在
     */
    public boolean contains(String name) {
        int idx = indexOf(name);
        if (idx >= 0) return true;
        if (parent != null) return parent.contains(name);
        return false;
    }

    /**
     * 检查变量是否已定义（别名）
     */
    public boolean isDefined(String name) {
        return contains(name);
    }

    /**
     * 检查变量是否为 val（不可变）
     */
    public boolean isVal(String name) {
        int idx = indexOf(name);
        if (idx >= 0) return !isMutableBit(idx);
        if (parent != null) return parent.isVal(name);
        throw new NovaException("Undefined variable: " + name);
    }

    /**
     * 检查变量是否在当前作用域定义
     */
    public boolean containsLocal(String name) {
        return indexOf(name) >= 0;
    }

    /**
     * 将当前环境的所有符号导出到目标环境
     */
    public void exportAll(Environment target) {
        for (int i = 0; i < size; i++) {
            if (keys[i] != null) {
                target.redefine(keys[i], vals[i], isMutableBit(i));
            }
        }
    }

    /**
     * 赋值（修改已存在的变量）
     */
    public void assign(String name, NovaValue value) {
        int idx = indexOf(name);
        if (idx >= 0) {
            if (!isMutableBit(idx)) {
                throw new NovaException("Cannot reassign val: " + name);
            }
            vals[idx] = value;
            return;
        }
        if (parent != null) {
            parent.assign(name, value);
            return;
        }
        throw new NovaException("Undefined variable: " + name);
    }

    /**
     * 尝试赋值（不抛异常，变量不存在返回 false）。
     * 合并 contains() + assign() 为单次环境链遍历。
     */
    public boolean tryAssign(String name, NovaValue value) {
        int idx = indexOf(name);
        if (idx >= 0) {
            if (!isMutableBit(idx)) {
                throw new NovaException("Cannot reassign val: " + name);
            }
            vals[idx] = value;
            return true;
        }
        if (parent != null) return parent.tryAssign(name, value);
        return false;
    }

    /**
     * 在指定距离的祖先环境中获取变量
     */
    public NovaValue getAt(int distance, String name) {
        Environment env = ancestor(distance);
        int idx = env.indexOf(name);
        if (idx >= 0) return env.vals[idx];
        throw new NovaException("Undefined variable: " + name);
    }

    /**
     * 在指定距离的祖先环境中赋值
     */
    public void assignAt(int distance, String name, NovaValue value) {
        Environment env = ancestor(distance);
        int idx = env.indexOf(name);
        if (idx >= 0) {
            if (!env.isMutableBit(idx)) {
                throw new NovaException("Cannot reassign val: " + name);
            }
            env.vals[idx] = value;
            return;
        }
        throw new NovaException("Undefined variable: " + name);
    }

    private Environment ancestor(int distance) {
        Environment env = this;
        for (int i = 0; i < distance; i++) {
            env = env.parent;
        }
        return env;
    }

    /**
     * 通过 (depth, slot) 直接读取变量，O(1) 无字符串比较
     */
    public NovaValue getAtSlot(int depth, int slot) {
        Environment env = this;
        for (int i = 0; i < depth; i++) {
            if (env.parent == null) {
                throw new NovaException(
                    "Internal error: variable scope depth " + depth + " exceeds environment chain (stopped at depth " + i + ")");
            }
            env = env.parent;
        }
        if (slot < 0 || slot >= env.size) {
            throw new NovaException(
                "Internal error: slot " + slot + " out of bounds (scope has " + env.size + " variables, depth=" + depth + ")");
        }
        return env.vals[slot];
    }

    /**
     * 通过 (depth, slot) 直接赋值，保留 mutability 检查
     */
    public void assignAtSlot(int depth, int slot, NovaValue value) {
        Environment env = this;
        for (int i = 0; i < depth; i++) {
            if (env.parent == null) {
                throw new NovaException(
                    "Internal error: variable scope depth " + depth + " exceeds environment chain (stopped at depth " + i + ")");
            }
            env = env.parent;
        }
        if (slot < 0 || slot >= env.size) {
            throw new NovaException(
                "Internal error: slot " + slot + " out of bounds (scope has " + env.size + " variables, depth=" + depth + ")");
        }
        if (!env.isMutableBit(slot)) {
            throw new NovaException("Cannot reassign val: " + env.keys[slot]);
        }
        env.vals[slot] = value;
    }

    /**
     * 获取当前作用域的所有变量名
     */
    public Set<String> getLocalNames() {
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < size; i++) {
            names.add(keys[i]);
        }
        return names;
    }

    /**
     * 复制环境（浅复制）
     */
    public Environment copy() {
        Environment copy = new Environment(parent);
        copy.keys = Arrays.copyOf(keys, keys.length);
        copy.vals = Arrays.copyOf(vals, vals.length);
        copy.size = size;
        copy.mutableBits = mutableBits;
        if (mutableBitsExt != null) copy.mutableBitsExt = mutableBitsExt.clone();
        if (keyIndex != null) copy.keyIndex = new HashMap<>(keyIndex);
        return copy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Environment{\n");
        for (int i = 0; i < size; i++) {
            String mutStr = isMutableBit(i) ? "var" : "val";
            sb.append("  ").append(mutStr).append(" ")
              .append(keys[i]).append(" = ")
              .append(vals[i]).append("\n");
        }
        sb.append("}");
        if (parent != null) {
            sb.append(" -> parent");
        }
        return sb.toString();
    }
}
