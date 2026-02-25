package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * 强度削弱（Strength Reduction）。
 * <p>
 * 将昂贵的算术操作替换为等价的更廉价操作：
 * <ul>
 *   <li>{@code x * 2^n} → {@code x << n}（乘以 2 的幂 → 左移）</li>
 *   <li>{@code x * 2} → {@code x + x}（乘以 2 → 自加，省去移位常量）</li>
 * </ul>
 */
public class StrengthReduction implements MirPass {

    @Override
    public String getName() {
        return "StrengthReduction";
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
        // 1. 收集单定义的整数常量：local → value
        Map<Integer, Long> constMap = buildConstMap(func);
        if (constMap.isEmpty()) return;

        // 2. 扫描并替换
        for (BasicBlock block : func.getBlocks()) {
            List<MirInst> instructions = block.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                MirInst inst = instructions.get(i);
                if (inst.getOp() != MirOp.BINARY) continue;

                BinaryOp binOp = inst.extraAs();
                if (binOp != BinaryOp.MUL) continue;

                int[] operands = inst.getOperands();
                if (operands == null || operands.length < 2) continue;

                int left = operands[0];
                int right = operands[1];
                Long leftVal = constMap.get(left);
                Long rightVal = constMap.get(right);

                // 确定哪边是常量 2^n，哪边是被乘数
                int base;
                long constVal;
                if (rightVal != null && rightVal > 1 && isPowerOf2(rightVal)) {
                    base = left;
                    constVal = rightVal;
                } else if (leftVal != null && leftVal > 1 && isPowerOf2(leftVal)) {
                    base = right;
                    constVal = leftVal;
                } else {
                    continue;
                }

                int shift = Long.numberOfTrailingZeros(constVal);

                if (shift == 1) {
                    // x * 2 → x + x（省去额外的常量局部变量）
                    MirInst addInst = new MirInst(MirOp.BINARY, inst.getDest(),
                            new int[]{base, base}, BinaryOp.ADD, inst.getLocation());
                    instructions.set(i, addInst);
                } else {
                    // x * 2^n → x << n
                    int shiftLocal = func.newLocal("$shl", MirType.ofInt());
                    MirInst constInst = new MirInst(MirOp.CONST_INT, shiftLocal,
                            null, shift, inst.getLocation());
                    instructions.add(i, constInst);
                    i++;
                    MirInst shlInst = new MirInst(MirOp.BINARY, inst.getDest(),
                            new int[]{base, shiftLocal}, BinaryOp.SHL, inst.getLocation());
                    instructions.set(i, shlInst);
                }
            }
        }
    }

    /**
     * 构建单定义整数常量映射。
     * 只收集恰好有一处定义且为 CONST_INT / CONST_LONG 的局部变量。
     */
    private Map<Integer, Long> buildConstMap(MirFunction func) {
        Map<Integer, Long> constMap = new HashMap<>();
        Set<Integer> nonConst = new HashSet<>();

        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                int dest = inst.getDest();
                if (dest < 0) continue;

                if (!nonConst.contains(dest)) {
                    if (inst.getOp() == MirOp.CONST_INT) {
                        if (constMap.containsKey(dest)) {
                            nonConst.add(dest);
                            constMap.remove(dest);
                        } else {
                            constMap.put(dest, ((Integer) inst.getExtra()).longValue());
                        }
                    } else if (inst.getOp() == MirOp.CONST_LONG) {
                        if (constMap.containsKey(dest)) {
                            nonConst.add(dest);
                            constMap.remove(dest);
                        } else {
                            constMap.put(dest, (Long) inst.getExtra());
                        }
                    } else {
                        nonConst.add(dest);
                        constMap.remove(dest);
                    }
                }
            }
        }

        return constMap;
    }

    private static boolean isPowerOf2(long n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}
