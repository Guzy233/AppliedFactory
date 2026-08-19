package com.fulent.appliedfactory.network;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.client.ClientControllerProgramPayloadHandler;
import com.fulent.appliedfactory.client.ClientMachineIconsPayloadHandler;
import com.fulent.appliedfactory.client.ClientMcpPayloadHandler;
import com.fulent.appliedfactory.client.ClientRecipeDumpPayloadHandler;
import com.fulent.appliedfactory.command.MachineIconManager;
import com.fulent.appliedfactory.command.RecipeDumpManager;
import com.fulent.appliedfactory.factory.McpProbeManager;
import com.fulent.appliedfactory.factory.McpProbeResult;
import com.fulent.appliedfactory.menu.FactoryControllerMenuAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class NetworkHandler {
    private NetworkHandler() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("3")
                .playToServer(
                        SaveControllerProgramPayload.TYPE,
                        SaveControllerProgramPayload.STREAM_CODEC,
                        NetworkHandler::handleSaveControllerProgram)
                .playToServer(
                        RequestControllerProgramPayload.TYPE,
                        RequestControllerProgramPayload.STREAM_CODEC,
                        NetworkHandler::handleRequestControllerProgram)
                .playToServer(
                        SetControllerLogSubscriptionPayload.TYPE,
                        SetControllerLogSubscriptionPayload.STREAM_CODEC,
                        NetworkHandler::handleSetControllerLogSubscription)
                .playToServer(
                        ExecuteMcpCodePayload.TYPE,
                        ExecuteMcpCodePayload.STREAM_CODEC,
                        NetworkHandler::handleExecuteMcpCode)
                .playToServer(
                        UploadControllerProgramPayload.TYPE,
                        UploadControllerProgramPayload.STREAM_CODEC,
                        NetworkHandler::handleUploadControllerProgram)
                .playToServer(
                        BindMcpControllerPayload.TYPE,
                        BindMcpControllerPayload.STREAM_CODEC,
                        NetworkHandler::handleBindMcpController)
                .playToClient(
                        ControllerProgramSaveResultPayload.TYPE,
                        ControllerProgramSaveResultPayload.STREAM_CODEC,
                        NetworkHandler::handleSaveControllerProgramResult)
                .playToClient(
                        ControllerProgramContentPayload.TYPE,
                        ControllerProgramContentPayload.STREAM_CODEC,
                        NetworkHandler::handleControllerProgramContent)
                .playToClient(
                        McpCodeResultPayload.TYPE,
                        McpCodeResultPayload.STREAM_CODEC,
                        NetworkHandler::handleMcpCodeResult)
                .playToClient(
                        UploadResultPayload.TYPE,
                        UploadResultPayload.STREAM_CODEC,
                        NetworkHandler::handleUploadResult)
                .playToClient(
                        McpBindResultPayload.TYPE,
                        McpBindResultPayload.STREAM_CODEC,
                        NetworkHandler::handleMcpBindResult)
                .playToClient(
                        RequestMachineIconsPayload.TYPE,
                        RequestMachineIconsPayload.STREAM_CODEC,
                        NetworkHandler::handleMachineIconsRequest)
                .playToServer(
                        MachineIconsPayload.TYPE,
                        MachineIconsPayload.STREAM_CODEC,
                        NetworkHandler::handleMachineIcons)
                .playToClient(
                        RequestRecipeDumpPayload.TYPE,
                        RequestRecipeDumpPayload.STREAM_CODEC,
                        NetworkHandler::handleRecipeDumpRequest)
                .playToServer(
                        RecipeDumpChunkPayload.TYPE,
                        RecipeDumpChunkPayload.STREAM_CODEC,
                        NetworkHandler::handleRecipeDumpChunk);
    }

    private static void handleSaveControllerProgram(
            SaveControllerProgramPayload payload, IPayloadContext context) {
        var factory = controllerFor(payload.pos(), context);
        if (factory == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }

        var result = factory.updateControllerProgram(
                payload.source(), payload.compiledSource(), payload.workspacePath());
        PacketDistributor.sendToPlayer(player, new ControllerProgramSaveResultPayload(
                payload.pos(), result.successful(),
                result.successful() ? "" : result.errorMessage()));
        if (result.successful()) {
            player.containerMenu.broadcastChanges();
        }
    }

    private static void handleRequestControllerProgram(
            RequestControllerProgramPayload payload, IPayloadContext context) {
        var factory = controllerFor(payload.pos(), context);
        if (factory == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ControllerProgramContentPayload(
                payload.pos(), factory.getControllerProgram(), factory.getControllerProgramPath()));
    }

    private static void handleSetControllerLogSubscription(
            SetControllerLogSubscriptionPayload payload, IPayloadContext context) {
        var factory = controllerFor(payload.pos(), context);
        if (factory == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        factory.updateLogSubscription(player.getUUID(), payload.subscribed());
    }

    private static void handleExecuteMcpCode(
            ExecuteMcpCodePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        var factory = boundController(player, payload.dimension(), payload.pos());
        if (factory == null) {
            PacketDistributor.sendToPlayer(player, new McpCodeResultPayload(
                    payload.requestId(), "error",
                    "controller not loaded or not in the bound dimension",
                    List.of(), Optional.empty(), List.of(), 0, 0));
            return;
        }
        var playerId = player.getUUID();
        var server = player.server;
        McpProbeManager.execute(
                playerId,
                payload.requestId(),
                factory,
                payload.code(),
                payload.timeoutTicks(),
                (requestId, result) -> {
                    var recipient = server.getPlayerList().getPlayer(playerId);
                    if (recipient != null) {
                        PacketDistributor.sendToPlayer(recipient, toCodeResult(requestId, result));
                    }
                });
    }

    private static McpCodeResultPayload toCodeResult(UUID requestId, McpProbeResult result) {
        var message = result.message();
        if (message.length() > 2_000) {
            message = message.substring(0, 2_000);
        }
        return new McpCodeResultPayload(
                requestId,
                result.reason(),
                message,
                result.logs(),
                Optional.ofNullable(result.resultJson()),
                result.pending(),
                result.elapsedTicks(),
                result.steps());
    }

    private static void handleUploadControllerProgram(
            UploadControllerProgramPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        var factory = boundController(player, payload.dimension(), payload.pos());
        if (factory == null) {
            PacketDistributor.sendToPlayer(player, new UploadResultPayload(
                    payload.requestId(), false, "controller not loaded or not bound"));
            return;
        }
        var result = factory.updateControllerProgram(
                payload.source(), payload.compiledSource(), payload.workspacePath());
        PacketDistributor.sendToPlayer(player, new UploadResultPayload(
                payload.requestId(), result.successful(),
                result.successful() ? "" : result.errorMessage()));
        if (result.successful()) {
            AppliedFactory.LOGGER.info(
                    "Player {} uploaded a production program to factory controller at {} via MCP",
                    player.getGameProfile().getName(), payload.pos().toShortString());
            player.sendSystemMessage(Component.literal(
                    "MCP uploaded a production program to factory at " + payload.pos().toShortString()));
        }
    }

    private static void handleBindMcpController(
            BindMcpControllerPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        var factory = player.level().getBlockEntity(payload.pos());
        if (!(factory instanceof FactoryControllerBlockEntity controller)
                || controller.getBlockPos().distSqr(player.blockPosition()) > 64) {
            PacketDistributor.sendToPlayer(player, new McpBindResultPayload(
                    payload.requestId(), payload.pos(), false, "", ""));
            return;
        }
        var dimension = player.level().dimension().location().toString();
        var label = "factory@" + payload.pos().toShortString();
        PacketDistributor.sendToPlayer(player, new McpBindResultPayload(
                payload.requestId(), payload.pos(), true, dimension, label));
    }

    private static void handleSaveControllerProgramResult(
            ControllerProgramSaveResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientControllerProgramPayloadHandler.handleSaveResult(payload));
    }

    private static void handleControllerProgramContent(
            ControllerProgramContentPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientControllerProgramPayloadHandler.handleProgramContent(payload));
    }

    private static void handleMcpCodeResult(McpCodeResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientMcpPayloadHandler.handleCodeResult(payload));
    }

    private static void handleUploadResult(UploadResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientMcpPayloadHandler.handleUploadResult(payload));
    }

    private static void handleMcpBindResult(McpBindResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientMcpPayloadHandler.handleBindResult(payload));
    }

    private static void handleMachineIconsRequest(
            RequestMachineIconsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientMachineIconsPayloadHandler.handleRequest(payload));
    }

    private static void handleMachineIcons(MachineIconsPayload payload, IPayloadContext context) {
        MachineIconManager.accept(payload);
    }

    private static void handleRecipeDumpRequest(
            RequestRecipeDumpPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRecipeDumpPayloadHandler.handleRequest(payload));
    }

    private static void handleRecipeDumpChunk(
            RecipeDumpChunkPayload payload, IPayloadContext context) {
        RecipeDumpManager.accept(payload);
    }

    private static FactoryControllerBlockEntity controllerFor(
            BlockPos pos, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof FactoryControllerMenuAccess menu)) {
            return null;
        }
        FactoryControllerBlockEntity factory = menu.getBlockEntity();
        if (factory == null
                || !factory.getBlockPos().equals(pos)
                || factory.getBlockPos().distSqr(player.blockPosition()) > 64) {
            return null;
        }
        return factory;
    }

    /** Looks up the controller at {@code pos} in the player's current dimension, if loaded. */
    private static FactoryControllerBlockEntity boundController(
            ServerPlayer player, String dimension, BlockPos pos) {
        if (player == null || player.level() == null) {
            return null;
        }
        if (!dimension.equals(player.level().dimension().location().toString())) {
            return null;
        }
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof FactoryControllerBlockEntity controller
                ? controller
                : null;
    }
}
