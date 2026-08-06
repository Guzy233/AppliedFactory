package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.client.ClientControllerProgramPayloadHandler;
import com.fulent.appliedfactory.menu.FactoryControllerMenuAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

public final class NetworkHandler {
    private NetworkHandler() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                SaveControllerProgramPayload.TYPE,
                SaveControllerProgramPayload.STREAM_CODEC,
                NetworkHandler::handleSaveControllerProgram)
                .playToServer(
                        SetControllerErrorSubscriptionPayload.TYPE,
                        SetControllerErrorSubscriptionPayload.STREAM_CODEC,
                        NetworkHandler::handleSetControllerErrorSubscription)
                .playToClient(
                        ControllerProgramSaveResultPayload.TYPE,
                        ControllerProgramSaveResultPayload.STREAM_CODEC,
                        NetworkHandler::handleSaveControllerProgramResult);
    }

    private static void handleSaveControllerProgram(SaveControllerProgramPayload payload, IPayloadContext context) {
        var factory = controllerFor(payload.pos(), context);
        if (factory == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }

        var result = factory.updateControllerProgram(payload.source());
        PacketDistributor.sendToPlayer(player, new ControllerProgramSaveResultPayload(
                payload.pos(), result.successful(),
                result.successful() ? "" : result.errorMessage()));
        if (result.successful()) {
            player.containerMenu.broadcastChanges();
        }
    }

    private static void handleSetControllerErrorSubscription(
            SetControllerErrorSubscriptionPayload payload, IPayloadContext context) {
        var factory = controllerFor(payload.pos(), context);
        if (factory == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        factory.updateErrorSubscription(player.getUUID(), payload.subscribed());
    }

    private static void handleSaveControllerProgramResult(
            ControllerProgramSaveResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientControllerProgramPayloadHandler.handleSaveResult(payload));
    }

    private static FactoryControllerBlockEntity controllerFor(BlockPos pos, IPayloadContext context) {
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
}
