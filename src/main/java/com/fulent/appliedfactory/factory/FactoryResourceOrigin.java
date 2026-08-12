package com.fulent.appliedfactory.factory;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/** Source of a resource selection: either an external endpoint or one order's private escrow. */
public record FactoryResourceOrigin(
        Kind kind,
        @Nullable FactoryEndpoint endpoint,
        @Nullable UUID escrowId) {

    public FactoryResourceOrigin {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ENDPOINT && endpoint == null) {
            throw new IllegalArgumentException("Endpoint origin requires an endpoint");
        }
        if (kind == Kind.ESCROW && escrowId == null) {
            throw new IllegalArgumentException("Escrow origin requires an allocation id");
        }
    }

    public static FactoryResourceOrigin endpoint(FactoryEndpoint endpoint) {
        return new FactoryResourceOrigin(Kind.ENDPOINT, Objects.requireNonNull(endpoint), null);
    }

    public static FactoryResourceOrigin escrow(UUID allocationId) {
        return new FactoryResourceOrigin(Kind.ESCROW, null, Objects.requireNonNull(allocationId));
    }

    public enum Kind {
        ENDPOINT,
        ESCROW
    }
}
