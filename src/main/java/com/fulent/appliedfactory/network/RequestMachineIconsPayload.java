package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client request for the JEI machine-icon map: the client resolves
 * each recipe category's catalysts (the machines JEI shows next to recipes)
 * and answers with {@link MachineIconsPayload}.
 */
public record RequestMachineIconsPayload(UUID requestId) implements CustomPacketPayload {
    public static final Type<RequestMachineIconsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "machine_icons_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMachineIconsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, RequestMachineIconsPayload::requestId,
                    RequestMachineIconsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
