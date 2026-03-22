package com.novalang.ir.mir;

import com.novalang.compiler.ast.Modifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MIR 函数（包含 CFG）。
 */
public class MirFunction {

    private final String name;
    private final MirType returnType;
    private final List<MirParam> params;
    private final Set<Modifier> modifiers;
    private final List<BasicBlock> blocks;
    private final List<MirLocal> locals;
    /** 次级构造器委托参数的局部变量索引，null 表示主构造器/普通方法 */
    private int[] delegationArgLocals;
    /** 超类构造器参数的局部变量索引，null 表示无参 super() */
    private int[] superInitArgLocals;
    /** 超类名称（用于 MirInterpreter 正确查找超类构造器，避免依赖运行时对象的 getClass） */
    private String superClassName;
    /** 覆盖方法描述符（扩展函数等静态方法使用原始类型描述符） */
    private String overrideDescriptor;
    /** body 起始块 ID（默认参数处理之后的块），构造器用于 SET_FIELD 插入位置 */
    private int bodyStartBlockId = 0;
    /** 类型参数名列表（reified 运行时解析用） */
    private List<String> typeParams = Collections.emptyList();
    /** 异常表 */
    private final List<TryCatchEntry> tryCatchEntries = new ArrayList<>();
    /** 栈帧大小（最大寄存器索引），-1 表示未计算 */
    private int frameSize = -1;
    /** 预缓存的 blockId → BasicBlock 数组（lazy 构建） */
    private BasicBlock[] blockArr;
    /** ?????????? */
    private boolean memoized;
    /** ????? */
    private transient Map<Object, Object> memoCache;
    /** int ??? memo cache??? HashMap ???? */
    private transient IntMemoCache intMemoCache;

    /** try-catch 异常表条目 */
    public static class TryCatchEntry {
        public final int tryStartBlock;
        public final int tryEndBlock;   // exclusive
        public final int handlerBlock;
        public final String exceptionType; // JVM internal name
        public final int exceptionLocal;   // handler 开始时 ASTORE 异常到此 local

        public TryCatchEntry(int tryStartBlock, int tryEndBlock,
                             int handlerBlock, String exceptionType, int exceptionLocal) {
            this.tryStartBlock = tryStartBlock;
            this.tryEndBlock = tryEndBlock;
            this.handlerBlock = handlerBlock;
            this.exceptionType = exceptionType;
            this.exceptionLocal = exceptionLocal;
        }
    }

    public MirFunction(String name, MirType returnType, List<MirParam> params,
                       Set<Modifier> modifiers) {
        this.name = name;
        this.returnType = returnType;
        this.params = params;
        this.modifiers = modifiers;
        this.blocks = new ArrayList<>();
        this.locals = new ArrayList<>();
    }

    public String getName() { return name; }
    public MirType getReturnType() { return returnType; }
    public List<MirParam> getParams() { return params; }
    public Set<Modifier> getModifiers() { return modifiers; }
    public List<BasicBlock> getBlocks() { return blocks; }
    public List<MirLocal> getLocals() { return locals; }

    public int[] getDelegationArgLocals() { return delegationArgLocals; }
    public void setDelegationArgLocals(int[] locals) { this.delegationArgLocals = locals; }
    public boolean hasDelegation() { return delegationArgLocals != null; }

    public int[] getSuperInitArgLocals() { return superInitArgLocals; }
    public void setSuperInitArgLocals(int[] locals) { this.superInitArgLocals = locals; }
    public boolean hasSuperInitArgs() { return superInitArgLocals != null; }

    public String getSuperClassName() { return superClassName; }
    public void setSuperClassName(String name) { this.superClassName = name; }

    public String getOverrideDescriptor() { return overrideDescriptor; }
    public void setOverrideDescriptor(String desc) { this.overrideDescriptor = desc; }

    public int getBodyStartBlockId() { return bodyStartBlockId; }
    public void setBodyStartBlockId(int id) { this.bodyStartBlockId = id; }

    public List<String> getTypeParams() { return typeParams; }
    public void setTypeParams(List<String> tp) { this.typeParams = tp != null ? tp : Collections.emptyList(); }

    public List<TryCatchEntry> getTryCatchEntries() { return tryCatchEntries; }
    public void addTryCatchEntry(int tryStart, int tryEnd, int handler,
                                  String exceptionType, int exceptionLocal) {
        tryCatchEntries.add(new TryCatchEntry(tryStart, tryEnd, handler,
                exceptionType, exceptionLocal));
    }

    public boolean isMemoized() { return memoized; }
    public void setMemoized(boolean memoized) { this.memoized = memoized; }
    /** @memoized 缓存上限 */
    private static final int MEMO_MAX_SIZE = 4096;

    public Map<Object, Object> getMemoCache() {
        if (memoCache == null) {
            // synchronizedMap 包装：access-order LinkedHashMap 的 get() 会修改链表，
            // 子解释器共享 MirFunction 时需要线程安全保护
            memoCache = java.util.Collections.synchronizedMap(
                new LinkedHashMap<Object, Object>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
                        return size() > MEMO_MAX_SIZE;
                    }
                });
        }
        return memoCache;
    }
    public Object getIntMemoized(int key) {
        return intMemoCache != null ? intMemoCache.get(key) : null;
    }
    public void putIntMemoized(int key, Object value) {
        if (value == null) return;
        if (intMemoCache == null) intMemoCache = new IntMemoCache();
        intMemoCache.put(key, value);
    }
    public void clearMemoCaches() {
        if (memoCache != null) memoCache.clear();
        if (intMemoCache != null) intMemoCache.clear();
    }

    private static final class IntMemoCache {
        private static final int DENSE_MIN = 0;
        private static final int DENSE_MAX = 1024;
        private static final int DENSE_SIZE = DENSE_MAX - DENSE_MIN + 1;

        private final Object[] denseValues = new Object[DENSE_SIZE];
        private final int[] denseTouched = new int[DENSE_SIZE];
        private int denseTouchedCount;

        private int[] overflowKeys = new int[16];
        private Object[] overflowValues = new Object[16];
        private int[] overflowTouched = new int[16];
        private int overflowTouchedCount;
        private int overflowSize;

        Object get(int key) {
            if (key >= DENSE_MIN && key <= DENSE_MAX) {
                return denseValues[key - DENSE_MIN];
            }
            Object[] values = overflowValues;
            int mask = values.length - 1;
            int slot = mix(key) & mask;
            while (true) {
                Object value = values[slot];
                if (value == null) {
                    return null;
                }
                if (overflowKeys[slot] == key) {
                    return value;
                }
                slot = (slot + 1) & mask;
            }
        }

        void put(int key, Object value) {
            if (key >= DENSE_MIN && key <= DENSE_MAX) {
                int idx = key - DENSE_MIN;
                if (denseValues[idx] == null) {
                    denseTouched[denseTouchedCount++] = idx;
                }
                denseValues[idx] = value;
                return;
            }
            ensureOverflowCapacity();
            insertOverflow(key, value);
        }

        void clear() {
            for (int i = 0; i < denseTouchedCount; i++) {
                denseValues[denseTouched[i]] = null;
            }
            denseTouchedCount = 0;
            for (int i = 0; i < overflowTouchedCount; i++) {
                overflowValues[overflowTouched[i]] = null;
            }
            overflowTouchedCount = 0;
            overflowSize = 0;
        }

        private void ensureOverflowCapacity() {
            if ((overflowSize + 1) * 2 < overflowValues.length) {
                return;
            }
            int[] oldKeys = overflowKeys;
            Object[] oldValues = overflowValues;
            int[] oldTouched = overflowTouched;
            int oldTouchedCount = overflowTouchedCount;
            overflowKeys = new int[oldValues.length << 1];
            overflowValues = new Object[oldValues.length << 1];
            overflowTouched = new int[overflowValues.length];
            overflowTouchedCount = 0;
            overflowSize = 0;
            for (int i = 0; i < oldTouchedCount; i++) {
                int slot = oldTouched[i];
                Object value = oldValues[slot];
                if (value != null) {
                    insertOverflow(oldKeys[slot], value);
                }
            }
        }

        private void insertOverflow(int key, Object value) {
            int mask = overflowValues.length - 1;
            int slot = mix(key) & mask;
            while (true) {
                Object existing = overflowValues[slot];
                if (existing == null) {
                    overflowKeys[slot] = key;
                    overflowValues[slot] = value;
                    if (overflowTouchedCount == overflowTouched.length) {
                        overflowTouched = Arrays.copyOf(overflowTouched, overflowTouched.length << 1);
                    }
                    overflowTouched[overflowTouchedCount++] = slot;
                    overflowSize++;
                    return;
                }
                if (overflowKeys[slot] == key) {
                    overflowValues[slot] = value;
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        private static int mix(int key) {
            int h = key;
            h ^= (h >>> 16);
            h *= 0x7feb352d;
            h ^= (h >>> 15);
            h *= 0x846ca68b;
            h ^= (h >>> 16);
            return h;
        }
    }

    public BasicBlock getEntryBlock() {
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    public BasicBlock newBlock() {
        BasicBlock block = new BasicBlock(blocks.size());
        blocks.add(block);
        return block;
    }

    public int newLocal(String name, MirType type) {
        int index = locals.size();
        locals.add(new MirLocal(index, name, type));
        return index;
    }

    /** 获取预缓存的 blockId → BasicBlock 数组（首次调用时构建） */
    public BasicBlock[] getBlockArr() {
        if (blockArr == null) buildBlockArr();
        return blockArr;
    }

    private void buildBlockArr() {
        int maxId = 0;
        for (BasicBlock b : blocks) {
            if (b.getId() > maxId) maxId = b.getId();
        }
        blockArr = new BasicBlock[maxId + 1];
        for (BasicBlock b : blocks) {
            blockArr[b.getId()] = b;
        }
    }

    /** 获取栈帧大小（首次调用时 lazy 计算） */
    public int getFrameSize() {
        if (frameSize < 0) computeFrameSize();
        return frameSize;
    }

    /** 扫描所有指令/终止符/异常表，计算最大寄存器索引 */
    public void computeFrameSize() {
        int maxIndex = locals.size();
        for (BasicBlock block : blocks) {
            for (MirInst inst : block.getInstructions()) {
                if (inst.getDest() >= maxIndex) maxIndex = inst.getDest() + 1;
                if (inst.getOperands() != null) {
                    for (int op : inst.getOperands()) {
                        if (op >= maxIndex) maxIndex = op + 1;
                    }
                }
            }
            MirTerminator term = block.getTerminator();
            if (term instanceof MirTerminator.Branch) {
                MirTerminator.Branch br = (MirTerminator.Branch) term;
                int c = br.getCondition();
                if (c >= maxIndex) maxIndex = c + 1;
                // 窥孔融合后 fusedLeft/fusedRight 引用的寄存器也须纳入帧大小
                if (br.getFusedCmpOp() != null) {
                    int fl = br.getFusedLeft(), fr = br.getFusedRight();
                    if (fl >= maxIndex) maxIndex = fl + 1;
                    if (fr >= maxIndex) maxIndex = fr + 1;
                }
            } else if (term instanceof MirTerminator.Return) {
                int v = ((MirTerminator.Return) term).getValueLocal();
                if (v >= maxIndex) maxIndex = v + 1;
            } else if (term instanceof MirTerminator.Switch) {
                int k = ((MirTerminator.Switch) term).getKey();
                if (k >= maxIndex) maxIndex = k + 1;
            } else if (term instanceof MirTerminator.Throw) {
                int e = ((MirTerminator.Throw) term).getExceptionLocal();
                if (e >= maxIndex) maxIndex = e + 1;
            }
        }
        for (TryCatchEntry entry : tryCatchEntries) {
            if (entry.exceptionLocal >= maxIndex) maxIndex = entry.exceptionLocal + 1;
        }
        this.frameSize = maxIndex;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("fun ").append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getName()).append(": ").append(params.get(i).getType());
        }
        sb.append("): ").append(returnType).append(" {\n");
        for (BasicBlock block : blocks) {
            sb.append(block);
        }
        sb.append("}\n");
        return sb.toString();
    }
}
