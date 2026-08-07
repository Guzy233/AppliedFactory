package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Leftover owned resources of a job that ended while its recovery network was offline or
 * full. {@link FactoryProgram} retries each entry every step until the resources are
 * returned, then drops it. This is the only remnant a finished job may leave behind — a
 * job itself exists only while suspended.
 */
public final class RecoveryEntry {
    private static final String ID_TAG = "Id";
    private static final String OWNED_TAG = "Owned";
    private static final String RECOVERY_SIDE_TAG = "RecoverySide";

    private final UUID id;
    private final List<FactoryResource> owned;
    @Nullable
    private final Direction recoverySide;

    public RecoveryEntry(
            UUID id, List<FactoryResource> owned, @Nullable Direction recoverySide) {
        this.id = Objects.requireNonNull(id, "id");
        this.owned = normalize(owned);
        this.recoverySide = recoverySide;
    }

    public UUID id() {
        return id;
    }

    public List<FactoryResource> owned() {
        return owned;
    }

    @Nullable
    public Direction recoverySide() {
        return recoverySide;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putUUID(ID_TAG, id);
        tag.put(OWNED_TAG, saveResources(owned, registries));
        if (recoverySide != null) {
            tag.putString(RECOVERY_SIDE_TAG, recoverySide.getName());
        }
        return tag;
    }

    public static Optional<RecoveryEntry> load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(ID_TAG) || !isCompoundList(tag, OWNED_TAG)) {
            return Optional.empty();
        }
        var owned = loadResources(tag.getList(OWNED_TAG, Tag.TAG_COMPOUND), registries);
        if (owned.isEmpty() || owned.get().isEmpty()) {
            return Optional.empty();
        }
        var side = tag.contains(RECOVERY_SIDE_TAG, Tag.TAG_STRING)
                ? Direction.byName(tag.getString(RECOVERY_SIDE_TAG))
                : null;
        return Optional.of(new RecoveryEntry(tag.getUUID(ID_TAG), owned.get(), side));
    }

    private static List<FactoryResource> normalize(List<FactoryResource> resources) {
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

    private static ListTag saveResources(
            List<FactoryResource> resources, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var resource : resources) {
            list.add(resource.save(registries));
        }
        return list;
    }

    private static Optional<List<FactoryResource>> loadResources(
            ListTag list, HolderLookup.Provider registries) {
        var result = new ArrayList<FactoryResource>(list.size());
        for (int index = 0; index < list.size(); index++) {
            var resource = FactoryResource.load(list.getCompound(index), registries);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            result.add(resource.get());
        }
        try {
            return Optional.of(normalize(result));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static boolean isCompoundList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            return false;
        }
        var list = (ListTag) tag.get(key);
        return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
    }
}
