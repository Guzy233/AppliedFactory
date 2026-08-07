package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fulent.appliedfactory.script.FactoryActionResult;
import com.fulent.appliedfactory.script.FactoryScriptAction;

import com.google.gson.JsonParseException;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Executes one non-waiting script action against the controller's private cache. */
public final class FactoryActionExecutor {
    private final FactoryCellCache cache;
    private final Function<FactoryBusAddress, Optional<com.fulent.appliedfactory.part.FactoryBusPart>>
            busResolver;
    private final NetworkResolver networkResolver;
    private final Runnable changed;

    public FactoryActionExecutor(
            FactoryCellCache cache,
            Function<FactoryBusAddress,
                    Optional<com.fulent.appliedfactory.part.FactoryBusPart>> busResolver,
            NetworkResolver networkResolver,
            Runnable changed) {
        this.cache = cache;
        this.busResolver = busResolver;
        this.networkResolver = networkResolver;
        this.changed = changed;
    }

    public FactoryActionResult perform(FactoryJob job, FactoryScriptAction action) {
        return switch (action.type()) {
            case BUS_PUSH -> FactoryActionResult.pushed(pushToBus(job, action));
            case BUS_EXTRACT -> FactoryActionResult.extracted(extractFromBus(job, action));
            case BUS_DROP -> FactoryActionResult.booleanResult(dropFromBus(job, action));
            case BUS_USE -> FactoryActionResult.booleanResult(useBus(action));
            case BUS_PLACE -> FactoryActionResult.booleanResult(placeAtBus(job, action));
            case BUS_BREAK, BUS_BREAK_WITH -> FactoryActionResult.extracted(breakBus(job, action));
            case BUS_REDSTONE -> FactoryActionResult.booleanResult(setBusRedstone(action));
            case RENAME_OWNED -> FactoryActionResult.extracted(renameOwned(job, action));
            case NETWORK_PUSH -> {
                job.setRecoverySideIfAbsent(action.networkSide());
                yield FactoryActionResult.pushed(pushToNetwork(
                        action.networkSide(),
                        action.resources(),
                        job::canConsumeOwned,
                        job::consumeOwned));
            }
            case NETWORK_EXTRACT -> {
                job.setRecoverySideIfAbsent(action.networkSide());
                yield FactoryActionResult.extracted(
                        extractFromNetwork(job, action.networkSide(), action.resources()));
            }
            case SLEEP -> throw new IllegalStateException("SLEEP is handled by the scheduler");
        };
    }

    private boolean dropFromBus(FactoryJob job, FactoryScriptAction action) {
        var resources = action.resources();
        if (!job.canConsumeOwned(resources)) {
            throw new IllegalStateException("Workflow does not own bus drop resources");
        }
        var machine = resolvedMachine(action.bus());
        var stacks = toItemStacks(resources);
        if (machine == null || stacks == null || !cache.removeAll(resources)) {
            return false;
        }
        final boolean dropped;
        try {
            dropped = machine.throwItems(stacks);
        } catch (RuntimeException exception) {
            restoreCacheOrThrow(resources, "bus drop", exception);
            return false;
        }
        if (!dropped) {
            restoreCacheOrThrow(resources, "bus drop", null);
            return false;
        }
        job.consumeOwned(resources);
        changed.run();
        return true;
    }

    private boolean useBus(FactoryScriptAction action) {
        var machine = resolvedMachine(action.bus());
        return machine != null && machine.use();
    }

    private boolean placeAtBus(FactoryJob job, FactoryScriptAction action) {
        var resource = action.resources().get(0);
        if (!job.canConsumeOwned(action.resources())) {
            throw new IllegalStateException("Workflow does not own bus placement resource");
        }
        var machine = resolvedMachine(action.bus());
        var stacks = toItemStacks(action.resources());
        if (machine == null || stacks == null || stacks.size() != 1 || !cache.removeAll(action.resources())) {
            return false;
        }
        final boolean placed;
        try {
            placed = machine.place(stacks.get(0));
        } catch (RuntimeException exception) {
            restoreCacheOrThrow(action.resources(), "bus placement", exception);
            return false;
        }
        if (!placed) {
            restoreCacheOrThrow(action.resources(), "bus placement", null);
            return false;
        }
        job.consumeOwned(List.of(resource));
        changed.run();
        return true;
    }

    private List<FactoryResource> breakBus(FactoryJob job, FactoryScriptAction action) {
        var machine = resolvedMachine(action.bus());
        if (machine == null) {
            return List.of();
        }
        var tool = action.resources().isEmpty() ? null : breakTool(job, action);
        var preview = resources(machine.previewBreakDrops(tool));
        if (!cache.canStoreAll(preview)) {
            return List.of();
        }
        var result = machine.breakAndCollect(tool);
        if (!result.destroyed()) {
            return List.of();
        }
        var drops = resources(result.drops());
        if (!drops.isEmpty() && !cache.storeAll(drops)) {
            // A modded loot table may produce more than its preview. Preserve rather than delete it.
            machine.throwItems(result.drops());
            return List.of();
        }
        job.addOwned(drops);
        changed.run();
        return drops;
    }

    /**
     * Returns the owned item unit used as the held tool for
     * {@code BUS_BREAK_WITH}. The tool is only validated for ownership, never
     * consumed.
     */
    private ItemStack breakTool(FactoryJob job, FactoryScriptAction action) {
        if (!job.canConsumeOwned(action.resources())) {
            throw new IllegalStateException("Workflow does not own the bus break tool");
        }
        var stacks = toItemStacks(action.resources());
        if (stacks == null || stacks.size() != 1) {
            throw new IllegalStateException("Bus break tool must be a single item resource");
        }
        return stacks.get(0);
    }

    /**
     * Applies a custom display name to one owned item resource: the old key is
     * removed from the cache, every item of that amount is renamed, the renamed
     * key is stored back and the workflow ledger moves from the old to the new
     * key. Returns the renamed resources now owned by the workflow.
     */
    private List<FactoryResource> renameOwned(FactoryJob job, FactoryScriptAction action) {
        var resources = action.resources();
        if (!job.canConsumeOwned(resources)) {
            throw new IllegalStateException("Workflow does not own rename resource");
        }
        if (resources.size() != 1 || !(resources.get(0).key() instanceof AEItemKey)) {
            return List.of();
        }
        var stacks = toItemStacks(resources);
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        if (!cache.removeAll(resources)) {
            return List.of();
        }
        final List<FactoryResource> renamed;
        try {
            var component = parseName(action.name(), job.registries());
            var amounts = new LinkedHashMap<AEKey, Long>();
            for (var stack : stacks) {
                stack.set(DataComponents.CUSTOM_NAME, component);
                amounts.merge(AEItemKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
            renamed = fromAmounts(amounts);
            if (!cache.storeAll(renamed)) {
                restoreCacheOrThrow(resources, "rename", null);
                return List.of();
            }
        } catch (RuntimeException exception) {
            restoreCacheOrThrow(resources, "rename", exception);
            return List.of();
        }
        job.consumeOwned(resources);
        job.addOwned(renamed);
        changed.run();
        return renamed;
    }

    /** Parses a plain literal name, or a JSON serialized component when valid. */
    private static Component parseName(String name, HolderLookup.Provider registries) {
        try {
            var component = Component.Serializer.fromJson(name, registries);
            if (component != null) {
                return component;
            }
        } catch (JsonParseException | IllegalArgumentException ignored) {
            // Not a JSON component; treat as a plain literal name.
        }
        return Component.literal(name);
    }

    private boolean setBusRedstone(FactoryScriptAction action) {
        var bus = busResolver.apply(action.bus()).orElse(null);
        if (bus == null) {
            return false;
        }
        bus.setRedstoneOutput(action.redstoneLevel());
        changed.run();
        return true;
    }

    /**
     * Returns owned resources to a network side. Used for job recovery: the owned list is the
     * exact set being returned, so no ownership ledger is consulted.
     */
    public boolean returnOwned(List<FactoryResource> owned, Direction side) {
        return pushToNetwork(side, owned, resources -> true, resources -> {
        });
    }

    private boolean pushToBus(FactoryJob job, FactoryScriptAction action) {
        var resources = action.resources();
        if (!job.canConsumeOwned(resources)) {
            throw new IllegalStateException("Workflow does not own bus push resources");
        }
        var bus = busResolver.apply(action.bus()).orElse(null);
        var machine = bus == null ? null : bus.machine().orElse(null);
        var stacks = toItemStacks(resources);
        if (machine == null || stacks == null || !machine.canInsertAll(stacks)) {
            return false;
        }
        if (!cache.removeAll(resources)) {
            return false;
        }
        var rejected = machine.insertAll(stacks);
        if (!rejected.isEmpty()) {
            var rejectedResources = resources(rejected);
            if (!cache.storeAll(rejectedResources)) {
                machine.dropItems(rejected);
            }
            var inserted = subtract(resources, rejectedResources);
            if (!inserted.isEmpty()) {
                job.consumeOwned(inserted);
            }
            throw new IllegalStateException(
                    "Target item handler violated successful insertion simulation");
        }
        job.consumeOwned(resources);
        changed.run();
        return true;
    }

    private com.fulent.appliedfactory.factory.FactoryMachineAccess resolvedMachine(
            FactoryBusAddress address) {
        var bus = busResolver.apply(address).orElse(null);
        return bus == null ? null : bus.machine().orElse(null);
    }

    private void restoreCacheOrThrow(
            List<FactoryResource> resources, String operation, RuntimeException cause) {
        if (!cache.storeAll(resources)) {
            var failure = new IllegalStateException(
                    "Factory cache could not restore resources after failed " + operation, cause);
            throw failure;
        }
        if (cause != null) {
            throw cause;
        }
    }

    private List<FactoryResource> extractFromBus(
            FactoryJob job, FactoryScriptAction action) {
        var bus = busResolver.apply(action.bus()).orElse(null);
        var machine = bus == null ? null : bus.machine().orElse(null);
        if (machine == null) {
            return List.of();
        }
        var simulated = resources(machine.extractAll(true));
        if (simulated.isEmpty() || !cache.canStoreAll(simulated)) {
            return List.of();
        }
        var extractedStacks = machine.extractAll(false);
        var extracted = resources(extractedStacks);
        if (extracted.isEmpty()) {
            return List.of();
        }
        if (!cache.storeAll(extracted)) {
            var rejected = machine.insertAll(extractedStacks);
            if (!rejected.isEmpty()) {
                machine.dropItems(rejected);
            }
            return List.of();
        }
        job.addOwned(extracted);
        changed.run();
        return extracted;
    }

    private boolean pushToNetwork(
            Direction side,
            List<FactoryResource> resources,
            Predicate<List<FactoryResource>> canConsume,
            Consumer<List<FactoryResource>> consume) {
        if (!canConsume.test(resources)) {
            throw new IllegalStateException("Workflow does not own network push resources");
        }
        if (resources.isEmpty()) {
            return true;
        }
        var target = networkResolver.resolve(side).orElse(null);
        if (target == null || !canInsertAll(target, resources)) {
            return false;
        }
        if (!cache.removeAll(resources)) {
            return false;
        }
        var inserted = new ArrayList<FactoryResource>();
        for (var resource : resources) {
            var amount = target.storage.insert(
                    resource.key(), resource.amount(), Actionable.MODULATE, target.source);
            if (amount > 0) {
                inserted.add(new FactoryResource(resource.key(), amount));
            }
            if (amount != resource.amount()) {
                return recoverPartialNetworkPush(target, resources, inserted, consume);
            }
        }
        consume.accept(resources);
        changed.run();
        return true;
    }

    private boolean recoverPartialNetworkPush(
            NetworkEndpoint target,
            List<FactoryResource> requested,
            List<FactoryResource> inserted,
            Consumer<List<FactoryResource>> consume) {
        var recovered = new ArrayList<FactoryResource>();
        for (var resource : inserted) {
            var amount = target.storage.extract(
                    resource.key(), resource.amount(), Actionable.MODULATE, target.source);
            if (amount > 0) {
                recovered.add(new FactoryResource(resource.key(), amount));
            }
        }
        recovered.addAll(subtract(requested, inserted));
        if (!cache.storeAll(recovered)) {
            throw new IllegalStateException("Cannot restore a partially rejected network push");
        }
        var lost = subtract(requested, recovered);
        if (!lost.isEmpty()) {
            consume.accept(lost);
            throw new IllegalStateException("AE storage violated insertion simulation");
        }
        return false;
    }

    private List<FactoryResource> extractFromNetwork(
            FactoryJob job, Direction side, List<FactoryResource> requested) {
        var source = networkResolver.resolve(side).orElse(null);
        if (source == null || !canExtractAll(source, requested)
                || !cache.canStoreAll(requested)) {
            return List.of();
        }
        var extracted = new ArrayList<FactoryResource>();
        for (var resource : requested) {
            var amount = source.storage.extract(
                    resource.key(), resource.amount(), Actionable.MODULATE, source.source);
            if (amount > 0) {
                extracted.add(new FactoryResource(resource.key(), amount));
            }
            if (amount != resource.amount()) {
                rollbackNetworkExtraction(source, extracted);
                return List.of();
            }
        }
        if (!cache.storeAll(extracted)) {
            rollbackNetworkExtraction(source, extracted);
            return List.of();
        }
        job.addOwned(extracted);
        changed.run();
        return List.copyOf(extracted);
    }

    private static void rollbackNetworkExtraction(
            NetworkEndpoint storage, List<FactoryResource> extracted) {
        for (var resource : extracted) {
            var inserted = storage.storage.insert(
                    resource.key(), resource.amount(), Actionable.MODULATE, storage.source);
            if (inserted != resource.amount()) {
                throw new IllegalStateException("Cannot roll back partial AE network extraction");
            }
        }
    }

    private static boolean canInsertAll(
            NetworkEndpoint storage, List<FactoryResource> resources) {
        return resources.stream().allMatch(resource -> storage.storage.insert(
                resource.key(), resource.amount(), Actionable.SIMULATE, storage.source)
                == resource.amount());
    }

    private static boolean canExtractAll(
            NetworkEndpoint storage, List<FactoryResource> resources) {
        return resources.stream().allMatch(resource -> storage.storage.extract(
                resource.key(), resource.amount(), Actionable.SIMULATE, storage.source)
                == resource.amount());
    }

    private static List<FactoryResource> resources(List<ItemStack> stacks) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var stack : stacks) {
            if (!stack.isEmpty()) {
                amounts.merge(AEItemKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        return fromAmounts(amounts);
    }

    private static List<ItemStack> toItemStacks(List<FactoryResource> resources) {
        var result = new ArrayList<ItemStack>();
        for (var resource : resources) {
            if (!(resource.key() instanceof AEItemKey itemKey)) {
                return null;
            }
            var remaining = resource.amount();
            while (remaining > 0) {
                var amount = (int) Math.min(remaining, itemKey.getMaxStackSize());
                result.add(itemKey.toStack(amount));
                remaining -= amount;
            }
        }
        return List.copyOf(result);
    }

    private static List<FactoryResource> subtract(
            List<FactoryResource> current, List<FactoryResource> removed) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var resource : current) {
            amounts.merge(resource.key(), resource.amount(), Math::addExact);
        }
        for (var resource : removed) {
            var remaining = amounts.getOrDefault(resource.key(), 0L) - resource.amount();
            if (remaining < 0) {
                throw new IllegalStateException("Resource subtraction underflow");
            }
            amounts.put(resource.key(), remaining);
        }
        return fromAmounts(amounts);
    }

    private static List<FactoryResource> fromAmounts(LinkedHashMap<AEKey, Long> amounts) {
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    @FunctionalInterface
    public interface NetworkResolver {
        Optional<NetworkEndpoint> resolve(Direction side);
    }

    public record NetworkEndpoint(MEStorage storage, IActionSource source) {
    }
}
