package com.novalang.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.novalang.compiler.analysis.*;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;
import com.novalang.compiler.formatter.NovaFormatter;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.ParseError;
import com.novalang.compiler.parser.ParseResult;
import com.novalang.compiler.parser.Parser;
import nova.runtime.NovaTypeRegistry;
import nova.runtime.stdlib.StdlibRegistry;

import static com.novalang.lsp.LspConstants.*;

import java.util.*;

/**
 * NovaLang 代码分析器
 *
 * <p>基于 AST 语义分析提供诊断信息、补全候选、悬停信息等。</p>
 */
public class NovaAnalyzer {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(NovaAnalyzer.class.getName());

    private final DocumentManager documentManager;
    private final ProjectIndex projectIndex = new ProjectIndex();
    private JavaClassResolver javaClassResolver;

    /** URI → AST 表达式位置索引（与 CachedAnalysis 生命周期一致） */
    private final Map<String, AstExpressionIndex> exprIndexCache = new HashMap<>();
    /** URI → 构建索引时对应的 ParseResult 引用（用于检测刷新） */
    private final Map<String, ParseResult> exprIndexSource = new HashMap<>();

    public NovaAnalyzer(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    public ProjectIndex getProjectIndex() {
        return projectIndex;
    }

    public void setJavaClassResolver(JavaClassResolver resolver) {
        this.javaClassResolver = resolver;
    }

    /** 内置函数名 → 返回类型（从 Registry 动态构建） */
    private static final Map<String, String> BUILTIN_FUNC_RETURN_TYPES = new HashMap<String, String>();
    static {
        for (NovaTypeRegistry.FunctionInfo f : NovaTypeRegistry.getBuiltinFunctions()) {
            BUILTIN_FUNC_RETURN_TYPES.put(f.name, f.returnType);
        }
    }

    /** Nova 内置类型 → 对应的 Java 类全限定名 */
    private static final Map<String, String> NOVA_TO_JAVA_TYPE = new HashMap<String, String>();
    static {
        NOVA_TO_JAVA_TYPE.put("String", "java.lang.String");
        NOVA_TO_JAVA_TYPE.put("List", "java.util.ArrayList");
        NOVA_TO_JAVA_TYPE.put("Map", "java.util.HashMap");
        NOVA_TO_JAVA_TYPE.put("Int", "java.lang.Integer");
        NOVA_TO_JAVA_TYPE.put("Long", "java.lang.Long");
        NOVA_TO_JAVA_TYPE.put("Double", "java.lang.Double");
        NOVA_TO_JAVA_TYPE.put("Float", "java.lang.Float");
        NOVA_TO_JAVA_TYPE.put("Boolean", "java.lang.Boolean");
        NOVA_TO_JAVA_TYPE.put("Byte", "java.lang.Byte");
        NOVA_TO_JAVA_TYPE.put("Short", "java.lang.Short");
        NOVA_TO_JAVA_TYPE.put("Char", "java.lang.Character");
        NOVA_TO_JAVA_TYPE.put("Set", "java.util.HashSet");
        NOVA_TO_JAVA_TYPE.put("Future", "java.util.concurrent.CompletableFuture");
    }

    /** Java 全限定名 → Nova 类型名（反向查找） */
    private static final Map<String, String> JAVA_TO_NOVA_TYPE = new HashMap<String, String>();
    static {
        for (Map.Entry<String, String> e : NOVA_TO_JAVA_TYPE.entrySet()) {
            JAVA_TO_NOVA_TYPE.put(e.getValue(), e.getKey());
        }
        JAVA_TO_NOVA_TYPE.put("java.util.LinkedList", "List");
        JAVA_TO_NOVA_TYPE.put("java.util.LinkedHashMap", "Map");
        JAVA_TO_NOVA_TYPE.put("java.util.TreeMap", "Map");
    }

    private static String javaTypeToNova(String javaClassName) {
        return JAVA_TO_NOVA_TYPE.get(javaClassName);
    }

    /** 获取声明名称的精确位置（优先 nameLocation，回退到 location） */
    private static SourceLocation declNameLoc(Declaration decl) {
        return decl.getNameLocation() != null ? decl.getNameLocation() : decl.getLocation();
    }

    /**
     * 获取已缓存的分析结果，若缓存不存在则即时解析（兼容未通过 DocumentManager.open 的场景）
     */
    private DocumentManager.CachedAnalysis ensureParsed(String uri, String content) {
        DocumentManager.CachedAnalysis cached = documentManager.getAnalysis(uri);
        if (cached != null) {
            ensureExprIndex(uri, cached);
            return cached;
        }
        // 文档缓存不存在（可能已变更），清除过期的表达式索引
        exprIndexCache.remove(uri);
        exprIndexSource.remove(uri);
        if (content == null) return null;
        try {
            String fileName = DocumentManager.getFileName(uri);
            Lexer lexer = new Lexer(content, fileName);
            Parser parser = new Parser(lexer, fileName);
            ParseResult parseResult = parser.parseTolerant();
            SemanticAnalyzer semAnalyzer = new SemanticAnalyzer();
            AnalysisResult analysisResult = semAnalyzer.analyze(
                    parseResult.getProgram(), parseResult.getTopLevelStatements());
            cached = new DocumentManager.CachedAnalysis(parseResult, analysisResult);
            ensureExprIndex(uri, cached);
            return cached;
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "解析失败: " + uri, e);
            return null;
        }
    }

    /** 确保指定文档的表达式索引已构建（ParseResult 变化时自动重建） */
    private void ensureExprIndex(String uri, DocumentManager.CachedAnalysis cached) {
        if (cached.parseResult == null || cached.parseResult.getProgram() == null) return;
        // 引用比较：ParseResult 变化时说明重新解析过，需重建索引
        if (exprIndexSource.get(uri) == cached.parseResult) return;
        try {
            exprIndexCache.put(uri, new AstExpressionIndex(
                    cached.parseResult.getProgram(), cached.parseResult.getTopLevelStatements()));
            exprIndexSource.put(uri, cached.parseResult);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "构建表达式索引失败: " + uri, e);
        }
    }

    // ============ 核心 LSP 方法 ============

    /**
     * 分析文档，返回诊断信息
     */
    public JsonArray analyze(String uri, String content) {
        JsonArray diagnostics = new JsonArray();
        if (content == null) return diagnostics;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached == null) return diagnostics;

        // 1. 语法错误（从容错解析器收集）
        for (ParseError error : cached.parseResult.getErrors()) {
            JsonObject diag = new JsonObject();
            int line = error.getLine() > 0 ? error.getLine() - 1 : 0;
            int col = error.getColumn() > 0 ? error.getColumn() - 1 : 0;
            int endCol = col + 1;
            if (error.getToken() != null) {
                endCol = col + error.getToken().getLexeme().length();
            }
            diag.add("range", createRange(line, col, line, endCol));
            diag.addProperty("severity", SEVERITY_ERROR);
            diag.addProperty("source", "nova");
            diag.addProperty("message", error.getMessage());
            diagnostics.add(diag);
        }

        // 2. 语义错误（从语义分析器收集）
        for (SemanticDiagnostic sd : cached.analysisResult.getDiagnostics()) {
            JsonObject diag = new JsonObject();
            int line = 0, col = 0, endCol = 1;
            if (sd.getLocation() != null) {
                line = sd.getLocation().getLine() - 1;
                col = sd.getLocation().getColumn() - 1;
                endCol = col + sd.getLength();
            }
            diag.add("range", createRange(line, col, line, endCol));
            int severity;
            switch (sd.getSeverity()) {
                case ERROR: severity = SEVERITY_ERROR; break;
                case WARNING: severity = SEVERITY_WARNING; break;
                case INFO: severity = SEVERITY_INFORMATION; break;
                case HINT: severity = SEVERITY_HINT; break;
                default: severity = SEVERITY_ERROR;
            }
            diag.addProperty("severity", severity);
            diag.addProperty("source", "nova");
            diag.addProperty("message", sd.getMessage());
            diagnostics.add(diag);
        }

        // 3. 语义检查（仅在无语法错误时）
        if (cached.parseResult.getErrors().isEmpty()) {
            checkMethodArgCounts(cached, content, diagnostics);
            checkValReassignment(cached, content, diagnostics);
            checkConstVal(cached, diagnostics);
            checkArrayConstructorArgs(cached, content, diagnostics);
            checkArgTypes(cached, content, diagnostics);
        }

        return diagnostics;
    }

    /**
     * 获取补全候选
     */
    public JsonArray complete(String uri, String content, int line, int character) {
        JsonArray items = new JsonArray();
        if (content == null) return items;

        String prefix = LspTextUtils.getPrefix(content, line, character);

        // 字符串插值补全
        if (LspTextUtils.isInStringInterpolation(content, line, character, prefix)) {
            return completeInterpolation(uri, content, prefix, line, character);
        }

        // 成员补全（点号后）
        String memberContext = LspTextUtils.detectDotContext(content, line, character, prefix);
        if (memberContext != null) {
            return completeMember(uri, content, line, character, prefix);
        }

        // === 通用补全 ===

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);

        // 1. 从符号表获取当前作用域可见的所有符号（内置函数/常量由步骤 4 统一处理，避免重复）
        if (cached != null) {
            SymbolTable st = cached.analysisResult.getSymbolTable();
            // LSP line 从 0 开始, SymbolTable 从 1 开始
            List<Symbol> visible = st.getVisibleSymbols(line + 1, character + 1);
            for (Symbol sym : visible) {
                if (sym.getKind() == SymbolKind.BUILTIN_FUNCTION || sym.getKind() == SymbolKind.BUILTIN_CONSTANT) continue;
                if (!prefix.isEmpty() && !sym.getName().startsWith(prefix)) continue;
                items.add(symbolToCompletionItem(sym));
            }
        }

        // 2. 关键词补全
        for (String keyword : Lexer.getKeywords()) {
            if (prefix.isEmpty() || keyword.startsWith(prefix)) {
                JsonObject item = new JsonObject();
                item.addProperty("label", keyword);
                item.addProperty("kind", COMPLETION_KEYWORD);
                item.addProperty("detail", "关键词");
                items.add(item);
            }
        }

        // 3. 内置类型补全
        for (NovaTypeRegistry.TypeInfo typeInfo : NovaTypeRegistry.getBuiltinTypes()) {
            if (prefix.isEmpty() || typeInfo.name.startsWith(prefix)) {
                JsonObject item = new JsonObject();
                item.addProperty("label", typeInfo.name);
                item.addProperty("kind", COMPLETION_CLASS);
                item.addProperty("detail", "内置类型");
                items.add(item);
            }
        }

        // 4. 内置函数补全
        for (NovaTypeRegistry.FunctionInfo func : NovaTypeRegistry.getBuiltinFunctions()) {
            if (!prefix.isEmpty() && !func.name.startsWith(prefix)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("label", func.signature);
            item.addProperty("kind", COMPLETION_FUNCTION);
            item.addProperty("detail", func.description + " → " + func.returnType);
            item.addProperty("insertTextFormat", INSERT_TEXT_SNIPPET);
            if (func.signature.endsWith("()")) {
                item.addProperty("insertText", func.name + "()$0");
            } else {
                item.addProperty("insertText", func.name + "($1)$0");
            }
            items.add(item);
        }

        // 5. 内置常量补全
        for (NovaTypeRegistry.ConstantInfo c : NovaTypeRegistry.getBuiltinConstants()) {
            if (!prefix.isEmpty() && !c.name.startsWith(prefix)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("label", c.name);
            item.addProperty("kind", COMPLETION_CONSTANT);
            item.addProperty("detail", c.description + " : " + c.type);
            items.add(item);
        }

        // 6. 接收者 Lambda 上下文补全（buildString { | } 内提示 receiver 的方法）
        addReceiverLambdaCompletions(items, content, line, character, prefix);

        // 7. 内置对象补全
        if (prefix.isEmpty() || "Java".startsWith(prefix)) {
            JsonObject item = new JsonObject();
            item.addProperty("label", "Java");
            item.addProperty("kind", COMPLETION_VARIABLE);
            item.addProperty("detail", "Java 互操作命名空间");
            items.add(item);
        }

        // 8. 代码片段补全
        Scope.ScopeType scopeType = null;
        if (cached != null) {
            Scope scope = cached.analysisResult.getSymbolTable().getScopeAtPosition(line + 1, character + 1);
            if (scope != null) scopeType = scope.getType();
        }
        addSnippetCompletions(items, prefix, scopeType);

        return items;
    }

    /**
     * 成员补全
     */
    private JsonArray completeMember(String uri, String content, int line, int character, String prefix) {
        JsonArray items = new JsonArray();
        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);

        String receiverType = null;
        String receiverText = LspTextUtils.getTextBeforeDot(content, line, character, prefix);

        // 策略1: 从符号表推断
        Symbol receiverSym = null;
        if (cached != null && receiverText != null) {
            receiverSym = cached.analysisResult.getSymbolTable().resolve(receiverText, line + 1, character + 1);
            if (receiverSym != null) {
                receiverType = receiverSym.getTypeName();
            }
        }

        // 策略2: 从 AST 语义分析推断 receiver 类型
        if (receiverType == null || "Any".equals(receiverType)) {
            String astInferred = inferReceiverTypeFromAst(uri, content, line, character, prefix, cached);
            if (astInferred != null) receiverType = astInferred;
        }

        // 策略3: Lambda 参数类型推断（隐式 it 或显式命名参数）
        if (receiverType == null || "Any".equals(receiverType)) {
            if (receiverText != null) {
                String lambdaType = inferLambdaParamType(uri, content, line, character, cached);
                if (lambdaType != null) receiverType = lambdaType;
            }
        }

        // 策略4: receiver lambda 内部 this 解析
        if (receiverType == null) {
            if ("this".equals(receiverText)) {
                StdlibRegistry.ReceiverLambdaInfo rlInfo = findReceiverLambdaAt(content, line, character);
                if (rlInfo != null) {
                    receiverType = "java:" + rlInfo.receiverType.replace('/', '.');
                }
            }
        }

        if (receiverType != null) {
            String base = LspTextUtils.baseType(receiverType);

            // Java 互操作
            if ("__Java__".equals(base)) {
                String[][] javaMethods = {
                    {"type(className)", "获取 Java 类 → 可调用对象"},
                    {"static(className, methodName, args...)", "调用 Java 静态方法"},
                    {"field(className, fieldName)", "获取 Java 静态字段值"},
                    {"new(className, args...)", "创建 Java 实例"},
                    {"isInstance(obj, className)", "检查对象是否是指定类的实例"},
                    {"class(obj)", "获取对象的 Java 类名"}
                };
                for (String[] m : javaMethods) {
                    addMethodItem(items, m[0], m[1], "Java", prefix);
                }
                return items;
            }

            // Java 类型补全
            if (base != null && base.startsWith("java:")) {
                String javaClassName = base.substring(5);
                if (javaClassResolver != null) {
                    JavaClassInfo classInfo = javaClassResolver.resolve(javaClassName);
                    if (classInfo != null) {
                        for (JavaClassInfo.MethodInfo m : classInfo.methods) {
                            if (!m.isStatic && !m.name.equals("<init>")) {
                                String label = m.name + "(" + String.join(", ", m.paramTypes) + ")";
                                addMethodItem(items, label, m.returnType, javaClassName, prefix);
                            }
                        }
                        for (JavaClassInfo.FieldInfo f : classInfo.fields) {
                            if (!f.isStatic) {
                                if (!prefix.isEmpty() && !f.name.startsWith(prefix)) continue;
                                JsonObject item = new JsonObject();
                                item.addProperty("label", f.name);
                                item.addProperty("kind", COMPLETION_PROPERTY);
                                item.addProperty("detail", javaClassName + " - " + f.type);
                                items.add(item);
                            }
                        }
                    }
                }
                return items;
            }

            // 从 Registry 获取内置类型方法
            List<NovaTypeRegistry.MethodInfo> methods = NovaTypeRegistry.getMethodsForType(base);
            if (methods != null) {
                for (NovaTypeRegistry.MethodInfo m : methods) {
                    if (m.isProperty) {
                        addMethodItem(items, m.name, m.description, base, prefix);
                    } else {
                        addMethodItem(items, buildRegistryMethodLabel(m), m.description, base, prefix);
                    }
                }
            }

            // 从符号表获取用户定义的类成员
            if (cached != null) {
                SymbolTable st = cached.analysisResult.getSymbolTable();
                Symbol classSym = st.resolve(base, line + 1, character + 1);
                // fallback: 按名称搜索所有 CLASS 符号
                if (classSym == null || (classSym.getKind() != SymbolKind.CLASS
                        && classSym.getKind() != SymbolKind.ENUM)) {
                    for (Symbol s : st.getAllSymbolsOfKind(SymbolKind.CLASS, SymbolKind.ENUM)) {
                        if (s.getName().equals(base)) {
                            classSym = s;
                            break;
                        }
                    }
                }
                if (classSym != null && classSym.getMembers() != null) {
                    for (Symbol member : classSym.getMembers().values()) {
                        if (!prefix.isEmpty() && !member.getName().startsWith(prefix)) continue;
                        items.add(symbolToCompletionItem(member));
                    }
                }
            }

            // Any 通用方法
            List<NovaTypeRegistry.MethodInfo> anyMethods = NovaTypeRegistry.getMethodsForType("Any");
            if (anyMethods != null) {
                for (NovaTypeRegistry.MethodInfo m : anyMethods) {
                    if (SCOPE_FUNCTION_NAMES.contains(m.name)) {
                        if (!prefix.isEmpty() && !m.name.startsWith(prefix)) continue;
                        JsonObject item = new JsonObject();
                        item.addProperty("label", m.name + " { }");
                        item.addProperty("kind", COMPLETION_METHOD);
                        item.addProperty("detail", "Any - " + m.description);
                        item.addProperty("filterText", m.name);
                        item.addProperty("insertTextFormat", INSERT_TEXT_SNIPPET);
                        item.addProperty("insertText", m.name + " { $1 }$0");
                        items.add(item);
                    } else {
                        addMethodItem(items, buildRegistryMethodLabel(m), m.description, "Any", prefix);
                    }
                }
            }
        }

        return items;
    }

    /**
     * 获取悬停信息
     */
    public JsonObject hover(String uri, String content, int line, int character) {
        if (content == null) return null;
        String word = LspTextUtils.getWordAt(content, line, character);
        if (word == null || word.isEmpty()) return null;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);

        // 1. 从符号表查找（最精确）
        if (cached != null) {
            Symbol sym = cached.analysisResult.getSymbolTable().resolve(word, line + 1, character + 1);
            if (sym != null && sym.getKind() != SymbolKind.BUILTIN_FUNCTION
                    && sym.getKind() != SymbolKind.BUILTIN_CONSTANT) {
                // 变量无类型或类型为 Any 时，用文本推断补充更精确的类型
                if ((sym.getKind() == SymbolKind.VARIABLE || sym.getKind() == SymbolKind.PROPERTY)
                        && (sym.getTypeName() == null || "Any".equals(sym.getTypeName()))) {
                    String inferred = inferVariableType(content, word);
                    if (inferred != null) {
                        String h = "**" + (sym.isMutable() ? "var" : "val") + "** `"
                                + sym.getName() + ": " + LspTextUtils.formatTypeForDisplay(inferred) + "`";
                        return createHover(h);
                    }
                }
                return createHover(buildSymbolHover(sym));
            }
        }

        // 2. 检查关键词
        String keywordHover = getKeywordHover(word);
        if (keywordHover != null) {
            return createHover(keywordHover);
        }

        // 3. 内置类型
        for (NovaTypeRegistry.TypeInfo typeInfo : NovaTypeRegistry.getBuiltinTypes()) {
            if (typeInfo.name.equals(word)) {
                return createHover("**内置类型** `" + typeInfo.name + "`\n\n" + typeInfo.description);
            }
        }

        // 4. 内置函数
        for (NovaTypeRegistry.FunctionInfo func : NovaTypeRegistry.getBuiltinFunctions()) {
            if (func.name.equals(word)) {
                return createHover("**内置函数** `" + func.signature + "` → `" + func.returnType + "`\n\n" + func.description);
            }
        }

        // 5. 内置常量
        for (NovaTypeRegistry.ConstantInfo c : NovaTypeRegistry.getBuiltinConstants()) {
            if (c.name.equals(word)) {
                return createHover("**内置常量** `" + c.name + "`: `" + c.type + "`\n\n" + c.description);
            }
        }

        // 6. 内置对象
        if ("Java".equals(word)) {
            return createHover("**Java 互操作**\n\n" +
                    "- `Java.type(className)` — 获取 Java 类\n" +
                    "- `Java.static(className, method, args...)` — 调用静态方法\n" +
                    "- `Java.field(className, fieldName)` — 获取静态字段\n" +
                    "- `Java.new(className, args...)` — 创建实例\n" +
                    "- `Java.isInstance(obj, className)` — 类型检查\n" +
                    "- `Java.class(obj)` — 获取类名");
        }

        // 7. 成员方法 hover
        String[] allLines = content.split("\n", -1);
        if (line >= 0 && line < allLines.length) {
            String lineText = allLines[line];
            int wordStart = character;
            while (wordStart > 0 && LspTextUtils.isIdentChar(lineText.charAt(wordStart - 1))) wordStart--;
            if (wordStart > 0 && lineText.charAt(wordStart - 1) == '.') {
                String beforeDot = lineText.substring(0, wordStart - 1).trim();
                if (!beforeDot.isEmpty()) {
                    // 优先从符号表推断 receiver 类型
                    String receiverType = null;
                    if (cached != null && LspTextUtils.isSimpleIdentifier(beforeDot)) {
                        Symbol sym = cached.analysisResult.getSymbolTable().resolve(beforeDot, line + 1, character + 1);
                        if (sym != null) receiverType = sym.getTypeName();
                    }
                    if (receiverType == null) {
                        int dotOffset = LspTextUtils.toOffset(content, line, wordStart - 1);
                        if (dotOffset > 0) {
                            receiverType = inferReceiverTypeFromAstAtOffset(uri, content, dotOffset, cached);
                        }
                    }
                    if (receiverType == null && LspTextUtils.isSimpleIdentifier(beforeDot)) {
                        receiverType = inferLambdaParamType(uri, content, line, character, cached);
                    }
                    if (receiverType != null) {
                        String overloadsHover = buildMethodOverloadsHover(receiverType, word);
                        if (overloadsHover != null) return createHover(overloadsHover);
                    }
                }
            }
        }

        // 7. 从符号表查内置符号（作为回退）
        if (cached != null) {
            Symbol sym = cached.analysisResult.getSymbolTable().resolve(word, line + 1, character + 1);
            if (sym != null) {
                return createHover(buildSymbolHover(sym));
            }
        }

        return null;
    }

    /**
     * 跳转定义
     */
    public JsonObject goToDefinition(String uri, String content, int line, int character) {
        if (content == null) return null;
        String word = LspTextUtils.getWordAt(content, line, character);
        if (word == null || word.isEmpty()) return null;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);

        // 1. 从符号表查找
        if (cached != null) {
            Symbol sym = cached.analysisResult.getSymbolTable().resolve(word, line + 1, character + 1);
            if (sym != null && sym.getLocation() != null) {
                JsonObject result = new JsonObject();
                result.addProperty("uri", uri);
                int defLine = sym.getLocation().getLine() - 1;
                int defCol = sym.getLocation().getColumn() - 1;
                // 在行中精确找到名称位置
                defCol = LspTextUtils.findNameInLine(content, defLine, word, defCol);
                result.add("range", createRange(defLine, defCol, defLine, defCol + word.length()));
                return result;
            }
        }

        // 2. 文本搜索回退
        return findLocalVarDefinition(uri, content, line, word);
    }

    /**
     * 获取文档符号
     */
    public JsonArray documentSymbols(String uri, String content) {
        JsonArray symbols = new JsonArray();
        if (content == null) return symbols;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached != null) {
            // 从全局作用域获取顶层声明
            for (Symbol sym : cached.analysisResult.getSymbolTable().getGlobalScope().getSymbols().values()) {
                if (sym.getKind() == SymbolKind.BUILTIN_FUNCTION || sym.getKind() == SymbolKind.BUILTIN_CONSTANT) {
                    continue;
                }
                JsonObject docSym = symbolToDocumentSymbol(sym, content);
                if (docSym != null) {
                    symbols.add(docSym);
                }
            }
            return symbols;
        }

        return symbols;
    }

    /**
     * 格式化文档
     */
    public JsonArray format(String uri, String content) {
        try {
            String fileName = DocumentManager.getFileName(uri);
            Lexer lexer = new Lexer(content, fileName);
            Parser parser = new Parser(lexer, fileName);
            Program program = parser.parse();

            NovaFormatter formatter = new NovaFormatter();
            String formatted = formatter.format(program);

            if (formatted.equals(content)) {
                return new JsonArray();
            }

            String[] lines = content.split("\n", -1);
            int lastLine = lines.length - 1;
            int lastChar = lines[lastLine].length();

            JsonArray edits = new JsonArray();
            JsonObject edit = new JsonObject();
            edit.add("range", createRange(0, 0, lastLine, lastChar));
            edit.addProperty("newText", formatted);
            edits.add(edit);
            return edits;
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 符号转换辅助 ============

    /** 将 Symbol 转为 LSP CompletionItem */
    private JsonObject symbolToCompletionItem(Symbol sym) {
        JsonObject item = new JsonObject();
        item.addProperty("label", sym.getName());

        switch (sym.getKind()) {
            case VARIABLE:
            case PROPERTY:
                item.addProperty("kind", COMPLETION_VARIABLE);
                String varDetail = (sym.isMutable() ? "var " : "val ") + sym.getName();
                if (sym.getTypeName() != null) varDetail += ": " + sym.getTypeName();
                item.addProperty("detail", varDetail);
                break;
            case PARAMETER:
                item.addProperty("kind", COMPLETION_VARIABLE);
                String paramDetail = "参数 " + sym.getName();
                if (sym.getTypeName() != null) paramDetail += ": " + sym.getTypeName();
                item.addProperty("detail", paramDetail);
                break;
            case FUNCTION:
                // label 带括号和参数名
                StringBuilder funcLabel = new StringBuilder(sym.getName());
                funcLabel.append("(");
                if (sym.getParameters() != null) {
                    for (int i = 0; i < sym.getParameters().size(); i++) {
                        if (i > 0) funcLabel.append(", ");
                        funcLabel.append(sym.getParameters().get(i).getName());
                    }
                }
                funcLabel.append(")");
                item.addProperty("label", funcLabel.toString());
                item.addProperty("kind", COMPLETION_METHOD); // Method
                item.addProperty("filterText", sym.getName());
                item.addProperty("detail", buildFunSymbolSignature(sym));
                item.addProperty("insertTextFormat", INSERT_TEXT_SNIPPET);
                boolean hasParams = sym.getParameters() != null && !sym.getParameters().isEmpty();
                item.addProperty("insertText", hasParams
                        ? sym.getName() + "($1)$0"
                        : sym.getName() + "()$0");
                break;
            case CLASS: {
                item.addProperty("kind", COMPLETION_CLASS);
                String classPrefix = "class";
                if (sym.getDeclaration() instanceof ClassDecl) {
                    ClassDecl cd = (ClassDecl) sym.getDeclaration();
                    if (cd.isSealed()) classPrefix = "sealed class";
                    else if (cd.isAbstract()) classPrefix = "abstract class";
                    else if (cd.isOpen()) classPrefix = "open class";
                }
                item.addProperty("detail", classPrefix + " " + sym.getName());
                break;
            }
            case INTERFACE:
                item.addProperty("kind", COMPLETION_INTERFACE);
                item.addProperty("detail", "interface " + sym.getName());
                break;
            case ENUM:
                item.addProperty("kind", COMPLETION_ENUM);
                item.addProperty("detail", "enum class " + sym.getName());
                break;
            case ENUM_ENTRY:
                item.addProperty("kind", COMPLETION_ENUM_MEMBER);
                item.addProperty("detail", "enum entry " + sym.getName());
                break;
            case OBJECT:
                item.addProperty("kind", COMPLETION_CLASS);
                item.addProperty("detail", "object " + sym.getName());
                break;
            case BUILTIN_FUNCTION:
                item.addProperty("kind", COMPLETION_FUNCTION);
                item.addProperty("detail", "内置函数 → " + sym.getTypeName());
                break;
            case BUILTIN_CONSTANT:
                item.addProperty("kind", COMPLETION_CONSTANT);
                item.addProperty("detail", sym.getTypeName());
                break;
            default:
                item.addProperty("kind", COMPLETION_VARIABLE);
                item.addProperty("detail", sym.getName());
        }

        item.addProperty("sortText", "1_" + sym.getName());
        return item;
    }

    /** 构建符号 hover 信息 */
    private String buildSymbolHover(Symbol sym) {
        StringBuilder sb = new StringBuilder();
        switch (sym.getKind()) {
            case VARIABLE:
            case PROPERTY:
                sb.append("**").append(sym.isMutable() ? "var" : "val").append("** `");
                sb.append(sym.getName());
                if (sym.getTypeName() != null) sb.append(": ").append(sym.getTypeName());
                sb.append("`");
                break;
            case FUNCTION:
                sb.append("**fun** `").append(buildFunSymbolSignature(sym)).append("`");
                break;
            case CLASS: {
                String classModifier = "class";
                if (sym.getDeclaration() instanceof ClassDecl) {
                    ClassDecl cd = (ClassDecl) sym.getDeclaration();
                    if (cd.isSealed()) classModifier = "sealed class";
                    else if (cd.isAbstract()) classModifier = "abstract class";
                    else if (cd.isOpen()) classModifier = "open class";
                }
                sb.append("**").append(classModifier).append("** `").append(sym.getName()).append("`");
                break;
            }
            case INTERFACE:
                sb.append("**interface** `").append(sym.getName()).append("`");
                break;
            case ENUM:
                sb.append("**enum class** `").append(sym.getName()).append("`");
                break;
            case ENUM_ENTRY:
                sb.append("**enum entry** `").append(sym.getName()).append("`");
                break;
            case OBJECT:
                sb.append("**object** `").append(sym.getName()).append("`");
                break;
            case PARAMETER:
                sb.append("**参数** `").append(sym.getName());
                if (sym.getTypeName() != null) sb.append(": ").append(sym.getTypeName());
                sb.append("`");
                break;
            case BUILTIN_FUNCTION:
                sb.append("**内置函数** `").append(sym.getName());
                if (sym.getTypeName() != null) sb.append("` → `").append(sym.getTypeName());
                sb.append("`");
                break;
            case BUILTIN_CONSTANT:
                sb.append("**内置常量** `").append(sym.getName());
                if (sym.getTypeName() != null) sb.append(": ").append(sym.getTypeName());
                sb.append("`");
                break;
            default:
                sb.append("`").append(sym.getName()).append("`");
        }
        return sb.toString();
    }

    /** 构建函数符号签名 */
    private String buildFunSymbolSignature(Symbol sym) {
        StringBuilder sb = new StringBuilder("fun ");
        sb.append(sym.getName());
        sb.append("(");
        if (sym.getParameters() != null) {
            for (int i = 0; i < sym.getParameters().size(); i++) {
                if (i > 0) sb.append(", ");
                Symbol p = sym.getParameters().get(i);
                sb.append(p.getName());
                if (p.getTypeName() != null) sb.append(": ").append(p.getTypeName());
            }
        }
        sb.append(")");
        if (sym.getTypeName() != null) sb.append(": ").append(sym.getTypeName());
        return sb.toString();
    }

    /** 将 Symbol 转为 DocumentSymbol */
    private JsonObject symbolToDocumentSymbol(Symbol sym, String content) {
        if (sym.getLocation() == null) return null;

        JsonObject docSym = new JsonObject();
        docSym.addProperty("name", sym.getName());

        int kind;
        switch (sym.getKind()) {
            case CLASS: kind = SYMBOL_CLASS; break;
            case INTERFACE: kind = SYMBOL_INTERFACE; break;
            case FUNCTION: kind = SYMBOL_FUNCTION; break;
            case VARIABLE:
            case PROPERTY: kind = SYMBOL_VARIABLE; break;
            case ENUM: kind = SYMBOL_ENUM; break;
            case OBJECT: kind = SYMBOL_CLASS; break;
            case CONSTRUCTOR: kind = SYMBOL_CONSTRUCTOR; break;
            default: return null;
        }
        docSym.addProperty("kind", kind);

        int line = sym.getLocation().getLine() - 1;
        int col = LspTextUtils.findNameInLine(content, line, sym.getName(), sym.getLocation().getColumn() - 1);
        docSym.add("range", createRange(line, col, line, col + sym.getName().length()));
        docSym.add("selectionRange", createRange(line, col, line, col + sym.getName().length()));

        // 子符号
        if (sym.getMembers() != null && !sym.getMembers().isEmpty()) {
            JsonArray children = new JsonArray();
            for (Symbol member : sym.getMembers().values()) {
                JsonObject child = symbolToDocumentSymbol(member, content);
                if (child != null) {
                    children.add(child);
                }
            }
            if (children.size() > 0) {
                docSym.add("children", children);
            }
        }

        return docSym;
    }

    // ============ 成员补全辅助 ============

    /** scope 函数名集合 */
    private static final Set<String> SCOPE_FUNCTION_NAMES = new HashSet<String>(
        Arrays.asList("let", "also", "run", "apply", "takeIf", "takeUnless")
    );

    /** 从 MethodInfo 构建 label 字符串 */
    private String buildRegistryMethodLabel(NovaTypeRegistry.MethodInfo m) {
        if (m.isProperty) return m.name;
        if (m.arity == 0) return m.name + "()";
        if (m.arity == -1) return m.name + "(values...)";
        StringBuilder sb = new StringBuilder(m.name);
        sb.append("(");
        for (int i = 0; i < m.arity; i++) {
            if (i > 0) sb.append(", ");
            sb.append("arg").append(i + 1);
        }
        sb.append(")");
        return sb.toString();
    }

    // ============ Lambda 隐式参数 it 类型推断 ============

    /** 作用域函数：it = receiver 类型 */
    private static final Set<String> SCOPE_FUNCTIONS = new HashSet<>(Arrays.asList(
            "let", "also", "takeIf", "takeUnless"
    ));

    /** 集合元素迭代函数：it = 元素类型 */
    private static final Set<String> COLLECTION_ELEMENT_FUNCTIONS = new HashSet<>(Arrays.asList(
            "filter", "forEach", "map", "flatMap", "any", "all", "none",
            "find", "count", "onEach", "sortedBy", "minBy", "maxBy", "groupBy"
    ));

    /**
     * 推断 lambda 参数的类型（隐式 it 或显式命名参数）。
     * 从光标位置反向扫描找到包含的 lambda { ... }，然后从 { 前面提取
     * receiver.method 调用模式，根据方法语义确定参数的类型。
     */
    private String inferLambdaParamType(String uri, String content, int line, int character,
                                DocumentManager.CachedAnalysis cached) {
        int cursorOffset = LspTextUtils.toOffset(content, line, character);
        if (cursorOffset <= 0 || cursorOffset > content.length()) return null;

        // 反向扫描找到包含当前位置的 '{'（跳过嵌套和字符串）
        int depth = 0;
        for (int i = cursorOffset - 1; i >= 0; i--) {
            char ch = content.charAt(i);

            // 跳过字符串内部
            if (ch == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                i--;
                while (i > 0 && !(content.charAt(i) == '"' && content.charAt(i - 1) != '\\')) {
                    i--;
                }
                continue;
            }

            if (ch == '}') {
                depth++;
            } else if (ch == '{') {
                if (depth > 0) {
                    depth--;
                } else {
                    // 找到包含光标的未匹配 { — 从它前面提取 receiver.method
                    String itType = extractItTypeFromBrace(uri, content, i, cached);
                    if (itType != null) return itType;
                    // 不是可识别的模式，继续向外层扫描
                }
            }
        }
        return null;
    }

    /**
     * 从 { 位置前面的文本提取 receiver.method 模式，推断 it 的类型。
     */
    private String extractItTypeFromBrace(String uri, String content, int bracePos,
                                           DocumentManager.CachedAnalysis cached) {
        // 跳过 { 前面的空白
        int j = bracePos - 1;
        while (j >= 0 && Character.isWhitespace(content.charAt(j))) {
            j--;
        }
        if (j < 0) return null;

        // 跳过可能的参数列表 (...)
        if (content.charAt(j) == ')') {
            int parenDepth = 1;
            j--;
            while (j >= 0 && parenDepth > 0) {
                if (content.charAt(j) == ')') parenDepth++;
                else if (content.charAt(j) == '(') parenDepth--;
                j--;
            }
            while (j >= 0 && Character.isWhitespace(content.charAt(j))) {
                j--;
            }
            if (j < 0) return null;
        }

        // 提取方法名
        int methodEnd = j + 1;
        while (j >= 0 && (Character.isLetterOrDigit(content.charAt(j)) || content.charAt(j) == '_')) {
            j--;
        }
        String methodName = content.substring(j + 1, methodEnd);
        if (methodName.isEmpty()) return null;

        // 检查方法名前是否有 '.'
        int beforeMethod = j;
        while (beforeMethod >= 0 && Character.isWhitespace(content.charAt(beforeMethod))) {
            beforeMethod--;
        }
        if (beforeMethod < 0 || content.charAt(beforeMethod) != '.') return null;

        int dotPos = beforeMethod;

        // 从 AST 语义分析推断 receiver 类型
        String receiverType = inferReceiverTypeFromAstAtOffset(uri, content, dotPos, cached);
        if (receiverType == null) return null;

        // 根据方法名确定 it 的类型
        return resolveItType(receiverType, methodName);
    }

    /**
     * 根据 receiver 类型和方法名确定 it 的类型。
     */
    private String resolveItType(String receiverType, String methodName) {
        // 作用域函数：it = receiver 类型
        if (SCOPE_FUNCTIONS.contains(methodName)) {
            return receiverType;
        }

        // 集合元素迭代函数：it = 元素类型
        if (COLLECTION_ELEMENT_FUNCTIONS.contains(methodName)) {
            String base = LspTextUtils.baseType(receiverType);
            List<String> genericArgs = LspTextUtils.genericArgs(receiverType);
            if ("List".equals(base) || "Set".equals(base)
                    || "java:java.util.ArrayList".equals(base)
                    || "java:java.util.HashSet".equals(base)
                    || "java:java.util.LinkedList".equals(base)) {
                return genericArgs.isEmpty() ? null : genericArgs.get(0);
            }
            if ("Range".equals(base)) {
                return "Int";
            }
        }

        return null;
    }

    /**
     * 找到未匹配的 { 后检查其前面的标识符是否是已注册的 receiver lambda 函数。
     */
    private StdlibRegistry.ReceiverLambdaInfo findReceiverLambdaAt(String content, int line, int character) {
        int cursorOffset = LspTextUtils.toOffset(content, line, character);
        if (cursorOffset <= 0 || cursorOffset > content.length()) return null;

        // 反向扫描，跟踪 {} 嵌套深度
        int depth = 0;
        for (int i = cursorOffset - 1; i >= 0; i--) {
            char ch = content.charAt(i);

            // 跳过字符串内部（简化：遇到引号就反向跳到匹配引号）
            if (ch == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                i--;
                while (i > 0 && !(content.charAt(i) == '"' && content.charAt(i - 1) != '\\')) {
                    i--;
                }
                continue;
            }

            if (ch == '}') {
                depth++;
            } else if (ch == '{') {
                if (depth > 0) {
                    depth--;
                } else {
                    // 找到了未匹配的 { — 提取它前面的标识符
                    int bracePos = i;
                    // 跳过 { 前面的空白
                    int j = bracePos - 1;
                    while (j >= 0 && Character.isWhitespace(content.charAt(j))) {
                        j--;
                    }
                    if (j < 0) return null;

                    // 可能有 ) — 跳过参数列表 (...)
                    if (content.charAt(j) == ')') {
                        int parenDepth = 1;
                        j--;
                        while (j >= 0 && parenDepth > 0) {
                            if (content.charAt(j) == ')') parenDepth++;
                            else if (content.charAt(j) == '(') parenDepth--;
                            j--;
                        }
                        // 跳过 ( 前面的空白
                        while (j >= 0 && Character.isWhitespace(content.charAt(j))) {
                            j--;
                        }
                        if (j < 0) return null;
                    }

                    // 提取标识符
                    int end = j + 1;
                    while (j >= 0 && (Character.isLetterOrDigit(content.charAt(j)) || content.charAt(j) == '_')) {
                        j--;
                    }
                    String funcName = content.substring(j + 1, end);
                    if (funcName.isEmpty()) return null;

                    StdlibRegistry.ReceiverLambdaInfo info = StdlibRegistry.getReceiverLambda(funcName);
                    if (info != null) {
                        return info;
                    }
                    // 不是 receiver lambda，继续向外层扫描
                }
            }
        }
        return null;
    }

    /**
     * 检测光标是否在接收者 Lambda 体内，如果是，补全 receiver 类型的方法。
     */
    private void addReceiverLambdaCompletions(JsonArray items,
                                               String content, int line, int character, String prefix) {
        if (javaClassResolver == null) return;

        StdlibRegistry.ReceiverLambdaInfo bestMatch = findReceiverLambdaAt(content, line, character);
        if (bestMatch == null) return;

        String javaClassName = bestMatch.receiverType.replace('/', '.');
        JavaClassInfo classInfo = javaClassResolver.resolve(javaClassName);
        if (classInfo == null) return;

        String shortName = javaClassName.substring(javaClassName.lastIndexOf('.') + 1);
        for (JavaClassInfo.MethodInfo m : classInfo.methods) {
            if (m.isStatic || "<init>".equals(m.name)) continue;
            String label = m.name + "(" + String.join(", ", m.paramTypes) + ")";
            addMethodItem(items, label, "this." + m.name + " → " + m.returnType, shortName, prefix);
        }
    }

    private void addMethodItem(JsonArray items, String label, String detail, String typeName, String prefix) {
        String methodName = label.contains("(") ? label.substring(0, label.indexOf('(')) : label;
        if (!prefix.isEmpty() && !methodName.startsWith(prefix)) return;

        JsonObject item = new JsonObject();
        item.addProperty("label", label);
        item.addProperty("detail", typeName + " - " + detail);

        if (!label.contains("(")) {
            item.addProperty("kind", COMPLETION_PROPERTY); // Property
            item.addProperty("insertText", methodName);
        } else {
            item.addProperty("kind", COMPLETION_METHOD); // Method
            item.addProperty("insertTextFormat", INSERT_TEXT_SNIPPET);
            if (label.endsWith("()")) {
                item.addProperty("insertText", methodName + "()$0");
            } else {
                item.addProperty("insertText", methodName + "($1)$0");
            }
        }

        items.add(item);
    }

    /** 构造方法所有重载的 Hover 文档 */
    private String buildMethodOverloadsHover(String receiverType, String methodName) {
        List<String> signatures = new ArrayList<String>();
        String base = LspTextUtils.baseType(receiverType);
        String typeName = base.startsWith("java:") ? base.substring(5) : base;

        // Nova 内置方法
        List<NovaTypeRegistry.MethodInfo> builtins = NovaTypeRegistry.getMethodsForType(base);
        if (builtins != null) {
            for (NovaTypeRegistry.MethodInfo m : builtins) {
                if (m.name.equals(methodName)) {
                    String label = m.isProperty ? m.name : buildRegistryMethodLabel(m);
                    String retType = m.returnType != null ? m.returnType : "Unit";
                    signatures.add("fun " + label + ": " + retType + "  // " + m.description);
                }
            }
        }

        // Java 类方法
        if (javaClassResolver != null) {
            String javaClassName = null;
            if (base.startsWith("java:")) {
                javaClassName = base.substring(5);
            } else if (NOVA_TO_JAVA_TYPE.containsKey(base)) {
                javaClassName = NOVA_TO_JAVA_TYPE.get(base);
            }
            if (javaClassName != null) {
                JavaClassInfo info = javaClassResolver.resolve(javaClassName);
                if (info != null) {
                    for (JavaClassInfo.MethodInfo m : info.methods) {
                        if (m.name.equals(methodName) && !m.isStatic) {
                            StringBuilder sig = new StringBuilder("fun " + m.name + "(");
                            for (int i = 0; i < m.paramTypes.size(); i++) {
                                if (i > 0) sig.append(", ");
                                sig.append("p").append(i).append(": ").append(m.paramTypes.get(i));
                            }
                            sig.append("): ").append(m.returnType);
                            signatures.add(sig.toString());
                        }
                    }
                }
            }
        }

        if (signatures.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(typeName).append(".").append(methodName).append("**");
        if (signatures.size() > 1) {
            sb.append(" — ").append(signatures.size()).append(" 个重载");
        }
        sb.append("\n\n");
        for (String sig : signatures) {
            sb.append("```nova\n").append(sig).append("\n```\n");
        }
        return sb.toString();
    }

    // ============ 类型推断（回退方案） ============

    /**
     * 从 AST 语义分析推断 '.' 前接收者表达式的类型。
     * 计算 '.' 在文档中的偏移量，在该位置之前查找最内层 AST 表达式并获取其类型。
     *
     * @param uri       文档 URI
     * @param content   文档文本内容
     * @param line      光标行（0-based）
     * @param character 光标列（0-based）
     * @param prefix    已输入的成员名前缀
     * @param cached    缓存的分析结果
     * @return 接收者类型名称，或 null
     */
    private String inferReceiverTypeFromAst(String uri, String content, int line, int character,
                                             String prefix, DocumentManager.CachedAnalysis cached) {
        // '.' 在行内的列位置
        int dotCol = character - prefix.length() - 1;
        if (dotCol < 0) return null;
        int dotOffset = LspTextUtils.toOffset(content, line, dotCol);
        if (dotOffset <= 0) return null;

        // 优先从 AST 表达式索引查找
        if (cached != null && cached.analysisResult != null) {
            AstExpressionIndex index = exprIndexCache.get(uri);
            if (index != null) {
                Expression expr = index.findInnermostAt(dotOffset - 1);
                if (expr != null) {
                    String type = resolveExpressionType(expr, content, cached);
                    if (type != null) return type;
                }
            }
        }

        // AST 查找失败（容错解析不完整），从文本提取 dot 前的简单标识符
        return resolveReceiverFromText(content, dotOffset, cached);
    }

    /**
     * 从文档偏移量处查找 AST 表达式类型（用于 lambda receiver 推断等场景）。
     *
     * @param uri       文档 URI
     * @param content   文档文本内容
     * @param dotOffset '.' 在文档中的字符偏移量
     * @param cached    缓存的分析结果
     * @return 接收者类型名称，或 null
     */
    private String inferReceiverTypeFromAstAtOffset(String uri, String content, int dotOffset,
                                                     DocumentManager.CachedAnalysis cached) {
        if (dotOffset <= 0) return null;

        // 优先从 AST 表达式索引查找
        if (cached != null && cached.analysisResult != null) {
            AstExpressionIndex index = exprIndexCache.get(uri);
            if (index != null) {
                Expression expr = index.findInnermostAt(dotOffset - 1);
                if (expr != null) {
                    String type = resolveExpressionType(expr, content, cached);
                    if (type != null) return type;
                }
            }
        }

        // AST 查找失败（容错解析不完整），从文本提取 dot 前的简单标识符
        return resolveReceiverFromText(content, dotOffset, cached);
    }

    /**
     * 从文本中提取 dot 前的标识符/表达式，通过符号表和变量声明扫描推断类型。
     * 仅在 AST 查找失败（容错解析不完整）时使用。
     */
    private String resolveReceiverFromText(String content, int dotOffset, DocumentManager.CachedAnalysis cached) {
        // 跳过 dot 前的空白
        int pos = dotOffset - 1;
        while (pos >= 0 && Character.isWhitespace(content.charAt(pos))) pos--;
        if (pos < 0) return null;

        char ch = content.charAt(pos);

        // 尝试提取简单标识符
        if (Character.isLetterOrDigit(ch) || ch == '_') {
            int end = pos + 1;
            while (pos >= 0 && (Character.isLetterOrDigit(content.charAt(pos)) || content.charAt(pos) == '_')) pos--;
            String name = content.substring(pos + 1, end);

            // 符号表查找
            if (!name.isEmpty() && cached != null && cached.analysisResult != null) {
                int line = 0, col = 0;
                for (int i = 0; i < pos + 1 && i < content.length(); i++) {
                    if (content.charAt(i) == '\n') { line++; col = 0; }
                    else col++;
                }
                Symbol sym = cached.analysisResult.getSymbolTable().resolve(name, line + 1, col + 1);
                if (sym != null && sym.getTypeName() != null) return sym.getTypeName();
            }

            // 变量声明扫描
            if (!name.isEmpty()) {
                String varType = inferVariableType(content, name);
                if (varType != null) return varType;
            }
        }

        // 通用回退：从 dot 前的文本推断表达式类型
        // 取当前行 dot 前的文本
        int lineStart = content.lastIndexOf('\n', dotOffset - 1) + 1;
        String beforeDot = content.substring(lineStart, dotOffset).trim();
        if (beforeDot.endsWith("?")) beforeDot = beforeDot.substring(0, beforeDot.length() - 1).trim();
        if (!beforeDot.isEmpty()) {
            return inferExprType(content, beforeDot, cached);
        }
        return null;
    }

    /**
     * 递归推断任意表达式的类型（文本扫描回退）
     *
     * <p>本方法及 {@link #inferVariableType}, {@link #inferThisType},
     * {@link #inferBuiltinCallReturnType}, {@link #inferListLiteralElementType},
     * {@link #inferCollectionGenericArgs} 是基于文本扫描的简易类型推断。
     * 外部调用点（completeMember, hover, extractItTypeFromBrace）已通过
     * {@link #inferReceiverTypeFromAst}/{@link #inferReceiverTypeFromAstAtOffset}
     * 优先使用 {@code AnalysisResult.getExprTypeName()} 的结构化结果，
     * 本方法仅作为语义分析不可用时（语法错误、AST 不完整）的回退。</p>
     */
    private String inferExprType(String content, String expr, DocumentManager.CachedAnalysis cached) {
        expr = expr.trim();
        if (expr.isEmpty()) return null;

        char lastChar = expr.charAt(expr.length() - 1);

        if (lastChar == '"') return "String";

        if (lastChar == ']') {
            int openBracket = LspTextUtils.findMatchingBracket(expr, expr.length() - 1);
            if (openBracket <= 0) return "List";
            String receiver = expr.substring(0, openBracket).trim();
            if (!receiver.isEmpty()) {
                String receiverType = inferExprType(content, receiver, cached);
                if (receiverType != null) {
                    String rBase = LspTextUtils.baseType(receiverType);
                    List<String> rGenArgs = LspTextUtils.genericArgs(receiverType);
                    if ("String".equals(rBase)) return "String";
                    if ("List".equals(rBase) || "java:java.util.ArrayList".equals(rBase)
                            || "java:java.util.LinkedList".equals(rBase)) {
                        return rGenArgs.isEmpty() ? null : rGenArgs.get(0);
                    }
                    if ("Map".equals(rBase) || "java:java.util.HashMap".equals(rBase)) {
                        return rGenArgs.size() < 2 ? null : rGenArgs.get(1);
                    }
                    if ("Array".equals(rBase)) {
                        return rGenArgs.isEmpty() ? null : rGenArgs.get(0);
                    }
                }
            }
            return null;
        }

        if (lastChar == '}') {
            // 尝试识别尾随 lambda: expr.method { ... }
            int openBrace = LspTextUtils.findMatchingBrace(expr, expr.length() - 1);
            if (openBrace > 0) {
                String beforeBrace = expr.substring(0, openBrace).trim();
                if (!beforeBrace.isEmpty() && beforeBrace.contains(".")) {
                    // 当作 expr.method() 处理
                    return inferExprType(content, beforeBrace + "()", cached);
                }
            }
            return "Map";
        }

        if (Character.isDigit(lastChar)) {
            if (expr.contains(".")) return "Double";
            return "Int";
        }

        if (expr.contains(" to ")) return "Pair";

        if (lastChar == ')') {
            int openIdx = LspTextUtils.findMatchingParen(expr, expr.length() - 1);
            if (openIdx < 0) return null;
            if (openIdx == 0) {
                String inner = expr.substring(1, expr.length() - 1).trim();
                int asIdx = inner.lastIndexOf(" as ");
                if (asIdx >= 0) {
                    return LspTextUtils.extractTypeWithGenerics(inner.substring(asIdx + 4).trim());
                }
                return inferExprType(content, inner, cached);
            }
            String beforeParen = expr.substring(0, openIdx).trim();
            if (beforeParen.isEmpty()) return null;
            int dotIdx = LspTextUtils.findLastDotOutsideParens(beforeParen);
            if (dotIdx >= 0) {
                String receiver = beforeParen.substring(0, dotIdx).trim();
                String methodName = beforeParen.substring(dotIdx + 1).trim();
                if (!receiver.isEmpty() && !methodName.isEmpty()) {
                    String receiverType = inferExprType(content, receiver, cached);
                    if (receiverType != null) {
                        // 流式推断：当 receiver 是简单变量且集合无泛型参数时，尝试从使用推断
                        if (LspTextUtils.genericArgs(receiverType).isEmpty() && LspTextUtils.isSimpleIdentifier(receiver)) {
                            String enhanced = enhanceWithFlowGenericArgs(content, receiver, receiverType, cached);
                            if (enhanced != null) receiverType = enhanced;
                        }
                        return resolveMethodReturnType(receiverType, methodName);
                    }
                }
            }
            if (Character.isUpperCase(beforeParen.charAt(0))) {
                return LspTextUtils.extractTypeWithGenerics(beforeParen);
            }
            // javaClass("className") / Java.type("className") → java:className
            if ("javaClass".equals(beforeParen) || "Java.type".equals(beforeParen)) {
                String argsStr = expr.substring(openIdx + 1, expr.length() - 1).trim();
                if (argsStr.startsWith("\"") && argsStr.endsWith("\"") && argsStr.length() > 2) {
                    return "java:" + argsStr.substring(1, argsStr.length() - 1);
                }
            }
            String funcRet = BUILTIN_FUNC_RETURN_TYPES.get(beforeParen);
            if (funcRet != null) return funcRet;
            // 查符号表中用户定义函数的返回类型
            if (cached != null && LspTextUtils.isSimpleIdentifier(beforeParen)) {
                Symbol funcSym = cached.analysisResult.getSymbolTable()
                        .getGlobalScope().resolve(beforeParen);
                if (funcSym != null && funcSym.getKind() == SymbolKind.FUNCTION
                        && funcSym.getTypeName() != null) {
                    return funcSym.getTypeName();
                }
            }
            return null;
        }

        if (LspTextUtils.isSimpleIdentifier(expr)) {
            if ("this".equals(expr)) return inferThisType(content);
            if ("Java".equals(expr)) return "__Java__";
            return inferVariableType(content, expr);
        }

        int exprStart = expr.length() - 1;
        while (exprStart >= 0 && (LspTextUtils.isIdentChar(expr.charAt(exprStart)) || expr.charAt(exprStart) == '.')) {
            exprStart--;
        }
        if (exprStart >= 0 && exprStart < expr.length() - 1) {
            String tail = expr.substring(exprStart + 1).trim();
            if (!tail.isEmpty() && !tail.equals(expr)) {
                return inferExprType(content, tail, cached);
            }
        }

        return null;
    }

    /** 文本扫描推断变量类型（回退） */
    private String inferVariableType(String content, String varName) {
        String[] lines = content.split("\n", -1);

        for (String l : lines) {
            String trimmed = l.trim();
            if (!trimmed.startsWith("val ") && !trimmed.startsWith("var ")) continue;
            int idx = LspTextUtils.findWholeWord(trimmed, varName);
            if (idx < 0) continue;
            String afterName = trimmed.substring(idx + varName.length()).trim();
            if (afterName.startsWith(":")) {
                String typeStr = afterName.substring(1).trim();
                String result = LspTextUtils.extractTypeWithGenerics(typeStr);
                if (result != null) return result;
            }
            if (afterName.startsWith("=")) {
                String valStr = afterName.substring(1).trim();
                if (valStr.isEmpty()) continue;
                if (valStr.contains(" to ")) return "Pair";
                if (valStr.startsWith("\"")) return "String";
                if (valStr.startsWith("[")) {
                    String elemType = inferListLiteralElementType(valStr);
                    return elemType != null ? "List<" + elemType + ">" : "List";
                }
                if (valStr.startsWith("#{")) {
                    // 区分 Map #{key: val} 和 Set #{val, val}
                    int firstComma = valStr.indexOf(',');
                    int firstColon = valStr.indexOf(':');
                    if (firstColon > 0 && (firstComma < 0 || firstColon < firstComma)) {
                        return "Map";
                    }
                    return "Set";
                }
                if (Character.isDigit(valStr.charAt(0)) || (valStr.charAt(0) == '-' && valStr.length() > 1 && Character.isDigit(valStr.charAt(1)))) {
                    return valStr.contains(".") ? "Double" : "Int";
                }
                if ("true".equals(valStr) || "false".equals(valStr)) return "Boolean";
                if (valStr.startsWith("async ") || valStr.startsWith("async{")) return "Future";
                if (valStr.startsWith("Java.type(\"") || valStr.startsWith("javaClass(\"")) {
                    int start = valStr.indexOf('"') + 1;
                    int end = valStr.indexOf('"', start);
                    if (end > start) return "java:" + valStr.substring(start, end);
                }
                if (Character.isUpperCase(valStr.charAt(0))) {
                    int parenIdx = valStr.indexOf('(');
                    if (parenIdx > 0) return LspTextUtils.extractTypeWithGenerics(valStr.substring(0, parenIdx));
                    return LspTextUtils.extractTypeWithGenerics(valStr);
                }
                String funcRet = inferBuiltinCallReturnType(valStr);
                if (funcRet != null) return funcRet;
            }
        }

        // 搜索函数参数
        for (String l : lines) {
            String trimmed = l.trim();
            if (!trimmed.contains("fun ")) continue;
            int parenStart = trimmed.indexOf('(');
            int parenEnd = LspTextUtils.findClosingParen(trimmed, parenStart);
            if (parenStart < 0 || parenEnd < 0) continue;
            String paramsStr = trimmed.substring(parenStart + 1, parenEnd);
            String type = LspTextUtils.findParamType(paramsStr, varName);
            if (type != null) return type;
        }

        // 搜索类构造参数
        for (String l : lines) {
            String trimmed = l.trim();
            if (!trimmed.startsWith("class ")) continue;
            int parenStart = trimmed.indexOf('(');
            int parenEnd = LspTextUtils.findClosingParen(trimmed, parenStart);
            if (parenStart < 0 || parenEnd < 0) continue;
            String paramsStr = trimmed.substring(parenStart + 1, parenEnd);
            String type = LspTextUtils.findParamType(paramsStr, varName);
            if (type != null) return type;
        }

        return null;
    }

    private String inferThisType(String content) {
        String[] lines = content.split("\n", -1);
        String lastClassName = null;
        for (String l : lines) {
            String trimmed = l.trim();
            if (trimmed.startsWith("class ")) {
                String rest = trimmed.substring(6).trim();
                String name = rest.split("[\\s({:<]")[0];
                if (!name.isEmpty()) lastClassName = name;
            }
        }
        return lastClassName;
    }

    private String resolveMethodReturnType(String receiverType, String methodName) {
        String base = LspTextUtils.baseType(receiverType);
        List<String> genArgs = LspTextUtils.genericArgs(receiverType);

        String novaTypeName = base.startsWith("java:") ? javaTypeToNova(base.substring(5)) : base;
        if (novaTypeName != null) {
            List<NovaTypeRegistry.MethodInfo> methods = NovaTypeRegistry.getMethodsForType(novaTypeName);
            if (methods != null) {
                for (NovaTypeRegistry.MethodInfo m : methods) {
                    if (m.name.equals(methodName) && m.returnType != null) {
                        String ret = m.returnType;
                        if (ret.startsWith("$") && ret.length() == 2) {
                            int idx = ret.charAt(1) - '0';
                            if (idx >= 0 && idx < genArgs.size()) return genArgs.get(idx);
                            return null;
                        }
                        return ret;
                    }
                }
            }
            List<NovaTypeRegistry.MethodInfo> anyMethods = NovaTypeRegistry.getMethodsForType("Any");
            if (anyMethods != null) {
                for (NovaTypeRegistry.MethodInfo m : anyMethods) {
                    if (m.name.equals(methodName) && m.returnType != null) return m.returnType;
                }
            }
        }

        if (javaClassResolver == null) return null;
        String javaClassName;
        if (base.startsWith("java:")) {
            javaClassName = base.substring(5);
        } else {
            javaClassName = NOVA_TO_JAVA_TYPE.get(base);
        }
        if (javaClassName == null) return null;
        JavaClassInfo info = javaClassResolver.resolve(javaClassName);
        if (info == null) return null;
        for (JavaClassInfo.MethodInfo m : info.methods) {
            if (m.name.equals(methodName)) {
                if (m.genericReturnTypeIndex >= 0 && m.genericReturnTypeIndex < genArgs.size()) {
                    return genArgs.get(m.genericReturnTypeIndex);
                }
                return mapJavaReturnType(m.returnType, m.returnTypeFullName);
            }
        }
        return null;
    }

    private String mapJavaReturnType(String simpleType, String fullName) {
        if ("void".equals(simpleType)) return null;
        if (simpleType.endsWith("[]")) {
            String elemSimple = simpleType.substring(0, simpleType.length() - 2);
            String elemFull = (fullName != null && fullName.endsWith("[]"))
                    ? fullName.substring(0, fullName.length() - 2) : null;
            String elemType = mapJavaReturnType(elemSimple, elemFull);
            if (elemType != null) return "List<" + elemType + ">";
            return "List";
        }
        switch (simpleType) {
            case "Boolean": case "Int": case "Long":
            case "Float": case "Double": case "String":
            case "Byte": case "Short": case "Char":
                return simpleType;
        }
        if (fullName != null && !fullName.isEmpty()) return "java:" + fullName;
        return null;
    }

    private String inferBuiltinCallReturnType(String valStr) {
        int parenIdx = valStr.indexOf('(');
        if (parenIdx <= 0) return null;
        String funcName = valStr.substring(0, parenIdx);
        if (!LspTextUtils.isSimpleIdentifier(funcName)) return null;
        return BUILTIN_FUNC_RETURN_TYPES.get(funcName);
    }

    /** 从列表字面量中推断元素类型（如 ["a","b"] → String, [1,2] → Int） */
    private String inferListLiteralElementType(String listLiteral) {
        if (listLiteral == null || !listLiteral.startsWith("[")) return null;
        String inner = listLiteral.length() > 2
                ? listLiteral.substring(1, listLiteral.length() - (listLiteral.endsWith("]") ? 1 : 0)).trim()
                : "";
        if (inner.isEmpty()) return null;
        // 取第一个元素判断类型
        String firstElem;
        int comma = -1;
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '[' || c == '(' || c == '{') depth++;
            else if (c == ']' || c == ')' || c == '}') depth--;
            else if (c == ',' && depth == 0) { comma = i; break; }
        }
        firstElem = (comma >= 0 ? inner.substring(0, comma) : inner).trim();
        if (firstElem.startsWith("\"")) return "String";
        if ("true".equals(firstElem) || "false".equals(firstElem)) return "Boolean";
        if (firstElem.matches("-?\\d+\\.\\d+")) return "Double";
        if (firstElem.matches("-?\\d+")) return "Int";
        return null;
    }

    // ============ 代码片段补全 ============

    private static final String[][] CODE_SNIPPETS = {
        {"if",    "if (condition) { ... }",
                  "if (${1:condition}) {\n\t$0\n}",
                  "if 条件语句"},
        {"ife",   "if (condition) { ... } else { ... }",
                  "if (${1:condition}) {\n\t$2\n} else {\n\t$0\n}",
                  "if-else 条件语句"},
        {"for",   "for (item in collection) { ... }",
                  "for (${1:item} in ${2:collection}) {\n\t$0\n}",
                  "for-in 循环"},
        {"fori",  "for (i in 0..<n) { ... }",
                  "for (${1:i} in 0..<${2:n}) {\n\t$0\n}",
                  "索引 for 循环"},
        {"while", "while (condition) { ... }",
                  "while (${1:condition}) {\n\t$0\n}",
                  "while 循环"},
        {"when",  "when (expr) { value -> ... }",
                  "when (${1:expr}) {\n\t${2:value} -> ${3:result}\n\telse -> ${0:default}\n}",
                  "when 分支表达式"},
        {"fun",   "fun name(params): Type { ... }",
                  "fun ${1:name}(${2:params})${3:: ${4:Unit}} {\n\t$0\n}",
                  "函数声明"},
        {"class", "class Name(params) { ... }",
                  "class ${1:Name}(${2:params}) {\n\t$0\n}",
                  "类声明"},
        {"data",  "data class Name(val field: Type)",
                  "data class ${1:Name}(val ${2:field}: ${3:Type})",
                  "数据类声明"},
        {"enum",  "enum class Name { ENTRY1, ENTRY2 }",
                  "enum class ${1:Name} {\n\t${2:ENTRY1},\n\t${3:ENTRY2}\n}",
                  "枚举类声明"},
        {"interface", "interface Name { ... }",
                  "interface ${1:Name} {\n\t$0\n}",
                  "接口声明"},
        {"object","object Name { ... }",
                  "object ${1:Name} {\n\t$0\n}",
                  "单例对象声明"},
        {"try",   "try { ... } catch (e) { ... }",
                  "try {\n\t$1\n} catch (${2:e}) {\n\t$0\n}",
                  "try-catch 异常捕获"},
        {"tryf",  "try { ... } catch (e) { ... } finally { ... }",
                  "try {\n\t$1\n} catch (${2:e}) {\n\t$3\n} finally {\n\t$0\n}",
                  "try-catch-finally"},
        {"main",  "fun main(args: List) { ... }",
                  "fun main(args: List) {\n\t$0\n}",
                  "主函数入口"},
        {"println","println(value)",
                  "println(${1:value})$0",
                  "打印并换行"},
        {"lambda","{ params -> body }",
                  "{ ${1:it} -> ${0:body} }",
                  "Lambda 表达式"},
        {"import","import java className",
                  "import java ${0:java.util.ArrayList}",
                  "导入 Java 类"},
        {"val",   "val name = value",
                  "val ${1:name} = ${0:value}",
                  "不可变变量声明"},
        {"var",   "var name = value",
                  "var ${1:name} = ${0:value}",
                  "可变变量声明"},
        {"forl",  "label@ for (item in collection) { ... }",
                  "${1:label}@ for (${2:item} in ${3:collection}) {\n\t$0\n}",
                  "带标签的 for 循环"},
        {"whilel","label@ while (condition) { ... }",
                  "${1:label}@ while (${2:condition}) {\n\t$0\n}",
                  "带标签的 while 循环"},
        {"dol",   "label@ do { ... } while (condition)",
                  "${1:label}@ do {\n\t$2\n} while (${0:condition})",
                  "带标签的 do-while 循环"},
        {"get",   "get() = expression",
                  "get() = ${0:field}",
                  "属性 getter 访问器"},
        {"getb",  "get() { ... }",
                  "get() {\n\t$0\n}",
                  "属性 getter 访问器（块体）"},
        {"set",   "set(value) { ... }",
                  "set(${1:value}) {\n\t$0\n}",
                  "属性 setter 访问器"},
        {"pset",  "private set",
                  "private set",
                  "私有 setter 访问器"},
    };

    /** 仅在类/属性上下文中有意义的片段触发词 */
    private static final Set<String> PROPERTY_ACCESSOR_SNIPPETS = new HashSet<>(
            Arrays.asList("get", "getb", "set", "pset"));

    private void addSnippetCompletions(JsonArray items, String prefix, Scope.ScopeType scopeType) {
        boolean inClassBody = scopeType == Scope.ScopeType.CLASS;
        for (String[] snippet : CODE_SNIPPETS) {
            String trigger = snippet[0];
            if (!prefix.isEmpty() && !trigger.startsWith(prefix)) continue;
            // getter/setter 片段仅在类体内显示
            if (PROPERTY_ACCESSOR_SNIPPETS.contains(trigger) && !inClassBody) continue;

            JsonObject item = new JsonObject();
            item.addProperty("label", snippet[1]);
            item.addProperty("kind", COMPLETION_SNIPPET);
            item.addProperty("detail", snippet[3]);
            item.addProperty("insertTextFormat", INSERT_TEXT_SNIPPET);
            item.addProperty("insertText", snippet[2]);
            item.addProperty("filterText", trigger);
            item.addProperty("sortText", "0_" + trigger);
            items.add(item);
        }
    }

    // ============ 字符串插值补全 ============

    private JsonArray completeInterpolation(String uri, String content, String prefix, int line, int character) {
        JsonArray items = new JsonArray();

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached != null) {
            SymbolTable st = cached.analysisResult.getSymbolTable();
            List<Symbol> visible = st.getVisibleSymbols(line + 1, character + 1);
            for (Symbol sym : visible) {
                if (sym.getKind() == SymbolKind.BUILTIN_FUNCTION || sym.getKind() == SymbolKind.BUILTIN_CONSTANT) continue;
                if (!prefix.isEmpty() && !sym.getName().startsWith(prefix)) continue;
                items.add(symbolToCompletionItem(sym));
            }
        }

        // 内置函数
        for (NovaTypeRegistry.FunctionInfo func : NovaTypeRegistry.getBuiltinFunctions()) {
            if (!prefix.isEmpty() && !func.name.startsWith(prefix)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("label", func.name);
            item.addProperty("kind", COMPLETION_FUNCTION);
            item.addProperty("detail", func.description + " → " + func.returnType);
            items.add(item);
        }

        // 内置常量
        for (NovaTypeRegistry.ConstantInfo c : NovaTypeRegistry.getBuiltinConstants()) {
            if (!prefix.isEmpty() && !c.name.startsWith(prefix)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("label", c.name);
            item.addProperty("kind", COMPLETION_CONSTANT);
            item.addProperty("detail", c.type);
            items.add(item);
        }

        return items;
    }

    // ============ 文本扫描回退：跳转定义 ============

    private JsonObject findLocalVarDefinition(String uri, String content, int cursorLine, String name) {
        String[] lines = content.split("\n", -1);
        if (cursorLine < 0 || cursorLine >= lines.length) return null;

        int defLine = -1, defCol = -1;
        int defDepth = -1;
        int depth = 0;

        for (int i = 0; i <= cursorLine && i < lines.length; i++) {
            String line = lines[i];
            boolean inStr = false;
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '"' && (j == 0 || line.charAt(j - 1) != '\\')) {
                    inStr = !inStr;
                    continue;
                }
                if (inStr) continue;
                if (c == '/' && j + 1 < line.length() && line.charAt(j + 1) == '/') break;
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    if (defDepth == depth) {
                        defLine = -1;
                        defCol = -1;
                        defDepth = -1;
                    }
                    depth--;
                    if (depth < 0) depth = 0;
                }
            }

            String trimmed = line.trim();
            if (trimmed.startsWith("//")) continue;

            int col = LspTextUtils.findVarDeclaration(line, name);
            if (col >= 0) {
                defLine = i;
                defCol = col;
                defDepth = depth;
            }
        }

        if (defLine < 0) return null;

        JsonObject location = new JsonObject();
        location.addProperty("uri", uri);
        location.add("range", createRange(defLine, defCol, defLine, defCol + name.length()));
        return location;
    }

    private String typeRefToString(TypeRef typeRef) {
        return typeRef.accept(new TypeRefVisitor<String>() {
            @Override
            public String visitSimple(SimpleType type) {
                return type.getName().getFullName();
            }

            @Override
            public String visitNullable(NullableType type) {
                return typeRefToString(type.getInnerType()) + "?";
            }

            @Override
            public String visitGeneric(GenericType gt) {
                StringBuilder sb = new StringBuilder(gt.getName().getFullName());
                sb.append("<");
                for (int i = 0; i < gt.getTypeArgs().size(); i++) {
                    if (i > 0) sb.append(", ");
                    TypeRef argType = gt.getTypeArgs().get(i).getType();
                    sb.append(argType != null ? typeRefToString(argType) : "*");
                }
                sb.append(">");
                return sb.toString();
            }

            @Override
            public String visitFunction(FunctionType type) {
                return "?";
            }
        });
    }

    // ============ JSON 工具 ============

    private JsonObject createRange(int startLine, int startChar, int endLine, int endChar) {
        JsonObject range = new JsonObject();
        JsonObject start = new JsonObject();
        start.addProperty("line", startLine);
        start.addProperty("character", startChar);
        range.add("start", start);
        JsonObject end = new JsonObject();
        end.addProperty("line", endLine);
        end.addProperty("character", endChar);
        range.add("end", end);
        return range;
    }

    private JsonObject createHover(String markdownContent) {
        JsonObject hover = new JsonObject();
        JsonObject contents = new JsonObject();
        contents.addProperty("kind", "markdown");
        contents.addProperty("value", markdownContent);
        hover.add("contents", contents);
        return hover;
    }

    // ============ 关键词描述 ============

    private static final Map<String, String> KEYWORD_DESCRIPTIONS = new HashMap<String, String>();
    static {
        // 声明
        KEYWORD_DESCRIPTIONS.put("val", "**val** — 不可变变量声明\n\n```nova\nval name = \"Nova\"\n```");
        KEYWORD_DESCRIPTIONS.put("var", "**var** — 可变变量声明\n\n```nova\nvar count = 0\ncount = count + 1\n```");
        KEYWORD_DESCRIPTIONS.put("fun", "**fun** — 函数声明\n\n```nova\nfun greet(name: String): String = \"Hello, $name!\"\n```");
        KEYWORD_DESCRIPTIONS.put("class", "**class** — 类声明\n\n```nova\nclass Point(val x: Int, val y: Int)\n```");
        KEYWORD_DESCRIPTIONS.put("interface", "**interface** — 接口声明，支持抽象方法和默认实现\n\n```nova\ninterface Drawable {\n    fun draw()\n}\n```");
        KEYWORD_DESCRIPTIONS.put("object", "**object** — 单例对象声明\n\n```nova\nobject Logger {\n    fun log(msg: String) = println(msg)\n}\n```");
        KEYWORD_DESCRIPTIONS.put("enum", "**enum** — 枚举类声明\n\n```nova\nenum class Color { RED, GREEN, BLUE }\n```");
        KEYWORD_DESCRIPTIONS.put("constructor", "**constructor** — 次级构造器\n\n```nova\nclass Rect {\n    constructor(size: Int) : this(size, size)\n}\n```");
        KEYWORD_DESCRIPTIONS.put("typealias", "**typealias** — 类型别名");
        KEYWORD_DESCRIPTIONS.put("annotation", "**annotation** — 注解类声明\n\n```nova\nannotation class Serializable\n```");
        KEYWORD_DESCRIPTIONS.put("companion", "**companion** — 伴生对象");
        KEYWORD_DESCRIPTIONS.put("import", "**import** — 导入模块或 Java 类\n\n```nova\nimport java java.util.ArrayList\nimport models.User\nimport static java.lang.Math.PI\n```");
        KEYWORD_DESCRIPTIONS.put("package", "**package** — 包声明");
        KEYWORD_DESCRIPTIONS.put("module", "**module** — 模块声明");
        // 控制流
        KEYWORD_DESCRIPTIONS.put("if", "**if** — 条件表达式\n\n```nova\nval result = if (x > 0) \"positive\" else \"non-positive\"\n```");
        KEYWORD_DESCRIPTIONS.put("else", "**else** — 条件分支的否则子句");
        KEYWORD_DESCRIPTIONS.put("when", "**when** — 分支表达式（类似 switch）\n\n```nova\nwhen (x) {\n    1 -> \"one\"\n    2 -> \"two\"\n    else -> \"other\"\n}\n```");
        KEYWORD_DESCRIPTIONS.put("for", "**for** — for-in 循环\n\n```nova\nfor (item in list) { println(item) }\nfor (i in 0..<10 step 2) { println(i) }\n```");
        KEYWORD_DESCRIPTIONS.put("while", "**while** — while 循环\n\n```nova\nwhile (condition) { ... }\n```");
        KEYWORD_DESCRIPTIONS.put("do", "**do** — do-while 循环\n\n```nova\ndo { ... } while (condition)\n```");
        KEYWORD_DESCRIPTIONS.put("return", "**return** — 从函数返回值");
        KEYWORD_DESCRIPTIONS.put("break", "**break** — 跳出循环");
        KEYWORD_DESCRIPTIONS.put("continue", "**continue** — 跳过本次循环，进入下一次迭代");
        KEYWORD_DESCRIPTIONS.put("throw", "**throw** — 抛出异常");
        KEYWORD_DESCRIPTIONS.put("try", "**try** — 异常捕获块\n\n```nova\ntry { ... } catch (e) { ... } finally { ... }\n```");
        KEYWORD_DESCRIPTIONS.put("catch", "**catch** — 捕获异常");
        KEYWORD_DESCRIPTIONS.put("finally", "**finally** — try 块结束后一定执行的代码");
        KEYWORD_DESCRIPTIONS.put("guard", "**guard** — 守卫语句，条件不满足时提前返回\n\n```nova\nguard (x != null) else { return }\n```");
        KEYWORD_DESCRIPTIONS.put("use", "**use** — 资源管理（自动关闭）\n\n```nova\nuse (val file = openFile(\"data.txt\")) { ... }\n```");
        KEYWORD_DESCRIPTIONS.put("step", "**step** — 范围步长\n\n```nova\nfor (i in 0..10 step 2) { println(i) }\n```");
        // 操作符关键词
        KEYWORD_DESCRIPTIONS.put("as", "**as** — 类型转换\n\n```nova\nval str = obj as String\n```");
        KEYWORD_DESCRIPTIONS.put("is", "**is** — 类型检查\n\n```nova\nif (obj is String) { println(obj.length()) }\n```");
        KEYWORD_DESCRIPTIONS.put("in", "**in** — 包含检查 / for-in 循环\n\n```nova\nif (x in list) { ... }\nfor (item in list) { ... }\n```");
        KEYWORD_DESCRIPTIONS.put("to", "**to** — 创建 Pair\n\n```nova\nval pair = \"key\" to 42\n```");
        KEYWORD_DESCRIPTIONS.put("out", "**out** — 协变类型参数");
        KEYWORD_DESCRIPTIONS.put("where", "**where** — 泛型类型约束");
        // 修饰符
        KEYWORD_DESCRIPTIONS.put("abstract", "**abstract** — 抽象类或抽象方法");
        KEYWORD_DESCRIPTIONS.put("open", "**open** — 允许继承的类或可覆盖的方法");
        KEYWORD_DESCRIPTIONS.put("sealed", "**sealed** — 密封类，限制子类在同一文件");
        KEYWORD_DESCRIPTIONS.put("override", "**override** — 覆盖父类方法");
        KEYWORD_DESCRIPTIONS.put("private", "**private** — 私有可见性");
        KEYWORD_DESCRIPTIONS.put("protected", "**protected** — 受保护可见性（子类可访问）");
        KEYWORD_DESCRIPTIONS.put("public", "**public** — 公共可见性（默认）");
        KEYWORD_DESCRIPTIONS.put("internal", "**internal** — 模块内部可见性");
        KEYWORD_DESCRIPTIONS.put("final", "**final** — 不可继承/覆盖");
        KEYWORD_DESCRIPTIONS.put("const", "**const** — 编译期常量");
        KEYWORD_DESCRIPTIONS.put("inline", "**inline** — 内联函数");
        KEYWORD_DESCRIPTIONS.put("operator", "**operator** — 操作符重载函数\n\n```nova\noperator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)\n```");
        KEYWORD_DESCRIPTIONS.put("suspend", "**suspend** — 可暂停函数");
        KEYWORD_DESCRIPTIONS.put("static", "**static** — 静态成员");
        KEYWORD_DESCRIPTIONS.put("data", "**data** — 数据类修饰符，自动生成 toString/equals/hashCode/copy");
        KEYWORD_DESCRIPTIONS.put("lazy", "**lazy** — 延迟初始化");
        // 特殊
        KEYWORD_DESCRIPTIONS.put("this", "**this** — 当前对象引用");
        KEYWORD_DESCRIPTIONS.put("super", "**super** — 父类引用");
        KEYWORD_DESCRIPTIONS.put("it", "**it** — Lambda 隐式参数（单参数时）\n\n```nova\nlist.filter { it > 0 }\n```");
        KEYWORD_DESCRIPTIONS.put("async", "**async** — 异步执行块\n\n```nova\nval future = async { compute() }\n```");
        KEYWORD_DESCRIPTIONS.put("await", "**await** — 等待异步结果\n\n```nova\nval result = await(future)\n```");
        KEYWORD_DESCRIPTIONS.put("launch", "**launch** — 启动协程");
        KEYWORD_DESCRIPTIONS.put("scope", "**scope** — 协程作用域");
    }

    private String getKeywordHover(String word) {
        return KEYWORD_DESCRIPTIONS.get(word);
    }

    // ============ 成员方法参数个数检查 ============

    /**
     * 遍历 AST 检查所有成员方法调用的参数个数
     */
    private void checkMethodArgCounts(DocumentManager.CachedAnalysis cached, String content,
                                       JsonArray diagnostics) {
        List<CallExpr> memberCalls = new ArrayList<CallExpr>();

        // 遍历声明（函数体、属性初始化等）
        collectMemberCalls(cached.parseResult.getProgram(), memberCalls);

        // 遍历顶层语句（parseTolerant 中保留的非声明语句）
        for (Statement stmt : cached.parseResult.getTopLevelStatements()) {
            collectMemberCalls(stmt, memberCalls);
        }

        for (CallExpr call : memberCalls) {
            MemberExpr member = (MemberExpr) call.getCallee();
            String methodName = member.getMember();

            // 推断 receiver 类型
            String receiverType = resolveExpressionType(member.getTarget(), content, cached);
            if (receiverType == null) continue;

            // 获取该方法的合法参数个数集合
            Set<Integer> validArities = getMethodArities(receiverType, methodName);
            if (validArities == null) continue; // 未知方法或变参，跳过

            // 计算实际参数个数
            int actualArgs = call.getArgs().size();
            if (call.getTrailingLambda() != null) actualArgs++;

            // 不匹配则报错
            if (!validArities.contains(actualArgs)) {
                // 优先使用方法名的位置
                int line = -1, col = -1;
                if (member.getLocation() != null) {
                    line = member.getLocation().getLine() - 1;
                    col = member.getLocation().getColumn() - 1;
                    // MemberExpr 的位置是 target 的起始位置，方法名在 '.' 之后
                    // 通过源码文本定位方法名
                    String[] lines = content.split("\n", -1);
                    if (line >= 0 && line < lines.length) {
                        int dotIdx = lines[line].indexOf("." + methodName, col);
                        if (dotIdx >= 0) col = dotIdx + 1;
                    }
                }
                if (line < 0 && call.getLocation() != null) {
                    line = call.getLocation().getLine() - 1;
                    col = call.getLocation().getColumn() - 1;
                }
                if (line >= 0) {
                    String expected;
                    if (validArities.size() == 1) {
                        expected = String.valueOf(validArities.iterator().next());
                    } else {
                        List<Integer> sorted = new ArrayList<Integer>(validArities);
                        Collections.sort(sorted);
                        expected = sorted.toString();
                    }
                    String msg = "方法 '" + methodName + "' 需要 " + expected
                            + " 个参数，实际传入 " + actualArgs + " 个";
                    JsonObject diag = new JsonObject();
                    diag.add("range", createRange(line, col, line, col + methodName.length()));
                    diag.addProperty("severity", SEVERITY_ERROR);
                    diag.addProperty("source", "nova");
                    diag.addProperty("message", msg);
                    diagnostics.add(diag);
                }
            }
        }
    }

    /**
     * 递归遍历 AST，收集所有 callee 为 MemberExpr 的 CallExpr
     */
    private void collectMemberCalls(AstNode node, List<CallExpr> calls) {
        if (node == null) return;

        // 表达式
        if (node instanceof CallExpr) {
            CallExpr call = (CallExpr) node;
            if (call.getCallee() instanceof MemberExpr) {
                calls.add(call);
            }
            collectMemberCalls(call.getCallee(), calls);
            for (CallExpr.Argument arg : call.getArgs()) {
                collectMemberCalls(arg.getValue(), calls);
            }
            if (call.getTrailingLambda() != null) collectMemberCalls(call.getTrailingLambda(), calls);
        } else if (node instanceof MemberExpr) {
            collectMemberCalls(((MemberExpr) node).getTarget(), calls);
        } else if (node instanceof BinaryExpr) {
            collectMemberCalls(((BinaryExpr) node).getLeft(), calls);
            collectMemberCalls(((BinaryExpr) node).getRight(), calls);
        } else if (node instanceof UnaryExpr) {
            collectMemberCalls(((UnaryExpr) node).getOperand(), calls);
        } else if (node instanceof AssignExpr) {
            collectMemberCalls(((AssignExpr) node).getTarget(), calls);
            collectMemberCalls(((AssignExpr) node).getValue(), calls);
        } else if (node instanceof IndexExpr) {
            collectMemberCalls(((IndexExpr) node).getTarget(), calls);
            collectMemberCalls(((IndexExpr) node).getIndex(), calls);
        } else if (node instanceof LambdaExpr) {
            collectMemberCalls(((LambdaExpr) node).getBody(), calls);
        } else if (node instanceof Identifier || node instanceof Literal) {
            // 叶子节点
        }

        // 语句
        else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectMemberCalls(s, calls);
        } else if (node instanceof ExpressionStmt) {
            collectMemberCalls(((ExpressionStmt) node).getExpression(), calls);
        } else if (node instanceof DeclarationStmt) {
            collectMemberCalls(((DeclarationStmt) node).getDeclaration(), calls);
        } else if (node instanceof IfStmt) {
            collectMemberCalls(((IfStmt) node).getCondition(), calls);
            collectMemberCalls(((IfStmt) node).getThenBranch(), calls);
            collectMemberCalls(((IfStmt) node).getElseBranch(), calls);
        } else if (node instanceof WhileStmt) {
            collectMemberCalls(((WhileStmt) node).getCondition(), calls);
            collectMemberCalls(((WhileStmt) node).getBody(), calls);
        } else if (node instanceof ForStmt) {
            collectMemberCalls(((ForStmt) node).getIterable(), calls);
            collectMemberCalls(((ForStmt) node).getBody(), calls);
        } else if (node instanceof ReturnStmt) {
            collectMemberCalls(((ReturnStmt) node).getValue(), calls);
        }

        // 声明
        else if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations()) collectMemberCalls(d, calls);
        } else if (node instanceof ClassDecl) {
            for (Declaration m : ((ClassDecl) node).getMembers()) collectMemberCalls(m, calls);
        } else if (node instanceof FunDecl) {
            collectMemberCalls(((FunDecl) node).getBody(), calls);
        } else if (node instanceof PropertyDecl) {
            collectMemberCalls(((PropertyDecl) node).getInitializer(), calls);
        }
    }

    /**
     * 从 AST 表达式推断类型
     */
    private String resolveExpressionType(Expression expr, String content,
                                          DocumentManager.CachedAnalysis cached) {
        // 优先从 SemanticAnalyzer 的 exprNovaTypeMap 查找（最精确）
        if (cached != null && cached.analysisResult != null) {
            String fromMap = cached.analysisResult.getExprTypeName(expr);
            if (fromMap != null) return fromMap;
        }

        if (expr instanceof Identifier) {
            String name = ((Identifier) expr).getName();
            if ("this".equals(name)) return inferThisType(content);
            // 符号表查找
            if (cached != null && cached.analysisResult != null && expr.getLocation() != null) {
                Symbol sym = cached.analysisResult.getSymbolTable().resolve(
                        name, expr.getLocation().getLine(), expr.getLocation().getColumn());
                if (sym != null && sym.getTypeName() != null) return sym.getTypeName();
            }
            return inferVariableType(content, name);
        }
        // 复杂表达式：从源码文本推断（fallback）
        if (expr.getLocation() != null && expr.getLocation().getOffset() >= 0) {
            int offset = expr.getLocation().getOffset();
            int length = expr.getLocation().getLength();
            if (offset >= 0 && length > 0 && offset + length <= content.length()) {
                String text = content.substring(offset, offset + length);
                return inferExprType(content, text, cached);
            }
        }
        return null;
    }

    /**
     * 获取指定类型上指定方法的合法参数个数集合。
     * 返回 null 表示未知方法或变参（跳过检查）。
     */
    private Set<Integer> getMethodArities(String receiverType, String methodName) {
        Set<Integer> arities = new HashSet<Integer>();
        String base = LspTextUtils.baseType(receiverType);

        // 1. NovaTypeRegistry（内置类型）
        String novaType = base.startsWith("java:") ? javaTypeToNova(base.substring(5)) : base;
        if (novaType != null) {
            List<NovaTypeRegistry.MethodInfo> methods = NovaTypeRegistry.getMethodsForType(novaType);
            if (methods != null) {
                for (NovaTypeRegistry.MethodInfo m : methods) {
                    if (m.name.equals(methodName)) {
                        if (m.arity == -1) return null; // 变参
                        arities.add(m.isProperty ? 0 : m.arity);
                    }
                }
            }
            // Any 通用方法
            List<NovaTypeRegistry.MethodInfo> anyMethods = NovaTypeRegistry.getMethodsForType("Any");
            if (anyMethods != null) {
                for (NovaTypeRegistry.MethodInfo m : anyMethods) {
                    if (m.name.equals(methodName)) {
                        if (m.arity == -1) return null;
                        arities.add(m.arity);
                    }
                }
            }
        }

        // 2. JavaClassResolver（Java 类方法，合并重载）
        if (javaClassResolver != null) {
            String javaClassName = null;
            if (base.startsWith("java:")) {
                javaClassName = base.substring(5);
            } else if (NOVA_TO_JAVA_TYPE.containsKey(base)) {
                javaClassName = NOVA_TO_JAVA_TYPE.get(base);
            }
            if (javaClassName != null) {
                JavaClassInfo info = javaClassResolver.resolve(javaClassName);
                if (info != null) {
                    for (JavaClassInfo.MethodInfo m : info.methods) {
                        if (m.name.equals(methodName) && !m.isStatic) {
                            arities.add(m.paramTypes.size());
                        }
                    }
                }
            }
        }

        // 3. 用户定义类的成员方法
        // (暂不在此实现，后续可扩展)

        return arities.isEmpty() ? null : arities;
    }

    // ============ 集合泛型流式推断 ============

    /**
     * 如果 receiverType 是无泛型参数的集合类型，尝试从 AST 使用模式推断泛型参数。
     * 返回增强后的类型字符串（如 "java:java.util.ArrayList<String>"），或 null。
     */
    private String enhanceWithFlowGenericArgs(String content, String varName, String receiverType,
                                               DocumentManager.CachedAnalysis cached) {
        String base = LspTextUtils.baseType(receiverType);
        String novaType = base.startsWith("java:") ? javaTypeToNova(base.substring(5)) : base;
        if (novaType == null) return null;

        List<String> inferred = inferCollectionGenericArgs(content, varName, novaType, cached);
        if (inferred == null || inferred.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(base);
        sb.append("<");
        for (int i = 0; i < inferred.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(inferred.get(i));
        }
        sb.append(">");
        return sb.toString();
    }

    /**
     * 从 AST 中收集对 varName 的 add/put/索引赋值，推断集合泛型参数。
     */
    private List<String> inferCollectionGenericArgs(String content, String varName, String novaType,
                                                    DocumentManager.CachedAnalysis cached) {
        if (cached == null) return null;

        List<String> elementTypes = new ArrayList<String>();
        List<String> keyTypes = new ArrayList<String>();
        List<String> valueTypes = new ArrayList<String>();

        // 遍历声明 + 顶层语句
        for (Declaration d : cached.parseResult.getProgram().getDeclarations()) {
            collectFlowTypeInfo(d, varName, content, elementTypes, keyTypes, valueTypes, cached);
        }
        for (Statement stmt : cached.parseResult.getTopLevelStatements()) {
            collectFlowTypeInfo(stmt, varName, content, elementTypes, keyTypes, valueTypes, cached);
        }

        if ("List".equals(novaType) || "Set".equals(novaType)) {
            String elemType = getConsistentType(elementTypes);
            if (elemType != null) return Collections.singletonList(elemType);
        } else if ("Map".equals(novaType)) {
            String keyType = getConsistentType(keyTypes);
            String valType = getConsistentType(valueTypes);
            if (keyType != null && valType != null) {
                List<String> result = new ArrayList<String>();
                result.add(keyType);
                result.add(valType);
                return result;
            }
        }
        return null;
    }

    /**
     * 递归遍历 AST 节点，收集对 varName 的 add(x)/put(k,v)/[i]=x 调用的参数类型。
     */
    private void collectFlowTypeInfo(AstNode node, String varName, String content,
                                      List<String> elementTypes, List<String> keyTypes,
                                      List<String> valueTypes,
                                      DocumentManager.CachedAnalysis cached) {
        if (node == null) return;

        if (node instanceof CallExpr) {
            CallExpr call = (CallExpr) node;
            if (call.getCallee() instanceof MemberExpr) {
                MemberExpr member = (MemberExpr) call.getCallee();
                if (member.getTarget() instanceof Identifier
                        && varName.equals(((Identifier) member.getTarget()).getName())) {
                    String method = member.getMember();
                    if ("add".equals(method) && call.getArgs().size() == 1) {
                        String t = resolveExpressionType(call.getArgs().get(0).getValue(), content, cached);
                        if (t != null) elementTypes.add(t);
                    } else if ("put".equals(method) && call.getArgs().size() == 2) {
                        String kt = resolveExpressionType(call.getArgs().get(0).getValue(), content, cached);
                        String vt = resolveExpressionType(call.getArgs().get(1).getValue(), content, cached);
                        if (kt != null) keyTypes.add(kt);
                        if (vt != null) valueTypes.add(vt);
                    }
                }
            }
            // 递归子表达式
            collectFlowTypeInfo(call.getCallee(), varName, content, elementTypes, keyTypes, valueTypes, cached);
            for (CallExpr.Argument arg : call.getArgs()) {
                collectFlowTypeInfo(arg.getValue(), varName, content, elementTypes, keyTypes, valueTypes, cached);
            }
        } else if (node instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) node;
            // varName[i] = x → List 元素类型 / Map key+value 类型
            if (assign.getTarget() instanceof IndexExpr) {
                IndexExpr idx = (IndexExpr) assign.getTarget();
                if (idx.getTarget() instanceof Identifier
                        && varName.equals(((Identifier) idx.getTarget()).getName())) {
                    String vt = resolveExpressionType(assign.getValue(), content, cached);
                    if (vt != null) {
                        elementTypes.add(vt);
                        valueTypes.add(vt);
                    }
                    String kt = resolveExpressionType(idx.getIndex(), content, cached);
                    if (kt != null) keyTypes.add(kt);
                }
            }
            collectFlowTypeInfo(assign.getTarget(), varName, content, elementTypes, keyTypes, valueTypes, cached);
            collectFlowTypeInfo(assign.getValue(), varName, content, elementTypes, keyTypes, valueTypes, cached);
        } else if (node instanceof ExpressionStmt) {
            collectFlowTypeInfo(((ExpressionStmt) node).getExpression(), varName, content,
                    elementTypes, keyTypes, valueTypes, cached);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) {
                collectFlowTypeInfo(s, varName, content, elementTypes, keyTypes, valueTypes, cached);
            }
        } else if (node instanceof DeclarationStmt) {
            collectFlowTypeInfo(((DeclarationStmt) node).getDeclaration(), varName, content,
                    elementTypes, keyTypes, valueTypes, cached);
        } else if (node instanceof PropertyDecl) {
            collectFlowTypeInfo(((PropertyDecl) node).getInitializer(), varName, content,
                    elementTypes, keyTypes, valueTypes, cached);
        } else if (node instanceof FunDecl) {
            collectFlowTypeInfo(((FunDecl) node).getBody(), varName, content,
                    elementTypes, keyTypes, valueTypes, cached);
        } else if (node instanceof ClassDecl) {
            for (Declaration m : ((ClassDecl) node).getMembers()) {
                collectFlowTypeInfo(m, varName, content, elementTypes, keyTypes, valueTypes, cached);
            }
        } else if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations()) {
                collectFlowTypeInfo(d, varName, content, elementTypes, keyTypes, valueTypes, cached);
            }
        }
    }

    /** 如果列表中所有类型一致则返回该类型，否则返回 null */
    private String getConsistentType(List<String> types) {
        if (types.isEmpty()) return null;
        String first = types.get(0);
        for (int i = 1; i < types.size(); i++) {
            if (!first.equals(types.get(i))) return null;
        }
        return first;
    }

    // ============ val 重赋值检查 ============

    /**
     * 检查对 val 声明变量的重赋值
     */
    private void checkValReassignment(DocumentManager.CachedAnalysis cached, String content,
                                       JsonArray diagnostics) {
        // 1. 收集所有 val 声明的变量名
        Set<String> valNames = new HashSet<String>();
        for (Declaration d : cached.parseResult.getProgram().getDeclarations()) {
            if (d instanceof PropertyDecl && ((PropertyDecl) d).isVal()) {
                valNames.add(d.getName());
            }
        }
        if (valNames.isEmpty()) return;

        // 2. 在顶层语句和声明体中查找对这些变量的赋值
        List<AssignExpr> assigns = new ArrayList<AssignExpr>();
        for (Statement stmt : cached.parseResult.getTopLevelStatements()) {
            collectAssignExprs(stmt, assigns);
        }
        for (Declaration d : cached.parseResult.getProgram().getDeclarations()) {
            collectAssignExprs(d, assigns);
        }

        // 3. 检查是否赋值给 val 变量
        for (AssignExpr assign : assigns) {
            String targetName = null;
            if (assign.getTarget() instanceof Identifier) {
                targetName = ((Identifier) assign.getTarget()).getName();
            }
            if (targetName != null && valNames.contains(targetName)) {
                if (assign.getLocation() != null) {
                    int line = assign.getLocation().getLine() - 1;
                    int col = assign.getLocation().getColumn() - 1;
                    String msg = "val '" + targetName + "' 不可重新赋值";
                    JsonObject diag = new JsonObject();
                    diag.add("range", createRange(line, col, line, col + targetName.length()));
                    diag.addProperty("severity", SEVERITY_ERROR);
                    diag.addProperty("source", "nova");
                    diag.addProperty("message", msg);
                    diagnostics.add(diag);
                }
            }
        }
    }

    /** const val 检查：const 必须是 val、必须有初始化器、初始化器必须是编译期常量 */
    private void checkConstVal(DocumentManager.CachedAnalysis cached, JsonArray diagnostics) {
        List<PropertyDecl> props = new ArrayList<PropertyDecl>();
        collectAllPropertyDecls(cached.parseResult.getProgram(), props);
        for (Statement stmt : cached.parseResult.getTopLevelStatements()) {
            collectAllPropertyDecls(stmt, props);
        }

        // 收集已知的 const 名称，用于检查 const 引用
        Set<String> knownConsts = new HashSet<String>();
        for (PropertyDecl p : props) {
            if (p.isConst() && p.isVal() && p.hasInitializer()) {
                knownConsts.add(p.getName());
            }
        }

        for (PropertyDecl prop : props) {
            if (!prop.isConst()) continue;
            SourceLocation loc = prop.getLocation();
            if (loc == null) continue;
            int line = loc.getLine() - 1;
            int col = loc.getColumn() - 1;

            if (!prop.isVal()) {
                JsonObject diag = new JsonObject();
                diag.add("range", createRange(line, col, line, col + prop.getName().length() + 10));
                diag.addProperty("severity", SEVERITY_ERROR);
                diag.addProperty("source", "nova");
                diag.addProperty("message", "const must be val");
                diagnostics.add(diag);
            }
            if (!prop.hasInitializer()) {
                JsonObject diag = new JsonObject();
                diag.add("range", createRange(line, col, line, col + prop.getName().length() + 10));
                diag.addProperty("severity", SEVERITY_ERROR);
                diag.addProperty("source", "nova");
                diag.addProperty("message", "const val must have an initializer");
                diagnostics.add(diag);
            } else if (!isLspCompileTimeConstant(prop.getInitializer(), knownConsts)) {
                SourceLocation initLoc = prop.getInitializer().getLocation();
                int initLine = initLoc != null ? initLoc.getLine() - 1 : line;
                int initCol = initLoc != null ? initLoc.getColumn() - 1 : col;
                int initLen = initLoc != null ? Math.max(initLoc.getLength(), 1) : 1;
                JsonObject diag = new JsonObject();
                diag.add("range", createRange(initLine, initCol, initLine, initCol + initLen));
                diag.addProperty("severity", SEVERITY_ERROR);
                diag.addProperty("source", "nova");
                diag.addProperty("message", "const val initializer must be a compile-time constant");
                diagnostics.add(diag);
            }
        }
    }

    /** 检查表达式是否为编译期常量（LSP 版，不依赖符号表） */
    private boolean isLspCompileTimeConstant(Expression expr, Set<String> knownConsts) {
        if (expr instanceof Literal) {
            return ((Literal) expr).getKind() != Literal.LiteralKind.NULL;
        }
        if (expr instanceof Identifier) {
            return knownConsts.contains(((Identifier) expr).getName());
        }
        if (expr instanceof UnaryExpr) {
            UnaryExpr unary = (UnaryExpr) expr;
            if (unary.getOperator() == UnaryExpr.UnaryOp.NEG ||
                unary.getOperator() == UnaryExpr.UnaryOp.POS) {
                return isLspCompileTimeConstant(unary.getOperand(), knownConsts);
            }
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr binary = (BinaryExpr) expr;
            BinaryExpr.BinaryOp op = binary.getOperator();
            if (op == BinaryExpr.BinaryOp.ADD || op == BinaryExpr.BinaryOp.SUB ||
                op == BinaryExpr.BinaryOp.MUL || op == BinaryExpr.BinaryOp.DIV ||
                op == BinaryExpr.BinaryOp.MOD) {
                return isLspCompileTimeConstant(binary.getLeft(), knownConsts) &&
                       isLspCompileTimeConstant(binary.getRight(), knownConsts);
            }
            return false;
        }
        return false;
    }

    /** 递归收集所有 PropertyDecl */
    private void collectAllPropertyDecls(AstNode node, List<PropertyDecl> props) {
        if (node == null) return;
        if (node instanceof PropertyDecl) {
            props.add((PropertyDecl) node);
        } else if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations()) collectAllPropertyDecls(d, props);
        } else if (node instanceof ClassDecl) {
            for (Declaration m : ((ClassDecl) node).getMembers()) collectAllPropertyDecls(m, props);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectAllPropertyDecls(s, props);
        } else if (node instanceof DeclarationStmt) {
            collectAllPropertyDecls(((DeclarationStmt) node).getDeclaration(), props);
        } else if (node instanceof FunDecl) {
            collectAllPropertyDecls(((FunDecl) node).getBody(), props);
        }
    }

    /** 递归收集 AST 中的 AssignExpr */
    private void collectAssignExprs(AstNode node, List<AssignExpr> assigns) {
        if (node == null) return;
        if (node instanceof AssignExpr) {
            assigns.add((AssignExpr) node);
            collectAssignExprs(((AssignExpr) node).getValue(), assigns);
        } else if (node instanceof ExpressionStmt) {
            collectAssignExprs(((ExpressionStmt) node).getExpression(), assigns);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectAssignExprs(s, assigns);
        } else if (node instanceof DeclarationStmt) {
            collectAssignExprs(((DeclarationStmt) node).getDeclaration(), assigns);
        } else if (node instanceof PropertyDecl) {
            // 不收集初始化器中的赋值（那是声明，不是重赋值）
        } else if (node instanceof FunDecl) {
            collectAssignExprs(((FunDecl) node).getBody(), assigns);
        } else if (node instanceof CallExpr) {
            CallExpr call = (CallExpr) node;
            collectAssignExprs(call.getCallee(), assigns);
            for (CallExpr.Argument arg : call.getArgs()) collectAssignExprs(arg.getValue(), assigns);
        } else if (node instanceof IfStmt) {
            collectAssignExprs(((IfStmt) node).getCondition(), assigns);
            collectAssignExprs(((IfStmt) node).getThenBranch(), assigns);
            collectAssignExprs(((IfStmt) node).getElseBranch(), assigns);
        }
    }

    // ============ Array 构造器参数检查 ============

    /**
     * 检查 Array<T>(...) 构造调用的参数个数（应为 1）
     */
    private void checkArrayConstructorArgs(DocumentManager.CachedAnalysis cached, String content,
                                            JsonArray diagnostics) {
        List<CallExpr> calls = new ArrayList<CallExpr>();
        collectAllCallExprs(cached.parseResult.getProgram(), calls);
        for (Statement stmt : cached.parseResult.getTopLevelStatements()) {
            collectAllCallExprs(stmt, calls);
        }

        for (CallExpr call : calls) {
            if (call.getCallee() instanceof Identifier
                    && "Array".equals(((Identifier) call.getCallee()).getName())
                    && call.getTypeArgs() != null && !call.getTypeArgs().isEmpty()) {
                int argCount = call.getArgs().size();
                if (call.getTrailingLambda() != null) argCount++;
                if (argCount < 1 || argCount > 2) {
                    if (call.getLocation() != null) {
                        int line = call.getLocation().getLine() - 1;
                        int col = call.getLocation().getColumn() - 1;
                        String msg = "Array 构造器需要 1~2 个参数（大小[, 初始化函数]），实际传入 " + argCount + " 个";
                        JsonObject diag = new JsonObject();
                        diag.add("range", createRange(line, col, line, col + 5)); // "Array"
                        diag.addProperty("severity", SEVERITY_ERROR);
                        diag.addProperty("source", "nova");
                        diag.addProperty("message", msg);
                        diagnostics.add(diag);
                    }
                }
            }
        }
    }

    /** 递归收集所有 CallExpr */
    private void collectAllCallExprs(AstNode node, List<CallExpr> calls) {
        if (node == null) return;
        if (node instanceof CallExpr) {
            calls.add((CallExpr) node);
            CallExpr call = (CallExpr) node;
            collectAllCallExprs(call.getCallee(), calls);
            for (CallExpr.Argument arg : call.getArgs()) collectAllCallExprs(arg.getValue(), calls);
            if (call.getTrailingLambda() != null) collectAllCallExprs(call.getTrailingLambda(), calls);
        } else if (node instanceof MemberExpr) {
            collectAllCallExprs(((MemberExpr) node).getTarget(), calls);
        } else if (node instanceof BinaryExpr) {
            collectAllCallExprs(((BinaryExpr) node).getLeft(), calls);
            collectAllCallExprs(((BinaryExpr) node).getRight(), calls);
        } else if (node instanceof AssignExpr) {
            collectAllCallExprs(((AssignExpr) node).getTarget(), calls);
            collectAllCallExprs(((AssignExpr) node).getValue(), calls);
        } else if (node instanceof ExpressionStmt) {
            collectAllCallExprs(((ExpressionStmt) node).getExpression(), calls);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectAllCallExprs(s, calls);
        } else if (node instanceof DeclarationStmt) {
            collectAllCallExprs(((DeclarationStmt) node).getDeclaration(), calls);
        } else if (node instanceof PropertyDecl) {
            collectAllCallExprs(((PropertyDecl) node).getInitializer(), calls);
        } else if (node instanceof FunDecl) {
            collectAllCallExprs(((FunDecl) node).getBody(), calls);
        } else if (node instanceof ClassDecl) {
            for (Declaration m : ((ClassDecl) node).getMembers()) collectAllCallExprs(m, calls);
        } else if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations()) collectAllCallExprs(d, calls);
        }
    }

    // ============ 方法参数类型检查 ============

    /**
     * 检查成员方法调用的参数类型是否与 Java 方法签名匹配
     */
    private void checkArgTypes(DocumentManager.CachedAnalysis cached, String content,
                                JsonArray diagnostics) {
        if (javaClassResolver == null) return;

        List<CallExpr> memberCalls = new ArrayList<CallExpr>();
        collectMemberCalls(cached.parseResult.getProgram(), memberCalls);
        for (Statement stmt : cached.parseResult.getTopLevelStatements()) {
            collectMemberCalls(stmt, memberCalls);
        }

        for (CallExpr call : memberCalls) {
            MemberExpr member = (MemberExpr) call.getCallee();
            String methodName = member.getMember();

            String receiverType = resolveExpressionType(member.getTarget(), content, cached);
            if (receiverType == null) continue;

            String base = LspTextUtils.baseType(receiverType);
            String javaClassName = base.startsWith("java:") ? base.substring(5)
                    : NOVA_TO_JAVA_TYPE.get(base);
            if (javaClassName == null) continue;

            JavaClassInfo info = javaClassResolver.resolve(javaClassName);
            if (info == null) continue;

            // 收集同名非静态方法
            List<JavaClassInfo.MethodInfo> overloads = new ArrayList<JavaClassInfo.MethodInfo>();
            for (JavaClassInfo.MethodInfo m : info.methods) {
                if (m.name.equals(methodName) && !m.isStatic) overloads.add(m);
            }
            if (overloads.isEmpty()) continue;

            int argCount = call.getArgs().size();

            // 收集参数个数匹配的重载
            List<JavaClassInfo.MethodInfo> countMatched = new ArrayList<JavaClassInfo.MethodInfo>();
            for (JavaClassInfo.MethodInfo m : overloads) {
                if (m.paramTypes.size() == argCount) countMatched.add(m);
            }
            if (countMatched.isEmpty()) continue; // 参数个数不匹配由 checkMethodArgCounts 处理

            // 解析实际参数类型
            List<String> actualTypes = new ArrayList<String>();
            boolean allResolved = true;
            for (CallExpr.Argument arg : call.getArgs()) {
                String t = resolveExpressionType(arg.getValue(), content, cached);
                if (t == null) { allResolved = false; break; }
                actualTypes.add(t);
            }
            if (!allResolved) continue; // 有未知参数类型，跳过

            // 检查是否有任一重载匹配参数类型
            boolean anyMatch = false;
            for (JavaClassInfo.MethodInfo m : countMatched) {
                if (isArgTypesCompatible(actualTypes, m.paramTypes)) {
                    anyMatch = true;
                    break;
                }
            }

            if (!anyMatch) {
                if (member.getLocation() != null) {
                    int line = member.getLocation().getLine() - 1;
                    int col = member.getLocation().getColumn() - 1;
                    String[] lines = content.split("\n", -1);
                    if (line >= 0 && line < lines.length) {
                        int dotIdx = lines[line].indexOf("." + methodName, col);
                        if (dotIdx >= 0) col = dotIdx + 1;
                    }
                    StringBuilder msg = new StringBuilder();
                    msg.append("方法 '").append(methodName).append("' 没有匹配的重载：实际参数类型 (");
                    for (int i = 0; i < actualTypes.size(); i++) {
                        if (i > 0) msg.append(", ");
                        msg.append(actualTypes.get(i));
                    }
                    msg.append(")");
                    JsonObject diag = new JsonObject();
                    diag.add("range", createRange(line, col, line, col + methodName.length()));
                    diag.addProperty("severity", SEVERITY_ERROR);
                    diag.addProperty("source", "nova");
                    diag.addProperty("message", msg.toString());
                    diagnostics.add(diag);
                }
            }
        }
    }

    /**
     * 检查实际参数类型是否与 Java 方法签名兼容
     */
    private boolean isArgTypesCompatible(List<String> actualTypes, List<String> paramTypes) {
        if (actualTypes.size() != paramTypes.size()) return false;
        for (int i = 0; i < actualTypes.size(); i++) {
            if (!isTypeCompatible(actualTypes.get(i), paramTypes.get(i))) return false;
        }
        return true;
    }

    /**
     * 检查 Nova 类型是否兼容 Java 参数类型
     */
    private boolean isTypeCompatible(String novaType, String javaParamType) {
        if (novaType == null || javaParamType == null) return true; // 未知则不报错
        // Object 接受一切
        if ("Object".equals(javaParamType) || "object".equals(javaParamType)) return true;
        // 相同类型
        String base = LspTextUtils.baseType(novaType);
        if (base.equals(javaParamType)) return true;
        // Nova → Java 映射检查
        String javaEquiv = NOVA_TO_JAVA_TYPE.get(base);
        if (javaEquiv != null) {
            String javaSimple = javaEquiv.substring(javaEquiv.lastIndexOf('.') + 1);
            if (javaSimple.equals(javaParamType)) return true;
        }
        // 常见接口兼容性：String 实现 CharSequence/Comparable/Serializable
        if ("CharSequence".equals(javaParamType) || "Comparable".equals(javaParamType)
                || "Serializable".equals(javaParamType)) {
            if ("String".equals(base)) return true;
        }
        // int 参数只接受 Int
        if ("int".equals(javaParamType) || "Int".equals(javaParamType)) {
            return "Int".equals(base) || "int".equals(base);
        }
        // Number 及其子类型
        if ("Number".equals(javaParamType)) {
            return "Int".equals(base) || "Long".equals(base) || "Double".equals(base)
                    || "Float".equals(base) || "Short".equals(base) || "Byte".equals(base);
        }
        return false;
    }

    // ====================================================================
    //  Phase 2: 签名帮助 / 查找引用 / 文档高亮
    // ====================================================================

    /**
     * 签名帮助 — 光标在函数调用括号内时显示参数信息
     */
    public JsonObject signatureHelp(String uri, String content, int line, int character) {
        if (content == null) return null;

        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return null;

        String lineText = lines[line];
        String textBefore = lineText.substring(0, Math.min(character, lineText.length()));

        // 从光标位置向左扫描找到未闭合的 '('
        int depth = 0;
        int parenPos = -1;
        int commaCount = 0;
        for (int i = textBefore.length() - 1; i >= 0; i--) {
            char c = textBefore.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) { parenPos = i; break; }
                else depth--;
            } else if (c == ',' && depth == 0) {
                commaCount++;
            }
        }
        if (parenPos < 0) return null;

        // 提取函数名
        String beforeParen = textBefore.substring(0, parenPos).trim();
        String funcName = LspTextUtils.extractTrailingIdentifier(beforeParen);
        if (funcName == null || funcName.isEmpty()) return null;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);

        // 从符号表查找函数
        List<String> paramLabels = new ArrayList<>();
        String signatureLabel = null;

        if (cached != null) {
            Symbol sym = cached.analysisResult.getSymbolTable().resolve(funcName, line + 1, character + 1);
            if (sym != null && sym.getParameters() != null) {
                StringBuilder sig = new StringBuilder(funcName + "(");
                for (int i = 0; i < sym.getParameters().size(); i++) {
                    Symbol p = sym.getParameters().get(i);
                    String label = p.getName() + (p.getTypeName() != null ? ": " + p.getTypeName() : "");
                    paramLabels.add(label);
                    if (i > 0) sig.append(", ");
                    sig.append(label);
                }
                sig.append(")");
                if (sym.getTypeName() != null) sig.append(": ").append(sym.getTypeName());
                signatureLabel = sig.toString();
            }
        }

        // 内置函数 fallback
        if (signatureLabel == null) {
            for (NovaTypeRegistry.FunctionInfo f : NovaTypeRegistry.getBuiltinFunctions()) {
                if (f.name.equals(funcName)) {
                    signatureLabel = f.signature;
                    // 从签名中提取参数
                    int pStart = f.signature.indexOf('(');
                    int pEnd = f.signature.lastIndexOf(')');
                    if (pStart >= 0 && pEnd > pStart) {
                        String paramsStr = f.signature.substring(pStart + 1, pEnd);
                        if (!paramsStr.trim().isEmpty()) {
                            for (String p : paramsStr.split(",")) {
                                paramLabels.add(p.trim());
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (signatureLabel == null) return null;

        // 构建 SignatureHelp 响应
        JsonObject sigInfo = new JsonObject();
        sigInfo.addProperty("label", signatureLabel);
        JsonArray params = new JsonArray();
        for (String pl : paramLabels) {
            JsonObject param = new JsonObject();
            param.addProperty("label", pl);
            params.add(param);
        }
        sigInfo.add("parameters", params);

        JsonObject result = new JsonObject();
        JsonArray signatures = new JsonArray();
        signatures.add(sigInfo);
        result.add("signatures", signatures);
        result.addProperty("activeSignature", 0);
        result.addProperty("activeParameter", Math.min(commaCount, paramLabels.size() - 1));
        return result;
    }


    /**
     * 查找引用 — 在文档中搜索所有同名标识符
     */
    public JsonArray findReferences(String uri, String content, int line, int character,
                                     boolean includeDeclaration) {
        JsonArray result = new JsonArray();
        if (content == null) return result;

        String word = LspTextUtils.getWordAt(content, line, character);
        if (word == null || word.isEmpty()) return result;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);

        // 收集当前文件中的所有引用
        List<SourceLocation> refs = new ArrayList<>();
        if (cached != null) {
            collectIdentifierLocations(cached.parseResult.getProgram(), word, refs);
        }

        // 可选：包含声明位置
        if (!includeDeclaration && cached != null) {
            Symbol sym = cached.analysisResult.getSymbolTable().resolve(word, line + 1, character + 1);
            if (sym != null && sym.getLocation() != null) {
                SourceLocation defLoc = sym.getLocation();
                refs.removeIf(loc -> loc.getLine() == defLoc.getLine()
                        && loc.getColumn() == defLoc.getColumn());
            }
        }

        String[] contentLines = content.split("\n", -1);
        for (SourceLocation loc : refs) {
            JsonObject ref = new JsonObject();
            ref.addProperty("uri", uri);
            int refLine = loc.getLine() - 1;
            int refCol = LspTextUtils.correctColumnForWord(contentLines, refLine, loc.getColumn() - 1, word);
            ref.add("range", createRange(refLine, refCol, refLine, refCol + word.length()));
            result.add(ref);
        }

        // 跨文件引用（从 ProjectIndex 查询）
        List<ProjectIndex.SymbolEntry> crossFileEntries = projectIndex.findByName(word);
        for (ProjectIndex.SymbolEntry entry : crossFileEntries) {
            if (uri.equals(entry.uri)) continue; // 当前文件已处理
            JsonObject ref = new JsonObject();
            ref.addProperty("uri", entry.uri);
            ref.add("range", createRange(entry.line, entry.character, entry.endLine, entry.endCharacter));
            result.add(ref);
        }

        return result;
    }

    /**
     * 递归收集 AST 中所有匹配名称的标识符位置
     */
    private void collectIdentifierLocations(AstNode node, String name, List<SourceLocation> result) {
        if (node == null) return;

        if (node instanceof Identifier) {
            if (name.equals(((Identifier) node).getName()) && node.getLocation() != null) {
                result.add(node.getLocation());
            }
        } else if (node instanceof MemberExpr) {
            MemberExpr member = (MemberExpr) node;
            if (name.equals(member.getMember()) && node.getLocation() != null) {
                // node.getLocation() 指向 '.' 而非成员名, correctColumnForWord 会修正
                result.add(node.getLocation());
            }
            collectIdentifierLocations(member.getTarget(), name, result);
        } else if (node instanceof Program) {
            Program prog = (Program) node;
            for (Declaration d : prog.getDeclarations()) collectIdentifierLocations(d, name, result);
        } else if (node instanceof ClassDecl) {
            ClassDecl cls = (ClassDecl) node;
            if (name.equals(cls.getName()) && cls.getLocation() != null) result.add(declNameLoc(cls));
            for (Declaration m : cls.getMembers()) collectIdentifierLocations(m, name, result);
        } else if (node instanceof InterfaceDecl) {
            InterfaceDecl iface = (InterfaceDecl) node;
            if (name.equals(iface.getName()) && iface.getLocation() != null) result.add(declNameLoc(iface));
            for (Declaration m : iface.getMembers()) collectIdentifierLocations(m, name, result);
        } else if (node instanceof FunDecl) {
            FunDecl fun = (FunDecl) node;
            if (name.equals(fun.getName()) && fun.getLocation() != null) result.add(declNameLoc(fun));
            if (fun.getBody() != null) collectIdentifierLocations(fun.getBody(), name, result);
        } else if (node instanceof PropertyDecl) {
            PropertyDecl prop = (PropertyDecl) node;
            if (name.equals(prop.getName()) && prop.getLocation() != null) result.add(declNameLoc(prop));
            if (prop.getInitializer() != null) collectIdentifierLocations(prop.getInitializer(), name, result);
        } else if (node instanceof EnumDecl) {
            EnumDecl en = (EnumDecl) node;
            if (name.equals(en.getName()) && en.getLocation() != null) result.add(declNameLoc(en));
            for (EnumDecl.EnumEntry entry : en.getEntries()) {
                if (name.equals(entry.getName()) && entry.getLocation() != null) result.add(entry.getLocation());
            }
            for (Declaration m : en.getMembers()) collectIdentifierLocations(m, name, result);
        } else if (node instanceof ObjectDecl) {
            ObjectDecl obj = (ObjectDecl) node;
            if (name.equals(obj.getName()) && obj.getLocation() != null) result.add(declNameLoc(obj));
            for (Declaration m : obj.getMembers()) collectIdentifierLocations(m, name, result);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectIdentifierLocations(s, name, result);
        } else if (node instanceof ExpressionStmt) {
            collectIdentifierLocations(((ExpressionStmt) node).getExpression(), name, result);
        } else if (node instanceof DeclarationStmt) {
            collectIdentifierLocations(((DeclarationStmt) node).getDeclaration(), name, result);
        } else if (node instanceof ReturnStmt) {
            if (((ReturnStmt) node).getValue() != null)
                collectIdentifierLocations(((ReturnStmt) node).getValue(), name, result);
        } else if (node instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) node;
            collectIdentifierLocations(ifStmt.getCondition(), name, result);
            if (ifStmt.getThenBranch() != null) collectIdentifierLocations(ifStmt.getThenBranch(), name, result);
            if (ifStmt.getElseBranch() != null) collectIdentifierLocations(ifStmt.getElseBranch(), name, result);
        } else if (node instanceof ForStmt) {
            ForStmt forStmt = (ForStmt) node;
            collectIdentifierLocations(forStmt.getIterable(), name, result);
            if (forStmt.getBody() != null) collectIdentifierLocations(forStmt.getBody(), name, result);
        } else if (node instanceof WhileStmt) {
            collectIdentifierLocations(((WhileStmt) node).getCondition(), name, result);
            if (((WhileStmt) node).getBody() != null)
                collectIdentifierLocations(((WhileStmt) node).getBody(), name, result);
        } else if (node instanceof CallExpr) {
            CallExpr call = (CallExpr) node;
            collectIdentifierLocations(call.getCallee(), name, result);
            for (CallExpr.Argument arg : call.getArgs())
                collectIdentifierLocations(arg.getValue(), name, result);
            if (call.getTrailingLambda() != null) collectIdentifierLocations(call.getTrailingLambda(), name, result);
        } else if (node instanceof BinaryExpr) {
            collectIdentifierLocations(((BinaryExpr) node).getLeft(), name, result);
            collectIdentifierLocations(((BinaryExpr) node).getRight(), name, result);
        } else if (node instanceof UnaryExpr) {
            collectIdentifierLocations(((UnaryExpr) node).getOperand(), name, result);
        } else if (node instanceof AssignExpr) {
            collectIdentifierLocations(((AssignExpr) node).getTarget(), name, result);
            collectIdentifierLocations(((AssignExpr) node).getValue(), name, result);
        } else if (node instanceof IndexExpr) {
            collectIdentifierLocations(((IndexExpr) node).getTarget(), name, result);
            collectIdentifierLocations(((IndexExpr) node).getIndex(), name, result);
        } else if (node instanceof LambdaExpr) {
            if (((LambdaExpr) node).getBody() != null)
                collectIdentifierLocations(((LambdaExpr) node).getBody(), name, result);
        } else if (node instanceof WhenExpr) {
            WhenExpr when = (WhenExpr) node;
            if (when.getSubject() != null) collectIdentifierLocations(when.getSubject(), name, result);
            for (WhenBranch branch : when.getBranches()) {
                if (branch.getBody() != null) collectIdentifierLocations(branch.getBody(), name, result);
            }
            if (when.getElseExpr() != null) collectIdentifierLocations(when.getElseExpr(), name, result);
        } else if (node instanceof IfExpr) {
            IfExpr ifExpr = (IfExpr) node;
            collectIdentifierLocations(ifExpr.getCondition(), name, result);
            if (ifExpr.getThenExpr() != null) collectIdentifierLocations(ifExpr.getThenExpr(), name, result);
            if (ifExpr.getElseExpr() != null) collectIdentifierLocations(ifExpr.getElseExpr(), name, result);
        } else if (node instanceof TryStmt) {
            TryStmt tryStmt = (TryStmt) node;
            if (tryStmt.getTryBlock() != null) collectIdentifierLocations(tryStmt.getTryBlock(), name, result);
            for (CatchClause cc : tryStmt.getCatchClauses()) {
                if (cc.getBody() != null) collectIdentifierLocations(cc.getBody(), name, result);
            }
            if (tryStmt.getFinallyBlock() != null)
                collectIdentifierLocations(tryStmt.getFinallyBlock(), name, result);
        } else if (node instanceof WhenStmt) {
            WhenStmt when = (WhenStmt) node;
            if (when.getSubject() != null) collectIdentifierLocations(when.getSubject(), name, result);
            for (WhenBranch branch : when.getBranches()) {
                if (branch.getBody() != null) collectIdentifierLocations(branch.getBody(), name, result);
            }
            if (when.getElseBranch() != null) collectIdentifierLocations(when.getElseBranch(), name, result);
        } else if (node instanceof StringInterpolation) {
            for (StringInterpolation.StringPart part : ((StringInterpolation) node).getParts()) {
                if (part instanceof StringInterpolation.ExprPart)
                    collectIdentifierLocations(((StringInterpolation.ExprPart) part).getExpression(), name, result);
            }
        } else if (node instanceof ElvisExpr) {
            collectIdentifierLocations(((ElvisExpr) node).getLeft(), name, result);
            collectIdentifierLocations(((ElvisExpr) node).getRight(), name, result);
        } else if (node instanceof CollectionLiteral) {
            CollectionLiteral col = (CollectionLiteral) node;
            if (col.getElements() != null) {
                for (Expression elem : col.getElements()) collectIdentifierLocations(elem, name, result);
            }
            if (col.getMapEntries() != null) {
                for (CollectionLiteral.MapEntry entry : col.getMapEntries()) {
                    collectIdentifierLocations(entry.getKey(), name, result);
                    collectIdentifierLocations(entry.getValue(), name, result);
                }
            }
        }
    }

    /**
     * 文档高亮 — 高亮当前文档中所有同名标识符
     */
    public JsonArray documentHighlight(String uri, String content, int line, int character) {
        JsonArray result = new JsonArray();
        if (content == null) return result;

        String word = LspTextUtils.getWordAt(content, line, character);
        if (word == null || word.isEmpty()) return result;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached == null) return result;

        // 收集所有引用
        List<SourceLocation> refs = new ArrayList<>();
        collectIdentifierLocations(cached.parseResult.getProgram(), word, refs);

        // 判断赋值目标作为 Write 高亮
        Set<String> writeLocKeys = new HashSet<>();
        collectWriteLocations(cached.parseResult.getProgram(), word, writeLocKeys);

        String[] contentLines = content.split("\n", -1);
        for (SourceLocation loc : refs) {
            JsonObject highlight = new JsonObject();
            int refLine = loc.getLine() - 1;
            int refCol = LspTextUtils.correctColumnForWord(contentLines, refLine, loc.getColumn() - 1, word);
            highlight.add("range", createRange(refLine, refCol, refLine, refCol + word.length()));
            String key = refLine + ":" + refCol;
            highlight.addProperty("kind", writeLocKeys.contains(key) ? HIGHLIGHT_WRITE : HIGHLIGHT_READ);
            result.add(highlight);
        }
        return result;
    }

    private void collectWriteLocations(AstNode node, String name, Set<String> writeKeys) {
        if (node == null) return;
        if (node instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) node;
            if (assign.getTarget() instanceof Identifier
                    && name.equals(((Identifier) assign.getTarget()).getName())
                    && assign.getTarget().getLocation() != null) {
                SourceLocation loc = assign.getTarget().getLocation();
                writeKeys.add((loc.getLine() - 1) + ":" + (loc.getColumn() - 1));
            }
            collectWriteLocations(assign.getValue(), name, writeKeys);
        } else if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations()) collectWriteLocations(d, name, writeKeys);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectWriteLocations(s, name, writeKeys);
        } else if (node instanceof ExpressionStmt) {
            collectWriteLocations(((ExpressionStmt) node).getExpression(), name, writeKeys);
        } else if (node instanceof DeclarationStmt) {
            collectWriteLocations(((DeclarationStmt) node).getDeclaration(), name, writeKeys);
        } else if (node instanceof FunDecl) {
            if (((FunDecl) node).getBody() != null) collectWriteLocations(((FunDecl) node).getBody(), name, writeKeys);
        } else if (node instanceof ClassDecl) {
            for (Declaration m : ((ClassDecl) node).getMembers()) collectWriteLocations(m, name, writeKeys);
        } else if (node instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) node;
            if (ifStmt.getThenBranch() != null) collectWriteLocations(ifStmt.getThenBranch(), name, writeKeys);
            if (ifStmt.getElseBranch() != null) collectWriteLocations(ifStmt.getElseBranch(), name, writeKeys);
        } else if (node instanceof ForStmt) {
            if (((ForStmt) node).getBody() != null) collectWriteLocations(((ForStmt) node).getBody(), name, writeKeys);
        } else if (node instanceof WhileStmt) {
            if (((WhileStmt) node).getBody() != null) collectWriteLocations(((WhileStmt) node).getBody(), name, writeKeys);
        }
    }

    // ====================================================================
    //  Phase 3: 重命名 / 代码操作 / 折叠范围
    // ====================================================================

    /**
     * 准备重命名 — 返回可重命名的标识符范围
     */
    public JsonObject prepareRename(String uri, String content, int line, int character) {
        if (content == null) return null;

        String word = LspTextUtils.getWordAt(content, line, character);
        if (word == null || word.isEmpty()) return null;

        // 检查是否是关键词（不可重命名）
        if (KEYWORD_DESCRIPTIONS.containsKey(word)) return null;

        // 检查是否是内置函数/类型（不可重命名）
        for (NovaTypeRegistry.FunctionInfo f : NovaTypeRegistry.getBuiltinFunctions()) {
            if (f.name.equals(word)) return null;
        }
        for (NovaTypeRegistry.TypeInfo t : NovaTypeRegistry.getBuiltinTypes()) {
            if (t.name.equals(word)) return null;
        }

        // 确认可以在符号表中找到
        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached != null) {
            Symbol sym = cached.analysisResult.getSymbolTable().resolve(word, line + 1, character + 1);
            if (sym != null && sym.getKind() != SymbolKind.BUILTIN_FUNCTION
                    && sym.getKind() != SymbolKind.BUILTIN_CONSTANT) {
                // 在行中精确找到 word 位置
                String[] lines = content.split("\n", -1);
                if (line >= 0 && line < lines.length) {
                    int col = LspTextUtils.findWordInLine(lines[line], word, character);
                    return createRange(line, col, line, col + word.length());
                }
            }
        }

        // 文本回退
        String[] lines = content.split("\n", -1);
        if (line >= 0 && line < lines.length) {
            int col = LspTextUtils.findWordInLine(lines[line], word, character);
            if (col >= 0) {
                return createRange(line, col, line, col + word.length());
            }
        }
        return null;
    }


    /**
     * 重命名 — 生成 WorkspaceEdit
     */
    public JsonObject rename(String uri, String content, int line, int character, String newName) {
        if (content == null) return null;

        String word = LspTextUtils.getWordAt(content, line, character);
        if (word == null || word.isEmpty()) return null;

        // 收集所有引用位置
        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        List<SourceLocation> refs = new ArrayList<>();
        if (cached != null) {
            collectIdentifierLocations(cached.parseResult.getProgram(), word, refs);
        }
        if (refs.isEmpty()) return null;

        // 生成 TextEdit 数组
        String[] contentLines = content.split("\n", -1);
        JsonArray edits = new JsonArray();
        for (SourceLocation loc : refs) {
            int refLine = loc.getLine() - 1;
            int refCol = LspTextUtils.correctColumnForWord(contentLines, refLine, loc.getColumn() - 1, word);
            JsonObject edit = new JsonObject();
            edit.add("range", createRange(refLine, refCol, refLine, refCol + word.length()));
            edit.addProperty("newText", newName);
            edits.add(edit);
        }

        // 构建 WorkspaceEdit
        JsonObject workspaceEdit = new JsonObject();
        JsonObject changes = new JsonObject();
        changes.add(uri, edits);

        // 跨文件重命名
        List<ProjectIndex.SymbolEntry> crossFileEntries = projectIndex.findByName(word);
        for (ProjectIndex.SymbolEntry entry : crossFileEntries) {
            if (uri.equals(entry.uri)) continue;
            JsonArray fileEdits = new JsonArray();
            JsonObject edit = new JsonObject();
            edit.add("range", createRange(entry.line, entry.character, entry.endLine, entry.endCharacter));
            edit.addProperty("newText", newName);
            fileEdits.add(edit);
            changes.add(entry.uri, fileEdits);
        }

        workspaceEdit.add("changes", changes);
        return workspaceEdit;
    }

    /**
     * 代码操作 — 根据诊断生成快速修复
     */
    public JsonArray codeActions(String uri, String content, JsonObject range, JsonObject context) {
        JsonArray actions = new JsonArray();
        if (content == null || context == null) return actions;

        JsonArray diagnostics = context.has("diagnostics") ? context.getAsJsonArray("diagnostics") : null;
        if (diagnostics == null) return actions;

        for (int i = 0; i < diagnostics.size(); i++) {
            JsonObject diag = diagnostics.get(i).getAsJsonObject();
            String message = diag.has("message") ? diag.get("message").getAsString() : "";

            // val 重赋值 → 建议改为 var
            if (message.contains("val") && message.contains("不可变")) {
                // 从诊断范围提取变量名
                JsonObject diagRange = diag.getAsJsonObject("range");
                if (diagRange != null) {
                    JsonObject action = createQuickFix(
                            "将 val 改为 var",
                            uri, content, diagRange, message);
                    if (action != null) actions.add(action);
                }
            }
        }

        return actions;
    }

    private JsonObject createQuickFix(String title, String uri, String content,
                                       JsonObject diagRange, String message) {
        // 尝试从消息中提取变量名
        int quoteStart = message.indexOf('\'');
        int quoteEnd = message.indexOf('\'', quoteStart + 1);
        if (quoteStart < 0 || quoteEnd < 0) return null;
        String varName = message.substring(quoteStart + 1, quoteEnd);

        // 找到 val 声明行
        String[] lines = content.split("\n", -1);
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            // 匹配 val varName
            int valIdx = line.indexOf("val ");
            while (valIdx >= 0) {
                String after = line.substring(valIdx + 4).trim();
                if (after.startsWith(varName) &&
                        (after.length() == varName.length() || !LspTextUtils.isIdentChar(after.charAt(varName.length())))) {
                    JsonObject action = new JsonObject();
                    action.addProperty("title", title);
                    action.addProperty("kind", "quickfix");

                    JsonObject edit = new JsonObject();
                    JsonObject changes = new JsonObject();
                    JsonArray edits = new JsonArray();
                    JsonObject textEdit = new JsonObject();
                    textEdit.add("range", createRange(lineIdx, valIdx, lineIdx, valIdx + 3));
                    textEdit.addProperty("newText", "var");
                    edits.add(textEdit);
                    changes.add(uri, edits);
                    edit.add("changes", changes);
                    action.add("edit", edit);

                    JsonArray diagArray = new JsonArray();
                    JsonObject diagCopy = new JsonObject();
                    diagCopy.add("range", diagRange);
                    diagCopy.addProperty("message", message);
                    diagArray.add(diagCopy);
                    action.add("diagnostics", diagArray);

                    return action;
                }
                valIdx = line.indexOf("val ", valIdx + 1);
            }
        }
        return null;
    }

    /**
     * 折叠范围 — 为块体节点生成折叠区域
     */
    public JsonArray foldingRanges(String uri, String content) {
        JsonArray result = new JsonArray();
        if (content == null) return result;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached == null) return result;

        // 从 AST 收集折叠范围
        collectFoldingRanges(cached.parseResult.getProgram(), content, result);

        // 连续 import 语句折叠
        String[] lines = content.split("\n", -1);
        int importStart = -1, importEnd = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ")) {
                if (importStart < 0) importStart = i;
                importEnd = i;
            } else if (importStart >= 0 && importEnd > importStart) {
                addFoldingRange(result, importStart, importEnd, "imports");
                importStart = -1;
            } else {
                importStart = -1;
            }
        }
        if (importStart >= 0 && importEnd > importStart) {
            addFoldingRange(result, importStart, importEnd, "imports");
        }

        // 多行注释折叠
        boolean inComment = false;
        int commentStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!inComment && trimmed.startsWith("/*")) {
                inComment = true;
                commentStart = i;
            }
            if (inComment && trimmed.contains("*/")) {
                if (i > commentStart) {
                    addFoldingRange(result, commentStart, i, "comment");
                }
                inComment = false;
            }
        }

        return result;
    }

    private void collectFoldingRanges(AstNode node, String content, JsonArray result) {
        if (node == null) return;

        if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations()) collectFoldingRanges(d, content, result);
        } else if (node instanceof ClassDecl) {
            addFoldingRangeForNode(node, content, result, "region");
            for (Declaration m : ((ClassDecl) node).getMembers()) collectFoldingRanges(m, content, result);
        } else if (node instanceof InterfaceDecl) {
            addFoldingRangeForNode(node, content, result, "region");
            for (Declaration m : ((InterfaceDecl) node).getMembers()) collectFoldingRanges(m, content, result);
        } else if (node instanceof EnumDecl) {
            addFoldingRangeForNode(node, content, result, "region");
            for (Declaration m : ((EnumDecl) node).getMembers()) collectFoldingRanges(m, content, result);
        } else if (node instanceof ObjectDecl) {
            addFoldingRangeForNode(node, content, result, "region");
            for (Declaration m : ((ObjectDecl) node).getMembers()) collectFoldingRanges(m, content, result);
        } else if (node instanceof FunDecl) {
            FunDecl fun = (FunDecl) node;
            if (fun.getBody() instanceof Block) {
                addFoldingRangeForNode(node, content, result, "region");
            }
            if (fun.getBody() != null) collectFoldingRanges(fun.getBody(), content, result);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements()) collectFoldingRanges(s, content, result);
        } else if (node instanceof ExpressionStmt) {
            collectFoldingRanges(((ExpressionStmt) node).getExpression(), content, result);
        } else if (node instanceof DeclarationStmt) {
            collectFoldingRanges(((DeclarationStmt) node).getDeclaration(), content, result);
        } else if (node instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) node;
            addFoldingRangeForNode(node, content, result, "region");
            if (ifStmt.getThenBranch() != null) collectFoldingRanges(ifStmt.getThenBranch(), content, result);
            if (ifStmt.getElseBranch() != null) collectFoldingRanges(ifStmt.getElseBranch(), content, result);
        } else if (node instanceof ForStmt) {
            addFoldingRangeForNode(node, content, result, "region");
            if (((ForStmt) node).getBody() != null) collectFoldingRanges(((ForStmt) node).getBody(), content, result);
        } else if (node instanceof WhileStmt) {
            addFoldingRangeForNode(node, content, result, "region");
            if (((WhileStmt) node).getBody() != null) collectFoldingRanges(((WhileStmt) node).getBody(), content, result);
        } else if (node instanceof TryStmt) {
            addFoldingRangeForNode(node, content, result, "region");
        } else if (node instanceof WhenExpr) {
            addFoldingRangeForNode(node, content, result, "region");
        } else if (node instanceof WhenStmt) {
            addFoldingRangeForNode(node, content, result, "region");
        }
    }

    private void addFoldingRangeForNode(AstNode node, String content, JsonArray result, String kind) {
        if (node.getLocation() == null) return;
        int startLine = node.getLocation().getLine() - 1;
        // 估算结束行：查找该节点之后的闭合花括号
        String[] lines = content.split("\n", -1);
        int endLine = LspTextUtils.findClosingBraceLine(lines, startLine);
        if (endLine > startLine) {
            addFoldingRange(result, startLine, endLine, kind);
        }
    }


    private void addFoldingRange(JsonArray result, int startLine, int endLine, String kind) {
        if (endLine <= startLine) return;
        JsonObject range = new JsonObject();
        range.addProperty("startLine", startLine);
        range.addProperty("endLine", endLine);
        range.addProperty("kind", kind);
        result.add(range);
    }

    // ====================================================================
    //  Phase 4: 语义令牌 / 内嵌提示
    // ====================================================================

    /**
     * 语义令牌（完整文档）
     */
    public JsonObject semanticTokensFull(String uri, String content) {
        if (content == null) return null;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached == null) return null;

        SemanticTokensBuilder builder = new SemanticTokensBuilder();
        int[] data = builder.build(cached.parseResult.getProgram());

        JsonObject result = new JsonObject();
        JsonArray dataArray = new JsonArray();
        for (int d : data) dataArray.add(d);
        result.add("data", dataArray);
        return result;
    }

    /**
     * 内嵌提示 — 显示推断的类型和参数名
     */
    public JsonArray inlayHints(String uri, String content, int startLine, int endLine) {
        JsonArray result = new JsonArray();
        if (content == null) return result;

        DocumentManager.CachedAnalysis cached = ensureParsed(uri, content);
        if (cached == null) return result;

        // 遍历声明，为无类型注解的 val/var 添加类型提示
        collectInlayHints(cached.parseResult.getProgram(), cached, content, startLine, endLine, result);

        return result;
    }

    private void collectInlayHints(AstNode node, DocumentManager.CachedAnalysis cached,
                                    String content, int startLine, int endLine, JsonArray result) {
        if (node == null) return;

        if (node instanceof Program) {
            for (Declaration d : ((Program) node).getDeclarations())
                collectInlayHints(d, cached, content, startLine, endLine, result);
        } else if (node instanceof PropertyDecl) {
            PropertyDecl prop = (PropertyDecl) node;
            if (prop.getType() == null && prop.getInitializer() != null && prop.getLocation() != null) {
                SourceLocation propNameLoc = declNameLoc(prop);
                int line = propNameLoc.getLine() - 1;
                if (line >= startLine && line <= endLine) {
                    // 从 exprNovaTypeMap 或符号表获取推断类型
                    String inferredType = null;
                    if (cached.analysisResult != null) {
                        inferredType = cached.analysisResult.getExprTypeName(prop.getInitializer());
                    }
                    if (inferredType == null && cached.analysisResult != null) {
                        Symbol sym = cached.analysisResult.getSymbolTable().resolve(
                                prop.getName(), line + 1, propNameLoc.getColumn());
                        if (sym != null) inferredType = sym.getTypeName();
                    }
                    // 类型为 Any 时，尝试文本推断获取更精确的类型
                    if (inferredType == null || "Any".equals(inferredType)) {
                        String textInferred = inferVariableType(content, prop.getName());
                        if (textInferred != null) inferredType = textInferred;
                    }
                    if (inferredType != null) {
                        // 在变量名后面显示类型提示：从行文本中精确定位变量名末尾
                        String[] allLines = content.split("\n", -1);
                        int col = -1;
                        if (line >= 0 && line < allLines.length) {
                            int nameIdx = allLines[line].indexOf(prop.getName(), propNameLoc.getColumn() - 1);
                            if (nameIdx >= 0) {
                                col = nameIdx + prop.getName().length();
                            }
                        }
                        if (col >= 0) {
                            JsonObject hint = new JsonObject();
                            JsonObject position = new JsonObject();
                            position.addProperty("line", line);
                            position.addProperty("character", col);
                            hint.add("position", position);
                            hint.addProperty("label", ": " + LspTextUtils.formatTypeForDisplay(inferredType));
                            hint.addProperty("kind", INLAY_TYPE);
                            hint.addProperty("paddingLeft", false);
                            hint.addProperty("paddingRight", true);
                            result.add(hint);
                        }
                    }
                }
            }
            if (prop.getInitializer() != null)
                collectInlayHints(prop.getInitializer(), cached, content, startLine, endLine, result);
        } else if (node instanceof FunDecl) {
            FunDecl fun = (FunDecl) node;
            // 无返回类型注解的函数
            if (fun.getReturnType() == null && fun.getBody() instanceof Expression && fun.getLocation() != null) {
                int line = fun.getLocation().getLine() - 1;
                if (line >= startLine && line <= endLine) {
                    String returnType = null;
                    if (cached.analysisResult != null) {
                        returnType = cached.analysisResult.getExprTypeName((Expression) fun.getBody());
                    }
                    if (returnType != null) {
                        // 在参数列表 ')' 后面显示返回类型
                        String[] lines = content.split("\n", -1);
                        if (line >= 0 && line < lines.length) {
                            int parenIdx = lines[line].indexOf(')');
                            if (parenIdx >= 0) {
                                JsonObject hint = new JsonObject();
                                JsonObject position = new JsonObject();
                                position.addProperty("line", line);
                                position.addProperty("character", parenIdx + 1);
                                hint.add("position", position);
                                hint.addProperty("label", ": " + returnType);
                                hint.addProperty("kind", INLAY_TYPE);
                                hint.addProperty("paddingLeft", false);
                                hint.addProperty("paddingRight", true);
                                result.add(hint);
                            }
                        }
                    }
                }
            }
            if (fun.getBody() != null) collectInlayHints(fun.getBody(), cached, content, startLine, endLine, result);
        } else if (node instanceof ClassDecl) {
            for (Declaration m : ((ClassDecl) node).getMembers())
                collectInlayHints(m, cached, content, startLine, endLine, result);
        } else if (node instanceof InterfaceDecl) {
            for (Declaration m : ((InterfaceDecl) node).getMembers())
                collectInlayHints(m, cached, content, startLine, endLine, result);
        } else if (node instanceof ObjectDecl) {
            for (Declaration m : ((ObjectDecl) node).getMembers())
                collectInlayHints(m, cached, content, startLine, endLine, result);
        } else if (node instanceof EnumDecl) {
            for (Declaration m : ((EnumDecl) node).getMembers())
                collectInlayHints(m, cached, content, startLine, endLine, result);
        } else if (node instanceof Block) {
            for (Statement s : ((Block) node).getStatements())
                collectInlayHints(s, cached, content, startLine, endLine, result);
        } else if (node instanceof ExpressionStmt) {
            collectInlayHints(((ExpressionStmt) node).getExpression(), cached, content, startLine, endLine, result);
        } else if (node instanceof DeclarationStmt) {
            collectInlayHints(((DeclarationStmt) node).getDeclaration(), cached, content, startLine, endLine, result);
        } else if (node instanceof CallExpr) {
            // 参数名提示
            CallExpr call = (CallExpr) node;
            if (call.getLocation() != null) {
                int line = call.getLocation().getLine() - 1;
                if (line >= startLine && line <= endLine && call.getArgs().size() > 0) {
                    addParamNameHints(call, cached, result);
                }
            }
            // 递归子表达式
            collectInlayHints(call.getCallee(), cached, content, startLine, endLine, result);
            for (CallExpr.Argument arg : call.getArgs())
                collectInlayHints(arg.getValue(), cached, content, startLine, endLine, result);
        } else if (node instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) node;
            if (ifStmt.getThenBranch() != null) collectInlayHints(ifStmt.getThenBranch(), cached, content, startLine, endLine, result);
            if (ifStmt.getElseBranch() != null) collectInlayHints(ifStmt.getElseBranch(), cached, content, startLine, endLine, result);
        } else if (node instanceof ForStmt) {
            if (((ForStmt) node).getBody() != null) collectInlayHints(((ForStmt) node).getBody(), cached, content, startLine, endLine, result);
        } else if (node instanceof WhileStmt) {
            if (((WhileStmt) node).getBody() != null) collectInlayHints(((WhileStmt) node).getBody(), cached, content, startLine, endLine, result);
        }
    }

    private void addParamNameHints(CallExpr call, DocumentManager.CachedAnalysis cached, JsonArray result) {
        // 找到被调用函数的参数名
        List<String> paramNames = null;
        if (call.getCallee() instanceof Identifier) {
            String funcName = ((Identifier) call.getCallee()).getName();
            if (cached != null && cached.analysisResult != null) {
                Symbol sym = cached.analysisResult.getSymbolTable().resolve(funcName, 1, 1);
                if (sym != null && sym.getParameters() != null) {
                    paramNames = new ArrayList<>();
                    for (Symbol p : sym.getParameters()) paramNames.add(p.getName());
                }
            }
        }
        if (paramNames == null) return;

        for (int i = 0; i < call.getArgs().size() && i < paramNames.size(); i++) {
            CallExpr.Argument arg = call.getArgs().get(i);
            if (arg.isNamed()) continue; // 已有命名参数，跳过
            // 如果参数是简单字面量或跟参数名不同的标识符才显示
            if (arg.getValue().getLocation() != null) {
                int argLine = arg.getValue().getLocation().getLine() - 1;
                int argCol = arg.getValue().getLocation().getColumn() - 1;
                JsonObject hint = new JsonObject();
                JsonObject position = new JsonObject();
                position.addProperty("line", argLine);
                position.addProperty("character", argCol);
                hint.add("position", position);
                hint.addProperty("label", paramNames.get(i) + ":");
                hint.addProperty("kind", INLAY_PARAMETER);
                hint.addProperty("paddingLeft", false);
                hint.addProperty("paddingRight", true);
                result.add(hint);
            }
        }
    }

    // ====================================================================
    //  Phase 5: 工作区符号
    // ====================================================================

    /**
     * 工作区符号搜索
     */
    public JsonArray workspaceSymbols(String query) {
        JsonArray result = new JsonArray();

        // 从 ProjectIndex 搜索
        List<ProjectIndex.SymbolEntry> entries = projectIndex.search(query);
        for (ProjectIndex.SymbolEntry entry : entries) {
            JsonObject sym = new JsonObject();
            sym.addProperty("name", entry.name);
            sym.addProperty("kind", symbolKindToLspKind(entry.kind));
            if (entry.containerName != null) {
                sym.addProperty("containerName", entry.containerName);
            }
            JsonObject location = new JsonObject();
            location.addProperty("uri", entry.uri);
            location.add("range", createRange(entry.line, entry.character, entry.endLine, entry.endCharacter));
            sym.add("location", location);
            result.add(sym);
        }

        // 如果 ProjectIndex 为空，从所有打开的文档中搜索
        if (result.size() == 0) {
            String lowerQuery = query.toLowerCase();
            for (String uri : documentManager.getOpenDocuments()) {
                DocumentManager.CachedAnalysis cached = documentManager.getAnalysis(uri);
                if (cached == null) continue;
                for (Symbol sym : cached.analysisResult.getSymbolTable().getGlobalScope().getSymbols().values()) {
                    if (sym.getKind() == SymbolKind.BUILTIN_FUNCTION || sym.getKind() == SymbolKind.BUILTIN_CONSTANT) continue;
                    if (sym.getName() != null && sym.getName().toLowerCase().contains(lowerQuery)) {
                        JsonObject symObj = new JsonObject();
                        symObj.addProperty("name", sym.getName());
                        symObj.addProperty("kind", symbolKindToLspKind(sym.getKind()));
                        int line = 0, col = 0;
                        if (sym.getLocation() != null) {
                            line = sym.getLocation().getLine() - 1;
                            col = sym.getLocation().getColumn() - 1;
                        }
                        JsonObject location = new JsonObject();
                        location.addProperty("uri", uri);
                        location.add("range", createRange(line, col, line, col + sym.getName().length()));
                        symObj.add("location", location);
                        result.add(symObj);
                    }
                }
            }
        }

        return result;
    }

    private int symbolKindToLspKind(SymbolKind kind) {
        switch (kind) {
            case CLASS: return LspConstants.SYMBOL_CLASS;
            case INTERFACE: return LspConstants.SYMBOL_INTERFACE;
            case ENUM: return LspConstants.SYMBOL_ENUM;
            case ENUM_ENTRY: return LspConstants.SYMBOL_ENUM_MEMBER;
            case FUNCTION: return LspConstants.SYMBOL_FUNCTION;
            case VARIABLE: return LspConstants.SYMBOL_VARIABLE;
            case PROPERTY: return LspConstants.SYMBOL_PROPERTY;
            case PARAMETER: return LspConstants.SYMBOL_VARIABLE;
            case OBJECT: return LspConstants.SYMBOL_CLASS;
            case TYPE_ALIAS: return LspConstants.SYMBOL_TYPE_PARAMETER;
            case IMPORT: return LspConstants.SYMBOL_NAMESPACE;
            default: return LspConstants.SYMBOL_VARIABLE;
        }
    }

    /**
     * 更新 ProjectIndex（在文件打开/变更后调用）
     */
    public void updateProjectIndex(String uri) {
        DocumentManager.CachedAnalysis cached = documentManager.getAnalysis(uri);
        if (cached != null && cached.analysisResult != null) {
            List<Symbol> topLevel = new ArrayList<>(
                    cached.analysisResult.getSymbolTable().getGlobalScope().getSymbols().values());
            projectIndex.updateFile(uri, topLevel);
        }
    }

}
