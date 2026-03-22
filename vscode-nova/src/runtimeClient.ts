import * as http from 'http';
import * as https from 'https';
import * as vscode from 'vscode';

export interface RuntimeCompletionItem {
    label: string;
    kind: string;   // function | variable | extension | constant | namespace
    detail?: string;
    scope?: string;  // instance | shared | stdlib
    namespace?: string;
}

/**
 * Nova 运行时 HTTP API 客户端。
 * 从 NovaApiServer 获取运行时注册的函数/变量/扩展函数补全数据。
 */
export class NovaRuntimeClient {

    private baseUrl: string = '';
    private connected: boolean = false;
    private httpAgent?: any;
    private httpsAgent?: any;

    /** 从 VSCode 配置刷新 URL */
    refresh(): void {
        this.baseUrl = vscode.workspace.getConfiguration('nova').get<string>('runtime.httpUrl', '').replace(/\/+$/, '');
        this.connected = false;
        // 连接池（keepAlive 减少三次握手开销）
        if (this.baseUrl.startsWith('https')) {
            this.httpsAgent = new https.Agent({ keepAlive: true, maxSockets: 4 });
        } else if (this.baseUrl) {
            this.httpAgent = new http.Agent({ keepAlive: true, maxSockets: 4 });
        }
    }

    /** 是否已配置 */
    get isConfigured(): boolean {
        return this.baseUrl.length > 0;
    }

    /** 健康检查 */
    async health(): Promise<boolean> {
        if (!this.isConfigured) return false;
        try {
            const data = await this.get('/api/health');
            const result = JSON.parse(data);
            this.connected = result.status === 'ok';
            return this.connected;
        } catch {
            this.connected = false;
            return false;
        }
    }

    /** 获取全局补全项 */
    async getCompletions(prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItems(`/api/completions?prefix=${encodeURIComponent(prefix)}`);
    }

    /** 获取指定上下文的补全项 */
    async getContextCompletions(ctx: string, prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItems(`/api/context/completions?ctx=${encodeURIComponent(ctx)}&prefix=${encodeURIComponent(prefix)}`);
    }

    /** 获取扩展方法补全 */
    async getExtensionCompletions(ctx: string, typeName: string, prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItems(`/api/context/extensions?ctx=${encodeURIComponent(ctx)}&type=${encodeURIComponent(typeName)}&prefix=${encodeURIComponent(prefix)}`);
    }

    /** 获取已注册上下文列表 */
    async getContexts(): Promise<{ key: string; value: string; compositeKey: string }[]> {
        if (!this.isConfigured) return [];
        try {
            const data = await this.get('/api/contexts');
            return JSON.parse(data);
        } catch {
            return [];
        }
    }

    /** 获取命名空间列表 */
    async getNamespaces(): Promise<string[]> {
        if (!this.isConfigured) return [];
        try {
            const data = await this.get('/api/namespaces');
            return JSON.parse(data);
        } catch {
            return [];
        }
    }

    /** 获取对象成员列表（单层点号补全）。未知变量时抛异常。 */
    async getMembers(name: string, prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItemsStrict(`/api/members?name=${encodeURIComponent(name)}&prefix=${encodeURIComponent(prefix)}`);
    }

    /** 获取链式调用的成员列表（多层点号补全）。解析失败时抛异常。 */
    async getChainMembers(chain: string, prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItemsStrict(`/api/chain-members?chain=${encodeURIComponent(chain)}&prefix=${encodeURIComponent(prefix)}`);
    }

    /** 按 Java 全限定类名查成员（javaClass/Java.type 用）。 */
    async getMembersByType(className: string, prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItemsStrict(`/api/members?type=${encodeURIComponent(className)}&prefix=${encodeURIComponent(prefix)}`);
    }

    /** 解析链式表达式的最终返回类型 */
    async resolveType(chain: string): Promise<{ type: string; simpleName: string } | null> {
        if (!this.isConfigured) return null;
        try {
            const data = await this.get(`/api/resolve-type?chain=${encodeURIComponent(chain)}`);
            return JSON.parse(data);
        } catch {
            return null;
        }
    }

    /** Java 类名补全（Java.type/javaClass 参数用） */
    async getJavaClasses(prefix: string): Promise<RuntimeCompletionItem[]> {
        return this.fetchItems(`/api/java-classes?prefix=${encodeURIComponent(prefix)}`);
    }

    /** 获取函数/变量描述（hover 用） */
    async describe(name: string): Promise<{ name: string; description?: string; isFunction?: boolean; arity?: number; namespace?: string; scope?: string; valueType?: string } | null> {
        if (!this.isConfigured) return null;
        try {
            const data = await this.get(`/api/describe?name=${encodeURIComponent(name)}`);
            return JSON.parse(data);
        } catch {
            return null;
        }
    }

    // ======== 内部 ========

    /** 严格模式：200 返回结果（可能空数组），非 200 抛异常 */
    private async fetchItemsStrict(path: string): Promise<RuntimeCompletionItem[]> {
        if (!this.isConfigured) throw new Error('not configured');
        const data = await this.get(path); // 非 200 时 get() 会 reject
        return JSON.parse(data);
    }

    private async fetchItems(path: string): Promise<RuntimeCompletionItem[]> {
        if (!this.isConfigured) return [];
        try {
            const data = await this.get(path);
            return JSON.parse(data);
        } catch {
            return [];
        }
    }

    private get(path: string): Promise<string> {
        const url = this.baseUrl + path;
        const isHttps = url.startsWith('https');
        const client = isHttps ? https : http;
        // 重操作（ClassPath 扫描）给更长超时
        const timeout = path.includes('/java-classes') || path.includes('/resolve-type') ? 3000 : 800;
        const agent = isHttps ? this.httpsAgent : this.httpAgent;

        return new Promise((resolve, reject) => {
            const req = client.get(url, { timeout, agent }, (res) => {
                let data = '';
                res.on('data', chunk => data += chunk);
                res.on('end', () => {
                    if (res.statusCode === 200) {
                        resolve(data);
                    } else {
                        reject(new Error(`HTTP ${res.statusCode}`));
                    }
                });
            });
            req.on('error', reject);
            req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        });
    }
}

/** 将 RuntimeCompletionItem 转为 VSCode CompletionItem */
export function toVscodeCompletionItem(item: RuntimeCompletionItem): vscode.CompletionItem {
    const ci = new vscode.CompletionItem(item.label);

    switch (item.kind) {
        case 'function':
            ci.kind = vscode.CompletionItemKind.Function;
            ci.insertText = new vscode.SnippetString(item.label + '($0)');
            break;
        case 'method':
            ci.kind = vscode.CompletionItemKind.Method;
            ci.insertText = new vscode.SnippetString(item.label + '($0)');
            break;
        case 'variable':
            ci.kind = vscode.CompletionItemKind.Variable;
            break;
        case 'field':
            ci.kind = vscode.CompletionItemKind.Field;
            break;
        case 'constant':
            ci.kind = vscode.CompletionItemKind.Constant;
            break;
        case 'enumMember':
            ci.kind = vscode.CompletionItemKind.EnumMember;
            break;
        case 'class':
            ci.kind = vscode.CompletionItemKind.Class;
            break;
        case 'namespace':
            ci.kind = vscode.CompletionItemKind.Module;
            ci.insertText = new vscode.SnippetString(item.label + '.$0');
            break;
        case 'extension':
            ci.kind = vscode.CompletionItemKind.Method;
            ci.insertText = new vscode.SnippetString(item.label + '($0)');
            break;
        default:
            ci.kind = vscode.CompletionItemKind.Text;
    }

    // detail: 补全列表右侧灰色小字（简短）
    // documentation: 展开详情面板的完整描述（支持 Markdown）
    if (item.detail) {
        ci.detail = item.label;
        ci.documentation = new vscode.MarkdownString(item.detail);
    }
    if (item.scope && !item.detail) {
        ci.detail = `[${item.scope}]`;
    }

    // shared/instance 注册的运行时函数排在 stdlib 之前
    // stdlib 排在 LSP 静态分析之后
    if (item.scope === 'shared' || item.scope === 'instance') {
        ci.sortText = 'aa_' + item.label;  // 运行时注册优先
    } else {
        ci.sortText = 'zz_' + item.label;  // stdlib 排后
    }

    return ci;
}
