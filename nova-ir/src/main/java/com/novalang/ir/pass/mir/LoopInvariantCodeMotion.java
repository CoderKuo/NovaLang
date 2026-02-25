package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * 循环不变量提升（Loop-Invariant Code Motion, LICM）。
 * <p>
 * 识别循环中不依赖循环变量的纯计算指令，将其提升到循环前的 pre-header 块中，
 * 避免在每次迭代中重复计算。
 * <p>
 * 内部使用数组索引 + BitSet 替代 HashMap/HashSet，消除 Integer 装箱开销。
 */
public class LoopInvariantCodeMotion implements MirPass {

    @Override
    public String getName() {
        return "LoopInvariantCodeMotion";
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
        int n = blocks.size();
        if (n <= 1) return;

        // ---- blockId → 连续索引映射 (O(1) lookup, 无 Integer 装箱) ----
        int maxId = 0;
        for (int i = 0; i < n; i++) {
            int id = blocks.get(i).getId();
            if (id > maxId) maxId = id;
        }
        int[] idToIdx = new int[maxId + 1];
        Arrays.fill(idToIdx, -1);
        for (int i = 0; i < n; i++) idToIdx[blocks.get(i).getId()] = i;

        // ---- 前驱图 (int[][], 按索引寻址, 无 HashMap/HashSet) ----
        int[][] preds = buildPredecessors(blocks, n, maxId, idToIdx, func);

        // ---- 支配集 (BitSet[], 不转换为 Map) ----
        BitSet[] dom = buildDominators(n, preds);

        // ---- 检测回边 → 自然循环 (按 header 合并) ----
        int[] succBuf = new int[16];
        List<NaturalLoop> loops = null;
        // headerIdx → loops list 中的下标（用于合并同 header 的多条回边）
        int[] headerToLoopIdx = null;

        for (int i = 0; i < n; i++) {
            MirTerminator term = blocks.get(i).getTerminator();
            if (term == null) continue;
            int succCount = getSuccessorIds(term, succBuf);
            for (int s = 0; s < succCount; s++) {
                int succId = succBuf[s];
                int succIdx = (succId >= 0 && succId <= maxId) ? idToIdx[succId] : -1;
                if (succIdx < 0 || !dom[i].get(succIdx)) continue;
                // 回边 i → succIdx
                BitSet body = computeLoopBody(succIdx, i, n, preds);
                if (loops == null) {
                    loops = new ArrayList<>();
                    headerToLoopIdx = new int[n];
                    Arrays.fill(headerToLoopIdx, -1);
                }
                int existIdx = headerToLoopIdx[succIdx];
                if (existIdx >= 0) {
                    loops.get(existIdx).body.or(body);
                } else {
                    headerToLoopIdx[succIdx] = loops.size();
                    loops.add(new NaturalLoop(succIdx, body));
                }
            }
        }

        if (loops == null) return;

        // ---- 提升不变量 ----
        for (NaturalLoop loop : loops) {
            hoistInvariants(loop, blocks, func, preds, n);
        }
    }

    // ==================== 前驱图 ====================

    /**
     * 构建前驱图，返回 int[][] 按块索引寻址。
     * 两遍扫描：第一遍计数，第二遍填充，避免 ArrayList/HashSet 分配。
     */
    private int[][] buildPredecessors(List<BasicBlock> blocks, int n, int maxId,
                                       int[] idToIdx, MirFunction func) {
        int[] succBuf = new int[16];
        // 第一遍：统计每个块的前驱数量
        int[] predCount = new int[n];
        for (int i = 0; i < n; i++) {
            int count = getSuccessorIds(blocks.get(i).getTerminator(), succBuf);
            for (int s = 0; s < count; s++) {
                int succId = succBuf[s];
                int succIdx = (succId >= 0 && succId <= maxId) ? idToIdx[succId] : -1;
                if (succIdx >= 0) predCount[succIdx]++;
            }
        }

        // 分配精确大小的数组
        int[][] preds = new int[n][];
        for (int i = 0; i < n; i++) preds[i] = new int[predCount[i]];

        // 第二遍：填充前驱索引
        int[] cursor = new int[n];
        for (int i = 0; i < n; i++) {
            int count = getSuccessorIds(blocks.get(i).getTerminator(), succBuf);
            for (int s = 0; s < count; s++) {
                int succId = succBuf[s];
                int succIdx = (succId >= 0 && succId <= maxId) ? idToIdx[succId] : -1;
                if (succIdx >= 0) {
                    preds[succIdx][cursor[succIdx]++] = i;
                }
            }
        }

        // 为异常处理块添加合成前驱（entry → handler），确保支配集正确
        for (MirFunction.TryCatchEntry tce : func.getTryCatchEntries()) {
            int handlerIdx = (tce.handlerBlock >= 0 && tce.handlerBlock <= maxId)
                    ? idToIdx[tce.handlerBlock] : -1;
            if (handlerIdx >= 0 && preds[handlerIdx].length == 0) {
                preds[handlerIdx] = new int[]{0}; // entry 索引 = 0
            }
        }

        return preds;
    }

    // ==================== 支配集 ====================

    /**
     * 迭代数据流算法计算支配集。
     * dom(entry) = {entry}
     * dom(n) = {n} ∪ ∩{dom(p) | p ∈ preds(n)}
     */
    private BitSet[] buildDominators(int n, int[][] preds) {
        BitSet[] dom = new BitSet[n];
        for (int i = 0; i < n; i++) {
            dom[i] = new BitSet(n);
            if (i == 0) { // entry block
                dom[i].set(0);
            } else {
                dom[i].set(0, n); // 全集
            }
        }

        BitSet temp = new BitSet(n);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < n; i++) { // 跳过 entry (i=0)
                int[] ps = preds[i];
                if (ps.length == 0) continue;

                temp.set(0, n); // 全集
                for (int p : ps) temp.and(dom[p]);
                temp.set(i); // 加自身

                if (!temp.equals(dom[i])) {
                    dom[i] = (BitSet) temp.clone();
                    changed = true;
                }
            }
        }
        return dom;
    }

    // ==================== 循环体计算 ====================

    /**
     * 从回边 (tailIdx→headerIdx) 计算自然循环体（BitSet 表示）。
     * 使用 int 数组做 BFS 队列，无 ArrayDeque/HashSet 分配。
     */
    private BitSet computeLoopBody(int headerIdx, int tailIdx, int n, int[][] preds) {
        BitSet body = new BitSet(n);
        body.set(headerIdx);
        if (headerIdx == tailIdx) return body;

        body.set(tailIdx);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        queue[tail++] = tailIdx;
        while (head < tail) {
            int cur = queue[head++];
            for (int p : preds[cur]) {
                if (!body.get(p)) {
                    body.set(p);
                    queue[tail++] = p;
                }
            }
        }
        return body;
    }

    // ==================== 不变量提升 ====================

    private void hoistInvariants(NaturalLoop loop, List<BasicBlock> blocks, MirFunction func,
                                  int[][] preds, int n) {
        BitSet body = loop.body;

        // 1. 统计循环体内各局部变量的定义次数
        Map<Integer, Integer> defCount = new HashMap<>();
        for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
            for (MirInst inst : blocks.get(idx).getInstructions()) {
                if (inst.getDest() >= 0) {
                    defCount.merge(inst.getDest(), 1, Integer::sum);
                }
            }
        }

        // 2. 循环体内定义的所有局部变量
        Set<Integer> loopDefs = defCount.keySet();

        // 3. 迭代标记不变量（fixed-point）
        Set<MirInst> invariants = new LinkedHashSet<>();
        Set<Integer> invariantDests = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
                for (MirInst inst : blocks.get(idx).getInstructions()) {
                    if (invariants.contains(inst)) continue;
                    if (!isPure(inst.getOp())) continue;
                    if (inst.getDest() < 0) continue;

                    Integer count = defCount.get(inst.getDest());
                    if (count == null || count != 1) continue;

                    if (allOperandsInvariant(inst, loopDefs, invariantDests)) {
                        invariants.add(inst);
                        invariantDests.add(inst.getDest());
                        changed = true;
                    }
                }
            }
        }

        if (invariants.isEmpty()) return;

        // 4. 创建 pre-header
        BasicBlock preheader = func.newBlock();
        int headerId = blocks.get(loop.headerIdx).getId();

        // 重定向循环头的外部前驱到 pre-header
        for (int predIdx : preds[loop.headerIdx]) {
            if (body.get(predIdx)) continue; // 跳过回边
            redirectTerminator(blocks.get(predIdx), headerId, preheader.getId());
        }

        // pre-header → 循环头
        preheader.setTerminator(new MirTerminator.Goto(null, headerId));

        // 5. 移至 pre-header
        for (MirInst inst : invariants) {
            preheader.getInstructions().add(inst);
        }

        // 6. 从循环体中移除已提升的指令
        Set<MirInst> identitySet = Collections.newSetFromMap(new IdentityHashMap<>());
        identitySet.addAll(invariants);
        for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
            blocks.get(idx).getInstructions().removeIf(identitySet::contains);
        }
    }

    private boolean allOperandsInvariant(MirInst inst, Set<Integer> loopDefs,
                                          Set<Integer> invariantDests) {
        int[] operands = inst.getOperands();
        if (operands == null) return true;
        for (int op : operands) {
            if (!loopDefs.contains(op)) continue;
            if (!invariantDests.contains(op)) return false;
        }
        return true;
    }

    private boolean isPure(MirOp op) {
        switch (op) {
            case CONST_INT:
            case CONST_LONG:
            case CONST_FLOAT:
            case CONST_DOUBLE:
            case CONST_STRING:
            case CONST_BOOL:
            case CONST_NULL:
            case CONST_CLASS:
            case MOVE:
            case BINARY:
            case UNARY:
            case TYPE_CHECK:
            case TYPE_CAST:
                return true;
            default:
                return false;
        }
    }

    // ==================== CFG 工具 ====================

    /**
     * 获取终止器的后继块 ID，写入 buf。返回后继数量。
     * Goto/Branch/TailCall 零分配；Switch（罕见）内部去重。
     */
    private int getSuccessorIds(MirTerminator term, int[] buf) {
        if (term == null) return 0;
        if (term instanceof MirTerminator.Goto) {
            buf[0] = ((MirTerminator.Goto) term).getTargetBlockId();
            return 1;
        }
        if (term instanceof MirTerminator.Branch) {
            MirTerminator.Branch b = (MirTerminator.Branch) term;
            buf[0] = b.getThenBlock();
            buf[1] = b.getElseBlock();
            return 2;
        }
        if (term instanceof MirTerminator.TailCall) {
            buf[0] = ((MirTerminator.TailCall) term).getEntryBlockId();
            return 1;
        }
        if (term instanceof MirTerminator.Switch) {
            MirTerminator.Switch sw = (MirTerminator.Switch) term;
            int count = 0;
            for (int target : sw.getCases().values()) {
                boolean dup = false;
                for (int j = 0; j < count; j++) {
                    if (buf[j] == target) { dup = true; break; }
                }
                if (!dup) {
                    if (count >= buf.length) return count;
                    buf[count++] = target;
                }
            }
            int def = sw.getDefaultBlock();
            boolean dup = false;
            for (int j = 0; j < count; j++) {
                if (buf[j] == def) { dup = true; break; }
            }
            if (!dup && count < buf.length) buf[count++] = def;
            return count;
        }
        return 0;
    }

    private void redirectTerminator(BasicBlock block, int oldTarget, int newTarget) {
        MirTerminator term = block.getTerminator();
        if (term instanceof MirTerminator.Goto) {
            MirTerminator.Goto g = (MirTerminator.Goto) term;
            if (g.getTargetBlockId() == oldTarget) {
                block.setTerminator(new MirTerminator.Goto(g.getLocation(), newTarget));
            }
        } else if (term instanceof MirTerminator.Branch) {
            MirTerminator.Branch b = (MirTerminator.Branch) term;
            int thenBlock = b.getThenBlock() == oldTarget ? newTarget : b.getThenBlock();
            int elseBlock = b.getElseBlock() == oldTarget ? newTarget : b.getElseBlock();
            if (thenBlock != b.getThenBlock() || elseBlock != b.getElseBlock()) {
                block.setTerminator(new MirTerminator.Branch(
                        b.getLocation(), b.getCondition(), thenBlock, elseBlock));
            }
        } else if (term instanceof MirTerminator.Switch) {
            MirTerminator.Switch sw = (MirTerminator.Switch) term;
            Map<Object, Integer> newCases = new LinkedHashMap<>();
            boolean casesChanged = false;
            for (Map.Entry<Object, Integer> e : sw.getCases().entrySet()) {
                if (e.getValue() == oldTarget) {
                    newCases.put(e.getKey(), newTarget);
                    casesChanged = true;
                } else {
                    newCases.put(e.getKey(), e.getValue());
                }
            }
            int newDefault = sw.getDefaultBlock() == oldTarget ? newTarget : sw.getDefaultBlock();
            if (casesChanged || newDefault != sw.getDefaultBlock()) {
                block.setTerminator(new MirTerminator.Switch(
                        sw.getLocation(), sw.getKey(), newCases, newDefault));
            }
        }
    }

    // ==================== 辅助类 ====================

    private static class NaturalLoop {
        final int headerIdx;
        final BitSet body;

        NaturalLoop(int headerIdx, BitSet body) {
            this.headerIdx = headerIdx;
            this.body = body;
        }
    }
}
