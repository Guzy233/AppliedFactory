package com.fulent.appliedfactory.factory;

import java.util.List;
import java.util.Objects;

/** A source-to-target transfer whose mutable progress belongs to the action. */
public final class FactoryTransferAction implements FactoryAction {
    private final FactoryResourceOrigin source;
    private final FactoryEndpoint target;
    private final Mode mode;
    private List<FactoryResource> remaining;

    public FactoryTransferAction(
            FactoryResourceOrigin source,
            FactoryEndpoint target,
            List<FactoryResource> remaining,
            Mode mode) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.remaining = FactoryResourceRef.normalize(remaining);
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public FactoryResourceOrigin source() {
        return source;
    }

    public FactoryEndpoint target() {
        return target;
    }

    public List<FactoryResource> remaining() {
        return remaining;
    }

    public Mode mode() {
        return mode;
    }

    void updateRemaining(List<FactoryResource> resources) {
        remaining = FactoryResourceRef.normalize(resources);
    }

    public enum Mode {
        PARTIAL,
        EXACT
    }
}
