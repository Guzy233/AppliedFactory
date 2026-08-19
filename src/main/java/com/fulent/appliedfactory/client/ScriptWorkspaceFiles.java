package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.fulent.appliedfactory.mcp.ScriptBundler;

/** Safe client-side access to files below {@code appliedscripts/}. */
final class ScriptWorkspaceFiles {
    private ScriptWorkspaceFiles() {
    }

    static List<String> list() throws IOException {
        var root = root();
        Files.createDirectories(root);
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(ScriptWorkspaceFiles::portable)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    static String read(String relativePath) throws IOException {
        return Files.readString(resolve(relativePath), StandardCharsets.UTF_8);
    }

    static void write(String relativePath, String source) throws IOException {
        var file = resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    static boolean exists(String relativePath) {
        try {
            return Files.isRegularFile(resolve(relativePath));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String availableDownloadPath(String suggestedPath) {
        var safeSuggestion = safeSuggestion(suggestedPath);
        if (!exists(safeSuggestion)) {
            return safeSuggestion;
        }
        var dot = safeSuggestion.lastIndexOf('.');
        var stem = dot > 0 ? safeSuggestion.substring(0, dot) : safeSuggestion;
        var suffix = dot > 0 ? safeSuggestion.substring(dot) : ".js";
        for (int index = 1; ; index++) {
            var candidate = stem + ".downloaded" + (index == 1 ? "" : index) + suffix;
            if (!exists(candidate)) {
                return candidate;
            }
        }
    }

    static Path absolute(String relativePath) {
        return resolve(relativePath);
    }

    private static Path root() {
        return ScriptBundler.workspaceDir().toAbsolutePath().normalize();
    }

    private static Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Workspace path is empty");
        }
        var relative = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Workspace path must be relative");
        }
        var resolved = root().resolve(relative).normalize();
        if (!resolved.startsWith(root())) {
            throw new IllegalArgumentException("Workspace path escapes appliedscripts");
        }
        return resolved;
    }

    private static String safeSuggestion(String suggestedPath) {
        try {
            resolve(suggestedPath);
            return suggestedPath;
        } catch (IllegalArgumentException ignored) {
            return "downloaded_controller.js";
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
