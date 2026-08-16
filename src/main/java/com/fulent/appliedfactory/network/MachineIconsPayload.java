package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client answer to {@link RequestMachineIconsPayload}. {@code entriesJson} is a
 * compact JSON object mapping recipe type id to the machine block/item ids the
 * client's JEI runtime reports as that type's catalysts (the machines shown in
 * the recipe tab). {@code available} is false when the client has no JEI
 * runtime, in which case the payload carries no usable data.
 */
public record MachineIconsPayload(
        UUID requestId,
        boolean available,
        String entriesJson) implements CustomPacketPayload {
    public static final int MAX_ENTRIES_CHARS = 96_000;

    public static final Type<MachineIconsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "machine_icons"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineIconsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, MachineIconsPayload::requestId,
                    ByteBufCodecs.BOOL, MachineIconsPayload::available,
                    ByteBufCodecs.stringUtf8(MAX_ENTRIES_CHARS), MachineIconsPayload::entriesJson,
                    MachineIconsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
