package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Controller-internal authoritative storage for AE processing inputs and rollback recovery.
 * Allocations are isolated by workflow id and are never mounted as AE storage.
 */
public final class FactoryEscrow {
    private static final String ID_TAG = "Id";
    private static final String SIDE_TAG = "RecoverySide";
    private static final String RESOURCES_TAG = "Resources";

    private final Map<UUID, Allocation> allocations = new LinkedHashMap<>();
    private final Runnable changed;

    public FactoryEscrow(Runnable changed) {
        this.changed = changed;
    }

    public boolean create(UUID id, Direction recoverySide, List<FactoryResource> resources) {
        var normalized = FactoryResourceRef.normalize(resources);
        if (normalized.isEmpty() || allocations.containsKey(id)) {
            return false;
        }
        allocations.put(id, new Allocation(recoverySide, normalized));
        changed.run();
        return true;
    }

    public List<FactoryResource> contents(UUID id) {
        var allocation = allocations.get(id);
        return allocation == null ? List.of() : allocation.resources();
    }

    public Direction recoverySide(UUID id) {
        var allocation = allocations.get(id);
        return allocation == null ? null : allocation.recoverySide();
    }

    public Set<UUID> allocationIds() {
        return Set.copyOf(allocations.keySet());
    }

    public long available(UUID id, AEKey key) {
        return contents(id).stream()
                .filter(resource -> resource.key().equals(key))
                .mapToLong(FactoryResource::amount)
                .sum();
    }

    public long extract(UUID id, AEKey key, long amount, boolean simulate) {
        var available = Math.min(amount, available(id, key));
        if (available <= 0 || simulate) {
            return available;
        }
        var allocation = allocations.get(id);
        var removed = List.of(new FactoryResource(key, available));
        var remaining = FactoryResourceRef.subtract(allocation.resources(), removed);
        // Keep an empty allocation until its workflow ends. Its recovery side is needed if a
        // target violates simulation and the just-extracted material must be rolled back.
        allocations.put(id, new Allocation(allocation.recoverySide(), remaining));
        changed.run();
        return available;
    }

    public long insert(UUID id, AEKey key, long amount) {
        if (amount <= 0) {
            return 0;
        }
        var allocation = allocations.get(id);
        if (allocation == null) {
            return 0;
        }
        var resources = new ArrayList<>(allocation.resources());
        resources.add(new FactoryResource(key, amount));
        allocations.put(id, new Allocation(
                allocation.recoverySide(), FactoryResourceRef.normalize(resources)));
        changed.run();
        return amount;
    }

    /** Adds emergency rollback material, creating an allocation when the workflow had none. */
    public void recover(
            UUID id,
            Direction recoverySide,
            List<FactoryResource> recovered) {
        var normalized = FactoryResourceRef.normalize(recovered);
        if (normalized.isEmpty()) {
            return;
        }
        var allocation = allocations.get(id);
        var resources = new ArrayList<FactoryResource>();
        if (allocation != null) {
            resources.addAll(allocation.resources());
        }
        resources.addAll(normalized);
        allocations.put(id, new Allocation(
                allocation == null ? recoverySide : allocation.recoverySide(),
                FactoryResourceRef.normalize(resources)));
        changed.run();
    }

    public void remove(UUID id) {
        if (allocations.remove(id) != null) {
            changed.run();
        }
    }

    public void clear() {
        if (!allocations.isEmpty()) {
            allocations.clear();
            changed.run();
        }
    }

    public List<FactoryResource> allContents() {
        return FactoryResourceRef.normalize(allocations.values().stream()
                .flatMap(allocation -> allocation.resources().stream())
                .toList());
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        var root = new CompoundTag();
        var entries = new ListTag();
        allocations.forEach((id, allocation) -> {
            var tag = new CompoundTag();
            tag.putUUID(ID_TAG, id);
            tag.putString(SIDE_TAG, allocation.recoverySide().getName());
            var resources = new ListTag();
            allocation.resources().forEach(resource -> resources.add(resource.save(registries)));
            tag.put(RESOURCES_TAG, resources);
            entries.add(tag);
        });
        root.put("Allocations", entries);
        return root;
    }

    public void load(CompoundTag root, HolderLookup.Provider registries) {
        allocations.clear();
        var entries = root.getList("Allocations", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            var tag = entries.getCompound(index);
            if (!tag.hasUUID(ID_TAG)) {
                continue;
            }
            var side = Direction.byName(tag.getString(SIDE_TAG));
            if (side == null) {
                continue;
            }
            var resources = new ArrayList<FactoryResource>();
            var saved = tag.getList(RESOURCES_TAG, Tag.TAG_COMPOUND);
            var valid = true;
            for (int resourceIndex = 0; resourceIndex < saved.size(); resourceIndex++) {
                Optional<FactoryResource> resource = FactoryResource.load(
                        saved.getCompound(resourceIndex), registries);
                if (resource.isEmpty()) {
                    valid = false;
                    break;
                }
                resources.add(resource.get());
            }
            if (valid && !resources.isEmpty()) {
                allocations.put(tag.getUUID(ID_TAG),
                        new Allocation(side, FactoryResourceRef.normalize(resources)));
            }
        }
    }

    private record Allocation(Direction recoverySide, List<FactoryResource> resources) {
        private Allocation {
            resources = List.copyOf(resources);
        }
    }
}
