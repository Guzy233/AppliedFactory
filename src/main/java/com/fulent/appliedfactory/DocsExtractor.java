package com.fulent.appliedfactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;

/** Extracts the bundled, localized script workspace into the game directory. */
public final class DocsExtractor {
    private static final String WORKSPACE_ROOT = "/assets/appliedfactory/appliedscripts/";
    private static final String GUIDE_ROOT = "/assets/appliedfactory/ae2guide/";
    private static final List<String> WORKSPACE_FILES = List.of(
            "agents.md",
            "applied_factory.d.ts",
            "demo.ts",
            "tsconfig.json",
            ".codex/config.toml");

    private DocsExtractor() {
    }

    /** Extracts base files first, then applies the selected-language overlay. */
    public static void extract() {
        var target = Minecraft.getInstance().gameDirectory.toPath().resolve("appliedscripts");
        try {
            Files.createDirectories(target);
            copyWorkspaceLayer(WORKSPACE_ROOT, target, true);
            var language = normalizeLanguage(
                    Minecraft.getInstance().getLanguageManager().getSelected());
            if (!"en_us".equals(language)) {
                copyWorkspaceLayer(WORKSPACE_ROOT + "_" + language + "/", target, false);
            }
            copyApiReference(language, target.resolve("SCRIPT_API.md"));
            Files.deleteIfExists(target.resolve("mcp.json"));
            AppliedFactory.LOGGER.info(
                    "Extracted Applied Factory {} script workspace to {}", language, target);
        } catch (IOException exception) {
            AppliedFactory.LOGGER.warn(
                    "Failed to extract Applied Factory script workspace to {}", target, exception);
        }
    }

    private static void copyWorkspaceLayer(
            String resourceRoot, Path target, boolean required) throws IOException {
        for (var name : WORKSPACE_FILES) {
            try (InputStream in = DocsExtractor.class.getResourceAsStream(resourceRoot + name)) {
                if (in == null) {
                    if (required) {
                        AppliedFactory.LOGGER.warn(
                                "Missing bundled workspace resource {}", resourceRoot + name);
                    }
                    continue;
                }
                var output = target.resolve(name);
                Files.createDirectories(output.getParent());
                Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyApiReference(String language, Path output) throws IOException {
        var localized = "en_us".equals(language)
                ? null
                : GUIDE_ROOT + "_" + language + "/applied_factory/script_api.md";
        InputStream in = localized == null
                ? null
                : DocsExtractor.class.getResourceAsStream(localized);
        if (in == null) {
            in = DocsExtractor.class.getResourceAsStream(
                    GUIDE_ROOT + "applied_factory/script_api.md");
        }
        if (in == null) {
            throw new IOException("Missing bundled script API reference");
        }
        try (InputStream selected = in) {
            var markdown = new String(selected.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(output, stripFrontmatter(markdown), StandardCharsets.UTF_8);
        }
    }

    static String stripFrontmatter(String markdown) {
        if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) {
            return markdown;
        }
        var normalized = markdown.replace("\r\n", "\n");
        var end = normalized.indexOf("\n---\n", 4);
        return end < 0 ? markdown : normalized.substring(end + 5);
    }

    static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "en_us";
        }
        return language.toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
