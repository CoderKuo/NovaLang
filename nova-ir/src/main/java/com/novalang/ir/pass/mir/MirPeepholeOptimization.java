package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * MIR 窥孔优化。
 * <ul>
 *   <li>冗余 MOVE 消除：若 MOVE 的 src 仅有一次定义且一次使用，
 *       将定义指令的 dest 直接改为 MOVE 的 dest，删除 MOVE。</li>
 *   <li>比较+分支融合：若 Branch 的条件仅被一条 BINARY 比较指令定义且仅此处使用，
 *       将比较信息融合到 Branch 终止指令中，删除 BINARY 指令。</li>
 * </ul>
 */
public class MirPeepholeOptimization implements MirPass {

    @Override
    public String getName() {
        return "MirPeepholeOptimization";
    }

    @Override
    public MirModule run(MirModule module) {
        for (MirClass cls : module.getClasses()) {
            for (MirFunction method : cls.getMethods()) {
                optimizeFunction(method);
            }
        }
        for (MirFunction func : module.getTopLevelFunctions()) {
            optimizeFunction(func);
        }
        return module;
    }

    private void optimizeFunction(MirFunction func) {
        List<BasicBlock> blocks = func.getBlocks();
        if (blocks.isEmpty()) return;

        // 1. 构建 def/use 计数
        int maxLocal = countLocals(func);
        if (maxLocal == 0) return;

        int[] defCount = new int[maxLocal];
        int[] useCount = new int[maxLocal];
        buildDefUse(blocks, defCount, useCount);

        // 构造器元数据隐式引用的局部变量（代码生成器使用，MIR 指令流中无显式使用）
        countImplicitUses(func, useCount);

        // 2. 死指令消除（清理 CSE 生成的无用 MOVE 等）
        eliminateDeadInstructions(blocks, defCount, useCount);

        // 3. 冗余 MOVE 消除
        eliminateRedundantMoves(blocks, defCount, useCount);

        // 4. 比较+分支融合
        fuseCompareBranch(blocks, defCount, useCount);
    }

    /** 计算函数中最大局部变量索引 +1 */
    private int countLocals(MirFunction func) {
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
            MirTerminator term = block.getTerminator();
            if (term instanceof MirTerminator.Branch) {
                int c = ((MirTerminator.Branch) term).getCondition();
                if (c >= max) max = c + 1;
            } else if (term instanceof MirTerminator.Return) {
                int v = ((MirTerminator.Return) term).getValueLocal();
                if (v >= max) max = v + 1;
            } else if (term instanceof MirTerminator.Switch) {
                int k = ((MirTerminator.Switch) term).getKey();
                if (k >= max) max = k + 1;
            } else if (term instanceof MirTerminator.Throw) {
                int e = ((MirTerminator.Throw) term).getExceptionLocal();
                if (e >= max) max = e + 1;
            }
        }
        return max;
    }

    /** 构建 def/use 计数 */
    private void buildDefUse(List<BasicBlock> blocks, int[] defCount, int[] useCount) {
        for (BasicBlock block : blocks) {
            for (MirInst inst : block.getInstructions()) {
                int dest = inst.getDest();
                if (dest >= 0 && dest < defCount.length) defCount[dest]++;
                int[] ops = inst.getOperands();
                if (ops != null) {
                    for (int op : ops) {
                        if (op >= 0 && op < useCount.length) useCount[op]++;
                    }
                }
            }
            // 终止指令中的使用
            MirTerminator term = block.getTerminator();
            if (term instanceof MirTerminator.Branch) {
                int c = ((MirTerminator.Branch) term).getCondition();
                if (c >= 0 && c < useCount.length) useCount[c]++;
            } else if (term instanceof MirTerminator.Return) {
                int v = ((MirTerminator.Return) term).getValueLocal();
                if (v >= 0 && v < useCount.length) useCount[v]++;
            } else if (term instanceof MirTerminator.Switch) {
                int k = ((MirTerminator.Switch) term).getKey();
                if (k >= 0 && k < useCount.length) useCount[k]++;
            } else if (term instanceof MirTerminator.Throw) {
                int e = ((MirTerminator.Throw) term).getExceptionLocal();
                if (e >= 0 && e < useCount.length) useCount[e]++;
            }
        }
    }

    /** 统计构造器元数据中隐式引用的局部变量（super/delegation 参数） */
    private void countImplicitUses(MirFunction func, int[] useCount) {
        if (func.hasSuperInitArgs()) {
            for (int l : func.getSuperInitArgLocals()) {
                if (l >= 0 && l < useCount.length) useCount[l]++;
            }
        }
        if (func.hasDelegation()) {
            for (int l : func.getDelegationArgLocals()) {
                if (l >= 0 && l < useCount.length) useCount[l]++;
            }
        }
    }

    /**
     * 冗余 MOVE 消除。
     * <p>模式: 定义指令 → MOVE %dest, %src，其中 src 仅一次定义一次使用，
     * 且定义在同一块内。将定义指令的 dest 改为 MOVE 的 dest，删除 MOVE。</p>
     */
    private void eliminateRedundantMoves(List<BasicBlock> blocks, int[] defCount, int[] useCount) {
        for (BasicBlock block : blocks) {
            List<MirInst> instructions = block.getInstructions();
            for (int i = instructions.size() - 1; i >= 0; i--) {
                MirInst inst = instructions.get(i);
                if (inst.getOp() != MirOp.MOVE) continue;

                int moveDest = inst.getDest();
                int[] moveOps = inst.getOperands();
                if (moveOps == null || moveOps.length < 1) continue;
                int moveSrc = moveOps[0];

                // src 必须恰好一次定义、一次使用
                if (moveSrc >= defCount.length || defCount[moveSrc] != 1 || useCount[moveSrc] != 1) continue;

                // 在同一块内向上查找 src 的定义
                MirInst defInst = null;
                int defIdx = -1;
                for (int j = i - 1; j >= 0; j--) {
                    if (instructions.get(j).getDest() == moveSrc) {
                        defInst = instructions.get(j);
                        defIdx = j;
                        break;
                    }
                }
                if (defInst == null) continue;

                // 安全检查：defIdx 和 MOVE 之间的指令不能读 moveDest
                // 否则提前写入 moveDest 会导致后续指令读到错误的值
                // （典型场景：TCE 生成的参数保护 MOVE 链）
                if (isLocalReadBetween(instructions, defIdx + 1, i, moveDest)) continue;

                // 替换定义指令的 dest 为 MOVE 的 dest
                MirInst newDef = new MirInst(defInst.getOp(), moveDest,
                        defInst.getOperands(), defInst.getExtra(), defInst.getLocation());
                newDef.specialKind = defInst.specialKind;
                instructions.set(defIdx, newDef);
                instructions.remove(i);

                // 更新 def/use 计数
                defCount[moveSrc]--;
                useCount[moveSrc]--;
                // moveDest 现在由 newDef 定义（与原 MOVE 定义相同，净变化为零）
            }
        }
    }

    /**
     * 检查 [from, to) 范围内是否有指令读取指定局部变量。
     */
    private boolean isLocalReadBetween(List<MirInst> instructions, int from, int to, int local) {
        for (int k = from; k < to; k++) {
            int[] ops = instructions.get(k).getOperands();
            if (ops != null) {
                for (int op : ops) {
                    if (op == local) return true;
                }
            }
        }
        return false;
    }

    /**
     * 比较+分支融合。
     * <p>对每个以 Branch 结尾的块，若条件变量由单条 BINARY 比较指令定义
     * 且仅被此 Branch 使用，则将比较融合到 Branch 中并删除 BINARY。</p>
     */
    private void fuseCompareBranch(List<BasicBlock> blocks, int[] defCount, int[] useCount) {
        for (BasicBlock block : blocks) {
            MirTerminator term = block.getTerminator();
            if (!(term instanceof MirTerminator.Branch)) continue;

            MirTerminator.Branch br = (MirTerminator.Branch) term;
            int condLocal = br.getCondition();

            // 条件变量必须恰好一次定义、一次使用（仅被此 Branch 使用）
            if (condLocal < 0 || condLocal >= defCount.length) continue;
            if (defCount[condLocal] != 1 || useCount[condLocal] != 1) continue;

            // 在块内找到定义 condLocal 的 BINARY 指令
            List<MirInst> instructions = block.getInstructions();
            for (int i = instructions.size() - 1; i >= 0; i--) {
                MirInst inst = instructions.get(i);
                if (inst.getDest() != condLocal) continue;
                if (inst.getOp() != MirOp.BINARY) break; // 非 BINARY，放弃

                Object extraObj = inst.getExtra();
                if (!(extraObj instanceof BinaryOp)) break;
                BinaryOp binOp = (BinaryOp) extraObj;

                // 只融合比较操作
                if (binOp != BinaryOp.EQ && binOp != BinaryOp.NE
                        && binOp != BinaryOp.LT && binOp != BinaryOp.GT
                        && binOp != BinaryOp.LE && binOp != BinaryOp.GE) break;

                int[] ops = inst.getOperands();
                if (ops == null || ops.length < 2) break;

                // 融合到 Branch
                br.setFusedCmp(binOp, ops[0], ops[1]);
                instructions.remove(i);

                // 更新计数
                defCount[condLocal]--;
                useCount[condLocal]--;
                break;
            }
        }
    }

    /**
     * 死指令消除。
     * <p>移除 dest 的 useCount 为 0 且无副作用的指令（CONST/BINARY/MOVE/INDEX_GET 等）。
     * 迭代执行直到无更多变化，确保级联死代码被完全清除。</p>
     */
    private void eliminateDeadInstructions(List<BasicBlock> blocks, int[] defCount, int[] useCount) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock block : blocks) {
                List<MirInst> instructions = block.getInstructions();
                for (int i = instructions.size() - 1; i >= 0; i--) {
                    MirInst inst = instructions.get(i);
                    int dest = inst.getDest();
                    if (dest < 0 || dest >= useCount.length) continue;
                    if (useCount[dest] > 0) continue;

                    MirOp op = inst.getOp();
                    // INDEX_GET 可抛 IndexOutOfBoundsException，不可 DCE
                    // BINARY DIV/REM 可抛 ArithmeticException，不可 DCE
                    if (op == MirOp.INDEX_GET) continue;
                    if (op == MirOp.BINARY) {
                        Object extra = inst.getExtra();
                        if (extra instanceof BinaryOp) {
                            BinaryOp bop = (BinaryOp) extra;
                            if (bop == BinaryOp.DIV || bop == BinaryOp.MOD) continue;
                        }
                    }
                    if (op == MirOp.CONST_INT || op == MirOp.CONST_LONG
                            || op == MirOp.CONST_FLOAT || op == MirOp.CONST_DOUBLE
                            || op == MirOp.CONST_STRING || op == MirOp.CONST_NULL
                            || op == MirOp.CONST_BOOL || op == MirOp.CONST_CHAR
                            || op == MirOp.BINARY || op == MirOp.UNARY
                            || op == MirOp.MOVE) {
                        instructions.remove(i);
                        defCount[dest]--;
                        int[] ops = inst.getOperands();
                        if (ops != null) {
                            for (int opIdx : ops) {
                                if (opIdx >= 0 && opIdx < useCount.length) {
                                    useCount[opIdx]--;
                                }
                            }
                        }
                        changed = true;
                    }
                }
            }
        }
    }
}
