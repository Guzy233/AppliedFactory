package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.script.ControllerProgram;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server response carrying the source only to the player actively editing the controller. */
public record ControllerProgramContentPayload(BlockPos pos, String source, String workspacePath)
        implements CustomPacketPayload {
    public static final Type<ControllerProgramContentPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    AppliedFactory.MOD_ID, "controller_program_content"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerProgramContentPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ControllerProgramContentPayload::pos,
                    ByteBufCodecs.stringUtf8(ControllerProgram.MAX_SOURCE_BYTES),
                    ControllerProgramContentPayload::source,
                    ByteBufCodecs.stringUtf8(ControllerProgram.MAX_WORKSPACE_PATH_BYTES),
                    ControllerProgramContentPayload::workspacePath,
                    ControllerProgramContentPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
