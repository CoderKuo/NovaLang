package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Environment（作用域/环境）单元测试
 */
class EnvironmentTest {

    private Environment env;

    @BeforeEach
    void setUp() {
        env = new Environment();
    }

    // ============ 基本定义和获取 ============

    @Nested
    @DisplayName("变量定义和获取")
    class DefineAndGetTests {

        @Test
        @DisplayName("定义和获取 val")
        void testDefineVal() {
            env.defineVal("x", new NovaInt(42));
            NovaValue result = env.get("x");

            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("定义和获取 var")
        void testDefineVar() {
            env.defineVar("y", new NovaInt(10));
            NovaValue result = env.get("y");

            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("获取未定义变量抛出异常")
        void testGetUndefined() {
            assertThrows(NovaException.class, () -> {
                env.get("undefined");
            });
        }

        @Test
        @DisplayName("重复定义抛出异常")
        void testDuplicateDefinition() {
            env.defineVal("dup", new NovaInt(1));

            assertThrows(NovaException.class, () -> {
                env.defineVal("dup", new NovaInt(2));
            });
        }
    }

    // ============ 变量赋值 ============

    @Nested
    @DisplayName("变量赋值")
    class AssignmentTests {

        @Test
        @DisplayName("var 可以赋值")
        void testVarAssignment() {
            env.defineVar("count", new NovaInt(0));
            env.assign("count", new NovaInt(10));

            assertEquals(10, env.get("count").asInt());
        }

        @Test
        @DisplayName("val 不可赋值")
        void testValCannotAssign() {
            env.defineVal("constant", new NovaInt(100));

            assertThrows(NovaException.class, () -> {
                env.assign("constant", new NovaInt(200));
            });
        }

        @Test
        @DisplayName("赋值未定义变量抛出异常")
        void testAssignUndefined() {
            assertThrows(NovaException.class, () -> {
                env.assign("notExist", new NovaInt(1));
            });
        }
    }

    // ============ 作用域链 ============

    @Nested
    @DisplayName("作用域链")
    class ScopeChainTests {

        @Test
        @DisplayName("子作用域可以访问父作用域变量")
        void testChildAccessParent() {
            env.defineVal("outer", new NovaInt(100));

            Environment child = new Environment(env);
            assertEquals(100, child.get("outer").asInt());
        }

        @Test
        @DisplayName("子作用域可以遮蔽父作用域变量")
        void testShadowing() {
            env.defineVal("x", new NovaInt(1));

            Environment child = new Environment(env);
            child.defineVal("x", new NovaInt(2));

            assertEquals(2, child.get("x").asInt());
            assertEquals(1, env.get("x").asInt());  // 父作用域不变
        }

        @Test
        @DisplayName("子作用域可以修改父作用域的 var")
        void testChildModifyParent() {
            env.defineVar("shared", new NovaInt(0));

            Environment child = new Environment(env);
            child.assign("shared", new NovaInt(99));

            assertEquals(99, env.get("shared").asInt());
        }

        @Test
        @DisplayName("多层作用域")
        void testMultipleLevels() {
            env.defineVal("level0", new NovaInt(0));

            Environment level1 = new Environment(env);
            level1.defineVal("level1", new NovaInt(1));

            Environment level2 = new Environment(level1);
            level2.defineVal("level2", new NovaInt(2));

            // level2 可以访问所有层级
            assertEquals(0, level2.get("level0").asInt());
            assertEquals(1, level2.get("level1").asInt());
            assertEquals(2, level2.get("level2").asInt());

            // level1 不能访问 level2
            assertThrows(NovaException.class, () -> {
                level1.get("level2");
            });
        }
    }

    // ============ REPL 模式 ============

    @Nested
    @DisplayName("REPL 模式")
    class ReplModeTests {

        @Test
        @DisplayName("REPL 模式允许重新定义变量")
        void testReplRedefine() {
            env.setReplMode(true);

            env.defineVal("x", new NovaInt(1));
            env.defineVal("x", new NovaInt(2));  // 不抛出异常

            assertEquals(2, env.get("x").asInt());
        }

        @Test
        @DisplayName("非 REPL 模式不允许重新定义")
        void testNonReplRedefine() {
            env.setReplMode(false);

            env.defineVal("y", new NovaInt(1));

            assertThrows(NovaException.class, () -> {
                env.defineVal("y", new NovaInt(2));
            });
        }
    }

    // ============ 辅助方法 ============

    @Nested
    @DisplayName("辅助方法")
    class UtilityTests {

        @Test
        @DisplayName("isDefined 检查")
        void testIsDefined() {
            env.defineVal("exists", new NovaInt(1));

            assertTrue(env.isDefined("exists"));
            assertFalse(env.isDefined("notExists"));
        }

        @Test
        @DisplayName("isVal 检查")
        void testIsVal() {
            env.defineVal("valVar", new NovaInt(1));
            env.defineVar("varVar", new NovaInt(2));

            assertTrue(env.isVal("valVar"));
            assertFalse(env.isVal("varVar"));
        }
    }
}
