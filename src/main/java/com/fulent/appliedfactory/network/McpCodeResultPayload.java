package com.fulent.appliedfactory.network;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-client result of one MCP probe run. */
public record McpCodeResultPayload(
        UUID requestId,
        String reason,
        String message,
        List<String> logs,
        Optional<String> resultJson,
        List<String> pending,
        long elapsedTicks,
        long steps) implements CustomPacketPayload {
    public static final Type<McpCodeResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "mcp_code_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, McpCodeResultPayload> STREAM_CODEC =
            StreamCodec.of(McpCodeResultPayload::encode, McpCodeResultPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, McpCodeResultPayload payload) {
        NetworkCodecs.UUID.encode(buf, payload.requestId());
        ByteBufCodecs.stringUtf8(16).encode(buf, payload.reason());
        ByteBufCodecs.stringUtf8(8192).encode(buf, payload.message());
        ByteBufCodecs.stringUtf8(50_000).apply(ByteBufCodecs.list(20_000))
                .encode(buf, payload.logs());
        ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(130_000)).encode(buf, payload.resultJson());
        ByteBufCodecs.stringUtf8(50_000).apply(ByteBufCodecs.list(200))
                .encode(buf, payload.pending());
        ByteBufCodecs.VAR_LONG.encode(buf, payload.elapsedTicks());
        ByteBufCodecs.VAR_LONG.encode(buf, payload.steps());
    }

    private static McpCodeResultPayload decode(RegistryFriendlyByteBuf buf) {
        return new McpCodeResultPayload(
                NetworkCodecs.UUID.decode(buf),
                ByteBufCodecs.stringUtf8(16).decode(buf),
                ByteBufCodecs.stringUtf8(8192).decode(buf),
                ByteBufCodecs.stringUtf8(50_000).apply(ByteBufCodecs.list(20_000)).decode(buf),
                ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(130_000)).decode(buf),
                ByteBufCodecs.stringUtf8(50_000).apply(ByteBufCodecs.list(200)).decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
