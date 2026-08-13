package com.fulent.appliedfactory.mcp;

import java.util.UUID;

import com.fulent.appliedfactory.network.BindMcpControllerPayload;
import com.fulent.appliedfactory.network.McpBindResultPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-side coordinator: owns the HTTP MCP server, pending request registry and binding. */
public final class McpClientManager {
    private static final McpClientManager INSTANCE = new McpClientManager();

    private final McpRequestRegistry registry = new McpRequestRegistry();
    private final McpSseRegistry sse = new McpSseRegistry();
    private McpServer server;
    private McpBinding binding;

    private McpClientManager() {
    }

    public static McpClientManager get() {
        return INSTANCE;
    }

    public McpRequestRegistry registry() {
        return registry;
    }

    public McpBinding binding() {
        return binding;
    }

    public void setBinding(McpBinding binding) {
        this.binding = binding;
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    public int port() {
        return server == null ? -1 : server.port();
    }

    public synchronized boolean start(int port) {
        if (isRunning()) {
            return true;
        }
        server = new McpServer(port, registry, sse);
        if (!server.start()) {
            server = null;
            return false;
        }
        return true;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        registry.clear();
        sse.closeAll();
    }

    /** Binds the controller at {@code pos} (validated by the server) and updates the binding. */
    public void requestBind(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        var requestId = UUID.randomUUID();
        var future = registry.awaitBind(requestId);
        mc.execute(() -> {
            try {
                PacketDistributor.sendToServer(new BindMcpControllerPayload(requestId, pos));
            } catch (RuntimeException exception) {
                registry.completeBind(new McpBindResultPayload(
                        requestId, pos, false, "", "send failed: " + exception.getMessage()));
            }
        });
        future.thenAccept(result -> {
            if (mc.player == null) {
                return;
            }
            if (result.accepted()) {
                binding = new McpBinding(result.dimension(), result.pos(), result.label());
                mc.player.sendSystemMessage(Component.literal(
                        "MCP bound to " + result.label()
                                + " (" + result.dimension() + " " + result.pos().toShortString() + ")"));
            } else {
                mc.player.sendSystemMessage(Component.literal(
                        "MCP bind failed: no factory controller in range"));
            }
        });
    }
}
