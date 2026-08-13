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

/** Client-to-server request to replace the bound controller's production program. */
public record UploadControllerProgramPayload(
        UUID requestId, String dimension, BlockPos pos, String source)
        implements CustomPacketPayload {
    public static final Type<UploadControllerProgramPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    AppliedFactory.MOD_ID, "mcp_upload_controller_program"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadControllerProgramPayload>
            STREAM_CODEC = StreamCodec.composite(
                    NetworkCodecs.UUID, UploadControllerProgramPayload::requestId,
                    ByteBufCodecs.stringUtf8(64), UploadControllerProgramPayload::dimension,
                    BlockPos.STREAM_CODEC, UploadControllerProgramPayload::pos,
                    ByteBufCodecs.stringUtf8(ControllerProgram.MAX_SOURCE_LENGTH * 3),
                    UploadControllerProgramPayload::source,
                    UploadControllerProgramPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
