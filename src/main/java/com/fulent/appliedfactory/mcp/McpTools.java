package com.fulent.appliedfactory.mcp;

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
        if (!arguments.has("code")) {
            throw new McpToolException(-32602, "code is required");
        }
        var code = arguments.get("code").getAsString();
        if (code.length() > ControllerProgram.MAX_SOURCE_LENGTH) {
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
        if (!arguments.has("source")) {
            throw new McpToolException(-32602, "source is required");
        }
        var source = arguments.get("source").getAsString();
        if (source.length() > ControllerProgram.MAX_SOURCE_LENGTH) {
            throw new McpToolException(-32602,
                    "source too long (max " + ControllerProgram.MAX_SOURCE_LENGTH + " chars)");
        }
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
                            requestId, binding.dimension(), binding.pos(), source));
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

    private JsonObject executeSchema() {
        var properties = new JsonObject();
        var code = new JsonObject();
        code.addProperty("type", "string");
        code.addProperty("description",
                "Factory script (Rhino ES6). Same API as a controller program: network(side),"
                        + " buses/target, extract(), storage(), item(), stack(), recipes(), log(), sleep(),"
                        + " go(function*(){...}) with yield resource.to(target) /"
                        + " pushExactlyInto(target). go() generators run as ordinary passive"
                        + " jobs: transfers wait on resources/capacity, sleep crosses real ticks."
                        + " The value of the last expression is returned as 'result'."
                        + " log()/console.log() output is returned in 'logs' directly.");
        properties.add("code", code);
        var timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("description",
                "Max ticks to wait for all go() generators to finish. 0 = evaluate only"
                        + " (generators not started), -1/omitted = wait until completion"
                        + " (hard ceiling 1 hour). 20 ticks = 1 second.");
        properties.add("timeoutTicks", timeout);
        return tool("appliedfactory_execute",
                "Runs a probe program against the bound controller and returns its logs.",
                properties, "code");
    }

    private JsonObject uploadSchema() {
        var properties = new JsonObject();
        var source = new JsonObject();
        source.addProperty("type", "string");
        source.addProperty("description",
                "Full controller program source. Compiles first; on failure the existing"
                        + " production program is left untouched. Same semantics as saving"
                        + " in the controller GUI.");
        properties.add("source", source);
        return tool("appliedfactory_upload",
                "Replaces the bound controller's production program. Prefer testing with"
                        + " appliedfactory_execute before uploading.",
                properties, "source");
    }

    private JsonObject statusSchema() {
        return tool("appliedfactory_status",
                "Read-only status of the connection and the bound controller.",
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
}
