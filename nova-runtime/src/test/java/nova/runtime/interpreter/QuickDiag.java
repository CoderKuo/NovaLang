package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Interpreter 电商订单 bug 诊断测试。
 *
 * <p>期望结果 41600333，实际得到 1333。
 * totalPrice 始终为 0，itemCount 正确 (1333)。
 * 逐步拆解定位问题根因。</p>
 */
@DisplayName("Interpreter 电商 bug 诊断")
class QuickDiag {

    // ============ Test 1: 构造函数参数字段访问 ============

    @Test
    @DisplayName("1. Product(10, 0).price 是否可访问")
    void testFieldAccess() {
        Interpreter interp = new Interpreter();
        String code =
            "class Product(val price: Int, val category: Int)\n" +
            "val p = Product(10, 0)\n" +
            "p.price";
        NovaValue result = interp.eval(code);
        System.out.println("[Test1] Product(10, 0).price = " + result);
        System.out.println("[Test1] p.category = " + new Interpreter().eval(
            "class Product(val price: Int, val category: Int)\n" +
            "Product(10, 0).category"
        ));
    }

    // ============ Test 2: discountedPrice() 方法调用 ============

    @Test
    @DisplayName("2. discountedPrice() 方法调用")
    void testMethodCall() {
        String classDecl =
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return when (category) {\n" +
            "      0 -> price * 90 / 100\n" +
            "      2 -> price * 80 / 100\n" +
            "      else -> price\n" +
            "    }\n" +
            "  }\n" +
            "}\n";

        // category=0: 应返回 10*90/100 = 9
        NovaValue r0 = new Interpreter().eval(classDecl + "Product(10, 0).discountedPrice()");
        System.out.println("[Test2] Product(10,0).discountedPrice() = " + r0 + " (期望 9)");

        // category=2: 应返回 10*80/100 = 8
        NovaValue r2 = new Interpreter().eval(classDecl + "Product(10, 2).discountedPrice()");
        System.out.println("[Test2] Product(10,2).discountedPrice() = " + r2 + " (期望 8)");

        // category=1: 应返回 10
        NovaValue r1 = new Interpreter().eval(classDecl + "Product(10, 1).discountedPrice()");
        System.out.println("[Test2] Product(10,1).discountedPrice() = " + r1 + " (期望 10)");

        // 更大的 price
        NovaValue r50 = new Interpreter().eval(classDecl + "Product(59, 0).discountedPrice()");
        System.out.println("[Test2] Product(59,0).discountedPrice() = " + r50 + " (期望 53)");
    }

    // ============ Test 3: var 赋值是否正常 ============

    @Test
    @DisplayName("3. var 可变变量赋值")
    void testVarAssignment() {
        // 简单赋值
        NovaValue r1 = new Interpreter().eval("var x = 0\nx = x + 5\nx");
        System.out.println("[Test3] var x=0; x=x+5; x = " + r1 + " (期望 5)");

        // 循环累加
        NovaValue r2 = new Interpreter().eval(
            "var sum = 0\n" +
            "for (i in 0..<10) {\n" +
            "  sum = sum + i\n" +
            "}\n" +
            "sum"
        );
        System.out.println("[Test3] sum(0..9) = " + r2 + " (期望 45)");

        // 循环中累加方法返回值
        String code3 =
            "class Box(val value: Int) {\n" +
            "  fun doubled(): Int { return value * 2 }\n" +
            "}\n" +
            "var total = 0\n" +
            "for (i in 0..<5) {\n" +
            "  val b = Box(i)\n" +
            "  total = total + b.doubled()\n" +
            "}\n" +
            "total";
        NovaValue r3 = new Interpreter().eval(code3);
        System.out.println("[Test3] sum of Box(i).doubled() for i=0..4 = " + r3 + " (期望 20)");
    }

    // ============ Test 4: 完整电商代码 ============

    @Test
    @DisplayName("4. 完整电商订单代码")
    void testFullEcommerce() {
        // 先用普通 Interpreter 计算预期结果
        Interpreter baseInterp = new Interpreter();
        String fullCode =
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return when (category) {\n" +
            "      0 -> price * 90 / 100\n" +
            "      2 -> price * 80 / 100\n" +
            "      else -> price\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "var totalPrice = 0\n" +
            "var itemCount = 0\n" +
            "for (i in 0..<2000) {\n" +
            "  val p = Product(10 + i % 50, i % 3)\n" +
            "  if (p.category != 1) {\n" +
            "    totalPrice = totalPrice + p.discountedPrice()\n" +
            "    itemCount = itemCount + 1\n" +
            "  }\n" +
            "}\n" +
            "val taxedTotal = totalPrice * 108 / 100\n" +
            "taxedTotal * 1000 + itemCount";

        NovaValue baseResult = baseInterp.eval(fullCode);
        System.out.println("[Test4] Interpreter 基准结果 = " + baseResult);

        // 用 Interpreter 执行
        Interpreter hirInterp = new Interpreter();
        NovaValue hirResult = hirInterp.eval(fullCode);
        System.out.println("[Test4] Interpreter 结果   = " + hirResult);
        System.out.println("[Test4] 两者是否一致: " + baseResult.equals(hirResult));

        // 拆解：只看 totalPrice
        NovaValue totalPriceOnly = new Interpreter().eval(
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return when (category) {\n" +
            "      0 -> price * 90 / 100\n" +
            "      2 -> price * 80 / 100\n" +
            "      else -> price\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "var totalPrice = 0\n" +
            "for (i in 0..<2000) {\n" +
            "  val p = Product(10 + i % 50, i % 3)\n" +
            "  if (p.category != 1) {\n" +
            "    totalPrice = totalPrice + p.discountedPrice()\n" +
            "  }\n" +
            "}\n" +
            "totalPrice"
        );
        System.out.println("[Test4] Interpreter totalPrice = " + totalPriceOnly + " (应为非零)");

        // 拆解：只看 itemCount
        NovaValue itemCountOnly = new Interpreter().eval(
            "class Product(val price: Int, val category: Int)\n" +
            "var itemCount = 0\n" +
            "for (i in 0..<2000) {\n" +
            "  val p = Product(10 + i % 50, i % 3)\n" +
            "  if (p.category != 1) {\n" +
            "    itemCount = itemCount + 1\n" +
            "  }\n" +
            "}\n" +
            "itemCount"
        );
        System.out.println("[Test4] Interpreter itemCount  = " + itemCountOnly + " (期望 1333)");

        // 拆解：小规模（3次迭代）检查 totalPrice 累加
        NovaValue smallTest = new Interpreter().eval(
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return when (category) {\n" +
            "      0 -> price * 90 / 100\n" +
            "      2 -> price * 80 / 100\n" +
            "      else -> price\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "var totalPrice = 0\n" +
            "for (i in 0..<3) {\n" +
            "  val p = Product(10 + i % 50, i % 3)\n" +
            "  if (p.category != 1) {\n" +
            "    totalPrice = totalPrice + p.discountedPrice()\n" +
            "  }\n" +
            "}\n" +
            "totalPrice"
        );
        // i=0: Product(10,0) cat!=1, discounted=9, total=9
        // i=1: Product(11,1) cat==1, skip
        // i=2: Product(12,2) cat!=1, discounted=12*80/100=9, total=18
        System.out.println("[Test4] 小规模(3次) totalPrice = " + smallTest + " (期望 18)");

        // 最小复现：单次循环中 var 累加方法返回值
        NovaValue minimal = new Interpreter().eval(
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return price * 90 / 100\n" +
            "  }\n" +
            "}\n" +
            "var totalPrice = 0\n" +
            "val p = Product(10, 0)\n" +
            "totalPrice = totalPrice + p.discountedPrice()\n" +
            "totalPrice"
        );
        System.out.println("[Test4] 单次累加 totalPrice = " + minimal + " (期望 9)");
    }
}
