package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One chunk of the client-side recipe dump. {@code entriesJson} is a compact
 * JSON array of {@code {id, type, inputs, outputs}} entries; inputs use the
 * {@code {channel, key, amount, options?}} slot shape and outputs the
 * {@code {channel, key, amount}} shape. {@code available} is false when the
 * client has no JEI runtime or no converters, in which case the dump is empty.
 */
public record RecipeDumpChunkPayload(
        UUID requestId,
        int chunkIndex,
        int totalChunks,
        boolean available,
        String entriesJson) implements CustomPacketPayload {
    public static final int MAX_CHUNK_CHARS = 48_000;

    public static final Type<RecipeDumpChunkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "recipe_dump_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeDumpChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, RecipeDumpChunkPayload::requestId,
                    ByteBufCodecs.VAR_INT, RecipeDumpChunkPayload::chunkIndex,
                    ByteBufCodecs.VAR_INT, RecipeDumpChunkPayload::totalChunks,
                    ByteBufCodecs.BOOL, RecipeDumpChunkPayload::available,
                    ByteBufCodecs.stringUtf8(MAX_CHUNK_CHARS), RecipeDumpChunkPayload::entriesJson,
                    RecipeDumpChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
