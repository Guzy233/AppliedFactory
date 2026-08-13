package com.fulent.appliedfactory.network;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-side opt-in for chat notifications (script logs and Java-caught errors) from one controller. */
public record SetControllerLogSubscriptionPayload(BlockPos pos, boolean subscribed)
        implements CustomPacketPayload {
    public static final Type<SetControllerLogSubscriptionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    AppliedFactory.MOD_ID, "set_controller_log_subscription"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetControllerLogSubscriptionPayload>
            STREAM_CODEC = StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetControllerLogSubscriptionPayload::pos,
                    ByteBufCodecs.BOOL, SetControllerLogSubscriptionPayload::subscribed,
                    SetControllerLogSubscriptionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
