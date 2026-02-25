package nova.runtime;

import nova.runtime.stdlib.StdlibRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * NovaLang 类型元数据注册表 — 唯一数据源。
 * Interpreter 和 LSP 共同读取此注册表。
 */
public final class NovaTypeRegistry {

    /** 方法/属性的元数据 */
    public static final class MethodInfo {
        public final String name;
        public final int arity;        // -1 = 变参, 0+ = 固定参数
        public final String description;
        public final String returnType; // 返回类型名，null = 同 receiver
        public final boolean isProperty; // true = 零参属性（.length）

        public MethodInfo(String name, int arity, String desc, String ret, boolean prop) {
            this.name = name;
            this.arity = arity;
            this.description = desc;
            this.returnType = ret;
            this.isProperty = prop;
        }

        public static MethodInfo method(String name, int arity, String desc, String ret) {
            return new MethodInfo(name, arity, desc, ret, false);
        }

        public static MethodInfo property(String name, String desc, String ret) {
            return new MethodInfo(name, 0, desc, ret, true);
        }
    }

    /** 全局函数元数据 */
    public static final class FunctionInfo {
        public final String name;
        public final String signature;  // 如 "println(values...)"
        public final int arity;
        public final String description;
        public final String returnType;

        public FunctionInfo(String name, String sig, int arity, String desc, String ret) {
            this.name = name;
            this.signature = sig;
            this.arity = arity;
            this.description = desc;
            this.returnType = ret;
        }
    }

    /** 常量元数据 */
    public static final class ConstantInfo {
        public final String name;
        public final String type;
        public final String description;

        public ConstantInfo(String name, String type, String desc) {
            this.name = name;
            this.type = type;
            this.description = desc;
        }
    }

    /** 内置类型描述 */
    public static final class TypeInfo {
        public final String name;
        public final String description;

        public TypeInfo(String name, String desc) {
            this.name = name;
            this.description = desc;
        }
    }

    // === 类型 → 方法列表 ===
    private static final Map<String, List<MethodInfo>> TYPE_METHODS = new LinkedHashMap<>();
    // === 全局函数 ===
    private static final List<FunctionInfo> FUNCTIONS = new ArrayList<>();
    // === 常量 ===
    private static final List<ConstantInfo> CONSTANTS = new ArrayList<>();
    // === 内置类型描述 ===
    private static final List<TypeInfo> TYPES = new ArrayList<>();

    static {
        registerBuiltinTypeDescriptions();
        registerAnyMethods();
        registerStringMethods();
        registerListMethods();
        registerMapMethods();
        registerRangeMethods();
        registerPairMethods();
        registerArrayMethods();
        registerSetMethods();
        registerCharMethods();
        registerNumberMethods();
        registerResultMethods();
        registerBuiltinFunctions();
        scanStdlibAnnotations();
    }

    // ========== 内置类型描述 ==========

    private static void registerBuiltinTypeDescriptions() {
        TYPES.add(new TypeInfo("Int",     "32 位整数类型，对应 JVM `int`"));
        TYPES.add(new TypeInfo("Long",    "64 位整数类型，对应 JVM `long`"));
        TYPES.add(new TypeInfo("Float",   "32 位浮点类型，对应 JVM `float`"));
        TYPES.add(new TypeInfo("Double",  "64 位浮点类型，对应 JVM `double`"));
        TYPES.add(new TypeInfo("Boolean", "布尔类型，值为 `true` 或 `false`"));
        TYPES.add(new TypeInfo("Char",    "单个字符类型，对应 JVM `char`"));
        TYPES.add(new TypeInfo("Byte",    "8 位整数类型，对应 JVM `byte`"));
        TYPES.add(new TypeInfo("Short",   "16 位整数类型，对应 JVM `short`"));
        TYPES.add(new TypeInfo("String",  "字符串类型，不可变的字符序列"));
        TYPES.add(new TypeInfo("Any",     "所有类型的根类型"));
        TYPES.add(new TypeInfo("Unit",    "无返回值类型，类似 Java 的 `void`"));
        TYPES.add(new TypeInfo("Nothing", "不可达类型，表示永远不会返回的表达式"));
        TYPES.add(new TypeInfo("List",    "有序集合类型，支持 `add`/`remove`/`map`/`filter` 等操作"));
        TYPES.add(new TypeInfo("Map",     "键值对集合类型，支持 `get`/`put`/`keys`/`values` 等操作"));
        TYPES.add(new TypeInfo("Set",     "不重复元素集合类型"));
        TYPES.add(new TypeInfo("Range",   "范围类型，由 `..`（闭区间）或 `..<`（半开区间）创建"));
        TYPES.add(new TypeInfo("Pair",    "二元组类型，由 `to` 中缀运算符或 `Pair(first, second)` 创建。支持 `first`/`second` 属性访问和解构赋值"));
        TYPES.add(new TypeInfo("Array",   "原生数组类型，映射到 JVM 原生数组。`Array<Int>` → `int[]`\n\n创建: `Array<Int>(size)` 或 `Array<Int>(size) { i -> init }`"));
        TYPES.add(new TypeInfo("Result",  "结果类型，表示可能成功或失败的计算。\n\n创建: `Ok(value)` 或 `Err(error)`\n\n使用 `?` 操作符自动传播错误"));
        // Future 类型由 Concurrency.register() → registerFutureType() 注册
    }

    // ========== Any（所有类型通用）==========

    private static void registerAnyMethods() {
        registerType("Any", Arrays.asList(
            MethodInfo.method("toString", 0, "转为字符串", "String"),
            MethodInfo.method("hashCode", 0, "获取哈希值", "Int"),
            MethodInfo.method("equals", 1, "判断是否相等", "Boolean"),
            MethodInfo.method("let", 1, "以 it 引用自身，返回 lambda 结果", null),
            MethodInfo.method("also", 1, "以 it 引用自身，返回自身", null),
            MethodInfo.method("run", 1, "以 this 引用自身，返回 lambda 结果", null),
            MethodInfo.method("apply", 1, "以 this 引用自身，返回自身", null),
            MethodInfo.method("takeIf", 1, "满足条件返回自身，否则 null", null),
            MethodInfo.method("takeUnless", 1, "不满足条件返回自身，否则 null", null)
        ));
    }

    // ========== String ==========

    private static void registerStringMethods() {
        registerType("String", Arrays.asList(
            MethodInfo.property("length", "字符串长度", "Int"),
            MethodInfo.property("isEmpty", "是否为空字符串", "Boolean"),
            MethodInfo.property("isNotEmpty", "是否非空", "Boolean"),
            MethodInfo.property("isBlank", "是否为空白字符串", "Boolean"),
            MethodInfo.method("toUpperCase", 0, "转大写", "String"),
            MethodInfo.method("toLowerCase", 0, "转小写", "String"),
            MethodInfo.method("uppercase", 0, "转为大写", "String"),
            MethodInfo.method("lowercase", 0, "转为小写", "String"),
            MethodInfo.method("trim", 0, "去除首尾空白", "String"),
            MethodInfo.method("trimStart", 0, "去除前导空白", "String"),
            MethodInfo.method("trimEnd", 0, "去除尾部空白", "String"),
            MethodInfo.method("reverse", 0, "反转字符串", "String"),
            MethodInfo.method("capitalize", 0, "首字母大写", "String"),
            MethodInfo.method("decapitalize", 0, "首字母小写", "String"),
            MethodInfo.method("split", 1, "按分隔符拆分", "List"),
            MethodInfo.method("contains", 1, "是否包含子串", "Boolean"),
            MethodInfo.method("startsWith", 1, "是否以指定前缀开头", "Boolean"),
            MethodInfo.method("endsWith", 1, "是否以指定后缀结尾", "Boolean"),
            MethodInfo.method("replace", 2, "替换子串", "String"),
            MethodInfo.method("substring", 1, "截取子串", "String"),
            MethodInfo.method("indexOf", 1, "查找子串位置", "Int"),
            MethodInfo.method("repeat", 1, "重复 N 次", "String"),
            MethodInfo.method("take", 1, "取前 N 个字符", "String"),
            MethodInfo.method("drop", 1, "丢弃前 N 个字符", "String"),
            MethodInfo.method("takeLast", 1, "取后 N 个字符", "String"),
            MethodInfo.method("dropLast", 1, "丢弃后 N 个字符", "String"),
            MethodInfo.method("removePrefix", 1, "移除前缀", "String"),
            MethodInfo.method("removeSuffix", 1, "移除后缀", "String"),
            MethodInfo.method("padStart", 2, "左补齐到指定长度", "String"),
            MethodInfo.method("padEnd", 2, "右补齐到指定长度", "String"),
            MethodInfo.method("toList", 0, "转为字符列表", "List"),
            MethodInfo.method("lines", 0, "按行分割", "List"),
            MethodInfo.method("chars", 0, "转为字符列表", "List"),
            MethodInfo.method("toInt", 0, "解析为整数", "Int"),
            MethodInfo.method("toLong", 0, "解析为长整数", "Long"),
            MethodInfo.method("toDouble", 0, "解析为浮点数", "Double"),
            MethodInfo.method("toBoolean", 0, "解析为布尔值", "Boolean"),
            MethodInfo.method("toIntOrNull", 0, "安全解析为整数", "Int?"),
            MethodInfo.method("toLongOrNull", 0, "安全解析为长整数", "Long?"),
            MethodInfo.method("toDoubleOrNull", 0, "安全解析为浮点数", "Double?"),
            MethodInfo.method("lastIndexOf", 1, "查找子串最后出现位置", "Int"),
            MethodInfo.method("replaceFirst", 2, "替换第一个匹配的子串", "String"),
            MethodInfo.method("matches", 1, "是否匹配正则表达式", "Boolean"),
            MethodInfo.method("format", -1, "格式化字符串", "String"),
            MethodInfo.method("any", 1, "存在符合条件的字符", "Boolean"),
            MethodInfo.method("all", 1, "所有字符都符合条件", "Boolean"),
            MethodInfo.method("none", 1, "没有字符符合条件", "Boolean"),
            MethodInfo.method("count", 1, "符合条件的字符数", "Int")
        ));
    }

    // ========== List ==========

    private static void registerListMethods() {
        registerType("List", Arrays.asList(
            MethodInfo.property("size", "列表大小", "Int"),
            MethodInfo.property("isEmpty", "是否为空列表", "Boolean"),
            MethodInfo.property("isNotEmpty", "是否非空", "Boolean"),
            MethodInfo.property("first", "第一个元素", "$0"),
            MethodInfo.property("last", "最后一个元素", "$0"),
            MethodInfo.method("firstOrNull", 0, "第一个元素或 null", null),
            MethodInfo.method("lastOrNull", 0, "最后一个元素或 null", null),
            MethodInfo.method("single", 0, "获取唯一元素（非恰好 1 个则抛异常）", null),
            MethodInfo.method("singleOrNull", 0, "获取唯一元素或 null", null),
            MethodInfo.method("add", 1, "添加元素", "Boolean"),
            MethodInfo.method("remove", 1, "移除元素", "Boolean"),
            MethodInfo.method("removeAt", 1, "按索引移除", "$0"),
            MethodInfo.method("clear", 0, "清空列表", null),
            MethodInfo.method("contains", 1, "是否包含元素", "Boolean"),
            MethodInfo.method("indexOf", 1, "查找元素位置", "Int"),
            MethodInfo.method("reversed", 0, "反转列表", "List"),
            MethodInfo.method("sorted", 0, "排序列表", "List"),
            MethodInfo.method("sortedDescending", 0, "降序排列", "List"),
            MethodInfo.method("sortedBy", 1, "按选择器排序", "List"),
            MethodInfo.method("distinct", 0, "去重", "List"),
            MethodInfo.method("flatten", 0, "展平嵌套列表", "List"),
            MethodInfo.method("shuffled", 0, "随机排列", "List"),
            MethodInfo.method("joinToString", 1, "用分隔符连接", "String"),
            MethodInfo.method("take", 1, "取前 N 个元素", "List"),
            MethodInfo.method("drop", 1, "丢弃前 N 个元素", "List"),
            MethodInfo.method("takeLast", 1, "取后 N 个元素", "List"),
            MethodInfo.method("dropLast", 1, "丢弃后 N 个元素", "List"),
            MethodInfo.method("forEach", 1, "遍历每个元素", null),
            MethodInfo.method("forEachIndexed", 1, "遍历（含索引）", null),
            MethodInfo.method("map", 1, "映射转换", "List"),
            MethodInfo.method("mapIndexed", 1, "映射转换（含索引）", "List"),
            MethodInfo.method("mapNotNull", 1, "映射转换（跳过 null）", "List"),
            MethodInfo.method("flatMap", 1, "展平映射", "List"),
            MethodInfo.method("filter", 1, "过滤元素", "List"),
            MethodInfo.method("find", 1, "查找第一个匹配元素", null),
            MethodInfo.method("findLast", 1, "查找最后一个匹配元素", null),
            MethodInfo.method("filterNot", 1, "反向过滤", "List"),
            MethodInfo.method("filterIndexed", 1, "按索引过滤", "List"),
            MethodInfo.method("takeWhile", 1, "取满足条件的前缀", "List"),
            MethodInfo.method("dropWhile", 1, "丢弃满足条件的前缀", "List"),
            MethodInfo.method("fold", 2, "带初始值归约", null),
            MethodInfo.method("foldRight", 2, "从右带初始值归约", null),
            MethodInfo.method("zip", 1, "与另一列表配对", "List"),
            MethodInfo.method("associateBy", 1, "按键映射为 Map", "Map"),
            MethodInfo.method("associateWith", 1, "按值映射为 Map", "Map"),
            MethodInfo.method("partition", 1, "按条件分为两组", "List"),
            MethodInfo.method("chunked", 1, "按大小分块", "List"),
            MethodInfo.method("windowed", 1, "滑动窗口", "List"),
            MethodInfo.method("maxBy", 1, "按选择器取最大", null),
            MethodInfo.method("minBy", 1, "按选择器取最小", null),
            MethodInfo.method("sumBy", 1, "按选择器求和", "Number"),
            MethodInfo.method("withIndex", 0, "附加索引", "List"),
            MethodInfo.method("toMap", 0, "Pair 列表转为 Map", "Map"),
            MethodInfo.method("toMutableList", 0, "转为可变列表", "List"),
            MethodInfo.method("unzip", 0, "拆分 Pair 列表", "List"),
            MethodInfo.method("intersect", 1, "集合交集", "List"),
            MethodInfo.method("subtract", 1, "集合差集", "List"),
            MethodInfo.method("union", 1, "集合并集", "List"),
            MethodInfo.method("any", 1, "存在符合条件的元素", "Boolean"),
            MethodInfo.method("all", 1, "所有元素都符合条件", "Boolean"),
            MethodInfo.method("none", 1, "没有元素符合条件", "Boolean"),
            MethodInfo.method("count", 1, "符合条件的元素数", "Int"),
            MethodInfo.method("groupBy", 1, "按键分组", "Map"),
            MethodInfo.method("reduce", 2, "归约", "$0"),
            MethodInfo.method("sum", 0, "求和", "Number"),
            MethodInfo.method("average", 0, "平均值", "Double"),
            MethodInfo.method("maxOrNull", 0, "最大值或 null", null),
            MethodInfo.method("minOrNull", 0, "最小值或 null", null),
            MethodInfo.method("toSet", 0, "转为 Set", "Set")
        ));
    }

    // ========== Map ==========

    private static void registerMapMethods() {
        registerType("Map", Arrays.asList(
            MethodInfo.property("size", "键值对数量", "Int"),
            MethodInfo.property("isEmpty", "是否为空 Map", "Boolean"),
            MethodInfo.property("isNotEmpty", "是否非空", "Boolean"),
            MethodInfo.property("keys", "所有键的集合", "List"),
            MethodInfo.property("values", "所有值的集合", "List"),
            MethodInfo.method("containsKey", 1, "是否包含指定键", "Boolean"),
            MethodInfo.method("containsValue", 1, "是否包含指定值", "Boolean"),
            MethodInfo.method("get", 1, "获取指定键的值", "$1"),
            MethodInfo.method("getOrDefault", 2, "获取指定键的值，不存在返回默认值", "$1"),
            MethodInfo.method("put", 2, "设置键值对", null),
            MethodInfo.method("remove", 1, "移除键值对", "$1"),
            MethodInfo.method("clear", 0, "清空 Map", null),
            MethodInfo.method("entries", 0, "获取所有条目", "List"),
            MethodInfo.method("toList", 0, "转为键值对列表", "List"),
            MethodInfo.method("toMutableMap", 0, "转为可变 Map", "Map"),
            MethodInfo.method("merge", 1, "合并另一个 Map", "Map"),
            MethodInfo.method("getOrPut", 2, "获取或插入默认值", null),
            MethodInfo.method("mapKeys", 1, "映射键", "Map"),
            MethodInfo.method("mapValues", 1, "映射值", "Map"),
            MethodInfo.method("filterKeys", 1, "按键过滤", "Map"),
            MethodInfo.method("filterValues", 1, "按值过滤", "Map"),
            MethodInfo.method("filter", 1, "过滤条目", "Map"),
            MethodInfo.method("forEach", 1, "遍历条目", null),
            MethodInfo.method("map", 1, "映射条目", "List"),
            MethodInfo.method("flatMap", 1, "展平映射条目", "List"),
            MethodInfo.method("any", 1, "存在符合条件的条目", "Boolean"),
            MethodInfo.method("all", 1, "所有条目都符合条件", "Boolean"),
            MethodInfo.method("none", 1, "没有条目符合条件", "Boolean"),
            MethodInfo.method("count", 1, "符合条件的条目数", "Int")
        ));
    }

    // ========== Range ==========

    private static void registerRangeMethods() {
        registerType("Range", Arrays.asList(
            MethodInfo.property("size", "范围大小", "Int"),
            MethodInfo.property("first", "起始值", "Int"),
            MethodInfo.property("last", "结束值", "Int"),
            MethodInfo.method("contains", 1, "是否包含值", "Boolean"),
            MethodInfo.method("toList", 0, "转为列表", "List"),
            MethodInfo.method("forEach", 1, "遍历", null),
            MethodInfo.method("map", 1, "映射", "List"),
            MethodInfo.method("filter", 1, "过滤", "List")
        ));
    }

    // ========== Pair ==========

    private static void registerPairMethods() {
        registerType("Pair", Arrays.asList(
            MethodInfo.property("first", "第一个元素", null),
            MethodInfo.property("second", "第二个元素", null),
            MethodInfo.method("toList", 0, "转为列表", "List")
        ));
    }

    // ========== Array ==========

    private static void registerArrayMethods() {
        registerType("Array", Arrays.asList(
            MethodInfo.property("size", "数组大小", "Int"),
            MethodInfo.method("toList", 0, "转为列表", "List"),
            MethodInfo.method("forEach", 1, "遍历每个元素", null),
            MethodInfo.method("map", 1, "映射转换", "List"),
            MethodInfo.method("filter", 1, "过滤元素", "List"),
            MethodInfo.method("contains", 1, "是否包含元素", "Boolean"),
            MethodInfo.method("indexOf", 1, "查找元素位置", "Int")
        ));
    }

    // ========== Set ==========

    private static void registerSetMethods() {
        registerType("Set", Arrays.asList(
            MethodInfo.property("size", "集合大小", "Int"),
            MethodInfo.property("isEmpty", "是否为空 Set", "Boolean"),
            MethodInfo.property("isNotEmpty", "是否非空", "Boolean"),
            MethodInfo.method("contains", 1, "是否包含元素", "Boolean"),
            MethodInfo.method("add", 1, "添加元素", "Boolean"),
            MethodInfo.method("remove", 1, "移除元素", "Boolean"),
            MethodInfo.method("clear", 0, "清空 Set", null),
            MethodInfo.method("toList", 0, "转为列表", "List"),
            MethodInfo.method("forEach", 1, "遍历每个元素", null),
            MethodInfo.method("map", 1, "映射转换", "Set"),
            MethodInfo.method("filter", 1, "过滤元素", "Set"),
            MethodInfo.method("union", 1, "集合并集", "Set"),
            MethodInfo.method("intersect", 1, "集合交集", "Set"),
            MethodInfo.method("subtract", 1, "集合差集", "Set")
        ));
    }

    // ========== Char ==========

    private static void registerCharMethods() {
        registerType("Char", Arrays.asList(
            MethodInfo.method("isDigit", 0, "是否为数字字符", "Boolean"),
            MethodInfo.method("isLetter", 0, "是否为字母字符", "Boolean"),
            MethodInfo.method("isWhitespace", 0, "是否为空白字符", "Boolean"),
            MethodInfo.method("isUpperCase", 0, "是否为大写字母", "Boolean"),
            MethodInfo.method("isLowerCase", 0, "是否为小写字母", "Boolean"),
            MethodInfo.method("uppercase", 0, "转为大写字符串", "String"),
            MethodInfo.method("lowercase", 0, "转为小写字符串", "String"),
            MethodInfo.method("code", 0, "获取字符的 Unicode 码点", "Int"),
            MethodInfo.method("digitToInt", 0, "数字字符转为整数", "Int")
        ));
    }

    // ========== Number (Int/Long/Double/Float) ==========

    private static void registerNumberMethods() {
        registerType("Number", Arrays.asList(
            MethodInfo.method("toInt", 0, "转换为 Int", "Int"),
            MethodInfo.method("toLong", 0, "转换为 Long", "Long"),
            MethodInfo.method("toDouble", 0, "转换为 Double", "Double"),
            MethodInfo.method("toFloat", 0, "转换为 Float", "Float"),
            MethodInfo.method("abs", 0, "绝对值", "Number"),
            MethodInfo.method("coerceIn", 2, "限制在范围 [min, max] 内", "Number"),
            MethodInfo.method("coerceAtLeast", 1, "不低于最小值", "Number"),
            MethodInfo.method("coerceAtMost", 1, "不高于最大值", "Number"),
            MethodInfo.method("roundToInt", 0, "四舍五入为整数", "Int"),
            MethodInfo.method("isNaN", 0, "是否为 NaN", "Boolean"),
            MethodInfo.method("isInfinite", 0, "是否为无穷大", "Boolean"),
            MethodInfo.method("isFinite", 0, "是否为有限数", "Boolean"),
            MethodInfo.method("downTo", 1, "递减序列", "List"),
            MethodInfo.method("until", 1, "半开区间", "Range")
        ));
    }

    // ========== Result ==========

    private static void registerResultMethods() {
        registerType("Result", Arrays.asList(
            MethodInfo.property("isOk", "是否为成功结果", "Boolean"),
            MethodInfo.property("isErr", "是否为错误结果", "Boolean"),
            MethodInfo.method("getOrNull", 0, "获取成功值或 null", null),
            MethodInfo.method("getOrElse", 1, "获取成功值或默认值", null),
            MethodInfo.method("getOrThrow", 0, "获取成功值或抛出异常", null),
            MethodInfo.method("map", 1, "映射成功值", "Result"),
            MethodInfo.method("mapErr", 1, "映射错误值", "Result"),
            MethodInfo.method("flatMap", 1, "扁平映射成功值", "Result"),
            MethodInfo.method("fold", 2, "折叠", null),
            MethodInfo.method("onSuccess", 1, "成功时执行操作", "Result"),
            MethodInfo.method("onFailure", 1, "失败时执行操作", "Result")
        ));
    }

    // ========== 全局函数 ==========

    private static void registerBuiltinFunctions() {
        // I/O
        registerFunction("println", "println(values...)", -1, "打印多个值并换行，值之间用空格分隔", "Unit");
        registerFunction("print", "print(values...)", -1, "打印多个值不换行，值之间用空格分隔", "Unit");
        registerFunction("readLine", "readLine()", 0, "从标准输入读取一行文本", "String?");
        registerFunction("input", "input(prompt)", 1, "打印提示信息并读取一行输入", "String?");
        // 类型转换
        registerFunction("toString", "toString(value)", 1, "将任意值转换为字符串", "String");
        registerFunction("toInt", "toInt(value)", 1, "将数值或字符串转换为整数", "Int");
        registerFunction("toLong", "toLong(value)", 1, "将数值或字符串转换为长整数", "Long");
        registerFunction("toDouble", "toDouble(value)", 1, "将数值或字符串转换为浮点数", "Double");
        registerFunction("toBoolean", "toBoolean(value)", 1, "将任意值转换为布尔值", "Boolean");
        // 类型检查
        registerFunction("typeof", "typeof(value)", 1, "获取值的类型名称", "String");
        registerFunction("isCallable", "isCallable(value)", 1, "检查值是否为可调用对象", "Boolean");
        // 集合
        registerFunction("listOf", "listOf(elements...)", -1, "创建列表", "List");
        registerFunction("mutableListOf", "mutableListOf(elements...)", -1, "创建可变列表", "List");
        registerFunction("mapOf", "mapOf(pairs...)", -1, "创建 Map（参数为 key1, value1, key2, value2...）", "Map");
        registerFunction("mutableMapOf", "mutableMapOf(pairs...)", -1, "创建可变 Map", "Map");
        registerFunction("setOf", "setOf(elements...)", -1, "创建 Set", "Set");
        registerFunction("mutableSetOf", "mutableSetOf(elements...)", -1, "创建可变 Set", "Set");
        registerFunction("Pair", "Pair(first, second)", 2, "创建二元组", "Pair");
        registerFunction("arrayOf", "arrayOf(elements...)", -1, "创建原生数组", "Array");
        registerFunction("range", "range(start, end)", 2, "创建半开区间整数列表 [start, end)", "List");
        registerFunction("rangeClosed", "rangeClosed(start, end)", 2, "创建闭区间整数列表 [start, end]", "List");
        // 字符串/集合
        registerFunction("len", "len(value)", 1, "获取字符串长度、列表大小或 Map 大小", "Int");
        // 错误处理
        registerFunction("error", "error(message)", 1, "抛出异常", "Nothing");
        // Java 互操作
        registerFunction("javaClass", "javaClass(className)", 1, "获取 Java 类引用", "Any");
        // 注解
        registerFunction("registerAnnotationProcessor", "registerAnnotationProcessor(name, handler)", 2, "注册自定义注解处理器", "Unit");
        // Result
        registerFunction("Ok", "Ok(value)", 1, "创建成功 Result", "Result");
        registerFunction("Err", "Err(error)", 1, "创建错误 Result", "Result");
        registerFunction("runCatching", "runCatching(block)", 1, "执行 block，成功返回 Ok(result)，异常返回 Err(message)", "Result");
        // 反射
        registerFunction("classOf", "classOf(classOrInstance)", 1, "获取类的反射信息", "ClassInfo");
        // 构建器（接收者 Lambda）
        registerFunction("buildString", "buildString(block)", 1, "使用 StringBuilder 构建字符串，lambda 内 this 为 StringBuilder", "String");
        registerFunction("buildList", "buildList(block)", 1, "使用 ArrayList 构建不可变列表，lambda 内 this 为 ArrayList", "List");
        registerFunction("buildMap", "buildMap(block)", 1, "使用 LinkedHashMap 构建不可变 Map，lambda 内 this 为 LinkedHashMap", "Map");
        registerFunction("buildSet", "buildSet(block)", 1, "使用 LinkedHashSet 构建不可变 Set，lambda 内 this 为 LinkedHashSet", "Set");
        // 并发
        registerFunction("async", "async(block)", 1, "异步执行代码块，返回 Future", "Future");
        registerFunction("coroutineScope", "coroutineScope(block)", 1, "创建结构化并发作用域，自动等待所有子任务", "Any");
        registerFunction("supervisorScope", "supervisorScope(block)", 1, "创建监督作用域，子任务失败不影响兄弟任务", "Any");
        // 时间
        registerFunction("sleep", "sleep(millis)", 1, "暂停当前线程指定毫秒数", "Unit");
        registerFunction("measureTimeMillis", "measureTimeMillis(block)", 1, "测量代码块执行时间（毫秒）", "Long");
        registerFunction("measureNanoTime", "measureNanoTime(block)", 1, "测量代码块执行时间（纳秒）", "Long");
        registerFunction("repeat", "repeat(times, action)", 2, "重复执行 action 指定次数，lambda 参数为当前索引", "Unit");
    }

    // === 公开查询 API ===

    public static List<MethodInfo> getMethodsForType(String typeName) {
        return TYPE_METHODS.get(typeName);
    }

    public static List<FunctionInfo> getBuiltinFunctions() {
        return Collections.unmodifiableList(FUNCTIONS);
    }

    public static List<ConstantInfo> getBuiltinConstants() {
        return Collections.unmodifiableList(CONSTANTS);
    }

    public static List<TypeInfo> getBuiltinTypes() {
        return Collections.unmodifiableList(TYPES);
    }

    public static Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(TYPE_METHODS.keySet());
    }

    // === 注册辅助 ===

    public static void registerType(String name, List<MethodInfo> methods) {
        TYPE_METHODS.put(name, Collections.unmodifiableList(methods));
    }

    /** 注册类型描述信息（LSP 补全类型名列表用） */
    public static void registerTypeInfo(TypeInfo typeInfo) {
        TYPES.add(typeInfo);
    }

    private static void registerFunction(String name, String sig, int arity, String desc, String ret) {
        FUNCTIONS.add(new FunctionInfo(name, sig, arity, desc, ret));
    }

    private static void registerConstant(String name, String type, String desc) {
        CONSTANTS.add(new ConstantInfo(name, type, desc));
    }

    /**
     * 自动扫描 StdlibRegistry 中所有 NativeFunctionInfo 和 ConstantInfo，
     * 通过反射读取 @NovaFunction / @NovaConstant 注解，自动注册 LSP 元数据。
     */
    private static void scanStdlibAnnotations() {
        // 扫描原生函数的 @NovaFunction 注解
        for (StdlibRegistry.NativeFunctionInfo nfi : StdlibRegistry.getNativeFunctions()) {
            try {
                String className = nfi.jvmOwner.replace('/', '.');
                Class<?> clazz = Class.forName(className);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals(nfi.jvmMethodName)) continue;
                    NovaFunction ann = m.getAnnotation(NovaFunction.class);
                    if (ann == null) continue;
                    String sig = ann.signature().isEmpty()
                            ? nfi.name + "(" + ")" // fallback
                            : ann.signature();
                    registerFunction(nfi.name, sig, nfi.arity, ann.description(), ann.returnType());
                    break;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        // 扫描常量的 @NovaConstant 注解
        for (StdlibRegistry.ConstantInfo ci : StdlibRegistry.getConstants()) {
            try {
                String className = ci.jvmOwner.replace('/', '.');
                Class<?> clazz = Class.forName(className);
                Field f = clazz.getDeclaredField(ci.jvmFieldName);
                NovaConstant ann = f.getAnnotation(NovaConstant.class);
                if (ann != null) {
                    registerConstant(ci.name, ann.type(), ann.description());
                }
            } catch (ClassNotFoundException | NoSuchFieldException ignored) {
            }
        }
    }

    /**
     * 注册 Future 类型元数据（直接硬编码，避免反射依赖 nova-runtime 内部类）。
     */
    public static void registerFutureType() {
        TYPES.add(new TypeInfo("Future", "异步计算类型，由 async { } 创建。使用 await 或 .get() 获取结果"));
        registerType("Future", Arrays.asList(
            MethodInfo.method("get", 0, "阻塞等待异步结果", null),
            MethodInfo.method("await", 0, "阻塞等待异步结果（get 别名）", null),
            MethodInfo.method("getWithTimeout", 1, "带超时的阻塞等待（毫秒）", null),
            MethodInfo.method("cancel", 0, "取消异步计算", "Boolean"),
            MethodInfo.property("isCancelled", "异步计算是否已取消", "Boolean"),
            MethodInfo.property("isDone", "异步计算是否已完成", "Boolean")
        ));

        // Scope — 结构化并发作用域
        TYPES.add(new TypeInfo("Scope", "结构化并发作用域，由 coroutineScope/supervisorScope 创建"));
        registerType("Scope", Arrays.asList(
            MethodInfo.method("async", 1, "在当前作用域启动异步任务", "Deferred"),
            MethodInfo.method("launch", 1, "在当前作用域启动无返回值任务", "Job"),
            MethodInfo.method("cancel", 0, "取消作用域及所有子任务", "Unit"),
            MethodInfo.property("isActive", "作用域是否活跃", "Boolean"),
            MethodInfo.property("isCancelled", "作用域是否已取消", "Boolean")
        ));

        // Deferred — async 返回值
        TYPES.add(new TypeInfo("Deferred", "异步计算延迟结果，由 scope 内 async { } 创建"));
        registerType("Deferred", Arrays.asList(
            MethodInfo.method("await", 0, "等待异步结果", null),
            MethodInfo.method("cancel", 0, "取消任务", "Boolean"),
            MethodInfo.property("isDone", "任务是否已完成", "Boolean"),
            MethodInfo.property("isCancelled", "任务是否已取消", "Boolean")
        ));

        // Job — launch 返回值
        TYPES.add(new TypeInfo("Job", "结构化并发任务句柄，由 scope 内 launch { } 创建"));
        registerType("Job", Arrays.asList(
            MethodInfo.method("join", 0, "等待任务完成", "Unit"),
            MethodInfo.method("cancel", 0, "取消任务", "Boolean"),
            MethodInfo.property("isActive", "任务是否活跃", "Boolean"),
            MethodInfo.property("isCompleted", "任务是否已完成", "Boolean"),
            MethodInfo.property("isCancelled", "任务是否已取消", "Boolean")
        ));
    }

    /**
     * 通过反射扫描 @NovaType/@NovaMember 注解，自动注册类型元数据。
     * 若类不在 classpath 上（如 LSP 不依赖 nova-runtime），则静默跳过。
     */
    public static void scanAnnotatedClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            NovaType typeAnnotation = clazz.getAnnotation(NovaType.class);
            if (typeAnnotation == null) return;

            String typeName = typeAnnotation.name();
            TYPES.add(new TypeInfo(typeName, typeAnnotation.description()));

            List<MethodInfo> methods = new ArrayList<>();
            for (Method m : clazz.getDeclaredMethods()) {
                NovaMember member = m.getAnnotation(NovaMember.class);
                if (member == null) continue;

                String name = m.getName();
                String ret = member.returnType().isEmpty() ? null : member.returnType();
                // 计算 arity：排除 Interpreter 类型的参数
                int arity = 0;
                for (Class<?> paramType : m.getParameterTypes()) {
                    if (!paramType.getSimpleName().equals("Interpreter")) arity++;
                }
                methods.add(new MethodInfo(name, arity, member.description(), ret, member.property()));
            }
            if (!methods.isEmpty()) {
                registerType(typeName, methods);
            }
        } catch (ClassNotFoundException ignored) {
            // 类不在 classpath 上，静默跳过
        }
    }
}
