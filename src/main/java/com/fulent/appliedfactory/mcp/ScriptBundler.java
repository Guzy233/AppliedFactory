package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.minecraft.client.Minecraft;

import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.StringLiteral;

/**
 * Client-side source bundler for MCP scripts and controller-editor saves:
 * resolves {@code include("file")} calls by textually inlining the target file's
 * content, recursively, so long recipe/data files can live separately in the
 * appliedscripts workspace while the controller still receives one source. After
 * inlining, {@code require_recipes(filter)} calls are expanded by
 * {@link RecipeMacroExpander} against the workspace's
 * {@code processing_recipes.json}, so baked recipe data is selected by the
 * filter at bundle time instead of in the script.
 *
 * <p>{@code include("file")} has one extension-independent meaning, matching C++
 * {@code #include}: the call itself is replaced with the target's raw text. The caller is
 * responsible for putting the include where that text is valid JavaScript or data.
 */
public final class ScriptBundler {
    private static final int MAX_DEPTH = 16;

    private ScriptBundler() {
    }

    /** Root directory of client-authored scripts and generated recipe data. */
    public static Path workspaceDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("appliedscripts");
    }

    /**
     * Resolves a file name against the appliedscripts workspace. Absolute paths
     * win, then the including file's directory (when {@code baseDir} is set), the
     * game directory's appliedscripts/ and root, and finally the process working
     * directory's appliedscripts/ and root.
     */
    @Nullable
    public static Path resolveFile(String file, @Nullable Path baseDir) {
        var given = Path.of(file);
        var candidates = new ArrayList<Path>();
        if (given.isAbsolute()) {
            candidates.add(given);
        }
        if (baseDir != null) {
            candidates.add(baseDir.resolve(file));
        }
        var gameDir = Minecraft.getInstance().gameDirectory.toPath();
        candidates.add(workspaceDir().resolve(file));
        candidates.add(gameDir.resolve(file));
        var cwd = Path.of("").toAbsolutePath();
        candidates.add(cwd.resolve("appliedscripts").resolve(file));
        candidates.add(cwd.resolve(file));
        for (var candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Bundles include() directives and expands require_recipes() macros. */
    public static String bundle(String source, @Nullable Path baseDir) throws McpToolException {
        return RecipeMacroExpander.expand(bundle(source, baseDir, new HashSet<>(), 0), baseDir);
    }

    /**
     * Bundles inline source as a virtual file located in the appliedscripts
     * workspace root. Used by MCP inline scripts and the controller editor.
     */
    public static String bundleFromWorkspaceRoot(String source) throws McpToolException {
        return bundle(source, workspaceDir());
    }

    private static String bundle(
            String source, @Nullable Path baseDir, Set<String> chain, int depth)
            throws McpToolException {
        if (depth > MAX_DEPTH) {
            throw new McpToolException(-32602, "include() nesting exceeds " + MAX_DEPTH);
        }
        AstRoot ast;
        try {
            ast = new Parser().parse(source, "<factory script>", 1);
        } catch (RuntimeException exception) {
            throw new McpToolException(-32602, "script parse failed: " + exception.getMessage());
        }
        var includes = new ArrayList<Include>();
        ast.visitAll(node -> {
            if (node instanceof FunctionCall call
                    && call.getTarget() instanceof Name name
                    && "include".equals(name.getIdentifier())
                    && call.getArguments().size() == 1
                    && call.getArguments().get(0) instanceof StringLiteral literal) {
                var target = literal.getValue();
                includes.add(new Include(
                        call.getAbsolutePosition(), call.getLength(), target));
            }
            return true;
        });
        if (includes.isEmpty()) {
            return source;
        }
        includes.sort(Comparator.comparingInt(Include::offset));
        var result = new StringBuilder();
        int cursor = 0;
        for (var include : includes) {
            if (include.offset() < cursor) {
                throw new McpToolException(-32602, "overlapping include() statements");
            }
            result.append(source, cursor, include.offset());
            result.append(load(include.target(), baseDir, chain, depth));
            cursor = include.offset() + include.length();
        }
        result.append(source, cursor, source.length());
        return result.toString();
    }

    private static String load(
            String name, @Nullable Path baseDir, Set<String> chain, int depth)
            throws McpToolException {
        var path = resolveFile(name, baseDir);
        if (path == null) {
            throw new McpToolException(-32602, "include file not found: " + name
                    + (baseDir == null ? "" : " (base " + baseDir + ")"));
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new McpToolException(-32602,
                    "failed to read include " + name + ": " + exception.getMessage());
        }
        var key = path.toAbsolutePath().normalize().toString();
        if (!chain.add(key)) {
            throw new McpToolException(-32602, "include cycle: " + name);
        }
        try {
            // A JSON object is not a standalone JavaScript program, but textual inclusion
            // still makes it valid when the call occurs in an expression. JSON cannot carry
            // another include directive, so it needs no recursive pass.
            if (isJsonDocument(content)) {
                return content;
            }
            return bundle(content, path.getParent(), chain, depth + 1);
        } finally {
            chain.remove(key);
        }
    }

    private static boolean isJsonDocument(String content) {
        try {
            JsonParser.parseString(content);
            return true;
        } catch (JsonSyntaxException ignored) {
            return false;
        }
    }

    private record Include(int offset, int length, String target) {
    }
}
