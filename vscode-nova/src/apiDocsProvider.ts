import * as vscode from 'vscode';
import { NovaRuntimeClient, RuntimeCompletionItem } from './runtimeClient';

/**
 * 侧边栏 NovaLang 文档中心 — 语法参考 + 内置 API + 运行时注册函数浏览器。
 */
export class ApiDocsProvider implements vscode.WebviewViewProvider {

    public static readonly viewType = 'nova.apiDocs';
    private view?: vscode.WebviewView;

    constructor(private readonly client: NovaRuntimeClient) {}

    resolveWebviewView(webviewView: vscode.WebviewView) {
        this.view = webviewView;
        webviewView.webview.options = { enableScripts: true };
        webviewView.webview.onDidReceiveMessage(async (msg) => {
            switch (msg.type) {
                case 'refresh': await this.refresh(); break;
                case 'search': await this.search(msg.query); break;
                case 'describe': await this.showDetail(msg.name); break;
                case 'members': await this.showMembers(msg.name); break;
                case 'extensions': await this.showExtensions(msg.typeName); break;
                case 'insertSnippet': this.insertSnippet(msg.code); break;
            }
        });
        this.refresh();
    }

    async refresh() {
        if (!this.view) return;
        const connected = await this.client.health();
        // 即使未连接也显示语言文档，只是运行时部分显示离线
        const functions = connected ? await this.client.getCompletions('') : [];
        const namespaces = connected ? await this.client.getNamespaces() : [];
        this.view.webview.html = this.renderFull(functions, namespaces, connected);
    }

    private async search(query: string) {
        if (!this.view) return;
        const items = await this.client.getCompletions(query);
        this.post({ type: 'searchResult', items });
    }

    private async showDetail(name: string) {
        if (!this.view) return;
        const info = await this.client.describe(name);
        if (info) this.post({ type: 'detail', info });
    }

    private async showMembers(name: string) {
        if (!this.view) return;
        try {
            const members = await this.client.getMembers(name, '');
            this.post({ type: 'membersResult', name, members });
        } catch {
            this.post({ type: 'membersResult', name, members: [] });
        }
    }

    private async showExtensions(typeName: string) {
        if (!this.view) return;
        try {
            const items = await this.client.getExtensionCompletions('', typeName, '');
            this.post({ type: 'extensionsResult', typeName, items });
        } catch {
            this.post({ type: 'extensionsResult', typeName, items: [] });
        }
    }

    private insertSnippet(code: string) {
        const editor = vscode.window.activeTextEditor;
        if (editor) {
            editor.insertSnippet(new vscode.SnippetString(code));
        }
    }

    private post(msg: any) { this.view?.webview.postMessage(msg); }

    // ================================================================
    //  渲染
    // ================================================================

    private renderFull(items: RuntimeCompletionItem[], namespaces: string[], connected: boolean): string {
        const functions = items.filter(i => i.kind === 'function');
        const variables = items.filter(i => i.kind === 'variable' || i.kind === 'constant');

        return `<!DOCTYPE html><html><head>${this.css()}</head><body>

<!-- 顶部标签页 -->
<div class="tabs">
    <button class="tab active" onclick="showTab('guide')">语法指南</button>
    <button class="tab" onclick="showTab('stdlib')">标准库</button>
    <button class="tab" onclick="showTab('runtime')">运行时</button>
</div>

<!-- ========== 语法指南 ========== -->
<div id="tab-guide" class="tab-content active">

<div class="card">
    <div class="card-title">变量声明</div>
    <pre class="code">val name = "Steve"     <span class="c">// 不可变</span>
var score = 0          <span class="c">// 可变</span>
score = 100            <span class="c">// 重新赋值</span></pre>
    <button class="try-btn" onclick="insert('val \${1:name} = \${2:value}\\n')">插入</button>
</div>

<div class="card">
    <div class="card-title">函数定义</div>
    <pre class="code">fun greet(name) {
    return "Hello, " + name
}

<span class="c">// 单行简写</span>
fun double(x) = x * 2

<span class="c">// Lambda</span>
val add = { a, b -> a + b }</pre>
    <button class="try-btn" onclick="insert('fun \${1:name}(\${2:params}) {\\n    \${3}\\n}\\n')">插入</button>
</div>

<div class="card">
    <div class="card-title">条件语句</div>
    <pre class="code">if (score >= 90) {
    "优秀"
} else if (score >= 60) {
    "及格"
} else {
    "不及格"
}

<span class="c">// when 表达式</span>
val level = when (score) {
    100 -> "满分"
    in 90..99 -> "优秀"
    in 60..89 -> "及格"
    else -> "不及格"
}</pre>
</div>

<div class="card">
    <div class="card-title">循环</div>
    <pre class="code">for (i in 0..9) {
    log(i)
}

for (item in list) {
    log(item)
}

while (condition) {
    <span class="c">// ...</span>
}</pre>
</div>

<div class="card">
    <div class="card-title">集合</div>
    <pre class="code">val list = [1, 2, 3, 4, 5]
val map = { "name": "Steve", "age": 20 }

<span class="c">// 高阶函数</span>
val even = list.filter { it % 2 == 0 }
val doubled = list.map { it * 2 }
val sum = list.reduce { a, b -> a + b }</pre>
</div>

<div class="card">
    <div class="card-title">类与对象</div>
    <pre class="code">class Player(val name, var hp = 100) {
    fun heal(amount) {
        hp = hp + amount
    }

    fun toString() = name + " (HP:" + hp + ")"
}

val p = Player("Steve")
p.heal(20)</pre>
</div>

<div class="card">
    <div class="card-title">空安全</div>
    <pre class="code">val x = null
x?.toString()       <span class="c">// 安全调用 → null</span>
x ?: "default"      <span class="c">// Elvis → "default"</span>
x?.length ?: 0      <span class="c">// 链式空安全</span></pre>
</div>

<div class="card">
    <div class="card-title">字符串模板</div>
    <pre class="code">val name = "Steve"
val msg = "Hello, \${name}!"
val calc = "1+1 = \${1 + 1}"</pre>
</div>

<div class="card">
    <div class="card-title">异常处理</div>
    <pre class="code">try {
    riskyOperation()
} catch (e) {
    log("错误: " + e.message)
} finally {
    cleanup()
}</pre>
</div>

<div class="card">
    <div class="card-title">异步并发</div>
    <pre class="code"><span class="c">// 异步启动</span>
launch { heavyTask() }

<span class="c">// 并行执行</span>
val results = parallel(
    { fetchA() },
    { fetchB() }
)

<span class="c">// 带超时</span>
withTimeout(3000) { slowTask() }</pre>
</div>

</div>

<!-- ========== 标准库 ========== -->
<div id="tab-stdlib" class="tab-content">

<div class="card">
    <div class="card-title">数学函数</div>
    <div class="func-list">
        <div class="f"><b>abs(x)</b> — 绝对值</div>
        <div class="f"><b>min(a, b)</b> — 最小值</div>
        <div class="f"><b>max(a, b)</b> — 最大值</div>
        <div class="f"><b>sqrt(x)</b> — 平方根</div>
        <div class="f"><b>pow(base, exp)</b> — 幂运算</div>
        <div class="f"><b>floor(x)</b> / <b>ceil(x)</b> — 取整</div>
        <div class="f"><b>round(x)</b> — 四舍五入</div>
        <div class="f"><b>random()</b> — 0~1 随机数</div>
        <div class="f"><b>randomInt(min, max)</b> — 整数随机</div>
    </div>
</div>

<div class="card">
    <div class="card-title">类型函数</div>
    <div class="func-list">
        <div class="f"><b>typeof(x)</b> — 获取类型名</div>
        <div class="f"><b>toInt(x)</b> / <b>toLong(x)</b> — 整数转换</div>
        <div class="f"><b>toDouble(x)</b> / <b>toFloat(x)</b> — 浮点转换</div>
        <div class="f"><b>toString(x)</b> — 转字符串</div>
        <div class="f"><b>isCallable(x)</b> — 是否可调用</div>
        <div class="f"><b>len(x)</b> — 集合/字符串长度</div>
    </div>
</div>

<div class="card">
    <div class="card-title">集合函数</div>
    <div class="func-list">
        <div class="f"><b>listOf(...)</b> — 创建列表</div>
        <div class="f"><b>mapOf(...)</b> — 创建字典</div>
        <div class="f"><b>setOf(...)</b> — 创建集合</div>
        <div class="f"><b>range(start, end)</b> — 范围</div>
    </div>
</div>

<div class="card">
    <div class="card-title">字符串方法</div>
    <div class="func-list">
        <div class="f"><b>.length</b> — 长度</div>
        <div class="f"><b>.substring(start, end)</b> — 子串</div>
        <div class="f"><b>.split(sep)</b> — 分割</div>
        <div class="f"><b>.trim()</b> — 去空白</div>
        <div class="f"><b>.uppercase()</b> / <b>.lowercase()</b></div>
        <div class="f"><b>.replace(old, new)</b> — 替换</div>
        <div class="f"><b>.contains(s)</b> — 包含判断</div>
        <div class="f"><b>.startsWith(s)</b> / <b>.endsWith(s)</b></div>
        <div class="f"><b>.toInt()</b> / <b>.toDouble()</b> — 解析</div>
    </div>
</div>

<div class="card">
    <div class="card-title">List 方法</div>
    <div class="func-list">
        <div class="f"><b>.size</b> — 长度</div>
        <div class="f"><b>.add(item)</b> — 添加</div>
        <div class="f"><b>.remove(item)</b> — 删除</div>
        <div class="f"><b>.get(index)</b> — 取值</div>
        <div class="f"><b>.contains(item)</b> — 包含</div>
        <div class="f"><b>.map { ... }</b> — 映射</div>
        <div class="f"><b>.filter { ... }</b> — 过滤</div>
        <div class="f"><b>.reduce { a, b -> ... }</b> — 归约</div>
        <div class="f"><b>.forEach { ... }</b> — 遍历</div>
        <div class="f"><b>.find { ... }</b> — 查找</div>
        <div class="f"><b>.sorted()</b> / <b>.reversed()</b></div>
        <div class="f"><b>.join(sep)</b> — 拼接为字符串</div>
    </div>
</div>

<div class="card">
    <div class="card-title">Map 方法</div>
    <div class="func-list">
        <div class="f"><b>.size</b> — 键值对数</div>
        <div class="f"><b>.get(key)</b> / <b>[key]</b> — 取值</div>
        <div class="f"><b>.put(key, value)</b> — 设值</div>
        <div class="f"><b>.remove(key)</b> — 删除</div>
        <div class="f"><b>.containsKey(key)</b> — 判断</div>
        <div class="f"><b>.keys</b> / <b>.values</b> / <b>.entries</b></div>
    </div>
</div>

<div class="card">
    <div class="card-title">常量</div>
    <div class="func-list">
        <div class="f"><b>PI</b> — 3.14159265...</div>
        <div class="f"><b>E</b> — 2.71828182...</div>
        <div class="f"><b>MAX_INT</b> / <b>MIN_INT</b></div>
        <div class="f"><b>MAX_LONG</b> / <b>MIN_LONG</b></div>
        <div class="f"><b>true</b> / <b>false</b> / <b>null</b></div>
    </div>
</div>

</div>

<!-- ========== 运行时 ========== -->
<div id="tab-runtime" class="tab-content">

${!connected ? `
<div class="card warn">
    <div class="card-title">⚠ 未连接</div>
    <p>未连接到 Nova 运行时 API。请启动服务器后点击刷新。</p>
    <button class="try-btn" onclick="vscode.postMessage({type:'refresh'})">刷新</button>
</div>` : ''}

<div class="toolbar">
    <input type="text" id="search" placeholder="搜索..." oninput="onSearch(this.value)" />
    <button onclick="vscode.postMessage({type:'refresh'})" title="刷新">↻</button>
</div>

<!-- 命名空间 -->
${namespaces.length > 0 ? `
<div class="card">
    <div class="card-title">命名空间 (${namespaces.length})</div>
    ${namespaces.map(ns => `
        <div class="item clickable" onclick="vscode.postMessage({type:'members', name:'${ns}'})">
            <span class="icon ns">N</span><span class="name">${ns}</span><span class="arrow">→</span>
        </div>`).join('')}
</div>` : ''}

<!-- 函数 -->
<div class="card">
    <div class="card-title">注册函数 (${functions.length})</div>
    <div id="func-list">
    ${functions.map(f => this.ri(f, 'fn')).join('')}
    </div>
    ${functions.length === 0 ? '<p class="empty">暂无注册函数</p>' : ''}
</div>

<!-- 变量 -->
<div class="card">
    <div class="card-title">注册变量 (${variables.length})</div>
    <div id="var-list">
    ${variables.map(v => this.ri(v, 'var')).join('')}
    </div>
    ${variables.length === 0 ? '<p class="empty">暂无注册变量</p>' : ''}
</div>

<!-- 类型浏览器 -->
<div class="card">
    <div class="card-title">类型成员浏览</div>
    <p class="hint">点击变量名查看其 Java 类型的方法和字段</p>
    <div class="type-grid">
        ${variables.filter(v => v.label[0] === v.label[0].toUpperCase()).map(v => `
        <button class="type-btn" onclick="vscode.postMessage({type:'members', name:'${v.label}'})">${this.e(v.label)}</button>
        `).join('')}
    </div>
</div>

</div>

<!-- 详情浮层 -->
<div id="overlay" class="overlay hidden"></div>

${this.js()}
</body></html>`;
    }

    private ri(item: RuntimeCompletionItem, type: string): string {
        const icon = type === 'fn' ? '<span class="icon fn">F</span>' : '<span class="icon var">V</span>';
        const detail = item.detail ? `<span class="item-detail">${this.e(item.detail)}</span>` : '';
        return `<div class="item clickable" onclick="vscode.postMessage({type:'describe', name:'${item.label}'})">
            ${icon}<span class="name">${this.e(item.label)}</span>${detail}</div>`;
    }

    private e(s: string): string {
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/'/g, '&#39;').replace(/"/g, '&quot;');
    }

    // ================================================================
    //  JS
    // ================================================================

    private js(): string {
        return `<script>
const vscode = acquireVsCodeApi();

function showTab(id) {
    document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.tab').forEach(el => el.classList.remove('active'));
    document.getElementById('tab-' + id).classList.add('active');
    event.target.classList.add('active');
    hideOverlay();
}

function onSearch(q) { vscode.postMessage({ type: 'search', query: q }); }

function insert(code) { vscode.postMessage({ type: 'insertSnippet', code }); }

function hideOverlay() {
    document.getElementById('overlay').classList.add('hidden');
}

function showOverlay(html) {
    const el = document.getElementById('overlay');
    el.innerHTML = '<div class="overlay-header"><button onclick="hideOverlay()">← 返回</button></div><div class="overlay-body">' + html + '</div>';
    el.classList.remove('hidden');
}

window.addEventListener('message', ev => {
    const msg = ev.data;
    if (msg.type === 'searchResult') updateSearch(msg.items);
    else if (msg.type === 'detail') renderDetail(msg.info);
    else if (msg.type === 'membersResult') renderMembers(msg.name, msg.members);
    else if (msg.type === 'extensionsResult') renderExtensions(msg.typeName, msg.items);
});

function updateSearch(items) {
    const fns = items.filter(i => i.kind === 'function');
    const vars = items.filter(i => i.kind === 'variable' || i.kind === 'constant');
    const fl = document.getElementById('func-list');
    const vl = document.getElementById('var-list');
    if (fl) fl.innerHTML = fns.length ? fns.map(f => mkItem(f,'fn')).join('') : '<p class="empty">无匹配</p>';
    if (vl) vl.innerHTML = vars.length ? vars.map(v => mkItem(v,'var')).join('') : '<p class="empty">无匹配</p>';
}

function mkItem(item, type) {
    const ic = type==='fn' ? '<span class="icon fn">F</span>' : '<span class="icon var">V</span>';
    const d = item.detail ? '<span class="item-detail">'+esc(item.detail)+'</span>' : '';
    return '<div class="item clickable" onclick="vscode.postMessage({type:\\'describe\\',name:\\''+item.label+'\\'})">'+ic+'<span class="name">'+esc(item.label)+'</span>'+d+'</div>';
}

function renderDetail(info) {
    let h = '<div class="detail-card">';
    if (info.isFunction) {
        const n = info.arity || 0;
        const p = Array.from({length:n},(_,i)=>'arg'+(i+1)).join(', ');
        h += '<pre class="code">fun '+esc(info.name)+'('+p+')</pre>';
    } else {
        h += '<pre class="code">val '+esc(info.name)+': '+(info.valueType||'Any')+'</pre>';
    }
    if (info.description) h += '<p class="desc">'+esc(info.description)+'</p>';
    const meta = [];
    if (info.scope) meta.push('来源: '+info.scope);
    if (info.namespace) meta.push('命名空间: '+info.namespace);
    if (meta.length) h += '<p class="meta">'+meta.join(' · ')+'</p>';
    // 如果是变量，提供查看成员按钮
    if (!info.isFunction) {
        h += '<button class="try-btn" onclick="vscode.postMessage({type:\\'members\\',name:\\''+info.name+'\\'})">查看成员 →</button>';
    }
    h += '</div>';
    showOverlay(h);
}

function renderMembers(name, members) {
    let h = '<h3>'+esc(name)+' 的成员</h3>';
    if (!members.length) { h += '<p class="empty">无可用成员</p>'; showOverlay(h); return; }
    const methods = members.filter(m => m.kind === 'method');
    const fields = members.filter(m => m.kind === 'field');
    const enums = members.filter(m => m.kind === 'enumMember');
    if (methods.length) {
        h += '<div class="member-section">方法 ('+methods.length+')</div>';
        for (const m of methods) h += '<div class="member"><span class="icon fn">M</span><b>'+esc(m.label)+'</b>'+(m.detail?'<div class="member-sig">'+esc(m.detail)+'</div>':'')+'</div>';
    }
    if (fields.length) {
        h += '<div class="member-section">字段 ('+fields.length+')</div>';
        for (const m of fields) h += '<div class="member"><span class="icon var">F</span><b>'+esc(m.label)+'</b>'+(m.detail?'<span class="member-type">: '+esc(m.detail)+'</span>':'')+'</div>';
    }
    if (enums.length) {
        h += '<div class="member-section">枚举值 ('+enums.length+')</div>';
        for (const m of enums) h += '<div class="member"><span class="icon enum">E</span><b>'+esc(m.label)+'</b></div>';
    }
    showOverlay(h);
}

function renderExtensions(typeName, items) {
    let h = '<h3>'+esc(typeName)+' 扩展方法</h3>';
    if (!items.length) { h += '<p class="empty">无扩展方法</p>'; showOverlay(h); return; }
    for (const m of items) h += '<div class="member"><span class="icon fn">E</span><b>'+esc(m.label)+'</b>'+(m.detail?'<div class="member-sig">'+esc(m.detail)+'</div>':'')+'</div>';
    showOverlay(h);
}

function esc(s){return s?s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'):'';}
</script>`;
    }

    // ================================================================
    //  CSS
    // ================================================================

    private css(): string {
        return `<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:var(--vscode-font-family);font-size:13px;color:var(--vscode-foreground);background:var(--vscode-sideBar-background);overflow-x:hidden}

/* 标签页 */
.tabs{display:flex;border-bottom:1px solid var(--vscode-sideBarSectionHeader-border);position:sticky;top:0;background:var(--vscode-sideBar-background);z-index:10}
.tab{flex:1;padding:8px 4px;border:none;background:none;color:var(--vscode-foreground);cursor:pointer;font-size:12px;opacity:.7;border-bottom:2px solid transparent}
.tab:hover{opacity:1}
.tab.active{opacity:1;border-bottom-color:var(--vscode-focusBorder);font-weight:bold}
.tab-content{display:none;padding:8px}
.tab-content.active{display:block}

/* 卡片 */
.card{background:var(--vscode-editor-background);border:1px solid var(--vscode-sideBarSectionHeader-border);border-radius:6px;padding:10px;margin-bottom:10px}
.card-title{font-weight:bold;font-size:12px;color:var(--vscode-sideBarSectionHeader-foreground);margin-bottom:8px;text-transform:uppercase;letter-spacing:.5px}
.card.warn{border-color:var(--vscode-editorWarning-foreground);background:rgba(255,200,0,.05)}

/* 代码块 */
.code{background:var(--vscode-textCodeBlock-background);padding:8px 10px;border-radius:4px;font-family:var(--vscode-editor-font-family);font-size:12px;line-height:1.6;overflow-x:auto;white-space:pre;margin-bottom:6px}
.code .c{color:var(--vscode-editorLineNumber-foreground);font-style:italic}

/* 函数列表 */
.func-list .f{padding:3px 0;border-bottom:1px solid var(--vscode-sideBarSectionHeader-border)}
.func-list .f:last-child{border-bottom:none}
.func-list .f b{color:var(--vscode-symbolIcon-functionForeground,#dcdcaa)}

/* 列表项 */
.item{display:flex;align-items:center;gap:6px;padding:4px 6px;border-radius:4px}
.clickable{cursor:pointer}
.clickable:hover{background:var(--vscode-list-hoverBackground)}
.icon{display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;border-radius:3px;font-size:10px;font-weight:bold;flex-shrink:0;color:#fff}
.icon.fn{background:#b180d7} .icon.var{background:#4fc1ff} .icon.ns{background:#e5c07b;color:#282c34} .icon.enum{background:#98c379}
.name{flex-shrink:0}
.item-detail{color:var(--vscode-descriptionForeground);font-size:11px;margin-left:auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:55%}
.arrow{margin-left:auto;color:var(--vscode-descriptionForeground)}
.empty{color:var(--vscode-descriptionForeground);font-size:12px;text-align:center;padding:12px}
.hint{color:var(--vscode-descriptionForeground);font-size:11px;margin-bottom:8px}

/* 搜索栏 */
.toolbar{display:flex;gap:4px;margin-bottom:10px}
.toolbar input{flex:1;padding:5px 8px;background:var(--vscode-input-background);color:var(--vscode-input-foreground);border:1px solid var(--vscode-input-border);border-radius:4px;outline:none;font-size:12px}
.toolbar input:focus{border-color:var(--vscode-focusBorder)}
.toolbar button{padding:4px 10px;background:var(--vscode-button-background);color:var(--vscode-button-foreground);border:none;border-radius:4px;cursor:pointer}

/* 按钮 */
.try-btn{display:inline-block;margin-top:6px;padding:3px 10px;background:var(--vscode-button-secondaryBackground);color:var(--vscode-button-secondaryForeground);border:none;border-radius:3px;cursor:pointer;font-size:11px}
.try-btn:hover{background:var(--vscode-button-secondaryHoverBackground)}
.type-grid{display:flex;flex-wrap:wrap;gap:4px;margin-top:6px}
.type-btn{padding:3px 8px;background:var(--vscode-badge-background);color:var(--vscode-badge-foreground);border:none;border-radius:3px;cursor:pointer;font-size:11px}
.type-btn:hover{opacity:.8}

/* 浮层 */
.overlay{position:fixed;top:0;left:0;right:0;bottom:0;background:var(--vscode-sideBar-background);z-index:20;overflow-y:auto;padding:8px}
.overlay.hidden{display:none}
.overlay-header{position:sticky;top:0;background:var(--vscode-sideBar-background);padding:6px 0;border-bottom:1px solid var(--vscode-sideBarSectionHeader-border);margin-bottom:8px}
.overlay-header button{background:none;border:none;color:var(--vscode-textLink-foreground);cursor:pointer;font-size:12px}
.overlay-header button:hover{text-decoration:underline}

/* 详情 */
.detail-card .desc{line-height:1.6;margin:8px 0}
.detail-card .meta{color:var(--vscode-descriptionForeground);font-size:11px}
.member-section{font-weight:bold;font-size:11px;color:var(--vscode-sideBarSectionHeader-foreground);margin:10px 0 4px;text-transform:uppercase}
.member{padding:5px 0;border-bottom:1px solid var(--vscode-sideBarSectionHeader-border);display:flex;flex-wrap:wrap;align-items:center;gap:6px}
.member b{color:var(--vscode-symbolIcon-methodForeground,#dcdcaa)}
.member-sig{width:100%;color:var(--vscode-descriptionForeground);font-family:var(--vscode-editor-font-family);font-size:11px;padding-left:24px}
.member-type{color:var(--vscode-descriptionForeground);font-size:11px}
h3{font-size:14px;margin-bottom:8px}
</style>`;
    }
}
