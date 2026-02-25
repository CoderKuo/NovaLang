package com.novalang.ir.mir;

import java.util.ArrayList;
import java.util.List;

/**
 * MIR 基本块。
 */
public class BasicBlock {

    private final int id;
    private final List<MirInst> instructions;
    private MirTerminator terminator;
    /** 冻结后的指令数组缓存（执行阶段使用，避免 ArrayList.get 边界检查） */
    private MirInst[] instArray;

    public BasicBlock(int id) {
        this.id = id;
        this.instructions = new ArrayList<>();
    }

    public int getId() { return id; }

    public List<MirInst> getInstructions() { return instructions; }

    /** 获取冻结的指令数组（首次调用时从 ArrayList 转换，后续直接返回缓存） */
    public MirInst[] getInstArray() {
        if (instArray == null) {
            instArray = instructions.toArray(new MirInst[0]);
        }
        return instArray;
    }

    public void addInstruction(MirInst inst) {
        instructions.add(inst);
    }

    public MirTerminator getTerminator() { return terminator; }

    public void setTerminator(MirTerminator terminator) {
        this.terminator = terminator;
    }

    public boolean hasTerminator() {
        return terminator != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("B").append(id).append(":\n");
        for (MirInst inst : instructions) {
            sb.append("  ").append(inst).append('\n');
        }
        if (terminator != null) {
            sb.append("  ").append(terminator).append('\n');
        }
        return sb.toString();
    }
}
