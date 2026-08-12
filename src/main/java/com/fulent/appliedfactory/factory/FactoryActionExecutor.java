package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.core.Direction;

/** Resolves durable handles for each attempt and moves resources without a controller cell. */
public final class FactoryActionExecutor {
    private static final IActionSource BUS_SOURCE = IActionSource.empty();

    private final FactoryEscrow escrow;
    private final BusResolver busResolver;
    private final NetworkResolver networkResolver;
    private final Runnable changed;

    public FactoryActionExecutor(
            FactoryEscrow escrow,
            BusResolver busResolver,
            NetworkResolver networkResolver,
            Runnable changed) {
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        this.busResolver = Objects.requireNonNull(busResolver, "busResolver");
        this.networkResolver = Objects.requireNonNull(networkResolver, "networkResolver");
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    public FactoryTransferResult perform(UUID workflowId, FactoryTransferAction action) {
        if (action.remaining().isEmpty()) {
            return FactoryTransferResult.complete();
        }
        var result = action.mode() == FactoryTransferAction.Mode.EXACT
                ? exact(workflowId, action)
                : partial(workflowId, action);
        action.updateRemaining(result.remaining());
        return result;
    }

    /** Current concrete contents of an external endpoint. */
    public List<FactoryResource> available(FactoryEndpoint endpoint) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        if (endpoint.kind() == FactoryEndpoint.Kind.NETWORK) {
            var network = networkResolver.resolve(endpoint.networkSide()).orElse(null);
            if (network == null) {
                return List.of();
            }
            collect(network.storage(), amounts);
        } else {
            var bus = busResolver.resolve(endpoint.bus()).orElse(null);
            if (bus == null) {
                return List.of();
            }
            for (var channel : bus.channels()) {
                var storage = bus.storage(channel);
                if (storage != null) {
                    collect(storage, amounts);
                }
            }
        }
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
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

    private FactoryTransferResult exact(UUID workflowId, FactoryTransferAction action) {
        for (var resource : action.remaining()) {
            var source = source(action.source(), resource.key());
            var target = target(action.target(), resource.key());
            if (source == null || target == null
                    || source.extract(resource.key(), resource.amount(), true) != resource.amount()
                    || target.insert(resource.key(), resource.amount(), true) != resource.amount()) {
                return FactoryTransferResult.waiting(action.remaining());
            }
        }

        var extracted = new ArrayList<FactoryResource>();
        for (var resource : action.remaining()) {
            var source = source(action.source(), resource.key());
            var amount = source.extract(resource.key(), resource.amount(), false);
            if (amount > 0) {
                extracted.add(new FactoryResource(resource.key(), amount));
            }
            if (amount != resource.amount()) {
                restoreSource(workflowId, action, extracted);
                return FactoryTransferResult.waiting(action.remaining());
            }
        }

        var inserted = new ArrayList<FactoryResource>();
        for (var resource : extracted) {
            var target = target(action.target(), resource.key());
            var amount = target.insert(resource.key(), resource.amount(), false);
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
        var failed = new ArrayList<FactoryResource>();
        for (var resource : resources) {
            var source = source(action.source(), resource.key());
            var restored = source == null
                    ? 0
                    : source.insert(resource.key(), resource.amount(), false);
            if (restored < resource.amount()) {
                failed.add(new FactoryResource(resource.key(), resource.amount() - restored));
            }
        }
        if (!failed.isEmpty()) {
            escrow.recover(workflowId, recoverySide(action), failed);
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
        if (action.target().kind() == FactoryEndpoint.Kind.NETWORK) {
            return action.target().networkSide();
        }
        return Direction.NORTH;
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
