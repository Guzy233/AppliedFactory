package com.fulent.appliedfactory.factory;

import java.util.List;

/** Result of exactly one transfer attempt. */
public record FactoryTransferResult(
        boolean completed,
        List<FactoryResource> remaining) {

    public FactoryTransferResult {
        remaining = FactoryResourceRef.normalize(remaining);
        if (completed != remaining.isEmpty()) {
            throw new IllegalArgumentException("Completed transfer must have no remainder");
        }
    }

    public static FactoryTransferResult waiting(List<FactoryResource> remaining) {
        return new FactoryTransferResult(false, remaining);
    }

    public static FactoryTransferResult complete() {
        return new FactoryTransferResult(true, List.of());
    }
}
