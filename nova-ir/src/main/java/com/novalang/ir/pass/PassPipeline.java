package com.novalang.ir.pass;

import com.novalang.compiler.ast.decl.Program;
import com.novalang.ir.backend.MirCodeGenerator;
import com.novalang.ir.hir.decl.HirClass;
import com.novalang.ir.hir.decl.HirModule;
import com.novalang.ir.lowering.AstToHirLowering;
import com.novalang.ir.lowering.HirToMirLowering;
import com.novalang.ir.mir.BasicBlock;
import com.novalang.ir.mir.MirClass;
import com.novalang.ir.mir.MirFunction;
import com.novalang.ir.mir.MirLocal;
import com.novalang.ir.mir.MirModule;
import com.novalang.ir.pass.hir.HirConstantFolding;
import com.novalang.ir.pass.hir.HirDeadCodeElimination;
import com.novalang.ir.pass.hir.HirInlineExpansion;
import com.novalang.ir.pass.mir.BlockMerging;
import com.novalang.ir.pass.mir.DeadBlockElimination;
import com.novalang.ir.pass.mir.LoopDeadStoreElimination;
import com.novalang.ir.pass.mir.LoopInvariantCodeMotion;
import com.novalang.ir.pass.mir.StrengthReduction;
import com.novalang.ir.pass.mir.MirLocalCSE;
import com.novalang.ir.pass.mir.MirPeepholeOptimization;
import com.novalang.ir.pass.mir.TailCallElimination;

import com.novalang.compiler.analysis.AnalysisResult;
import com.novalang.compiler.analysis.SemanticAnalyzer;
import com.novalang.compiler.analysis.SemanticDiagnostic;

import java.util.*;


/**
 * 编译 Pass 管线。
 * 串联完整流程：AST → HIR → HIR优化 → MIR → MIR优化 → 字节码。
 */
public class PassPipeline {

    private final List<HirPass> hirPasses = new ArrayList<>();
    private final List<MirPass> mirPasses = new ArrayList<>();
    private List<HirClass> externalClasses = new ArrayList<>();
    private Collection<String> externalClassNames = Collections.emptyList();
    private Collection<String> externalInterfaceNames = Collections.emptyList();
    private boolean scriptMode = false;
    private boolean interpreterMode = false;
    /** 持久化匿名类计数器（跨 evalRepl 调用递增，避免 Lambda 类名冲突） */
    private int anonymousClassCounterBase = 0;
    /** 是否在 pipeline 中运行语义分析 */
    private boolean enableSemanticAnalysis = false;
    /** strict 模式：ERROR 级诊断抛异常中止 */
    private boolean strictSemanticMode = false;

    public void setScriptMode(boolean scriptMode) {
        this.scriptMode = scriptMode;
    }

    public void setInterpreterMode(boolean interpreterMode) {
        this.interpreterMode = interpreterMode;
    }

    public void setEnableSemanticAnalysis(boolean enable) {
        this.enableSemanticAnalysis = enable;
    }

    public void setStrictSemanticMode(boolean strict) {
        this.strictSemanticMode = strict;
    }

    public PassPipeline() {
    }

    /**
     * 创建默认管线（包含 P0 优化 pass）。
     */
    public static PassPipeline createDefault() {
        PassPipeline pipeline = new PassPipeline();
        // HIR P0
        pipeline.addHirPass(new HirInlineExpansion());
        pipeline.addHirPass(new HirConstantFolding());
        pipeline.addHirPass(new HirDeadCodeElimination());
        // MIR P0
        pipeline.addMirPass(new DeadBlockElimination());
        pipeline.addMirPass(new LoopInvariantCodeMotion());
        pipeline.addMirPass(new LoopDeadStoreElimination());
        pipeline.addMirPass(new TailCallElimination());
        pipeline.addMirPass(new StrengthReduction());
        pipeline.addMirPass(new MirLocalCSE());
        pipeline.addMirPass(new MirPeepholeOptimization());
        pipeline.addMirPass(new BlockMerging());
        pipeline.addMirPass(new DeadBlockElimination());  // 清理合并后的不可达块
        return pipeline;
    }

    public void addHirPass(HirPass pass) {
        hirPasses.add(pass);
    }

    public void addMirPass(MirPass pass) {
        mirPasses.add(pass);
    }

    public List<HirPass> getHirPasses() { return hirPasses; }
    public List<MirPass> getMirPasses() { return mirPasses; }

    public void setExternalClasses(List<HirClass> classes) {
        this.externalClasses = classes;
    }

    public void setExternalClassNames(Collection<String> names) {
        this.externalClassNames = names;
    }

    public void setExternalInterfaceNames(Collection<String> names) {
        this.externalInterfaceNames = names;
    }

    /**
     * 执行完整管线：AST → HIR → HIR优化 → MIR → MIR优化 → 字节码。
     *
     * @param program AST 根节点
     * @return className → bytecode 映射
     */
    public Map<String, byte[]> execute(Program program) {
        // 0. 语义分析（可选）
        if (enableSemanticAnalysis) {
            runSemanticAnalysis(program);
        }

        // 1. AST → HIR（AST 之后不再需要，program 由调用方管理）
        AstToHirLowering astLowering = new AstToHirLowering();
        astLowering.setScriptMode(scriptMode);
        HirModule hir = astLowering.lower(program);

        // 2. HIR 优化
        for (HirPass pass : hirPasses) {
            hir = pass.run(hir);
        }

        // 3. HIR → MIR
        HirToMirLowering lowering = new HirToMirLowering();
        lowering.setScriptMode(scriptMode);
        lowering.setInterpreterMode(interpreterMode);
        lowering.setAnonymousClassCounterBase(anonymousClassCounterBase);
        if (!externalClasses.isEmpty()) {
            lowering.registerExternalClasses(externalClasses);
        }
        if (externalClassNames != null && !externalClassNames.isEmpty()) {
            lowering.registerExternalClassNames(externalClassNames);
        }
        if (externalInterfaceNames != null && !externalInterfaceNames.isEmpty()) {
            lowering.registerExternalInterfaceNames(externalInterfaceNames);
        }
        MirModule mir = lowering.lower(hir);
        anonymousClassCounterBase = lowering.getAnonymousClassCounter();
        hir = null; // 释放 HIR，减少内存峰值

        // 4. MIR 优化
        for (MirPass pass : mirPasses) {
            mir = pass.run(mir);
        }

        // MIR dump（设置 NOVA_DUMP_MIR=1 环境变量启用）
        if ("1".equals(System.getenv("NOVA_DUMP_MIR"))) {
            System.err.println("===== MIR DUMP =====");
            dumpMir(mir);
            System.err.println("===== END MIR DUMP =====");
        }

        // 5. MIR → 字节码
        return new MirCodeGenerator().generate(mir);
    }

    /**
     * 运行语义分析并处理诊断结果。
     * strict 模式下 ERROR 级诊断会抛出异常中止；宽松模式仅输出到 stderr。
     */
    private void runSemanticAnalysis(Program program) {
        try {
            SemanticAnalyzer analyzer = new SemanticAnalyzer();
            // 编译管线只需诊断输出，跳过 exprNovaTypeMap / 位置索引记录以节省内存
            analyzer.setDiagnosticsOnly(true);
            AnalysisResult result = analyzer.analyze(program);
            List<SemanticDiagnostic> diagnostics = result.getDiagnostics();
            if (diagnostics.isEmpty()) return;

            boolean hasErrors = false;
            StringBuilder errorMessages = null;

            for (SemanticDiagnostic d : diagnostics) {
                SemanticDiagnostic.Severity severity = d.getSeverity();
                if (strictSemanticMode && severity == SemanticDiagnostic.Severity.WARNING) {
                    severity = SemanticDiagnostic.Severity.ERROR;
                }
                System.err.println("[" + severity + "] " + d.getMessage());
                if (severity == SemanticDiagnostic.Severity.ERROR) {
                    hasErrors = true;
                    if (errorMessages == null) errorMessages = new StringBuilder("Semantic analysis errors:\n");
                    errorMessages.append("  ").append(d.getMessage()).append('\n');
                }
            }

            if (hasErrors && strictSemanticMode && errorMessages != null) {
                throw new RuntimeException(errorMessages.toString());
            }
        } catch (RuntimeException e) {
            if (strictSemanticMode) throw e;
            // 宽松模式：区分诊断异常和分析器内部 bug
            // 诊断异常（上面主动抛出的）含 "Semantic analysis errors:" 前缀，静默处理
            // 其他异常（NPE/状态不一致等）输出到 stderr 以便排查
            if (e.getMessage() == null || !e.getMessage().startsWith("Semantic analysis errors:")) {
                System.err.println("[WARN] Semantic analyzer internal error: " + e.getMessage());
            }
        }
    }

    /**
     * 打印 MIR 模块详细信息（用于调试）。
     */
    private void dumpMir(MirModule mir) {
        for (MirClass cls : mir.getClasses()) {
            System.err.println("class " + cls.getName() + " {");
            for (MirFunction method : cls.getMethods()) {
                dumpFunction(method);
            }
            System.err.println("}");
        }
        for (MirFunction func : mir.getTopLevelFunctions()) {
            dumpFunction(func);
        }
    }

    private void dumpFunction(MirFunction func) {
        System.err.println("  fun " + func.getName() + " -> " + func.getReturnType());
        // 打印局部变量表
        System.err.println("    locals:");
        for (MirLocal local : func.getLocals()) {
            System.err.println("      %" + local.getIndex() + " " + local.getName()
                    + " : " + local.getType());
        }
        // 打印异常表
        if (!func.getTryCatchEntries().isEmpty()) {
            System.err.println("    try-catch:");
            for (MirFunction.TryCatchEntry entry : func.getTryCatchEntries()) {
                System.err.println("      [B" + entry.tryStartBlock + ", B" + entry.tryEndBlock
                        + ") -> B" + entry.handlerBlock + " type=" + entry.exceptionType
                        + " exLocal=%" + entry.exceptionLocal);
            }
        }
        // 打印基本块
        for (BasicBlock block : func.getBlocks()) {
            System.err.print("    " + block.toString().replace("\n", "\n    "));
            System.err.println();
        }
    }

    /**
     * 只执行到 HIR 阶段（用于解释器）。
     */
    public HirModule executeToHir(Program program) {
        // 语义分析（可选）
        if (enableSemanticAnalysis) {
            runSemanticAnalysis(program);
        }

        AstToHirLowering astLowering = new AstToHirLowering();
        astLowering.setScriptMode(scriptMode);
        HirModule hir = astLowering.lower(program);
        for (HirPass pass : hirPasses) {
            hir = pass.run(hir);
        }
        return hir;
    }

    /**
     * 只执行到 MIR 阶段（用于调试/分析）。
     */
    public MirModule executeToMir(Program program) {
        HirModule hir = executeToHir(program);
        HirToMirLowering lowering = new HirToMirLowering();
        lowering.setScriptMode(scriptMode);
        lowering.setInterpreterMode(interpreterMode);
        lowering.setAnonymousClassCounterBase(anonymousClassCounterBase);
        if (!externalClasses.isEmpty()) {
            lowering.registerExternalClasses(externalClasses);
        }
        if (externalClassNames != null && !externalClassNames.isEmpty()) {
            lowering.registerExternalClassNames(externalClassNames);
        }
        if (externalInterfaceNames != null && !externalInterfaceNames.isEmpty()) {
            lowering.registerExternalInterfaceNames(externalInterfaceNames);
        }
        MirModule mir = lowering.lower(hir);
        anonymousClassCounterBase = lowering.getAnonymousClassCounter();
        hir = null; // 释放 HIR
        for (MirPass pass : mirPasses) {
            mir = pass.run(mir);
        }
        return mir;
    }
}
