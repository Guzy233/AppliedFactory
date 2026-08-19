package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Client-side precompile expansion of the {@code require_recipes(filter)} macro.
 *
 * <p>The macro lets a script fetch the recipes it needs by a static filter
 * written inside the call, e.g.
 *
 * <pre>{@code const smelt = require_recipes({ type: "minecraft:smelting" });}</pre>
 *
 * Before MCP {@code execute}/{@code upload} is sent or the controller editor
 * saves, the client reads {@code processing_recipes.json} (and
 * {@code recipe_types.json} when a {@code machine} filter is used) from the
 * appliedscripts workspace and replaces every {@code require_recipes(...)}
 * call with a JSON array literal of the matching recipe entries — the call
 * behaves like a precompiled macro and never reaches the controller runtime.
 * A filter that matches nothing expands to {@code []}; a missing or malformed
 * data file is a bundling error.
 *
 * <p>Filter keys (multiple keys AND, a value may be a string or an array of
 * strings meaning any-of):
 * <ul>
 * <li>{@code id} — exact recipe id;</li>
 * <li>{@code type} — exact recipe type id, e.g. {@code minecraft:smelting};</li>
 * <li>{@code machine} — machine block id that processes the recipe's type,
 * resolved through {@code recipe_types.json};</li>
 * <li>{@code input}/{@code output} — any input/output resource whose
 * {@code key.id} equals the value.</li>
 * </ul>
 */
public final class RecipeMacroExpander {
    private static final String MACRO = "require_recipes";
    private static final Gson GSON = new Gson();

    private RecipeMacroExpander() {
    }

    /** Selects and serializes one statically parsed macro invocation. */
    static String expandFilter(Map<String, List<String>> filter, @Nullable Path baseDir)
            throws McpToolException {
        var recipes = loadArray("processing_recipes.json", baseDir);
        var machineTypes = filter.containsKey("machine")
                ? loadObject("recipe_types.json", baseDir)
                : null;
        try {
            return select(recipes, machineTypes, filter).toString();
        } catch (RuntimeException exception) {
            throw new McpToolException(-32602, MACRO + "() expansion failed: "
                    + exception.getMessage() + " (is " + "processing_recipes.json"
                    + " well-formed?)");
        }
    }

    private static JsonArray select(
            JsonArray recipes, @Nullable JsonObject machineTypes, Map<String, List<String>> filter) {
        var result = new JsonArray();
        for (var element : recipes) {
            if (element instanceof JsonObject recipe && matches(recipe, filter, machineTypes)) {
                result.add(recipe);
            }
        }
        return result;
    }

    private static boolean matches(
            JsonObject recipe, Map<String, List<String>> filter, @Nullable JsonObject machineTypes) {
        for (var entry : filter.entrySet()) {
            var values = entry.getValue();
            switch (entry.getKey()) {
                case "id" -> {
                    var id = recipe.get("id");
                    if (id == null || !id.isJsonPrimitive() || !values.contains(id.getAsString())) {
                        return false;
                    }
                }
                case "type" -> {
                    var type = recipe.get("type");
                    if (type == null || !type.isJsonPrimitive()
                            || !values.contains(type.getAsString())) {
                        return false;
                    }
                }
                case "machine" -> {
                    var type = recipe.get("type");
                    var typeId = type != null && type.isJsonPrimitive() ? type.getAsString() : null;
                    var machines = typeId == null || machineTypes == null
                            ? null
                            : machineTypes.getAsJsonArray(typeId);
                    if (machines == null || !containsAny(values, machines)) {
                        return false;
                    }
                }
                case "input" -> {
                    var inputs = recipe.get("inputs");
                    if (!(inputs instanceof JsonArray array) || !resourceContains(values, array)) {
                        return false;
                    }
                }
                case "output" -> {
                    var outputs = recipe.get("outputs");
                    if (!(outputs instanceof JsonArray array) || !resourceContains(values, array)) {
                        return false;
                    }
                }
                default -> throw new IllegalStateException("unhandled filter key " + entry.getKey());
            }
        }
        return true;
    }

    private static boolean containsAny(List<String> values, JsonArray actuals) {
        for (var element : actuals) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && values.contains(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean resourceContains(List<String> values, JsonArray resources) {
        for (var element : resources) {
            if (!(element instanceof JsonObject resource)) {
                continue;
            }
            if (idMatches(values, resource.get("key"))) {
                return true;
            }
            // Input slots may carry an alternatives list; matching any option
            // counts as matching the slot.
            if (resource.get("options") instanceof JsonArray options) {
                for (var option : options) {
                    if (option instanceof JsonObject obj && idMatches(values, obj.get("key"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean idMatches(List<String> values, @Nullable JsonElement key) {
        return key instanceof JsonObject obj
                && obj.has("id") && obj.get("id").isJsonPrimitive()
                && values.contains(obj.get("id").getAsString());
    }

    private static JsonArray loadArray(String name, @Nullable Path baseDir) throws McpToolException {
        JsonArray parsed;
        try {
            parsed = GSON.fromJson(load(name, baseDir), JsonArray.class);
        } catch (JsonParseException exception) {
            throw new McpToolException(-32602, MACRO + "(): " + name + " is not valid JSON");
        }
        if (parsed == null) {
            throw new McpToolException(-32602, MACRO + "(): " + name + " must contain a JSON array");
        }
        return parsed;
    }

    private static JsonObject loadObject(String name, @Nullable Path baseDir) throws McpToolException {
        JsonObject parsed;
        try {
            parsed = GSON.fromJson(load(name, baseDir), JsonObject.class);
        } catch (JsonParseException exception) {
            throw new McpToolException(-32602, MACRO + "(): " + name + " is not valid JSON");
        }
        if (parsed == null) {
            throw new McpToolException(-32602, MACRO + "(): " + name + " must contain a JSON object");
        }
        return parsed;
    }

    private static String load(String name, @Nullable Path baseDir) throws McpToolException {
        var path = ScriptBundler.resolveFile(name, baseDir);
        if (path == null && baseDir != null) {
            path = ScriptBundler.resolveFile(name, ScriptBundler.workspaceDir());
        }
        if (path == null) {
            throw new McpToolException(-32602, MACRO + "(): " + name
                    + " not found in the appliedscripts workspace; run /appliedfactory export"
                    + " (or setupworkspace) in a local save to generate it");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new McpToolException(-32602, MACRO + "(): failed to read " + path
                    + ": " + exception.getMessage());
        }
    }
}
