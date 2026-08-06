package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-side opt-in for chat notifications from one controller. */
public record SetControllerErrorSubscriptionPayload(BlockPos pos, boolean subscribed)
        implements CustomPacketPayload {
    public static final Type<SetControllerErrorSubscriptionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    AppliedFactory.MOD_ID, "set_controller_error_subscription"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetControllerErrorSubscriptionPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetControllerErrorSubscriptionPayload::pos,
                    ByteBufCodecs.BOOL, SetControllerErrorSubscriptionPayload::subscribed,
                    SetControllerErrorSubscriptionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
