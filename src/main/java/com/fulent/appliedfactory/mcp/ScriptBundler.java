package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.client.Minecraft;

/** Client-side TypeScript precompiler for controller and MCP scripts. */
public final class ScriptBundler {
    private static final String TYPESCRIPT_RESOURCE =
            "/assets/appliedfactory/compiler/typescript.js";
    private static final Object COMPILER_LOCK = new Object();
    private static Context compilerContext;

    private static final String COMPILER_HELPER = """
            globalThis.__afScan = function(source) {
              const sf = ts.createSourceFile("controller.ts", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
              if (sf.parseDiagnostics.length) {
                const d = sf.parseDiagnostics[0];
                return JSON.stringify({ error: ts.flattenDiagnosticMessageText(d.messageText, "\\n") });
              }
              const edits = [];
              let error = null;
              const fail = message => { if (error === null) error = message; };
              const literalStrings = (node, key) => {
                if (ts.isStringLiteral(node)) return [node.text];
                if (ts.isArrayLiteralExpression(node)) {
                  const values = [];
                  for (const item of node.elements) {
                    if (!ts.isStringLiteral(item)) {
                      fail(`require_recipes() filter "${key}" must contain only strings`);
                      return [];
                    }
                    values.push(item.text);
                  }
                  return values;
                }
                fail(`require_recipes() filter "${key}" must be a string or an array of strings`);
                return [];
              };
              for (const statement of sf.statements) {
                if (ts.isImportDeclaration(statement)) {
                  const clause = statement.importClause;
                  const specifier = ts.isStringLiteral(statement.moduleSpecifier)
                    ? statement.moduleSpecifier.text : "";
                  if (!clause || clause.isTypeOnly || !clause.name || clause.namedBindings || !specifier.endsWith(".json")) {
                    fail('Only default JSON imports are supported, e.g. import data from "./data.json"');
                  } else {
                    edits.push({ kind: "json", start: statement.getStart(sf), end: statement.end,
                      name: clause.name.text, path: specifier });
                  }
                } else if (ts.isExportDeclaration(statement) || ts.isExportAssignment(statement)
                    || (statement.modifiers && statement.modifiers.some(m => m.kind === ts.SyntaxKind.ExportKeyword))) {
                  fail("TypeScript modules are not supported yet; only default JSON imports are allowed");
                }
              }
              const visit = node => {
                if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
                  fail("Dynamic import() is not supported");
                }
                if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)
                    && node.expression.text === "require_recipes") {
                  if (node.arguments.length > 1) {
                    fail("require_recipes() takes at most one filter object");
                  } else {
                    const filter = {};
                    const argument = node.arguments[0];
                    if (argument && !ts.isObjectLiteralExpression(argument)) {
                      fail("require_recipes() filter must be an object literal");
                    } else if (argument) {
                      for (const property of argument.properties) {
                        if (!ts.isPropertyAssignment(property)
                            || !(ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))) {
                          fail("require_recipes() filter must contain plain name: value pairs");
                          break;
                        }
                        const key = property.name.text;
                        if (!["id", "type", "machine", "input", "output"].includes(key)) {
                          fail(`require_recipes(): unknown filter key "${key}"`);
                          break;
                        }
                        filter[key] = literalStrings(property.initializer, key);
                      }
                    }
                    edits.push({ kind: "recipes", start: node.getStart(sf), end: node.end, filter });
                  }
                }
                ts.forEachChild(node, visit);
              };
              visit(sf);
              return JSON.stringify(error === null ? { edits } : { error });
            };
            globalThis.__afTranspile = function(source) {
              const result = ts.transpileModule(source, {
                fileName: "controller.ts",
                reportDiagnostics: true,
                compilerOptions: {
                  target: ts.ScriptTarget.ES2022,
                  module: ts.ModuleKind.None,
                  isolatedModules: true,
                  removeComments: false
                }
              });
              const errors = (result.diagnostics || []).filter(d => d.category === ts.DiagnosticCategory.Error);
              return JSON.stringify(errors.length
                ? { error: ts.flattenDiagnosticMessageText(errors[0].messageText, "\\n") }
                : { code: result.outputText });
            };
            """;

    private ScriptBundler() {
    }

    public static Path workspaceDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("appliedscripts");
    }

    @Nullable
    public static Path resolveFile(String file, @Nullable Path baseDir) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            var root = workspaceDir().toAbsolutePath().normalize();
            var base = baseDir == null ? root : baseDir.toAbsolutePath().normalize();
            var given = Path.of(file.replace('/', java.io.File.separatorChar));
            if (given.isAbsolute()) {
                return null;
            }
            var resolved = base.resolve(given).normalize();
            return resolved.startsWith(root) && Files.isRegularFile(resolved) ? resolved : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static String bundle(String source, @Nullable Path baseDir) throws McpToolException {
        synchronized (COMPILER_LOCK) {
            var scan = invoke("__afScan", source);
            var edits = parseEdits(scan, baseDir);
            var expanded = apply(source, edits);
            var result = invoke("__afTranspile", expanded);
            if (result.has("error")) {
                throw new McpToolException(-32602, "TypeScript compile failed: "
                        + result.get("error").getAsString());
            }
            return result.get("code").getAsString();
        }
    }

    public static String bundleFromWorkspaceRoot(String source) throws McpToolException {
        return bundle(source, workspaceDir());
    }

    public static void requireTypeScriptEntry(String path) throws McpToolException {
        var normalized = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.endsWith(".ts") || normalized.endsWith(".d.ts")) {
            throw new McpToolException(-32602,
                    "Controller entry file must be a .ts file (declaration files cannot execute)");
        }
    }

    private static List<Edit> parseEdits(JsonObject scan, @Nullable Path baseDir)
            throws McpToolException {
        if (scan.has("error")) {
            throw new McpToolException(-32602, "TypeScript precompile failed: "
                    + scan.get("error").getAsString());
        }
        var edits = new ArrayList<Edit>();
        for (var element : scan.getAsJsonArray("edits")) {
            var edit = element.getAsJsonObject();
            String replacement;
            if ("json".equals(edit.get("kind").getAsString())) {
                var requested = edit.get("path").getAsString();
                if (!(requested.startsWith("./") || requested.startsWith("../"))) {
                    throw new McpToolException(-32602,
                            "JSON import must use a relative path: " + requested);
                }
                var path = resolveFile(requested, baseDir);
                if (path == null) {
                    throw new McpToolException(-32602, "JSON import not found: " + requested);
                }
                replacement = "const " + edit.get("name").getAsString() + " = "
                        + readJson(path, requested) + ";";
            } else {
                var filter = new LinkedHashMap<String, List<String>>();
                for (var entry : edit.getAsJsonObject("filter").entrySet()) {
                    var values = new ArrayList<String>();
                    entry.getValue().getAsJsonArray().forEach(value -> values.add(value.getAsString()));
                    filter.put(entry.getKey(), List.copyOf(values));
                }
                replacement = RecipeMacroExpander.expandFilter(filter, baseDir);
            }
            edits.add(new Edit(edit.get("start").getAsInt(), edit.get("end").getAsInt(), replacement));
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        return edits;
    }

    private static String apply(String source, List<Edit> edits) throws McpToolException {
        var result = new StringBuilder(source);
        int previousStart = source.length();
        for (var edit : edits) {
            if (edit.end() > previousStart || edit.start() < 0 || edit.end() > source.length()) {
                throw new McpToolException(-32602, "Overlapping TypeScript precompile edits");
            }
            result.replace(edit.start(), edit.end(), edit.replacement());
            previousStart = edit.start();
        }
        return result.toString();
    }

    private static String readJson(Path path, String requested) throws McpToolException {
        try {
            var text = Files.readString(path, StandardCharsets.UTF_8);
            return JsonParser.parseString(text).toString();
        } catch (IOException | RuntimeException exception) {
            throw new McpToolException(-32602,
                    "Invalid JSON import " + requested + ": " + messageOf(exception));
        }
    }

    private static JsonObject invoke(String function, String source) throws McpToolException {
        try {
            var context = compilerContext();
            var json = context.getBindings("js").getMember(function).execute(source).asString();
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new McpToolException(-32602,
                    "TypeScript compiler failed: " + messageOf(exception));
        }
    }

    private static Context compilerContext() throws McpToolException {
        if (compilerContext != null) {
            return compilerContext;
        }
        try (InputStream stream = ScriptBundler.class.getResourceAsStream(TYPESCRIPT_RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing bundled " + TYPESCRIPT_RESOURCE);
            }
            var typescript = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var context = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(ignored -> false)
                    .allowIO(IOAccess.NONE)
                    .allowCreateThread(false)
                    .build();
            context.eval(Source.newBuilder("js", typescript, "typescript.js").buildLiteral());
            context.eval(Source.newBuilder("js", COMPILER_HELPER, "appliedfactory-ts-helper.js")
                    .buildLiteral());
            compilerContext = context;
            return context;
        } catch (IOException | PolyglotException exception) {
            AppliedFactory.LOGGER.warn("Unable to initialize the embedded TypeScript compiler", exception);
            throw new McpToolException(-32602,
                    "Unable to initialize TypeScript compiler: " + messageOf(exception));
        }
    }

    private static String messageOf(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private record Edit(int start, int end, String replacement) {
    }
}
