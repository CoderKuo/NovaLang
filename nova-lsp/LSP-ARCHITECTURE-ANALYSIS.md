# LSP 模块架构分析报告

## 一、整体架构

模块采用分层设计，10 个类各有明确职责：

| 层 | 类 | 行数 | 职责 |
|---|---|---:|---|
| 传输层 | `JsonRpcTransport` | 134 | JSON-RPC 2.0 读写（stdin/stdout） |
| 协议层 | `NovaLanguageServer` | 850 | LSP 消息路由、生命周期管理 |
| 分析层 | `NovaAnalyzer` | 3382 | 核心语言智能（补全/悬停/定义等 15 个能力） |
| 索引层 | `ProjectIndex` | 138 | 跨文件符号索引 |
| 文档层 | `DocumentManager` | 233 | 文档内容 + AST/语义缓存 + debounce |
| Java 互操作 | `JavaClassResolver` / `JavaClassInfo` | 405 | ASM 字节码解析 + 继承链补全 |
| 工具层 | `LspTextUtils` / `LspConstants` / `SemanticTokensBuilder` | ~910 | 文本工具 / 协议常量 / 语义令牌 |

**评价：分层清晰，职责边界合理。**

---

## 二、设计亮点

1. **异步请求 + 取消追踪** — 4 线程池 + ConcurrentHashMap 追踪 `$/cancelRequest`
2. **Debounce + 版本校验** — 200ms debounce + versionCounter 防过期回调
3. **多策略类型推断** — 符号表 → 文本扫描 → receiver lambda 逐步降级
4. **增量文档同步** — `TextDocumentSyncKind.Incremental`
5. **JAR 索引缓存** — 首次访问一次性建索引，后续 O(1) 查找

---

## 三、问题清单

### 🔴 严重 (线程安全)

#### P0-1: `currentCached` 可变共享状态
- **位置**: `NovaAnalyzer.java:35`
- **问题**: 实例字段被 4 个线程池线程并发读写，竞态条件
- **修复**: 改为方法参数传递

#### P0-2: `JavaClassResolver.cache` 使用 HashMap
- **位置**: `JavaClassResolver.java:23`
- **问题**: 多线程环境 HashMap 并发读写可能死循环
- **修复**: 改为 `ConcurrentHashMap`

#### P0-3: JAR 索引初始化竞态
- **位置**: `JavaClassResolver.java:131-133`
- **问题**: 多线程同时到达可能重复构建索引
- **修复**: synchronized 或 volatile + 双重检查

### 🟠 中等

#### P1-1: NovaAnalyzer God Class (3382 行)
- **问题**: 承担过多职责（诊断/补全/悬停/定义/推断/引用/高亮/重命名/代码操作/折叠/语义令牌/内嵌提示/签名帮助/工作区符号）
- **建议**: 按功能域拆分为多个 handler 类

#### P1-2: AST 遍历重复代码 (9 个 instanceof 链)
- **位置**: collectMemberCalls, collectIdentifierLocations, collectAssignExprs, collectAllCallExprs, collectAllPropertyDecls, collectFlowTypeInfo, collectFoldingRanges, collectInlayHints, collectWriteLocations
- **建议**: 统一使用 AstVisitor 模式

#### P1-3: `symbolToDocumentSymbol` 仍有 magic number
- **位置**: `NovaAnalyzer.java:773-782`
- **修复**: 使用 `LspConstants.SYMBOL_*`

#### P1-4: `ensureParsed` 吞掉异常
- **位置**: `NovaAnalyzer.java:110`
- **修复**: 添加 LOG.warning

#### P1-5: `DocumentManager.scheduler` 没有关闭
- **位置**: `DocumentManager.java:42`
- **修复**: 添加 shutdown() 方法

#### P1-6: `format()` 使用严格解析器
- **位置**: `NovaAnalyzer.java:593`
- **问题**: 有语法错误时格式化直接失败

### 🟡 轻微

#### P2-1: 重复的 `content.split("\n", -1)` 调用
#### P2-2: `ProjectIndex` 不持久化
#### P2-3: `inferExprType` 文本推断与 SemanticAnalyzer 重复 (已有 TODO)

---

## 四、扩展性评估

| 维度 | 评分 | 说明 |
|------|:---:|------|
| 新增 LSP 能力 | ⭐⭐⭐⭐ | switch + handler |
| 新增语法特性 | ⭐⭐⭐ | 需在多个 instanceof 遍历中添加 |
| 新增 Java 类型映射 | ⭐⭐⭐⭐ | 静态 Map 添加条目 |
| 新增内置类型/函数 | ⭐⭐⭐⭐⭐ | NovaTypeRegistry 自动注册 |
| 新增诊断检查 | ⭐⭐ | 新 instanceof 链 |
| 增量解析 | ⭐ | 无基础设施 |

---

## 五、健壮性评估

| 场景 | 状态 | 说明 |
|------|:---:|------|
| 语法错误文档 | ✅ | parseTolerant() |
| 空文档/null | ✅ | 各入口均有 null 检查 |
| 流结束 | ✅ | readMessage() null → 退出 |
| 请求取消 | ✅ | $/cancelRequest + Future.cancel |
| 异步请求失败 | ✅ | catch + sendError |
| 并发文档编辑 | ⚠️ | currentCached 竞态 |
| 大文件性能 | ⚠️ | 反复 split + 全量重解析 |
| OOM | ❌ | 无缓存大小限制 |
