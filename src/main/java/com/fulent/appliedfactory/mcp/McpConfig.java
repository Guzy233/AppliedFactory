package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;

/** Writes the MCP server configuration and agent snippets into {@code appliedscripts/}. */
public final class McpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private McpConfig() {
    }

    public static void write(int port, @Nullable McpBinding binding) {
        var dir = Minecraft.getInstance().gameDirectory.toPath().resolve("appliedscripts");
        try {
            Files.createDirectories(dir);
            var root = new JsonObject();
            var server = new JsonObject();
            server.addProperty("host", "127.0.0.1");
            server.addProperty("port", port);
            server.addProperty("token", "");
            root.add("server", server);
            if (binding != null) {
                var bind = new JsonObject();
                bind.addProperty("dimension", binding.dimension());
                var pos = new JsonArray();
                pos.add(binding.pos().getX());
                pos.add(binding.pos().getY());
                pos.add(binding.pos().getZ());
                bind.add("pos", pos);
                bind.addProperty("label", binding.label());
                root.add("binding", bind);
            }
            var url = "http://127.0.0.1:" + port + "/mcp";
            var clients = new JsonObject();
            clients.addProperty("claude_code",
                    "claude mcp add --transport http appliedfactory " + url);
            clients.addProperty("cursor", "Cursor Settings -> MCP -> Add -> URL: " + url);
            var desktop = new JsonObject();
            var mcpServers = new JsonObject();
            var entry = new JsonObject();
            entry.addProperty("type", "http");
            entry.addProperty("url", url);
            mcpServers.add("appliedfactory", entry);
            desktop.add("mcpServers", mcpServers);
            clients.add("claude_desktop_config", desktop);
            root.add("clients", clients);
            var note = new JsonObject();
            note.addProperty("502_proxy", "If the agent reports 502 Bad Gateway, it is routing "
                    + "localhost through an environment proxy. Launch the agent with "
                    + "NO_PROXY=localhost,127.0.0.1 (and no_proxy) set, or use "
                    + "curl --noproxy '*' to verify the endpoint directly.");
            root.add("troubleshooting", note);
            Files.writeString(
                    dir.resolve("mcp.json"), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AppliedFactory.LOGGER.warn(
                    "Failed to write MCP config to {}: {}", dir, exception.getMessage());
        }
    }
}
