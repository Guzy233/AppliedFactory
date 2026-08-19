package com.fulent.appliedfactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import net.minecraft.client.Minecraft;

/**
 * Copies the documentation bundled in the mod jar (assets/appliedfactory/docs)
 * into the game directory's {@code appliedscripts/} when the client starts, so
 * players and AI agents can read the API reference, the agent prompt and the
 * type declarations from the working directory without starting a server.
 *
 * <p>The file list must stay in sync with the {@code generateDocs} task in
 * build.gradle, which decides what is bundled into the jar.
 */
public final class DocsExtractor {
    private static final String RESOURCE_ROOT = "/assets/appliedfactory/docs/";
    private static final List<String> DOCS = List.of(
            "agents.md",
            "SCRIPT_API.md",
            "applied_factory.d.ts",
            "demo.ts",
            "tsconfig.json");

    private DocsExtractor() {
    }

    /**
     * Extracts the bundled docs into {@code <game dir>/appliedscripts/}.
     * Call from the client only (e.g. wrapped in {@code FMLClientSetupEvent}
     * {@code enqueueWork}); must not run on a dedicated server.
     */
    public static void extract() {
        var target = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("appliedscripts");
        try {
            Files.createDirectories(target);
        } catch (IOException exception) {
            AppliedFactory.LOGGER.warn("Failed to create docs directory {}", target, exception);
            return;
        }
        for (var name : DOCS) {
            try (InputStream in = DocsExtractor.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (in == null) {
                    AppliedFactory.LOGGER.warn(
                            "Missing bundled documentation resource {} in the mod jar",
                            RESOURCE_ROOT + name);
                    continue;
                }
                Files.copy(in, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                AppliedFactory.LOGGER.warn("Failed to extract documentation {}", name, exception);
            }
        }
        AppliedFactory.LOGGER.info("Extracted Applied Factory documentation to {}", target);
    }
}
