package com.novalang.ir.backend;

import com.novalang.compiler.ast.Modifier;
import com.novalang.ir.hir.ClassKind;
import com.novalang.ir.mir.*;
import org.objectweb.asm.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * MIR → JVM 字节码生成器。
 * <p>
 * 采用全 Object 装箱策略：所有值（包括 int, boolean 等原始类型）
 * 均装箱为 Object 引用存储，确保 ALOAD/ASTORE 类型一致性。
 */
public class MirCodeGenerator {

    private final Map<String, byte[]> generatedClasses = new HashMap<>();
    /** 全局字段描述符: className → (fieldName → JVM descriptor) */
    private final Map<String, Map<String, String>> allFieldDescs = new HashMap<>();
    /** 全局构造器描述符: "className#argCount" → JVM descriptor */
    private final Map<String, String> allCtorDescs = new HashMap<>();
    /** 类继承关系: className → superClassName */
    private final Map<String, String> classSuperClass = new HashMap<>();
    /** 当前类的字段描述符（指向 allFieldDescs 中的条目） */
    private Map<String, String> currentFieldDescs = new HashMap<>();
    /** 当前方法中使用 ILOAD/ISTORE 的 int 局部变量集合 */
    private Set<Integer> intLocals = new HashSet<>();
    private final Set<Integer> stringLocals = new HashSet<>();
    /** 当前方法的 JVM 描述符（用于判断返回值类型） */
    private String currentMethodDesc;

    /**
     * 从 MIR 模块生成字节码。
     * @return className → bytecode 映射
     */
    public Map<String, byte[]> generate(MirModule module) {
        String packagePrefix = module.getPackageName().isEmpty()
                ? "" : module.getPackageName().replace('.', '/') + "/";

        // 预扫描：收集所有类的字段描述符
        // 静态字段保持装箱描述符；实例字段原始类型用 I/J/D/F/Z，引用类型统一为 Ljava/lang/Object;
        // （与 buildMethodDescriptor 保持一致——参数和返回值都用 Object，避免 PUTFIELD 时类型不匹配）
        for (MirClass cls : module.getClasses()) {
            String className = packagePrefix + cls.getName();
            Map<String, String> descs = new HashMap<>();
            for (MirField field : cls.getFields()) {
                descs.put(field.getName(), field.getType().getFieldDescriptor());
            }
            allFieldDescs.put(className, descs);
            if (cls.getSuperClass() != null) {
                classSuperClass.put(className, cls.getSuperClass());
            }
        }

        // 预扫描：收集所有类的构造器描述符（必须与 generateMethod 使用相同逻辑）
        for (MirClass cls : module.getClasses()) {
            String className = packagePrefix + cls.getName();
            for (MirFunction method : cls.getMethods()) {
                if ("<init>".equals(method.getName())) {
                    String desc = method.getOverrideDescriptor() != null
                            ? method.getOverrideDescriptor() : buildMethodDescriptor(method);
                    allCtorDescs.put(className + "#" + method.getParams().size(), desc);
                }
            }
        }

        for (MirClass cls : module.getClasses()) {
            generateClass(cls, packagePrefix);
        }

        // 顶层函数生成到一个 $Module 类中
        if (!module.getTopLevelFunctions().isEmpty()) {
            generateModuleClass(module, packagePrefix);
        }

        return generatedClasses;
    }

    private void generateClass(MirClass cls, String packagePrefix) {
        // annotation class → 特殊处理：生成 JVM @interface
        if (cls.getKind() == ClassKind.ANNOTATION) {
            generateAnnotationClass(cls, packagePrefix);
            return;
        }

        // object → 特殊处理：生成单例类
        if (cls.getKind() == ClassKind.OBJECT) {
            generateObjectClass(cls, packagePrefix);
            return;
        }

        ClassWriter cw = new NovaClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        String className = packagePrefix + cls.getName();
        int access = ACC_PUBLIC;
        if (cls.getKind() == ClassKind.INTERFACE) access |= ACC_INTERFACE | ACC_ABSTRACT;
        if (cls.getKind() == ClassKind.ENUM) access |= ACC_ENUM;
        if (cls.getModifiers().contains(Modifier.ABSTRACT)) access |= ACC_ABSTRACT;
        if (cls.getModifiers().contains(Modifier.FINAL)) access |= ACC_FINAL;

        String superName = cls.getSuperClass() != null ? cls.getSuperClass() : "java/lang/Object";
        String[] interfaces = cls.getInterfaces().toArray(new String[0]);

        cw.visit(V1_8, access, className, null, superName, interfaces);

        // 自定义注解标注
        addClassAnnotations(cw, cls, packagePrefix);

        // 从预扫描中获取字段描述符映射
        currentFieldDescs = allFieldDescs.getOrDefault(className, new HashMap<>());

        // 字段（静态字段保持装箱描述符，实例字段原始类型用 I/J 等，引用类型统一 Object）
        for (MirField field : cls.getFields()) {
            int fieldAccess = ACC_PUBLIC;
            if (field.getModifiers().contains(Modifier.PRIVATE)) fieldAccess = ACC_PRIVATE;
            if (field.getModifiers().contains(Modifier.PROTECTED)) fieldAccess = ACC_PROTECTED;
            if (field.getModifiers().contains(Modifier.STATIC)) fieldAccess |= ACC_STATIC;
            if (field.getModifiers().contains(Modifier.FINAL)) fieldAccess |= ACC_FINAL;
            String desc = field.getType().getFieldDescriptor();
            cw.visitField(fieldAccess, field.getName(), desc, null, null);
        }

        // 默认构造器（如果类没有自己的构造器）
        boolean hasInit = false;
        for (MirFunction method : cls.getMethods()) {
            if ("<init>".equals(method.getName())) hasInit = true;
        }
        if (!hasInit && cls.getKind() != ClassKind.INTERFACE) {
            if (cls.getSuperCtorDesc() != null) {
                generateAnonymousConstructor(cw, superName, cls.getSuperCtorDesc());
            } else {
                generateDefaultConstructor(cw, superName);
            }
        }

        // 方法
        for (MirFunction method : cls.getMethods()) {
            boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
            generateMethod(cw, method, className, isStatic, superName, cls.getKind());
        }

        // @data 合成方法
        if (cls.hasAnnotation("data")) {
            generateDataMethods(cw, cls, className);
        }

        // @builder 合成方法 + Builder 内部类
        if (cls.hasAnnotation("builder")) {
            generateBuilderFactory(cw, cls, className, packagePrefix);
            generateBuilderInnerClass(cls, className, packagePrefix);
        }

        // 枚举: 生成 values() 和 toString() 方法
        if (cls.getKind() == ClassKind.ENUM) {
            generateEnumValuesMethod(cw, cls, className);
            generateEnumToString(cw, cls, className);
        }

        // 运行时注解处理器触发（<clinit>）
        generateAnnotationTriggerClinit(cw, cls, className);

        cw.visitEnd();
        generatedClasses.put(className, cw.toByteArray());
    }

    private void generateModuleClass(MirModule module, String packagePrefix) {
        ClassWriter cw = new NovaClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String className = packagePrefix + "$Module";
        cw.visit(V1_8, ACC_PUBLIC, className, null, "java/lang/Object", null);

        // 默认构造器
        generateDefaultConstructor(cw, "java/lang/Object");

        for (MirFunction func : module.getTopLevelFunctions()) {
            generateMethod(cw, func, className, true, "java/lang/Object", ClassKind.CLASS);
        }

        cw.visitEnd();
        generatedClasses.put(className, cw.toByteArray());
    }

    private void generateDefaultConstructor(ClassWriter cw, String superName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /**
     * 匿名类构造器：接受 Object 参数，CHECKCAST 后转发给超类构造器。
     */
    private void generateAnonymousConstructor(ClassWriter cw, String superName,
                                              String superCtorDesc) {
        Type[] superParamTypes = Type.getArgumentTypes(superCtorDesc);
        // 匿名类构造器描述符：所有参数为 Object
        String anonDesc = MethodDescriptor.allObjectVoidDesc(superParamTypes.length);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", anonDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0); // this
        for (int i = 0; i < superParamTypes.length; i++) {
            mv.visitVarInsn(ALOAD, i + 1);
            String internalName = superParamTypes[i].getInternalName();
            if (!"java/lang/Object".equals(internalName)) {
                mv.visitTypeInsn(CHECKCAST, internalName);
            }
        }
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", superCtorDesc, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateMethod(ClassWriter cw, MirFunction func, String ownerClass,
                                boolean isStatic, String superClass, ClassKind classKind) {
        int access = ACC_PUBLIC;
        if (isStatic) access |= ACC_STATIC;
        if (func.getName().equals("<init>")) {
            access = ACC_PUBLIC; // 构造器不能是 static
        } else if (func.getName().equals("<clinit>")) {
            access = ACC_STATIC; // 类初始化器只需 static
        }

        String desc = func.getOverrideDescriptor() != null
                ? func.getOverrideDescriptor() : buildMethodDescriptor(func);
        this.currentMethodDesc = desc;

        // 接口抽象方法：无实际方法体（仅含单条 RETURN_VOID 指令的方法视为抽象）
        if (classKind == ClassKind.INTERFACE && !isStatic
                && !"<init>".equals(func.getName()) && !"<clinit>".equals(func.getName())
                && isAbstractBody(func)) {
            MethodVisitor mv = cw.visitMethod(access | ACC_ABSTRACT, func.getName(), desc, null, null);
            mv.visitEnd();
            return;
        }

        MethodVisitor mv = cw.visitMethod(access, func.getName(), desc, null, null);
        mv.visitCode();

        // 识别可使用 ILOAD/ISTORE 的 int 局部变量
        this.intLocals = identifyIntLocals(func, isStatic);
        this.stringLocals.clear();

        // INT 参数入口拆箱：identifyIntLocals 已验证参数的所有写入均为 int 安全操作
        // 主构造器（无委托）可安全拆箱，次级构造器（有委托）参数可能在 this() 前使用
        boolean canUnboxParams = !"<clinit>".equals(func.getName())
                && (!"<init>".equals(func.getName()) || !func.hasDelegation());
        {
            int slotOffset = isStatic ? 0 : 1;
            if (canUnboxParams) {
                boolean hasNativeDesc = func.getOverrideDescriptor() != null;
                for (int i = 0; i < func.getParams().size(); i++) {
                    int slot = slotOffset + i;
                    if (intLocals.contains(slot)) {
                        if (hasNativeDesc) {
                            // 原始类型描述符：参数已经是 int，无需拆箱
                        } else {
                            // Object 参数 → 拆箱为 JVM int，后续直接 ILOAD
                            mv.visitVarInsn(ALOAD, slot);
                            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number",
                                    "intValue", "()I", false);
                            mv.visitVarInsn(ISTORE, slot);
                        }
                    }
                }
            } else {
                // 参数未拆箱，仍为 Object，从 intLocals 中移除参数 slot
                for (int i = 0; i < func.getParams().size(); i++) {
                    intLocals.remove(slotOffset + i);
                }
            }
        }

        // 为每个基本块生成标签（提前创建，委托构造器可能需要）
        Map<Integer, Label> blockLabels = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            blockLabels.put(block.getId(), new Label());
        }

        // 构造器初始化
        if ("<init>".equals(func.getName()) && superClass != null) {
            if (func.hasDelegation()) {
                // 次级构造器: this.<init>(delegationArgs...)
                int[] delegLocals = func.getDelegationArgLocals();

                // 先初始化非参数的委托局部变量（如字面量常量）
                int paramCount = func.getParams().size() + 1; // +1 for this
                Set<Integer> synthLocals = new HashSet<>();
                for (int l : delegLocals) {
                    if (l >= paramCount) synthLocals.add(l);
                }
                if (!synthLocals.isEmpty() && !func.getBlocks().isEmpty()) {
                    BasicBlock firstBlock = func.getBlocks().get(0);
                    Iterator<MirInst> it = firstBlock.getInstructions().iterator();
                    while (it.hasNext()) {
                        MirInst inst = it.next();
                        if (synthLocals.contains(inst.getDest())) {
                            generateInstruction(mv, inst, blockLabels, func);
                            it.remove();
                        }
                    }
                }

                mv.visitVarInsn(ALOAD, 0); // this
                for (int local : delegLocals) {
                    loadObject(mv, local);
                }
                String delegDesc = MethodDescriptor.allObjectVoidDesc(delegLocals.length);
                mv.visitMethodInsn(INVOKESPECIAL, ownerClass, "<init>",
                        delegDesc, false);
            } else if (func.hasSuperInitArgs()) {
                // 主构造器: super.<init>(args...)
                int[] superArgs = func.getSuperInitArgLocals();
                // 先初始化非参数的超类构造器局部变量（如字面量常量）
                int paramCount = func.getParams().size() + 1; // +1 for this
                Set<Integer> synthSuperLocals = new HashSet<>();
                for (int l : superArgs) {
                    if (l >= paramCount) synthSuperLocals.add(l);
                }
                if (!synthSuperLocals.isEmpty() && !func.getBlocks().isEmpty()) {
                    BasicBlock firstBlock = func.getBlocks().get(0);
                    Iterator<MirInst> it = firstBlock.getInstructions().iterator();
                    while (it.hasNext()) {
                        MirInst inst = it.next();
                        if (synthSuperLocals.contains(inst.getDest())) {
                            generateInstruction(mv, inst, blockLabels, func);
                            it.remove();
                        }
                    }
                }
                mv.visitVarInsn(ALOAD, 0);
                for (int local : superArgs) {
                    loadObject(mv, local);
                }
                String superDesc = MethodDescriptor.allObjectVoidDesc(superArgs.length);
                mv.visitMethodInsn(INVOKESPECIAL, superClass, "<init>", superDesc, false);
            } else {
                // 主构造器: super.<init>()
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, superClass, "<init>", "()V", false);
            }
        }

        // 异常表: visitTryCatchBlock 必须在代码生成前调用
        Map<Integer, Integer> catchHandlerLocals = new HashMap<>();
        for (MirFunction.TryCatchEntry entry : func.getTryCatchEntries()) {
            Label startLabel = blockLabels.get(entry.tryStartBlock);
            Label endLabel = blockLabels.get(entry.tryEndBlock);
            Label handlerLabel = blockLabels.get(entry.handlerBlock);
            // 安全检查：引用的块可能被优化 Pass 删除
            if (startLabel == null || endLabel == null || handlerLabel == null) continue;
            mv.visitTryCatchBlock(startLabel, endLabel, handlerLabel, entry.exceptionType);
            catchHandlerLocals.put(entry.handlerBlock, entry.exceptionLocal);
        }

        // 预初始化所有非参数局部变量，确保 JVM 验证器在所有控制流路径上都能看到已初始化的 local
        {
            int paramCount = func.getParams().size() + (isStatic ? 0 : 1);
            for (MirLocal local : func.getLocals()) {
                int idx = local.getIndex();
                if (idx < paramCount) continue; // 跳过参数
                if (intLocals.contains(idx)) {
                    mv.visitInsn(ICONST_0);
                    mv.visitVarInsn(ISTORE, idx);
                } else {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ASTORE, idx);
                }
            }
        }

        // 生成每个基本块（复用集合实例，避免每个 block 重复分配）
        Map<Integer, Integer> fusedConstants = new HashMap<>();
        Set<MirInst> fusedConstInsts = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            mv.visitLabel(blockLabels.get(block.getId()));

            // catch handler: 栈顶是异常对象，ASTORE 到指定 local
            if (catchHandlerLocals.containsKey(block.getId())) {
                mv.visitVarInsn(ASTORE, catchHandlerLocals.get(block.getId()));
            }

            // 检测分支-比较融合：最后一条指令是比较，终结器是 Branch 使用该比较结果
            List<MirInst> instructions = block.getInstructions();
            MirInst fusedCmp = null;
            fusedConstants.clear();
            fusedConstInsts.clear();

            if (block.getTerminator() instanceof MirTerminator.Branch && !instructions.isEmpty()) {
                MirTerminator.Branch branch = (MirTerminator.Branch) block.getTerminator();
                MirInst lastInst = instructions.get(instructions.size() - 1);
                if (lastInst.getOp() == MirOp.BINARY && isComparisonOp(lastInst.extraAs())
                        && lastInst.getDest() == branch.getCondition()) {
                    fusedCmp = lastInst;
                    // 检测比较操作数的 CONST_INT 是否可内联
                    int cmpLeft = fusedCmp.operand(0);
                    int cmpRight = fusedCmp.operand(1);
                    for (MirInst prev : instructions) {
                        if (prev.getOp() == MirOp.CONST_INT
                                && (prev.getDest() == cmpLeft || prev.getDest() == cmpRight)) {
                            int constDest = prev.getDest();
                            if (isLocalOnlyUsedInFusedCmp(instructions, fusedCmp, constDest)
                                    && !isLocalUsedInOtherBlocks(func, block, constDest)) {
                                fusedConstants.put(constDest, (Integer) prev.getExtra());
                                fusedConstInsts.add(prev);
                            }
                        }
                    }
                }
            }

            for (MirInst inst : instructions) {
                if (inst == fusedCmp || fusedConstInsts.contains(inst)) continue;
                generateInstruction(mv, inst, blockLabels, func);
            }

            if (block.getTerminator() != null) {
                if (fusedCmp != null) {
                    generateFusedCompareBranch(mv, fusedCmp,
                            (MirTerminator.Branch) block.getTerminator(),
                            blockLabels, func, fusedConstants);
                } else if (block.getTerminator() instanceof MirTerminator.Branch
                        && ((MirTerminator.Branch) block.getTerminator()).getFusedCmpOp() != null) {
                    // 由 MirPeepholeOptimization 预融合的 Branch
                    MirTerminator.Branch br = (MirTerminator.Branch) block.getTerminator();
                    generateFusedCompareBranch(mv, br.getFusedCmpOp(),
                            br.getFusedLeft(), br.getFusedRight(),
                            br, blockLabels, func, Collections.emptyMap());
                } else {
                    generateTerminator(mv, block.getTerminator(), blockLabels, func);
                }
            }
        }

        mv.visitMaxs(0, 0); // COMPUTE_MAXS 自动计算
        mv.visitEnd();
    }

    // ========== 指令生成 ==========

    private void generateInstruction(MethodVisitor mv, MirInst inst,
                                     Map<Integer, Label> blockLabels, MirFunction func) {
        switch (inst.getOp()) {
            case CONST_INT: {
                int value = (Integer) inst.getExtra();
                pushInt(mv, value);
                if (intLocals.contains(inst.getDest())) {
                    mv.visitVarInsn(ISTORE, inst.getDest());
                } else {
                    boxInt(mv);
                    mv.visitVarInsn(ASTORE, inst.getDest());
                }
                break;
            }
            case CONST_LONG: {
                mv.visitLdcInsn((Long) inst.getExtra());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_FLOAT: {
                mv.visitLdcInsn((Float) inst.getExtra());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_DOUBLE: {
                mv.visitLdcInsn((Double) inst.getExtra());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_STRING: {
                mv.visitLdcInsn((String) inst.getExtra());
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_BOOL: {
                boolean value = (Boolean) inst.getExtra();
                mv.visitInsn(value ? ICONST_1 : ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_CHAR: {
                int value = (Integer) inst.getExtra();
                pushInt(mv, value);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf",
                        "(C)Ljava/lang/Character;", false);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_NULL: {
                mv.visitInsn(ACONST_NULL);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case CONST_CLASS: {
                String internalName = (String) inst.getExtra();
                mv.visitLdcInsn(Type.getObjectType(internalName));
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case MOVE: {
                int src = inst.operand(0);
                int dest = inst.getDest();
                if (intLocals.contains(dest)) {
                    loadInt(mv, src);
                    mv.visitVarInsn(ISTORE, dest);
                } else if (intLocals.contains(src)) {
                    mv.visitVarInsn(ILOAD, src);
                    boxInt(mv);
                    mv.visitVarInsn(ASTORE, dest);
                } else {
                    mv.visitVarInsn(ALOAD, src);
                    mv.visitVarInsn(ASTORE, dest);
                }
                break;
            }
            case BINARY: {
                BinaryOp op = inst.extraAs();
                int left = inst.operand(0);
                int right = inst.operand(1);
                generateBinaryOp(mv, op, left, right, inst.getDest(), func);
                break;
            }
            case UNARY: {
                UnaryOp op = inst.extraAs();
                int operand = inst.operand(0);
                generateUnaryOp(mv, op, operand, inst.getDest(), func);
                break;
            }
            case NEW_OBJECT: {
                String className = (String) inst.getExtra();
                int argCount = inst.getOperands() != null ? inst.getOperands().length : 0;
                // 查找预扫描的构造器描述符（Nova 类）
                String ctorDesc = allCtorDescs.get(className + "#" + argCount);
                if (ctorDesc != null) {
                    // Nova 类：直接 NEW + INVOKESPECIAL
                    mv.visitTypeInsn(NEW, className);
                    mv.visitInsn(DUP);
                    if (argCount > 0) {
                        loadAndUnboxParams(mv, inst.getOperands(), 0, ctorDesc);
                    }
                    mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", ctorDesc, false);
                } else if (argCount == 0) {
                    // 无参构造：直接 NEW + ()V
                    mv.visitTypeInsn(NEW, className);
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "()V", false);
                } else {
                    // Java 类有参构造：MethodHandleCache 运行时分派（处理重载/varargs）
                    mv.visitMethodInsn(INVOKESTATIC,
                            "nova/runtime/interpreter/MethodHandleCache", "getInstance",
                            "()Lnova/runtime/interpreter/MethodHandleCache;", false);
                    mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(className));
                    pushInt(mv, argCount);
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                    for (int i = 0; i < argCount; i++) {
                        mv.visitInsn(DUP);
                        pushInt(mv, i);
                        loadObject(mv, inst.getOperands()[i]);
                        mv.visitInsn(AASTORE);
                    }
                    mv.visitMethodInsn(INVOKEVIRTUAL,
                            "nova/runtime/interpreter/MethodHandleCache", "newInstance",
                            "(Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                }
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case GET_FIELD: {
                String fieldName = (String) inst.getExtra();
                int obj = inst.operand(0);
                MirType objType = getLocalType(func, obj);
                String owner = objType.getKind() == MirType.Kind.OBJECT && objType.getClassName() != null
                        ? objType.getClassName() : "java/lang/Object";
                // 数组类型: size/length → ARRAYLENGTH
                if (owner.startsWith("[") && ("size".equals(fieldName) || "length".equals(fieldName))) {
                    loadObject(mv, obj);
                    mv.visitInsn(ARRAYLENGTH);
                    if (intLocals.contains(inst.getDest())) {
                        mv.visitVarInsn(ISTORE, inst.getDest());
                    } else {
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                                "(I)Ljava/lang/Integer;", false);
                        mv.visitVarInsn(ASTORE, inst.getDest());
                    }
                    break;
                }
                if (owner.startsWith("[")) {
                    owner = "java/lang/Object";
                }
                String fieldDesc = lookupFieldDesc(owner, fieldName);
                loadObject(mv, obj);
                if (!"java/lang/Object".equals(owner)) {
                    mv.visitTypeInsn(CHECKCAST, owner);
                }
                mv.visitFieldInsn(GETFIELD, owner, fieldName, fieldDesc);
                // 原始类型字段：GETFIELD 产生原始值
                if (isPrimitiveDesc(fieldDesc)) {
                    if ("I".equals(fieldDesc) && intLocals.contains(inst.getDest())) {
                        mv.visitVarInsn(ISTORE, inst.getDest());
                    } else {
                        boxFieldValue(mv, fieldDesc);
                        mv.visitVarInsn(ASTORE, inst.getDest());
                    }
                } else {
                    mv.visitVarInsn(ASTORE, inst.getDest());
                }
                break;
            }
            case SET_FIELD: {
                String fieldName = (String) inst.getExtra();
                int obj = inst.operand(0);
                int value = inst.operand(1);
                MirType objType = getLocalType(func, obj);
                String owner = objType.getKind() == MirType.Kind.OBJECT && objType.getClassName() != null
                        ? objType.getClassName() : "java/lang/Object";
                String fieldDesc = lookupFieldDesc(owner, fieldName);
                loadObject(mv, obj);
                if (!"java/lang/Object".equals(owner)) {
                    mv.visitTypeInsn(CHECKCAST, owner);
                }
                // 原始类型字段：加载原始值
                if (isPrimitiveDesc(fieldDesc)) {
                    switch (fieldDesc) {
                        case "I": loadInt(mv, value); break;
                        case "J": unboxLong(mv, value); break;
                        case "F": unboxFloat(mv, value); break;
                        case "D": unboxDouble(mv, value); break;
                        case "Z": unboxBoolean(mv, value); break;
                        default: loadObject(mv, value); break;
                    }
                } else {
                    loadObject(mv, value);
                    if (!MethodDescriptor.OBJECT_DESC.equals(fieldDesc)) {
                        String castType;
                        if (fieldDesc.startsWith("L") && fieldDesc.endsWith(";")) {
                            castType = fieldDesc.substring(1, fieldDesc.length() - 1);
                        } else {
                            // 数组类型如 "[Ljava/lang/String;" 直接使用完整描述符
                            castType = fieldDesc;
                        }
                        mv.visitTypeInsn(CHECKCAST, castType);
                    }
                }
                mv.visitFieldInsn(PUTFIELD, owner, fieldName, fieldDesc);
                break;
            }
            case GET_STATIC: {
                String extra = (String) inst.getExtra();
                if (extra.contains("|")) {
                    String[] parts = extra.split("\\|", 3);
                    mv.visitFieldInsn(GETSTATIC, parts[0], parts[1], parts[2]);
                } else {
                    mv.visitFieldInsn(GETSTATIC, "java/lang/Object", extra, MethodDescriptor.OBJECT_DESC);
                }
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case SET_STATIC: {
                String extra = (String) inst.getExtra();
                int value = inst.operand(0);
                loadObject(mv, value);
                if (extra.contains("|")) {
                    String[] parts = extra.split("\\|", 3);
                    mv.visitFieldInsn(PUTSTATIC, parts[0], parts[1], parts[2]);
                } else {
                    mv.visitFieldInsn(PUTSTATIC, "java/lang/Object", extra, MethodDescriptor.OBJECT_DESC);
                }
                break;
            }
            case INVOKE_VIRTUAL: {
                String extra = (String) inst.getExtra();
                String owner, methodName, descriptor;

                if (extra.contains("|")) {
                    // 新格式: "owner|methodName|descriptor"
                    String[] parts = extra.split("\\|", 3);
                    owner = parts[0];
                    methodName = parts[1];
                    descriptor = parts[2];
                } else {
                    // 旧格式: 只有方法名
                    methodName = extra;
                    owner = "java/lang/Object";
                    int argCount = inst.getOperands() != null ? inst.getOperands().length - 1 : 0;
                    descriptor = MethodDescriptor.allObjectDesc(argCount);
                }

                // 加载 receiver
                int receiver = inst.operand(0);
                loadObject(mv, receiver);
                // 确保 receiver 类型匹配 owner（全 Object 装箱策略下 JVM 可能只知道是 Object）
                if (!"java/lang/Object".equals(owner)) {
                    mv.visitTypeInsn(CHECKCAST, owner);
                }

                // 加载参数（根据描述符自动拆箱原始类型）
                loadAndUnboxParams(mv, inst.getOperands(), 1, descriptor);

                mv.visitMethodInsn(INVOKEVIRTUAL, owner, methodName, descriptor, false);

                // 存储结果（void 方法不存储）
                if (inst.getDest() >= 0 && !descriptor.endsWith(")V")) {
                    String retType = descriptor.substring(descriptor.indexOf(')') + 1);
                    if ("I".equals(retType) && intLocals.contains(inst.getDest())) {
                        mv.visitVarInsn(ISTORE, inst.getDest());
                    } else {
                        boxReturnIfPrimitive(mv, descriptor);
                        mv.visitVarInsn(ASTORE, inst.getDest());
                    }
                }
                break;
            }
            case INVOKE_STATIC: {
                String extra = (String) inst.getExtra();
                String owner, methodName, descriptor;

                if (extra.contains("|")) {
                    String[] parts = extra.split("\\|", 3);
                    owner = parts[0];
                    methodName = parts[1];
                    descriptor = parts[2];
                } else {
                    methodName = extra;
                    owner = "java/lang/Object";
                    int argCount = inst.getOperands() != null ? inst.getOperands().length : 0;
                    descriptor = MethodDescriptor.allObjectDesc(argCount);
                }

                // 加载参数（根据描述符自动拆箱原始类型）
                if (inst.getOperands() != null) {
                    loadAndUnboxParams(mv, inst.getOperands(), 0, descriptor);
                }

                mv.visitMethodInsn(INVOKESTATIC, owner, methodName, descriptor, false);

                if (inst.getDest() >= 0 && !descriptor.endsWith(")V")) {
                    String retType = descriptor.substring(descriptor.indexOf(')') + 1);
                    if ("I".equals(retType) && intLocals.contains(inst.getDest())) {
                        // int 返回值直接 ISTORE
                        mv.visitVarInsn(ISTORE, inst.getDest());
                    } else {
                        boxReturnIfPrimitive(mv, descriptor);
                        mv.visitVarInsn(ASTORE, inst.getDest());
                    }
                }
                break;
            }
            case INVOKE_INTERFACE: {
                String extra = (String) inst.getExtra();
                String owner, methodName, descriptor;

                if (extra.contains("|")) {
                    String[] parts = extra.split("\\|", 3);
                    owner = parts[0];
                    methodName = parts[1];
                    descriptor = parts[2];
                } else {
                    methodName = extra;
                    owner = "java/lang/Object";
                    int argCount = inst.getOperands() != null ? inst.getOperands().length - 1 : 0;
                    descriptor = MethodDescriptor.allObjectDesc(argCount);
                }

                // 加载 receiver
                {
                    int ifReceiver = inst.operand(0);
                    loadObject(mv, ifReceiver);
                    if (!"java/lang/Object".equals(owner)) {
                        mv.visitTypeInsn(CHECKCAST, owner);
                    }
                }

                // 加载参数（根据描述符自动拆箱原始类型）
                loadAndUnboxParams(mv, inst.getOperands(), 1, descriptor);

                mv.visitMethodInsn(INVOKEINTERFACE, owner, methodName, descriptor, true);

                if (inst.getDest() >= 0 && !descriptor.endsWith(")V")) {
                    String retType = descriptor.substring(descriptor.indexOf(')') + 1);
                    if ("I".equals(retType) && intLocals.contains(inst.getDest())) {
                        mv.visitVarInsn(ISTORE, inst.getDest());
                    } else {
                        boxReturnIfPrimitive(mv, descriptor);
                        mv.visitVarInsn(ASTORE, inst.getDest());
                    }
                }
                break;
            }
            case TYPE_CHECK: {
                String typeName = (String) inst.getExtra();
                int src = inst.operand(0);
                loadObject(mv, src);
                // Ok/Err/Result 不是 JVM 类，需要运行时值类型检查
                if ("Ok".equals(typeName)) {
                    mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaResult", "checkIsOk",
                            "(Ljava/lang/Object;)Z", false);
                } else if ("Err".equals(typeName)) {
                    mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaResult", "checkIsErr",
                            "(Ljava/lang/Object;)Z", false);
                } else if ("Result".equals(typeName)) {
                    mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaResult", "checkIsResult",
                            "(Ljava/lang/Object;)Z", false);
                } else {
                    mv.visitTypeInsn(INSTANCEOF, typeName);
                }
                // 装箱为 Boolean
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case TYPE_CAST: {
                String typeName = (String) inst.getExtra();
                int src = inst.operand(0);
                loadObject(mv, src);
                mv.visitTypeInsn(CHECKCAST, typeName);
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case NEW_ARRAY: {
                int size = inst.operand(0);
                MirType destArrayType = getLocalType(func, inst.getDest());
                loadInt(mv, size);
                if ("[I".equals(destArrayType.getClassName())) {
                    mv.visitIntInsn(NEWARRAY, T_INT);
                } else {
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                }
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case INDEX_GET: {
                int target = inst.operand(0);
                int index = inst.operand(1);
                MirType targetType = getLocalType(func, target);
                String targetOwner = targetType.getKind() == MirType.Kind.OBJECT
                        && targetType.getClassName() != null
                        ? targetType.getClassName() : "";
                if ("[I".equals(targetOwner)) {
                    // int array: IALOAD → int 结果
                    mv.visitVarInsn(ALOAD, target);
                    mv.visitTypeInsn(CHECKCAST, "[I");
                    loadInt(mv, index);
                    mv.visitInsn(IALOAD);
                    if (intLocals.contains(inst.getDest())) {
                        mv.visitVarInsn(ISTORE, inst.getDest());
                    } else {
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                                "(I)Ljava/lang/Integer;", false);
                        mv.visitVarInsn(ASTORE, inst.getDest());
                    }
                    break;
                }
                if (isMapType(targetOwner)) {
                    // Map.get(key)
                    loadObject(mv, target);
                    mv.visitTypeInsn(CHECKCAST, "java/util/Map");
                    loadObject(mv, index);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                            "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                } else if (isListType(targetOwner)) {
                    // List.get(index)
                    loadObject(mv, target);
                    mv.visitTypeInsn(CHECKCAST, "java/util/List");
                    loadInt(mv, index);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get",
                            "(I)Ljava/lang/Object;", true);
                } else if ("java/lang/String".equals(targetOwner)) {
                    // String.charAt(index) → String.valueOf(char)
                    loadObject(mv, target);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/String");
                    loadInt(mv, index);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt",
                            "(I)C", false);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                            "(C)Ljava/lang/String;", false);
                } else {
                    // 运行时分派: NovaCollections.getIndex(target, index)
                    // 使用 Object 索引版本，支持 Map 的非 int 键
                    loadObject(mv, target);
                    loadObject(mv, index);
                    mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaCollections", "getIndex",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                }
                mv.visitVarInsn(ASTORE, inst.getDest());
                break;
            }
            case INDEX_SET: {
                int target = inst.operand(0);
                int index = inst.operand(1);
                int value = inst.operand(2);
                MirType targetType = getLocalType(func, target);
                String targetOwner = targetType.getKind() == MirType.Kind.OBJECT
                        && targetType.getClassName() != null
                        ? targetType.getClassName() : "";
                if ("[I".equals(targetOwner)) {
                    // int array: IASTORE
                    mv.visitVarInsn(ALOAD, target);
                    mv.visitTypeInsn(CHECKCAST, "[I");
                    loadInt(mv, index);
                    loadInt(mv, value);
                    mv.visitInsn(IASTORE);
                    break;
                }
                if (isMapType(targetOwner)) {
                    // Map.put(key, value)
                    loadObject(mv, target);
                    mv.visitTypeInsn(CHECKCAST, "java/util/Map");
                    loadObject(mv, index);
                    loadObject(mv, value);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                    mv.visitInsn(POP); // 丢弃 put 返回值
                } else if (isListType(targetOwner)) {
                    // List.set(index, value)
                    loadObject(mv, target);
                    mv.visitTypeInsn(CHECKCAST, "java/util/List");
                    loadInt(mv, index);
                    loadObject(mv, value);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "set",
                            "(ILjava/lang/Object;)Ljava/lang/Object;", true);
                    mv.visitInsn(POP); // 丢弃 set 返回值
                } else {
                    // 运行时分派: NovaCollections.setIndex(target, index, value)
                    // 使用 Object 索引版本，支持 Map 的非 int 键
                    loadObject(mv, target);
                    loadObject(mv, index);
                    loadObject(mv, value);
                    mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaCollections", "setIndex",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                }
                break;
            }
            default:
                // 未处理的指令：推入 null 占位
                if (inst.getDest() >= 0) {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ASTORE, inst.getDest());
                }
                break;
        }
    }

    // ========== 二元运算 ==========

    private void generateBinaryOp(MethodVisitor mv, BinaryOp op,
                                   int left, int right, int dest, MirFunction func) {
        MirType destType = getLocalType(func, dest);

        // 字符串拼接：ADD 且 dest/任一操作数为 String 类型
        if (op == BinaryOp.ADD && isStringConcat(func, destType, left, right)) {
            loadObject(mv, left);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false);
            loadObject(mv, right);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, dest);
            // 标记 dest 为 String 类型，确保连续拼接 (a + b + c) 正确识别
            stringLocals.add(dest);
            return;
        }

        // 动态 ADD：操作数类型为 OBJECT（运行时可能是 String 或 Number），委托 NovaOps.add()
        if (op == BinaryOp.ADD && isUnknownObjectType(func, left, right)) {
            loadObject(mv, left);
            loadObject(mv, right);
            mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaOps", "add",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, dest);
            return;
        }

        // 比较运算 → 根据类型选择指令
        if (isComparisonOp(op)) {
            generateComparison(mv, op, left, right, dest, func);
            return;
        }

        // 逻辑运算 AND/OR → 拆箱 Boolean，运算，装箱
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            unboxBoolean(mv, left);
            unboxBoolean(mv, right);
            mv.visitInsn(op == BinaryOp.AND ? IAND : IOR);
            boxBoolean(mv);
            mv.visitVarInsn(ASTORE, dest);
            return;
        }

        // 算术运算：根据类型选择指令
        MirType.Kind kind = resolveNumericKind(func, left, right);
        switch (kind) {
            case DOUBLE:
                unboxDouble(mv, left);
                unboxDouble(mv, right);
                emitArithOp(mv, op, DADD, DSUB, DMUL, DDIV, DREM);
                boxDouble(mv);
                break;
            case FLOAT:
                unboxFloat(mv, left);
                unboxFloat(mv, right);
                emitArithOp(mv, op, FADD, FSUB, FMUL, FDIV, FREM);
                boxFloat(mv);
                break;
            case LONG:
                unboxLong(mv, left);
                unboxLong(mv, right);
                switch (op) {
                    case ADD: mv.visitInsn(LADD); break;
                    case SUB: mv.visitInsn(LSUB); break;
                    case MUL: mv.visitInsn(LMUL); break;
                    case DIV: mv.visitInsn(LDIV); break;
                    case MOD: mv.visitInsn(LREM); break;
                    case BAND: mv.visitInsn(LAND); break;
                    case BOR: mv.visitInsn(LOR); break;
                    case BXOR: mv.visitInsn(LXOR); break;
                    case SHL:
                        // long 位移量是 int 类型：先 pop long right，改用 int right
                        mv.visitInsn(POP2); // 丢弃 long right
                        loadInt(mv, right);
                        mv.visitInsn(LSHL);
                        break;
                    case SHR:
                        mv.visitInsn(POP2);
                        loadInt(mv, right);
                        mv.visitInsn(LSHR);
                        break;
                    case USHR:
                        mv.visitInsn(POP2);
                        loadInt(mv, right);
                        mv.visitInsn(LUSHR);
                        break;
                    default: mv.visitInsn(LADD); break;
                }
                boxLong(mv);
                break;
            default: // INT
                loadInt(mv, left);
                loadInt(mv, right);
                switch (op) {
                    case ADD: mv.visitInsn(IADD); break;
                    case SUB: mv.visitInsn(ISUB); break;
                    case MUL: mv.visitInsn(IMUL); break;
                    case DIV: mv.visitInsn(IDIV); break;
                    case MOD: mv.visitInsn(IREM); break;
                    case SHL: mv.visitInsn(ISHL); break;
                    case SHR: mv.visitInsn(ISHR); break;
                    case USHR: mv.visitInsn(IUSHR); break;
                    case BAND: mv.visitInsn(IAND); break;
                    case BOR: mv.visitInsn(IOR); break;
                    case BXOR: mv.visitInsn(IXOR); break;
                    default: mv.visitInsn(IADD); break;
                }
                if (intLocals.contains(dest)) {
                    mv.visitVarInsn(ISTORE, dest);
                    return;
                }
                boxInt(mv);
                break;
        }
        mv.visitVarInsn(ASTORE, dest);
    }

    /**
     * 发出浮点/双精度算术指令（ADD/SUB/MUL/DIV/MOD 通用）。
     * 位运算对浮点无意义，降级为 NOP + ADD。
     */
    private void emitArithOp(MethodVisitor mv, BinaryOp op,
                              int addOp, int subOp, int mulOp, int divOp, int remOp) {
        switch (op) {
            case ADD: mv.visitInsn(addOp); break;
            case SUB: mv.visitInsn(subOp); break;
            case MUL: mv.visitInsn(mulOp); break;
            case DIV: mv.visitInsn(divOp); break;
            case MOD: mv.visitInsn(remOp); break;
            default:  mv.visitInsn(addOp); break;
        }
    }

    // ========== 比较运算 ==========

    /**
     * 根据操作数类型生成正确的比较指令。
     * DOUBLE/FLOAT 使用 DCMPG/FCMPG → IFxx 分支。
     * LONG 使用 LCMP → IFxx 分支。
     * INT 使用 IF_ICMPxx。
     * OBJECT/BOOLEAN 的 EQ/NE 使用 Objects.equals()。
     */
    private void generateComparison(MethodVisitor mv, BinaryOp op,
                                     int left, int right, int dest, MirFunction func) {
        MirType.Kind kind = resolveNumericKind(func, left, right);
        MirType.Kind lk = getLocalType(func, left).getKind();
        MirType.Kind rk = getLocalType(func, right).getKind();

        // OBJECT/BOOLEAN 类型的 EQ/NE 使用引用比较（含 null 比较，避免拆箱 NPE）
        boolean isObjectLike = (op == BinaryOp.EQ || op == BinaryOp.NE)
                && (lk == MirType.Kind.OBJECT || rk == MirType.Kind.OBJECT
                    || lk == MirType.Kind.BOOLEAN || rk == MirType.Kind.BOOLEAN);
        if (isObjectLike) {
            loadObject(mv, left);
            loadObject(mv, right);
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
            if (op == BinaryOp.NE) {
                // 取反
                Label trueLabel = new Label();
                Label endLabel = new Label();
                mv.visitJumpInsn(IFEQ, trueLabel);
                mv.visitInsn(ICONST_0);
                mv.visitJumpInsn(GOTO, endLabel);
                mv.visitLabel(trueLabel);
                mv.visitInsn(ICONST_1);
                mv.visitLabel(endLabel);
            }
            boxBoolean(mv);
            mv.visitVarInsn(ASTORE, dest);
            return;
        }

        switch (kind) {
            case DOUBLE:
                unboxDouble(mv, left);
                unboxDouble(mv, right);
                mv.visitInsn(op == BinaryOp.LT || op == BinaryOp.LE ? DCMPG : DCMPL);
                emitCmpBranch(mv, op, dest);
                break;
            case FLOAT:
                unboxFloat(mv, left);
                unboxFloat(mv, right);
                mv.visitInsn(op == BinaryOp.LT || op == BinaryOp.LE ? FCMPG : FCMPL);
                emitCmpBranch(mv, op, dest);
                break;
            case LONG:
                unboxLong(mv, left);
                unboxLong(mv, right);
                mv.visitInsn(LCMP);
                emitCmpBranch(mv, op, dest);
                break;
            default: // INT
                loadInt(mv, left);
                loadInt(mv, right);
                emitICmpBranch(mv, op, dest);
                break;
        }
    }

    /**
     * 对 LCMP/FCMPG/DCMPG 结果（-1/0/1 int）使用 IFxx 分支生成布尔值。
     */
    private void emitCmpBranch(MethodVisitor mv, BinaryOp op, int dest) {
        Label trueLabel = new Label();
        Label endLabel = new Label();
        int jumpOp;
        switch (op) {
            case EQ: jumpOp = IFEQ; break;
            case NE: jumpOp = IFNE; break;
            case LT: jumpOp = IFLT; break;
            case GT: jumpOp = IFGT; break;
            case LE: jumpOp = IFLE; break;
            case GE: jumpOp = IFGE; break;
            default: jumpOp = IFEQ; break;
        }
        mv.visitJumpInsn(jumpOp, trueLabel);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, endLabel);
        mv.visitLabel(trueLabel);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(endLabel);
        boxBoolean(mv);
        mv.visitVarInsn(ASTORE, dest);
    }

    /**
     * 对两个 int 操作数使用 IF_ICMPxx 分支生成布尔值。
     */
    private void emitICmpBranch(MethodVisitor mv, BinaryOp op, int dest) {
        Label trueLabel = new Label();
        Label endLabel = new Label();
        int jumpOp;
        switch (op) {
            case EQ: jumpOp = IF_ICMPEQ; break;
            case NE: jumpOp = IF_ICMPNE; break;
            case LT: jumpOp = IF_ICMPLT; break;
            case GT: jumpOp = IF_ICMPGT; break;
            case LE: jumpOp = IF_ICMPLE; break;
            case GE: jumpOp = IF_ICMPGE; break;
            default: jumpOp = IF_ICMPEQ; break;
        }
        mv.visitJumpInsn(jumpOp, trueLabel);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, endLabel);
        mv.visitLabel(trueLabel);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(endLabel);
        boxBoolean(mv);
        mv.visitVarInsn(ASTORE, dest);
    }

    // ========== 一元运算 ==========

    private void generateUnaryOp(MethodVisitor mv, UnaryOp op, int operand, int dest,
                                  MirFunction func) {
        switch (op) {
            case NEG: {
                MirType.Kind kind = getLocalType(func, operand).getKind();
                switch (kind) {
                    case DOUBLE:
                        unboxDouble(mv, operand);
                        mv.visitInsn(DNEG);
                        boxDouble(mv);
                        mv.visitVarInsn(ASTORE, dest);
                        break;
                    case FLOAT:
                        unboxFloat(mv, operand);
                        mv.visitInsn(FNEG);
                        boxFloat(mv);
                        mv.visitVarInsn(ASTORE, dest);
                        break;
                    case LONG:
                        unboxLong(mv, operand);
                        mv.visitInsn(LNEG);
                        boxLong(mv);
                        mv.visitVarInsn(ASTORE, dest);
                        break;
                    default:
                        loadInt(mv, operand);
                        mv.visitInsn(INEG);
                        if (intLocals.contains(dest)) {
                            mv.visitVarInsn(ISTORE, dest);
                        } else {
                            boxInt(mv);
                            mv.visitVarInsn(ASTORE, dest);
                        }
                        break;
                }
                break;
            }
            case NOT: {
                unboxBoolean(mv, operand);
                Label trueLabel = new Label();
                Label endLabel = new Label();
                mv.visitJumpInsn(IFEQ, trueLabel);
                mv.visitInsn(ICONST_0);
                mv.visitJumpInsn(GOTO, endLabel);
                mv.visitLabel(trueLabel);
                mv.visitInsn(ICONST_1);
                mv.visitLabel(endLabel);
                boxBoolean(mv);
                mv.visitVarInsn(ASTORE, dest);
                break;
            }
            case BNOT: {
                MirType.Kind kind = getLocalType(func, operand).getKind();
                if (kind == MirType.Kind.LONG) {
                    unboxLong(mv, operand);
                    mv.visitLdcInsn(-1L);
                    mv.visitInsn(LXOR);
                    boxLong(mv);
                    mv.visitVarInsn(ASTORE, dest);
                } else {
                    loadInt(mv, operand);
                    mv.visitInsn(ICONST_M1);
                    mv.visitInsn(IXOR);
                    if (intLocals.contains(dest)) {
                        mv.visitVarInsn(ISTORE, dest);
                    } else {
                        boxInt(mv);
                        mv.visitVarInsn(ASTORE, dest);
                    }
                }
                break;
            }
            default: {
                mv.visitInsn(ACONST_NULL);
                mv.visitVarInsn(ASTORE, dest);
                break;
            }
        }
    }

    // ========== 终止指令 ==========

    private void generateTerminator(MethodVisitor mv, MirTerminator term,
                                    Map<Integer, Label> blockLabels, MirFunction func) {
        if (term instanceof MirTerminator.Goto) {
            int target = ((MirTerminator.Goto) term).getTargetBlockId();
            mv.visitJumpInsn(GOTO, blockLabels.get(target));
        } else if (term instanceof MirTerminator.TailCall) {
            int target = ((MirTerminator.TailCall) term).getEntryBlockId();
            mv.visitJumpInsn(GOTO, blockLabels.get(target));
        } else if (term instanceof MirTerminator.Branch) {
            MirTerminator.Branch branch = (MirTerminator.Branch) term;
            // 调用 NovaValue.truthyCheck(Object) 支持非 Boolean 类型的 truthy 语义
            mv.visitVarInsn(ALOAD, branch.getCondition());
            mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaValue", "truthyCheck",
                    "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFNE, blockLabels.get(branch.getThenBlock()));
            mv.visitJumpInsn(GOTO, blockLabels.get(branch.getElseBlock()));
        } else if (term instanceof MirTerminator.Return) {
            int value = ((MirTerminator.Return) term).getValueLocal();
            if (value >= 0) {
                // 根据方法返回类型选择正确的返回指令
                String retType = currentMethodDesc.substring(currentMethodDesc.indexOf(')') + 1);
                if ("V".equals(retType)) {
                    // void 方法：忽略返回值，使用 RETURN
                    mv.visitInsn(RETURN);
                } else if ("I".equals(retType)) {
                    loadInt(mv, value);
                    mv.visitInsn(IRETURN);
                } else if ("Z".equals(retType)) {
                    if (intLocals.contains(value)) {
                        mv.visitVarInsn(ILOAD, value);
                    } else {
                        unboxBoolean(mv, value);
                    }
                    mv.visitInsn(IRETURN);
                } else if ("J".equals(retType)) {
                    loadObject(mv, value);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number",
                            "longValue", "()J", false);
                    mv.visitInsn(LRETURN);
                } else if ("F".equals(retType)) {
                    loadObject(mv, value);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number",
                            "floatValue", "()F", false);
                    mv.visitInsn(FRETURN);
                } else if ("D".equals(retType)) {
                    loadObject(mv, value);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number",
                            "doubleValue", "()D", false);
                    mv.visitInsn(DRETURN);
                } else {
                    loadObject(mv, value);
                    mv.visitInsn(ARETURN);
                }
            } else {
                mv.visitInsn(RETURN);
            }
        } else if (term instanceof MirTerminator.Throw) {
            int exc = ((MirTerminator.Throw) term).getExceptionLocal();
            mv.visitVarInsn(ALOAD, exc);
            mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
            mv.visitInsn(ATHROW);
        } else if (term instanceof MirTerminator.Unreachable) {
            mv.visitTypeInsn(NEW, "java/lang/AssertionError");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("unreachable");
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError",
                    "<init>", "(Ljava/lang/Object;)V", false);
            mv.visitInsn(ATHROW);
        } else if (term instanceof MirTerminator.Switch) {
            // Switch → equals 链：逐个比较 case key，跳转到对应 block
            MirTerminator.Switch sw = (MirTerminator.Switch) term;
            for (Map.Entry<Object, Integer> entry : sw.getCases().entrySet()) {
                Object caseKey = entry.getKey();
                Label next = new Label();
                loadObject(mv, sw.getKey());
                // 枚举条目 key 是 String（名字），需要先 toString() 再比较
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object",
                        "toString", "()Ljava/lang/String;", false);
                if (caseKey instanceof String) {
                    mv.visitLdcInsn(caseKey);
                } else if (caseKey instanceof Integer) {
                    mv.visitLdcInsn(String.valueOf(caseKey));
                } else {
                    mv.visitLdcInsn(caseKey.toString());
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object",
                        "equals", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, next);
                mv.visitJumpInsn(GOTO, blockLabels.get(entry.getValue()));
                mv.visitLabel(next);
            }
            mv.visitJumpInsn(GOTO, blockLabels.get(sw.getDefaultBlock()));
        }
    }

    /**
     * 分支-比较融合：直接生成 IF_ICMPxx 跳转，跳过中间 Boolean 装箱。
     * fusedConstants: 可内联的 CONST_INT (local → value)，对应指令已跳过生成。
     */
    private void generateFusedCompareBranch(MethodVisitor mv, MirInst cmpInst,
                                             MirTerminator.Branch branch,
                                             Map<Integer, Label> blockLabels,
                                             MirFunction func,
                                             Map<Integer, Integer> fusedConstants) {
        generateFusedCompareBranch(mv, cmpInst.extraAs(),
                cmpInst.operand(0), cmpInst.operand(1),
                branch, blockLabels, func, fusedConstants);
    }

    /**
     * 分支-比较融合核心：直接生成 IF_ICMPxx 跳转，跳过中间 Boolean 装箱。
     * fusedConstants: 可内联的 CONST_INT (local → value)，对应指令已跳过生成。
     */
    private void generateFusedCompareBranch(MethodVisitor mv, BinaryOp op,
                                             int left, int right,
                                             MirTerminator.Branch branch,
                                             Map<Integer, Label> blockLabels,
                                             MirFunction func,
                                             Map<Integer, Integer> fusedConstants) {
        Label thenLabel = blockLabels.get(branch.getThenBlock());
        Label elseLabel = blockLabels.get(branch.getElseBlock());

        MirType.Kind lk = getLocalType(func, left).getKind();
        MirType.Kind rk = getLocalType(func, right).getKind();

        // OBJECT/BOOLEAN 类型：使用引用比较，不可当 int 拆箱（可能是 enum、null 等）
        // EQ/NE: 任一操作数为 OBJECT 即使用引用路径（处理 x != null 等场景）
        // GT/LT 等: 双方均为 OBJECT 才走 Comparable 路径
        boolean hasObjectOperand = (lk == MirType.Kind.OBJECT || lk == MirType.Kind.BOOLEAN)
                || (rk == MirType.Kind.OBJECT || rk == MirType.Kind.BOOLEAN);
        boolean isObjectLike = (op == BinaryOp.EQ || op == BinaryOp.NE)
                ? hasObjectOperand
                : (lk == MirType.Kind.OBJECT || lk == MirType.Kind.BOOLEAN)
                  && (rk == MirType.Kind.OBJECT || rk == MirType.Kind.BOOLEAN);
        if (isObjectLike) {
            if (op == BinaryOp.EQ || op == BinaryOp.NE) {
                loadObject(mv, left);
                loadObject(mv, right);
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(op == BinaryOp.EQ ? IFNE : IFEQ, thenLabel);
                mv.visitJumpInsn(GOTO, elseLabel);
            } else {
                // 非 EQ/NE 的 Object 比较：通过 Comparable.compareTo 比较
                loadObject(mv, left);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Comparable");
                loadObject(mv, right);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Comparable",
                        "compareTo", "(Ljava/lang/Object;)I", true);
                emitFusedCmpBranch(mv, op, thenLabel, elseLabel);
            }
            return;
        }

        MirType.Kind kind = resolveNumericKind(func, left, right);

        if (kind == MirType.Kind.INT) {
            Integer leftConst = fusedConstants.get(left);
            Integer rightConst = fusedConstants.get(right);

            if (rightConst != null && rightConst == 0) {
                // x CMP 0 → 使用 IFxx（单操作数，省去加载常量 0）
                if (leftConst != null) {
                    pushInt(mv, leftConst);
                } else {
                    loadInt(mv, left);
                }
                emitFusedCmpBranch(mv, op, thenLabel, elseLabel);
            } else if (leftConst != null && leftConst == 0) {
                // 0 CMP x → 反转操作符，使用 IFxx
                if (rightConst != null) {
                    pushInt(mv, rightConst);
                } else {
                    loadInt(mv, right);
                }
                emitFusedCmpBranch(mv, reverseCompareOp(op), thenLabel, elseLabel);
            } else {
                // 非零常量：直接 pushInt 替代从局部变量加载
                if (leftConst != null) {
                    pushInt(mv, leftConst);
                } else {
                    loadInt(mv, left);
                }
                if (rightConst != null) {
                    pushInt(mv, rightConst);
                } else {
                    loadInt(mv, right);
                }
                int jumpOp;
                switch (op) {
                    case EQ: jumpOp = IF_ICMPEQ; break;
                    case NE: jumpOp = IF_ICMPNE; break;
                    case LT: jumpOp = IF_ICMPLT; break;
                    case GT: jumpOp = IF_ICMPGT; break;
                    case LE: jumpOp = IF_ICMPLE; break;
                    case GE: jumpOp = IF_ICMPGE; break;
                    default: jumpOp = IF_ICMPEQ; break;
                }
                mv.visitJumpInsn(jumpOp, thenLabel);
                mv.visitJumpInsn(GOTO, elseLabel);
            }
        } else if (kind == MirType.Kind.LONG) {
            unboxLong(mv, left);
            unboxLong(mv, right);
            mv.visitInsn(LCMP);
            emitFusedCmpBranch(mv, op, thenLabel, elseLabel);
        } else if (kind == MirType.Kind.DOUBLE) {
            unboxDouble(mv, left);
            unboxDouble(mv, right);
            mv.visitInsn(op == BinaryOp.LT || op == BinaryOp.LE ? DCMPG : DCMPL);
            emitFusedCmpBranch(mv, op, thenLabel, elseLabel);
        } else if (kind == MirType.Kind.FLOAT) {
            unboxFloat(mv, left);
            unboxFloat(mv, right);
            mv.visitInsn(op == BinaryOp.LT || op == BinaryOp.LE ? FCMPG : FCMPL);
            emitFusedCmpBranch(mv, op, thenLabel, elseLabel);
        } else {
            // Object EQ/NE
            if (op == BinaryOp.EQ || op == BinaryOp.NE) {
                loadObject(mv, left);
                loadObject(mv, right);
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(op == BinaryOp.EQ ? IFNE : IFEQ, thenLabel);
                mv.visitJumpInsn(GOTO, elseLabel);
            } else {
                // 非 EQ/NE 的 Object 比较：通过 Comparable.compareTo 比较
                loadObject(mv, left);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Comparable");
                loadObject(mv, right);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Comparable",
                        "compareTo", "(Ljava/lang/Object;)I", true);
                emitFusedCmpBranch(mv, op, thenLabel, elseLabel);
            }
        }
    }

    /** LCMP/DCMP/FCMP 结果的融合分支 */
    private void emitFusedCmpBranch(MethodVisitor mv, BinaryOp op,
                                     Label thenLabel, Label elseLabel) {
        switch (op) {
            case EQ: mv.visitJumpInsn(IFEQ, thenLabel); break;
            case NE: mv.visitJumpInsn(IFNE, thenLabel); break;
            case LT: mv.visitJumpInsn(IFLT, thenLabel); break;
            case GT: mv.visitJumpInsn(IFGT, thenLabel); break;
            case LE: mv.visitJumpInsn(IFLE, thenLabel); break;
            case GE: mv.visitJumpInsn(IFGE, thenLabel); break;
            default: mv.visitJumpInsn(IFEQ, thenLabel); break;
        }
        mv.visitJumpInsn(GOTO, elseLabel);
    }

    /** 反转比较操作符方向：a op b ↔ b reverseOp a */
    private BinaryOp reverseCompareOp(BinaryOp op) {
        switch (op) {
            case LT: return BinaryOp.GT;
            case GT: return BinaryOp.LT;
            case LE: return BinaryOp.GE;
            case GE: return BinaryOp.LE;
            default: return op; // EQ, NE 不变
        }
    }

    // ========== int 局部变量优化 ==========

    /**
     * 识别可以使用 ILOAD/ISTORE 的 int 局部变量。
     * 条件：非参数 + MirType 为 INT + 所有写入都是 int 安全操作。
     */
    private Set<Integer> identifyIntLocals(MirFunction func, boolean isStatic) {
        Set<Integer> candidates = new HashSet<>();
        // 所有 INT 类型的局部变量（含参数）均为候选
        for (MirLocal local : func.getLocals()) {
            if (local.getType().getKind() == MirType.Kind.INT) {
                candidates.add(local.getIndex());
            }
        }
        // INT 类型参数也加入候选（MirLocal 可能不含参数）
        int slotOffset = isStatic ? 0 : 1;
        for (int i = 0; i < func.getParams().size(); i++) {
            if (func.getParams().get(i).getType().getKind() == MirType.Kind.INT) {
                candidates.add(slotOffset + i);
            }
        }
        if (candidates.isEmpty()) return candidates;

        // 验证：遍历所有指令，排除被非 int 操作写入的局部变量
        Set<Integer> disqualified = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                int dest = inst.getDest();
                if (dest < 0 || !candidates.contains(dest)) continue;
                switch (inst.getOp()) {
                    case CONST_INT:
                        break;
                    case MOVE: {
                        // MOVE 源必须也是 int 类型或已确认的 intLocal，否则不安全
                        int src = inst.operand(0);
                        MirType srcType = getLocalType(func, src);
                        if (srcType.getKind() != MirType.Kind.INT) {
                            disqualified.add(dest);
                        }
                        break;
                    }
                    case BINARY: {
                        BinaryOp bop = inst.extraAs();
                        // 比较/逻辑运算产生 Boolean → 不安全
                        if (isComparisonOp(bop)
                                || bop == BinaryOp.AND || bop == BinaryOp.OR) {
                            disqualified.add(dest);
                            break;
                        }
                        // ADD 可能触发动态路径 → 检查操作数类型
                        if (bop == BinaryOp.ADD) {
                            MirType lt = getLocalType(func, inst.operand(0));
                            MirType rt = getLocalType(func, inst.operand(1));
                            if (isStringType(lt) || isStringType(rt)
                                    || isStringType(getLocalType(func, dest))
                                    // OBJECT 操作数触发 NovaOps.add() 动态路径 → 返回 Object
                                    || lt.getKind() == MirType.Kind.OBJECT
                                    || rt.getKind() == MirType.Kind.OBJECT) {
                                disqualified.add(dest);
                            }
                        }
                        break;
                    }
                    case UNARY: {
                        // NEG/BNOT 对 int 操作数产生 int → 安全
                        UnaryOp uop = inst.extraAs();
                        if (uop == UnaryOp.NEG || uop == UnaryOp.BNOT) {
                            MirType operandType = getLocalType(func, inst.operand(0));
                            if (operandType.getKind() != MirType.Kind.INT) {
                                disqualified.add(dest);
                            }
                        } else {
                            // NOT 等产生 Boolean
                            disqualified.add(dest);
                        }
                        break;
                    }
                    case INDEX_GET: {
                        // int[] 数组的 IALOAD 产生 int → 安全
                        int tgt = inst.operand(0);
                        MirType tgtType = getLocalType(func, tgt);
                        if (!"[I".equals(tgtType.getClassName())) {
                            disqualified.add(dest);
                        }
                        break;
                    }
                    case GET_FIELD: {
                        String fn = (String) inst.getExtra();
                        int objLocal = inst.operand(0);
                        MirType ot = getLocalType(func, objLocal);
                        String ow = ot.getKind() == MirType.Kind.OBJECT && ot.getClassName() != null
                                ? ot.getClassName() : "java/lang/Object";
                        // 数组 size/length → ARRAYLENGTH → int → 安全
                        if (ow.startsWith("[") && ("size".equals(fn) || "length".equals(fn))) {
                            break;
                        }
                        if (ow.startsWith("[")) ow = "java/lang/Object";
                        String fd = lookupFieldDesc(ow, fn);
                        if (!"I".equals(fd)) {
                            disqualified.add(dest);
                        }
                        break;
                    }
                    case INVOKE_STATIC: {
                        if (!isIntReturningInvoke(inst)) {
                            disqualified.add(dest);
                        }
                        break;
                    }
                    case INVOKE_VIRTUAL:
                    case INVOKE_INTERFACE: {
                        if (!isIntReturningInvoke(inst)) {
                            disqualified.add(dest);
                        }
                        break;
                    }
                    default:
                        disqualified.add(dest);
                        break;
                }
            }
        }
        candidates.removeAll(disqualified);
        return candidates;
    }

    /** 检查方法调用指令的返回类型是否为 int */
    private boolean isIntReturningInvoke(MirInst inst) {
        String extra = (String) inst.getExtra();
        if (extra != null && extra.contains("|")) {
            String[] parts = extra.split("\\|", 3);
            if (parts.length >= 3) {
                String retDesc = parts[2].substring(parts[2].indexOf(')') + 1);
                return "I".equals(retDesc);
            }
        }
        return false;
    }

    /** 将局部变量作为 JVM int 加载到栈顶（int 局部变量直接 ILOAD，否则 ALOAD+拆箱） */
    private void loadInt(MethodVisitor mv, int local) {
        if (intLocals.contains(local)) {
            mv.visitVarInsn(ILOAD, local);
        } else {
            unboxInt(mv, local);
        }
    }

    /** 将局部变量作为 Object 加载到栈顶（int 局部变量 ILOAD+装箱，否则 ALOAD） */
    private void loadObject(MethodVisitor mv, int local) {
        if (intLocals.contains(local)) {
            mv.visitVarInsn(ILOAD, local);
            boxInt(mv);
        } else {
            mv.visitVarInsn(ALOAD, local);
        }
    }

    // ========== 装箱/拆箱辅助 ==========

    /** 从局部变量加载并拆箱为 JVM int */
    private void unboxInt(MethodVisitor mv, int local) {
        mv.visitVarInsn(ALOAD, local);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
    }

    /** 从局部变量加载并拆箱为 JVM boolean */
    private void unboxBoolean(MethodVisitor mv, int local) {
        mv.visitVarInsn(ALOAD, local);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
    }

    /** 将栈顶 int 装箱为 Integer */
    private void boxInt(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false);
    }

    /** 将栈顶 boolean(int) 装箱为 Boolean */
    private void boxBoolean(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                "(Z)Ljava/lang/Boolean;", false);
    }

    /** 判断字段描述符是否为 JVM 原始类型 */
    private static boolean isPrimitiveDesc(String desc) {
        return desc.length() == 1 && "IJFDZBC".indexOf(desc.charAt(0)) >= 0;
    }

    /** 将栈顶原始类型值装箱为对应包装类 */
    private void boxFieldValue(MethodVisitor mv, String fieldDesc) {
        switch (fieldDesc) {
            case "I": boxInt(mv); break;
            case "J": boxLong(mv); break;
            case "F":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                break;
            case "D":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                break;
            case "Z": boxBoolean(mv); break;
        }
    }

    /** 从局部变量加载并拆箱为 JVM long */
    private void unboxLong(MethodVisitor mv, int local) {
        mv.visitVarInsn(ALOAD, local);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
    }

    /** 从局部变量加载并拆箱为 JVM float */
    private void unboxFloat(MethodVisitor mv, int local) {
        mv.visitVarInsn(ALOAD, local);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
    }

    /** 从局部变量加载并拆箱为 JVM double */
    private void unboxDouble(MethodVisitor mv, int local) {
        mv.visitVarInsn(ALOAD, local);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
    }

    /** 将栈顶 long 装箱为 Long */
    private void boxLong(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                "(J)Ljava/lang/Long;", false);
    }

    /** 将栈顶 float 装箱为 Float */
    private void boxFloat(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                "(F)Ljava/lang/Float;", false);
    }

    /** 将栈顶 double 装箱为 Double */
    private void boxDouble(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                "(D)Ljava/lang/Double;", false);
    }

    /** 将 int 值压入栈 */
    private void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    /**
     * 如果方法返回原始类型，将栈顶值装箱为对应包装类型。
     */
    private void boxReturnIfPrimitive(MethodVisitor mv, String descriptor) {
        String ret = descriptor.substring(descriptor.indexOf(')') + 1);
        switch (ret) {
            case "I": boxInt(mv); break;
            case "Z": boxBoolean(mv); break;
            case "J":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                break;
            case "F":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                break;
            case "D":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                break;
            // 引用类型不需要装箱
        }
    }

    // ========== 类型辅助 ==========

    private MirType getLocalType(MirFunction func, int localIdx) {
        if (localIdx >= 0 && localIdx < func.getLocals().size()) {
            return func.getLocals().get(localIdx).getType();
        }
        return MirType.ofObject("java/lang/Object");
    }

    /** 按 owner 类名查找字段描述符，沿继承链查找，找不到则回退 Object */
    private String lookupFieldDesc(String owner, String fieldName) {
        // 沿 Nova 类继承链查找字段描述符
        String current = owner;
        while (current != null) {
            Map<String, String> descs = allFieldDescs.get(current);
            if (descs != null) {
                String d = descs.get(fieldName);
                if (d != null) return d;
            }
            current = classSuperClass.get(current);
        }
        // 反射回退：外部类（如 NovaClassInfo）的字段
        if (!"java/lang/Object".equals(owner)) {
            try {
                Class<?> cls = Class.forName(owner.replace('/', '.'));
                java.lang.reflect.Field f = findFieldReflect(cls, fieldName);
                if (f != null) return getDescriptor(f.getType());
            } catch (Exception ignored) { }
        }
        return MethodDescriptor.OBJECT_DESC;
    }

    /** 查找字段：先查 public（含继承），再沿超类链查 declared（含 private/protected） */
    private static java.lang.reflect.Field findFieldReflect(Class<?> cls, String name) {
        try {
            return cls.getField(name);
        } catch (NoSuchFieldException ignored) { }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private static String getDescriptor(Class<?> type) {
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == void.class) return "V";
        if (type.isArray()) return "[" + getDescriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /** 方法体是否为"空"（只有一个块且只有 void return terminator，无其他指令） */
    private boolean isAbstractBody(MirFunction func) {
        List<BasicBlock> blocks = func.getBlocks();
        if (blocks.isEmpty()) return true;
        if (blocks.size() > 1) return false;
        BasicBlock block = blocks.get(0);
        return block.getInstructions().isEmpty()
                && block.getTerminator() instanceof MirTerminator.Return
                && ((MirTerminator.Return) block.getTerminator()).getValueLocal() < 0;
    }

    /**
     * 加载方法参数，根据描述符中的参数类型自动拆箱原始类型。
     * @param startIdx operands 中参数开始的索引（virtual/interface 为 1，static 为 0）
     */
    private void loadAndUnboxParams(MethodVisitor mv, int[] operands, int startIdx, String descriptor) {
        Type[] paramTypes = Type.getArgumentTypes(descriptor);
        for (int i = startIdx; i < operands.length; i++) {
            int paramIdx = i - startIdx;
            if (intLocals.contains(operands[i])) {
                // int 局部变量：根据目标参数类型选择最优加载方式
                if (paramIdx < paramTypes.length && paramTypes[paramIdx].getSort() == Type.INT) {
                    mv.visitVarInsn(ILOAD, operands[i]);
                } else {
                    mv.visitVarInsn(ILOAD, operands[i]);
                    boxInt(mv);
                    if (paramIdx < paramTypes.length) {
                        unboxForType(mv, paramTypes[paramIdx]);
                    }
                }
            } else {
                mv.visitVarInsn(ALOAD, operands[i]);
                if (paramIdx < paramTypes.length) {
                    unboxForType(mv, paramTypes[paramIdx]);
                }
            }
        }
    }

    private void unboxForType(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.INT:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                break;
            case Type.BOOLEAN:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                break;
            case Type.LONG:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                break;
            case Type.FLOAT:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                break;
            case Type.DOUBLE:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                break;
            case Type.CHAR:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                break;
            case Type.OBJECT:
            case Type.ARRAY: {
                String internalName = type.getInternalName();
                if (!"java/lang/Object".equals(internalName)) {
                    mv.visitTypeInsn(CHECKCAST, internalName);
                }
                break;
            }
        }
    }

    private boolean isMapType(String className) {
        return className != null && (
                className.equals("java/util/HashMap") ||
                className.equals("java/util/LinkedHashMap") ||
                className.equals("java/util/TreeMap") ||
                className.equals("java/util/Map") ||
                className.equals("java/util/concurrent/ConcurrentHashMap"));
    }

    private boolean isListType(String className) {
        return className != null && (
                className.equals("java/util/ArrayList") ||
                className.equals("java/util/LinkedList") ||
                className.equals("java/util/List"));
    }

    private boolean isStringType(MirType type) {
        return type.getKind() == MirType.Kind.OBJECT
                && ("java/lang/String".equals(type.getClassName()) || "String".equals(type.getClassName()));
    }

    private boolean isComparisonOp(BinaryOp op) {
        switch (op) {
            case EQ: case NE: case LT: case GT: case LE: case GE: return true;
            default: return false;
        }
    }

    /** 检查局部变量是否仅被融合比较指令使用（作为操作数） */
    private boolean isLocalOnlyUsedInFusedCmp(List<MirInst> instructions,
                                               MirInst fusedCmp, int localIdx) {
        for (MirInst inst : instructions) {
            if (inst == fusedCmp) continue;
            if (inst.getDest() == localIdx) continue; // 定义指令本身
            if (inst.getOperands() != null) {
                for (int op : inst.getOperands()) {
                    if (op == localIdx) return false; // 被其他指令使用
                }
            }
        }
        // 还需检查 terminator 引用
        return true;
    }

    /**
     * 检查指定 local 是否在函数的其他 block 中被引用。
     * 如果被引用，则不能跳过该 local 的生成。
     */
    private boolean isLocalUsedInOtherBlocks(MirFunction func, BasicBlock currentBlock, int localIdx) {
        for (BasicBlock block : func.getBlocks()) {
            if (block == currentBlock) continue;
            for (MirInst inst : block.getInstructions()) {
                if (inst.getOperands() != null) {
                    for (int op : inst.getOperands()) {
                        if (op == localIdx) return true;
                    }
                }
            }
            // 检查 terminator 中的引用
            MirTerminator term = block.getTerminator();
            if (term instanceof MirTerminator.Branch) {
                if (((MirTerminator.Branch) term).getCondition() == localIdx) return true;
            }
        }
        return false;
    }

    /** 检查指定 slot 是否被方法体中的任何指令写入（dest） */
    private boolean isSlotWrittenInBody(MirFunction func, int slot) {
        for (BasicBlock block : func.getBlocks()) {
            for (MirInst inst : block.getInstructions()) {
                if (inst.getDest() == slot) return true;
            }
        }
        return false;
    }

    /**
     * 从两个操作数的类型推断算术运算应使用的数值类型。
     * 优先级: DOUBLE > FLOAT > LONG > INT（同 Java 类型提升规则）
     */
    private MirType.Kind resolveNumericKind(MirFunction func, int left, int right) {
        MirType.Kind lk = getLocalType(func, left).getKind();
        MirType.Kind rk = getLocalType(func, right).getKind();
        if (lk == MirType.Kind.DOUBLE || rk == MirType.Kind.DOUBLE) return MirType.Kind.DOUBLE;
        if (lk == MirType.Kind.FLOAT || rk == MirType.Kind.FLOAT) return MirType.Kind.FLOAT;
        if (lk == MirType.Kind.LONG || rk == MirType.Kind.LONG) return MirType.Kind.LONG;
        return MirType.Kind.INT;
    }

    /**
     * 判断 ADD 操作是否为字符串拼接：dest 类型为 String，或任一操作数为 String。
     */
    private boolean isStringConcat(MirFunction func, MirType destType, int left, int right) {
        if (isStringType(destType)) return true;
        if (stringLocals.contains(left) || stringLocals.contains(right)) return true;
        return isStringType(getLocalType(func, left)) || isStringType(getLocalType(func, right));
    }

    /**
     * 判断 ADD 操作数是否包含泛型 OBJECT 类型（编译时无法确定 String 还是 Number）。
     * 此时需委托 NovaOps.add() 在运行时动态判断。
     */
    private boolean isUnknownObjectType(MirFunction func, int left, int right) {
        MirType lt = getLocalType(func, left);
        MirType rt = getLocalType(func, right);
        boolean leftUnknown = lt.getKind() == MirType.Kind.OBJECT && !isStringType(lt)
                && !intLocals.contains(left) && !stringLocals.contains(left);
        boolean rightUnknown = rt.getKind() == MirType.Kind.OBJECT && !isStringType(rt)
                && !intLocals.contains(right) && !stringLocals.contains(right);
        return leftUnknown || rightUnknown;
    }

    /**
     * 构建方法描述符。所有参数和返回值均装箱为 Object。
     */
    private String buildMethodDescriptor(MirFunction func) {
        return MethodDescriptor.fromMirFunction(func).toAllObjectDescriptor();
    }

    // ========== Object 单例类生成 ==========

    private void generateObjectClass(MirClass cls, String packagePrefix) {
        ClassWriter cw = new NovaClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String className = packagePrefix + cls.getName();
        String superName = cls.getSuperClass() != null ? cls.getSuperClass() : "java/lang/Object";

        cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, superName, null);

        // INSTANCE 静态字段
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "INSTANCE",
                "L" + className + ";", null, null).visitEnd();

        // 从预扫描中获取字段描述符映射
        currentFieldDescs = allFieldDescs.getOrDefault(className, new HashMap<>());

        // 实例字段（静态保持装箱，实例引用类型统一 Object）
        for (MirField field : cls.getFields()) {
            int fieldAccess = ACC_PUBLIC;
            if (field.getModifiers().contains(Modifier.STATIC)) fieldAccess |= ACC_STATIC;
            String desc = field.getType().getFieldDescriptor();
            cw.visitField(fieldAccess, field.getName(), desc, null, null);
        }

        // 构造器: 使用 MIR 生成的 <init>（含字段初始化），否则用空构造器
        MirFunction mirInit = null;
        for (MirFunction m : cls.getMethods()) {
            if ("<init>".equals(m.getName())) { mirInit = m; break; }
        }
        if (mirInit != null) {
            generateMethod(cw, mirInit, className, false, superName, ClassKind.CLASS);
        } else {
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        // <clinit>: INSTANCE = new ClassName()
        // 检查是否有 MIR 生成的 <clinit>（如枚举初始化等）
        MirFunction mirClinit = null;
        for (MirFunction m : cls.getMethods()) {
            if ("<clinit>".equals(m.getName())) { mirClinit = m; break; }
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, className);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "()V", false);
            mv.visitFieldInsn(PUTSTATIC, className, "INSTANCE", "L" + className + ";");
            // 如果有 MIR <clinit>，追加其静态字段初始化指令
            if (mirClinit != null) {
                Map<Integer, Label> blkLabels = new HashMap<>();
                for (BasicBlock block : mirClinit.getBlocks()) {
                    blkLabels.put(block.getId(), new Label());
                }
                this.intLocals = identifyIntLocals(mirClinit, true);
                this.stringLocals.clear();
                for (BasicBlock block : mirClinit.getBlocks()) {
                    mv.visitLabel(blkLabels.get(block.getId()));
                    for (MirInst inst : block.getInstructions()) {
                        generateInstruction(mv, inst, blkLabels, mirClinit);
                    }
                    // 不生成 terminator（我们自己控制 RETURN）
                }
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(10, 10);
            mv.visitEnd();
        }

        // 实例方法
        for (MirFunction method : cls.getMethods()) {
            if ("<init>".equals(method.getName())) continue;
            if ("<clinit>".equals(method.getName())) continue;
            boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
            generateMethod(cw, method, className, isStatic, superName, ClassKind.CLASS);
        }

        cw.visitEnd();
        generatedClasses.put(className, cw.toByteArray());
    }

    // ========== Annotation @interface 生成 ==========

    private void generateAnnotationClass(MirClass cls, String packagePrefix) {
        ClassWriter cw = new NovaClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String className = packagePrefix + cls.getName();

        cw.visit(V1_8,
                ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION,
                className, null, "java/lang/Object",
                new String[]{"java/lang/annotation/Annotation"});

        // @Retention(RUNTIME)
        AnnotationVisitor retAv = cw.visitAnnotation(
                "Ljava/lang/annotation/Retention;", true);
        retAv.visitEnum("value",
                "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
        retAv.visitEnd();

        // @Target({TYPE, FIELD, METHOD})
        AnnotationVisitor targetAv = cw.visitAnnotation(
                "Ljava/lang/annotation/Target;", true);
        AnnotationVisitor arr = targetAv.visitArray("value");
        arr.visitEnum(null, "Ljava/lang/annotation/ElementType;", "TYPE");
        arr.visitEnum(null, "Ljava/lang/annotation/ElementType;", "FIELD");
        arr.visitEnum(null, "Ljava/lang/annotation/ElementType;", "METHOD");
        arr.visitEnd();
        targetAv.visitEnd();

        // 构造器参数 → 注解元素方法（abstract）
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            String retDesc = MethodDescriptor.OBJECT_DESC;
            // 特殊类型映射
            MirType.Kind kind = field.getType().getKind();
            if (kind == MirType.Kind.INT) retDesc = "I";
            else if (kind == MirType.Kind.LONG) retDesc = "J";
            else if (kind == MirType.Kind.BOOLEAN) retDesc = "Z";
            else if (kind == MirType.Kind.OBJECT &&
                    ("java/lang/String".equals(field.getType().getClassName())
                            || "String".equals(field.getType().getClassName())))
                retDesc = "Ljava/lang/String;";
            MethodVisitor mv = cw.visitMethod(
                    ACC_PUBLIC | ACC_ABSTRACT, field.getName(),
                    "()" + retDesc, null, null);
            mv.visitEnd();
        }

        cw.visitEnd();
        generatedClasses.put(className, cw.toByteArray());
    }

    // ========== @data 合成方法 ==========

    /**
     * 为 @data class 生成 toString, equals, hashCode, componentN, copy 方法。
     */
    private void generateDataMethods(ClassWriter cw, MirClass cls, String className) {
        // 收集非静态字段（构造器参数字段）
        List<MirField> instanceFields = new java.util.ArrayList<>();
        for (MirField f : cls.getFields()) {
            if (!f.getModifiers().contains(Modifier.STATIC)) {
                instanceFields.add(f);
            }
        }

        generateDataToString(cw, cls, className, instanceFields);
        generateDataEquals(cw, cls, className, instanceFields);
        generateDataHashCode(cw, cls, className, instanceFields);
        generateDataComponentN(cw, cls, className, instanceFields);
        generateDataCopy(cw, cls, className, instanceFields);
    }

    private void generateDataToString(ClassWriter cw, MirClass cls, String className,
                                       List<MirField> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "toString",
                "()Ljava/lang/String;", null, null);
        mv.visitCode();

        // StringBuilder sb = new StringBuilder("ClassName(")
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(cls.getName() + "(");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder",
                "<init>", "(Ljava/lang/String;)V", false);

        for (int i = 0; i < fields.size(); i++) {
            MirField field = fields.get(i);
            if (i > 0) {
                mv.visitLdcInsn(", ");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder",
                        "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            }
            // "fieldName="
            mv.visitLdcInsn(field.getName() + "=");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder",
                    "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            // this.field
            mv.visitVarInsn(ALOAD, 0);
            String toStrFd = currentFieldDescs.getOrDefault(field.getName(), MethodDescriptor.OBJECT_DESC);
            mv.visitFieldInsn(GETFIELD, className, field.getName(), toStrFd);
            if (isPrimitiveDesc(toStrFd)) boxFieldValue(mv, toStrFd);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder",
                    "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false);
        }

        mv.visitLdcInsn(")");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder",
                "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder",
                "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
    }

    private void generateDataEquals(ClassWriter cw, MirClass cls, String className,
                                     List<MirField> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "equals",
                "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();

        // if (this == other) return true
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        Label notSame = new Label();
        mv.visitJumpInsn(IF_ACMPNE, notSame);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(notSame);

        // if (!(other instanceof ClassName)) return false
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(INSTANCEOF, className);
        Label isInstance = new Label();
        mv.visitJumpInsn(IFNE, isInstance);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitLabel(isInstance);

        // ClassName that = (ClassName) other
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, className);
        mv.visitVarInsn(ASTORE, 2);

        // 逐字段比较 Objects.equals(this.f, that.f)
        for (MirField field : fields) {
            String fd = currentFieldDescs.getOrDefault(field.getName(), MethodDescriptor.OBJECT_DESC);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, className, field.getName(), fd);
            if (isPrimitiveDesc(fd)) boxFieldValue(mv, fd);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitFieldInsn(GETFIELD, className, field.getName(), fd);
            if (isPrimitiveDesc(fd)) boxFieldValue(mv, fd);
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
            Label fieldEqual = new Label();
            mv.visitJumpInsn(IFNE, fieldEqual);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitLabel(fieldEqual);
        }

        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(4, 3);
        mv.visitEnd();
    }

    private void generateDataHashCode(ClassWriter cw, MirClass cls, String className,
                                       List<MirField> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        mv.visitCode();

        // Objects.hash(field1, field2, ...)
        pushInt(mv, fields.size());
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < fields.size(); i++) {
            mv.visitInsn(DUP);
            pushInt(mv, i);
            mv.visitVarInsn(ALOAD, 0);
            String hashFd = currentFieldDescs.getOrDefault(fields.get(i).getName(), MethodDescriptor.OBJECT_DESC);
            mv.visitFieldInsn(GETFIELD, className, fields.get(i).getName(), hashFd);
            if (isPrimitiveDesc(hashFd)) boxFieldValue(mv, hashFd);
            mv.visitInsn(AASTORE);
        }
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "hash",
                "([Ljava/lang/Object;)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(5, 1);
        mv.visitEnd();
    }

    private void generateDataComponentN(ClassWriter cw, MirClass cls, String className,
                                         List<MirField> fields) {
        for (int i = 0; i < fields.size(); i++) {
            MirField field = fields.get(i);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "component" + (i + 1),
                    "()Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            String compFd = currentFieldDescs.getOrDefault(field.getName(), MethodDescriptor.OBJECT_DESC);
            mv.visitFieldInsn(GETFIELD, className, field.getName(), compFd);
            if (isPrimitiveDesc(compFd)) boxFieldValue(mv, compFd);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
    }

    private void generateDataCopy(ClassWriter cw, MirClass cls, String className,
                                   List<MirField> fields) {
        // copy(arg1, arg2, ...) → new ClassName(arg1, arg2, ...)
        String desc = MethodDescriptor.allObjectDesc(fields.size());

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "copy", desc, null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, className);
        mv.visitInsn(DUP);
        for (int i = 0; i < fields.size(); i++) {
            mv.visitVarInsn(ALOAD, i + 1); // params start at 1 (0 is this)
        }
        String initDesc = MethodDescriptor.allObjectVoidDesc(fields.size());
        mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", initDesc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(fields.size() + 2, fields.size() + 1);
        mv.visitEnd();
    }

    // ========== @builder 生成 ==========

    /**
     * 在原类上生成 static builder() 工厂方法。
     */
    private void generateBuilderFactory(ClassWriter cw, MirClass cls, String className,
                                         String packagePrefix) {
        String builderClass = className + "$Builder";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "builder",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, builderClass);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, builderClass, "<init>", "()V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    /**
     * 生成 Builder 内部类：字段、fluent setter、build() 方法。
     */
    private void generateBuilderInnerClass(MirClass cls, String className, String packagePrefix) {
        String builderClass = className + "$Builder";
        ClassWriter cw = new NovaClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, builderClass, null, "java/lang/Object", null);

        // 收集非静态字段
        List<MirField> instanceFields = new java.util.ArrayList<>();
        for (MirField f : cls.getFields()) {
            if (!f.getModifiers().contains(Modifier.STATIC)) {
                instanceFields.add(f);
            }
        }

        // Builder 的字段（与原类一致）
        for (MirField field : instanceFields) {
            cw.visitField(ACC_PRIVATE, field.getName(), MethodDescriptor.OBJECT_DESC, null, null);
        }

        // 默认构造器
        generateDefaultConstructor(cw, "java/lang/Object");

        // Fluent setter 方法
        for (MirField field : instanceFields) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, field.getName(),
                    "(" + MethodDescriptor.OBJECT_DESC + ")" + MethodDescriptor.OBJECT_DESC, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, builderClass, field.getName(), MethodDescriptor.OBJECT_DESC);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }

        // build() 方法
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "build",
                    MethodDescriptor.NO_ARG_OBJECT, null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, className);
            mv.visitInsn(DUP);
            for (MirField field : instanceFields) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, builderClass, field.getName(), MethodDescriptor.OBJECT_DESC);
            }
            String initDesc = MethodDescriptor.allObjectVoidDesc(instanceFields.size());
            mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", initDesc, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(instanceFields.size() + 2, 1);
            mv.visitEnd();
        }

        cw.visitEnd();
        generatedClasses.put(builderClass, cw.toByteArray());
    }

    // ========== 运行时注解处理器触发 ==========

    /**
     * 为枚举类生成 {@code values()} 静态方法，返回包含所有枚举条目的 Object[] 数组。
     */
    private void generateEnumValuesMethod(ClassWriter cw, MirClass cls, String className) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "values",
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();

        // 统计枚举条目数量
        int count = 0;
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)
                    && field.getModifiers().contains(Modifier.FINAL)) {
                count++;
            }
        }

        // new Object[count]
        pushInt(mv, count);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

        // 依次填充数组
        int idx = 0;
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)
                    && field.getModifiers().contains(Modifier.FINAL)) {
                mv.visitInsn(DUP);
                pushInt(mv, idx);
                mv.visitFieldInsn(GETSTATIC, className, field.getName(),
                        field.getType().getFieldDescriptor());
                mv.visitInsn(AASTORE);
                idx++;
            }
        }

        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
    }

    /**
     * 为枚举类生成 {@code toString()} 方法，返回枚举条目的名称。
     * 逻辑：依次比较 this == 各静态字段，匹配则返回字段名。
     */
    private void generateEnumToString(ClassWriter cw, MirClass cls, String className) {
        // 检查是否已有用户定义的 toString
        for (MirFunction method : cls.getMethods()) {
            if ("toString".equals(method.getName()) && method.getParams().isEmpty()) return;
        }

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "toString",
                "()Ljava/lang/String;", null, null);
        mv.visitCode();

        // 收集枚举条目（static final 字段）
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)
                    && field.getModifiers().contains(Modifier.FINAL)) {
                Label nextLabel = new Label();
                mv.visitVarInsn(ALOAD, 0); // this
                mv.visitFieldInsn(GETSTATIC, className, field.getName(),
                        field.getType().getFieldDescriptor());
                mv.visitJumpInsn(IF_ACMPNE, nextLabel);
                mv.visitLdcInsn(field.getName());
                mv.visitInsn(ARETURN);
                mv.visitLabel(nextLabel);
            }
        }

        // 默认回退：调用 Object.toString()
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "toString",
                "()Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
    }

    /**
     * 为被注解类生成 {@code <clinit>}，在类初始化时调用
     * {@code NovaAnnotations.trigger(name, Class, Map)}。
     */
    private void generateAnnotationTriggerClinit(ClassWriter cw, MirClass cls, String className) {
        // 收集需要运行时触发的注解（跳过编译期处理器）
        java.util.List<String> runtimeAnns = new java.util.ArrayList<>();
        for (String annName : cls.getAnnotationNames()) {
            if ("data".equals(annName) || "builder".equals(annName)) continue;
            runtimeAnns.add(annName);
        }
        if (runtimeAnns.isEmpty()) return;

        // 已有 <clinit> 则不重复生成
        for (MirFunction method : cls.getMethods()) {
            if ("<clinit>".equals(method.getName())) return;
        }

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        for (String annName : runtimeAnns) {
            // 参数 1: 注解名
            mv.visitLdcInsn(annName);
            // 参数 2: 当前类的 Class 对象
            mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(className));
            // 参数 3: 空 Map（MIR 暂不携带注解参数）
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "emptyMap",
                    "()Ljava/util/Map;", false);
            // 调用 NovaAnnotations.trigger(String, Class, Map)
            mv.visitMethodInsn(INVOKESTATIC, "nova/runtime/NovaAnnotations", "trigger",
                    "(Ljava/lang/String;Ljava/lang/Class;Ljava/util/Map;)V", false);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
    }

    // ========== 自定义注解标注类 ==========

    /**
     * 为类添加 JVM VisibleAnnotation（使自定义注解可通过反射读取）。
     */
    private void addClassAnnotations(ClassWriter cw, MirClass cls, String packagePrefix) {
        for (String annName : cls.getAnnotationNames()) {
            // 跳过内建注解处理器名
            if ("data".equals(annName) || "builder".equals(annName)) continue;
            String annDesc = "L" + packagePrefix + annName + ";";
            AnnotationVisitor av = cw.visitAnnotation(annDesc, true);
            av.visitEnd();
        }
    }

    /**
     * 通过反射查找 Java 类构造器描述符。
     */
    private String resolveJavaCtorDesc(String internalName, int argCount) {
        try {
            Class<?> cls = Class.forName(internalName.replace('/', '.'));
            for (java.lang.reflect.Constructor<?> ctor : cls.getConstructors()) {
                if (ctor.getParameterCount() == argCount) {
                    StringBuilder desc = new StringBuilder("(");
                    for (Class<?> p : ctor.getParameterTypes()) {
                        desc.append(org.objectweb.asm.Type.getDescriptor(p));
                    }
                    desc.append(")V");
                    return desc.toString();
                }
            }
        } catch (ClassNotFoundException e) {
            // 非 Java 类，返回 null 走回退逻辑
        }
        return null;
    }

    /**
     * 自定义 ClassWriter：动态生成的 Nova 类无法通过 Class.forName 解析，
     * 覆盖 getCommonSuperClass 避免 TypeNotPresentException。
     */
    private static class NovaClassWriter extends ClassWriter {
        NovaClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (RuntimeException e) {
                return "java/lang/Object";
            }
        }
    }
}
