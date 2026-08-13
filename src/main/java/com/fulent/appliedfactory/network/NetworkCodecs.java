package com.fulent.appliedfactory.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Shared custom stream codecs for MCP payloads. */
public final class NetworkCodecs {
    public static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID = StreamCodec.of(
            (buf, value) -> buf.writeLong(value.getMostSignificantBits())
                    .writeLong(value.getLeastSignificantBits()),
            buf -> new UUID(buf.readLong(), buf.readLong()));

    private NetworkCodecs() {
    }
}
