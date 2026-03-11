package com.novalang.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

final class WorkspaceFileIndex {
    private static final Logger LOG = Logger.getLogger(WorkspaceFileIndex.class.getName());

    private static final Set<String> IGNORED_DIR_NAMES = new HashSet<String>(Arrays.asList(
            ".git", ".gradle", ".idea", ".vscode", "build", "out", "node_modules"
    ));

    private final ProjectIndex projectIndex;
    private final WorkspaceReferenceIndex referenceIndex;
    private final DocumentManager documentManager;
    private final Set<String> indexedUris = ConcurrentHashMap.newKeySet();

    private volatile List<Path> workspaceRoots = new ArrayList<Path>();

    WorkspaceFileIndex(ProjectIndex projectIndex, WorkspaceReferenceIndex referenceIndex, DocumentManager documentManager) {
        this.projectIndex = projectIndex;
        this.referenceIndex = referenceIndex;
        this.documentManager = documentManager;
    }

    void setWorkspaceRoots(List<String> rootUris) {
        List<Path> roots = new ArrayList<Path>();
        if (rootUris != null) {
            for (String rootUri : rootUris) {
                Path path = LspUriUtils.toPath(rootUri);
                if (path != null && Files.isDirectory(path)) {
                    roots.add(path);
                }
            }
        }
        workspaceRoots = roots;
    }

    void rebuild() {
        List<Path> roots = workspaceRoots;
        Set<String> seen = new HashSet<String>();

        for (Path root : roots) {
            scanRoot(root, seen);
        }

        List<String> stale = new ArrayList<String>();
        for (String uri : indexedUris) {
            if (!seen.contains(uri) && !documentManager.isOpen(uri)) {
                stale.add(uri);
            }
        }

        for (String uri : stale) {
            removeFile(uri);
        }
    }

    void reindexFileFromDisk(String uri) {
        if (uri == null || uri.isEmpty()) {
            return;
        }

        if (documentManager.isOpen(uri)) {
            return;
        }

        Path path = LspUriUtils.toPath(uri);
        if (path == null || !Files.exists(path) || !isWorkspacePath(path)) {
            removeFile(uri);
            return;
        }

        indexPath(path, uri);
    }

    void handleWatchedFiles(JsonArray changes) {
        if (changes == null) {
            return;
        }

        for (int i = 0; i < changes.size(); i++) {
            JsonObject change = changes.get(i).getAsJsonObject();
            if (change == null || !change.has("uri")) {
                continue;
            }

            String uri = change.get("uri").getAsString();
            int type = change.has("type") ? change.get("type").getAsInt() : 0;

            if (type == 3) {
                removeFile(uri);
            } else {
                reindexFileFromDisk(uri);
            }
        }
    }

    private void scanRoot(Path root, Set<String> seen) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(root) && IGNORED_DIR_NAMES.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || !file.getFileName().toString().endsWith(".nova")) {
                        return FileVisitResult.CONTINUE;
                    }

                    String uri = LspUriUtils.toUri(file);
                    seen.add(uri);

                    if (!documentManager.isOpen(uri)) {
                        indexPath(file, uri);
                    } else {
                        indexedUris.add(uri);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "扫描工作区失败: " + root, e);
        }
    }

    private boolean isWorkspacePath(Path path) {
        List<Path> roots = workspaceRoots;
        for (Path root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private void indexPath(Path path, String uri) {
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            DocumentManager.CachedAnalysis cached = NovaAnalysisSupport.analyze(uri, content);
            if (cached == null || cached.analysisResult == null) {
                removeFile(uri);
                return;
            }

            List<com.novalang.compiler.analysis.Symbol> topLevel = new ArrayList<com.novalang.compiler.analysis.Symbol>(
                    cached.analysisResult.getSymbolTable().getGlobalScope().getSymbols().values());
            projectIndex.updateFile(uri, NovaAnalysisSupport.packageName(cached), topLevel);
            referenceIndex.updateFile(uri, content, cached);
            indexedUris.add(uri);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "索引工作区文件失败: " + path, e);
            removeFile(uri);
        }
    }

    private void removeFile(String uri) {
        projectIndex.removeFile(uri);
        referenceIndex.removeFile(uri);
        indexedUris.remove(uri);
    }
}
