package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.script.ControllerProgram;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server request to run one MCP probe program against the bound controller.
 * {@code timeoutTicks} is -1 (no caller timeout), 0 (evaluate only) or a positive tick bound.
 */
public record ExecuteMcpCodePayload(
        UUID requestId,
        String dimension,
        BlockPos pos,
        String code,
        int timeoutTicks) implements CustomPacketPayload {
    public static final Type<ExecuteMcpCodePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "mcp_execute_code"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteMcpCodePayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, ExecuteMcpCodePayload::requestId,
                    ByteBufCodecs.stringUtf8(64), ExecuteMcpCodePayload::dimension,
                    BlockPos.STREAM_CODEC, ExecuteMcpCodePayload::pos,
                    ByteBufCodecs.stringUtf8(ControllerProgram.MAX_SOURCE_LENGTH),
                    ExecuteMcpCodePayload::code,
                    ByteBufCodecs.VAR_INT, ExecuteMcpCodePayload::timeoutTicks,
                    ExecuteMcpCodePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
