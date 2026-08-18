package com.fulent.appliedfactory.network;

import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client request to dump every recipe the client's JEI runtime can
 * normalize (via the ae2-jei-integration {@code IngredientConverters} registry).
 * The client answers with {@link RecipeDumpChunkPayload} chunks.
 */
public record RequestRecipeDumpPayload(UUID requestId) implements CustomPacketPayload {
    public static final Type<RequestRecipeDumpPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "recipe_dump_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRecipeDumpPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NetworkCodecs.UUID, RequestRecipeDumpPayload::requestId,
                    RequestRecipeDumpPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
