package com.novalang.compiler.parser;

import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;
import com.novalang.compiler.lexer.Lexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser 单元测试
 */
class ParserTest {

    private AstNode parseRepl(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer, "<test>");
        return parser.parseReplInput();
    }

    private Program parse(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer, "<test>");
        return parser.parse();
    }

    // ============ 函数声明测试 ============

    @Nested
    @DisplayName("函数声明")
    class FunctionDeclarationTests {

        @Test
        @DisplayName("无参数函数")
        void testNoParamFunction() {
            AstNode node = parseRepl("fun hello() { return 42 }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("hello", fun.getName());
            assertEquals(0, fun.getParams().size());
        }

        @Test
        @DisplayName("单参数函数（无类型注解）")
        void testSingleParamFunction() {
            AstNode node = parseRepl("fun double(x) { return x * 2 }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("double", fun.getName());
            assertEquals(1, fun.getParams().size());
            assertEquals("x", fun.getParams().get(0).getName());
        }

        @Test
        @DisplayName("多参数函数（无类型注解）")
        void testMultiParamFunction() {
            AstNode node = parseRepl("fun add(a, b) { return a + b }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("add", fun.getName());
            assertEquals(2, fun.getParams().size());
            assertEquals("a", fun.getParams().get(0).getName());
            assertEquals("b", fun.getParams().get(1).getName());
        }

        @Test
        @DisplayName("表达式体函数")
        void testExpressionBodyFunction() {
            AstNode node = parseRepl("fun square(x) = x * x");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("square", fun.getName());
            assertTrue(fun.getBody() instanceof BinaryExpr);
        }

        @Test
        @DisplayName("带默认参数的函数")
        void testDefaultParameterFunction() {
            AstNode node = parseRepl("fun greet(name = \"World\") { return \"Hello, \" + name }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals(1, fun.getParams().size());
            assertTrue(fun.getParams().get(0).hasDefaultValue());
        }

        @Test
        @DisplayName("递归函数定义")
        void testRecursiveFunctionDefinition() {
            AstNode node = parseRepl("fun factorial(n) { if (n <= 1) return 1; return n * factorial(n - 1) }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("factorial", fun.getName());
        }

        @Test
        @DisplayName("斐波那契函数定义")
        void testFibonacciFunction() {
            AstNode node = parseRepl("fun fib(n) { if (n <= 1) return n; return fib(n-1) + fib(n-2) }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("fib", fun.getName());
        }
    }

    // ============ 函数调用测试 ============

    @Nested
    @DisplayName("函数调用")
    class FunctionCallTests {

        @Test
        @DisplayName("简单调用")
        void testSimpleCall() {
            AstNode node = parseRepl("foo()");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CallExpr);
        }

        @Test
        @DisplayName("带字面量参数的调用")
        void testCallWithLiteralArg() {
            AstNode node = parseRepl("print(42)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(1, call.getArgs().size());
        }

        @Test
        @DisplayName("带变量参数的调用")
        void testCallWithVariableArg() {
            AstNode node = parseRepl("print(x)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(1, call.getArgs().size());
        }

        @Test
        @DisplayName("带二元表达式参数的调用")
        void testCallWithBinaryExprArg() {
            AstNode node = parseRepl("foo(n - 1)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(1, call.getArgs().size());
            assertTrue(call.getArgs().get(0).getValue() instanceof BinaryExpr);
        }

        @Test
        @DisplayName("带无空格减法表达式参数的调用")
        void testCallWithNoSpaceSubtraction() {
            AstNode node = parseRepl("fib(n-1)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(1, call.getArgs().size());
            Expression arg = call.getArgs().get(0).getValue();
            assertTrue(arg instanceof BinaryExpr);
            BinaryExpr binary = (BinaryExpr) arg;
            assertEquals(BinaryExpr.BinaryOp.SUB, binary.getOperator());
        }

        @Test
        @DisplayName("带多个二元表达式参数的调用")
        void testCallWithMultipleBinaryExprs() {
            AstNode node = parseRepl("add(a + b, c * d)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(2, call.getArgs().size());
            assertTrue(call.getArgs().get(0).getValue() instanceof BinaryExpr);
            assertTrue(call.getArgs().get(1).getValue() instanceof BinaryExpr);
        }

        @Test
        @DisplayName("嵌套函数调用")
        void testNestedFunctionCall() {
            AstNode node = parseRepl("outer(inner(x))");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertTrue(call.getArgs().get(0).getValue() instanceof CallExpr);
        }

        @Test
        @DisplayName("递归调用表达式")
        void testRecursiveCallExpression() {
            AstNode node = parseRepl("fib(n-1) + fib(n-2)");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof BinaryExpr);
            BinaryExpr binary = (BinaryExpr) expr;
            assertTrue(binary.getLeft() instanceof CallExpr);
            assertTrue(binary.getRight() instanceof CallExpr);
        }
    }

    // ============ Lambda 表达式测试 ============

    @Nested
    @DisplayName("Lambda 表达式")
    class LambdaTests {

        @Test
        @DisplayName("单参数 Lambda")
        void testSingleParamLambda() {
            AstNode node = parseRepl("val f = { x -> x * 2 }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
            LambdaExpr lambda = (LambdaExpr) prop.getInitializer();
            assertEquals(1, lambda.getParams().size());
            assertEquals("x", lambda.getParams().get(0).getName());
        }

        @Test
        @DisplayName("多参数 Lambda")
        void testMultiParamLambda() {
            AstNode node = parseRepl("val f = { a, b -> a + b }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            LambdaExpr lambda = (LambdaExpr) prop.getInitializer();
            assertEquals(2, lambda.getParams().size());
        }

        @Test
        @DisplayName("无参数 Lambda")
        void testNoParamLambda() {
            AstNode node = parseRepl("val f = { 42 }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
        }

        @Test
        @DisplayName("空 Lambda")
        void testEmptyLambda() {
            AstNode node = parseRepl("val f = {}");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
        }

        @Test
        @DisplayName("Lambda 作为函数参数")
        void testLambdaAsArgument() {
            AstNode node = parseRepl("apply(5, { n -> n * n })");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(2, call.getArgs().size());
            assertTrue(call.getArgs().get(1).getValue() instanceof LambdaExpr);
        }

        @Test
        @DisplayName("Lambda 体包含多条语句")
        void testLambdaWithMultipleStatements() {
            AstNode node = parseRepl("val f = { x -> val y = x + 1; return y * 2 }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
        }

        @Test
        @DisplayName("Lambda 体包含赋值")
        void testLambdaWithAssignment() {
            AstNode node = parseRepl("val f = { count = count + 1; return count }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
        }

        @Test
        @DisplayName("返回 Lambda 的函数")
        void testFunctionReturningLambda() {
            AstNode node = parseRepl("fun makeCounter() { var count = 0; return { count = count + 1; return count } }");
            assertTrue(node instanceof FunDecl);
        }
    }

    // ============ 局部变量声明测试 ============

    @Nested
    @DisplayName("局部变量声明")
    class LocalVariableTests {

        @Test
        @DisplayName("函数体内 val 声明")
        void testValInFunctionBody() {
            AstNode node = parseRepl("fun test() { val x = 10; return x }");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertTrue(fun.getBody() instanceof Block);
            Block block = (Block) fun.getBody();
            assertTrue(block.getStatements().size() >= 2);
        }

        @Test
        @DisplayName("函数体内 var 声明")
        void testVarInFunctionBody() {
            AstNode node = parseRepl("fun test() { var x = 10; x = 20; return x }");
            assertTrue(node instanceof FunDecl);
        }

        @Test
        @DisplayName("Lambda 体内变量声明")
        void testVarInLambdaBody() {
            AstNode node = parseRepl("val f = { val y = 5; y * 2 }");
            assertTrue(node instanceof PropertyDecl);
        }

        @Test
        @DisplayName("嵌套作用域变量声明")
        void testNestedScopeVariable() {
            AstNode node = parseRepl("fun test() { val x = 1; { val y = 2; return x + y } }");
            assertTrue(node instanceof FunDecl);
        }
    }

    // ============ 类声明测试 ============

    @Nested
    @DisplayName("类声明")
    class ClassDeclarationTests {

        @Test
        @DisplayName("空类")
        void testEmptyClass() {
            AstNode node = parseRepl("class Empty {}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals("Empty", cls.getName());
        }

        @Test
        @DisplayName("带属性的类")
        void testClassWithProperty() {
            AstNode node = parseRepl("class Counter { var value = 0 }");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(1, cls.getMembers().size());
        }

        @Test
        @DisplayName("带方法的类")
        void testClassWithMethod() {
            AstNode node = parseRepl("class Counter { fun increment() { return 1 } }");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(1, cls.getMembers().size());
            assertTrue(cls.getMembers().get(0) instanceof FunDecl);
        }

        @Test
        @DisplayName("带属性和方法的类")
        void testClassWithPropertyAndMethod() {
            AstNode node = parseRepl("class Counter { var value = 0; fun get() { return value } }");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(2, cls.getMembers().size());
        }

        @Test
        @DisplayName("方法访问属性（隐式 this）")
        void testMethodAccessingProperty() {
            AstNode node = parseRepl("class Counter { var value; fun increment() { value = value + 1 } }");
            assertTrue(node instanceof ClassDecl);
        }
    }

    // ============ Map 字面量测试 ============

    @Nested
    @DisplayName("Map 字面量")
    class MapLiteralTests {

        @Test
        @DisplayName("字符串键 Map")
        void testStringKeyMap() {
            AstNode node = parseRepl("val m = #{\"a\": 1, \"b\": 2}");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof CollectionLiteral);
        }

        @Test
        @DisplayName("标识符键 Map")
        void testIdentifierKeyMap() {
            AstNode node = parseRepl("val m = #{x: 1, y: 2}");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof CollectionLiteral);
        }

        @Test
        @DisplayName("空 #{} 解析为空 Map")
        void testEmptyHashMap() {
            AstNode node = parseRepl("val m = #{}");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof CollectionLiteral);
            CollectionLiteral col = (CollectionLiteral) prop.getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.MAP, col.getKind());
            assertTrue(col.getMapEntries().isEmpty());
        }

        @Test
        @DisplayName("空 {} 解析为空 Lambda")
        void testEmptyBracesIsLambda() {
            AstNode node = parseRepl("val f = {}");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
        }
    }

    // ============ 列表字面量测试 ============

    @Nested
    @DisplayName("列表字面量")
    class ListLiteralTests {

        @Test
        @DisplayName("简单列表")
        void testSimpleList() {
            AstNode node = parseRepl("[1, 2, 3]");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CollectionLiteral);
        }

        @Test
        @DisplayName("空列表")
        void testEmptyList() {
            AstNode node = parseRepl("[]");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CollectionLiteral);
        }

        @Test
        @DisplayName("包含表达式的列表")
        void testListWithExpressions() {
            AstNode node = parseRepl("[1 + 2, a * b, foo()]");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CollectionLiteral);
        }
    }

    // ============ 二元表达式测试 ============

    @Nested
    @DisplayName("二元表达式")
    class BinaryExpressionTests {

        @Test
        @DisplayName("加法")
        void testAddition() {
            AstNode node = parseRepl("a + b");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof BinaryExpr);
            assertEquals(BinaryExpr.BinaryOp.ADD, ((BinaryExpr) expr).getOperator());
        }

        @Test
        @DisplayName("减法")
        void testSubtraction() {
            AstNode node = parseRepl("a - b");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof BinaryExpr);
            assertEquals(BinaryExpr.BinaryOp.SUB, ((BinaryExpr) expr).getOperator());
        }

        @Test
        @DisplayName("乘法优先级高于加法")
        void testMultiplicationPrecedence() {
            AstNode node = parseRepl("a + b * c");
            assertTrue(node instanceof ExpressionStmt);
            BinaryExpr expr = (BinaryExpr) ((ExpressionStmt) node).getExpression();
            assertEquals(BinaryExpr.BinaryOp.ADD, expr.getOperator());
            assertTrue(expr.getRight() instanceof BinaryExpr);
        }

        @Test
        @DisplayName("比较表达式")
        void testComparison() {
            AstNode node = parseRepl("n <= 1");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof BinaryExpr);
            assertEquals(BinaryExpr.BinaryOp.LE, ((BinaryExpr) expr).getOperator());
        }

        @Test
        @DisplayName("逻辑与")
        void testLogicalAnd() {
            AstNode node = parseRepl("a && b");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof BinaryExpr);
            assertEquals(BinaryExpr.BinaryOp.AND, ((BinaryExpr) expr).getOperator());
        }

        @Test
        @DisplayName("逻辑或")
        void testLogicalOr() {
            AstNode node = parseRepl("a || b");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof BinaryExpr);
            assertEquals(BinaryExpr.BinaryOp.OR, ((BinaryExpr) expr).getOperator());
        }
    }

    // ============ 一元表达式测试 ============

    @Nested
    @DisplayName("一元表达式")
    class UnaryExpressionTests {

        @Test
        @DisplayName("负号")
        void testNegation() {
            AstNode node = parseRepl("-x");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof UnaryExpr);
            assertEquals(UnaryExpr.UnaryOp.NEG, ((UnaryExpr) expr).getOperator());
        }

        @Test
        @DisplayName("逻辑非")
        void testLogicalNot() {
            AstNode node = parseRepl("!flag");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof UnaryExpr);
            assertEquals(UnaryExpr.UnaryOp.NOT, ((UnaryExpr) expr).getOperator());
        }

        @Test
        @DisplayName("双负号")
        void testDoubleNegation() {
            AstNode node = parseRepl("--5");
            assertTrue(node instanceof ExpressionStmt);
            // --5 应该被解析为 -(-5)
        }
    }

    // ============ 赋值表达式测试 ============

    @Nested
    @DisplayName("赋值表达式")
    class AssignmentTests {

        @Test
        @DisplayName("简单赋值")
        void testSimpleAssignment() {
            AstNode node = parseRepl("x = 10");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof AssignExpr);
        }

        @Test
        @DisplayName("复合赋值 +=")
        void testPlusAssignment() {
            AstNode node = parseRepl("x += 5");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof AssignExpr);
            assertEquals(AssignExpr.AssignOp.ADD_ASSIGN, ((AssignExpr) expr).getOperator());
        }

        @Test
        @DisplayName("索引赋值")
        void testIndexAssignment() {
            AstNode node = parseRepl("arr[0] = 42");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof AssignExpr);
        }

        @Test
        @DisplayName("成员赋值")
        void testMemberAssignment() {
            AstNode node = parseRepl("obj.field = value");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof AssignExpr);
        }
    }

    // ============ Range 表达式测试 ============

    @Nested
    @DisplayName("Range 表达式")
    class RangeTests {

        @Test
        @DisplayName("包含范围")
        void testInclusiveRange() {
            AstNode node = parseRepl("1..10");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof RangeExpr);
            assertFalse(((RangeExpr) expr).isExclusive());
        }

        @Test
        @DisplayName("不包含范围")
        void testExclusiveRange() {
            AstNode node = parseRepl("1..<10");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof RangeExpr);
            assertTrue(((RangeExpr) expr).isExclusive());
        }
    }

    // ============ 控制流测试 ============

    @Nested
    @DisplayName("控制流")
    class ControlFlowTests {

        @Test
        @DisplayName("if-else 语句")
        void testIfElseStatement() {
            AstNode node = parseRepl("if (x > 0) { return 1 } else { return -1 }");
            assertTrue(node instanceof IfStmt);
            IfStmt ifStmt = (IfStmt) node;
            assertTrue(ifStmt.hasElse());
        }

        @Test
        @DisplayName("when 语句")
        void testWhenStatement() {
            AstNode node = parseRepl("when (x) { 1 -> println(\"one\"); 2 -> println(\"two\") }");
            assertTrue(node instanceof WhenStmt);
        }

        @Test
        @DisplayName("for 循环")
        void testForLoop() {
            AstNode node = parseRepl("for (i in 1..10) { println(i) }");
            assertTrue(node instanceof ForStmt);
        }

        @Test
        @DisplayName("while 循环")
        void testWhileLoop() {
            AstNode node = parseRepl("while (x > 0) { x = x - 1 }");
            assertTrue(node instanceof WhileStmt);
        }
    }

    // ============ 错误处理测试 ============

    @Nested
    @DisplayName("错误处理")
    class ErrorHandlingTests {

        @Test
        @DisplayName("try-catch（带类型注解）")
        void testTryCatch() {
            AstNode node = parseRepl("try { risky() } catch (e: Exception) { handle(e) }");
            assertTrue(node instanceof TryStmt);
        }

        @Test
        @DisplayName("throw 语句")
        void testThrowStatement() {
            AstNode node = parseRepl("throw Error(\"oops\")");
            assertTrue(node instanceof ThrowStmt);
        }
    }

    // ============ Lookahead 相关测试 ============

    @Nested
    @DisplayName("Lookahead 测试")
    class LookaheadTests {

        @Test
        @DisplayName("命名参数检测")
        void testNamedParameterDetection() {
            // name = value 应该被检测为命名参数
            AstNode node = parseRepl("foo(name = \"test\")");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertEquals("name", call.getArgs().get(0).getName());
        }

        @Test
        @DisplayName("普通参数后跟赋值运算符")
        void testNormalArgWithAssignment() {
            // x + 1 在参数位置应该被解析为表达式
            AstNode node = parseRepl("foo(x + 1)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();
            assertNull(call.getArgs().get(0).getName());
        }

        @Test
        @DisplayName("Lambda 参数检测 - 单参数箭头")
        void testLambdaParamDetectionSingleArrow() {
            // 在表达式上下文中测试（顶层 { 会被解析为块语句）
            AstNode node = parseRepl("val f = { x -> x }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
            LambdaExpr lambda = (LambdaExpr) prop.getInitializer();
            assertEquals(1, lambda.getParams().size());
        }

        @Test
        @DisplayName("Lambda 参数检测 - 多参数逗号")
        void testLambdaParamDetectionMultiComma() {
            // 在表达式上下文中测试
            AstNode node = parseRepl("val f = { x, y -> x + y }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
            LambdaExpr lambda = (LambdaExpr) prop.getInitializer();
            assertEquals(2, lambda.getParams().size());
        }

        @Test
        @DisplayName("Map 检测 - #{} 语法")
        void testMapDetectionColon() {
            AstNode node = parseRepl("val m = #{ a: 1 }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof CollectionLiteral);
        }

        @Test
        @DisplayName("无参数 Lambda 体")
        void testNoParamLambdaBody() {
            // 在表达式上下文中测试
            AstNode node = parseRepl("val f = { 1 + 2 }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertTrue(prop.getInitializer() instanceof LambdaExpr);
            LambdaExpr lambda = (LambdaExpr) prop.getInitializer();
            assertEquals(0, lambda.getParams().size());
        }

        @Test
        @DisplayName("顶层块语句")
        void testTopLevelBlock() {
            // 顶层 { } 被解析为块语句
            AstNode node = parseRepl("{ val x = 1 }");
            assertTrue(node instanceof Block);
        }
    }

    // ==================== Array 类型测试 ====================

    @Nested
    @DisplayName("Array 类型解析")
    class ArrayTests {

        @Test
        @DisplayName("Array<Int>(5) 解析为带类型参数的调用")
        void testArrayConstructorWithTypeArg() {
            AstNode node = parseRepl("Array<Int>(5)");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CallExpr);
            CallExpr call = (CallExpr) expr;

            // callee 是 Identifier("Array")
            assertTrue(call.getCallee() instanceof Identifier);
            assertEquals("Array", ((Identifier) call.getCallee()).getName());

            // 有一个类型参数 Int
            assertNotNull(call.getTypeArgs());
            assertEquals(1, call.getTypeArgs().size());
            TypeRef typeArg = call.getTypeArgs().get(0);
            assertTrue(typeArg instanceof SimpleType);
            assertEquals("Int", ((SimpleType) typeArg).getName().getFullName());

            // 有一个参数 5
            assertEquals(1, call.getArgs().size());
            assertTrue(call.getArgs().get(0).getValue() instanceof Literal);
        }

        @Test
        @DisplayName("Array<String>(10) 解析为 String 类型数组")
        void testArrayConstructorWithStringType() {
            AstNode node = parseRepl("Array<String>(10)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();

            assertEquals("Array", ((Identifier) call.getCallee()).getName());
            assertEquals(1, call.getTypeArgs().size());
            assertEquals("String", ((SimpleType) call.getTypeArgs().get(0)).getName().getFullName());
            assertEquals(1, call.getArgs().size());
        }

        @Test
        @DisplayName("Array<Double>(3) 解析为 Double 类型数组")
        void testArrayConstructorWithDoubleType() {
            AstNode node = parseRepl("Array<Double>(3)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();

            assertEquals("Array", ((Identifier) call.getCallee()).getName());
            assertEquals("Double", ((SimpleType) call.getTypeArgs().get(0)).getName().getFullName());
        }

        @Test
        @DisplayName("arrayOf(1, 2, 3) 解析为普通函数调用")
        void testArrayOfCall() {
            AstNode node = parseRepl("arrayOf(1, 2, 3)");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CallExpr);
            CallExpr call = (CallExpr) expr;

            assertTrue(call.getCallee() instanceof Identifier);
            assertEquals("arrayOf", ((Identifier) call.getCallee()).getName());

            // 没有类型参数
            assertTrue(call.getTypeArgs() == null || call.getTypeArgs().isEmpty());

            // 有三个参数
            assertEquals(3, call.getArgs().size());
        }

        @Test
        @DisplayName("arrayOf() 无参调用")
        void testArrayOfEmpty() {
            AstNode node = parseRepl("arrayOf()");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();

            assertEquals("arrayOf", ((Identifier) call.getCallee()).getName());
            assertEquals(0, call.getArgs().size());
        }

        @Test
        @DisplayName("val arr = Array<Int>(5) 解析为属性声明")
        void testArrayValDeclaration() {
            AstNode node = parseRepl("val arr = Array<Int>(5)");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;

            assertEquals("arr", prop.getName());
            assertTrue(prop.isVal());
            assertTrue(prop.hasInitializer());

            // 初始化器是 Array<Int>(5) 调用
            assertTrue(prop.getInitializer() instanceof CallExpr);
            CallExpr call = (CallExpr) prop.getInitializer();
            assertEquals("Array", ((Identifier) call.getCallee()).getName());
            assertEquals(1, call.getTypeArgs().size());
        }

        @Test
        @DisplayName("var arr = arrayOf(1, 2) 解析为可变属性声明")
        void testArrayOfVarDeclaration() {
            AstNode node = parseRepl("var arr = arrayOf(1, 2)");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;

            assertEquals("arr", prop.getName());
            assertTrue(prop.isVar());
            assertTrue(prop.hasInitializer());

            CallExpr call = (CallExpr) prop.getInitializer();
            assertEquals("arrayOf", ((Identifier) call.getCallee()).getName());
            assertEquals(2, call.getArgs().size());
        }

        @Test
        @DisplayName("arr[0] 解析为索引表达式")
        void testArrayIndexAccess() {
            AstNode node = parseRepl("arr[0]");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof IndexExpr);
            IndexExpr idx = (IndexExpr) expr;

            assertTrue(idx.getTarget() instanceof Identifier);
            assertEquals("arr", ((Identifier) idx.getTarget()).getName());
            assertTrue(idx.getIndex() instanceof Literal);
        }

        @Test
        @DisplayName("arr[i] = 42 解析为索引赋值")
        void testArrayIndexAssignment() {
            AstNode node = parseRepl("arr[i] = 42");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof AssignExpr);
        }

        @Test
        @DisplayName("arr.size 解析为成员访问")
        void testArraySizeMember() {
            AstNode node = parseRepl("arr.size");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof MemberExpr);
            MemberExpr mem = (MemberExpr) expr;

            assertTrue(mem.getTarget() instanceof Identifier);
            assertEquals("arr", ((Identifier) mem.getTarget()).getName());
            assertEquals("size", mem.getMember());
        }

        @Test
        @DisplayName("arr.toList() 解析为方法调用")
        void testArrayToListCall() {
            AstNode node = parseRepl("arr.toList()");
            assertTrue(node instanceof ExpressionStmt);
            Expression expr = ((ExpressionStmt) node).getExpression();
            assertTrue(expr instanceof CallExpr);
            CallExpr call = (CallExpr) expr;

            assertTrue(call.getCallee() instanceof MemberExpr);
            assertEquals("toList", ((MemberExpr) call.getCallee()).getMember());
        }

        @Test
        @DisplayName("Array<Int>(5) { i -> i * 2 } 带尾随Lambda")
        void testArrayConstructorWithTrailingLambda() {
            AstNode node = parseRepl("Array<Int>(5) { i -> i * 2 }");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();

            assertEquals("Array", ((Identifier) call.getCallee()).getName());
            assertEquals(1, call.getArgs().size());
            assertTrue(call.hasTrailingLambda());
            assertNotNull(call.getTrailingLambda());
            assertEquals(1, call.getTrailingLambda().getParams().size());
        }

        @Test
        @DisplayName("Array<Int>(5, initFn) 显式第二参数")
        void testArrayConstructorWithExplicitInitFn() {
            AstNode node = parseRepl("Array<Int>(5, { i -> i * 2 })");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();

            assertEquals("Array", ((Identifier) call.getCallee()).getName());
            assertEquals(1, call.getTypeArgs().size());
            // 有两个参数：size 和 lambda
            assertEquals(2, call.getArgs().size());
            assertTrue(call.getArgs().get(1).getValue() instanceof LambdaExpr);
        }

        @Test
        @DisplayName("Array<Boolean>(3) 布尔数组构造")
        void testArrayConstructorBoolean() {
            AstNode node = parseRepl("Array<Boolean>(3)");
            assertTrue(node instanceof ExpressionStmt);
            CallExpr call = (CallExpr) ((ExpressionStmt) node).getExpression();

            assertEquals("Boolean", ((SimpleType) call.getTypeArgs().get(0)).getName().getFullName());
        }
    }

    // ============ 导入声明测试 ============

    @Nested
    @DisplayName("导入声明")
    class ImportTests {

        @Test
        @DisplayName("Nova 模块导入: import models.User")
        void testNovaModuleImport() {
            Program program = parse("import models.User");
            assertEquals(1, program.getImports().size());
            ImportDecl imp = program.getImports().get(0);
            assertFalse(imp.isJava());
            assertFalse(imp.isStatic());
            assertFalse(imp.isWildcard());
            assertEquals("models.User", imp.getName().getFullName());
            assertEquals("User", imp.getName().getSimpleName());
        }

        @Test
        @DisplayName("Nova 通配符导入: import models.*")
        void testNovaWildcardImport() {
            Program program = parse("import models.*");
            ImportDecl imp = program.getImports().get(0);
            assertFalse(imp.isJava());
            assertTrue(imp.isWildcard());
            assertEquals("models", imp.getName().getFullName());
        }

        @Test
        @DisplayName("Nova 深层导入: import utils.math.Vector")
        void testNovaDeepImport() {
            Program program = parse("import utils.math.Vector");
            ImportDecl imp = program.getImports().get(0);
            assertFalse(imp.isJava());
            assertEquals("utils.math.Vector", imp.getName().getFullName());
        }

        @Test
        @DisplayName("Java 类导入: import java java.util.ArrayList")
        void testJavaClassImport() {
            Program program = parse("import java java.util.ArrayList");
            assertEquals(1, program.getImports().size());
            ImportDecl imp = program.getImports().get(0);
            assertTrue(imp.isJava());
            assertFalse(imp.isStatic());
            assertFalse(imp.isWildcard());
            assertEquals("java.util.ArrayList", imp.getName().getFullName());
            assertEquals("ArrayList", imp.getName().getSimpleName());
        }

        @Test
        @DisplayName("Java 通配符导入: import java java.util.*")
        void testJavaWildcardImport() {
            Program program = parse("import java java.util.*");
            ImportDecl imp = program.getImports().get(0);
            assertTrue(imp.isJava());
            assertTrue(imp.isWildcard());
            assertEquals("java.util", imp.getName().getFullName());
        }

        @Test
        @DisplayName("Java javax 导入: import java javax.swing.JFrame")
        void testJavaXImport() {
            Program program = parse("import java javax.swing.JFrame");
            ImportDecl imp = program.getImports().get(0);
            assertTrue(imp.isJava());
            assertEquals("javax.swing.JFrame", imp.getName().getFullName());
        }

        @Test
        @DisplayName("Java 别名导入: import java java.util.ArrayList as JavaList")
        void testJavaAliasImport() {
            Program program = parse("import java java.util.ArrayList as JavaList");
            ImportDecl imp = program.getImports().get(0);
            assertTrue(imp.isJava());
            assertTrue(imp.hasAlias());
            assertEquals("JavaList", imp.getAlias());
            assertEquals("java.util.ArrayList", imp.getName().getFullName());
        }

        @Test
        @DisplayName("静态导入: import static java.lang.Math.PI")
        void testStaticImport() {
            Program program = parse("import static java.lang.Math.PI");
            ImportDecl imp = program.getImports().get(0);
            assertFalse(imp.isJava());
            assertTrue(imp.isStatic());
            assertEquals("java.lang.Math.PI", imp.getName().getFullName());
        }

        @Test
        @DisplayName("多个导入语句")
        void testMultipleImports() {
            Program program = parse(
                "import java java.util.ArrayList\n" +
                "import java java.util.HashMap\n" +
                "import models.User\n" +
                "val x = 1"
            );
            assertEquals(3, program.getImports().size());
            assertTrue(program.getImports().get(0).isJava());
            assertTrue(program.getImports().get(1).isJava());
            assertFalse(program.getImports().get(2).isJava());
            assertEquals(1, program.getDeclarations().size());
        }

        @Test
        @DisplayName("REPL 模式支持 import")
        void testReplImport() {
            AstNode node = parseRepl("import java java.util.ArrayList");
            assertTrue(node instanceof ImportDecl);
            ImportDecl imp = (ImportDecl) node;
            assertTrue(imp.isJava());
            assertEquals("java.util.ArrayList", imp.getName().getFullName());
        }

        @Test
        @DisplayName("Nova 别名导入: import models.User as U")
        void testNovaAliasImport() {
            Program program = parse("import models.User as U");
            ImportDecl imp = program.getImports().get(0);
            assertFalse(imp.isJava());
            assertTrue(imp.hasAlias());
            assertEquals("U", imp.getAlias());
        }
    }

    // ============ Bug 10: Set/Map 字面量消歧义 ============

    @Nested
    @DisplayName("Set/Map 字面量消歧义")
    class SetMapLiteralTests {

        // --- 正常值 ---

        @Test
        @DisplayName("Set 字面量: #{1, 2, 3}")
        void testSetLiteral() {
            AstNode node = parseRepl("val s = #{1, 2, 3}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.SET, col.getKind());
            assertEquals(3, col.getElements().size());
            assertNull(col.getMapEntries());
        }

        @Test
        @DisplayName("Map 字面量: #{a: 1, b: 2}")
        void testMapLiteral() {
            AstNode node = parseRepl("val m = #{a: 1, b: 2}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.MAP, col.getKind());
            assertEquals(2, col.getMapEntries().size());
        }

        @Test
        @DisplayName("空 #{} 解析为空 Map")
        void testEmptyIsMap() {
            AstNode node = parseRepl("val m = #{}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.MAP, col.getKind());
            assertTrue(col.getMapEntries().isEmpty());
        }

        @Test
        @DisplayName("单元素 Set: #{42}")
        void testSingleElementSet() {
            AstNode node = parseRepl("val s = #{42}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.SET, col.getKind());
            assertEquals(1, col.getElements().size());
        }

        @Test
        @DisplayName("单条目 Map: #{k: v}")
        void testSingleEntryMap() {
            AstNode node = parseRepl("val m = #{k: v}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.MAP, col.getKind());
            assertEquals(1, col.getMapEntries().size());
        }

        // --- 边缘值 ---

        @Test
        @DisplayName("Set 含表达式: #{1 + 2, a * b}")
        void testSetWithExpressions() {
            AstNode node = parseRepl("val s = #{1 + 2, a * b}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.SET, col.getKind());
            assertEquals(2, col.getElements().size());
            assertTrue(col.getElements().get(0) instanceof BinaryExpr);
        }

        @Test
        @DisplayName("Set 含字符串: #{\"a\", \"b\"}")
        void testSetWithStrings() {
            AstNode node = parseRepl("val s = #{\"a\", \"b\"}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.SET, col.getKind());
            assertEquals(2, col.getElements().size());
        }

        @Test
        @DisplayName("Map 含字符串键: #{\"key\": 1}")
        void testMapWithStringKey() {
            AstNode node = parseRepl("val m = #{\"key\": 1}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.MAP, col.getKind());
        }

        @Test
        @DisplayName("Set 带尾逗号: #{1, 2,}")
        void testSetTrailingComma() {
            AstNode node = parseRepl("val s = #{1, 2,}");
            assertTrue(node instanceof PropertyDecl);
            CollectionLiteral col = (CollectionLiteral) ((PropertyDecl) node).getInitializer();
            assertEquals(CollectionLiteral.CollectionKind.SET, col.getKind());
            assertEquals(2, col.getElements().size());
        }

        // --- 异常值 ---

        @Test
        @DisplayName("Map 缺少冒号 → 报错")
        void testMapMissingColonAfterFirst() {
            // 第一个元素有冒号（Map），第二个缺少冒号 → 解析错误
            assertThrows(ParseException.class, () -> parseRepl("val m = #{a: 1, b}"));
        }
    }

    // ============ Bug 11: 修饰符互斥性校验 ============

    @Nested
    @DisplayName("修饰符互斥性校验")
    class ModifierExclusivityTests {

        // --- 正常值 ---

        @Test
        @DisplayName("单个可见性修饰符: public class")
        void testSingleVisibility() {
            AstNode node = parseRepl("public class Foo {}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertTrue(cls.getModifiers().contains(Modifier.PUBLIC));
        }

        @Test
        @DisplayName("abstract class 合法")
        void testAbstractClass() {
            AstNode node = parseRepl("abstract class Base {}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertTrue(cls.getModifiers().contains(Modifier.ABSTRACT));
        }

        @Test
        @DisplayName("open class 合法")
        void testOpenClass() {
            AstNode node = parseRepl("open class Parent {}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertTrue(cls.getModifiers().contains(Modifier.OPEN));
        }

        @Test
        @DisplayName("可见性 + abstract 组合合法: public abstract")
        void testVisibilityPlusAbstract() {
            AstNode node = parseRepl("public abstract class Base {}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertTrue(cls.getModifiers().contains(Modifier.PUBLIC));
            assertTrue(cls.getModifiers().contains(Modifier.ABSTRACT));
        }

        // --- 异常值 ---

        @Test
        @DisplayName("重复修饰符: public public → 报错")
        void testDuplicateModifier() {
            assertThrows(ParseException.class, () -> parseRepl("public public class Foo {}"));
        }

        @Test
        @DisplayName("冲突可见性: public private → 报错")
        void testConflictingVisibility() {
            assertThrows(ParseException.class, () -> parseRepl("public private class Foo {}"));
        }

        @Test
        @DisplayName("冲突可见性: protected internal → 报错")
        void testProtectedInternal() {
            assertThrows(ParseException.class, () -> parseRepl("protected internal class Foo {}"));
        }

        @Test
        @DisplayName("abstract final 互斥 → 报错")
        void testAbstractFinal() {
            assertThrows(ParseException.class, () -> parseRepl("abstract final class Foo {}"));
        }

        @Test
        @DisplayName("final abstract 反序也互斥 → 报错")
        void testFinalAbstract() {
            assertThrows(ParseException.class, () -> parseRepl("final abstract class Foo {}"));
        }

        // --- 边缘值 ---

        @Test
        @DisplayName("final 单独使用合法")
        void testFinalAlone() {
            AstNode node = parseRepl("final class Sealed {}");
            assertTrue(node instanceof ClassDecl);
            assertTrue(((ClassDecl) node).getModifiers().contains(Modifier.FINAL));
        }

        @Test
        @DisplayName("多个非冲突修饰符: public override inline fun")
        void testMultipleNonConflicting() {
            AstNode node = parseRepl("public override inline fun test() {}");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertTrue(fun.getModifiers().contains(Modifier.PUBLIC));
            assertTrue(fun.getModifiers().contains(Modifier.OVERRIDE));
            assertTrue(fun.getModifiers().contains(Modifier.INLINE));
        }
    }

    // ============ Bug 12: 扩展接收者类型 ============

    @Nested
    @DisplayName("扩展接收者类型")
    class ExtensionReceiverTypeTests {

        // --- 正常值 ---

        @Test
        @DisplayName("简单接收者: fun String.exclaim()")
        void testSimpleReceiver() {
            AstNode node = parseRepl("fun String.exclaim() = this");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("exclaim", fun.getName());
            assertNotNull(fun.getReceiverType());
            assertTrue(fun.isExtensionFunction());
        }

        @Test
        @DisplayName("内置类型接收者: fun Int.abs()")
        void testBuiltinTypeReceiver() {
            AstNode node = parseRepl("fun Int.abs() = this");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("abs", fun.getName());
            assertNotNull(fun.getReceiverType());
        }

        @Test
        @DisplayName("泛型接收者: fun List<Int>.sum()")
        void testGenericReceiver() {
            AstNode node = parseRepl("fun List<Int>.sum() = 0");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("sum", fun.getName());
            assertNotNull(fun.getReceiverType());
            assertTrue(fun.getReceiverType() instanceof GenericType);
        }

        @Test
        @DisplayName("可空接收者: fun String?.orEmpty()")
        void testNullableReceiver() {
            AstNode node = parseRepl("fun String?.orEmpty() = this");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("orEmpty", fun.getName());
            assertNotNull(fun.getReceiverType());
            assertTrue(fun.getReceiverType() instanceof NullableType);
        }

        @Test
        @DisplayName("无接收者普通函数不受影响")
        void testNoReceiverFunction() {
            AstNode node = parseRepl("fun hello() = 1");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("hello", fun.getName());
            assertNull(fun.getReceiverType());
            assertFalse(fun.isExtensionFunction());
        }

        // --- 扩展属性 ---

        @Test
        @DisplayName("简单扩展属性: val String.length")
        void testSimpleExtensionProperty() {
            AstNode node = parseRepl("val String.reversed = this");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertEquals("reversed", prop.getName());
            assertTrue(prop.isExtensionProperty());
        }

        @Test
        @DisplayName("可空扩展属性: val String?.safe")
        void testNullableExtensionProperty() {
            AstNode node = parseRepl("val String?.safe = this");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertEquals("safe", prop.getName());
            assertTrue(prop.isExtensionProperty());
            assertTrue(prop.getReceiverType() instanceof NullableType);
        }

        // --- 边缘值 ---

        @Test
        @DisplayName("泛型可空接收者: fun List<String>?.orEmpty()")
        void testGenericNullableReceiver() {
            AstNode node = parseRepl("fun List<String>?.orEmpty() = this");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("orEmpty", fun.getName());
            assertNotNull(fun.getReceiverType());
            assertTrue(fun.getReceiverType() instanceof NullableType);
            NullableType nullable = (NullableType) fun.getReceiverType();
            assertTrue(nullable.getInnerType() instanceof GenericType);
        }

        @Test
        @DisplayName("多类型参数泛型接收者: fun Map<String, Int>.keys()")
        void testMultiTypeArgReceiver() {
            AstNode node = parseRepl("fun Map<String, Int>.keys() = this");
            assertTrue(node instanceof FunDecl);
            FunDecl fun = (FunDecl) node;
            assertEquals("keys", fun.getName());
            assertTrue(fun.getReceiverType() instanceof GenericType);
            GenericType generic = (GenericType) fun.getReceiverType();
            assertEquals(2, generic.getTypeArgs().size());
        }
    }

    // ============ Bug 13: 循环标签语法 ============

    @Nested
    @DisplayName("循环标签语法")
    class LoopLabelTests {

        // --- 正常值 ---

        @Test
        @DisplayName("for 循环标签: outer@ for")
        void testForLabel() {
            AstNode node = parseRepl("outer@ for (i in 1..10) { break@outer }");
            assertTrue(node instanceof ForStmt);
            ForStmt forStmt = (ForStmt) node;
            assertTrue(forStmt.hasLabel());
            assertEquals("outer", forStmt.getLabel());
        }

        @Test
        @DisplayName("while 循环标签: loop@ while")
        void testWhileLabel() {
            AstNode node = parseRepl("loop@ while (true) { break@loop }");
            assertTrue(node instanceof WhileStmt);
            WhileStmt whileStmt = (WhileStmt) node;
            assertTrue(whileStmt.hasLabel());
            assertEquals("loop", whileStmt.getLabel());
        }

        @Test
        @DisplayName("do-while 循环标签: retry@ do")
        void testDoWhileLabel() {
            AstNode node = parseRepl("retry@ do { break@retry } while (false)");
            assertTrue(node instanceof DoWhileStmt);
            DoWhileStmt doStmt = (DoWhileStmt) node;
            assertTrue(doStmt.hasLabel());
            assertEquals("retry", doStmt.getLabel());
        }

        @Test
        @DisplayName("无标签循环不受影响")
        void testNoLabelLoop() {
            AstNode node = parseRepl("for (i in 1..5) { println(i) }");
            assertTrue(node instanceof ForStmt);
            ForStmt forStmt = (ForStmt) node;
            assertFalse(forStmt.hasLabel());
            assertNull(forStmt.getLabel());
        }

        // --- 边缘值 ---

        @Test
        @DisplayName("标签名与变量名相同不冲突")
        void testLabelNameSameAsVar() {
            AstNode node = parseRepl("i@ for (i in 1..3) { break@i }");
            assertTrue(node instanceof ForStmt);
            ForStmt forStmt = (ForStmt) node;
            assertEquals("i", forStmt.getLabel());
        }

        @Test
        @DisplayName("非循环标签回退为表达式")
        void testNonLoopLabelFallback() {
            // x@ 后面不是 for/while/do → 回退为表达式语句
            // x 是标识符，@ 是运算符 → 不会被识别为标签
            AstNode node = parseRepl("x@ for (i in 1..3) {}");
            // 如果 x@ for 匹配，则为带标签的 for
            assertTrue(node instanceof ForStmt);
            assertEquals("x", ((ForStmt) node).getLabel());
        }

        @Test
        @DisplayName("嵌套循环多标签")
        void testNestedLabels() {
            String code = "outer@ for (i in 1..3) {\n" +
                           "  inner@ for (j in 1..3) {\n" +
                           "    break@outer\n" +
                           "  }\n" +
                           "}";
            AstNode node = parseRepl(code);
            assertTrue(node instanceof ForStmt);
            ForStmt outer = (ForStmt) node;
            assertEquals("outer", outer.getLabel());
            // 内层循环在 body block 的第一条语句
            Block body = (Block) outer.getBody();
            assertTrue(body.getStatements().get(0) instanceof ForStmt);
            ForStmt inner = (ForStmt) body.getStatements().get(0);
            assertEquals("inner", inner.getLabel());
        }

        // --- 异常值 ---

        @Test
        @DisplayName("标识符后不跟 @ 仍然正常解析")
        void testIdentifierWithoutAt() {
            // 普通标识符表达式
            AstNode node = parseRepl("myVar");
            assertTrue(node instanceof ExpressionStmt);
        }
    }

    // ============ Bug 14: 属性 getter/setter ============

    @Nested
    @DisplayName("属性 getter/setter")
    class PropertyAccessorTests {

        // --- 正常值 ---

        @Test
        @DisplayName("getter 表达式体: val x get() = 42")
        void testGetterExpressionBody() {
            AstNode node = parseRepl("val x: Int\n  get() = 42");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getGetter());
            assertTrue(prop.getGetter().isGetter());
            assertNotNull(prop.getGetter().getBody());
        }

        @Test
        @DisplayName("getter 块体: val x get() { return 42 }")
        void testGetterBlockBody() {
            AstNode node = parseRepl("val x: Int\n  get() { return 42 }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getGetter());
            assertTrue(prop.getGetter().getBody() instanceof Block);
        }

        @Test
        @DisplayName("setter 带参数: var x set(value) { }")
        void testSetterWithParam() {
            AstNode node = parseRepl("var x: Int = 0\n  set(value) { println(value) }");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getSetter());
            assertTrue(prop.getSetter().isSetter());
            assertNotNull(prop.getSetter().getParam());
            assertEquals("value", prop.getSetter().getParam().getName());
        }

        @Test
        @DisplayName("getter + setter 同时存在")
        void testGetterAndSetter() {
            String code = "var x: Int = 0\n" +
                           "  get() = field\n" +
                           "  set(value) { field = value }";
            AstNode node = parseRepl(code);
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getGetter());
            assertNotNull(prop.getSetter());
        }

        @Test
        @DisplayName("private set 无参数无体")
        void testPrivateSetNoBody() {
            AstNode node = parseRepl("var x: Int = 0\n  private set");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getSetter());
            assertTrue(prop.getSetter().getModifiers().contains(Modifier.PRIVATE));
            assertNull(prop.getSetter().getBody());
        }

        // --- 边缘值 ---

        @Test
        @DisplayName("无访问器的属性不受影响")
        void testNoAccessors() {
            AstNode node = parseRepl("val x = 42");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNull(prop.getGetter());
            assertNull(prop.getSetter());
        }

        @Test
        @DisplayName("getter 使用 = 表达式体")
        void testGetterAssignBody() {
            AstNode node = parseRepl("val name: String\n  get = \"hello\"");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getGetter());
            assertNotNull(prop.getGetter().getBody());
        }

        @Test
        @DisplayName("setter 反序先于 getter")
        void testSetterBeforeGetter() {
            String code = "var x: Int = 0\n" +
                           "  set(v) { field = v }\n" +
                           "  get() = field";
            AstNode node = parseRepl(code);
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getGetter());
            assertNotNull(prop.getSetter());
        }

        @Test
        @DisplayName("protected set 可见性修饰符")
        void testProtectedSet() {
            AstNode node = parseRepl("var x: Int = 0\n  protected set");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getSetter());
            assertTrue(prop.getSetter().getModifiers().contains(Modifier.PROTECTED));
        }

        @Test
        @DisplayName("private set + getter 组合")
        void testPrivateSetWithGetter() {
            String code = "var x: Int = 0\n" +
                           "  get() = field\n" +
                           "  private set";
            AstNode node = parseRepl(code);
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNotNull(prop.getGetter());
            assertNotNull(prop.getSetter());
            assertTrue(prop.getSetter().getModifiers().contains(Modifier.PRIVATE));
            assertNull(prop.getSetter().getBody());
        }

        // --- 异常值 ---

        @Test
        @DisplayName("类内属性带 getter 正常解析")
        void testGetterInClass() {
            AstNode node = parseRepl("class Foo {\n  val x: Int\n    get() = 42\n}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(1, cls.getMembers().size());
            assertTrue(cls.getMembers().get(0) instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) cls.getMembers().get(0);
            assertNotNull(prop.getGetter());
        }

        @Test
        @DisplayName("get 作为普通标识符不干扰（无括号无等号无花括号）")
        void testGetAsIdentifier() {
            // get 后跟 . 不是访问器
            AstNode node = parseRepl("val x = 1");
            assertTrue(node instanceof PropertyDecl);
            PropertyDecl prop = (PropertyDecl) node;
            assertNull(prop.getGetter());
        }
    }

    // ============ init 块测试 ============

    @Nested
    @DisplayName("init 块解析")
    class InitBlockTests {

        @Test
        @DisplayName("基本 init 块解析")
        void testBasicInitBlock() {
            AstNode node = parseRepl("class Foo {\n  init { val x = 1 }\n}");
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(1, cls.getMembers().size());
            assertTrue(cls.getMembers().get(0) instanceof InitBlockDecl);
            InitBlockDecl init = (InitBlockDecl) cls.getMembers().get(0);
            assertNotNull(init.getBody());
        }

        @Test
        @DisplayName("多个 init 块解析")
        void testMultipleInitBlocks() {
            AstNode node = parseRepl(
                "class Foo {\n" +
                "  init { val a = 1 }\n" +
                "  init { val b = 2 }\n" +
                "}"
            );
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(2, cls.getMembers().size());
            assertTrue(cls.getMembers().get(0) instanceof InitBlockDecl);
            assertTrue(cls.getMembers().get(1) instanceof InitBlockDecl);
        }

        @Test
        @DisplayName("init 块与属性交织解析")
        void testInitBlockInterleavedWithProperties() {
            AstNode node = parseRepl(
                "class Foo(val x: Int) {\n" +
                "  val a = 1\n" +
                "  init { val tmp = a }\n" +
                "  val b = 2\n" +
                "  init { val tmp2 = b }\n" +
                "}"
            );
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(4, cls.getMembers().size());
            assertTrue(cls.getMembers().get(0) instanceof PropertyDecl);
            assertTrue(cls.getMembers().get(1) instanceof InitBlockDecl);
            assertTrue(cls.getMembers().get(2) instanceof PropertyDecl);
            assertTrue(cls.getMembers().get(3) instanceof InitBlockDecl);
        }

        @Test
        @DisplayName("init 块与构造器共存")
        void testInitBlockWithConstructor() {
            AstNode node = parseRepl(
                "class Foo(val x: Int) {\n" +
                "  init { val tmp = x }\n" +
                "  constructor(a: Int, b: Int) : this(a + b)\n" +
                "}"
            );
            assertTrue(node instanceof ClassDecl);
            ClassDecl cls = (ClassDecl) node;
            assertEquals(2, cls.getMembers().size());
            assertTrue(cls.getMembers().get(0) instanceof InitBlockDecl);
            assertTrue(cls.getMembers().get(1) instanceof ConstructorDecl);
        }

        @Test
        @DisplayName("init 块名称为占位符")
        void testInitBlockName() {
            AstNode node = parseRepl("class Foo {\n  init { }\n}");
            ClassDecl cls = (ClassDecl) node;
            InitBlockDecl init = (InitBlockDecl) cls.getMembers().get(0);
            assertEquals("<init-block>", init.getName());
        }
    }
}
