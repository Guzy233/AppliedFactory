package com.fulent.appliedfactory.script;

import java.util.List;

import com.fulent.appliedfactory.factory.FactoryResource;

/** Value passed back into the suspended API call after one world-operation attempt. */
public record FactoryActionResult(Kind kind, boolean success, List<FactoryResource> resources) {
    public FactoryActionResult {
        resources = List.copyOf(resources);
        if (kind != Kind.BOOLEAN && success) {
            throw new IllegalArgumentException("Only BOOLEAN results carry a success flag");
        }
        if (kind != Kind.RESOURCES && !resources.isEmpty()) {
            throw new IllegalArgumentException("Only RESOURCES results carry resources");
        }
    }

    public static FactoryActionResult pushed(boolean success) {
        return new FactoryActionResult(Kind.BOOLEAN, success, List.of());
    }

    public static FactoryActionResult booleanResult(boolean success) {
        return new FactoryActionResult(Kind.BOOLEAN, success, List.of());
    }

    public static FactoryActionResult extracted(List<FactoryResource> resources) {
        return new FactoryActionResult(Kind.RESOURCES, false, resources);
    }

    public static FactoryActionResult slept() {
        return new FactoryActionResult(Kind.VOID, false, List.of());
    }

    public enum Kind {
        BOOLEAN,
        RESOURCES,
        VOID
    }
}
