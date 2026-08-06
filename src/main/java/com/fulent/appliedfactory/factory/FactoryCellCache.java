package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Private controller storage backed by real AE storage cells.
 *
 * <p>The cache is deliberately not mounted on any attached grid. Factory jobs
 * keep logical ownership in their ledgers while the actual resources live in
 * the installed cells.</p>
 */
public final class FactoryCellCache {
    private static final IActionSource ACTION_SOURCE = IActionSource.empty();

    private final Runnable changed;
    private final ItemStackHandler inventory;

    public FactoryCellCache(int slots, Runnable changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
        inventory = new ItemStackHandler(slots) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return isEmptyStorageCell(stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                FactoryCellCache.this.changed.run();
            }
        };
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public boolean hasStorageCell() {
        return !cells().isEmpty();
    }

    /** Only empty cells are accepted; partition configuration is preserved. */
    public boolean isEmptyStorageCell(ItemStack stack) {
        var cell = StorageCells.getCellInventory(stack, changed::run);
        return cell != null && cell.getAvailableStacks().isEmpty();
    }

    public boolean canStoreAll(List<FactoryResource> resources) {
        var inserted = insert(resources);
        if (inserted == null) {
            return false;
        }
        return rollbackInsert(inserted);
    }

    /** Stores a complete resource list or restores the original cell contents. */
    public boolean storeAll(List<FactoryResource> resources) {
        var inserted = insert(resources);
        if (inserted == null) {
            return false;
        }
        persist(inserted);
        changed.run();
        return true;
    }

    /** Removes a complete resource list or restores the original cell contents. */
    public boolean removeAll(List<FactoryResource> resources) {
        var removed = new ArrayList<Allocation>();
        for (var resource : resources) {
            var remaining = resource.amount();
            for (var cell : cells()) {
                if (remaining <= 0) {
                    break;
                }
                var extracted = cell.extract(
                        resource.key(), remaining, Actionable.MODULATE, ACTION_SOURCE);
                if (extracted > 0) {
                    removed.add(new Allocation(cell, resource.key(), extracted));
                    remaining -= extracted;
                }
            }
            if (remaining > 0) {
                if (!rollbackRemoval(removed)) {
                    throw new IllegalStateException(
                            "Factory cache could not restore a failed resource removal");
                }
                return false;
            }
        }
        persist(removed);
        changed.run();
        return true;
    }

    public List<FactoryResource> contents() {
        var amounts = new appeng.api.stacks.KeyCounter();
        for (var cell : cells()) {
            cell.getAvailableStacks(amounts);
        }
        var result = new ArrayList<FactoryResource>();
        for (var entry : amounts) {
            if (entry.getLongValue() > 0) {
                result.add(new FactoryResource(entry.getKey(), entry.getLongValue()));
            }
        }
        return List.copyOf(result);
    }

    public void persistCells() {
        for (var cell : cells()) {
            cell.persist();
        }
    }

    private List<Allocation> insert(List<FactoryResource> resources) {
        var inserted = new ArrayList<Allocation>();
        for (var resource : resources) {
            var remaining = resource.amount();
            for (var cell : cells()) {
                if (remaining <= 0) {
                    break;
                }

                var before = cell.getAvailableStacks().get(resource.key());
                cell.insert(resource.key(), remaining, Actionable.MODULATE, ACTION_SOURCE);
                var after = cell.getAvailableStacks().get(resource.key());
                var actual = Math.max(0, after - before);
                if (actual > 0) {
                    inserted.add(new Allocation(cell, resource.key(), actual));
                    remaining -= actual;
                }
            }
            if (remaining > 0) {
                if (!rollbackInsert(inserted)) {
                    throw new IllegalStateException(
                            "Factory cache could not restore a failed resource insertion");
                }
                return null;
            }
        }
        return inserted;
    }

    private boolean rollbackInsert(List<Allocation> allocations) {
        var complete = true;
        for (int index = allocations.size() - 1; index >= 0; index--) {
            var allocation = allocations.get(index);
            var extracted = allocation.cell().extract(
                    allocation.key(), allocation.amount(), Actionable.MODULATE, ACTION_SOURCE);
            complete &= extracted == allocation.amount();
        }
        persist(allocations);
        changed.run();
        return complete;
    }

    private boolean rollbackRemoval(List<Allocation> allocations) {
        var complete = true;
        for (int index = allocations.size() - 1; index >= 0; index--) {
            var allocation = allocations.get(index);
            var inserted = allocation.cell().insert(
                    allocation.key(), allocation.amount(), Actionable.MODULATE, ACTION_SOURCE);
            complete &= inserted == allocation.amount();
        }
        persist(allocations);
        changed.run();
        return complete;
    }

    /**
     * Flushes the exact cell instances mutated by an operation.
     *
     * <p>AE2 cell inventories buffer their contents in the inventory object and only write that buffer
     * to the backing item stack from {@link StorageCell#persist()}. Re-resolving the item stack here
     * would create a fresh inventory with the old NBT and silently discard the mutation.</p>
     */
    private static void persist(List<Allocation> allocations) {
        for (var allocation : allocations) {
            allocation.cell().persist();
        }
    }

    private List<StorageCell> cells() {
        var result = new ArrayList<StorageCell>(inventory.getSlots());
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var cell = StorageCells.getCellInventory(
                    inventory.getStackInSlot(slot), changed::run);
            if (cell != null) {
                result.add(cell);
            }
        }
        return result;
    }

    private record Allocation(StorageCell cell, AEKey key, long amount) {
    }
}
