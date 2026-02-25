package com.novalang.ir.mir;

import com.novalang.compiler.ast.SourceLocation;

import java.util.Map;

/**
 * MIR 基本块终止指令。每个基本块必须恰好有一个终止指令。
 */
public abstract class MirTerminator {

    // ===== 快速分派标记 =====
    public static final int KIND_BRANCH = 0;
    public static final int KIND_GOTO = 1;
    public static final int KIND_RETURN = 2;
    public static final int KIND_TAIL_CALL = 3;
    public static final int KIND_SWITCH = 4;
    public static final int KIND_THROW = 5;
    public static final int KIND_UNREACHABLE = 6;

    protected final SourceLocation location;
    /** 子类类型标记，消除执行时的 instanceof 链。 */
    public final int kind;

    protected MirTerminator(SourceLocation location) {
        this.location = location;
        // 构造时一次性确定 kind，执行时 O(1) switch 分派
        if (this instanceof Branch) this.kind = KIND_BRANCH;
        else if (this instanceof Goto) this.kind = KIND_GOTO;
        else if (this instanceof Return) this.kind = KIND_RETURN;
        else if (this instanceof TailCall) this.kind = KIND_TAIL_CALL;
        else if (this instanceof Switch) this.kind = KIND_SWITCH;
        else if (this instanceof Throw) this.kind = KIND_THROW;
        else this.kind = KIND_UNREACHABLE;
    }

    public SourceLocation getLocation() { return location; }

    /**
     * 无条件跳转。
     */
    public static class Goto extends MirTerminator {
        private final int targetBlockId;

        public Goto(SourceLocation location, int targetBlockId) {
            super(location);
            this.targetBlockId = targetBlockId;
        }

        public int getTargetBlockId() { return targetBlockId; }

        @Override
        public String toString() {
            return "goto B" + targetBlockId;
        }
    }

    /**
     * 条件分支。
     */
    public static class Branch extends MirTerminator {
        private final int condition;    // 条件局部变量
        private final int thenBlock;
        private final int elseBlock;
        // 窥孔优化：融合的比较操作（null = 正常分支）
        private BinaryOp fusedCmpOp;
        private int fusedLeft = -1;
        private int fusedRight = -1;

        public Branch(SourceLocation location, int condition, int thenBlock, int elseBlock) {
            super(location);
            this.condition = condition;
            this.thenBlock = thenBlock;
            this.elseBlock = elseBlock;
        }

        public int getCondition() { return condition; }
        public int getThenBlock() { return thenBlock; }
        public int getElseBlock() { return elseBlock; }

        public void setFusedCmp(BinaryOp op, int left, int right) {
            this.fusedCmpOp = op;
            this.fusedLeft = left;
            this.fusedRight = right;
        }
        public BinaryOp getFusedCmpOp() { return fusedCmpOp; }
        public int getFusedLeft() { return fusedLeft; }
        public int getFusedRight() { return fusedRight; }

        @Override
        public String toString() {
            if (fusedCmpOp != null) {
                return "branch %" + fusedLeft + " " + fusedCmpOp + " %" + fusedRight
                        + " ? B" + thenBlock + " : B" + elseBlock;
            }
            return "branch %" + condition + " ? B" + thenBlock + " : B" + elseBlock;
        }
    }

    /**
     * 返回。
     */
    public static class Return extends MirTerminator {
        private final int valueLocal;   // -1 = void

        public Return(SourceLocation location, int valueLocal) {
            super(location);
            this.valueLocal = valueLocal;
        }

        public int getValueLocal() { return valueLocal; }

        @Override
        public String toString() {
            return valueLocal >= 0 ? "return %" + valueLocal : "return";
        }
    }

    /**
     * Switch（多路分支）。
     */
    public static class Switch extends MirTerminator {
        private final int key;
        private final Map<Object, Integer> cases;
        private final int defaultBlock;

        public Switch(SourceLocation location, int key, Map<Object, Integer> cases, int defaultBlock) {
            super(location);
            this.key = key;
            this.cases = cases;
            this.defaultBlock = defaultBlock;
        }

        public int getKey() { return key; }
        public Map<Object, Integer> getCases() { return cases; }
        public int getDefaultBlock() { return defaultBlock; }

        @Override
        public String toString() {
            return "switch %" + key + " cases=" + cases.size() + " default=B" + defaultBlock;
        }
    }

    /**
     * 抛出异常。
     */
    public static class Throw extends MirTerminator {
        private final int exceptionLocal;

        public Throw(SourceLocation location, int exceptionLocal) {
            super(location);
            this.exceptionLocal = exceptionLocal;
        }

        public int getExceptionLocal() { return exceptionLocal; }

        @Override
        public String toString() { return "throw %" + exceptionLocal; }
    }

    /**
     * 不可达。
     */
    public static class Unreachable extends MirTerminator {
        public Unreachable(SourceLocation location) {
            super(location);
        }

        @Override
        public String toString() { return "unreachable"; }
    }

    /**
     * 尾递归回跳。由 TailCallElimination pass 生成，语义等同于
     * "参数已搬运完毕，跳转回函数入口块开始下一次迭代"。
     * 解释器据此做递归深度检查和异常栈折叠。
     */
    public static class TailCall extends MirTerminator {
        private final int entryBlockId;

        public TailCall(SourceLocation location, int entryBlockId) {
            super(location);
            this.entryBlockId = entryBlockId;
        }

        public int getEntryBlockId() { return entryBlockId; }

        @Override
        public String toString() { return "tailcall B" + entryBlockId; }
    }
}
