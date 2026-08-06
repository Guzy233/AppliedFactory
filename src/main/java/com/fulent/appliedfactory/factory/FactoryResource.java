package com.fulent.appliedfactory.factory;

import java.util.Objects;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** An exact AE resource key and amount owned by a factory job. */
public record FactoryResource(AEKey key, long amount) {
    private static final String KEY_TAG = "Key";
    private static final String AMOUNT_TAG = "Amount";

    public FactoryResource {
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("Factory resource amount must be positive");
        }
    }

    public ResourceLocation id() {
        return key.getId();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.put(KEY_TAG, key.toTagGeneric(registries));
        tag.putLong(AMOUNT_TAG, amount);
        return tag;
    }

    public static Optional<FactoryResource> load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(KEY_TAG, Tag.TAG_COMPOUND)
                || !tag.contains(AMOUNT_TAG, Tag.TAG_LONG)) {
            return Optional.empty();
        }
        var key = AEKey.fromTagGeneric(registries, tag.getCompound(KEY_TAG));
        var amount = tag.getLong(AMOUNT_TAG);
        if (key == null || amount <= 0) {
            return Optional.empty();
        }
        return Optional.of(new FactoryResource(key, amount));
    }
}
