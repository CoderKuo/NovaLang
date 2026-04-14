package com.novalang.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Loop control regression coverage")
class LoopControlRegressionTest {

    @Test
    @DisplayName("interpreter path should not expose internal LoopSignal for break outside loops")
    void interpreterBreakOutsideLoopShouldNotExposeLoopSignal() {
        Throwable thrown = assertThrows(Throwable.class, () -> new Nova().eval("break"));
        assertFalse(containsLoopSignal(thrown),
                "break outside loops currently leaks internal LoopSignal through the interpreter path");
    }

    @Test
    @DisplayName("interpreter path should not expose internal LoopSignal for continue outside loops")
    void interpreterContinueOutsideLoopShouldNotExposeLoopSignal() {
        Throwable thrown = assertThrows(Throwable.class, () -> new Nova().eval("continue"));
        assertFalse(containsLoopSignal(thrown),
                "continue outside loops currently leaks internal LoopSignal through the interpreter path");
    }

    @Test
    @DisplayName("compiled path should not expose internal LoopSignal for break outside loops")
    void compiledBreakOutsideLoopShouldNotExposeLoopSignal() {
        Throwable thrown = assertThrows(Throwable.class,
                () -> new Nova().compileToBytecode("break", "loop-control.nova").run());
        assertFalse(containsLoopSignal(thrown),
                "break outside loops currently leaks internal LoopSignal through the compiled path");
    }

    @Test
    @DisplayName("compiled path should not expose internal LoopSignal for continue outside loops")
    void compiledContinueOutsideLoopShouldNotExposeLoopSignal() {
        Throwable thrown = assertThrows(Throwable.class,
                () -> new Nova().compileToBytecode("continue", "loop-control.nova").run());
        assertFalse(containsLoopSignal(thrown),
                "continue outside loops currently leaks internal LoopSignal through the compiled path");
    }

    private static boolean containsLoopSignal(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof LoopSignal) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
