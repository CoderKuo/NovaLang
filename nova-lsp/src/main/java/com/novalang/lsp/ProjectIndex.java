package com.novalang.lsp;

import com.novalang.compiler.analysis.Symbol;
import com.novalang.compiler.analysis.SymbolKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectIndex {

    public static class SymbolEntry {
        public final String name;
        public final String uri;
        public final int line;
        public final int character;
        public final int endLine;
        public final int endCharacter;
        public final SymbolKind kind;
        public final String typeName;
        public final String packageName;
        public final String qualifiedName;
        public final String containerName;
        public final String containerQualifiedName;

        public SymbolEntry(String name, String uri, int line, int character,
                           int endLine, int endCharacter,
                           SymbolKind kind, String typeName,
                           String packageName, String qualifiedName,
                           String containerName, String containerQualifiedName) {
            this.name = name;
            this.uri = uri;
            this.line = line;
            this.character = character;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.kind = kind;
            this.typeName = typeName;
            this.packageName = packageName;
            this.qualifiedName = qualifiedName;
            this.containerName = containerName;
            this.containerQualifiedName = containerQualifiedName;
        }
    }

    private final Map<String, List<SymbolEntry>> globalSymbols = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileSymbolNames = new ConcurrentHashMap<>();

    public void updateFile(String uri, String packageName, List<Symbol> topLevelSymbols) {
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

            String qualifiedName = qualify(packageName, sym.getName());
            SymbolEntry entry = new SymbolEntry(
                    sym.getName(), uri, line, col,
                    line, col + safeLength(sym.getName()),
                    sym.getKind(), sym.getTypeName(),
                    packageName, qualifiedName,
                    null, null);

            globalSymbols.computeIfAbsent(sym.getName(), key -> Collections.synchronizedList(new ArrayList<>()))
                    .add(entry);
            names.add(sym.getName());

            if (sym.getMembers() != null) {
                for (Map.Entry<String, Symbol> memberEntry : sym.getMembers().entrySet()) {
                    Symbol memberSym = memberEntry.getValue();
                    int memberLine = 0, memberCol = 0;
                    if (memberSym.getLocation() != null) {
                        memberLine = memberSym.getLocation().getLine() - 1;
                        memberCol = memberSym.getLocation().getColumn() - 1;
                    }

                    String memberQualifiedName = qualify(qualifiedName, memberSym.getName());
                    SymbolEntry member = new SymbolEntry(
                            memberSym.getName(), uri, memberLine, memberCol,
                            memberLine, memberCol + safeLength(memberSym.getName()),
                            memberSym.getKind(), memberSym.getTypeName(),
                            packageName, memberQualifiedName,
                            sym.getName(), qualifiedName);

                    globalSymbols.computeIfAbsent(memberSym.getName(), key -> Collections.synchronizedList(new ArrayList<>()))
                            .add(member);
                    names.add(memberSym.getName());
                }
            }
        }

        fileSymbolNames.put(uri, names);
    }

    public void removeFile(String uri) {
        Set<String> names = fileSymbolNames.remove(uri);
        if (names == null) return;

        for (String name : names) {
            List<SymbolEntry> entries = globalSymbols.get(name);
            if (entries != null) {
                entries.removeIf(entry -> uri.equals(entry.uri));
                if (entries.isEmpty()) {
                    globalSymbols.remove(name);
                }
            }
        }
    }

    public List<SymbolEntry> findByName(String name) {
        List<SymbolEntry> entries = globalSymbols.get(name);
        return entries != null ? new ArrayList<>(entries) : Collections.emptyList();
    }

    public SymbolEntry findByQualifiedName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return null;
        }

        String simpleName = qualifiedName;
        int idx = qualifiedName.lastIndexOf('.');
        if (idx >= 0) {
            simpleName = qualifiedName.substring(idx + 1);
        }

        for (SymbolEntry entry : findByName(simpleName)) {
            if (qualifiedName.equals(entry.qualifiedName)) {
                return entry;
            }
        }
        return null;
    }

    public List<SymbolEntry> search(String query) {
        List<SymbolEntry> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (Map.Entry<String, List<SymbolEntry>> entry : globalSymbols.entrySet()) {
            if (entry.getKey().toLowerCase().contains(lowerQuery)) {
                results.addAll(entry.getValue());
            }
            if (results.size() > 200) break;
        }
        return results;
    }

    private String qualify(String prefix, String name) {
        if (name == null || name.isEmpty()) {
            return prefix;
        }
        if (prefix == null || prefix.isEmpty()) {
            return name;
        }
        return prefix + "." + name;
    }

    private int safeLength(String name) {
        return name != null ? name.length() : 0;
    }
}
