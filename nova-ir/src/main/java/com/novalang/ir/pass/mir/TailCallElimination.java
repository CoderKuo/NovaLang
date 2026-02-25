package com.novalang.ir.pass.mir;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 尾递归消除（Tail Call Elimination, TCE）。
 * <p>
 * 检测自递归尾调用（函数最后一条指令是调用自身且结果直接 return），
 * 将其转换为参数赋值 + 跳转到函数入口块，从而将递归转为循环。
 * <p>
 * 转换前：
 * <pre>
 *   %result = INVOKE_STATIC "Owner|funcName|desc" [arg0, arg1, ...]
 *   return %result
 * </pre>
 * 转换后：
 * <pre>
 *   %temp0 = MOVE arg0        // 先暂存所有参数（防止覆盖）
 *   %temp1 = MOVE arg1
 *   MOVE param0 = %temp0      // 再写入参数局部变量
 *   MOVE param1 = %temp1
 *   goto entry_block
 * </pre>
 */
public class TailCallElimination implements MirPass {

    @Override
    public String getName() {
        return "TailCallElimination";
    }

    @Override
    public MirModule run(MirModule module) {
        for (MirClass cls : module.getClasses()) {
            for (MirFunction method : cls.getMethods()) {
                optimizeFunction(method, cls.getName());
            }
        }
        for (MirFunction func : module.getTopLevelFunctions()) {
            optimizeFunction(func, null);
        }
        return module;
    }

    private void optimizeFunction(MirFunction func, String ownerClass) {
        String funcName = func.getName();
        int paramCount = func.getParams().size();
        // 实例方法：local 0 是 this，声明参数从 local 1 开始
        boolean isInstance = ownerClass != null
                && !func.getModifiers().contains(com.novalang.compiler.ast.Modifier.STATIC);
        int totalSlots = isInstance ? paramCount + 1 : paramCount; // 含 this

        // 跳过构造器（构造器不能尾递归优化）
        if (funcName.startsWith("<")) return;

        int entryBlockId = func.getBlocks().get(0).getId();
        // 尾递归跳转目标：跳过默认参数检查，直接到函数体起始块
        int tailTargetBlockId = func.getBodyStartBlockId() > 0
                ? func.getBodyStartBlockId() : entryBlockId;

        // 构建 blockId → BasicBlock 映射
        Map<Integer, BasicBlock> blockMap = new HashMap<>();
        for (BasicBlock b : func.getBlocks()) blockMap.put(b.getId(), b);

        for (BasicBlock block : func.getBlocks()) {
            // 模式 1: 块以 Return 结尾（直接尾调用）
            // 模式 2: 块以 Goto → 空块 → Return 结尾（if-else 合并块的尾调用）
            MirTerminator term = block.getTerminator();
            int returnLocal;
            if (term instanceof MirTerminator.Return) {
                returnLocal = ((MirTerminator.Return) term).getValueLocal();
            } else if (term instanceof MirTerminator.Goto) {
                BasicBlock target = blockMap.get(((MirTerminator.Goto) term).getTargetBlockId());
                if (target == null || !target.getInstructions().isEmpty()) continue;
                if (!(target.getTerminator() instanceof MirTerminator.Return)) continue;
                returnLocal = ((MirTerminator.Return) target.getTerminator()).getValueLocal();
            } else {
                continue;
            }

            List<MirInst> insts = block.getInstructions();
            if (insts.isEmpty()) continue;

            // 查找尾调用指令：可能是最后一条（直接），或倒数第二条（后跟 MOVE）
            MirInst callInst;
            int callIdx;
            MirInst lastInst = insts.get(insts.size() - 1);
            MirOp lastOp = lastInst.getOp();

            if (lastOp == MirOp.INVOKE_STATIC || lastOp == MirOp.INVOKE_VIRTUAL) {
                // 模式 A: 最后一条就是调用
                callInst = lastInst;
                callIdx = insts.size() - 1;
                if (callInst.getDest() != returnLocal) continue;
            } else if (lastOp == MirOp.MOVE && insts.size() >= 2) {
                // 模式 B: INVOKE → MOVE %result, %temp → return %result
                int moveSrc = lastInst.operand(0);
                if (lastInst.getDest() != returnLocal) continue;
                MirInst prev = insts.get(insts.size() - 2);
                MirOp prevOp = prev.getOp();
                if (prevOp != MirOp.INVOKE_STATIC && prevOp != MirOp.INVOKE_VIRTUAL) continue;
                if (prev.getDest() != moveSrc) continue;
                callInst = prev;
                callIdx = insts.size() - 2;
            } else {
                continue;
            }

            // 检查是否为自递归调用
            int[] callArgs = callInst.getOperands();
            if (!isSelfCall(callInst, funcName, totalSlots, ownerClass, callArgs)) continue;

            // ====== 执行转换 ======
            // 删除调用指令（和可能的尾部 MOVE）
            if (callIdx == insts.size() - 2) {
                insts.remove(insts.size() - 1); // 先删 MOVE
            }
            insts.remove(callIdx); // 再删 INVOKE
            SourceLocation loc = callInst.getLocation();

            // 步骤 1: 所有实参 → 临时变量（防止参数覆盖导致数据丢失）
            int[] temps = new int[totalSlots];
            for (int i = 0; i < totalSlots; i++) {
                MirType type = i == 0 && isInstance
                        ? MirType.ofObject(ownerClass)
                        : func.getParams().get(isInstance ? i - 1 : i).getType();
                temps[i] = func.newLocal("$tail" + i, type);
                insts.add(new MirInst(MirOp.MOVE, temps[i],
                        new int[]{callArgs[i]}, type, loc));
            }

            // 步骤 2: 临时变量 → 参数局部变量（local 0..totalSlots-1）
            for (int i = 0; i < totalSlots; i++) {
                MirType type = i == 0 && isInstance
                        ? MirType.ofObject(ownerClass)
                        : func.getParams().get(isInstance ? i - 1 : i).getType();
                insts.add(new MirInst(MirOp.MOVE, i,
                        new int[]{temps[i]}, type, loc));
            }

            // 步骤 3: Return → TailCall 入口块（显式尾递归语义）
            block.setTerminator(new MirTerminator.TailCall(term.getLocation(), tailTargetBlockId));
        }
    }

    /**
     * 判断 INVOKE 指令是否为自递归调用。
     * extra 格式: "owner|methodName|descriptor"
     */
    private boolean isSelfCall(MirInst inst, String funcName, int paramCount,
                                String ownerClass, int[] callArgs) {
        Object extra = inst.getExtra();
        if (!(extra instanceof String)) return false;
        String extraStr = (String) extra;

        int firstSep = extraStr.indexOf('|');
        if (firstSep < 0) return false;
        int secondSep = extraStr.indexOf('|', firstSep + 1);
        if (secondSep < 0) return false;

        // 检查函数名
        String callName = extraStr.substring(firstSep + 1, secondSep);
        if (!callName.equals(funcName)) return false;

        // 检查参数数量
        int argCount = callArgs != null ? callArgs.length : 0;
        if (argCount != paramCount) return false;

        // 检查 owner
        if (ownerClass != null) {
            // 类方法：owner 需精确匹配
            String callOwner = extraStr.substring(0, firstSep);
            if (!callOwner.equals(ownerClass)) return false;
        } else {
            // 顶层函数：自递归调用必须是 INVOKE_STATIC
            if (inst.getOp() != MirOp.INVOKE_STATIC) return false;
            // owner 必须是 $PipeCall 或以 $Module 结尾（interpreterMode 下的自调用路径）
            // 其他 owner（如 nova/runtime/stdlib/StringExtensions）是标准库调用，不是自递归
            String callOwner = extraStr.substring(0, firstSep);
            if (!"$PipeCall".equals(callOwner) && !callOwner.endsWith("$Module")) return false;
        }

        return true;
    }
}
