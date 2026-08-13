package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Streamable HTTP transport for MCP (protocol 2025-06-18). Single JSON-RPC dispatch:
 * {@code POST /mcp} for requests, {@code GET /mcp} opens an SSE stream, {@code DELETE /mcp}
 * terminates the session.
 */
public final class McpHttpHandler implements HttpHandler {
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpTools tools;
    private final McpSseRegistry sse;
    private final String sessionId;

    public McpHttpHandler(
            McpRequestRegistry registry, McpSseRegistry sse, String sessionId) {
        this.tools = new McpTools(registry);
        this.sse = sse;
        this.sessionId = sessionId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            var method = exchange.getRequestMethod();
            switch (method) {
                case "POST" -> handlePost(exchange);
                case "GET" -> handleGet(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                }
            }
        } catch (RuntimeException exception) {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body;
        try (var in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject request;
        JsonElement id;
        String method;
        try {
            request = JsonParser.parseString(body).getAsJsonObject();
            id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
            method = request.has("method") ? request.get("method").getAsString() : "";
        } catch (RuntimeException parseError) {
            sendJson(exchange, errorResponse(JsonNull.INSTANCE, -32700, "Parse error"));
            return;
        }
        if (method.startsWith("notifications/")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        switch (method) {
            case "initialize" -> response.add("result", initialize());
            case "ping" -> response.add("result", new JsonObject());
            case "tools/list" -> response.add("result", tools.list());
            case "tools/call" -> {
                try {
                    response.add("result", tools.call(params(request)));
                } catch (McpToolException exception) {
                    response.add("error", errorObject(exception.code(), exception.getMessage()));
                }
            }
            default -> response.add("error", errorObject(-32601, "Method not found: " + method));
        }
        sendJson(exchange, response);
    }

    private JsonObject params(JsonObject request) {
        if (!request.has("params") || !request.get("params").isJsonObject()) {
            return new JsonObject();
        }
        return request.getAsJsonObject("params");
    }

    private JsonObject initialize() {
        var result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        var capabilities = new JsonObject();
        var toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        capabilities.add("tools", toolsCapability);
        result.add("capabilities", capabilities);
        var serverInfo = new JsonObject();
        serverInfo.addProperty("name", "appliedfactory-mcp");
        serverInfo.addProperty("version", McpVersion.current());
        result.add("serverInfo", serverInfo);
        return result;
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        var accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Mcp-Protocol-Version", PROTOCOL_VERSION);
            headers.set("Mcp-Session-Id", sessionId);
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sse.register(out);
        } else {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        sse.closeAll();
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private void sendJson(HttpExchange exchange, JsonObject body) throws IOException {
        var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Mcp-Protocol-Version", PROTOCOL_VERSION);
        headers.set("Mcp-Session-Id", sessionId);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static JsonObject errorResponse(JsonElement id, int code, String message) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", errorObject(code, message));
        return response;
    }

    private static JsonObject errorObject(int code, String message) {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }
}
