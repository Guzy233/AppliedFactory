package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-client result of a production program upload. */
public record UploadResultPayload(UUID requestId, boolean ok, String message)
        implements CustomPacketPayload {
    public static final Type<UploadResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "mcp_upload_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, UploadResultPayload::requestId,
                    ByteBufCodecs.BOOL, UploadResultPayload::ok,
                    ByteBufCodecs.stringUtf8(2048), UploadResultPayload::message,
                    UploadResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
