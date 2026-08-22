package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-side compilation result displayed by the controller program editor. */
public record ControllerProgramSaveResultPayload(
        BlockPos pos, boolean saved, String message, long updatedAt)
        implements CustomPacketPayload {
    private static final int MAX_MESSAGE_LENGTH = 2_048;

    public static final Type<ControllerProgramSaveResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    AppliedFactory.MOD_ID, "controller_program_save_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerProgramSaveResultPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ControllerProgramSaveResultPayload::pos,
                    ByteBufCodecs.BOOL, ControllerProgramSaveResultPayload::saved,
                    ByteBufCodecs.stringUtf8(MAX_MESSAGE_LENGTH),
                    ControllerProgramSaveResultPayload::message,
                    ByteBufCodecs.VAR_LONG, ControllerProgramSaveResultPayload::updatedAt,
                    ControllerProgramSaveResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
