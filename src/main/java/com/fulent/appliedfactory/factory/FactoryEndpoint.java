package com.fulent.appliedfactory.factory;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;

/** A durable address for a resource endpoint. It never holds a live storage capability. */
public record FactoryEndpoint(
        Kind kind,
        @Nullable Direction networkSide,
        @Nullable FactoryBusAddress bus) {

    public FactoryEndpoint {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.NETWORK && networkSide == null) {
            throw new IllegalArgumentException("Network endpoint requires a side");
        }
        if (kind == Kind.BUS && bus == null) {
            throw new IllegalArgumentException("Bus endpoint requires an address");
        }
    }

    public static FactoryEndpoint network(Direction side) {
        return new FactoryEndpoint(Kind.NETWORK, Objects.requireNonNull(side), null);
    }

    public static FactoryEndpoint bus(FactoryBusAddress address) {
        return new FactoryEndpoint(Kind.BUS, null, Objects.requireNonNull(address));
    }

    public enum Kind {
        NETWORK,
        BUS
    }
}
