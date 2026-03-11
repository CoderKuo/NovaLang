package com.novalang.ir.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StringAccumLoopPlan {

    public enum ReturnKind {
        STRING,
        LENGTH
    }

    public enum AppendKind {
        STRING_LITERAL,
        INT_LOCAL,
        INT_CONST,
        VALUE_LOCAL
    }

    public static final class AppendPart {
        public final AppendKind kind;
        public final int localIndex;
        public final int intValue;
        public final String stringValue;

        private AppendPart(AppendKind kind, int localIndex, int intValue, String stringValue) {
            this.kind = kind;
            this.localIndex = localIndex;
            this.intValue = intValue;
            this.stringValue = stringValue;
        }

        public static AppendPart stringLiteral(String value) {
            return new AppendPart(AppendKind.STRING_LITERAL, -1, 0, value);
        }

        public static AppendPart intLocal(int localIndex) {
            return new AppendPart(AppendKind.INT_LOCAL, localIndex, 0, null);
        }

        public static AppendPart intConst(int value) {
            return new AppendPart(AppendKind.INT_CONST, -1, value, null);
        }

        public static AppendPart valueLocal(int localIndex) {
            return new AppendPart(AppendKind.VALUE_LOCAL, localIndex, 0, null);
        }
    }

    public final int headerBlockId;
    public final int bodyBlockId;
    public final int exitBlockId;
    public final int stringLocal;
    public final int counterLocal;
    public final int limitLocal;
    public final int stepValue;
    public final BinaryOp compareOp;
    public final boolean loopOnTrue;
    public final ReturnKind returnKind;
    public final AppendPart[] appendParts;

    private StringAccumLoopPlan(int headerBlockId, int bodyBlockId, int exitBlockId,
                                int stringLocal, int counterLocal, int limitLocal,
                                int stepValue, BinaryOp compareOp, boolean loopOnTrue,
                                ReturnKind returnKind, AppendPart[] appendParts) {
        this.headerBlockId = headerBlockId;
        this.bodyBlockId = bodyBlockId;
        this.exitBlockId = exitBlockId;
        this.stringLocal = stringLocal;
        this.counterLocal = counterLocal;
        this.limitLocal = limitLocal;
        this.stepValue = stepValue;
        this.compareOp = compareOp;
        this.loopOnTrue = loopOnTrue;
        this.returnKind = returnKind;
        this.appendParts = appendParts;
    }

    public static StringAccumLoopPlan detect(MirFunction func) {
        if (func == null || !func.getTryCatchEntries().isEmpty()) {
            return null;
        }
        BasicBlock[] blockArr = func.getBlockArr();
        Map<Integer, String> constStrings = new HashMap<Integer, String>();
        Map<Integer, Integer> constInts = new HashMap<Integer, Integer>();
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstArray()) {
                if (inst.getOp() == MirOp.CONST_STRING && inst.getExtra() instanceof String) {
                    constStrings.put(Integer.valueOf(inst.getDest()), (String) inst.getExtra());
                } else if (inst.getOp() == MirOp.CONST_INT) {
                    constInts.put(Integer.valueOf(inst.getDest()), Integer.valueOf(inst.extraInt));
                }
            }
        }

        for (BasicBlock block : func.getBlocks()) {
            MirTerminator term = block.getTerminator();
            if (!(term instanceof MirTerminator.Branch)) {
                continue;
            }
            MirTerminator.Branch branch = (MirTerminator.Branch) term;
            BinaryOp compareOp = branch.getFusedCmpOp();
            if (compareOp == null) {
                continue;
            }
            StringAccumLoopPlan plan = detectOrientation(func, blockArr, constStrings, constInts,
                    block.getId(), branch.getThenBlock(), branch.getElseBlock(), compareOp,
                    branch.getFusedLeft(), branch.getFusedRight(), true);
            if (plan != null) {
                return plan;
            }
            plan = detectOrientation(func, blockArr, constStrings, constInts,
                    block.getId(), branch.getElseBlock(), branch.getThenBlock(), compareOp,
                    branch.getFusedLeft(), branch.getFusedRight(), false);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static StringAccumLoopPlan detectOrientation(MirFunction func,
                                                         BasicBlock[] blockArr,
                                                         Map<Integer, String> constStrings,
                                                         Map<Integer, Integer> constInts,
                                                         int headerBlockId,
                                                         int bodyBlockId,
                                                         int exitBlockId,
                                                         BinaryOp compareOp,
                                                         int fusedLeft,
                                                         int fusedRight,
                                                         boolean loopOnTrue) {
        if (bodyBlockId < 0 || bodyBlockId >= blockArr.length
                || exitBlockId < 0 || exitBlockId >= blockArr.length) {
            return null;
        }
        BasicBlock bodyBlock = blockArr[bodyBlockId];
        BasicBlock exitBlock = blockArr[exitBlockId];
        if (bodyBlock == null || exitBlock == null) {
            return null;
        }
        MirTerminator bodyTerm = bodyBlock.getTerminator();
        if (!(bodyTerm instanceof MirTerminator.Goto)
                || ((MirTerminator.Goto) bodyTerm).getTargetBlockId() != headerBlockId) {
            return null;
        }

        ReturnInfo returnInfo = detectReturnInfo(exitBlock);
        if (returnInfo == null || !isStringLikeLocal(func, returnInfo.stringLocal)) {
            return null;
        }

        int counterLocal = detectCounterLocal(func, bodyBlock.getInstArray(), constInts, fusedLeft, fusedRight);
        if (counterLocal < 0) {
            return null;
        }
        BinaryOp normalizedCompare = compareOp;
        int limitLocal;
        if (fusedLeft == counterLocal && isIntLikeLocal(func, fusedRight)) {
            limitLocal = fusedRight;
        } else if (fusedRight == counterLocal && isIntLikeLocal(func, fusedLeft)) {
            limitLocal = fusedLeft;
            normalizedCompare = reverseCompare(compareOp);
        } else {
            return null;
        }

        MirInst counterUpdate = null;
        Map<Integer, MirInst> defs = new HashMap<Integer, MirInst>();
        for (MirInst inst : bodyBlock.getInstArray()) {
            if (inst.getDest() == counterLocal && isAddOrSub(inst)) {
                if (counterUpdate != null) {
                    return null;
                }
                counterUpdate = inst;
                continue;
            }
            if (inst.getDest() >= 0) {
                defs.put(Integer.valueOf(inst.getDest()), inst);
            }
        }
        if (counterUpdate == null) {
            return null;
        }
        Integer stepValue = extractStepValue(counterUpdate, constInts, counterLocal);
        if (stepValue == null) {
            return null;
        }

        List<AppendPart> parts = new ArrayList<AppendPart>();
        Set<Integer> concatDefs = new HashSet<Integer>();
        if (!collectAppendParts(func, returnInfo.stringLocal, returnInfo.stringLocal, false, defs,
                constStrings, constInts, parts, concatDefs)) {
            return null;
        }
        if (parts.isEmpty()) {
            return null;
        }

        for (MirInst inst : bodyBlock.getInstArray()) {
            if (inst == counterUpdate) {
                continue;
            }
            if (!concatDefs.contains(Integer.valueOf(inst.getDest()))) {
                return null;
            }
        }

        return new StringAccumLoopPlan(headerBlockId, bodyBlockId, exitBlockId,
                returnInfo.stringLocal, counterLocal, limitLocal, stepValue.intValue(),
                normalizedCompare, loopOnTrue, returnInfo.kind,
                parts.toArray(new AppendPart[0]));
    }

    private static boolean collectAppendParts(MirFunction func, int local, int stringBaseLocal,
                                              boolean allowBaseReference,
                                              Map<Integer, MirInst> defs,
                                              Map<Integer, String> constStrings,
                                              Map<Integer, Integer> constInts,
                                              List<AppendPart> out,
                                              Set<Integer> concatDefs) {
        if (allowBaseReference && local == stringBaseLocal) {
            return true;
        }
        MirInst inst = defs.get(Integer.valueOf(local));
        if (inst == null) {
            return local == stringBaseLocal;
        }
        if (!isAdd(inst)) {
            return false;
        }
        int left = inst.operand(0);
        int right = inst.operand(1);
        AppendPart rightPart = detectAppendPart(func, right, defs, constStrings, constInts);
        if (rightPart != null && collectAppendParts(func, left, stringBaseLocal, true, defs, constStrings, constInts, out, concatDefs)) {
            concatDefs.add(Integer.valueOf(inst.getDest()));
            out.add(rightPart);
            return true;
        }
        AppendPart leftPart = detectAppendPart(func, left, defs, constStrings, constInts);
        if (leftPart != null && collectAppendParts(func, right, stringBaseLocal, true, defs, constStrings, constInts, out, concatDefs)) {
            concatDefs.add(Integer.valueOf(inst.getDest()));
            out.add(0, leftPart);
            return true;
        }
        return false;
    }

    private static AppendPart detectAppendPart(MirFunction func, int operand,
                                               Map<Integer, MirInst> defs,
                                               Map<Integer, String> constStrings,
                                               Map<Integer, Integer> constInts) {
        if (defs.containsKey(Integer.valueOf(operand))) {
            return null;
        }
        String stringConst = constStrings.get(Integer.valueOf(operand));
        if (stringConst != null) {
            return AppendPart.stringLiteral(stringConst);
        }
        if (isIntLikeLocal(func, operand)) {
            return AppendPart.intLocal(operand);
        }
        Integer intConst = constInts.get(Integer.valueOf(operand));
        if (intConst != null) {
            return AppendPart.intConst(intConst.intValue());
        }
        if (isStringLikeLocal(func, operand) || isObjectLikeLocal(func, operand)) {
            return AppendPart.valueLocal(operand);
        }
        return null;
    }

    private static int detectCounterLocal(MirFunction func, MirInst[] bodyInsts,
                                          Map<Integer, Integer> constInts,
                                          int fusedLeft, int fusedRight) {
        int candidateA = isIntLikeLocal(func, fusedLeft) ? fusedLeft : -1;
        int candidateB = isIntLikeLocal(func, fusedRight) ? fusedRight : -1;
        for (MirInst inst : bodyInsts) {
            if (!isAddOrSub(inst) || inst.getDest() < 0) {
                continue;
            }
            if (inst.getDest() != candidateA && inst.getDest() != candidateB) {
                continue;
            }
            if (extractStepValue(inst, constInts, inst.getDest()) != null) {
                return inst.getDest();
            }
        }
        return -1;
    }

    private static ReturnInfo detectReturnInfo(BasicBlock exitBlock) {
        MirInst[] exitInsts = exitBlock.getInstArray();
        MirTerminator term = exitBlock.getTerminator();
        if (!(term instanceof MirTerminator.Return)) {
            return null;
        }
        int returnLocal = ((MirTerminator.Return) term).getValueLocal();
        if (exitInsts.length == 0) {
            return new ReturnInfo(returnLocal, ReturnKind.STRING);
        }
        if (exitInsts.length == 1 && exitInsts[0].getOp() == MirOp.MOVE
                && exitInsts[0].getDest() == returnLocal) {
            return new ReturnInfo(exitInsts[0].operand(0), ReturnKind.STRING);
        }
        if (exitInsts.length == 1 && exitInsts[0].getOp() == MirOp.INVOKE_STATIC
                && exitInsts[0].getDest() == returnLocal
                && exitInsts[0].getOperands() != null
                && exitInsts[0].getOperands().length == 1
                && containsStringLength(exitInsts[0].getExtra())) {
            return new ReturnInfo(exitInsts[0].operand(0), ReturnKind.LENGTH);
        }
        return null;
    }

    private static boolean containsStringLength(Object extra) {
        return extra instanceof String && ((String) extra).contains("StringExtensions|length|");
    }

    private static Integer extractStepValue(MirInst inst, Map<Integer, Integer> constInts, int counterLocal) {
        int left = inst.operand(0);
        int right = inst.operand(1);
        BinaryOp op = inst.extraAs();
        Integer constRight = constInts.get(Integer.valueOf(right));
        if (left == counterLocal && constRight != null) {
            return Integer.valueOf(op == BinaryOp.ADD ? constRight.intValue() : -constRight.intValue());
        }
        Integer constLeft = constInts.get(Integer.valueOf(left));
        if (right == counterLocal && constLeft != null && op == BinaryOp.ADD) {
            return constLeft;
        }
        return null;
    }

    private static boolean isAdd(MirInst inst) {
        return inst != null && inst.getOp() == MirOp.BINARY && inst.extraAs() == BinaryOp.ADD;
    }

    private static boolean isAddOrSub(MirInst inst) {
        if (inst == null || inst.getOp() != MirOp.BINARY) {
            return false;
        }
        BinaryOp op = inst.extraAs();
        return op == BinaryOp.ADD || op == BinaryOp.SUB;
    }

    private static boolean isStringLikeLocal(MirFunction func, int local) {
        if (local < 0 || local >= func.getLocals().size()) {
            return false;
        }
        MirType type = func.getLocals().get(local).getType();
        return type.getKind() == MirType.Kind.OBJECT && "java/lang/String".equals(type.getClassName());
    }

    private static boolean isObjectLikeLocal(MirFunction func, int local) {
        if (local < 0 || local >= func.getLocals().size()) {
            return false;
        }
        return func.getLocals().get(local).getType().getKind() == MirType.Kind.OBJECT;
    }

    private static boolean isIntLikeLocal(MirFunction func, int local) {
        if (local < 0 || local >= func.getLocals().size()) {
            return false;
        }
        return func.getLocals().get(local).getType().getKind() == MirType.Kind.INT;
    }

    private static BinaryOp reverseCompare(BinaryOp op) {
        switch (op) {
            case LT:
                return BinaryOp.GT;
            case GT:
                return BinaryOp.LT;
            case LE:
                return BinaryOp.GE;
            case GE:
                return BinaryOp.LE;
            default:
                return op;
        }
    }

    private static final class ReturnInfo {
        final int stringLocal;
        final ReturnKind kind;

        ReturnInfo(int stringLocal, ReturnKind kind) {
            this.stringLocal = stringLocal;
            this.kind = kind;
        }
    }
}
