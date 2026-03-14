package com.novalang.runtime.interpreter;

import com.novalang.ir.mir.MirModule;
import com.novalang.runtime.NovaValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterpreterMemoizationTest {

    private static final int EXPECTED_FIB20 = 6765;
    private static final int EXPECTED_CALLS = 21;
    private static final int ENCODED_RESULT = EXPECTED_CALLS * 100000 + EXPECTED_CALLS * 1000 + EXPECTED_FIB20;

    private static final String MEMOIZED_FIB_WITH_COUNTER =
            "annotation class memoized\n"
                    + "var calls = 0\n"
                    + "@memoized fun fib(n: Int): Int {\n"
                    + "  calls = calls + 1\n"
                    + "  if (n <= 1) return n\n"
                    + "  return fib(n - 1) + fib(n - 2)\n"
                    + "}\n"
                    + "fun run(): Int {\n"
                    + "  val first = fib(20)\n"
                    + "  val afterFirst = calls\n"
                    + "  val second = fib(20)\n"
                    + "  return afterFirst * 100000 + calls * 1000 + second\n"
                    + "}\n"
                    + "run()";

    @Test
    void evalMemoizedRecursiveFunction() {
        Interpreter interpreter = new Interpreter();
        NovaValue result = interpreter.eval(MEMOIZED_FIB_WITH_COUNTER);
        assertEquals(ENCODED_RESULT, result.asInt());
    }

    @Test
    void executeMirMemoizedRecursiveFunction() {
        Interpreter compiler = new Interpreter();
        MirModule mir = compiler.precompileToMir(MEMOIZED_FIB_WITH_COUNTER);
        NovaValue result = new Interpreter().executeMir(mir);
        assertEquals(ENCODED_RESULT, result.asInt());
    }
}
