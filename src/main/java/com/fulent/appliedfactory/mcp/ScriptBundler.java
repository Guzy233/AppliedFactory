package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;

import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.StringLiteral;

/**
 * Client-side source bundler for MCP scripts: resolves {@code include("file")}
 * calls by inlining the target file's content, recursively, so long recipe/data
 * files can live separately in the appliedscripts workspace while the controller
 * still receives one source. After inlining, {@code require_recipes(filter)}
 * calls are expanded by {@link RecipeMacroExpander} against the workspace's
 * {@code processing_recipes.json}, so baked recipe data is selected by the
 * filter at bundle time instead of in the script.
 *
 * <p>Two forms:
 * <ul>
 * <li>{@code include("file.json")} — the call is replaced by the file's raw
 * content, which (JSON being a valid JS expression) can be assigned:
 * {@code const recipes = include("seed_recipes.json")}.</li>
 * <li>{@code include("file.js")} — must be a top-level statement; the whole
 * statement is replaced by the file content, so top-level declarations
 * (const/var/function) become visible to the rest of the program.</li>
 * </ul>
 */
public final class ScriptBundler {
    private static final int MAX_DEPTH = 16;

    private ScriptBundler() {
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
        candidates.add(gameDir.resolve("appliedscripts").resolve(file));
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
        var root = ast;
        ast.visitAll(node -> {
            if (node instanceof FunctionCall call
                    && call.getTarget() instanceof Name name
                    && "include".equals(name.getIdentifier())
                    && call.getArguments().size() == 1
                    && call.getArguments().get(0) instanceof StringLiteral literal) {
                var target = literal.getValue();
                if (target.endsWith(".json")) {
                    // Expression position: replace just the call so the JSON can
                    // be assigned (JSON is a valid JS expression).
                    includes.add(new Include(
                            call.getAbsolutePosition(), call.getLength(), target, true));
                } else if (node.getParent() instanceof ExpressionStatement statement
                        && statement.getParent() == root) {
                    // Top-level statement position: inline declarations.
                    includes.add(new Include(
                            statement.getAbsolutePosition(), statement.getLength(), target, false));
                } else {
                    includes.add(new Include(-1, -1, target, false));
                }
            }
            return true;
        });
        for (var include : includes) {
            if (include.offset() < 0) {
                throw new McpToolException(-32602, "include() of \"" + include.target()
                        + "\" must be a top-level statement; only .json includes can be used "
                        + "as expressions");
            }
        }
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
            result.append(load(include.target(), baseDir, chain, depth, include.json()));
            cursor = include.offset() + include.length();
        }
        result.append(source, cursor, source.length());
        return result.toString();
    }

    private static String load(
            String name, @Nullable Path baseDir, Set<String> chain, int depth, boolean json)
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
        if (json) {
            // JSON is data, not a script: inline it verbatim without recursing.
            return content;
        }
        var key = path.toAbsolutePath().normalize().toString();
        if (!chain.add(key)) {
            throw new McpToolException(-32602, "include cycle: " + name);
        }
        try {
            return bundle(content, path.getParent(), chain, depth + 1);
        } finally {
            chain.remove(key);
        }
    }

    private record Include(int offset, int length, String target, boolean json) {
    }
}
