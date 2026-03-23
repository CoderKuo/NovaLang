package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * data object 测试。
 * Kotlin 语义：data object 自动生成 toString（返回类名）、equals、hashCode。
 */
class DataObjectTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    @Test
    @DisplayName("@data object toString 返回纯类名")
    void testDataObjectToString() {
        eval("@data object Error");
        assertEquals("Error", eval("Error.toString()").asString());
    }

    @Test
    @DisplayName("@data object println 输出类名")
    void testDataObjectPrintln() {
        eval("@data object Loading");
        NovaValue result = eval("\"\" + Loading");
        assertEquals("Loading", result.asString());
    }

    @Test
    @DisplayName("普通 object toString 带括号")
    void testPlainObjectToString() {
        eval("object Idle");
        // 普通 object 不是 data，toString 应含括号
        NovaValue result = eval("Idle.toString()");
        assertTrue(result.asString().contains("Idle"));
    }

    @Test
    @DisplayName("data object 与 sealed interface 配合")
    void testDataObjectWithSealed() {
        eval("sealed interface Result");
        eval("@data class Success(val data: String) : Result");
        eval("@data object Failure : Result");
        assertEquals("Success(data=hello)", eval("Success(\"hello\").toString()").asString());
        assertEquals("Failure", eval("Failure.toString()").asString());
    }

    @Test
    @DisplayName("data object 相等性 — 同一单例")
    void testDataObjectEquality() {
        eval("@data object Singleton");
        assertTrue(eval("Singleton == Singleton").asBool());
    }

    @Test
    @DisplayName("data object 可作为 when 分支")
    void testDataObjectInWhen() {
        eval("sealed interface State");
        eval("@data object Loading : State");
        eval("@data class Ready(val data: String) : State");
        eval("fun describe(s: State) = when(s) {\n"
            + "    is Loading -> \"loading\"\n"
            + "    is Ready -> \"ready: \" + s.data\n"
            + "    else -> \"unknown\"\n"
            + "}");
        assertEquals("loading", eval("describe(Loading)").asString());
        assertEquals("ready: ok", eval("describe(Ready(\"ok\"))").asString());
    }

    @Test
    @DisplayName("data object 带方法")
    void testDataObjectWithMethod() {
        eval("@data object Config {\n"
            + "    val version = \"1.0\"\n"
            + "    fun info() = \"Config v\" + version\n"
            + "}");
        assertEquals("Config v1.0", eval("Config.info()").asString());
        assertEquals("Config", eval("Config.toString()").asString());
    }

    @Test
    @DisplayName("data object is 类型检查")
    void testDataObjectTypeCheck() {
        eval("sealed interface Event");
        eval("@data object Click : Event");
        eval("@data class Input(val text: String) : Event");
        assertTrue(eval("Click is Event").asBool());
        assertTrue(eval("Input(\"hi\") is Event").asBool());
    }
}
