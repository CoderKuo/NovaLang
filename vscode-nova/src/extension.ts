import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let runTerminal: vscode.Terminal | undefined;
let outputChannel: vscode.OutputChannel | undefined;

export function activate(context: vscode.ExtensionContext) {
    tryStartLsp(context);

    // 监听配置变更，自动重启 LSP
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('nova.lsp')) {
                vscode.window.showInformationMessage('NovaLang: LSP 配置已变更，正在重启...');
                restartLsp(context);
            }
        })
    );

    // 注册运行命令
    context.subscriptions.push(
        vscode.commands.registerCommand('nova.runFile', () => {
            runNovaFile();
        })
    );

    // 注册编译运行命令
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
}

function tryStartLsp(context: vscode.ExtensionContext) {
    const config = vscode.workspace.getConfiguration('nova');
    let lspPath = config.get<string>('lsp.path', '');
    const javaPath = config.get<string>('lsp.javaPath', 'java');

    // 如果未配置 lsp.path，尝试自动查找
    if (!lspPath) {
        lspPath = findJar('nova-lsp');
    }

    if (!lspPath) {
        vscode.window.showWarningMessage(
            'NovaLang: 未找到 nova-lsp JAR，请在设置中配置 nova.lsp.path'
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

    // 客户端选项
    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'nova' }],
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

    // 创建并启动客户端
    client = new LanguageClient(
        'novaLanguageServer',
        'NovaLang Language Server',
        serverOptions,
        clientOptions
    );

    client.start();

    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('NovaLang LSP');
        context.subscriptions.push(outputChannel);
    }
    outputChannel.appendLine(`LSP JAR: ${lspPath}`);
    outputChannel.appendLine(`Java: ${javaPath}`);
}

/**
 * 运行当前打开的 Nova 脚本文件
 */
async function runNovaFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage('没有打开的文件');
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
        cliPath = findJar('nova-cli');
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
 * 编译并直接运行当前 Nova 文件（不输出 .class 文件）
 */
async function compileAndRunNovaFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage('没有打开的文件');
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
        cliPath = findJar('nova-cli');
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
 * 构建 Nova 项目（编译当前工作区所有 .nova 文件，可选生成 JAR）
 */
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
        cliPath = findJar('nova-cli');
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
            { label: '编译到 class 文件', description: 'build/classes', value: 'classes' },
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
 * 排除 -sources/-javadoc JAR，优先选择最新版本。
 */
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
    if (!client) {
        return undefined;
    }
    return client.stop();
}
