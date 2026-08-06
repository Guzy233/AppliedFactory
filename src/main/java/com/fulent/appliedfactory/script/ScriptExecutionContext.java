package com.fulent.appliedfactory.script;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.part.FactoryBusPart;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;

/** Live controller data exposed to one initializer or workflow invocation. */
public final class ScriptExecutionContext {
    private final UUID workflowId;
    private final long tick;
    @Nullable
    private final Direction orderNetwork;
    private final List<FactoryResource> inputs;
    private final List<FactoryResource> outputs;
    private final List<FactoryResource> owned;
    private final Map<Direction, List<FactoryBusPart>> busesByNetwork;
    private final Set<Direction> accessibleNetworks;
    private final Set<Direction> onlineNetworks;
    private final HolderLookup.Provider registries;

    public ScriptExecutionContext(
            UUID workflowId,
            long tick,
            @Nullable Direction orderNetwork,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            List<FactoryResource> owned,
            Map<Direction, List<FactoryBusPart>> busesByNetwork,
            Set<Direction> accessibleNetworks,
            Set<Direction> onlineNetworks,
            HolderLookup.Provider registries) {
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.tick = tick;
        this.orderNetwork = orderNetwork;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.owned = List.copyOf(owned);
        var copiedNetworks = new EnumMap<Direction, List<FactoryBusPart>>(Direction.class);
        busesByNetwork.forEach((side, buses) -> copiedNetworks.put(side, List.copyOf(buses)));
        this.busesByNetwork = Map.copyOf(copiedNetworks);
        this.accessibleNetworks = Set.copyOf(accessibleNetworks);
        this.onlineNetworks = Set.copyOf(onlineNetworks);
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    public UUID workflowId() {
        return workflowId;
    }

    public long tick() {
        return tick;
    }

    @Nullable
    public Direction orderNetwork() {
        return orderNetwork;
    }

    public List<FactoryResource> inputs() {
        return inputs;
    }

    public List<FactoryResource> outputs() {
        return outputs;
    }

    public List<FactoryResource> owned() {
        return owned;
    }

    public HolderLookup.Provider registries() {
        return registries;
    }

    public boolean canAccess(Direction side) {
        return accessibleNetworks.contains(side);
    }

    public boolean isOnline(Direction side) {
        return accessibleNetworks.contains(side) && onlineNetworks.contains(side);
    }

    public List<FactoryBusPart> buses(Direction side) {
        if (!accessibleNetworks.contains(side)) {
            throw new IllegalArgumentException("Network " + side.getName() + " is not accessible here");
        }
        return busesByNetwork.getOrDefault(side, List.of());
    }

    public List<FactoryBusPart> allBuses() {
        var unique = new LinkedHashMap<FactoryBusAddress, FactoryBusPart>();
        for (var side : Direction.values()) {
            if (!accessibleNetworks.contains(side)) {
                continue;
            }
            for (var bus : busesByNetwork.getOrDefault(side, List.of())) {
                bus.address().ifPresent(address -> unique.putIfAbsent(address, bus));
            }
        }
        return List.copyOf(unique.values());
    }

    public Optional<FactoryBusPart> resolveBus(FactoryBusAddress address) {
        for (var bus : allBuses()) {
            if (bus.address().filter(address::equals).isPresent()) {
                return Optional.of(bus);
            }
        }
        return Optional.empty();
    }
}
