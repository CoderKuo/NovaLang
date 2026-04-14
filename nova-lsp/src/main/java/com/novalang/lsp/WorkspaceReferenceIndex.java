package com.novalang.lsp;

import com.novalang.compiler.analysis.Symbol;
import com.novalang.compiler.analysis.SymbolKind;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WorkspaceReferenceIndex {

    static final class ReferenceEntry {
        final String uri;
        final int line;
        final int character;
        final int endLine;
        final int endCharacter;
        final boolean declaration;
        final boolean renameAllowed;

        ReferenceEntry(String uri, int line, int character, int endLine, int endCharacter,
                       boolean declaration, boolean renameAllowed) {
            this.uri = uri;
            this.line = line;
            this.character = character;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.declaration = declaration;
            this.renameAllowed = renameAllowed;
        }
    }

    private static final class RawReference {
        final String name;
        final int line;
        final int character;
        final String explicitQualifiedTarget;
        final String containerName;
        final boolean renameAllowed;

        RawReference(String name, int line, int character, String explicitQualifiedTarget,
                     String containerName, boolean renameAllowed) {
            this.name = name;
            this.line = line;
            this.character = character;
            this.explicitQualifiedTarget = explicitQualifiedTarget;
            this.containerName = containerName;
            this.renameAllowed = renameAllowed;
        }
    }

    private static final class ImportSpec {
        final String qualifiedName;
        final String simpleName;
        final boolean wildcard;
        final String alias;
        final SourceLocation location;

        ImportSpec(String qualifiedName, String simpleName, boolean wildcard, String alias, SourceLocation location) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.wildcard = wildcard;
            this.alias = alias;
            this.location = location;
        }
    }

    private static final class FileSnapshot {
        final String uri;
        final String content;
        final String packageName;
        final DocumentManager.CachedAnalysis cached;
        final List<ImportSpec> imports;
        final List<RawReference> references;

        FileSnapshot(String uri, String content, String packageName,
                     DocumentManager.CachedAnalysis cached,
                     List<ImportSpec> imports,
                     List<RawReference> references) {
            this.uri = uri;
            this.content = content;
            this.packageName = packageName;
            this.cached = cached;
            this.imports = imports;
            this.references = references;
        }
    }

    private final ProjectIndex projectIndex;
    private final Map<String, FileSnapshot> snapshots = new ConcurrentHashMap<String, FileSnapshot>();
    private final Map<String, String> filePackages = new ConcurrentHashMap<String, String>();

    WorkspaceReferenceIndex(ProjectIndex projectIndex) {
        this.projectIndex = projectIndex;
    }

    void updateFile(String uri, String content, DocumentManager.CachedAnalysis cached) {
        if (uri == null || content == null || cached == null || cached.parseResult == null
                || cached.parseResult.getProgram() == null) {
            removeFile(uri);
            return;
        }

        Program program = cached.parseResult.getProgram();
        String packageName = extractPackageName(program);
        List<ImportSpec> imports = extractImports(program);
        List<RawReference> references = new ReferenceCollector(cached, content, imports, packageName).collect(program);

        snapshots.put(uri, new FileSnapshot(uri, content, packageName, cached, imports, references));
        filePackages.put(uri, packageName);
    }

    void removeFile(String uri) {
        if (uri == null) {
            return;
        }
        snapshots.remove(uri);
        filePackages.remove(uri);
    }

    Symbol findSymbol(ProjectIndex.SymbolEntry entry) {
        if (entry == null) {
            return null;
        }

        FileSnapshot snapshot = snapshots.get(entry.uri);
        if (snapshot == null || snapshot.cached == null || snapshot.cached.analysisResult == null) {
            return null;
        }

        List<Symbol> globals = snapshot.cached.analysisResult.getSymbolTable().getGlobalScope().getDeclaredSymbols();
        if (entry.containerQualifiedName == null) {
            for (Symbol symbol : globals) {
                String qualifiedName = qualify(snapshot.packageName, symbol.getName());
                if (entry.qualifiedName.equals(qualifiedName)) {
                    return symbol;
                }
            }
            return null;
        }

        for (Symbol symbol : globals) {
            String qualifiedName = qualify(snapshot.packageName, symbol.getName());
            if (entry.containerQualifiedName.equals(qualifiedName)
                    && symbol.getMembers() != null
                    && symbol.getMembers().containsKey(entry.name)) {
                return symbol.getMembers().get(entry.name);
            }
        }

        return null;
    }

    ProjectIndex.SymbolEntry resolveReference(String uri,
                                              String content,
                                              DocumentManager.CachedAnalysis cached,
                                              String name,
                                              int line,
                                              int character) {
        if (uri == null || content == null || cached == null || cached.parseResult == null
                || cached.parseResult.getProgram() == null || name == null || name.isEmpty()) {
            return null;
        }

        FileSnapshot snapshot = buildSnapshot(uri, content, cached);
        RawReference reference = null;
        for (RawReference candidate : snapshot.references) {
            if (!name.equals(candidate.name) || candidate.line != line) {
                continue;
            }

            int start = candidate.character;
            int end = candidate.character + candidate.name.length();
            if (character >= start && character <= end) {
                reference = candidate;
                break;
            }
        }

        if (reference == null) {
            reference = new RawReference(name, line, character, null, null, true);
        }
        return resolveReference(snapshot, reference);
    }

    List<ReferenceEntry> findReferences(ProjectIndex.SymbolEntry target, boolean includeDeclaration) {
        if (target == null) {
            return Collections.emptyList();
        }

        List<ReferenceEntry> result = new ArrayList<ReferenceEntry>();
        Set<String> seen = new HashSet<String>();

        if (includeDeclaration) {
            addReference(result, seen, new ReferenceEntry(
                    target.uri, target.line, target.character,
                    target.endLine, target.endCharacter,
                    true, true));
        }

        for (FileSnapshot snapshot : snapshots.values()) {
            String[] lines = snapshot.content.split("\n", -1);
            for (RawReference reference : snapshot.references) {
                ProjectIndex.SymbolEntry resolved = resolveReference(snapshot, reference);
                if (!sameSymbol(target, resolved)) {
                    continue;
                }

                int correctedColumn = LspTextUtils.correctColumnForWord(lines, reference.line, reference.character, reference.name);
                addReference(result, seen, new ReferenceEntry(
                        snapshot.uri,
                        reference.line,
                        correctedColumn,
                        reference.line,
                        correctedColumn + reference.name.length(),
                        false,
                        reference.renameAllowed && !usesAlias(snapshot, reference)));
            }
        }

        result.sort(Comparator
                .comparing((ReferenceEntry entry) -> entry.uri)
                .thenComparingInt(entry -> entry.line)
                .thenComparingInt(entry -> entry.character));
        return result;
    }

    private void addReference(List<ReferenceEntry> result, Set<String> seen, ReferenceEntry entry) {
        String key = entry.uri + ":" + entry.line + ":" + entry.character + ":" + entry.endCharacter + ":" + entry.declaration;
        if (seen.add(key)) {
            result.add(entry);
        }
    }

    private boolean sameSymbol(ProjectIndex.SymbolEntry left, ProjectIndex.SymbolEntry right) {
        return left != null && right != null
                && Objects.equals(left.name, right.name)
                && Objects.equals(left.uri, right.uri)
                && left.line == right.line
                && left.character == right.character
                && left.kind == right.kind
                && Objects.equals(left.qualifiedName, right.qualifiedName)
                && Objects.equals(left.containerQualifiedName, right.containerQualifiedName);
    }

    private ProjectIndex.SymbolEntry resolveReference(FileSnapshot snapshot, RawReference reference) {
        if (reference.explicitQualifiedTarget != null) {
            return findByQualifiedName(reference.explicitQualifiedTarget);
        }

        if (reference.containerName != null) {
            List<ProjectIndex.SymbolEntry> members = memberCandidates(reference.name, reference.containerName);
            if (members.size() == 1) {
                return members.get(0);
            }
            return null;
        }

        Symbol localSymbol = resolveLocalSymbol(snapshot, reference);
        if (localSymbol != null) {
            ProjectIndex.SymbolEntry exact = findExactEntry(snapshot.uri, reference.name, localSymbol.getKind(), localSymbol.getLocation());
            if (exact != null) {
                return exact;
            }
            if (localSymbol.getKind() != SymbolKind.IMPORT
                    && localSymbol.getKind() != SymbolKind.BUILTIN_FUNCTION
                    && localSymbol.getKind() != SymbolKind.BUILTIN_CONSTANT) {
                return null;
            }
        }

        ProjectIndex.SymbolEntry imported = findImportedCandidate(snapshot, reference.name);
        if (imported != null) {
            return imported;
        }

        List<ProjectIndex.SymbolEntry> candidates = topLevelCandidates(reference.name);
        if (candidates.isEmpty()) {
            return null;
        }

        List<ProjectIndex.SymbolEntry> samePackage = filterByPackage(candidates, snapshot.packageName);
        if (samePackage.size() == 1) {
            return samePackage.get(0);
        }

        List<ProjectIndex.SymbolEntry> sameDirectory = filterByDirectory(candidates, snapshot.uri);
        if (sameDirectory.size() == 1) {
            return sameDirectory.get(0);
        }

        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private Symbol resolveLocalSymbol(FileSnapshot snapshot, RawReference reference) {
        if (snapshot.cached == null || snapshot.cached.analysisResult == null) {
            return null;
        }
        return snapshot.cached.analysisResult.getSymbolTable().resolveAny(
                reference.name, reference.line + 1, reference.character + 1);
    }

    private ProjectIndex.SymbolEntry findImportedCandidate(FileSnapshot snapshot,
                                                           String name) {
        List<ProjectIndex.SymbolEntry> candidates = topLevelCandidates(name);
        List<ProjectIndex.SymbolEntry> wildcardMatches = new ArrayList<ProjectIndex.SymbolEntry>();

        for (ImportSpec importSpec : snapshot.imports) {
            if (importSpec.alias != null && name.equals(importSpec.alias)) {
                ProjectIndex.SymbolEntry aliasTarget = findByQualifiedName(importSpec.qualifiedName);
                if (aliasTarget != null) {
                    return aliasTarget;
                }
            }

            if (!importSpec.wildcard && name.equals(importSpec.simpleName)) {
                ProjectIndex.SymbolEntry match = findByQualifiedName(importSpec.qualifiedName);
                if (match != null) {
                    return match;
                }
            }

            if (importSpec.wildcard) {
                for (ProjectIndex.SymbolEntry candidate : candidates) {
                    if (Objects.equals(candidate.packageName, importSpec.qualifiedName)) {
                        wildcardMatches.add(candidate);
                    }
                }
            }
        }

        return wildcardMatches.size() == 1 ? wildcardMatches.get(0) : null;
    }

    private List<ProjectIndex.SymbolEntry> topLevelCandidates(String name) {
        List<ProjectIndex.SymbolEntry> result = new ArrayList<ProjectIndex.SymbolEntry>();
        for (ProjectIndex.SymbolEntry entry : projectIndex.findByName(name)) {
            if (entry.containerName == null) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<ProjectIndex.SymbolEntry> memberCandidates(String name, String containerQualifiedName) {
        List<ProjectIndex.SymbolEntry> result = new ArrayList<ProjectIndex.SymbolEntry>();
        for (ProjectIndex.SymbolEntry entry : projectIndex.findByName(name)) {
            if (containerQualifiedName.equals(entry.containerQualifiedName)) {
                result.add(entry);
            }
        }
        return result;
    }

    private ProjectIndex.SymbolEntry findExactEntry(String uri, String name, SymbolKind kind, SourceLocation location) {
        if (location == null) {
            return null;
        }

        int line = location.getLine() - 1;
        int character = location.getColumn() - 1;
        for (ProjectIndex.SymbolEntry entry : projectIndex.findByName(name)) {
            if (Objects.equals(entry.uri, uri)
                    && entry.kind == kind
                    && entry.line == line
                    && entry.character == character) {
                return entry;
            }
        }
        return null;
    }

    private ProjectIndex.SymbolEntry findByQualifiedName(String qualifiedName) {
        String simpleName = simpleNameOf(qualifiedName);
        List<ProjectIndex.SymbolEntry> candidates = topLevelCandidates(simpleName);
        return projectIndex.findByQualifiedName(qualifiedName);
    }

    private String simpleNameOf(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('.');
        return idx >= 0 ? qualifiedName.substring(idx + 1) : qualifiedName;
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

    private boolean usesAlias(FileSnapshot snapshot, RawReference reference) {
        for (ImportSpec importSpec : snapshot.imports) {
            if (importSpec.alias != null && importSpec.alias.equals(reference.name)) {
                return true;
            }
        }
        return false;
    }

    private List<ProjectIndex.SymbolEntry> filterByPackage(List<ProjectIndex.SymbolEntry> candidates, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProjectIndex.SymbolEntry> result = new ArrayList<ProjectIndex.SymbolEntry>();
        for (ProjectIndex.SymbolEntry candidate : candidates) {
            if (Objects.equals(packageName, candidate.packageName)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<ProjectIndex.SymbolEntry> filterByDirectory(List<ProjectIndex.SymbolEntry> candidates, String uri) {
        java.nio.file.Path currentPath = LspUriUtils.toPath(uri);
        if (currentPath == null || currentPath.getParent() == null) {
            return Collections.emptyList();
        }

        List<ProjectIndex.SymbolEntry> result = new ArrayList<ProjectIndex.SymbolEntry>();
        for (ProjectIndex.SymbolEntry candidate : candidates) {
            java.nio.file.Path candidatePath = LspUriUtils.toPath(candidate.uri);
            if (candidatePath != null && candidatePath.getParent() != null
                    && currentPath.getParent().equals(candidatePath.getParent())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private String extractPackageName(Program program) {
        if (program.getPackageDecl() == null || program.getPackageDecl().getName() == null) {
            return "";
        }
        return program.getPackageDecl().getName().getFullName();
    }

    private List<ImportSpec> extractImports(Program program) {
        List<ImportSpec> imports = new ArrayList<ImportSpec>();
        for (ImportDecl importDecl : program.getImports()) {
            if (importDecl == null || importDecl.isJava() || importDecl.isStatic() || importDecl.getName() == null) {
                continue;
            }

            String qualifiedName = importDecl.getName().getFullName();
            imports.add(new ImportSpec(
                    qualifiedName,
                    importDecl.getName().getSimpleName(),
                    importDecl.isWildcard(),
                    importDecl.getAlias(),
                    importDecl.getLocation()));
        }
        return imports;
    }

    private FileSnapshot buildSnapshot(String uri, String content, DocumentManager.CachedAnalysis cached) {
        Program program = cached.parseResult.getProgram();
        String packageName = extractPackageName(program);
        List<ImportSpec> imports = extractImports(program);
        List<RawReference> references = new ReferenceCollector(cached, content, imports, packageName).collect(program);
        return new FileSnapshot(uri, content, packageName, cached, imports, references);
    }

    private final class ReferenceCollector implements AstVisitor<Void, Void> {
        private final DocumentManager.CachedAnalysis cached;
        private final String content;
        private final List<ImportSpec> imports;
        private final String packageName;
        private final List<RawReference> references = new ArrayList<RawReference>();

        ReferenceCollector(DocumentManager.CachedAnalysis cached, String content, List<ImportSpec> imports, String packageName) {
            this.cached = cached;
            this.content = content;
            this.imports = imports;
            this.packageName = packageName;
        }

        List<RawReference> collect(Program program) {
            for (ImportSpec importSpec : imports) {
                if (!importSpec.wildcard) {
                    addReference(importSpec.simpleName, importSpec.location, importSpec.qualifiedName, true);
                    if (importSpec.alias != null) {
                        addReference(importSpec.alias, importSpec.location, importSpec.qualifiedName, false);
                    }
                }
            }
            visit(program);
            return references;
        }

        private void visit(AstNode node) {
            if (node != null) {
                node.accept(this, null);
            }
        }

        private void visitType(TypeRef typeRef) {
            if (typeRef != null) {
                typeRef.accept(this, null);
            }
        }

        private <T> List<T> listOrEmpty(List<T> items) {
            return items != null ? items : Collections.<T>emptyList();
        }

        private void addReference(String name, SourceLocation location, String explicitQualifiedTarget, boolean renameAllowed) {
            if (name == null || name.isEmpty() || location == null) {
                return;
            }
            references.add(new RawReference(
                    name,
                    Math.max(0, location.getLine() - 1),
                    Math.max(0, location.getColumn() - 1),
                    explicitQualifiedTarget,
                    null,
                    renameAllowed));
        }

        private void addMemberReference(String name, SourceLocation location, String containerName) {
            if (name == null || name.isEmpty() || location == null || containerName == null || containerName.isEmpty()) {
                return;
            }
            references.add(new RawReference(
                    name,
                    Math.max(0, location.getLine() - 1),
                    Math.max(0, location.getColumn() - 1),
                    null,
                    containerName,
                    true));
        }

        private String resolveReceiverTypeName(Expression expression) {
            if (expression == null || cached == null || cached.analysisResult == null) {
                return null;
            }

            String typeName = cached.analysisResult.getExprTypeName(expression);
            if ((typeName == null || typeName.isEmpty() || "Any".equals(LspTextUtils.baseType(typeName)))
                    && expression instanceof Identifier) {
                Identifier identifier = (Identifier) expression;
                SourceLocation location = identifier.getLocation();
                if (location != null) {
                    Symbol symbol = cached.analysisResult.getSymbolTable().resolveAny(
                            identifier.getName(),
                            location.getLine(),
                            location.getColumn());
                    if (symbol != null) {
                        typeName = symbol.getTypeName();
                    }
                }
            }

            if (typeName == null || typeName.isEmpty()) {
                if (expression instanceof Identifier) {
                    typeName = inferVariableTypeByText(((Identifier) expression).getName());
                }
            }

            if (typeName == null || typeName.isEmpty()) {
                return null;
            }

            String baseType = LspTextUtils.baseType(typeName);
            if (baseType == null || "Any".equals(baseType) || baseType.startsWith("java:")) {
                return null;
            }

            return resolveTypeQualifiedName(baseType);
        }

        private String resolveTypeQualifiedName(String typeName) {
            if (typeName == null || typeName.isEmpty()) {
                return null;
            }

            if (typeName.contains(".")) {
                ProjectIndex.SymbolEntry qualified = projectIndex.findByQualifiedName(typeName);
                if (qualified != null) {
                    return qualified.qualifiedName;
                }
            }

            for (ImportSpec importSpec : imports) {
                if (typeName.equals(importSpec.alias) || typeName.equals(importSpec.simpleName)) {
                    return importSpec.qualifiedName;
                }
            }

            List<ProjectIndex.SymbolEntry> candidates = topLevelCandidates(typeName);
            if (candidates.isEmpty()) {
                return null;
            }

            for (ProjectIndex.SymbolEntry candidate : candidates) {
                if (Objects.equals(packageName, candidate.packageName)) {
                    return candidate.qualifiedName;
                }
            }

            for (ImportSpec importSpec : imports) {
                if (importSpec.wildcard) {
                    for (ProjectIndex.SymbolEntry candidate : candidates) {
                        if (Objects.equals(importSpec.qualifiedName, candidate.packageName)) {
                            return candidate.qualifiedName;
                        }
                    }
                }
            }

            return candidates.size() == 1 ? candidates.get(0).qualifiedName : null;
        }

        private String inferVariableTypeByText(String name) {
            if (name == null || name.isEmpty()) {
                return null;
            }

            String[] lines = content.split("\n", -1);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!(trimmed.startsWith("val ") || trimmed.startsWith("var "))) {
                    continue;
                }

                String pattern = "(?:val|var)\\s+" + java.util.regex.Pattern.quote(name)
                        + "\\s*(?::\\s*([A-Z][a-zA-Z0-9_?.<>]*))?\\s*=\\s*([A-Z][a-zA-Z0-9_.]*)\\s*\\(";
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(trimmed);
                if (matcher.find()) {
                    if (matcher.group(1) != null && !matcher.group(1).isEmpty()) {
                        return matcher.group(1);
                    }
                    return matcher.group(2);
                }
            }
            return null;
        }

        @Override
        public Void visitProgram(Program node, Void ctx) {
            visit(node.getPackageDecl());
            for (ImportDecl importDecl : node.getImports()) visit(importDecl);
            for (Declaration declaration : node.getDeclarations()) visit(declaration);
            return null;
        }

        @Override
        public Void visitPackageDecl(PackageDecl node, Void ctx) {
            return null;
        }

        @Override
        public Void visitImportDecl(ImportDecl node, Void ctx) {
            return null;
        }

        @Override
        public Void visitClassDecl(ClassDecl node, Void ctx) {
            for (TypeParameter typeParameter : listOrEmpty(node.getTypeParams())) visitTypeParameterNode(typeParameter);
            for (Parameter parameter : listOrEmpty(node.getPrimaryConstructorParams())) visit(parameter);
            for (TypeRef typeRef : listOrEmpty(node.getSuperTypes())) visitType(typeRef);
            for (Expression expression : listOrEmpty(node.getSuperConstructorArgs())) visit(expression);
            for (Declaration declaration : listOrEmpty(node.getMembers())) visit(declaration);
            return null;
        }

        @Override
        public Void visitInterfaceDecl(InterfaceDecl node, Void ctx) {
            for (TypeParameter typeParameter : listOrEmpty(node.getTypeParams())) visitTypeParameterNode(typeParameter);
            for (TypeRef typeRef : listOrEmpty(node.getSuperTypes())) visitType(typeRef);
            for (Declaration declaration : listOrEmpty(node.getMembers())) visit(declaration);
            return null;
        }

        @Override
        public Void visitObjectDecl(ObjectDecl node, Void ctx) {
            for (TypeRef typeRef : listOrEmpty(node.getSuperTypes())) visitType(typeRef);
            for (Declaration declaration : listOrEmpty(node.getMembers())) visit(declaration);
            return null;
        }

        @Override
        public Void visitEnumDecl(EnumDecl node, Void ctx) {
            for (Parameter parameter : listOrEmpty(node.getPrimaryConstructorParams())) visit(parameter);
            for (TypeRef typeRef : listOrEmpty(node.getSuperTypes())) visitType(typeRef);
            for (EnumDecl.EnumEntry entry : listOrEmpty(node.getEntries())) {
                for (Expression expression : listOrEmpty(entry.getArgs())) visit(expression);
                for (Declaration declaration : listOrEmpty(entry.getMembers())) visit(declaration);
            }
            for (Declaration declaration : listOrEmpty(node.getMembers())) visit(declaration);
            return null;
        }

        @Override
        public Void visitFunDecl(FunDecl node, Void ctx) {
            for (TypeParameter typeParameter : listOrEmpty(node.getTypeParams())) visitTypeParameterNode(typeParameter);
            visitType(node.getReceiverType());
            for (Parameter parameter : listOrEmpty(node.getParams())) visit(parameter);
            visitType(node.getReturnType());
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitPropertyDecl(PropertyDecl node, Void ctx) {
            for (TypeParameter typeParameter : listOrEmpty(node.getTypeParams())) visitTypeParameterNode(typeParameter);
            visitType(node.getReceiverType());
            visitType(node.getType());
            visit(node.getInitializer());
            visit(node.getGetter());
            visit(node.getSetter());
            return null;
        }

        @Override
        public Void visitTypeAliasDecl(TypeAliasDecl node, Void ctx) {
            for (TypeParameter typeParameter : listOrEmpty(node.getTypeParams())) visitTypeParameterNode(typeParameter);
            visitType(node.getAliasedType());
            return null;
        }

        @Override
        public Void visitParameter(Parameter node, Void ctx) {
            visitType(node.getType());
            visit(node.getDefaultValue());
            return null;
        }

        @Override
        public Void visitConstructorDecl(ConstructorDecl node, Void ctx) {
            for (Parameter parameter : listOrEmpty(node.getParams())) visit(parameter);
            for (Expression expression : listOrEmpty(node.getDelegationArgs())) visit(expression);
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitInitBlockDecl(InitBlockDecl node, Void ctx) {
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitBlock(Block node, Void ctx) {
            for (Statement statement : node.getStatements()) visit(statement);
            return null;
        }

        @Override
        public Void visitExpressionStmt(ExpressionStmt node, Void ctx) {
            visit(node.getExpression());
            return null;
        }

        @Override
        public Void visitIfStmt(IfStmt node, Void ctx) {
            visit(node.getCondition());
            visit(node.getThenBranch());
            visit(node.getElseBranch());
            return null;
        }

        @Override
        public Void visitWhenStmt(WhenStmt node, Void ctx) {
            visit(node.getSubject());
            for (WhenBranch branch : node.getBranches()) visit(branch);
            visit(node.getElseBranch());
            return null;
        }

        @Override
        public Void visitForStmt(ForStmt node, Void ctx) {
            visit(node.getIterable());
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitWhileStmt(WhileStmt node, Void ctx) {
            visit(node.getCondition());
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitDoWhileStmt(DoWhileStmt node, Void ctx) {
            visit(node.getBody());
            visit(node.getCondition());
            return null;
        }

        @Override
        public Void visitTryStmt(TryStmt node, Void ctx) {
            visit(node.getTryBlock());
            for (CatchClause catchClause : node.getCatchClauses()) visit(catchClause);
            visit(node.getFinallyBlock());
            return null;
        }

        @Override
        public Void visitReturnStmt(ReturnStmt node, Void ctx) {
            visit(node.getValue());
            return null;
        }

        @Override
        public Void visitThrowStmt(ThrowStmt node, Void ctx) {
            visit(node.getException());
            return null;
        }

        @Override
        public Void visitGuardStmt(GuardStmt node, Void ctx) {
            visit(node.getExpression());
            visit(node.getElseBody());
            return null;
        }

        @Override
        public Void visitUseStmt(UseStmt node, Void ctx) {
            for (UseStmt.UseBinding binding : node.getBindings()) visit(binding.getInitializer());
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitDeclarationStmt(DeclarationStmt node, Void ctx) {
            visit(node.getDeclaration());
            return null;
        }

        @Override
        public Void visitBinaryExpr(BinaryExpr node, Void ctx) {
            visit(node.getLeft());
            visit(node.getRight());
            return null;
        }

        @Override
        public Void visitUnaryExpr(UnaryExpr node, Void ctx) {
            visit(node.getOperand());
            return null;
        }

        @Override
        public Void visitCallExpr(CallExpr node, Void ctx) {
            visit(node.getCallee());
            for (TypeRef typeRef : listOrEmpty(node.getTypeArgs())) visitType(typeRef);
            for (CallExpr.Argument argument : listOrEmpty(node.getArgs())) visit(argument.getValue());
            visit(node.getTrailingLambda());
            return null;
        }

        @Override
        public Void visitIndexExpr(IndexExpr node, Void ctx) {
            visit(node.getTarget());
            visit(node.getIndex());
            return null;
        }

        @Override
        public Void visitMemberExpr(MemberExpr node, Void ctx) {
            visit(node.getTarget());
            addMemberReference(
                    node.getMember(),
                    node.getMemberLocation() != null ? node.getMemberLocation() : node.getLocation(),
                    resolveReceiverTypeName(node.getTarget()));
            return null;
        }

        @Override
        public Void visitAssignExpr(AssignExpr node, Void ctx) {
            visit(node.getTarget());
            visit(node.getValue());
            return null;
        }

        @Override
        public Void visitLambdaExpr(LambdaExpr node, Void ctx) {
            for (LambdaExpr.LambdaParam param : listOrEmpty(node.getParams())) {
                if (param.hasType()) visitType(param.getType());
            }
            visit(node.getBody());
            return null;
        }

        @Override
        public Void visitIfExpr(IfExpr node, Void ctx) {
            visit(node.getCondition());
            visit(node.getThenExpr());
            visit(node.getElseExpr());
            return null;
        }

        @Override
        public Void visitWhenExpr(WhenExpr node, Void ctx) {
            visit(node.getSubject());
            for (WhenBranch branch : node.getBranches()) visit(branch);
            visit(node.getElseExpr());
            return null;
        }

        @Override
        public Void visitTryExpr(TryExpr node, Void ctx) {
            visit(node.getTryBlock());
            for (CatchClause catchClause : node.getCatchClauses()) visit(catchClause);
            visit(node.getFinallyBlock());
            return null;
        }

        @Override
        public Void visitAwaitExpr(AwaitExpr node, Void ctx) {
            visit(node.getOperand());
            return null;
        }

        @Override
        public Void visitIdentifier(Identifier node, Void ctx) {
            addReference(node.getName(), node.getLocation(), null, true);
            return null;
        }

        @Override
        public Void visitTypeCheckExpr(TypeCheckExpr node, Void ctx) {
            visit(node.getOperand());
            visitType(node.getTargetType());
            return null;
        }

        @Override
        public Void visitTypeCastExpr(TypeCastExpr node, Void ctx) {
            visit(node.getOperand());
            visitType(node.getTargetType());
            return null;
        }

        @Override
        public Void visitRangeExpr(RangeExpr node, Void ctx) {
            visit(node.getStart());
            visit(node.getEnd());
            visit(node.getStep());
            return null;
        }

        @Override
        public Void visitSliceExpr(SliceExpr node, Void ctx) {
            visit(node.getTarget());
            visit(node.getStart());
            visit(node.getEnd());
            return null;
        }

        @Override
        public Void visitSpreadExpr(SpreadExpr node, Void ctx) {
            visit(node.getOperand());
            return null;
        }

        @Override
        public Void visitPipelineExpr(PipelineExpr node, Void ctx) {
            visit(node.getLeft());
            visit(node.getRight());
            return null;
        }

        @Override
        public Void visitMethodRefExpr(MethodRefExpr node, Void ctx) {
            visit(node.getTarget());
            visit(node.getReceiver());
            visitType(node.getTypeTarget());
            addReference(node.getMethodName(), node.getLocation(), null, true);
            return null;
        }

        @Override
        public Void visitObjectLiteralExpr(ObjectLiteralExpr node, Void ctx) {
            for (TypeRef typeRef : listOrEmpty(node.getSuperTypes())) visitType(typeRef);
            for (Expression expression : listOrEmpty(node.getSuperConstructorArgs())) visit(expression);
            for (Declaration declaration : listOrEmpty(node.getMembers())) visit(declaration);
            return null;
        }

        @Override
        public Void visitCollectionLiteral(CollectionLiteral node, Void ctx) {
            for (Expression element : listOrEmpty(node.getElements())) visit(element);
            for (CollectionLiteral.MapEntry entry : listOrEmpty(node.getMapEntries())) {
                visit(entry.getKey());
                visit(entry.getValue());
            }
            return null;
        }

        @Override
        public Void visitStringInterpolation(StringInterpolation node, Void ctx) {
            for (StringInterpolation.StringPart part : node.getParts()) {
                if (part instanceof StringInterpolation.ExprPart) {
                    visit(((StringInterpolation.ExprPart) part).getExpression());
                }
            }
            return null;
        }

        @Override
        public Void visitElvisExpr(ElvisExpr node, Void ctx) {
            visit(node.getLeft());
            visit(node.getRight());
            return null;
        }

        @Override
        public Void visitSafeCallExpr(SafeCallExpr node, Void ctx) {
            visit(node.getTarget());
            addMemberReference(node.getMember(), node.getLocation(), resolveReceiverTypeName(node.getTarget()));
            for (CallExpr.Argument argument : listOrEmpty(node.getArgs())) visit(argument.getValue());
            return null;
        }

        @Override
        public Void visitSafeIndexExpr(SafeIndexExpr node, Void ctx) {
            visit(node.getTarget());
            visit(node.getIndex());
            return null;
        }

        @Override
        public Void visitNotNullExpr(NotNullExpr node, Void ctx) {
            visit(node.getOperand());
            return null;
        }

        @Override
        public Void visitErrorPropagationExpr(ErrorPropagationExpr node, Void ctx) {
            visit(node.getOperand());
            return null;
        }

        @Override
        public Void visitScopeShorthandExpr(ScopeShorthandExpr node, Void ctx) {
            visit(node.getTarget());
            visit(node.getBlock());
            return null;
        }

        @Override
        public Void visitJumpExpr(JumpExpr node, Void ctx) {
            visit(node.getStatement());
            return null;
        }

        @Override
        public Void visitConditionalExpr(ConditionalExpr node, Void ctx) {
            visit(node.getCondition());
            visit(node.getThenExpr());
            visit(node.getElseExpr());
            return null;
        }

        @Override
        public Void visitBlockExpr(BlockExpr node, Void ctx) {
            for (Statement statement : node.getStatements()) visit(statement);
            visit(node.getResult());
            return null;
        }

        @Override
        public Void visitSimpleType(SimpleType node, Void ctx) {
            if (node.getName() != null) {
                addReference(node.getName().getSimpleName(), node.getName().getLocation(), null, true);
            }
            return null;
        }

        @Override
        public Void visitNullableType(NullableType node, Void ctx) {
            visitType(node.getInnerType());
            return null;
        }

        @Override
        public Void visitFunctionType(FunctionType node, Void ctx) {
            visitType(node.getReceiverType());
            for (TypeRef typeRef : node.getParamTypes()) visitType(typeRef);
            visitType(node.getReturnType());
            return null;
        }

        @Override
        public Void visitGenericType(GenericType node, Void ctx) {
            if (node.getName() != null) {
                addReference(node.getName().getSimpleName(), node.getName().getLocation(), null, true);
            }
            for (TypeArgument typeArgument : node.getTypeArgs()) visit(typeArgument);
            return null;
        }

        @Override
        public Void visitQualifiedName(QualifiedName node, Void ctx) {
            return null;
        }

        private void visitTypeParameterNode(TypeParameter node) {
            if (node != null) {
                visitType(node.getUpperBound());
            }
        }
    }
}
