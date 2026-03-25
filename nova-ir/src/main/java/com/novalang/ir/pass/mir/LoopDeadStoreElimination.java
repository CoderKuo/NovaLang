package com.novalang.ir.pass.mir;

import com.novalang.ir.mir.*;
import com.novalang.ir.pass.MirPass;

import java.util.*;

/**
 * 循环内死存储消除（Loop Dead Store Elimination）。
 *
 * <p>将循环体内对 $Module 顶层变量的 SET_STATIC + NovaScriptContext.set
 * 下沉到循环出口边，使每个变量在循环结束后仅执行一次存储。</p>
 *
 * <p>编译器为每次顶层变量赋值生成两条同步指令（SET_STATIC 写入 moduleStaticFields、
 * INVOKE_STATIC NovaScriptContext.set 写入 Environment）。在循环内部，中间值
 * 不可能被外部观测——只有循环结束后的最终值有意义。此 pass 消除循环体内的冗余同步，
 * 将其下沉到循环出口块。</p>
 *
 * <p>JMH 基准测试显示，对 arith_loop（200K 次迭代）此优化可将开销从 ~89ns/迭代
 * 降至 ~7ns/迭代（约 12× 提速）。</p>
 */
public class LoopDeadStoreElimination implements MirPass {

    private static final String MODULE_SUFFIX = "$Module";
    private static final String SCRIPT_CTX_SET = "NovaScriptContext|set|";

    @Override
    public String getName() {
        return "LoopDeadStoreElimination";
    }

    @Override
    public MirModule run(MirModule module) {
        for (MirClass cls : module.getClasses()) {
            for (MirFunction m : cls.getMethods()) {
                optimizeFunction(m);
            }
        }
        for (MirFunction f : module.getTopLevelFunctions()) {
            optimizeFunction(f);
        }
        return module;
    }

    private void optimizeFunction(MirFunction func) {
        List<BasicBlock> blocks = func.getBlocks();
        int n = blocks.size();
        if (n <= 2) return;

        // 跳过含 try-catch 的函数（异常路径可能观测中间值）
        if (!func.getTryCatchEntries().isEmpty()) return;

        // ---- blockId → 连续索引映射 ----
        int maxId = 0;
        for (int i = 0; i < n; i++) {
            int id = blocks.get(i).getId();
            if (id > maxId) maxId = id;
        }
        int[] idToIdx = new int[maxId + 1];
        Arrays.fill(idToIdx, -1);
        for (int i = 0; i < n; i++) idToIdx[blocks.get(i).getId()] = i;

        // ---- 前驱图 ----
        int[][] preds = buildPredecessors(blocks, n, maxId, idToIdx);

        // ---- 支配集 ----
        BitSet[] dom = buildDominators(n, preds);

        // ---- 检测回边 → 自然循环 ----
        int[] succBuf = new int[16];
        List<NaturalLoop> loops = null;
        int[] headerToLoopIdx = null;

        for (int i = 0; i < n; i++) {
            MirTerminator term = blocks.get(i).getTerminator();
            if (term == null) continue;
            int succCount = getSuccessorIds(term, succBuf);
            for (int s = 0; s < succCount; s++) {
                int succId = succBuf[s];
                int succIdx = (succId >= 0 && succId <= maxId) ? idToIdx[succId] : -1;
                if (succIdx < 0 || !dom[i].get(succIdx)) continue;
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

        // ---- 对每个循环做死存储消除 ----
        boolean modified = false;
        for (NaturalLoop loop : loops) {
            if (eliminateDeadStores(loop, blocks, func, n, maxId, idToIdx)) {
                modified = true;
            }
        }

        if (modified) {
            // 重置缓存（新增了 locals，blockArr/instArray 需重建）
            func.resetBlockArr();
        }
    }

    // ==================== 死存储消除核心 ====================

    private boolean eliminateDeadStores(NaturalLoop loop, List<BasicBlock> blocks,
                                         MirFunction func, int n, int maxId, int[] idToIdx) {
        BitSet body = loop.body;

        // 1. 扫描循环体：收集 $Module 的 SET_STATIC 写入和 GET_STATIC 读取
        // fieldName → 写入信息
        Map<String, StoreInfo> writeFields = null;
        Set<String> readFields = null;

        for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
            BasicBlock block = blocks.get(idx);
            List<MirInst> insts = block.getInstructions();
            for (int i = 0; i < insts.size(); i++) {
                MirInst inst = insts.get(i);
                if (inst.getOp() == MirOp.SET_STATIC) {
                    String[] parsed = parseModuleField(inst);
                    if (parsed != null) {
                        if (writeFields == null) writeFields = new HashMap<>();
                        writeFields.put(parsed[0], new StoreInfo(idx, i, inst.operand(0), inst.extraAs()));
                    }
                } else if (inst.getOp() == MirOp.GET_STATIC) {
                    String[] parsed = parseModuleField(inst);
                    if (parsed != null) {
                        if (readFields == null) readFields = new HashSet<>();
                        readFields.add(parsed[0]);
                    }
                }
            }
        }

        if (writeFields == null) return false;

        // 2. candidates = 只写不读的字段
        if (readFields != null) {
            writeFields.keySet().removeAll(readFields);
        }
        if (writeFields.isEmpty()) return false;

        // 3. 对每个 candidate 找持久寄存器
        List<SinkCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, StoreInfo> entry : writeFields.entrySet()) {
            String fieldName = entry.getKey();
            StoreInfo store = entry.getValue();

            // 向前扫描找 MOVE %persist = %valueReg
            int persistReg = findPersistentRegister(blocks.get(store.blockIdx), store.instIdx, store.valueReg);
            if (persistReg < 0) continue; // 保守跳过

            candidates.add(new SinkCandidate(fieldName, persistReg, store.extra));
        }

        if (candidates.isEmpty()) return false;

        // 4. 收集循环出口目标块
        Set<Integer> exitTargetIds = new LinkedHashSet<>();
        int[] sBuf = new int[16];
        for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
            MirTerminator term = blocks.get(idx).getTerminator();
            if (term == null) continue;
            int sc = getSuccessorIds(term, sBuf);
            for (int s = 0; s < sc; s++) {
                int succId = sBuf[s];
                int succIdx = (succId >= 0 && succId <= maxId) ? idToIdx[succId] : -1;
                if (succIdx >= 0 && !body.get(succIdx)) {
                    exitTargetIds.add(succId);
                }
            }
        }

        if (exitTargetIds.isEmpty()) return false;

        // 5. 从循环体移除候选 SET_STATIC + 伴随 INVOKE_STATIC
        Set<String> candidateFields = new HashSet<>();
        for (SinkCandidate c : candidates) candidateFields.add(c.fieldName);

        for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
            BasicBlock block = blocks.get(idx);
            List<MirInst> insts = block.getInstructions();
            Iterator<MirInst> it = insts.iterator();
            while (it.hasNext()) {
                MirInst inst = it.next();
                if (inst.getOp() == MirOp.SET_STATIC) {
                    String[] parsed = parseModuleField(inst);
                    if (parsed != null && candidateFields.contains(parsed[0])) {
                        it.remove();
                    }
                } else if (inst.getOp() == MirOp.INVOKE_STATIC && isScriptContextSet(inst)) {
                    // 检查值操作数是否匹配候选字段的 valueReg
                    int[] ops = inst.getOperands();
                    if (ops != null && ops.length >= 2) {
                        // 检查伴随的 CONST_STRING 是否是候选字段名
                        if (isCompanionEnvSet(inst, candidateFields, blocks, body)) {
                            it.remove();
                        }
                    }
                }
            }
        }

        // 6. 在每个出口目标块开头插入 SET_STATIC + INVOKE_STATIC
        for (int exitBlockId : exitTargetIds) {
            int exitIdx = idToIdx[exitBlockId];
            if (exitIdx < 0) continue;
            BasicBlock exitBlock = blocks.get(exitIdx);
            List<MirInst> exitInsts = exitBlock.getInstructions();

            // 逆序插入到开头（保持 candidates 的原始顺序）
            int insertPos = 0;
            for (SinkCandidate c : candidates) {
                // SET_STATIC %persist [extra]
                MirInst setStatic = new MirInst(MirOp.SET_STATIC, -1,
                        new int[]{c.persistReg}, c.extra, null);
                exitInsts.add(insertPos++, setStatic);

                // CONST_STRING fieldName (新 local)
                int nameLocal = func.newLocal("$ldse_" + c.fieldName, MirType.ofObject("java/lang/String"));
                MirInst constStr = new MirInst(MirOp.CONST_STRING, nameLocal,
                        null, c.fieldName, null);
                exitInsts.add(insertPos++, constStr);

                // INVOKE_STATIC nameLocal, persistReg [NovaScriptContext.set]
                String invokeExtra = "com/novalang/runtime/NovaScriptContext|set|(Ljava/lang/String;Ljava/lang/Object;)V";
                int voidDest = func.newLocal("$ldse_void", MirType.ofVoid());
                MirInst invokeStatic = new MirInst(MirOp.INVOKE_STATIC, voidDest,
                        new int[]{nameLocal, c.persistReg}, invokeExtra, null);
                invokeStatic.specialKind = MirInst.SK_ENV_ACCESS;
                exitInsts.add(insertPos++, invokeStatic);
            }
        }

        return true;
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析 SET_STATIC/GET_STATIC 的 extra 字段，如果是 $Module 字段则返回 [fieldName]。
     */
    private String[] parseModuleField(MirInst inst) {
        String extra = inst.extraAs();
        int pipe = extra.indexOf('|');
        if (pipe < 0) return null;
        String owner = extra.substring(0, pipe);
        if (!owner.endsWith(MODULE_SUFFIX)) return null;
        int pipe2 = extra.indexOf('|', pipe + 1);
        String fieldName = pipe2 >= 0 ? extra.substring(pipe + 1, pipe2) : extra.substring(pipe + 1);
        return new String[]{fieldName};
    }

    /**
     * 检查 INVOKE_STATIC 是否为 NovaScriptContext.set 调用。
     */
    private boolean isScriptContextSet(MirInst inst) {
        if (inst.specialKind == MirInst.SK_ENV_ACCESS) return true;
        String extra = inst.extraAs();
        return extra.contains(SCRIPT_CTX_SET);
    }

    /**
     * 检查 INVOKE_STATIC (NovaScriptContext.set) 是否与候选字段关联。
     * 通过检查其 extra 中包含 "|set|" 且操作数对应候选字段来判断。
     */
    private boolean isCompanionEnvSet(MirInst inst, Set<String> candidateFields,
                                       List<BasicBlock> blocks, BitSet body) {
        String extra = inst.extraAs();
        if (!extra.contains("|set|") && !extra.contains("|defineVar|")) return false;
        // 对于 NovaScriptContext.set, 第一个操作数是字段名字符串寄存器
        // 我们无法静态确定字符串值，但可以通过匹配 pattern 判断
        // 保守策略：只有当操作数[0]的 CONST_STRING 值在候选字段中时才移除
        int[] ops = inst.getOperands();
        if (ops == null || ops.length < 2) return false;
        int nameReg = ops[0];
        // 在循环体中查找 CONST_STRING %nameReg = "fieldName"
        for (int idx = body.nextSetBit(0); idx >= 0; idx = body.nextSetBit(idx + 1)) {
            for (MirInst mi : blocks.get(idx).getInstructions()) {
                if (mi.getOp() == MirOp.CONST_STRING && mi.getDest() == nameReg) {
                    String strVal = mi.extraAs();
                    return candidateFields.contains(strVal);
                }
            }
        }
        // 也检查循环体外的块（CONST_STRING 可能在 preheader 中）
        for (int idx = 0; idx < blocks.size(); idx++) {
            if (body.get(idx)) continue;
            for (MirInst mi : blocks.get(idx).getInstructions()) {
                if (mi.getOp() == MirOp.CONST_STRING && mi.getDest() == nameReg) {
                    String strVal = mi.extraAs();
                    return candidateFields.contains(strVal);
                }
            }
        }
        return false;
    }

    /**
     * 向前扫描查找持久寄存器：MOVE %persist = %valueReg。
     * 编译器总是生成 MOVE + SET_STATIC 的模式。
     */
    private int findPersistentRegister(BasicBlock block, int setStaticIdx, int valueReg) {
        List<MirInst> insts = block.getInstructions();
        // 从 SET_STATIC 位置向前扫描
        for (int i = setStaticIdx - 1; i >= 0; i--) {
            MirInst inst = insts.get(i);
            if (inst.getOp() == MirOp.MOVE) {
                int[] ops = inst.getOperands();
                if (ops != null && ops.length > 0 && ops[0] == valueReg) {
                    return inst.getDest();
                }
            }
            // 如果遇到其他写入 valueReg 的指令，停止
            if (inst.getDest() == valueReg) break;
        }
        return -1;
    }

    // ==================== 循环分析基础设施 ====================
    // （与 LoopInvariantCodeMotion 相同的实现）

    private int[][] buildPredecessors(List<BasicBlock> blocks, int n, int maxId, int[] idToIdx) {
        int[] succBuf = new int[16];
        int[] predCount = new int[n];
        for (int i = 0; i < n; i++) {
            int count = getSuccessorIds(blocks.get(i).getTerminator(), succBuf);
            for (int s = 0; s < count; s++) {
                int succId = succBuf[s];
                int succIdx = (succId >= 0 && succId <= maxId) ? idToIdx[succId] : -1;
                if (succIdx >= 0) predCount[succIdx]++;
            }
        }
        int[][] preds = new int[n][];
        for (int i = 0; i < n; i++) preds[i] = new int[predCount[i]];
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
        return preds;
    }

    private BitSet[] buildDominators(int n, int[][] preds) {
        BitSet[] dom = new BitSet[n];
        for (int i = 0; i < n; i++) {
            dom[i] = new BitSet(n);
            if (i == 0) {
                dom[i].set(0);
            } else {
                dom[i].set(0, n);
            }
        }
        BitSet temp = new BitSet(n);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < n; i++) {
                int[] ps = preds[i];
                if (ps.length == 0) continue;
                temp.set(0, n);
                for (int p : ps) temp.and(dom[p]);
                temp.set(i);
                if (!temp.equals(dom[i])) {
                    dom[i] = (BitSet) temp.clone();
                    changed = true;
                }
            }
        }
        return dom;
    }

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

    private int getSuccessorIds(MirTerminator term, int[] buf) {
        if (term == null) return 0;
        if (term instanceof MirTerminator.Goto) {
            buf[0] = ((MirTerminator.Goto) term).getTargetBlockId();
            return 1;
        }
        if (term instanceof MirTerminator.Branch) {
            MirTerminator.Branch br = (MirTerminator.Branch) term;
            buf[0] = br.getThenBlock();
            buf[1] = br.getElseBlock();
            return buf[0] == buf[1] ? 1 : 2;
        }
        if (term instanceof MirTerminator.Switch) {
            MirTerminator.Switch sw = (MirTerminator.Switch) term;
            int count = 0;
            for (int target : sw.getCases().values()) {
                boolean dup = false;
                for (int j = 0; j < count; j++) {
                    if (buf[j] == target) { dup = true; break; }
                }
                if (!dup && count < buf.length) buf[count++] = target;
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

    // ==================== 数据类 ====================

    private static class NaturalLoop {
        final int headerIdx;
        final BitSet body;

        NaturalLoop(int headerIdx, BitSet body) {
            this.headerIdx = headerIdx;
            this.body = body;
        }
    }

    private static class StoreInfo {
        final int blockIdx;
        final int instIdx;
        final int valueReg;
        final String extra;

        StoreInfo(int blockIdx, int instIdx, int valueReg, String extra) {
            this.blockIdx = blockIdx;
            this.instIdx = instIdx;
            this.valueReg = valueReg;
            this.extra = extra;
        }
    }

    private static class SinkCandidate {
        final String fieldName;
        final int persistReg;
        final String extra;

        SinkCandidate(String fieldName, int persistReg, String extra) {
            this.fieldName = fieldName;
            this.persistReg = persistReg;
            this.extra = extra;
        }
    }
}
