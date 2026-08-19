package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fulent.appliedfactory.factory.McpProbeManager;
import com.fulent.appliedfactory.network.ExecuteMcpCodePayload;
import com.fulent.appliedfactory.network.McpCodeResultPayload;
import com.fulent.appliedfactory.network.UploadControllerProgramPayload;
import com.fulent.appliedfactory.network.UploadResultPayload;
import com.fulent.appliedfactory.script.ControllerProgram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.network.PacketDistributor;

/** MCP tool schemas and call handlers backed by the client-to-server payload relay. */
public final class McpTools {
    private static final int MAX_TIMEOUT_TICKS = 72_000;

    private final McpRequestRegistry registry;

    public McpTools(McpRequestRegistry registry) {
        this.registry = registry;
    }

    public JsonObject list() {
        var tools = new JsonArray();
        tools.add(executeSchema());
        tools.add(uploadSchema());
        tools.add(statusSchema());
        var result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    public JsonObject call(JsonObject params) throws McpToolException {
        var name = params.has("name") ? params.get("name").getAsString() : "";
        var arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();
        switch (name) {
            case "appliedfactory_execute" -> {
                return execute(arguments);
            }
            case "appliedfactory_upload" -> {
                return upload(arguments);
            }
            case "appliedfactory_status" -> {
                return textResult(status(), false);
            }
            default -> throw new McpToolException(-32602, "Unknown tool: " + name);
        }
    }

    private JsonObject execute(JsonObject arguments) throws McpToolException {
        var code = scriptSource(arguments, "code");
        if (!ControllerProgram.isWithinLimit(code)) {
            throw new McpToolException(-32602,
                    "code too long (max " + ControllerProgram.MAX_SOURCE_LENGTH + " chars)");
        }
        int timeoutTicks = arguments.has("timeoutTicks")
                ? arguments.get("timeoutTicks").getAsInt() : -1;
        if (timeoutTicks < -1 || timeoutTicks > MAX_TIMEOUT_TICKS) {
            throw new McpToolException(-32602,
                    "timeoutTicks must be -1 (no timeout) .. " + MAX_TIMEOUT_TICKS);
        }
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            throw new McpToolException(-32000, "not connected to a server");
        }
        var binding = McpClientManager.get().binding();
        if (binding == null) {
            throw new McpToolException(-32000,
                    "no controller bound; open a controller GUI and click 'Bind to MCP'");
        }
        if (mc.level == null
                || !binding.dimension().equals(mc.level.dimension().location().toString())) {
            throw new McpToolException(-32000, "bound controller is in another dimension");
        }
        var requestId = UUID.randomUUID();
        var future = registry.awaitCode(requestId);
        try {
            mc.execute(() -> {
                try {
                    PacketDistributor.sendToServer(new ExecuteMcpCodePayload(
                            requestId, binding.dimension(), binding.pos(), code, timeoutTicks));
                } catch (RuntimeException exception) {
                    registry.completeCode(new McpCodeResultPayload(
                            requestId, "error", "failed to send: " + exception.getMessage(),
                            List.of(), Optional.empty(), List.of(), 0, 0));
                }
            });
        } catch (RuntimeException exception) {
            registry.completeCode(new McpCodeResultPayload(
                    requestId, "error", "failed to send: " + exception.getMessage(),
                    List.of(), Optional.empty(), List.of(), 0, 0));
        }
        var maxWaitMs = timeoutTicks > 0
                ? timeoutTicks * 50L + 60_000L
                : McpProbeManager.HARD_TIMEOUT_TICKS * 50L + 60_000L;
        McpCodeResultPayload payload;
        try {
            payload = future.get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new McpToolException(-32000,
                    "client wait exceeded " + (maxWaitMs / 1000)
                            + "s; the probe may still be running on the server");
        } catch (Exception exception) {
            throw new McpToolException(-32000, "request failed: " + exception.getMessage());
        }
        return textResult(codeResult(payload), "error".equals(payload.reason()));
    }

    private JsonObject codeResult(McpCodeResultPayload payload) {
        var inner = new JsonObject();
        inner.addProperty("reason", payload.reason());
        inner.addProperty("message", payload.message());
        var logs = new JsonArray();
        payload.logs().forEach(logs::add);
        inner.add("logs", logs);
        inner.add("result", parseResult(payload.resultJson()));
        var pending = new JsonArray();
        payload.pending().forEach(pending::add);
        inner.add("pending", pending);
        inner.addProperty("elapsedTicks", payload.elapsedTicks());
        inner.addProperty("steps", payload.steps());
        return inner;
    }

    private JsonElement parseResult(Optional<String> resultJson) {
        if (resultJson.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonParser.parseString(resultJson.get());
        } catch (JsonSyntaxException exception) {
            return new JsonPrimitive(resultJson.get());
        }
    }

    private JsonObject upload(JsonObject arguments) throws McpToolException {
        var program = controllerProgramFile(arguments);
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            throw new McpToolException(-32000, "not connected to a server");
        }
        var binding = McpClientManager.get().binding();
        if (binding == null) {
            throw new McpToolException(-32000, "no controller bound");
        }
        if (mc.level == null
                || !binding.dimension().equals(mc.level.dimension().location().toString())) {
            throw new McpToolException(-32000, "bound controller is in another dimension");
        }
        var requestId = UUID.randomUUID();
        var future = registry.awaitUpload(requestId);
        try {
            mc.execute(() -> {
                try {
                    PacketDistributor.sendToServer(new UploadControllerProgramPayload(
                            requestId, binding.dimension(), binding.pos(),
                            program.source(), program.compiledSource(), program.workspacePath()));
                } catch (RuntimeException exception) {
                    registry.completeUpload(new UploadResultPayload(
                            requestId, false, "failed to send: " + exception.getMessage()));
                }
            });
        } catch (RuntimeException exception) {
            registry.completeUpload(new UploadResultPayload(
                    requestId, false, "failed to send: " + exception.getMessage()));
        }
        UploadResultPayload payload;
        try {
            payload = future.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new McpToolException(-32000, "upload failed: " + exception.getMessage());
        }
        var inner = new JsonObject();
        inner.addProperty("ok", payload.ok());
        inner.addProperty("message", payload.message());
        return textResult(inner, !payload.ok());
    }

    private JsonObject status() {
        var inner = new JsonObject();
        var mc = Minecraft.getInstance();
        var binding = McpClientManager.get().binding();
        inner.addProperty("connected", mc.getConnection() != null);
        inner.addProperty("singlePlayer", mc.isSingleplayer());
        inner.addProperty("workspace", ScriptBundler.workspaceDir().toString());
        if (binding != null) {
            inner.addProperty("bound", true);
            inner.addProperty("dimension", binding.dimension());
            inner.addProperty("pos", binding.pos().toShortString());
            inner.addProperty("label", binding.label());
        } else {
            inner.addProperty("bound", false);
        }
        return inner;
    }

    /**
     * Script source for probe execution: a {@code file} argument (filename
     * relative to the appliedscripts workspace) takes precedence over the inline
     * {@code inlineName} argument, so long scripts with baked recipes can live in
     * files instead of the tool call. Production upload has a separate file-only path.
     * {@code include("file")} calls are resolved
     * as textual substitutions against the workspace before sending, and
     * {@code require_recipes(filter)} calls are expanded to recipe literals.
     */
    private static String scriptSource(JsonObject arguments, String inlineName)
            throws McpToolException {
        String bundled;
        if (arguments.has("file")) {
            var file = arguments.get("file").getAsString();
            var path = ScriptBundler.resolveFile(file, null);
            if (path == null) {
                throw new McpToolException(-32602,
                        "script file not found: " + file + " (resolved against the appliedscripts workspace)");
            }
            bundled = ScriptBundler.bundle(readFile(path, file), path.getParent());
        } else if (arguments.has(inlineName)) {
            bundled = ScriptBundler.bundleFromWorkspaceRoot(arguments.get(inlineName).getAsString());
        } else {
            throw new McpToolException(-32602, inlineName + " (inline) or file is required");
        }
        if (!ControllerProgram.isWithinLimit(bundled)) {
            throw new McpToolException(-32602, "bundled source exceeds "
                    + ControllerProgram.MAX_SOURCE_LENGTH + " chars after include()/require_recipes()"
                    + " expansion; narrow the require_recipes filters (or trim include()s)");
        }
        return bundled;
    }

    /** Production uploads are always anchored to a real local workspace file. */
    private static ControllerProgramFile controllerProgramFile(JsonObject arguments)
            throws McpToolException {
        if (!arguments.has("file")) {
            throw new McpToolException(-32602,
                    "file is required; controller programs cannot be uploaded without a local backup");
        }
        var requested = arguments.get("file").getAsString();
        var path = ScriptBundler.resolveFile(requested, null);
        if (path == null) {
            throw new McpToolException(-32602,
                    "script file not found: " + requested + " (resolved against the appliedscripts workspace)");
        }
        var workspace = ScriptBundler.workspaceDir().toAbsolutePath().normalize();
        var absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(workspace)) {
            throw new McpToolException(-32602,
                    "upload file must be inside the appliedscripts workspace");
        }
        var relative = workspace.relativize(absolute).toString().replace('\\', '/');
        if (!ControllerProgram.isWorkspacePathWithinLimit(relative)) {
            throw new McpToolException(-32602, "workspace file path is too long");
        }
        var source = readFile(path, requested);
        var compiled = ScriptBundler.bundle(source, path.getParent());
        if (!ControllerProgram.isWithinLimit(compiled)) {
            throw new McpToolException(-32602, "bundled source exceeds "
                    + ControllerProgram.MAX_SOURCE_LENGTH + " chars after preprocessing");
        }
        return new ControllerProgramFile(source, compiled, relative);
    }

    private static String readFile(Path path, String name) throws McpToolException {
        try {
            var source = Files.readString(path, StandardCharsets.UTF_8);
            if (!ControllerProgram.isWithinLimit(source)) {
                throw new McpToolException(-32602, "script file too long (max "
                        + ControllerProgram.MAX_SOURCE_LENGTH + " chars): " + name);
            }
            return source;
        } catch (IOException exception) {
            throw new McpToolException(-32602,
                    "failed to read script file " + name + ": " + exception.getMessage());
        }
    }

    private JsonObject executeSchema() {
        var properties = new JsonObject();
        var file = new JsonObject();
        file.addProperty("type", "string");
        file.addProperty("description", text("mcp.appliedfactory.prompt.execute.file"));
        properties.add("file", file);
        var code = new JsonObject();
        code.addProperty("type", "string");
        code.addProperty("description", text("mcp.appliedfactory.prompt.execute.code"));
        properties.add("code", code);
        var timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("description", text("mcp.appliedfactory.prompt.execute.timeout"));
        properties.add("timeoutTicks", timeout);
        return tool("appliedfactory_execute", text("mcp.appliedfactory.prompt.execute.tool"), properties);
    }

    private JsonObject uploadSchema() {
        var properties = new JsonObject();
        var file = new JsonObject();
        file.addProperty("type", "string");
        file.addProperty("description", text("mcp.appliedfactory.prompt.upload.file"));
        properties.add("file", file);
        return tool("appliedfactory_upload", text("mcp.appliedfactory.prompt.upload.tool"),
                properties, "file");
    }

    private JsonObject statusSchema() {
        return tool("appliedfactory_status", text("mcp.appliedfactory.prompt.status.tool"),
                new JsonObject());
    }

    private JsonObject tool(String name, String description, JsonObject properties,
            String... required) {
        var tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        var inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        inputSchema.add("properties", properties);
        if (required.length > 0) {
            var requiredArray = new JsonArray();
            for (var key : required) {
                requiredArray.add(key);
            }
            inputSchema.add("required", requiredArray);
        }
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private JsonObject textResult(JsonObject inner, boolean isError) {
        var content = new JsonArray();
        var text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", inner.toString());
        content.add(text);
        var result = new JsonObject();
        result.add("content", content);
        if (isError) {
            result.addProperty("isError", true);
        }
        return result;
    }

    /** MCP is client-side, so tool descriptions follow the active Minecraft resource-pack language. */
    private static String text(String key, Object... args) {
        return I18n.get(key, args);
    }

    private record ControllerProgramFile(
            String source, String compiledSource, String workspacePath) {
    }
}
