package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-client acknowledgement of a controller bind request. */
public record McpBindResultPayload(
        UUID requestId, BlockPos pos, boolean accepted, String dimension, String label)
        implements CustomPacketPayload {
    public static final Type<McpBindResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "mcp_bind_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, McpBindResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, McpBindResultPayload::requestId,
                    BlockPos.STREAM_CODEC, McpBindResultPayload::pos,
                    ByteBufCodecs.BOOL, McpBindResultPayload::accepted,
                    ByteBufCodecs.stringUtf8(64), McpBindResultPayload::dimension,
                    ByteBufCodecs.stringUtf8(64), McpBindResultPayload::label,
                    McpBindResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
