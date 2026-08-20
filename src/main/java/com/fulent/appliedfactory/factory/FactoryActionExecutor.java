package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonParseException;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Resolves durable handles for each attempt and moves resources without a controller cell. */
public final class FactoryActionExecutor {
    private static final IActionSource BUS_SOURCE = IActionSource.empty();

    private final FactoryEscrow escrow;
    private final BusResolver busResolver;
    private final NetworkResolver networkResolver;
    private final Function<FactoryBusAddress, Direction> busRecoverySide;
    private final Runnable changed;

    public FactoryActionExecutor(
            FactoryEscrow escrow,
            BusResolver busResolver,
            NetworkResolver networkResolver,
            Function<FactoryBusAddress, Direction> busRecoverySide,
            Runnable changed) {
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        this.busResolver = Objects.requireNonNull(busResolver, "busResolver");
        this.networkResolver = Objects.requireNonNull(networkResolver, "networkResolver");
        this.busRecoverySide = Objects.requireNonNull(busRecoverySide, "busRecoverySide");
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    public FactoryTransferResult perform(UUID workflowId, FactoryTransferAction action) {
        if (action.source().kind() == FactoryResourceOrigin.Kind.ESCROW
                && !workflowId.equals(action.source().escrowId())) {
            throw new IllegalStateException("A workflow cannot transfer another workflow's escrow");
        }
        if (action.remaining().isEmpty()) {
            return FactoryTransferResult.complete();
        }
        var result = action.mode() == FactoryTransferAction.Mode.EXACT
                ? exact(workflowId, action)
                : partial(workflowId, action);
        action.updateRemaining(result.remaining());
        return result;
    }

    /** Immediately renames the exact item selection in its source. */
    public Optional<FactoryResourceRef> rename(
            UUID workflowId,
            FactoryResourceRef input,
            String name,
            HolderLookup.Provider registries) {
        validateWorkflowOrigin(workflowId, input);
        if (name.isBlank() || input.bundle().size() != 1
                || !(input.bundle().getFirst().key() instanceof AEItemKey itemKey)) {
            throw new IllegalArgumentException("rename requires one exact item resource and a name");
        }
        var stack = itemKey.toStack(1);
        stack.set(DataComponents.CUSTOM_NAME, parseName(name, registries));
        var renamed = List.of(new FactoryResource(
                AEItemKey.of(stack), input.bundle().getFirst().amount()));
        var extracted = extractExact(workflowId, input);
        if (extracted == null) {
            return Optional.empty();
        }
        storeAtSourceOrRecover(
                workflowId, input.origin(), recoverySide(input.origin(), null), renamed);
        changed.run();
        return Optional.of(new FactoryResourceRef(input.origin(), renamed));
    }

    /** Immediately removes an exact item selection and emits it from a bus. */
    public boolean drop(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef input) {
        validateWorkflowOrigin(workflowId, input);
        var target = busResolver.resolve(bus).orElse(null);
        var stacks = itemStacks(input.bundle());
        if (target == null || stacks == null) {
            return false;
        }
        var extracted = extractExact(workflowId, input);
        if (extracted == null) {
            return false;
        }
        try {
            if (!target.throwItems(stacks)) {
                restoreSource(workflowId, input.origin(),
                        recoverySide(input.origin(), bus), extracted);
                return false;
            }
        } catch (RuntimeException exception) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            throw exception;
        }
        changed.run();
        return true;
    }

    /** Current concrete contents of an external endpoint (extractable only). */
    public List<FactoryResource> available(FactoryEndpoint endpoint) {
        return available(endpoint, null);
    }

    /** Current extractable contents, optionally limited to one AE key channel. */
    public List<FactoryResource> available(
            FactoryEndpoint endpoint, @Nullable AEKeyType channel) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        if (endpoint.kind() == FactoryEndpoint.Kind.NETWORK) {
            collectNetwork(amounts, endpoint);
        } else {
            var bus = busResolver.resolve(endpoint.bus()).orElse(null);
            if (bus != null) {
                if (channel != null) {
                    var storage = bus.storage(channel);
                    if (storage != null) {
                        collect(storage, amounts);
                    }
                } else {
                    for (var storage : bus.storages().values()) {
                        collect(storage, amounts);
                    }
                }
            }
        }
        return toResources(amounts, channel);
    }

    /**
     * Current full contents of an external endpoint: for a bus target, the
     * block's <em>whole container</em> (all slots, queried without a face), so
     * machine inputs that reject extraction are visible too. Network endpoints
     * have no such slots, so this equals {@link #available(FactoryEndpoint)}
     * there. Actions created from non-extractable entries wait exactly like
     * entries that do not exist.
     */
    public List<FactoryResource> storage(FactoryEndpoint endpoint) {
        return storage(endpoint, null);
    }

    /** Full endpoint contents, optionally limited to one AE key channel. */
    public List<FactoryResource> storage(
            FactoryEndpoint endpoint, @Nullable AEKeyType channel) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        if (endpoint.kind() == FactoryEndpoint.Kind.NETWORK) {
            collectNetwork(amounts, endpoint);
        } else {
            var bus = busResolver.resolve(endpoint.bus()).orElse(null);
            if (bus != null) {
                if (channel != null) {
                    var storage = bus.storageAll(channel);
                    if (storage != null) {
                        collect(storage, amounts);
                    }
                } else {
                    for (var storage : bus.storagesAll().values()) {
                        collect(storage, amounts);
                    }
                }
            }
        }
        return toResources(amounts, channel);
    }

    private void collectNetwork(LinkedHashMap<AEKey, Long> amounts, FactoryEndpoint endpoint) {
        var network = networkResolver.resolve(endpoint.networkSide()).orElse(null);
        if (network != null) {
            collect(network.storage(), amounts);
        }
    }

    private static List<FactoryResource> toResources(
            LinkedHashMap<AEKey, Long> amounts, @Nullable AEKeyType channel) {
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .filter(entry -> channel == null || entry.getKey().getType().equals(channel))
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    public long available(FactoryResourceOrigin origin, AEKey key) {
        var access = source(origin, key);
        return access == null ? 0 : access.extract(key, Long.MAX_VALUE, true);
    }

    /** Tries to return one orphaned order allocation to its preferred network. */
    public boolean recoverEscrow(UUID allocationId) {
        var resources = escrow.contents(allocationId);
        if (resources.isEmpty()) {
            escrow.remove(allocationId);
            return true;
        }
        var side = escrow.recoverySide(allocationId);
        if (side == null) {
            return false;
        }
        var result = perform(allocationId, new FactoryTransferAction(
                FactoryResourceOrigin.escrow(allocationId),
                FactoryEndpoint.network(side),
                resources,
                FactoryTransferAction.Mode.PARTIAL));
        if (result.completed()) {
            escrow.remove(allocationId);
        }
        return result.completed();
    }

    /** Immediately attempts an empty-hand use on the bus target. */
    public boolean use(FactoryBusAddress bus, boolean shift) {
        var target = busResolver.resolve(bus).orElse(null);
        return target != null && target.use(shift);
    }

    /** Immediately attempts one item use and writes its remainder to the source. */
    public boolean use(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef input, boolean shift) {
        validateWorkflowOrigin(workflowId, input);
        requireSingleItem(input, false);
        var target = busResolver.resolve(bus).orElse(null);
        if (target == null) {
            return false;
        }
        var extracted = extractExact(workflowId, input);
        if (extracted == null) {
            return false;
        }
        var held = singleItem(extracted);
        FactoryBusTarget.ItemUseResult result;
        try {
            result = target.useItem(held, shift);
        } catch (RuntimeException exception) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            throw exception;
        }
        if (!result.successful()) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            return false;
        }
        var remainder = itemResources(List.of(result.remainder()));
        storeAtSourceOrRecover(workflowId, input.origin(),
                recoverySide(input.origin(), bus), remainder);
        changed.run();
        return true;
    }

    /** Immediately attempts one block-item placement. */
    public boolean place(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef input, boolean shift) {
        validateWorkflowOrigin(workflowId, input);
        requireSingleItem(input, true);
        var target = busResolver.resolve(bus).orElse(null);
        if (target == null) {
            return false;
        }
        var extracted = extractExact(workflowId, input);
        if (extracted == null) {
            return false;
        }
        FactoryBusTarget.ItemUseResult result;
        try {
            result = target.placeAndCollect(singleItem(extracted), shift);
        } catch (RuntimeException exception) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            throw exception;
        }
        if (!result.successful()) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            return false;
        }
        var remainder = itemResources(List.of(result.remainder()));
        storeAtSourceOrRecover(workflowId, input.origin(),
                recoverySide(input.origin(), bus), remainder);
        changed.run();
        return true;
    }

    /** Immediately breaks one block and writes the damaged tool and drops to its source. */
    public Optional<FactoryResourceRef> breakBlock(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef input) {
        validateWorkflowOrigin(workflowId, input);
        requireSingleItem(input, false);
        var target = busResolver.resolve(bus).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        var extracted = extractExact(workflowId, input);
        if (extracted == null) {
            return Optional.empty();
        }
        FactoryBusTarget.BreakResult result;
        try {
            result = target.breakAndCollect(singleItem(extracted));
        } catch (RuntimeException exception) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            throw exception;
        }
        if (!result.destroyed()) {
            restoreSource(workflowId, input.origin(),
                    recoverySide(input.origin(), bus), extracted);
            return Optional.empty();
        }
        var drops = itemResources(result.drops());
        var tool = itemResources(List.of(result.tool()));
        var produced = new ArrayList<FactoryResource>(drops.size() + tool.size());
        produced.addAll(drops);
        produced.addAll(tool);
        storeAtSourceOrRecover(workflowId, input.origin(),
                recoverySide(input.origin(), bus), produced);
        changed.run();
        return Optional.of(new FactoryResourceRef(input.origin(), drops));
    }

    /** Extracts a complete interaction input or returns null without partial progress. */
    private List<FactoryResource> extractExact(
            UUID workflowId, FactoryResourceRef input) {
        var planned = new ArrayList<SourcePlan>(input.bundle().size());
        for (var resource : input.bundle()) {
            var access = source(input.origin(), resource.key());
            if (access == null
                    || access.extract(resource.key(), resource.amount(), true) != resource.amount()) {
                return null;
            }
            planned.add(new SourcePlan(resource, access));
        }
        var extracted = new ArrayList<FactoryResource>();
        for (var plan : planned) {
            var resource = plan.resource();
            var amount = plan.source().extract(resource.key(), resource.amount(), false);
            if (amount > 0) {
                extracted.add(new FactoryResource(resource.key(), amount));
            }
            if (amount != resource.amount()) {
                restoreSource(workflowId, input.origin(),
                        recoverySide(input.origin(), null), extracted);
                return null;
            }
        }
        return List.copyOf(extracted);
    }

    private void storeAtSourceOrRecover(
            UUID workflowId,
            FactoryResourceOrigin origin,
            Direction recoverySide,
            List<FactoryResource> resources) {
        var failed = new ArrayList<FactoryResource>();
        for (var resource : resources) {
            var target = source(origin, resource.key());
            var stored = target == null
                    ? 0
                    : target.insert(resource.key(), resource.amount(), false);
            if (stored < resource.amount()) {
                failed.add(new FactoryResource(resource.key(), resource.amount() - stored));
            }
        }
        if (!failed.isEmpty()) {
            escrow.recover(workflowId, recoverySide, failed);
            throw new IllegalStateException(
                    "Source rejected an item transformation; result moved to recovery escrow");
        }
    }

    private static void validateWorkflowOrigin(
            UUID workflowId, FactoryResourceRef input) {
        if (input != null
                && input.origin().kind() == FactoryResourceOrigin.Kind.ESCROW
                && !workflowId.equals(input.origin().escrowId())) {
            throw new IllegalStateException("A workflow cannot use another workflow's escrow");
        }
    }

    private Direction recoverySide(
            FactoryResourceOrigin origin, FactoryBusAddress fallbackBus) {
        if (origin != null) {
            if (origin.kind() == FactoryResourceOrigin.Kind.ESCROW) {
                var side = escrow.recoverySide(origin.escrowId());
                if (side != null) {
                    return side;
                }
            } else if (origin.endpoint().kind() == FactoryEndpoint.Kind.NETWORK) {
                return origin.endpoint().networkSide();
            } else {
                return busRecoverySide.apply(origin.endpoint().bus());
            }
        }
        return fallbackBus == null ? Direction.NORTH : busRecoverySide.apply(fallbackBus);
    }

    private static ItemStack singleItem(List<FactoryResource> resources) {
        var stacks = itemStacks(resources);
        if (stacks == null || stacks.size() != 1 || stacks.getFirst().getCount() != 1) {
            throw new IllegalStateException("Interaction requires exactly one item");
        }
        return stacks.getFirst();
    }

    private static void requireSingleItem(
            FactoryResourceRef input, boolean requireBlockItem) {
        if (input.bundle().size() != 1
                || input.bundle().getFirst().amount() != 1
                || !(input.bundle().getFirst().key() instanceof AEItemKey itemKey)) {
            throw new IllegalArgumentException("Interaction requires exactly one item resource");
        }
        if (requireBlockItem && !(itemKey.getItem() instanceof BlockItem)) {
            throw new IllegalArgumentException("Placement requires a block item");
        }
    }

    private static List<ItemStack> itemStacks(List<FactoryResource> resources) {
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

    private static List<FactoryResource> itemResources(List<ItemStack> stacks) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var stack : stacks) {
            if (!stack.isEmpty()) {
                amounts.merge(AEItemKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        return amounts.entrySet().stream()
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Parses either a JSON text component or a plain literal display name. */
    private static Component parseName(String name, HolderLookup.Provider registries) {
        try {
            var component = Component.Serializer.fromJson(name, registries);
            if (component != null) {
                return component;
            }
        } catch (JsonParseException | IllegalArgumentException ignored) {
            // A non-JSON string is intentionally treated as a literal name.
        }
        return Component.literal(name);
    }

    private FactoryTransferResult exact(UUID workflowId, FactoryTransferAction action) {
        var planned = new ArrayList<TransferPlan>(action.remaining().size());
        for (var resource : action.remaining()) {
            var source = source(action.source(), resource.key());
            var target = target(action.target(), resource.key());
            if (source == null || target == null
                    || source.extract(resource.key(), resource.amount(), true) != resource.amount()
                    || target.insert(resource.key(), resource.amount(), true) != resource.amount()) {
                return FactoryTransferResult.waiting(action.remaining());
            }
            planned.add(new TransferPlan(resource, source, target));
        }

        var extracted = new ArrayList<FactoryResource>();
        for (var plan : planned) {
            var resource = plan.resource();
            var amount = plan.source().extract(resource.key(), resource.amount(), false);
            if (amount > 0) {
                extracted.add(new FactoryResource(resource.key(), amount));
            }
            if (amount != resource.amount()) {
                restoreSource(workflowId, action, extracted);
                return FactoryTransferResult.waiting(action.remaining());
            }
        }

        var inserted = new ArrayList<FactoryResource>();
        for (int index = 0; index < extracted.size(); index++) {
            var resource = extracted.get(index);
            var amount = planned.get(index).target()
                    .insert(resource.key(), resource.amount(), false);
            if (amount > 0) {
                inserted.add(new FactoryResource(resource.key(), amount));
            }
            if (amount != resource.amount()) {
                rollbackExactTarget(workflowId, action, extracted, inserted);
                return FactoryTransferResult.waiting(action.remaining());
            }
        }
        changed.run();
        return FactoryTransferResult.complete();
    }

    private FactoryTransferResult partial(UUID workflowId, FactoryTransferAction action) {
        var moved = new ArrayList<FactoryResource>();
        for (var resource : action.remaining()) {
            var source = source(action.source(), resource.key());
            var target = target(action.target(), resource.key());
            if (source == null || target == null) {
                continue;
            }
            var available = source.extract(resource.key(), resource.amount(), true);
            if (available <= 0) {
                continue;
            }
            var capacity = target.insert(resource.key(), available, true);
            var planned = Math.min(available, capacity);
            if (planned <= 0) {
                continue;
            }
            var extracted = source.extract(resource.key(), planned, false);
            if (extracted <= 0) {
                continue;
            }
            var inserted = target.insert(resource.key(), extracted, false);
            if (inserted > 0) {
                moved.add(new FactoryResource(resource.key(), inserted));
            }
            if (inserted < extracted) {
                restoreOrEscrow(workflowId, action, resource.key(), extracted - inserted);
            }
        }
        if (moved.isEmpty()) {
            return FactoryTransferResult.waiting(action.remaining());
        }
        changed.run();
        var remaining = FactoryResourceRef.subtract(action.remaining(), moved);
        return remaining.isEmpty()
                ? FactoryTransferResult.complete()
                : FactoryTransferResult.waiting(remaining);
    }

    private void rollbackExactTarget(
            UUID workflowId,
            FactoryTransferAction action,
            List<FactoryResource> extracted,
            List<FactoryResource> inserted) {
        var recovered = new ArrayList<FactoryResource>();
        for (var resource : inserted) {
            var target = target(action.target(), resource.key());
            var amount = target.extract(resource.key(), resource.amount(), false);
            if (amount > 0) {
                recovered.add(new FactoryResource(resource.key(), amount));
            }
        }
        recovered.addAll(FactoryResourceRef.subtract(extracted, inserted));
        restoreSource(workflowId, action, recovered);
        var stranded = FactoryResourceRef.subtract(extracted, recovered);
        if (!stranded.isEmpty()) {
            throw new IllegalStateException(
                    "Target storage violated exact-transfer simulation; resources remain at target");
        }
    }

    private void restoreSource(
            UUID workflowId,
            FactoryTransferAction action,
            List<FactoryResource> resources) {
        restoreSource(workflowId, action.source(), recoverySide(action), resources);
    }

    private void restoreSource(
            UUID workflowId,
            FactoryResourceOrigin origin,
            Direction recoverySide,
            List<FactoryResource> resources) {
        var failed = new ArrayList<FactoryResource>();
        for (var resource : resources) {
            var source = source(origin, resource.key());
            var restored = source == null
                    ? 0
                    : source.insert(resource.key(), resource.amount(), false);
            if (restored < resource.amount()) {
                failed.add(new FactoryResource(resource.key(), resource.amount() - restored));
            }
        }
        if (!failed.isEmpty()) {
            escrow.recover(workflowId, recoverySide, failed);
            throw new IllegalStateException(
                    "Source rejected rollback; resources moved to recovery escrow");
        }
    }

    private void restoreOrEscrow(
            UUID workflowId,
            FactoryTransferAction action,
            AEKey key,
            long amount) {
        var source = source(action.source(), key);
        var restored = source == null ? 0 : source.insert(key, amount, false);
        if (restored < amount) {
            escrow.recover(workflowId, recoverySide(action),
                    List.of(new FactoryResource(key, amount - restored)));
            throw new IllegalStateException(
                    "Source rejected rollback; resources moved to recovery escrow");
        }
    }

    private Direction recoverySide(FactoryTransferAction action) {
        if (action.source().kind() == FactoryResourceOrigin.Kind.ESCROW) {
            var side = escrow.recoverySide(action.source().escrowId());
            if (side != null) {
                return side;
            }
        }
        if (action.source().endpoint() != null
                && action.source().endpoint().kind() == FactoryEndpoint.Kind.NETWORK) {
            return action.source().endpoint().networkSide();
        }
        if (action.source().endpoint() != null
                && action.source().endpoint().kind() == FactoryEndpoint.Kind.BUS) {
            return busRecoverySide.apply(action.source().endpoint().bus());
        }
        if (action.target().kind() == FactoryEndpoint.Kind.NETWORK) {
            return action.target().networkSide();
        }
        return busRecoverySide.apply(action.target().bus());
    }

    private StorageAccess source(FactoryResourceOrigin origin, AEKey key) {
        if (origin.kind() == FactoryResourceOrigin.Kind.ESCROW) {
            return new EscrowAccess(origin.escrowId());
        }
        return endpoint(origin.endpoint(), key);
    }

    private StorageAccess target(FactoryEndpoint endpoint, AEKey key) {
        return endpoint(endpoint, key);
    }

    private StorageAccess endpoint(FactoryEndpoint endpoint, AEKey key) {
        if (endpoint.kind() == FactoryEndpoint.Kind.NETWORK) {
            var resolved = networkResolver.resolve(endpoint.networkSide()).orElse(null);
            return resolved == null
                    ? null
                    : new MeStorageAccess(resolved.storage(), resolved.source());
        }
        var bus = busResolver.resolve(endpoint.bus()).orElse(null);
        if (bus == null) {
            return null;
        }
        var storage = bus.storage(key.getType());
        return storage == null ? null : new MeStorageAccess(storage, BUS_SOURCE);
    }

    private static void collect(MEStorage storage, LinkedHashMap<AEKey, Long> amounts) {
        var available = new KeyCounter();
        storage.getAvailableStacks(available);
        for (var entry : available) {
            if (entry.getLongValue() > 0) {
                amounts.merge(entry.getKey(), entry.getLongValue(), Math::addExact);
            }
        }
    }

    private interface StorageAccess {
        long extract(AEKey key, long amount, boolean simulate);

        long insert(AEKey key, long amount, boolean simulate);
    }

    private record SourcePlan(FactoryResource resource, StorageAccess source) {
    }

    private record TransferPlan(
            FactoryResource resource, StorageAccess source, StorageAccess target) {
    }

    private record MeStorageAccess(MEStorage storage, IActionSource source)
            implements StorageAccess {
        @Override
        public long extract(AEKey key, long amount, boolean simulate) {
            return storage.extract(key, amount,
                    simulate ? Actionable.SIMULATE : Actionable.MODULATE, source);
        }

        @Override
        public long insert(AEKey key, long amount, boolean simulate) {
            return storage.insert(key, amount,
                    simulate ? Actionable.SIMULATE : Actionable.MODULATE, source);
        }
    }

    private final class EscrowAccess implements StorageAccess {
        private final UUID allocationId;

        private EscrowAccess(UUID allocationId) {
            this.allocationId = allocationId;
        }

        @Override
        public long extract(AEKey key, long amount, boolean simulate) {
            return escrow.extract(allocationId, key, amount, simulate);
        }

        @Override
        public long insert(AEKey key, long amount, boolean simulate) {
            return simulate ? amount : escrow.insert(allocationId, key, amount);
        }
    }

    @FunctionalInterface
    public interface BusResolver {
        Optional<FactoryBusTarget> resolve(FactoryBusAddress address);
    }

    @FunctionalInterface
    public interface NetworkResolver {
        Optional<NetworkEndpoint> resolve(Direction side);
    }

    public record NetworkEndpoint(MEStorage storage, IActionSource source) {
    }
}
