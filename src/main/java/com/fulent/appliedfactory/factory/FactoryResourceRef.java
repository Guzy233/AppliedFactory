package com.fulent.appliedfactory.factory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import appeng.api.stacks.AEKey;

/** Immutable concrete resource bundle together with the address it should be taken from. */
public record FactoryResourceRef(
        FactoryResourceOrigin origin,
        List<FactoryResource> bundle) {

    public FactoryResourceRef {
        Objects.requireNonNull(origin, "origin");
        bundle = normalize(bundle);
    }

    public boolean isEmpty() {
        return bundle.isEmpty();
    }

    public FactoryResourceRef withBundle(List<FactoryResource> resources) {
        return new FactoryResourceRef(origin, resources);
    }

    public static List<FactoryResource> normalize(List<FactoryResource> resources) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var resource : resources) {
            Objects.requireNonNull(resource, "resource");
            amounts.merge(resource.key(), resource.amount(), Math::addExact);
        }
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static List<FactoryResource> subtract(
            List<FactoryResource> current,
            List<FactoryResource> removed) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var resource : normalize(current)) {
            amounts.put(resource.key(), resource.amount());
        }
        for (var resource : normalize(removed)) {
            var left = amounts.getOrDefault(resource.key(), 0L) - resource.amount();
            if (left < 0) {
                throw new IllegalArgumentException("Resource subtraction underflow");
            }
            if (left == 0) {
                amounts.remove(resource.key());
            } else {
                amounts.put(resource.key(), left);
            }
        }
        return amounts.entrySet().stream()
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }
}
