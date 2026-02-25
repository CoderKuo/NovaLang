package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * 局部公共子表达式消除 (Local CSE) + 单前驱跨块扩展 + 全局常量别名。
 * <p>
 * 对每个基本块进行值编号：
 * <ul>
 *   <li>CONST_INT/LONG/FLOAT/DOUBLE/BOOL/CHAR: 相同常量复用</li>
 *   <li>BINARY: 相同操作 + 相同规范化操作数 → 复用</li>
 *   <li>INDEX_GET: 相同目标 + 相同索引 → 复用（INDEX_SET/CALL 后失效）</li>
 * </ul>
 * 单前驱块继承前驱的值表，实现有限的跨块 CSE（如 if-then 路径）。
 * <p>
 * 全局常量别名：对单次定义的 MOVE 链追溯到常量源，跨循环头保留等价关系。
 */
public class MirLocalCSE implements MirPass {

    @Override
    public String getName() { return "MirLocalCSE"; }

    @Override
    public MirModule run(MirModule module) {
        for (MirClass cls : module.getClasses()) {
            for (MirFunction m : cls.getMethods()) optimizeFunction(m);
        }
        for (MirFunction f : module.getTopLevelFunctions()) optimizeFunction(f);
        return module;
    }

    // ---- 表达式键 ----

    static final class ExprKey {
        final int kind, a, b, extra;

        ExprKey(int kind, int a, int b, int extra) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.extra = extra;
        }

        @Override
        public int hashCode() {
            return ((kind * 31 + a) * 31 + b) * 31 + extra;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ExprKey)) return false;
            ExprKey k = (ExprKey) o;
            return kind == k.kind && a == k.a && b == k.b && extra == k.extra;
        }
    }

    // kind 常量
    private static final int K_CONST_INT = 1;
    private static final int K_CONST_LONG_HI = 2;
    private static final int K_CONST_FLOAT = 3;
    private static final int K_CONST_DOUBLE_HI = 4;
    private static final int K_CONST_BOOL = 5;
    private static final int K_BINARY = 6;
    private static final int K_INDEX_GET = 7;
    private static final int K_CONST_CHAR = 8;

    // ---- 值编号状态 ----

    static final class VNState {
        final Map<ExprKey, Integer> exprTable = new HashMap<>();
        final int[] canon;

        /** 使用全局常量别名初始化（多前驱块的基线映射） */
        VNState(int size, int[] constAlias) {
            canon = new int[size];
            if (constAlias != null) {
                System.arraycopy(constAlias, 0, canon, 0, Math.min(size, constAlias.length));
                // 超出 constAlias 范围的映射到自身
                for (int i = constAlias.length; i < size; i++) canon[i] = i;
            } else {
                for (int i = 0; i < size; i++) canon[i] = i;
            }
        }

        VNState(VNState src) {
            exprTable.putAll(src.exprTable);
            canon = src.canon.clone();
        }

        int getCanon(int reg) {
            return (reg >= 0 && reg < canon.length) ? canon[reg] : reg;
        }

        void setCanon(int reg, int val) {
            if (reg >= 0 && reg < canon.length) canon[reg] = val;
        }

        /** 寄存器被重定义：移除以该寄存器为结果或为操作数的条目 */
        void invalidateRegister(int reg) {
            exprTable.entrySet().removeIf(e -> {
                if (e.getValue() == reg) return true;
                ExprKey k = e.getKey();
                // 仅 BINARY/INDEX_GET 的 a, b 是寄存器编号
                return k.kind >= K_BINARY && (k.a == reg || k.b == reg);
            });
        }

        /** INDEX_SET/CALL 后失效所有 INDEX_GET 条目 */
        void invalidateVolatile() {
            exprTable.entrySet().removeIf(e -> e.getKey().kind == K_INDEX_GET);
        }
    }

    // ---- 优化逻辑 ----

    private void optimizeFunction(MirFunction func) {
        List<BasicBlock> blocks = func.getBlocks();
        if (blocks.isEmpty()) return;

        int maxLocal = computeMaxLocal(func);
        if (maxLocal == 0) return;

        // 全局常量别名：单次定义的寄存器通过 MOVE 链追溯到代表寄存器
        int[] constAlias = computeConstantAliases(func, maxLocal);

        // 构建前驱映射
        Map<Integer, List<Integer>> preds = new HashMap<>();
        for (BasicBlock block : blocks) {
            MirTerminator term = block.getTerminator();
            if (term == null) continue;
            for (int succ : getSuccessors(term)) {
                preds.computeIfAbsent(succ, k -> new ArrayList<>()).add(block.getId());
            }
        }

        Map<Integer, VNState> blockStates = new HashMap<>();

        for (BasicBlock block : blocks) {
            VNState state;
            List<Integer> predList = preds.get(block.getId());
            if (predList != null && predList.size() == 1) {
                VNState predState = blockStates.get(predList.get(0));
                state = predState != null ? new VNState(predState)
                        : new VNState(maxLocal, constAlias);
            } else {
                state = new VNState(maxLocal, constAlias);
            }

            processBlock(block, state);
            blockStates.put(block.getId(), state);
        }
    }

    /**
     * 全局常量别名分析：
     * 1. 相同常量值的单次定义寄存器互为别名（如多个 CONST_INT [1] → 统一代表）
     * 2. 单次定义的 MOVE 继承源的别名
     */
    private int[] computeConstantAliases(MirFunction func, int maxLocal) {
        int[] alias = new int[maxLocal];
        int[] defCount = new int[maxLocal];
        for (int i = 0; i < maxLocal; i++) alias[i] = i;

        // 统计每个寄存器的定义次数
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                int dest = inst.getDest();
                if (dest >= 0 && dest < maxLocal) defCount[dest]++;
            }
        }

        // 常量值 → 第一个定义该常量的寄存器
        Map<ExprKey, Integer> constRepresentative = new HashMap<>();

        // Pass 1: 为相同常量值建立统一代表
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                int dest = inst.getDest();
                if (dest < 0 || dest >= maxLocal || defCount[dest] != 1) continue;

                ExprKey constKey = makeConstKey(inst);
                if (constKey != null) {
                    Integer rep = constRepresentative.get(constKey);
                    if (rep == null) {
                        constRepresentative.put(constKey, dest);
                    } else {
                        alias[dest] = rep;
                    }
                }
            }
        }

        // Pass 2: MOVE 继承源的别名
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                int dest = inst.getDest();
                if (dest < 0 || dest >= maxLocal || defCount[dest] != 1) continue;

                if (inst.getOp() == MirOp.MOVE) {
                    int src = inst.operand(0);
                    if (src >= 0 && src < maxLocal && defCount[src] == 1) {
                        alias[dest] = alias[src];
                    }
                }
            }
        }
        return alias;
    }

    /** 为常量指令构建唯一键（用于 constAlias 的常量等价判断） */
    private ExprKey makeConstKey(MirInst inst) {
        switch (inst.getOp()) {
            case CONST_INT:
                return new ExprKey(K_CONST_INT, (Integer) inst.getExtra(), 0, 0);
            case CONST_LONG: {
                long v = (Long) inst.getExtra();
                return new ExprKey(K_CONST_LONG_HI, (int) (v >>> 32), (int) v, 0);
            }
            case CONST_FLOAT:
                return new ExprKey(K_CONST_FLOAT, Float.floatToIntBits((Float) inst.getExtra()), 0, 0);
            case CONST_DOUBLE: {
                long bits = Double.doubleToLongBits((Double) inst.getExtra());
                return new ExprKey(K_CONST_DOUBLE_HI, (int) (bits >>> 32), (int) bits, 0);
            }
            case CONST_BOOL:
                return new ExprKey(K_CONST_BOOL, (Boolean) inst.getExtra() ? 1 : 0, 0, 0);
            case CONST_CHAR:
                return new ExprKey(K_CONST_CHAR, ((Number) inst.getExtra()).intValue(), 0, 0);
            default:
                return null;
        }
    }

    private void processBlock(BasicBlock block, VNState state) {
        List<MirInst> insts = block.getInstructions();
        for (int i = 0; i < insts.size(); i++) {
            MirInst inst = insts.get(i);
            int dest = inst.getDest();
            MirOp op = inst.getOp();

            // MOVE: 更新规范化映射
            if (op == MirOp.MOVE) {
                if (dest >= 0) {
                    state.invalidateRegister(dest);
                    int src = inst.operand(0);
                    state.setCanon(dest, state.getCanon(src));
                }
                continue;
            }

            // 构建表达式键
            ExprKey key = makeKey(inst, state);
            if (key != null) {
                Integer existing = state.exprTable.get(key);
                if (existing != null) {
                    // CSE 命中：替换为 MOVE
                    insts.set(i, new MirInst(MirOp.MOVE, dest,
                            new int[]{existing}, null, inst.getLocation()));
                    if (dest >= 0) {
                        state.invalidateRegister(dest);
                        state.setCanon(dest, state.getCanon(existing));
                    }
                    continue;
                }
            }

            // 无 CSE 命中：失效 + 记录
            if (dest >= 0) {
                state.invalidateRegister(dest);
                state.setCanon(dest, dest);
                if (key != null) {
                    state.exprTable.put(key, dest);
                }
            }

            // 副作用失效
            if (op == MirOp.INDEX_SET || op == MirOp.SET_FIELD
                    || op == MirOp.INVOKE_VIRTUAL
                    || op == MirOp.INVOKE_STATIC || op == MirOp.INVOKE_SPECIAL
                    || op == MirOp.INVOKE_INTERFACE) {
                state.invalidateVolatile();
            }
        }
    }

    private ExprKey makeKey(MirInst inst, VNState state) {
        switch (inst.getOp()) {
            case CONST_INT:
                return new ExprKey(K_CONST_INT, (Integer) inst.getExtra(), 0, 0);
            case CONST_LONG: {
                long v = (Long) inst.getExtra();
                return new ExprKey(K_CONST_LONG_HI, (int) (v >>> 32), (int) v, 0);
            }
            case CONST_FLOAT:
                return new ExprKey(K_CONST_FLOAT, Float.floatToIntBits((Float) inst.getExtra()), 0, 0);
            case CONST_DOUBLE: {
                long bits = Double.doubleToLongBits((Double) inst.getExtra());
                return new ExprKey(K_CONST_DOUBLE_HI, (int) (bits >>> 32), (int) bits, 0);
            }
            case CONST_BOOL:
                return new ExprKey(K_CONST_BOOL, (Boolean) inst.getExtra() ? 1 : 0, 0, 0);
            case CONST_CHAR:
                return new ExprKey(K_CONST_CHAR, ((Number) inst.getExtra()).intValue(), 0, 0);
            case BINARY: {
                int[] ops = inst.getOperands();
                if (ops == null || ops.length < 2) return null;
                Object extra = inst.getExtra();
                if (!(extra instanceof BinaryOp)) return null;
                return new ExprKey(K_BINARY, state.getCanon(ops[0]), state.getCanon(ops[1]),
                        ((BinaryOp) extra).ordinal());
            }
            case INDEX_GET: {
                int[] ops = inst.getOperands();
                if (ops == null || ops.length < 2) return null;
                return new ExprKey(K_INDEX_GET, state.getCanon(ops[0]), state.getCanon(ops[1]), 0);
            }
            default:
                return null;
        }
    }

    // ---- 辅助 ----

    private int computeMaxLocal(MirFunction func) {
        int max = 0;
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                if (inst.getDest() >= max) max = inst.getDest() + 1;
                int[] ops = inst.getOperands();
                if (ops != null) {
                    for (int op : ops) {
                        if (op >= max) max = op + 1;
                    }
                }
            }
        }
        return max;
    }

    private List<Integer> getSuccessors(MirTerminator term) {
        List<Integer> succs = new ArrayList<>(2);
        if (term instanceof MirTerminator.Goto) {
            succs.add(((MirTerminator.Goto) term).getTargetBlockId());
        } else if (term instanceof MirTerminator.Branch) {
            MirTerminator.Branch br = (MirTerminator.Branch) term;
            succs.add(br.getThenBlock());
            succs.add(br.getElseBlock());
        } else if (term instanceof MirTerminator.TailCall) {
            succs.add(((MirTerminator.TailCall) term).getEntryBlockId());
        } else if (term instanceof MirTerminator.Switch) {
            MirTerminator.Switch sw = (MirTerminator.Switch) term;
            succs.addAll(sw.getCases().values());
            succs.add(sw.getDefaultBlock());
        }
        return succs;
    }
}
