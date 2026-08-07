package com.fulent.appliedfactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Thread-local holder for the AE crafting request a CPU is currently pushing patterns for. Set by
 * {@code CraftingCpuLogicMixin} around {@code CraftingCpuLogic.executeCrafting} and read by the
 * factory controller's {@code pushPattern}, so every job created during that push can be linked to
 * the requesting crafting request (identified by its {@link UUID}). Everything runs on the server
 * thread, so a thread-local is safe.
 *
 * <p>Deliberately lives outside the {@code com.fulent.appliedfactory.mixin} package: mixin
 * forbids referencing non-mixin classes from within a mixin package.
 */
public final class CraftingRequestContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CraftingRequestContext() {
    }

    public static void set(@Nullable UUID craftingId) {
        if (craftingId == null) {
            clear();
        } else {
            CURRENT.set(craftingId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Nullable
    public static UUID current() {
        return CURRENT.get();
    }
}
