package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * MIR P0 优化：删除不可达基本块。
 * 从 entry block 开始做可达性分析，移除所有不可达的块。
 */
public class DeadBlockElimination implements MirPass {

    @Override
    public String getName() {
        return "DeadBlockElimination";
    }

    @Override
    public MirModule run(MirModule module) {
        // 处理所有类的方法
        for (MirClass cls : module.getClasses()) {
            for (MirFunction method : cls.getMethods()) {
                eliminateDeadBlocks(method);
            }
        }
        // 处理顶层函数
        for (MirFunction func : module.getTopLevelFunctions()) {
            eliminateDeadBlocks(func);
        }
        return module;
    }

    private void eliminateDeadBlocks(MirFunction func) {
        List<BasicBlock> blocks = func.getBlocks();
        if (blocks.size() <= 1) return;

        // 建立 id → block 映射（需要在异常表处理前使用）
        Map<Integer, BasicBlock> blockMap = new HashMap<>();
        for (BasicBlock block : blocks) {
            blockMap.put(block.getId(), block);
        }

        // 从 entry block 开始 BFS 找可达块
        Set<Integer> reachable = new HashSet<>();
        Queue<Integer> worklist = new ArrayDeque<>();

        BasicBlock entry = blocks.get(0);
        reachable.add(entry.getId());
        worklist.add(entry.getId());

        // 异常表引用的块必须保留
        for (MirFunction.TryCatchEntry tce : func.getTryCatchEntries()) {
            // handler 块
            if (reachable.add(tce.handlerBlock)) {
                worklist.add(tce.handlerBlock);
            }
            // tryEndBlock 的标签必须存在（异常表引用）
            if (reachable.add(tce.tryEndBlock)) {
                worklist.add(tce.tryEndBlock);
            }
            // try 范围内的块：从 tryStartBlock 做 BFS，到 tryEndBlock/handler 为止
            Deque<Integer> tryQueue = new ArrayDeque<>();
            Set<Integer> tryVisited = new HashSet<>();
            tryVisited.add(tce.tryStartBlock);
            tryQueue.add(tce.tryStartBlock);
            if (reachable.add(tce.tryStartBlock)) {
                worklist.add(tce.tryStartBlock);
            }
            while (!tryQueue.isEmpty()) {
                int bid = tryQueue.poll();
                BasicBlock bb = blockMap.get(bid);
                if (bb == null) continue;
                for (int succ : getSuccessors(bb.getTerminator())) {
                    if (succ == tce.tryEndBlock || succ == tce.handlerBlock) continue;
                    if (!tryVisited.add(succ)) continue;
                    if (reachable.add(succ)) {
                        worklist.add(succ);
                    }
                    tryQueue.add(succ);
                }
            }
        }

        while (!worklist.isEmpty()) {
            int blockId = worklist.poll();
            BasicBlock block = blockMap.get(blockId);
            if (block == null) continue;

            for (int successor : getSuccessors(block.getTerminator())) {
                if (reachable.add(successor)) {
                    worklist.add(successor);
                }
            }
        }

        // 移除不可达块
        blocks.removeIf(block -> !reachable.contains(block.getId()));
    }

    private List<Integer> getSuccessors(MirTerminator term) {
        if (term == null) return Collections.emptyList();

        if (term instanceof MirTerminator.Goto) {
            return Collections.singletonList(((MirTerminator.Goto) term).getTargetBlockId());
        } else if (term instanceof MirTerminator.Branch) {
            MirTerminator.Branch branch = (MirTerminator.Branch) term;
            List<Integer> succ = new ArrayList<>(2);
            succ.add(branch.getThenBlock());
            succ.add(branch.getElseBlock());
            return succ;
        } else if (term instanceof MirTerminator.Switch) {
            MirTerminator.Switch sw = (MirTerminator.Switch) term;
            Set<Integer> succ = new LinkedHashSet<>(sw.getCases().values());
            succ.add(sw.getDefaultBlock());
            return new ArrayList<>(succ);
        } else if (term instanceof MirTerminator.TailCall) {
            return Collections.singletonList(((MirTerminator.TailCall) term).getEntryBlockId());
        }
        // Return, Throw, Unreachable 没有后继
        return Collections.emptyList();
    }
}
