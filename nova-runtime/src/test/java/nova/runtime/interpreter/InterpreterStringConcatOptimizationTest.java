package nova.runtime.interpreter;

import com.novalang.ir.mir.MirFunction;
import com.novalang.ir.mir.MirModule;
import com.novalang.ir.mir.StringAccumLoopPlan;
import nova.runtime.NovaValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterStringConcatOptimizationTest {

    private static final String STRING_CONCAT_SOURCE =
            "fun run(): Int {\n"
                    + "  var s = \"\"\n"
                    + "  for (i in 0..<3000) {\n"
                    + "    s = s + \"ab\" + i\n"
                    + "  }\n"
                    + "  return s.length()\n"
                    + "}\n"
                    + "run()";

    @Test
    void planDetectionFindsStringAccumLoop() {
        Interpreter compiler = new Interpreter();
        MirModule mir = compiler.precompileToMir(STRING_CONCAT_SOURCE);
        StringAccumLoopPlan plan = null;
        for (MirFunction fn : mir.getTopLevelFunctions()) {
            if ("run".equals(fn.getName())) {
                plan = StringAccumLoopPlan.detect(fn);
            }
        }
        assertTrue(plan != null, "expected string accumulation plan to be detected");
    }

    @Test
    void executeMirHitsStringAccumFastPath() {
        Interpreter compiler = new Interpreter();
        MirModule mir = compiler.precompileToMir(STRING_CONCAT_SOURCE);
        MirInterpreter.resetStringAccumLoopFastHits();
        NovaValue result = new Interpreter().executeMir(mir);
        assertEquals(16890, result.asInt());
        assertTrue(MirInterpreter.getStringAccumLoopPlanHits() > 0,
                "expected executeMir to resolve string accumulation plan");
        assertTrue(MirInterpreter.getStringAccumLoopFastHits() > 0,
                "expected executeMir to hit string accumulation fast path");
    }

    @Test
    void evalHitsStringAccumFastPath() {
        MirInterpreter.resetStringAccumLoopFastHits();
        NovaValue result = new Interpreter().eval(STRING_CONCAT_SOURCE, "string_concat.nova");
        assertEquals(16890, result.asInt());
        assertTrue(MirInterpreter.getStringAccumLoopPlanHits() > 0,
                "expected eval to resolve string accumulation plan");
        assertTrue(MirInterpreter.getStringAccumLoopFastHits() > 0,
                "expected eval pipeline to hit string accumulation fast path");
    }
}
