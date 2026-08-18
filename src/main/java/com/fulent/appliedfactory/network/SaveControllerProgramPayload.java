package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.script.ControllerProgram;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server update for the single program owned by a controller. */
public record SaveControllerProgramPayload(BlockPos pos, String source) implements CustomPacketPayload {
    public static final Type<SaveControllerProgramPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "save_controller_program"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveControllerProgramPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SaveControllerProgramPayload::pos,
                    ByteBufCodecs.stringUtf8(ControllerProgram.MAX_SOURCE_BYTES),
                    SaveControllerProgramPayload::source,
                    SaveControllerProgramPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
