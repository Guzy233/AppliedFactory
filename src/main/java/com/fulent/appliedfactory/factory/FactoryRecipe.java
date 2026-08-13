package com.fulent.appliedfactory.factory;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of one processing recipe exposed to scripts. {@code json}
 * is the Gson-decoded raw recipe JSON (plain maps/lists/primitives) so scripts
 * can pick the concrete input/output keys themselves.
 */
public record FactoryRecipe(
        String id,
        String type,
        @Nullable String machine,
        @Nullable Object json) {
}
