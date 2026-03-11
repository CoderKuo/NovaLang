package com.novalang.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

final class LspUriUtils {

    private LspUriUtils() {}

    static Path toPath(String uri) {
        if (uri == null || uri.isEmpty() || !uri.startsWith("file:")) {
            return null;
        }

        try {
            return Paths.get(URI.create(uri)).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    static String toUri(Path path) {
        if (path == null) {
            return null;
        }
        return path.toAbsolutePath().normalize().toUri().toString();
    }
}
