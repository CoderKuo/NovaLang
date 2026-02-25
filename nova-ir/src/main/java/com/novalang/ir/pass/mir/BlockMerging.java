package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * MIR P0 优化：合并单前驱/单后继基本块。
 * 如果块 A 的唯一后继是块 B，且 B 的唯一前驱是 A，则合并 A 和 B。
 */
public class BlockMerging implements MirPass {

    @Override
    public String getName() {
        return "BlockMerging";
    }

    @Override
    public MirModule run(MirModule module) {
        for (MirClass cls : module.getClasses()) {
            for (MirFunction method : cls.getMethods()) {
                mergeBlocks(method);
            }
        }
        for (MirFunction func : module.getTopLevelFunctions()) {
            mergeBlocks(func);
        }
        return module;
    }

    private void mergeBlocks(MirFunction func) {
        List<BasicBlock> blocks = func.getBlocks();
        if (blocks.size() <= 1) return;

        // 收集异常表引用的 block ID（不可合并/删除）
        Set<Integer> tryCatchBlocks = new HashSet<>();
        for (MirFunction.TryCatchEntry tce : func.getTryCatchEntries()) {
            tryCatchBlocks.add(tce.tryStartBlock);
            tryCatchBlocks.add(tce.tryEndBlock);
            tryCatchBlocks.add(tce.handlerBlock);
        }

        // 建立前驱计数和块映射（一次构建，增量维护）
        Map<Integer, Integer> predCount = new HashMap<>();
        Map<Integer, BasicBlock> blockMap = new HashMap<>();
        for (BasicBlock block : blocks) {
            blockMap.put(block.getId(), block);
            predCount.put(block.getId(), 0);
        }
        for (BasicBlock block : blocks) {
            for (int succ : getSuccessors(block.getTerminator())) {
                predCount.merge(succ, 1, Integer::sum);
            }
        }

        int entryId = blocks.get(0).getId();

        // 扫描合并，合并后回退索引以重新检查当前块（可能链式合并）
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            MirTerminator term = block.getTerminator();

            if (!(term instanceof MirTerminator.Goto)) continue;

            int targetId = ((MirTerminator.Goto) term).getTargetBlockId();
            BasicBlock target = blockMap.get(targetId);
            if (target == null) continue;

            Integer count = predCount.get(targetId);
            if (count == null || count != 1) continue;

            if (targetId == entryId) continue;
            if (block.getId() == entryId) continue;

            if (tryCatchBlocks.contains(targetId)) continue;
            if (tryCatchBlocks.contains(block.getId())) continue;

            if (targetId == block.getId()) continue;

            // 合并：A 吸收 B 的指令和 terminator
            block.getInstructions().addAll(target.getInstructions());
            block.setTerminator(target.getTerminator());

            // 增量更新：移除 B
            blocks.remove(target);
            blockMap.remove(targetId);
            predCount.remove(targetId);

            // 回退索引，重新检查当前块（A 的新 terminator 可能继续合并）
            i--;
        }
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
        return Collections.emptyList();
    }
}
