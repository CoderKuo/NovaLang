package com.novalang.runtime.interpreter;

import com.novalang.ir.mir.MirModule;
import com.novalang.runtime.NovaValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterpreterPreparedMirTest {

    @Test
    void preparedMirCanBeReusedAcrossExecutions() {
        String source = "var sum = 0\n"
                + "for (i in 0..<100) {\n"
                + "  sum = sum + i\n"
                + "}\n"
                + "sum";

        Interpreter compiler = new Interpreter();
        MirModule mir = compiler.precompileToMir(source);
        Interpreter interpreter = new Interpreter();
        Interpreter.PreparedMirModule prepared = interpreter.prepareMirForReuse(mir);

        NovaValue first = interpreter.executePreparedMir(prepared);
        NovaValue second = interpreter.executePreparedMir(prepared);

        assertEquals(4950, first.asInt());
        assertEquals(4950, second.asInt());
    }

    @Test
    void preparedMirSupportsThreeParamTailRecursion() {
        String source = "fun fibTail(n: Int, a: Int, b: Int): Int = "
                + "  if (n == 0) a else fibTail(n - 1, b, a + b)\n"
                + "fibTail(20, 0, 1)";

        Interpreter evalInterpreter = new Interpreter();
        int expected = evalInterpreter.eval(source).asInt();

        Interpreter compiler = new Interpreter();
        MirModule mir = compiler.precompileToMir(source);
        Interpreter interpreter = new Interpreter();
        Interpreter.PreparedMirModule prepared = interpreter.prepareMirForReuse(mir);

        assertEquals(expected, interpreter.executePreparedMir(prepared).asInt());
        assertEquals(expected, interpreter.executePreparedMir(prepared).asInt());
    }
}
