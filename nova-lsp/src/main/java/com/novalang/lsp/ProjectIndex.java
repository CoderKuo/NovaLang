package com.novalang.lsp;

import com.novalang.compiler.analysis.Symbol;
import com.novalang.compiler.analysis.SymbolKind;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨文件项目符号索引
 *
 * <p>维护所有打开文档的全局符号表，支持跨文件的定义跳转、引用查找和工作区符号搜索。</p>
 */
public class ProjectIndex {

    /** 符号条目 */
    public static class SymbolEntry {
        public final String name;
        public final String uri;
        public final int line;       // 0-based
        public final int character;  // 0-based
        public final int endLine;
        public final int endCharacter;
        public final SymbolKind kind;
        public final String typeName;
        public final String containerName;

        public SymbolEntry(String name, String uri, int line, int character,
                           int endLine, int endCharacter,
                           SymbolKind kind, String typeName, String containerName) {
            this.name = name;
            this.uri = uri;
            this.line = line;
            this.character = character;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.kind = kind;
            this.typeName = typeName;
            this.containerName = containerName;
        }
    }

    /** name -> 所有文件中该名称的符号列表 */
    private final Map<String, List<SymbolEntry>> globalSymbols = new ConcurrentHashMap<>();

    /** uri -> 该文件导出的所有符号名集合（用于文件更新时快速清理） */
    private final Map<String, Set<String>> fileSymbolNames = new ConcurrentHashMap<>();

    /**
     * 更新文件的符号索引
     */
    public void updateFile(String uri, List<Symbol> topLevelSymbols) {
        // 先清理旧条目
        removeFile(uri);

        Set<String> names = new HashSet<>();
        for (Symbol sym : topLevelSymbols) {
            if (sym.getKind() == SymbolKind.BUILTIN_FUNCTION
                    || sym.getKind() == SymbolKind.BUILTIN_CONSTANT
                    || sym.getKind() == SymbolKind.IMPORT) {
                continue;
            }
            int line = 0, col = 0;
            if (sym.getLocation() != null) {
                line = sym.getLocation().getLine() - 1;
                col = sym.getLocation().getColumn() - 1;
            }
            SymbolEntry entry = new SymbolEntry(
                    sym.getName(), uri, line, col,
                    line, col + (sym.getName() != null ? sym.getName().length() : 0),
                    sym.getKind(), sym.getTypeName(), null);

            globalSymbols.computeIfAbsent(sym.getName(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(entry);
            names.add(sym.getName());

            // 注册类/接口成员
            if (sym.getMembers() != null) {
                for (Map.Entry<String, Symbol> memberEntry : sym.getMembers().entrySet()) {
                    Symbol memberSym = memberEntry.getValue();
                    int mLine = 0, mCol = 0;
                    if (memberSym.getLocation() != null) {
                        mLine = memberSym.getLocation().getLine() - 1;
                        mCol = memberSym.getLocation().getColumn() - 1;
                    }
                    SymbolEntry mEntry = new SymbolEntry(
                            memberSym.getName(), uri, mLine, mCol,
                            mLine, mCol + (memberSym.getName() != null ? memberSym.getName().length() : 0),
                            memberSym.getKind(), memberSym.getTypeName(), sym.getName());
                    globalSymbols.computeIfAbsent(memberSym.getName(), k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(mEntry);
                    names.add(memberSym.getName());
                }
            }
        }
        fileSymbolNames.put(uri, names);
    }

    /**
     * 移除文件的所有符号索引
     */
    public void removeFile(String uri) {
        Set<String> names = fileSymbolNames.remove(uri);
        if (names == null) return;
        for (String name : names) {
            List<SymbolEntry> entries = globalSymbols.get(name);
            if (entries != null) {
                entries.removeIf(e -> uri.equals(e.uri));
                if (entries.isEmpty()) {
                    globalSymbols.remove(name);
                }
            }
        }
    }

    /**
     * 按名称查找符号
     */
    public List<SymbolEntry> findByName(String name) {
        List<SymbolEntry> entries = globalSymbols.get(name);
        return entries != null ? new ArrayList<>(entries) : Collections.emptyList();
    }

    /**
     * 模糊搜索符号（workspace/symbol）
     */
    public List<SymbolEntry> search(String query) {
        List<SymbolEntry> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (Map.Entry<String, List<SymbolEntry>> entry : globalSymbols.entrySet()) {
            if (entry.getKey().toLowerCase().contains(lowerQuery)) {
                results.addAll(entry.getValue());
            }
            if (results.size() > 200) break; // 限制结果数量
        }
        return results;
    }
}
