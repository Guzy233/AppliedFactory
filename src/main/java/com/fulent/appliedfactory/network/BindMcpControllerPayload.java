package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server request to link the MCP server to the controller at {@code pos}. */
public record BindMcpControllerPayload(UUID requestId, BlockPos pos)
        implements CustomPacketPayload {
    public static final Type<BindMcpControllerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "mcp_bind_controller"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BindMcpControllerPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, BindMcpControllerPayload::requestId,
                    BlockPos.STREAM_CODEC, BindMcpControllerPayload::pos,
                    BindMcpControllerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
