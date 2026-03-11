import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
} from 'vscode-languageclient/node';
import { EmbeddedNovaController } from './embeddedNova';

let client: LanguageClient | undefined;
let runTerminal: vscode.Terminal | undefined;
let outputChannel: vscode.OutputChannel | undefined;
let embeddedNovaController: EmbeddedNovaController | undefined;

export function activate(context: vscode.ExtensionContext) {
    tryStartLsp(context);

    // 鐩戝惉閰嶇疆鍙樻洿锛岃嚜鍔ㄩ噸鍚?LSP
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('nova')) {
                vscode.window.showInformationMessage('NovaLang: LSP 閰嶇疆宸插彉鏇达紝姝ｅ湪閲嶅惎...');
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

    // 娉ㄥ唽鏋勫缓椤圭洰鍛戒护
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

    // 自动查找 lsp.path
    if (!lspPath) {
        lspPath = findJar('nova-lsp');
    }

    if (!lspPath) {
        vscode.window.showWarningMessage(
            'NovaLang: 鏈壘鍒?nova-lsp JAR锛岃鍦ㄨ缃腑閰嶇疆 nova.lsp.path'
        );
    } else if (!fs.existsSync(lspPath)) {
        vscode.window.showErrorMessage(
            `NovaLang: LSP JAR 涓嶅瓨鍦? ${lspPath}`
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
 * 鑾峰彇褰撳墠娲诲姩鏂囦欢鎵€灞炵殑宸ヤ綔鍖烘枃浠跺す锛屽洖閫€鍒扮涓€涓伐浣滃尯
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

    // 鏈嶅姟鍣ㄩ€夐」锛氶€氳繃 Java 鍚姩 nova-lsp
    const serverOptions: ServerOptions = {
        command: javaPath,
        args: ['-jar', lspPath],
        options: {
            cwd: getActiveWorkspaceFolder(),
        },
    };

    // 璇诲彇 classpath 閰嶇疆
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
 * 杩愯褰撳墠鎵撳紑鐨?Nova 鑴氭湰鏂囦欢
 */
async function runNovaFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage('No file is currently open.');
        return;
    }

    const document = editor.document;
    if (document.languageId !== 'nova') {
        vscode.window.showWarningMessage('褰撳墠鏂囦欢涓嶆槸 Nova 鑴氭湰');
        return;
    }

    // 淇濆瓨鏂囦欢
    if (document.isDirty) {
        await document.save();
    }

    const config = vscode.workspace.getConfiguration('nova');
    const filePath = document.uri.fsPath;
    const javaPath = config.get<string>('lsp.javaPath', 'java');

    // 鏌ユ壘 nova-cli JAR
    let cliPath = config.get<string>('cli.path', '');
    if (!cliPath) {
        cliPath = findJar('nova-cli');
    }

    if (!cliPath || !fs.existsSync(cliPath)) {
        vscode.window.showErrorMessage(
            'NovaLang: 鏈壘鍒?nova-cli JAR锛岃鍦ㄨ缃腑閰嶇疆 nova.cli.path 鎴栧厛鎵ц gradlew build'
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

    // 鍙戦€佽繍琛屽懡浠わ紙Windows PowerShell 闇€瑕?& 璋冪敤鎿嶄綔绗︼級
    const cmd = process.platform === 'win32'
        ? `& "${javaPath}" -jar "${cliPath}" "${filePath}"`
        : `"${javaPath}" -jar "${cliPath}" "${filePath}"`;
    runTerminal.sendText(cmd);
}

/**
 * 缂栬瘧骞剁洿鎺ヨ繍琛屽綋鍓?Nova 鏂囦欢锛堜笉杈撳嚭 .class 鏂囦欢锛? */
async function compileAndRunNovaFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage('No file is currently open.');
        return;
    }

    const document = editor.document;
    if (document.languageId !== 'nova') {
        vscode.window.showWarningMessage('褰撳墠鏂囦欢涓嶆槸 Nova 鑴氭湰');
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
            'NovaLang: 鏈壘鍒?nova-cli JAR锛岃鍦ㄨ缃腑閰嶇疆 nova.cli.path 鎴栧厛鎵ц gradlew build'
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
 * 鏋勫缓 Nova 椤圭洰锛堢紪璇戝綋鍓嶅伐浣滃尯鎵€鏈?.nova 鏂囦欢锛屽彲閫夌敓鎴?JAR锛? */
async function buildNovaProject() {
    const workspaceRoot = getActiveWorkspaceFolder();
    if (!workspaceRoot) {
        vscode.window.showWarningMessage('娌℃湁鎵撳紑鐨勫伐浣滃尯');
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
            'NovaLang: 鏈壘鍒?nova-cli JAR锛岃鍦ㄨ缃腑閰嶇疆 nova.cli.path 鎴栧厛鎵ц gradlew build'
        );
        return;
    }

    // 璁╃敤鎴烽€夋嫨鏄惁鐢熸垚 JAR
    const buildOption = await vscode.window.showQuickPick(
        [
            { label: '缂栬瘧鍒?class 鏂囦欢', description: 'build/classes', value: 'classes' },
            { label: '缂栬瘧骞舵墦鍖?JAR', description: 'build/output.jar', value: 'jar' },
        ],
        { placeHolder: '閫夋嫨鏋勫缓鏂瑰紡' }
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
 * 鑷姩鏌ユ壘鎸囧畾妯″潡鐨?JAR 鏂囦欢锛堝湪宸ヤ綔鍖虹殑 build/libs 鐩綍涓嬶級
 * 鎺掗櫎 -sources/-javadoc JAR锛屼紭鍏堥€夋嫨鏈€鏂扮増鏈€? */
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




