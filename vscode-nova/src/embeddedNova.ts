import * as vscode from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';

type LspPosition = { line: number; character: number };
type LspRange = { start: LspPosition; end: LspPosition };

interface EmbeddedNovaBlock {
    id: string;
    hostUri: string;
    virtualUri: string;
    namespace: string;
    headerLine: number;
    contentStartLine: number;
    endLine: number;
    indent: number;
    content: string;
    virtualContent: string;
    preludeLineCount: number;
}

interface HostBindingConfig {
    version?: number;
    globals?: HostSymbol[];
    namespaces?: Record<string, HostNamespace>;
}

interface HostNamespace {
    extends?: string[];
    globals?: HostSymbol[];
}

interface HostSymbol {
    name: string;
    kind: 'variable' | 'function' | 'object' | 'property';
    type?: string;
    mutable?: boolean;
    documentation?: string;
    parameters?: HostParameter[];
    returnType?: string;
    members?: HostSymbol[];
}

interface HostParameter {
    name: string;
    type?: string;
}

interface HostBindingState {
    config: HostBindingConfig;
    diagnostics: Map<string, vscode.Diagnostic[]>;
    namespaces: Set<string>;
}

const YAML_SELECTOR: vscode.DocumentSelector = [
    { language: 'yaml', scheme: 'file' },
    { language: 'yaml', scheme: 'untitled' },
];

const NOVA_MARKER = /^\s*#\s*nova(?:\s*=\s*([A-Za-z0-9_.-]+))?\s*$/;
const HEADER_MARKER = /#\s*nova(?:\s*=\s*([A-Za-z0-9_.-]+))?\s*$/;
const BLOCK_SCALAR = /:\s*[>|][0-9]?[+-]?\s*(?:#.*)?$/;

/** 语义令牌类型（与 SemanticTokensBuilder.java TOKEN_TYPES 对齐） */
const TOKEN_TYPES = [
    'namespace', 'type', 'class', 'enum', 'interface',
    'function', 'variable', 'parameter', 'property', 'keyword',
    'string', 'number', 'operator', 'comment', 'enumMember',
];

/** 语义令牌修饰符（与 SemanticTokensBuilder.java TOKEN_MODIFIERS 对齐） */
const TOKEN_MODIFIERS = [
    'declaration', 'definition', 'readonly', 'deprecated', 'modification',
];

export const SEMANTIC_LEGEND = new vscode.SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);

/** 客户端关键字正则（用于 YAML 嵌入高亮，弥补 LSP 不生成关键字令牌的不足） */
const NOVA_KW_REGEX = /\b(val|var|fun|if|else|for|while|do|when|return|class|interface|enum|object|import|is|as|in|true|false|null|try|catch|finally|throw|break|continue|super|this|constructor|init|public|private|protected|internal|abstract|override|open)\b/g;
const KW_TOKEN_TYPE = 9; // TOKEN_TYPES.indexOf('keyword')

export class EmbeddedNovaController implements vscode.Disposable {
    private readonly disposables: vscode.Disposable[] = [];
    private readonly syncedBlocks = new Map<string, Map<string, EmbeddedNovaBlock>>();
    private readonly blocksByVirtualUri = new Map<string, EmbeddedNovaBlock>();
    private readonly diagnosticsByVirtualUri = new Map<string, vscode.Diagnostic[]>();
    private readonly metaDiagnosticsByHostUri = new Map<string, vscode.Diagnostic[]>();
    private readonly diagnosticCollection = vscode.languages.createDiagnosticCollection('Nova Embedded');
    private readonly configDiagnosticCollection = vscode.languages.createDiagnosticCollection('Nova Host Bindings');
    private readonly configDiagnosticUrisByWorkspace = new Map<string, Set<string>>();
    private readonly hostBindingStateCache = new Map<string, HostBindingState>();
    private versionCounter = 1;

    constructor(private readonly client: LanguageClient) {
        this.disposables.push(this.diagnosticCollection);
        this.disposables.push(this.configDiagnosticCollection);
        const hostBindingWatcher = vscode.workspace.createFileSystemWatcher('**/.nova/*.json');
        this.disposables.push(
            hostBindingWatcher,
            vscode.workspace.onDidOpenTextDocument(document => this.handleOpenOrChange(document)),
            vscode.workspace.onDidChangeTextDocument(event => this.handleOpenOrChange(event.document)),
            vscode.workspace.onDidCloseTextDocument(document => this.handleClose(document)),
            hostBindingWatcher.onDidChange(uri => this.handleHostBindingFileEvent(uri)),
            hostBindingWatcher.onDidCreate(uri => this.handleHostBindingFileEvent(uri)),
            hostBindingWatcher.onDidDelete(uri => this.handleHostBindingFileEvent(uri)),
            this.client.onNotification('textDocument/publishDiagnostics', params => this.handleDiagnostics(params)),
            vscode.languages.registerCompletionItemProvider(
                YAML_SELECTOR,
                {
                    provideCompletionItems: async (document, position) => {
                        const block = this.findBlockAtPosition(document, position);
                        if (!block) return undefined;

                        const result = await this.client.sendRequest<any>('textDocument/completion', {
                            textDocument: { uri: block.virtualUri },
                            position: this.toEmbeddedPosition(block, position),
                        });

                        const items = Array.isArray(result) ? result : result?.items;
                        if (!Array.isArray(items)) return undefined;
                        return items.map((item: any) => this.toCompletionItem(item));
                    },
                },
                '.', '$', ':'
            ),
            vscode.languages.registerHoverProvider(YAML_SELECTOR, {
                provideHover: async (document, position) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/hover', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                    });
                    if (!result || !result.contents) return undefined;

                    const value = this.hoverValue(result.contents);
                    if (!value) return undefined;

                    const range = result.range ? this.toHostRange(block, result.range) : undefined;
                    return new vscode.Hover(new vscode.MarkdownString(value), range);
                },
            }),
            vscode.languages.registerSignatureHelpProvider(YAML_SELECTOR, {
                provideSignatureHelp: async (document, position) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/signatureHelp', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                    });
                    if (!result || !Array.isArray(result.signatures)) return undefined;

                    const help = new vscode.SignatureHelp();
                    help.activeSignature = result.activeSignature ?? 0;
                    help.activeParameter = result.activeParameter ?? 0;
                    help.signatures = result.signatures.map((sig: any) => {
                        const signature = new vscode.SignatureInformation(sig.label, this.hoverValue(sig.documentation));
                        signature.parameters = Array.isArray(sig.parameters)
                            ? sig.parameters.map((param: any) => new vscode.ParameterInformation(param.label, this.hoverValue(param.documentation)))
                            : [];
                        return signature;
                    });
                    return help;
                },
            }, '(', ','),
            vscode.languages.registerDefinitionProvider(YAML_SELECTOR, {
                provideDefinition: async (document, position) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/definition', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                    });

                    return this.toDefinitionResult(result);
                },
            }),
            vscode.languages.registerReferenceProvider(YAML_SELECTOR, {
                provideReferences: async (document, position, context) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/references', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                        context: { includeDeclaration: context.includeDeclaration },
                    });

                    return this.toReferenceResult(result);
                },
            }),
            vscode.languages.registerDocumentHighlightProvider(YAML_SELECTOR, {
                provideDocumentHighlights: async (document, position) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/documentHighlight', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                    });

                    if (!Array.isArray(result)) return undefined;
                    return result
                        .map((item: any) => this.toDocumentHighlight(block, item))
                        .filter(Boolean) as vscode.DocumentHighlight[];
                },
            }),
            vscode.languages.registerRenameProvider(YAML_SELECTOR, {
                prepareRename: async (document, position) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/prepareRename', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                    });
                    if (!result || !result.range) return undefined;

                    if ((result.range.start?.line ?? 0) < block.preludeLineCount) {
                        throw new Error('Host binding symbols cannot be renamed.');
                    }

                    return this.toHostRange(block, result.range);
                },
                provideRenameEdits: async (document, position, newName) => {
                    const block = this.findBlockAtPosition(document, position);
                    if (!block) return undefined;

                    const result = await this.client.sendRequest<any>('textDocument/rename', {
                        textDocument: { uri: block.virtualUri },
                        position: this.toEmbeddedPosition(block, position),
                        newName,
                    });

                    return this.toWorkspaceEdit(result);
                },
            }),
            vscode.languages.registerDocumentSemanticTokensProvider(YAML_SELECTOR, {
                provideDocumentSemanticTokens: async (document) => {
                    return this.provideSemanticTokens(document);
                },
            }, SEMANTIC_LEGEND),
        );

        for (const document of vscode.workspace.textDocuments) {
            this.refreshDocument(document);
        }
    }

    dispose(): void {
        for (const disposable of this.disposables) {
            disposable.dispose();
        }

        for (const blocks of this.syncedBlocks.values()) {
            for (const block of blocks.values()) {
                this.client.sendNotification('textDocument/didClose', {
                    textDocument: { uri: block.virtualUri },
                });
                this.blocksByVirtualUri.delete(block.virtualUri);
                this.diagnosticsByVirtualUri.delete(block.virtualUri);
            }
        }
        this.syncedBlocks.clear();
        this.diagnosticCollection.clear();
        this.configDiagnosticCollection.clear();
    }

    private handleOpenOrChange(document: vscode.TextDocument): void {
        if (this.isHostBindingsDocument(document)) {
            this.refreshWorkspaceForHostBindings(document.uri);
            return;
        }
        this.refreshDocument(document);
    }

    private handleClose(document: vscode.TextDocument): void {
        if (this.isHostBindingsDocument(document)) {
            this.refreshWorkspaceForHostBindings(document.uri);
            return;
        }
        this.closeDocument(document);
    }

    private handleHostBindingFileEvent(uri: vscode.Uri): void {
        this.refreshWorkspaceForHostBindings(uri);
    }

    private refreshDocument(document: vscode.TextDocument): void {
        if (!this.isEnabled() || document.languageId !== 'yaml') {
            return;
        }

        const nextBlocks = new Map<string, EmbeddedNovaBlock>();
        for (const block of this.extractBlocks(document)) {
            nextBlocks.set(block.id, block);
        }

        const key = document.uri.toString();
        const previousBlocks = this.syncedBlocks.get(key) ?? new Map<string, EmbeddedNovaBlock>();

        for (const block of nextBlocks.values()) {
            this.blocksByVirtualUri.set(block.virtualUri, block);
        }

        for (const [id, previous] of previousBlocks) {
            const next = nextBlocks.get(id);
            if (!next) {
                this.client.sendNotification('textDocument/didClose', {
                    textDocument: { uri: previous.virtualUri },
                });
                this.blocksByVirtualUri.delete(previous.virtualUri);
                this.diagnosticsByVirtualUri.delete(previous.virtualUri);
                continue;
            }

            if (previous.virtualContent !== next.virtualContent) {
                this.client.sendNotification('textDocument/didChange', {
                    textDocument: { uri: next.virtualUri, version: this.versionCounter++ },
                    contentChanges: [{ text: next.virtualContent }],
                });
            }
        }

        for (const [id, block] of nextBlocks) {
            if (!previousBlocks.has(id)) {
                this.client.sendNotification('textDocument/didOpen', {
                    textDocument: {
                        uri: block.virtualUri,
                        languageId: 'nova',
                        version: this.versionCounter++,
                        text: block.virtualContent,
                    },
                });
            }
        }

        this.syncedBlocks.set(key, nextBlocks);
        this.metaDiagnosticsByHostUri.set(key, this.collectNamespaceDiagnostics(document, nextBlocks));
        this.publishHostDiagnostics(document.uri.toString());
    }

    private closeDocument(document: vscode.TextDocument): void {
        const key = document.uri.toString();
        const blocks = this.syncedBlocks.get(key);
        if (!blocks) return;

        for (const block of blocks.values()) {
            this.client.sendNotification('textDocument/didClose', {
                textDocument: { uri: block.virtualUri },
            });
            this.blocksByVirtualUri.delete(block.virtualUri);
            this.diagnosticsByVirtualUri.delete(block.virtualUri);
        }
        this.syncedBlocks.delete(key);
        this.metaDiagnosticsByHostUri.delete(key);
        this.diagnosticCollection.delete(document.uri);
    }

    private extractBlocks(document: vscode.TextDocument): EmbeddedNovaBlock[] {
        const lines = this.documentLines(document);
        const blocks: EmbeddedNovaBlock[] = [];
        let pendingNamespace: string | undefined;

        for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            const line = lines[lineIndex];
            const trimmed = line.trim();

            const standaloneMarker = this.parseMarker(trimmed);
            if (standaloneMarker && !BLOCK_SCALAR.test(line)) {
                pendingNamespace = standaloneMarker;
                continue;
            }

            if (!BLOCK_SCALAR.test(line)) {
                if (trimmed.length > 0 && !trimmed.startsWith('#')) {
                    pendingNamespace = undefined;
                }
                continue;
            }

            const sameLineNamespace = this.parseHeaderMarker(line);
            const namespace = sameLineNamespace ?? pendingNamespace;
            pendingNamespace = undefined;
            if (!namespace) {
                continue;
            }

            const headerIndent = this.leadingSpaces(line);
            let contentIndent: number | undefined;
            const contentLines: string[] = [];
            let endLine = lineIndex;

            for (let contentLine = lineIndex + 1; contentLine < lines.length; contentLine++) {
                const raw = lines[contentLine];
                const trimmedRaw = raw.trim();
                const indent = this.leadingSpaces(raw);

                if (contentIndent === undefined) {
                    if (trimmedRaw.length === 0) {
                        contentLines.push('');
                        endLine = contentLine;
                        continue;
                    }
                    if (indent <= headerIndent) {
                        break;
                    }
                    contentIndent = indent;
                }

                if (trimmedRaw.length > 0 && indent < contentIndent) {
                    break;
                }

                contentLines.push(trimmedRaw.length === 0 ? '' : raw.substring(Math.min(contentIndent, raw.length)));
                endLine = contentLine;
            }

            if (contentIndent === undefined) {
                continue;
            }

            const id = `${lineIndex}:${namespace}`;
            const virtualUri = this.virtualUri(document.uri, id, namespace).toString();
            const preludeLines = this.buildPreludeLines(document.uri, namespace);
            const virtualLines = preludeLines.concat(contentLines);
            blocks.push({
                id,
                hostUri: document.uri.toString(),
                virtualUri,
                namespace,
                headerLine: lineIndex,
                contentStartLine: lineIndex + 1,
                endLine,
                indent: contentIndent,
                content: contentLines.join('\n'),
                virtualContent: virtualLines.join('\n'),
                preludeLineCount: preludeLines.length,
            });

            lineIndex = endLine;
        }

        return blocks;
    }

    private findBlockAtPosition(document: vscode.TextDocument, position: vscode.Position): EmbeddedNovaBlock | undefined {
        const blocks = this.syncedBlocks.get(document.uri.toString());
        if (!blocks) return undefined;

        for (const block of blocks.values()) {
            if (position.line < block.contentStartLine || position.line > block.endLine) {
                continue;
            }

            const lineText = document.lineAt(position.line).text;
            if (lineText.trim().length > 0 && position.character < block.indent) {
                continue;
            }

            return block;
        }

        return undefined;
    }

    private async provideSemanticTokens(document: vscode.TextDocument): Promise<vscode.SemanticTokens | undefined> {
        const blocks = this.syncedBlocks.get(document.uri.toString());
        if (!blocks || blocks.size === 0) return undefined;

        type RawToken = { line: number; char: number; length: number; type: number; mod: number };
        const allTokens: RawToken[] = [];

        for (const block of blocks.values()) {
            // 1. 客户端关键字扫描（优先添加，排序后关键字在同位置 LSP 令牌之前）
            const contentLines = block.content.split('\n');
            for (let i = 0; i < contentLines.length; i++) {
                const hostLine = block.contentStartLine + i;
                const lineContent = contentLines[i];
                NOVA_KW_REGEX.lastIndex = 0;
                let match;
                while ((match = NOVA_KW_REGEX.exec(lineContent)) !== null) {
                    allTokens.push({
                        line: hostLine,
                        char: block.indent + match.index,
                        length: match[0].length,
                        type: KW_TOKEN_TYPE,
                        mod: 0,
                    });
                }
            }

            // 2. LSP 语义令牌
            try {
                const result = await this.client.sendRequest<any>('textDocument/semanticTokens/full', {
                    textDocument: { uri: block.virtualUri },
                });

                if (result?.data && Array.isArray(result.data)) {
                    const data: number[] = result.data;
                    let prevLine = 0;
                    let prevChar = 0;

                    for (let i = 0; i + 4 < data.length; i += 5) {
                        const deltaLine = data[i];
                        const deltaStartChar = data[i + 1];
                        const length = data[i + 2];
                        const tokenType = data[i + 3];
                        const tokenModifiers = data[i + 4];

                        const line = prevLine + deltaLine;
                        const startChar = deltaLine === 0 ? prevChar + deltaStartChar : deltaStartChar;
                        prevLine = line;
                        prevChar = startChar;

                        if (line < block.preludeLineCount) continue;

                        const hostLine = block.contentStartLine + (line - block.preludeLineCount);
                        const hostChar = block.indent + startChar;

                        allTokens.push({ line: hostLine, char: hostChar, length, type: tokenType, mod: tokenModifiers });
                    }
                }
            } catch {
                // LSP 未就绪或出错 — 跳过
            }
        }

        if (allTokens.length === 0) return undefined;

        // 排序：按行号、列号升序
        allTokens.sort((a, b) => a.line !== b.line ? a.line - b.line : a.char - b.char);

        // 去重：重叠令牌保留先出现的（关键字先添加，排序稳定 → 关键字优先）
        const filtered: RawToken[] = [allTokens[0]];
        for (let i = 1; i < allTokens.length; i++) {
            const prev = filtered[filtered.length - 1];
            const curr = allTokens[i];
            if (curr.line === prev.line && curr.char < prev.char + prev.length) {
                continue; // 重叠 — 跳过
            }
            filtered.push(curr);
        }

        const builder = new vscode.SemanticTokensBuilder(SEMANTIC_LEGEND);
        for (const token of filtered) {
            builder.push(token.line, token.char, token.length, token.type, token.mod);
        }

        return builder.build();
    }

    private toEmbeddedPosition(block: EmbeddedNovaBlock, position: vscode.Position): LspPosition {
        return {
            line: block.preludeLineCount + (position.line - block.contentStartLine),
            character: Math.max(0, position.character - block.indent),
        };
    }

    private toHostRange(block: EmbeddedNovaBlock, range: LspRange): vscode.Range {
        const startLine = Math.max(0, range.start.line - block.preludeLineCount);
        const endLine = Math.max(0, range.end.line - block.preludeLineCount);
        return new vscode.Range(
            new vscode.Position(block.contentStartLine + startLine, block.indent + range.start.character),
            new vscode.Position(block.contentStartLine + endLine, block.indent + range.end.character),
        );
    }

    private toCompletionItem(item: any): vscode.CompletionItem {
        const label = typeof item.label === 'string' ? item.label : item.label?.label ?? 'item';
        const completion = new vscode.CompletionItem(label, this.mapCompletionKind(item.kind));
        completion.detail = item.detail;
        completion.sortText = item.sortText;
        completion.filterText = item.filterText;
        completion.documentation = this.hoverValue(item.documentation)
            ? new vscode.MarkdownString(this.hoverValue(item.documentation)!)
            : undefined;

        if (item.insertText !== undefined) {
            completion.insertText = item.insertTextFormat === 2
                ? new vscode.SnippetString(item.insertText)
                : item.insertText;
        }

        return completion;
    }

    private handleDiagnostics(params: any): void {
        const uri = typeof params?.uri === 'string' ? params.uri : undefined;
        if (!uri) return;

        const block = this.blocksByVirtualUri.get(uri);
        if (!block) return;

        const diagnostics = Array.isArray(params?.diagnostics)
            ? params.diagnostics.map((diagnostic: any) => this.toHostDiagnostic(block, diagnostic)).filter(Boolean) as vscode.Diagnostic[]
            : [];

        this.diagnosticsByVirtualUri.set(uri, diagnostics);
        this.publishHostDiagnostics(block.hostUri);
    }

    private publishHostDiagnostics(hostUri: string): void {
        const blocks = this.syncedBlocks.get(hostUri);
        if (!blocks) {
            this.diagnosticCollection.delete(vscode.Uri.parse(hostUri));
            return;
        }

        const merged: vscode.Diagnostic[] = [
            ...(this.metaDiagnosticsByHostUri.get(hostUri) ?? []),
        ];
        for (const block of blocks.values()) {
            merged.push(...(this.diagnosticsByVirtualUri.get(block.virtualUri) ?? []));
        }
        this.diagnosticCollection.set(vscode.Uri.parse(hostUri), merged);
    }

    private refreshWorkspaceForHostBindings(uri: vscode.Uri): void {
        const folder = vscode.workspace.getWorkspaceFolder(uri);
        if (!folder) {
            return;
        }

        const workspaceRoot = folder.uri.fsPath;
        this.hostBindingStateCache.delete(workspaceRoot);
        const state = this.getHostBindingState(workspaceRoot);
        this.publishConfigDiagnostics(workspaceRoot, state);

        for (const document of vscode.workspace.textDocuments) {
            if (document.languageId === 'yaml') {
                const docFolder = vscode.workspace.getWorkspaceFolder(document.uri);
                if (docFolder && docFolder.uri.fsPath === workspaceRoot) {
                    this.refreshDocument(document);
                }
            }
        }
    }

    private collectNamespaceDiagnostics(document: vscode.TextDocument,
                                        blocks: Map<string, EmbeddedNovaBlock>): vscode.Diagnostic[] {
        const folder = vscode.workspace.getWorkspaceFolder(document.uri);
        if (!folder) {
            return [];
        }

        const state = this.getHostBindingState(folder.uri.fsPath);
        this.publishConfigDiagnostics(folder.uri.fsPath, state);

        const diagnostics: vscode.Diagnostic[] = [];
        for (const block of blocks.values()) {
            if (block.namespace === 'default') {
                continue;
            }
            if (!state.namespaces.has(block.namespace)) {
                const range = new vscode.Range(
                    new vscode.Position(block.headerLine, 0),
                    new vscode.Position(block.headerLine, document.lineAt(block.headerLine).text.length),
                );
                diagnostics.push(new vscode.Diagnostic(
                    range,
                    `Unknown Nova namespace '${block.namespace}'.`,
                    vscode.DiagnosticSeverity.Warning,
                ));
            }
        }
        return diagnostics;
    }

    private toHostDiagnostic(block: EmbeddedNovaBlock, diagnostic: any): vscode.Diagnostic | undefined {
        if (!diagnostic?.range) return undefined;
        const startLine = diagnostic.range.start?.line ?? 0;
        const endLine = diagnostic.range.end?.line ?? 0;
        if (endLine < block.preludeLineCount) {
            return undefined;
        }

        const range = this.toHostRange(block, diagnostic.range);
        const severity = this.toDiagnosticSeverity(diagnostic.severity);
        const hostDiagnostic = new vscode.Diagnostic(range, diagnostic.message ?? 'Nova diagnostic', severity);
        if (diagnostic.source) hostDiagnostic.source = diagnostic.source;
        return hostDiagnostic;
    }

    private toDiagnosticSeverity(severity: number | undefined): vscode.DiagnosticSeverity {
        switch (severity) {
            case 1: return vscode.DiagnosticSeverity.Error;
            case 2: return vscode.DiagnosticSeverity.Warning;
            case 3: return vscode.DiagnosticSeverity.Information;
            case 4: return vscode.DiagnosticSeverity.Hint;
            default: return vscode.DiagnosticSeverity.Information;
        }
    }

    private toDefinitionResult(result: any): vscode.Location | vscode.Location[] | undefined {
        if (!result) return undefined;
        if (Array.isArray(result)) {
            const mapped = result.map(item => this.toDefinitionLocation(item)).filter(Boolean) as vscode.Location[];
            return mapped.length > 0 ? mapped : undefined;
        }
        return this.toDefinitionLocation(result);
    }

    private toDefinitionLocation(location: any): vscode.Location | undefined {
        if (!location?.uri || !location?.range) return undefined;

        const block = this.blocksByVirtualUri.get(location.uri);
        if (block) {
            const targetRange = location.range.start?.line < block.preludeLineCount
                ? new vscode.Range(new vscode.Position(block.headerLine, 0), new vscode.Position(block.headerLine, 0))
                : this.toHostRange(block, location.range);
            return new vscode.Location(vscode.Uri.parse(block.hostUri), targetRange);
        }

        const range = new vscode.Range(
            new vscode.Position(location.range.start.line, location.range.start.character),
            new vscode.Position(location.range.end.line, location.range.end.character),
        );
        return new vscode.Location(vscode.Uri.parse(location.uri), range);
    }

    private toReferenceResult(result: any): vscode.Location[] | undefined {
        if (!Array.isArray(result)) return undefined;
        const mapped = result.map(item => this.toDefinitionLocation(item)).filter(Boolean) as vscode.Location[];
        return mapped.length > 0 ? mapped : undefined;
    }

    private toDocumentHighlight(block: EmbeddedNovaBlock, item: any): vscode.DocumentHighlight | undefined {
        if (!item?.range) return undefined;
        if ((item.range.end?.line ?? 0) < block.preludeLineCount) {
            return undefined;
        }

        const kind = item.kind === 3
            ? vscode.DocumentHighlightKind.Write
            : item.kind === 2
                ? vscode.DocumentHighlightKind.Read
                : vscode.DocumentHighlightKind.Text;
        return new vscode.DocumentHighlight(this.toHostRange(block, item.range), kind);
    }

    private toWorkspaceEdit(result: any): vscode.WorkspaceEdit | undefined {
        if (!result?.changes) return undefined;

        const edit = new vscode.WorkspaceEdit();
        for (const [uri, edits] of Object.entries<any>(result.changes)) {
            if (!Array.isArray(edits)) continue;

            const block = this.blocksByVirtualUri.get(uri);
            const targetUri = block ? vscode.Uri.parse(block.hostUri) : vscode.Uri.parse(uri);
            for (const textEdit of edits) {
                if (!textEdit?.range) continue;

                if (block) {
                    if ((textEdit.range.end?.line ?? 0) < block.preludeLineCount) {
                        continue;
                    }
                    edit.replace(targetUri, this.toHostRange(block, textEdit.range), textEdit.newText ?? '');
                } else {
                    edit.replace(
                        targetUri,
                        new vscode.Range(
                            new vscode.Position(textEdit.range.start.line, textEdit.range.start.character),
                            new vscode.Position(textEdit.range.end.line, textEdit.range.end.character),
                        ),
                        textEdit.newText ?? '',
                    );
                }
            }
        }

        return edit.size > 0 ? edit : undefined;
    }

    private mapCompletionKind(kind: number | undefined): vscode.CompletionItemKind {
        switch (kind) {
            case 2: return vscode.CompletionItemKind.Method;
            case 3: return vscode.CompletionItemKind.Function;
            case 5: return vscode.CompletionItemKind.Field;
            case 6: return vscode.CompletionItemKind.Variable;
            case 7: return vscode.CompletionItemKind.Class;
            case 8: return vscode.CompletionItemKind.Interface;
            case 10: return vscode.CompletionItemKind.Property;
            case 13: return vscode.CompletionItemKind.Enum;
            case 14: return vscode.CompletionItemKind.Keyword;
            case 15: return vscode.CompletionItemKind.Snippet;
            case 20: return vscode.CompletionItemKind.EnumMember;
            case 21: return vscode.CompletionItemKind.Constant;
            default: return vscode.CompletionItemKind.Text;
        }
    }

    private hoverValue(contents: any): string | undefined {
        if (!contents) return undefined;
        if (typeof contents === 'string') return contents;
        if (Array.isArray(contents)) {
            return contents.map(item => this.hoverValue(item)).filter(Boolean).join('\n\n');
        }
        if (typeof contents.value === 'string') return contents.value;
        return undefined;
    }

    private parseMarker(trimmedLine: string): string | undefined {
        const match = trimmedLine.match(NOVA_MARKER);
        if (!match) return undefined;
        return match[1] ?? 'default';
    }

    private parseHeaderMarker(line: string): string | undefined {
        const match = line.match(HEADER_MARKER);
        if (!match) return undefined;
        return match[1] ?? 'default';
    }

    private virtualUri(documentUri: vscode.Uri, blockId: string, namespace: string): vscode.Uri {
        return vscode.Uri.from({
            scheme: 'nova-embedded',
            path: `/${encodeURIComponent(documentUri.toString())}/${encodeURIComponent(blockId)}.nova`,
            query: `ns=${encodeURIComponent(namespace)}`,
        });
    }

    private leadingSpaces(text: string): number {
        let count = 0;
        while (count < text.length && text.charAt(count) === ' ') {
            count++;
        }
        return count;
    }

    private documentLines(document: vscode.TextDocument): string[] {
        const lines: string[] = [];
        for (let i = 0; i < document.lineCount; i++) {
            lines.push(document.lineAt(i).text);
        }
        return lines;
    }

    private buildPreludeLines(documentUri: vscode.Uri, namespace: string): string[] {
        const symbols = this.resolveNamespaceSymbols(documentUri, namespace);
        if (symbols.length === 0) {
            return [];
        }

        const lines: string[] = [];
        for (const symbol of symbols) {
            this.emitSymbolPrelude(symbol, lines, 0);
        }
        lines.push('', '');
        return lines;
    }

    private resolveNamespaceSymbols(documentUri: vscode.Uri, namespace: string): HostSymbol[] {
        const folder = vscode.workspace.getWorkspaceFolder(documentUri);
        if (!folder) {
            return [];
        }

        const state = this.getHostBindingState(folder.uri.fsPath);
        const config = state.config;
        const namespaces = config.namespaces ?? {};

        const merged = new Map<string, HostSymbol>();
        const visited = new Set<string>();
        const visitNamespace = (name: string) => {
            if (visited.has(name)) return;
            visited.add(name);

            const ns = namespaces[name];
            if (!ns) return;

            const parents = [...(ns.extends ?? [])];
            if (name !== 'default' && namespaces.default) {
                parents.unshift('default');
            }
            for (const parent of parents) {
                visitNamespace(parent);
            }

            for (const symbol of ns.globals ?? []) {
                if (symbol?.name) {
                    merged.set(symbol.name, symbol);
                }
            }
        };

        if (config.globals && config.globals.length > 0) {
            for (const symbol of config.globals) {
                if (symbol?.name) {
                    merged.set(symbol.name, symbol);
                }
            }
        }

        visitNamespace(namespace || 'default');
        return [...merged.values()];
    }

    private getHostBindingState(workspaceRoot: string): HostBindingState {
        const cached = this.hostBindingStateCache.get(workspaceRoot);
        if (cached) {
            return cached;
        }

        const state = this.loadHostBindingState(workspaceRoot);
        this.hostBindingStateCache.set(workspaceRoot, state);
        return state;
    }

    private loadHostBindingState(workspaceRoot: string): HostBindingState {
        const config: HostBindingConfig = { namespaces: {} };
        const diagnostics = new Map<string, vscode.Diagnostic[]>();
        const namespaces = new Set<string>();
        const novaDir = vscode.Uri.joinPath(vscode.Uri.file(workspaceRoot), '.nova').fsPath;
        if (!require('fs').existsSync(novaDir)) {
            return { config, diagnostics, namespaces };
        }

        const files = require('fs').readdirSync(novaDir)
            .filter((name: string) => name.toLowerCase().endsWith('.json'))
            .map((name: string) => `${novaDir}${require('path').sep}${name}`)
            .sort();

        for (const file of files) {
            const fileUri = vscode.Uri.file(file).toString();
            try {
                const parsed = JSON.parse(this.readHostBindingFile(file)) as HostBindingConfig;
                const fileDiagnostics = this.validateHostBindingConfig(parsed, fileUri);
                if (fileDiagnostics.length > 0) {
                    diagnostics.set(fileUri, fileDiagnostics);
                }

                if (Array.isArray(parsed.globals)) {
                    config.globals = (config.globals ?? []).concat(parsed.globals);
                }
                if (parsed.namespaces) {
                    for (const [key, value] of Object.entries(parsed.namespaces)) {
                        namespaces.add(key);
                        const existing = config.namespaces?.[key] ?? { globals: [], extends: [] };
                        config.namespaces![key] = {
                            extends: [...(existing.extends ?? []), ...((value as HostNamespace).extends ?? [])],
                            globals: [...(existing.globals ?? []), ...((value as HostNamespace).globals ?? [])],
                        };
                    }
                }
            } catch (error) {
                diagnostics.set(fileUri, [new vscode.Diagnostic(
                    new vscode.Range(new vscode.Position(0, 0), new vscode.Position(0, 1)),
                    `Invalid Nova host binding JSON: ${error instanceof Error ? error.message : 'Unknown error'}`,
                    vscode.DiagnosticSeverity.Error,
                )]);
            }
        }

        if ((config.globals?.length ?? 0) > 0 || (config.namespaces?.default?.globals?.length ?? 0) > 0) {
            namespaces.add('default');
        }

        return { config, diagnostics, namespaces };
    }

    private publishConfigDiagnostics(workspaceRoot: string, state: HostBindingState): void {
        const previousUris = this.configDiagnosticUrisByWorkspace.get(workspaceRoot) ?? new Set<string>();
        const nextUris = new Set<string>(state.diagnostics.keys());

        for (const previous of previousUris) {
            if (!nextUris.has(previous)) {
                this.configDiagnosticCollection.delete(vscode.Uri.parse(previous));
            }
        }

        for (const [uri, diagnostics] of state.diagnostics) {
            this.configDiagnosticCollection.set(vscode.Uri.parse(uri), diagnostics);
        }

        this.configDiagnosticUrisByWorkspace.set(workspaceRoot, nextUris);
    }

    private readHostBindingFile(filePath: string): string {
        const openDocument = vscode.workspace.textDocuments.find(document => document.uri.fsPath === filePath);
        if (openDocument) {
            return openDocument.getText();
        }
        return require('fs').readFileSync(filePath, 'utf8');
    }

    private isHostBindingsDocument(document: vscode.TextDocument): boolean {
        return document.uri.scheme === 'file'
            && document.uri.fsPath.includes(`${require('path').sep}.nova${require('path').sep}`)
            && document.uri.fsPath.toLowerCase().endsWith('.json');
    }

    private validateHostBindingConfig(config: HostBindingConfig, fileUri: string): vscode.Diagnostic[] {
        const diagnostics: vscode.Diagnostic[] = [];
        const range = new vscode.Range(new vscode.Position(0, 0), new vscode.Position(0, 1));

        if (config.version !== undefined && config.version !== 1) {
            diagnostics.push(new vscode.Diagnostic(range, 'host-bindings.json version must be 1.', vscode.DiagnosticSeverity.Warning));
        }
        if (config.globals !== undefined && !Array.isArray(config.globals)) {
            diagnostics.push(new vscode.Diagnostic(range, 'globals must be an array.', vscode.DiagnosticSeverity.Error));
        }
        if (config.namespaces !== undefined && (typeof config.namespaces !== 'object' || Array.isArray(config.namespaces))) {
            diagnostics.push(new vscode.Diagnostic(range, 'namespaces must be an object.', vscode.DiagnosticSeverity.Error));
        }

        for (const symbol of config.globals ?? []) {
            diagnostics.push(...this.validateHostSymbol(symbol, 'globals', range));
        }

        for (const [namespace, value] of Object.entries(config.namespaces ?? {})) {
            if (!value || typeof value !== 'object' || Array.isArray(value)) {
                diagnostics.push(new vscode.Diagnostic(range, `Namespace '${namespace}' must be an object.`, vscode.DiagnosticSeverity.Error));
                continue;
            }

            if (value.extends !== undefined && !Array.isArray(value.extends)) {
                diagnostics.push(new vscode.Diagnostic(range, `Namespace '${namespace}'.extends must be an array.`, vscode.DiagnosticSeverity.Error));
            }
            for (const parent of value.extends ?? []) {
                if (typeof parent !== 'string' || parent.length === 0) {
                    diagnostics.push(new vscode.Diagnostic(range, `Namespace '${namespace}' has an invalid extends entry.`, vscode.DiagnosticSeverity.Error));
                }
            }
            if (value.globals !== undefined && !Array.isArray(value.globals)) {
                diagnostics.push(new vscode.Diagnostic(range, `Namespace '${namespace}'.globals must be an array.`, vscode.DiagnosticSeverity.Error));
            }
            for (const symbol of value.globals ?? []) {
                diagnostics.push(...this.validateHostSymbol(symbol, `namespaces.${namespace}.globals`, range));
            }
        }

        return diagnostics;
    }

    private validateHostSymbol(symbol: any, path: string, range: vscode.Range): vscode.Diagnostic[] {
        const diagnostics: vscode.Diagnostic[] = [];
        if (!symbol || typeof symbol !== 'object' || Array.isArray(symbol)) {
            diagnostics.push(new vscode.Diagnostic(range, `${path} entry must be an object.`, vscode.DiagnosticSeverity.Error));
            return diagnostics;
        }

        if (typeof symbol.name !== 'string' || symbol.name.length === 0) {
            diagnostics.push(new vscode.Diagnostic(range, `${path}.name must be a non-empty string.`, vscode.DiagnosticSeverity.Error));
        }

        const validKinds = new Set(['variable', 'function', 'object', 'property']);
        if (!validKinds.has(symbol.kind)) {
            diagnostics.push(new vscode.Diagnostic(range, `${path}.kind must be one of variable/function/object/property.`, vscode.DiagnosticSeverity.Error));
        }

        if (symbol.parameters !== undefined && !Array.isArray(symbol.parameters)) {
            diagnostics.push(new vscode.Diagnostic(range, `${path}.parameters must be an array.`, vscode.DiagnosticSeverity.Error));
        }
        for (const parameter of symbol.parameters ?? []) {
            if (!parameter || typeof parameter !== 'object' || Array.isArray(parameter)
                    || typeof parameter.name !== 'string' || parameter.name.length === 0) {
                diagnostics.push(new vscode.Diagnostic(range, `${path}.parameters contains an invalid parameter.`, vscode.DiagnosticSeverity.Error));
            }
        }

        if (symbol.members !== undefined && !Array.isArray(symbol.members)) {
            diagnostics.push(new vscode.Diagnostic(range, `${path}.members must be an array.`, vscode.DiagnosticSeverity.Error));
        }
        for (const member of symbol.members ?? []) {
            diagnostics.push(...this.validateHostSymbol(member, `${path}.members`, range));
        }

        return diagnostics;
    }

    private loadHostBindingConfig(workspaceRoot: string): HostBindingConfig {
        const result: HostBindingConfig = { namespaces: {} };
        const novaDir = vscode.Uri.joinPath(vscode.Uri.file(workspaceRoot), '.nova').fsPath;
        if (!vscode.workspace.fs || !vscode.workspace.getConfiguration('nova').get<boolean>('embeddedYaml.enabled', true)) {
            return result;
        }
        if (!require('fs').existsSync(novaDir)) {
            return result;
        }

        const files = require('fs').readdirSync(novaDir)
            .filter((name: string) => name.toLowerCase().endsWith('.json'))
            .map((name: string) => `${novaDir}${require('path').sep}${name}`)
            .sort();

        for (const file of files) {
            try {
                const parsed = JSON.parse(require('fs').readFileSync(file, 'utf8')) as HostBindingConfig;
                if (Array.isArray(parsed.globals)) {
                    result.globals = (result.globals ?? []).concat(parsed.globals);
                }
                if (parsed.namespaces) {
                    for (const [key, value] of Object.entries(parsed.namespaces)) {
                        const existing = result.namespaces?.[key] ?? { globals: [], extends: [] };
                        result.namespaces![key] = {
                            extends: [...(existing.extends ?? []), ...(value.extends ?? [])],
                            globals: [...(existing.globals ?? []), ...(value.globals ?? [])],
                        };
                    }
                }
            } catch {
            }
        }

        return result;
    }

    private emitSymbolPrelude(symbol: HostSymbol, lines: string[], indent: number): void {
        const pad = ' '.repeat(indent);
        switch (symbol.kind) {
            case 'variable':
            case 'property':
                lines.push(`${pad}${symbol.mutable ? 'var' : 'val'} ${symbol.name}: ${symbol.type ?? 'Any'}`);
                break;
            case 'function': {
                const params = (symbol.parameters ?? [])
                    .map(param => `${param.name}: ${param.type ?? 'Any'}`)
                    .join(', ');
                lines.push(`${pad}fun ${symbol.name}(${params}): ${symbol.returnType ?? 'Unit'} {}`);
                break;
            }
            case 'object':
                lines.push(`${pad}object ${symbol.name} {`);
                for (const member of symbol.members ?? []) {
                    this.emitSymbolPrelude(member, lines, indent + 4);
                }
                lines.push(`${pad}}`);
                break;
        }
    }

    private isEnabled(): boolean {
        return vscode.workspace.getConfiguration('nova').get<boolean>('embeddedYaml.enabled', true);
    }
}
