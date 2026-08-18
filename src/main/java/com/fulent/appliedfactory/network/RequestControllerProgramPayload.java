package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Sent only by an open controller editor to fetch its source from world-level storage. */
public record RequestControllerProgramPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestControllerProgramPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    AppliedFactory.MOD_ID, "request_controller_program"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestControllerProgramPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestControllerProgramPayload::pos,
                    RequestControllerProgramPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
