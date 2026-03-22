import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
} from 'vscode-languageclient/node';
import { EmbeddedNovaController } from './embeddedNova';
import { NovaRuntimeClient, toVscodeCompletionItem } from './runtimeClient';
import { ApiDocsProvider } from './apiDocsProvider';

let client: LanguageClient | undefined;
let runTerminal: vscode.Terminal | undefined;
let outputChannel: vscode.OutputChannel | undefined;
let embeddedNovaController: EmbeddedNovaController | undefined;
let extensionPath: string = '';
export const runtimeClient = new NovaRuntimeClient();

export function activate(context: vscode.ExtensionContext) {
    extensionPath = context.extensionPath;
    runtimeClient.refresh();
    tryStartLsp(context);
    promptRuntimeConnection(context);

    // 侧边栏 API 文档面板
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(ApiDocsProvider.viewType, new ApiDocsProvider(runtimeClient))
    );

    // 监听配置变更，自动重启 LSP
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('nova')) {
                vscode.window.showInformationMessage('NovaLang: LSP 配置已变更，正在重启...');
                restartLsp(context);
            }
        })
    );

    // 娉ㄥ唽杩愯鍛戒护
    context.subscriptions.push(
        vscode.commands.registerCommand('nova.restartLanguageServer', async () => {
            await restartLsp(context);
            vscode.window.showInformationMessage('NovaLang: Language Server restarted');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('nova.organizeImports', async () => {
            await vscode.commands.executeCommand('editor.action.organizeImports');
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('nova.runFile', () => {
            runNovaFile();
        })
    );

    // 娉ㄥ唽缂栬瘧杩愯鍛戒护
    context.subscriptions.push(
        vscode.commands.registerCommand('nova.compileAndRun', () => {
            compileAndRunNovaFile();
        })
    );

    // 注册构建项目命令
    context.subscriptions.push(
        vscode.commands.registerCommand('nova.buildProject', () => {
            buildNovaProject();
        })
    );

    // 运行时补全 provider（.nova 文件）
    context.subscriptions.push(
        vscode.languages.registerCompletionItemProvider(
            { language: 'nova', scheme: 'file' },
            {
                async provideCompletionItems(document, position) {
                    if (!runtimeClient.isConfigured) return undefined;

                    // 检测 `xxx.yyy.zzz.` 链式调用模式 → 成员补全
                    const lineText = document.lineAt(position.line).text;
                    const textBefore = lineText.substring(0, position.character);

                    // ── import 语句补全 ──
                    // import java.util. → 包名/类名补全
                    // import java java.util. → 同上（import java 前缀）
                    const importMatch = textBefore.match(/^\s*import\s+(?:java\s+)?([A-Za-z_][\w.]*\.)$/);
                    if (importMatch) {
                        const importPrefix = importMatch[1];
                        const lastDot = importPrefix.lastIndexOf('.');
                        const lastSeg = lastDot >= 0 ? importPrefix.substring(lastDot + 1) : importPrefix;
                        const replaceStart = new vscode.Position(position.line,
                            position.character - lastSeg.length);
                        try {
                            const items = await runtimeClient.getJavaClasses(importPrefix);
                            return items.map(item => {
                                const isPackage = item.kind === 'namespace';
                                const ci = new vscode.CompletionItem(
                                    item.label,
                                    isPackage ? vscode.CompletionItemKind.Module : vscode.CompletionItemKind.Class
                                );
                                if (item.detail) ci.detail = item.detail;
                                ci.range = new vscode.Range(replaceStart, position);
                                if (isPackage) {
                                    ci.insertText = item.label + '.';
                                    ci.command = { command: 'editor.action.triggerSuggest', title: '' };
                                }
                                ci.sortText = '   ' + item.label;
                                return ci;
                            });
                        } catch { /* ignore */ }
                        return undefined;
                    }

                    // 检测 Java.type("org.bukkit.") / javaClass("org.bukkit.") 参数内的类名补全
                    const javaTypeArgMatch = textBefore.match(/(?:Java\.type|javaClass)\s*\(\s*"([^"]*)$/);
                    if (javaTypeArgMatch) {
                        const classPrefix = javaTypeArgMatch[1];
                        // 只替换最后一段（点号后的部分）
                        const lastDot = classPrefix.lastIndexOf('.');
                        const lastSeg = lastDot >= 0 ? classPrefix.substring(lastDot + 1) : classPrefix;
                        const replaceStart = new vscode.Position(position.line,
                            position.character - lastSeg.length);
                        try {
                            const items = await runtimeClient.getJavaClasses(classPrefix);
                            return items.map(item => {
                                const isPackage = item.kind === 'namespace';
                                const ci = new vscode.CompletionItem(
                                    item.label,
                                    isPackage ? vscode.CompletionItemKind.Module : vscode.CompletionItemKind.Class
                                );
                                if (item.detail) ci.detail = item.detail;
                                ci.range = new vscode.Range(replaceStart, position);
                                // 包名选中后自动加点继续补全；类名直接完成
                                if (isPackage) {
                                    ci.insertText = item.label + '.';
                                    ci.command = { command: 'editor.action.triggerSuggest', title: '' };
                                }
                                ci.sortText = '   ' + item.label;
                                return ci;
                            });
                        } catch { /* ignore */ }
                        return undefined;
                    }

                    // 跳过普通字符串内的点号
                    let inString = false;
                    let strChar = '';
                    for (let i = 0; i < textBefore.length; i++) {
                        const ch = textBefore[i];
                        if (ch === '\\' && inString) { i++; continue; }
                        if (!inString && (ch === '"' || ch === '\'')) { inString = true; strChar = ch; }
                        else if (inString && ch === strChar) { inString = false; }
                    }
                    if (inString) return undefined;

                    // ── Java. 互操作命名空间 ──
                    const javaNamespaceMatch = textBefore.match(/\bJava\s*\.\s*([A-Za-z_]\w*)?$/);
                    if (javaNamespaceMatch && !textBefore.match(/\bJava\.type\s*\(/) && !textBefore.match(/\bJava\.static\s*\(/)) {
                        const memberPrefix = (javaNamespaceMatch[1] || '').toLowerCase();
                        const javaApis: { label: string; detail: string; snippet: string }[] = [
                            { label: 'type', detail: 'Java.type(className) — 导入 Java 类（静态成员 + 构造器）', snippet: 'type("$1")' },
                            { label: 'static', detail: 'Java.static(className) — 访问 Java 类的静态方法和字段', snippet: 'static("$1")' },
                            { label: 'extend', detail: 'Java.extend(baseClass) — 继承 Java 类创建子类', snippet: 'extend("$1")' },
                            { label: 'from', detail: 'Java.from(javaCollection) — 将 Java 集合转为 Nova 列表', snippet: 'from($1)' },
                            { label: 'to', detail: 'Java.to(novaList, javaType) — 将 Nova 列表转为 Java 数组/集合', snippet: 'to($1, "$2")' },
                            { label: 'isType', detail: 'Java.isType(obj, className) — 判断对象是否是指定 Java 类型', snippet: 'isType($1, "$2")' },
                            { label: 'synchronized', detail: 'Java.synchronized(lock, block) — 在 Java 对象上同步执行', snippet: 'synchronized($1) {$2}' },
                        ];
                        return javaApis
                            .filter(api => api.label.toLowerCase().startsWith(memberPrefix))
                            .map(api => {
                                const ci = new vscode.CompletionItem(api.label, vscode.CompletionItemKind.Method);
                                ci.detail = api.detail;
                                ci.insertText = new vscode.SnippetString(api.snippet);
                                ci.sortText = '   ' + api.label;
                                ci.documentation = new vscode.MarkdownString(api.detail);
                                return ci;
                            });
                    }

                    // ── Java.type("xxx") / javaClass("xxx") 特化 ──
                    // 直接在点号前检测：Java.type("org.bukkit.Material").
                    const javaTypeMatch = textBefore.match(/(?:Java\.type|javaClass)\s*\(\s*"([^"]+)"\s*\)\s*\.\s*([A-Za-z_]\w*)?$/);
                    if (javaTypeMatch) {
                        const className = javaTypeMatch[1];
                        const memberPrefix = javaTypeMatch[2] || '';
                        try {
                            const items = await runtimeClient.getMembersByType(className, memberPrefix);
                            return items.map(item => {
                                const ci = toVscodeCompletionItem(item);
                                ci.sortText = '   ' + item.label;
                                return ci;
                            });
                        } catch { /* 类不存在 */ }
                        return undefined;
                    }

                    // ── Lambda 参数类型推导 ──
                    // expr.also { it -> it. } / expr.let { x -> x. } 等
                    // 检测：光标前是 `identifier.`，且 identifier 是 lambda 参数名
                    const lambdaParamDotMatch = textBefore.match(/([A-Za-z_]\w*)\s*\.\s*([A-Za-z_]\w*)?$/);
                    if (lambdaParamDotMatch) {
                        const paramName = lambdaParamDotMatch[1];
                        const memberPrefix = lambdaParamDotMatch[2] || '';
                        // 在当前行和上面几行找 `{ paramName ->` 或 `{ paramName,`
                        const resolvedType = await resolveLambdaParamType(document, position, paramName, runtimeClient);
                        if (resolvedType) {
                            try {
                                const items = await runtimeClient.getMembersByType(resolvedType, memberPrefix);
                                if (items.length > 0) {
                                    return items.map(item => {
                                        const ci = toVscodeCompletionItem(item);
                                        ci.sortText = '   ' + item.label;
                                        return ci;
                                    });
                                }
                            } catch { /* ignore */ }
                        }
                    }

                    // 追踪赋值：val xxx = Java.type("...") 或 val xxx = javaClass("...")
                    // 然后 xxx. 触发时查找该类的成员
                    const chainMatch = textBefore.match(/((?:[A-Za-z_]\w*(?:\([^)]*\))?\s*\.\s*)*[A-Za-z_]\w*(?:\([^)]*\))?)\s*\.\s*([A-Za-z_]\w*)?$/);

                    if (chainMatch) {
                        const chain = chainMatch[1].replace(/\s+/g, '');
                        const memberPrefix = chainMatch[2] || '';

                        // 单标识符时，尝试在文档中追踪 Java.type/javaClass 赋值
                        if (!chain.includes('.')) {
                            const javaClassName = resolveJavaTypeAssignment(document, chain);
                            if (javaClassName) {
                                try {
                                    const items = await runtimeClient.getMembersByType(javaClassName, memberPrefix);
                                    return items.map(item => {
                                        const ci = toVscodeCompletionItem(item);
                                        ci.sortText = '   ' + item.label;
                                        return ci;
                                    });
                                } catch { /* 回退到普通查找 */ }
                            }
                        }

                        try {
                            const hasDot = chain.includes('.');
                            const items = hasDot
                                ? await runtimeClient.getChainMembers(chain, memberPrefix)
                                : await runtimeClient.getMembers(chain, memberPrefix);
                            // API 请求成功 → 已知对象的成员访问，不回退到全局补全
                            // items 为空说明是 void 或无匹配成员，返回空列表即可
                            return items.map(item => {
                                const ci = toVscodeCompletionItem(item);
                                ci.sortText = '   ' + item.label;
                                ci.filterText = chain + '.' + item.label;
                                return ci;
                            });
                        } catch {
                            // API 请求失败 → 点号后不回退全局补全
                        }
                        return undefined;
                    }

                    // 非点号触发 → 全局补全（仅在普通输入时触发，点号后不会走到这里）
                    const wordRange = document.getWordRangeAtPosition(position, /[A-Za-z_]\w*/);
                    const prefix = wordRange ? document.getText(wordRange) : '';
                    if (!prefix) return undefined; // 空前缀不触发
                    try {
                        const ctx = parseNovaFileContext(document);
                        const items = ctx
                            ? await runtimeClient.getContextCompletions(ctx, prefix)
                            : await runtimeClient.getCompletions(prefix);
                        return items.map(toVscodeCompletionItem);
                    } catch {
                        return undefined;
                    }
                },
            },
            '.', ':'
        )
    );

    // 运行时 Hover provider（.nova 文件 — 鼠标悬停显示类型和描述）
    context.subscriptions.push(
        vscode.languages.registerHoverProvider(
            { language: 'nova', scheme: 'file' },
            {
                async provideHover(document, position) {
                    if (!runtimeClient.isConfigured) return undefined;
                    const wordRange = document.getWordRangeAtPosition(position, /[A-Za-z_]\w*/);
                    if (!wordRange) return undefined;
                    const word = document.getText(wordRange);

                    // 1. shared() 注册表查询（函数/变量描述）
                    try {
                        const info = await runtimeClient.describe(word);
                        if (info) {
                            const md = new vscode.MarkdownString();
                            md.isTrusted = true;
                            if (info.isFunction) {
                                const arity = info.arity ?? 0;
                                const params = Array.from({ length: arity }, (_, i) => `arg${i + 1}`).join(', ');
                                md.appendCodeblock(`fun ${info.name}(${params})`, 'nova');
                            } else {
                                const vtype = (info as any).valueType || 'Any';
                                md.appendCodeblock(`val ${info.name}: ${vtype}`, 'nova');
                            }
                            if (info.description) md.appendMarkdown('\n' + info.description + '\n');
                            const meta: string[] = [];
                            if (info.scope) meta.push(`来源: \`${info.scope}\``);
                            if (info.namespace) meta.push(`命名空间: \`${info.namespace}\``);
                            if (meta.length > 0) md.appendMarkdown('\n---\n' + meta.join(' · '));
                            return new vscode.Hover(md, wordRange);
                        }
                    } catch { /* 继续尝试其他推导 */ }

                    // 2. Lambda 参数类型推导（it / 显式参数名）
                    const lambdaType = await resolveLambdaParamType(document, position, word, runtimeClient);
                    if (lambdaType) {
                        const simpleName = lambdaType.includes('.') ? lambdaType.substring(lambdaType.lastIndexOf('.') + 1) : lambdaType;
                        const md = new vscode.MarkdownString();
                        md.isTrusted = true;
                        md.appendCodeblock(`val ${word}: ${simpleName}`, 'nova');
                        md.appendMarkdown(`\n类型: \`${lambdaType}\`\n`);
                        md.appendMarkdown('\n---\n*lambda 参数 — 类型从接收者表达式推导*');
                        return new vscode.Hover(md, wordRange);
                    }

                    // 3. 变量赋值类型推导
                    // val xxx = Java.type("...") → 类型
                    const javaClassName = resolveJavaTypeAssignment(document, word);
                    if (javaClassName) {
                        const simpleName = javaClassName.includes('.') ? javaClassName.substring(javaClassName.lastIndexOf('.') + 1) : javaClassName;
                        const md = new vscode.MarkdownString();
                        md.isTrusted = true;
                        md.appendCodeblock(`val ${word}: ${simpleName}`, 'nova');
                        md.appendMarkdown(`\n类型: \`${javaClassName}\`\n`);
                        return new vscode.Hover(md, wordRange);
                    }

                    // 4. val xxx = expr 的表达式类型推导
                    const exprType = await resolveVarAssignmentType(document, word, runtimeClient);
                    if (exprType) {
                        const simpleName = exprType.includes('.') ? exprType.substring(exprType.lastIndexOf('.') + 1) : exprType;
                        const md = new vscode.MarkdownString();
                        md.isTrusted = true;
                        md.appendCodeblock(`val ${word}: ${simpleName}`, 'nova');
                        md.appendMarkdown(`\n类型: \`${exprType}\`\n`);
                        return new vscode.Hover(md, wordRange);
                    }

                    return undefined;
                },
            }
        )
    );

    // 配置变更时刷新 runtime client
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('nova.runtime.httpUrl')) {
                runtimeClient.refresh();
                if (runtimeClient.isConfigured) {
                    runtimeClient.health().then(ok => {
                        if (ok) {
                            vscode.window.showInformationMessage('NovaLang: 已连接运行时 API 服务');
                        } else {
                            vscode.window.showWarningMessage('NovaLang: 无法连接运行时 API 服务，请检查 nova.runtime.httpUrl 配置');
                        }
                    });
                }
            }
        })
    );
}

function tryStartLsp(context: vscode.ExtensionContext) {
    const config = vscode.workspace.getConfiguration('nova');
    let lspPath = config.get<string>('lsp.path', '');
    const javaPath = config.get<string>('lsp.javaPath', 'java');

    // 查找优先级: 用户配置 > 扩展内嵌 server/ > 工作区 build/libs/
    if (!lspPath) {
        const bundled = findBundledJar('nova-lsp');
        const workspace = findJar('nova-lsp');
        lspPath = bundled || workspace;
        // 诊断日志（输出到开发者控制台）
        console.log(`[NovaLang] LSP 查找: extensionPath=${extensionPath}, bundled=${bundled || '(未找到)'}, workspace=${workspace || '(未找到)'}, final=${lspPath || '(未找到)'}`);
    }

    if (!lspPath) {
        vscode.window.showWarningMessage(
            'NovaLang: 未找到 nova-lsp JAR，请执行 gradlew :nova-lsp:jar 构建，或在设置中配置 nova.lsp.path'
        );
    } else if (!fs.existsSync(lspPath)) {
        vscode.window.showErrorMessage(
            `NovaLang: LSP JAR 不存在: ${lspPath}`
        );
    } else {
        startLspClient(context, lspPath, javaPath, config);
    }
}

async function restartLsp(context: vscode.ExtensionContext) {
    if (client) {
        await client.stop();
        client = undefined;
    }
    tryStartLsp(context);
}

/**
 * 获取当前活动文件所属的工作区文件夹，回退到第一个工作区
 */
function getActiveWorkspaceFolder(): string | undefined {
    const editor = vscode.window.activeTextEditor;
    if (editor) {
        const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
        if (folder) return folder.uri.fsPath;
    }
    return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}

function startLspClient(
    context: vscode.ExtensionContext,
    lspPath: string,
    javaPath: string,
    config: vscode.WorkspaceConfiguration
) {
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('NovaLang LSP');
        context.subscriptions.push(outputChannel);
    }

    // 服务器选项：通过 Java 启动 nova-lsp
    const serverOptions: ServerOptions = {
        command: javaPath,
        args: ['-jar', lspPath],
        options: {
            cwd: getActiveWorkspaceFolder(),
        },
    };

    // 读取 classpath 配置
    const classpath = config.get<string[]>('lsp.classpath', []);

    // 读取功能开关配置
    const enableTypeHints = config.get<boolean>('inlayHints.typeHints', true);
    const enableParamHints = config.get<boolean>('inlayHints.parameterHints', true);
    const enableSemanticHighlighting = config.get<boolean>('semanticHighlighting', true);

    // 瀹㈡埛绔€夐」
    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'nova' }],
        outputChannel: outputChannel,
        traceOutputChannel: outputChannel,
        initializationOptions: {
            classpath: classpath,
            inlayHints: {
                typeHints: enableTypeHints,
                parameterHints: enableParamHints,
            },
            semanticHighlighting: enableSemanticHighlighting,
        },
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.nova'),
        },
    };

    // 鍒涘缓骞跺惎鍔ㄥ鎴风
    client = new LanguageClient(
        'novaLanguageServer',
        'NovaLang Language Server',
        serverOptions,
        clientOptions
    );

    client.start();
    outputChannel.appendLine(`LSP JAR: ${lspPath}`);
    outputChannel.appendLine(`Java: ${javaPath}`);
    embeddedNovaController?.dispose();
    embeddedNovaController = new EmbeddedNovaController(client);
    context.subscriptions.push(embeddedNovaController);
}

/**
 * 运行当前打开的 Nova 脚本文件
 */
async function runNovaFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage('No file is currently open.');
        return;
    }

    const document = editor.document;
    if (document.languageId !== 'nova') {
        vscode.window.showWarningMessage('当前文件不是 Nova 脚本');
        return;
    }

    // 保存文件
    if (document.isDirty) {
        await document.save();
    }

    const config = vscode.workspace.getConfiguration('nova');
    const filePath = document.uri.fsPath;
    const javaPath = config.get<string>('lsp.javaPath', 'java');

    // 查找 nova-cli JAR
    let cliPath = config.get<string>('cli.path', '');
    if (!cliPath) {
        cliPath = findBundledJar('nova-cli') || findJar('nova-cli');
    }

    if (!cliPath || !fs.existsSync(cliPath)) {
        vscode.window.showErrorMessage(
            'NovaLang: 未找到 nova-cli JAR，请在设置中配置 nova.cli.path 或先执行 gradlew build'
        );
        return;
    }

    // 复用或创建终端
    if (runTerminal && runTerminal.exitStatus === undefined) {
        runTerminal.show();
    } else {
        runTerminal = vscode.window.createTerminal('Nova');
        runTerminal.show();
    }

    // 发送运行命令（Windows PowerShell 需要 & 调用操作符）
    const cmd = process.platform === 'win32'
        ? `& "${javaPath}" -jar "${cliPath}" "${filePath}"`
        : `"${javaPath}" -jar "${cliPath}" "${filePath}"`;
    runTerminal.sendText(cmd);
}

/**
 * 编译并直接运行当前 Nova 文件（不输出 .class 文件） */
async function compileAndRunNovaFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage('No file is currently open.');
        return;
    }

    const document = editor.document;
    if (document.languageId !== 'nova') {
        vscode.window.showWarningMessage('当前文件不是 Nova 脚本');
        return;
    }

    if (document.isDirty) {
        await document.save();
    }

    const config = vscode.workspace.getConfiguration('nova');
    const filePath = document.uri.fsPath;
    const javaPath = config.get<string>('lsp.javaPath', 'java');

    let cliPath = config.get<string>('cli.path', '');
    if (!cliPath) {
        cliPath = findBundledJar('nova-cli') || findJar('nova-cli');
    }

    if (!cliPath || !fs.existsSync(cliPath)) {
        vscode.window.showErrorMessage(
            'NovaLang: 未找到 nova-cli JAR，请在设置中配置 nova.cli.path 或先执行 gradlew build'
        );
        return;
    }

    if (runTerminal && runTerminal.exitStatus === undefined) {
        runTerminal.show();
    } else {
        runTerminal = vscode.window.createTerminal('Nova');
        runTerminal.show();
    }

    const cmd = process.platform === 'win32'
        ? `& "${javaPath}" -jar "${cliPath}" -r "${filePath}"`
        : `"${javaPath}" -jar "${cliPath}" -r "${filePath}"`;
    runTerminal.sendText(cmd);
}

/**
 * 构建 Nova 项目（编译当前工作区所有 .nova 文件，可选生成 JAR） */
async function buildNovaProject() {
    const workspaceRoot = getActiveWorkspaceFolder();
    if (!workspaceRoot) {
        vscode.window.showWarningMessage('没有打开的工作区');
        return;
    }

    const config = vscode.workspace.getConfiguration('nova');
    const javaPath = config.get<string>('lsp.javaPath', 'java');

    let cliPath = config.get<string>('cli.path', '');
    if (!cliPath) {
        cliPath = findBundledJar('nova-cli') || findJar('nova-cli');
    }

    if (!cliPath || !fs.existsSync(cliPath)) {
        vscode.window.showErrorMessage(
            'NovaLang: 未找到 nova-cli JAR，请在设置中配置 nova.cli.path 或先执行 gradlew build'
        );
        return;
    }

    // 让用户选择是否生成 JAR
    const buildOption = await vscode.window.showQuickPick(
        [
            { label: '缂栬瘧鍒?class 鏂囦欢', description: 'build/classes', value: 'classes' },
            { label: '编译并打包 JAR', description: 'build/output.jar', value: 'jar' },
        ],
        { placeHolder: '选择构建方式' }
    );

    if (!buildOption) {
        return;
    }

    if (runTerminal && runTerminal.exitStatus === undefined) {
        runTerminal.show();
    } else {
        runTerminal = vscode.window.createTerminal('Nova');
        runTerminal.show();
    }

    let cmd: string;
    if (buildOption.value === 'jar') {
        cmd = process.platform === 'win32'
            ? `& "${javaPath}" -jar "${cliPath}" build "${workspaceRoot}" --jar "${path.join(workspaceRoot, 'build', 'output.jar')}"`
            : `"${javaPath}" -jar "${cliPath}" build "${workspaceRoot}" --jar "${path.join(workspaceRoot, 'build', 'output.jar')}"`;
    } else {
        cmd = process.platform === 'win32'
            ? `& "${javaPath}" -jar "${cliPath}" build "${workspaceRoot}" -o "${path.join(workspaceRoot, 'build', 'classes')}"`
            : `"${javaPath}" -jar "${cliPath}" build "${workspaceRoot}" -o "${path.join(workspaceRoot, 'build', 'classes')}"`;
    }
    runTerminal.sendText(cmd);
}

/**
 * 自动查找指定模块的 JAR 文件（在工作区的 build/libs 目录下）
 * 排除 -sources/-javadoc JAR，优先选择最新版本。 */
/**
 * 解析 .nova 文件开头的上下文声明。
 * 格式：// nova=key:value（必须在前 5 行内）
 * 返回 "key:value" 或 undefined。
 */
function parseNovaFileContext(document: vscode.TextDocument): string | undefined {
    const maxLines = Math.min(document.lineCount, 5);
    for (let i = 0; i < maxLines; i++) {
        const line = document.lineAt(i).text.trim();
        const match = line.match(/^\/\/\s*nova\s*=\s*([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)\s*$/);
        if (match) return match[1];
        // 跳过空行和其他注释，遇到非注释代码停止
        if (line && !line.startsWith('//')) break;
    }
    return undefined;
}

/**
 * 推导 lambda 参数的类型。
 * 向上扫描找到 `{ paramName ->` 或 `{ paramName,` 的 lambda 开头，
 * 再找到该 lambda 前面的接收者表达式（如 expr.also { ... }），
 * 通过 /api/resolve-type 解析 expr 的返回类型。
 *
 * 支持的作用域函数：also, let, run, apply, with, forEach, map, filter, find 等
 */
const SCOPE_FUNCS = 'also|let|run|apply|with|forEach|map|filter|find|flatMap|any|all|none|first|last|sortBy|groupBy|associate|onEach|takeWhile|dropWhile|reduce|fold';

async function resolveLambdaParamType(
    document: vscode.TextDocument,
    position: vscode.Position,
    paramName: string,
    client: NovaRuntimeClient
): Promise<string | null> {
    const maxScan = Math.min(position.line + 1, 30);
    let combinedText = '';

    const chainPart = '([A-Za-z_]\\w*(?:\\([^)]*\\))?(?:\\s*\\.\\s*[A-Za-z_]\\w*(?:\\([^)]*\\))?)*)';
    const scopePart = `(${SCOPE_FUNCS})`;
    const escapedParam = paramName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

    for (let i = position.line; i >= Math.max(0, position.line - maxScan); i--) {
        combinedText = document.lineAt(i).text + ' ' + combinedText;

        // 1. 显式参数：expr.scopeFunc { paramName ->
        const explicitPattern = new RegExp(
            chainPart + '\\s*\\.\\s*' + scopePart +
            '\\s*\\{\\s*' + escapedParam + '\\s*->'
        );
        const explicitMatch = combinedText.match(explicitPattern);
        if (explicitMatch) {
            return resolveReceiverType(explicitMatch[1], client);
        }

        // 2. 隐式 it：expr.scopeFunc { ... it. （没有 xxx -> 声明）
        if (paramName === 'it') {
            const implicitPattern = new RegExp(
                chainPart + '\\s*\\.\\s*' + scopePart +
                '\\s*\\{(?!\\s*\\w+\\s*->)' // { 后面不能紧跟 xxx -> （否则 it 被覆盖）
            );
            const implicitMatch = combinedText.match(implicitPattern);
            if (implicitMatch) {
                return resolveReceiverType(implicitMatch[1], client);
            }
        }
    }
    return null;
}

async function resolveReceiverType(expr: string, client: NovaRuntimeClient): Promise<string | null> {
    const clean = expr.replace(/\s+/g, '');
    const typeInfo = await client.resolveType(clean);
    return typeInfo?.type ?? null;
}

/**
 * 在文档中追踪变量的 Java.type/javaClass 赋值。
 * 扫描光标前的行，匹配 val/var xxx = Java.type("class") 或 javaClass("class")。
 */
function resolveJavaTypeAssignment(document: vscode.TextDocument, varName: string): string | undefined {
    const pattern = new RegExp(
        `(?:val|var)\\s+${varName}\\s*=\\s*(?:Java\\.type|javaClass)\\s*\\(\\s*"([^"]+)"\\s*\\)`
    );
    const maxScan = Math.min(document.lineCount, 200);
    for (let i = 0; i < maxScan; i++) {
        const match = document.lineAt(i).text.match(pattern);
        if (match) return match[1];
    }
    return undefined;
}

/**
 * 推导 val/var 赋值表达式的类型。
 * val x = Bukkit.getPluginManager() → 通过 resolve-type 推导
 * val x = getPlayer("Steve") → 通过 shared() 函数返回类型推导
 */
async function resolveVarAssignmentType(
    document: vscode.TextDocument,
    varName: string,
    client: NovaRuntimeClient
): Promise<string | null> {
    const pattern = new RegExp(
        `(?:val|var)\\s+${varName}\\s*=\\s*(.+?)\\s*$`
    );
    const maxScan = Math.min(document.lineCount, 200);
    for (let i = 0; i < maxScan; i++) {
        const match = document.lineAt(i).text.match(pattern);
        if (match) {
            const expr = match[1].trim();
            // 跳过简单字面量
            if (/^[0-9]/.test(expr) || /^"/.test(expr) || /^\[/.test(expr) || /^\{/.test(expr)) {
                return null;
            }
            // 链式表达式类型推导
            const typeInfo = await client.resolveType(expr);
            if (typeInfo) return typeInfo.type;
            return null;
        }
    }
    return null;
}

/**
 * 从扩展安装目录的 server/ 子目录查找内嵌 JAR。
 * 这样用户安装扩展后无需手动配置 lsp.path。
 */
function findBundledJar(moduleName: string): string {
    if (!extensionPath) return '';
    const serverDir = path.join(extensionPath, 'server');
    if (!fs.existsSync(serverDir)) return '';
    const jars = fs.readdirSync(serverDir)
        .filter(f => f.startsWith(moduleName) && f.endsWith('.jar')
            && !f.includes('-sources') && !f.includes('-javadoc'))
        .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    return jars.length > 0 ? path.join(serverDir, jars[0]) : '';
}

/** 打开 .nova/.yml 文件时，如果未配置运行时 URL，提示连接 */
function promptRuntimeConnection(context: vscode.ExtensionContext) {
    const prompted = context.globalState.get<boolean>('nova.runtime.prompted', false);
    if (prompted) return;

    const check = (document: vscode.TextDocument) => {
        const lang = document.languageId;
        if ((lang === 'nova' || lang === 'yaml') && !runtimeClient.isConfigured) {
            context.globalState.update('nova.runtime.prompted', true);
            vscode.window.showInformationMessage(
                'NovaLang: 连接运行中的 Nova 服务器可获得完整补全支持（运行时函数、变量、扩展函数）',
                '配置'
            ).then(choice => {
                if (choice === '配置') {
                    vscode.commands.executeCommand('workbench.action.openSettings', 'nova.runtime.httpUrl');
                }
            });
            disposable.dispose();
        }
    };

    const disposable = vscode.workspace.onDidOpenTextDocument(check);
    context.subscriptions.push(disposable);
    vscode.workspace.textDocuments.forEach(check);
}

function findJar(moduleName: string): string {
    const candidates = [
        `${moduleName}/build/libs`,
        `../${moduleName}/build/libs`,
    ];

    const workspaceRoot = getActiveWorkspaceFolder();
    if (!workspaceRoot) return '';

    for (const dir of candidates) {
        const fullDir = path.resolve(workspaceRoot, dir);
        if (fs.existsSync(fullDir)) {
            const jars = fs.readdirSync(fullDir)
                .filter(f =>
                    f.startsWith(moduleName) &&
                    f.endsWith('.jar') &&
                    !f.includes('-sources') &&
                    !f.includes('-javadoc'))
                .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
            if (jars.length > 0) {
                return path.join(fullDir, jars[0]);
            }
        }
    }
    return '';
}

export function deactivate(): Thenable<void> | undefined {
    embeddedNovaController?.dispose();
    embeddedNovaController = undefined;
    if (!client) {
        return undefined;
    }
    return client.stop();
}




