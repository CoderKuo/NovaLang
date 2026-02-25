package com.novalang.ir.mir;

import com.novalang.compiler.ast.Modifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
