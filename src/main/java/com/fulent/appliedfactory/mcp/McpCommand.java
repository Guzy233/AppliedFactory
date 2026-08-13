package com.fulent.appliedfactory.mcp;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Client-side commands: {@code /appliedfactory mcp start|stop|status|bind}. */
public final class McpCommand {
    public static final int DEFAULT_PORT = 39291;

    private McpCommand() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("appliedfactory")
                .then(Commands.literal("mcp")
                        .then(Commands.literal("start")
                                .executes(ctx -> start(ctx.getSource(), DEFAULT_PORT))
                                .then(Commands.argument(
                                                "port", IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> start(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "port")))))
                        .then(Commands.literal("stop")
                                .executes(ctx -> stop(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("bind")
                                .executes(ctx -> bind(ctx.getSource())))));
    }

    private static int start(CommandSourceStack source, int port) {
        var manager = McpClientManager.get();
        if (manager.isRunning()) {
            source.sendSuccess(() -> Component.literal(
                    "MCP server already running at http://127.0.0.1:" + manager.port() + "/mcp"),
                    false);
            return 1;
        }
        if (manager.start(port)) {
            McpConfig.write(port, manager.binding());
            source.sendSuccess(() -> Component.literal(
                    "MCP server started at http://127.0.0.1:" + port
                            + "/mcp (config: <game>/appliedscripts/mcp.json)"), false);
        } else {
            source.sendFailure(Component.literal(
                    "MCP server failed to start on port " + port + " (in use?)"));
        }
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        McpClientManager.get().stop();
        source.sendSuccess(() -> Component.literal("MCP server stopped"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        var manager = McpClientManager.get();
        var mc = Minecraft.getInstance();
        source.sendSuccess(() -> Component.literal(
                "MCP running: " + manager.isRunning()
                        + (manager.isRunning() ? " on port " + manager.port() : "")
                        + ", connected: " + (mc.getConnection() != null)
                        + ", bound: " + (manager.binding() == null
                                ? "none"
                                : manager.binding().label()
                                        + " (" + manager.binding().dimension() + ")")),
                false);
        return 1;
    }

    private static int bind(CommandSourceStack source) {
        var mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult blockHit) {
            McpClientManager.get().requestBind(blockHit.getBlockPos());
            source.sendSuccess(() -> Component.literal("Sending MCP bind request..."), false);
        } else {
            source.sendFailure(Component.literal("Look at a factory controller to bind it"));
        }
        return 1;
    }
}
