# Nova Benchmarks — JVM 脚本引擎性能对比

基于 [JMH](https://openjdk.org/projects/code-tools/jmh/) 的多引擎性能基准测试，对比 NovaLang 与主流 JVM 脚本引擎的执行性能。

## 测试环境

- **JDK**: OpenJDK 21.0.8+9-LTS (Zulu)
- **JMH**: 1.37
- **OS**: Windows 11
- **配置**: Fork=1, Warmup=5×500ms, Measurement=8×500ms, -Xms512m -Xmx512m

## 对比引擎

| 引擎 | 版本 | 说明 |
|------|------|------|
| **Java Native** | JDK 21 | 纯 Java 基线，JIT 充分优化 |
| **Nova Compiled** | 0.1.2 | Nova 编译为 JVM 字节码执行 |
| **Nova Eval** | 0.1.2 | Nova MIR 解释器执行 |
| **Groovy** | 4.0.23 | GroovyShell 解析/执行 |
| **Nashorn** | 15.4 | OpenJDK Nashorn JavaScript 引擎 |
| **GraalJS** | 24.1.1 | GraalVM Polyglot JavaScript 引擎 |
| **JEXL** | 3.4.0 | Apache Commons JEXL 表达式引擎 |

## 测试场景

| 场景 | 说明 |
|------|------|
| `arith_loop` | 20000 次整数算术循环 |
| `call_loop` | 50000 次小函数调用循环 |
| `object_loop` | 5000 次对象创建 + 方法调用 |
| `branch_loop` | 20000 次分支密集分类循环 |
| `string_concat` | 3000 次字符串拼接 |
| `list_sum` | 3000 元素列表构建 + 索引求和 |
| `fib_recursion` | 递归 Fibonacci（fib(18)~fib(22) × 20 次） |

## 预编译 / 热路径执行性能

> 单位：µs/op（越低越好）

| 场景 | Java Native | Nova Compiled | Groovy Parsed | Nashorn Compiled | GraalJS Warmed | JEXL Script |
|------|----------:|-------------:|-------------:|----------------:|--------------:|-----------:|
| arith_loop | **3.6** | **3.6** | 60.2 | 48.3 | 273.6 | 576.1 |
| call_loop | **9.2** | **9.1** | 144.9 | 1041.9 | 2456.2 | 10593.1 |
| object_loop | **0.9** | 2.4 | 35.1 | 443.2 | 1561.2 | 413.8 |
| branch_loop | **19.9** | **19.6** | 157.0 | 918.2 | 2127.3 | 5369.0 |
| string_concat | **11.8** | **11.9** | 13475.0 | 89.2 | 128.7 | 3357.9 |
| list_sum | **8.9** | 13.8 | 36.9 | 79.7 | 228.9 | 207.6 |
| fib_recursion | **390.3** | **366.5** | 801.8 | 5472.7 | 65410.1 | 109710.5 |

### 相对 Java Native 的倍率

| 场景 | Nova Compiled | Groovy Parsed | Nashorn Compiled | GraalJS Warmed | JEXL Script |
|------|-------------:|-------------:|----------------:|--------------:|-----------:|
| arith_loop | 1.0x | 16.8x | 13.4x | 76.2x | 160.4x |
| call_loop | 1.0x | 15.8x | 113.5x | 267.6x | 1154.3x |
| object_loop | 2.7x | 39.9x | 504.2x | 1776.1x | 470.7x |
| branch_loop | 1.0x | 7.9x | 46.1x | 106.8x | 269.6x |
| string_concat | 1.0x | 1142.3x | 7.6x | 10.9x | 284.6x |
| list_sum | 1.6x | 4.1x | 9.0x | 25.7x | 23.3x |
| fib_recursion | 0.9x | 2.1x | 14.0x | 167.6x | 281.1x |

## 解释执行 / 冷启动性能

> 单位：µs/op（越低越好）

| 场景 | Nova Eval | JEXL Eval | Nashorn Eval | GraalJS Eval | Groovy Eval |
|------|--------:|---------:|-----------:|-----------:|-----------:|
| arith_loop | **420** | 650 | 1197 | 708 | 10388 |
| call_loop | **2331** | 11186 | 3863 | 3358 | 16639 |
| object_loop | **376** | 467 | 3915 | 1655 | 11611 |
| branch_loop | **2217** | 6267 | 2955 | 2850 | 22075 |
| string_concat | **47** | 3649 | 1083 | 548 | 20047 |
| list_sum | **189** | 292 | 1480 | 706 | 6017 |
| fib_recursion | 25363 | 149617 | **9361** | 52233 | 52352 |

## 编译速度

> 单位：µs/op（越低越好）

| 场景 | Nova Compile | Nashorn Compile |
|------|------------:|---------------:|
| arith_loop | **56.6** | 557.6 |
| call_loop | **62.2** | 571.5 |
| object_loop | **144.9** | 735.7 |
| branch_loop | **84.0** | 773.3 |
| string_concat | **65.8** | 560.7 |
| list_sum | **79.3** | 564.5 |
| fib_recursion | **76.8** | 573.7 |

Nova 编译速度比 Nashorn 快 **7~10 倍**。

## 关键结论

### Nova Compiled 已接近 Java Native 性能

- `arith_loop` / `call_loop` / `branch_loop` / `string_concat` / `fib_recursion`：与 Java Native 几乎持平（< 5% 差距）
- 编译后执行性能**远超所有其他脚本引擎**

### Nova Eval（MIR 解释器）表现优秀

- 在 **6/7** 个场景中领先所有引擎的解释执行模式
- 仅 `fib_recursion` 场景弱于 Nashorn（递归深调用栈开销）

### 各引擎特点

| 引擎 | 优势 | 劣势 |
|------|------|------|
| **Nova Compiled** | 算术/调用/分支/递归均接近原生 | 对象分配 2.7x、列表操作 1.6x |
| **Groovy Parsed** | 算术/对象/列表场景表现不错 | 字符串拼接极差（1142x，不走 StringBuilder） |
| **Nashorn Compiled** | 字符串拼接较好 | 函数调用/对象分派较慢 |
| **GraalJS Warmed** | 字符串/列表场景尚可 | 未启用 Truffle JIT 时整体较慢 |
| **JEXL** | 适合简单表达式求值 | 递归/循环等复杂脚本极慢 |

### Nova 优化方向

- **对象分配**：Nova Compiled 2.4µs vs Java 0.9µs（2.7x），`NovaObject` 创建开销有优化空间
- **列表操作**：Nova Compiled 13.8µs vs Java 8.9µs（1.6x），集合操作可进一步优化
- **递归 Eval**：25ms 是唯一弱于 Nashorn（9ms）的场景，`MirFrame` 栈帧管理是热点

## 运行方式

```bash
# 运行正确性验证
./gradlew :nova-benchmarks:test

# 运行 JMH 基准测试（约 13 分钟）
./gradlew :nova-benchmarks:jmh

# 对比两份报告
./gradlew :nova-benchmarks:compareScriptEngineJmh \
  -Pbaseline=nova-benchmarks/build/benchmarks/script-engines-jmh-before.json \
  -Pcandidate=nova-benchmarks/build/benchmarks/script-engines-jmh.json
```
