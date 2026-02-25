# NovaLang for VS Code

NovaLang 语言支持扩展，提供语法高亮、实时错误检查、代码补全、悬停提示、跳转定义等功能。

## 环境要求

- **VS Code** >= 1.75.0
- **Java** >= 8（用于运行 LSP 服务器）
- **Node.js** >= 16（用于编译扩展）

## 快速开始

### 1. 构建 LSP 服务器

在项目根目录执行：

```bash
gradlew.bat :nova-lsp:jar
```

构建完成后生成 Fat JAR：

```
nova-lsp/build/libs/nova-lsp-0.1.0-SNAPSHOT.jar
```

### 2. 安装扩展

#### 方式 A：开发调试模式

```bash
cd vscode-nova
npm install
npm run compile
```

然后用 VS Code 打开 `vscode-nova` 目录，按 **F5** 启动扩展开发宿主窗口。在弹出的新窗口中打开任意 `.nova` 文件即可体验。

#### 方式 B：打包为 VSIX 安装

```bash
cd vscode-nova
npm install
npm run compile
npm install -g @vscode/vsce
vsce package
```

生成 `nova-language-0.1.0.vsix` 文件，在 VS Code 中：

1. `Ctrl+Shift+P` 打开命令面板
2. 输入 `Extensions: Install from VSIX...`
3. 选择生成的 `.vsix` 文件

### 3. 配置 LSP 路径

打开 VS Code 设置（`Ctrl+,`），搜索 `nova`，配置以下选项：

| 设置项 | 默认值 | 说明 |
|--------|--------|------|
| `nova.lsp.path` | （空） | nova-lsp JAR 的绝对路径 |
| `nova.lsp.javaPath` | `java` | Java 可执行文件路径 |

示例配置（settings.json）：

```json
{
    "nova.lsp.path": "E:/novalang/nova-lsp/build/libs/nova-lsp-0.1.0-SNAPSHOT.jar",
    "nova.lsp.javaPath": "java"
}
```

> **自动发现**：如果用 VS Code 打开的是 NovaLang 项目根目录，扩展会自动在
> `nova-lsp/build/libs/` 下查找 JAR 文件，无需手动配置 `nova.lsp.path`。

## 功能一览

### 语法高亮

打开 `.nova` 文件即自动生效，支持：

- 关键词（`val`, `var`, `fun`, `class`, `if`, `when` 等）
- 字符串（含 `$变量` 和 `${表达式}` 插值高亮）
- 数字（整数、浮点数、十六进制 `0xFF`、二进制 `0b1010`）
- 注释（单行 `//` 和块注释 `/* */`，支持嵌套）
- 注解（`@Data`, `@Builder` 等）
- 类型名、函数名

### 实时错误诊断

编辑 `.nova` 文件时，语法错误会以红色波浪线标出，错误信息显示在：

- 编辑器内联提示
- 底部状态栏的错误计数
- "问题"面板（`Ctrl+Shift+M`）

```nova
// 示例：缺少右括号会实时标红
fun add(a: Int, b: Int {
    return a + b
}
```

### 代码补全

输入时自动弹出补全列表（或按 `Ctrl+Space` 手动触发），包含：

- **关键词**：`val`, `var`, `fun`, `class`, `interface`, `when`, `for` 等
- **内置类型**：`Int`, `String`, `Boolean`, `List`, `Map` 等
- **内置函数**：`println`, `listOf`, `mapOf`, `require` 等
- **文档符号**：当前文件中定义的函数、类、变量

触发字符：`.`（成员访问）和 `:`（类型标注）

```nova
// 输入 "pr" 后弹出：
//   println    内置函数
//   print      内置函数
//   private    关键词
//   protected  关键词
```

### 悬停提示

将鼠标悬停在标识符上，显示其类型信息：

- 关键词 → 显示"**关键词** `val`"
- 内置类型 → 显示"**内置类型** `Int`"
- 函数声明 → 显示"**fun** `add(a: ..., b: ...)`"
- 类声明 → 显示"**class** `Person`"

### 跳转定义

在标识符上按 `F12` 或 `Ctrl+Click`，跳转到其定义位置。

支持跳转到当前文件内的：

- 函数定义（`fun`）
- 类定义（`class`）
- 接口定义（`interface`）
- 枚举定义（`enum class`）
- 对象定义（`object`）
- 变量声明（`val` / `var`）

### 文档大纲

侧边栏"大纲"视图（Outline）自动显示当前文件的结构：

```
├── greet          (函数)
├── Person         (类)
│   ├── getName    (函数)
│   └── setName    (函数)
├── Printable      (接口)
└── Color          (枚举)
```

快捷导航：`Ctrl+Shift+O` 打开符号搜索。

### 括号匹配与自动补全

- 自动配对：`{}`, `[]`, `()`, `""`, `''`
- 自动缩进：在 `{` 后回车自动增加缩进
- 块注释：选中代码后 `Ctrl+/` 切换行注释
- 折叠：支持 `// region` / `// endregion` 自定义折叠区域

## CLI 工具

除了 VS Code 扩展，NovaLang 还提供命令行工具：

### 代码格式化

```bash
nova fmt app.nova                        # 默认格式化
nova fmt app.nova --indent-size 2        # 2 空格缩进
nova fmt app.nova --use-tabs             # Tab 缩进
nova fmt app.nova --max-width 80         # 最大行宽 80
```

### 项目编译

```bash
nova build src                           # 编译 src 目录
nova build src -o out                    # 指定输出目录
nova build src --jar app.jar             # 编译并打包为 JAR
```

支持增量编译 — 只重新编译有变化的文件。

## 故障排查

### LSP 未启动

1. 检查 `nova.lsp.path` 是否指向正确的 JAR 文件
2. 确认 `java -version` 能正常运行
3. 查看 VS Code 输出面板（`Ctrl+Shift+U`）→ 选择 "NovaLang LSP" 查看日志
4. 查看 "NovaLang Language Server" 输出通道查看 LSP 通信日志

### 常见问题

**Q: 打开 .nova 文件没有语法高亮？**

确认扩展已安装且启用。右下角状态栏应显示语言为 "NovaLang"。如果显示 "Plain Text"，点击切换为 "NovaLang"。

**Q: 有语法高亮但没有错误提示和补全？**

语法高亮由 TextMate 语法提供（不依赖 LSP），错误提示和补全由 LSP 提供。请检查 LSP 是否正常启动（见上方排查步骤）。

**Q: 补全列表太多/太少？**

当前补全包含所有关键词和内置符号。输入更多字符可以缩小候选范围。后续版本将支持基于上下文的智能补全。

**Q: 修改了代码但错误提示没更新？**

LSP 使用全量文本同步，每次编辑都会重新分析。如果仍有问题，尝试 `Ctrl+Shift+P` → `Developer: Reload Window`。

## 开发

### 目录结构

```
vscode-nova/
├── .vscode/
│   ├── launch.json              # F5 调试配置
│   └── tasks.json               # 编译任务
├── src/
│   └── extension.ts             # 扩展入口（LSP 客户端）
├── syntaxes/
│   └── nova.tmLanguage.json     # TextMate 语法高亮规则
├── language-configuration.json  # 语言配置（括号、注释等）
├── package.json                 # 扩展清单
└── tsconfig.json                # TypeScript 配置
```

### 调试 LSP 通信

在 VS Code 设置中添加：

```json
{
    "novaLanguageServer.trace.server": "verbose"
}
```

然后在输出面板查看完整的 JSON-RPC 消息。
