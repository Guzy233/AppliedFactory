package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryResource;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** One durable, single-attempt world operation yielded by a script. */
public record FactoryScriptAction(
        Type type,
        @Nullable FactoryBusAddress bus,
        @Nullable Direction networkSide,
        List<FactoryResource> resources,
        int sleepTicks,
        int redstoneLevel) {
    private static final String TYPE_TAG = "Type";
    private static final String BUS_TAG = "Bus";
    private static final String NETWORK_TAG = "Network";
    private static final String RESOURCES_TAG = "Resources";
    private static final String SLEEP_TAG = "Sleep";
    private static final String REDSTONE_TAG = "Redstone";

    public FactoryScriptAction {
        Objects.requireNonNull(type, "type");
        resources = normalize(resources);
        if (sleepTicks < 0) {
            throw new IllegalArgumentException("Sleep duration cannot be negative");
        }
        if (type.isBusAction() != (bus != null)) {
            throw new IllegalArgumentException(type + " has an invalid bus target");
        }
        if (type.isNetworkAction() != (networkSide != null)) {
            throw new IllegalArgumentException(type + " has an invalid network target");
        }
        if (type.requiresResources() && resources.isEmpty()) {
            throw new IllegalArgumentException(type + " requires at least one resource");
        }
        if (!type.requiresResources() && !resources.isEmpty()) {
            throw new IllegalArgumentException(type + " cannot contain resources");
        }
        if (type == Type.SLEEP) {
            if (bus != null || networkSide != null) {
                throw new IllegalArgumentException("SLEEP cannot have a target");
            }
        } else if (sleepTicks != 0) {
            throw new IllegalArgumentException(type + " cannot have a sleep duration");
        }
        if (type == Type.BUS_REDSTONE) {
            if (redstoneLevel < 0 || redstoneLevel > 15) {
                throw new IllegalArgumentException("Bus redstone output must be between 0 and 15");
            }
        } else if (redstoneLevel != 0) {
            throw new IllegalArgumentException(type + " cannot have a redstone level");
        }
        if (type == Type.BUS_PLACE && (resources.size() != 1 || resources.get(0).amount() != 1)) {
            throw new IllegalArgumentException("BUS_PLACE requires exactly one item resource");
        }
    }

    public static FactoryScriptAction busPush(
            FactoryBusAddress bus, List<FactoryResource> resources) {
        return new FactoryScriptAction(Type.BUS_PUSH, bus, null, resources, 0, 0);
    }

    public static FactoryScriptAction busExtract(FactoryBusAddress bus) {
        return new FactoryScriptAction(Type.BUS_EXTRACT, bus, null, List.of(), 0, 0);
    }

    public static FactoryScriptAction busDrop(
            FactoryBusAddress bus, List<FactoryResource> resources) {
        return new FactoryScriptAction(Type.BUS_DROP, bus, null, resources, 0, 0);
    }

    public static FactoryScriptAction busUse(FactoryBusAddress bus) {
        return new FactoryScriptAction(Type.BUS_USE, bus, null, List.of(), 0, 0);
    }

    public static FactoryScriptAction busPlace(
            FactoryBusAddress bus, FactoryResource resource) {
        return new FactoryScriptAction(Type.BUS_PLACE, bus, null, List.of(resource), 0, 0);
    }

    public static FactoryScriptAction busBreak(FactoryBusAddress bus) {
        return new FactoryScriptAction(Type.BUS_BREAK, bus, null, List.of(), 0, 0);
    }

    public static FactoryScriptAction busRedstone(FactoryBusAddress bus, int level) {
        return new FactoryScriptAction(Type.BUS_REDSTONE, bus, null, List.of(), 0, level);
    }

    public static FactoryScriptAction networkPush(
            Direction side, List<FactoryResource> resources) {
        return new FactoryScriptAction(Type.NETWORK_PUSH, null, side, resources, 0, 0);
    }

    public static FactoryScriptAction networkExtract(
            Direction side, List<FactoryResource> resources) {
        return new FactoryScriptAction(Type.NETWORK_EXTRACT, null, side, resources, 0, 0);
    }

    public static FactoryScriptAction sleep(int ticks) {
        return new FactoryScriptAction(Type.SLEEP, null, null, List.of(), ticks, 0);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putString(TYPE_TAG, type.name());
        if (bus != null) {
            tag.put(BUS_TAG, bus.save());
        }
        if (networkSide != null) {
            tag.putString(NETWORK_TAG, networkSide.getName());
        }
        var savedResources = new ListTag();
        for (var resource : resources) {
            savedResources.add(resource.save(registries));
        }
        tag.put(RESOURCES_TAG, savedResources);
        tag.putInt(SLEEP_TAG, sleepTicks);
        tag.putByte(REDSTONE_TAG, (byte) redstoneLevel);
        return tag;
    }

    public static Optional<FactoryScriptAction> load(
            CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(TYPE_TAG, Tag.TAG_STRING)
                || !isCompoundList(tag, RESOURCES_TAG)
                || !tag.contains(SLEEP_TAG, Tag.TAG_INT)) {
            return Optional.empty();
        }

        final Type type;
        try {
            type = Type.valueOf(tag.getString(TYPE_TAG));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        FactoryBusAddress bus = null;
        if (tag.contains(BUS_TAG, Tag.TAG_COMPOUND)) {
            bus = FactoryBusAddress.load(tag.getCompound(BUS_TAG)).orElse(null);
            if (bus == null) {
                return Optional.empty();
            }
        } else if (tag.contains(BUS_TAG)) {
            return Optional.empty();
        }

        Direction networkSide = null;
        if (tag.contains(NETWORK_TAG, Tag.TAG_STRING)) {
            networkSide = Direction.byName(tag.getString(NETWORK_TAG));
            if (networkSide == null) {
                return Optional.empty();
            }
        } else if (tag.contains(NETWORK_TAG)) {
            return Optional.empty();
        }

        var resources = new ArrayList<FactoryResource>();
        var savedResources = tag.getList(RESOURCES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedResources.size(); index++) {
            var resource = FactoryResource.load(savedResources.getCompound(index), registries);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            resources.add(resource.get());
        }

        try {
            return Optional.of(new FactoryScriptAction(
                    type, bus, networkSide, resources, tag.getInt(SLEEP_TAG),
                    tag.contains(REDSTONE_TAG, Tag.TAG_BYTE) ? tag.getByte(REDSTONE_TAG) : 0));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static List<FactoryResource> normalize(List<FactoryResource> resources) {
        Objects.requireNonNull(resources, "resources");
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var resource : resources) {
            Objects.requireNonNull(resource, "resource");
            amounts.merge(resource.key(), resource.amount(), Math::addExact);
        }
        return amounts.entrySet().stream()
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean isCompoundList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            return false;
        }
        var list = (ListTag) tag.get(key);
        return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
    }

    public enum Type {
        BUS_PUSH(true, false, true),
        BUS_EXTRACT(true, false, false),
        BUS_DROP(true, false, true),
        BUS_USE(true, false, false),
        BUS_PLACE(true, false, true),
        BUS_BREAK(true, false, false),
        BUS_REDSTONE(true, false, false),
        NETWORK_PUSH(false, true, true),
        NETWORK_EXTRACT(false, true, true),
        SLEEP(false, false, false);

        private final boolean busAction;
        private final boolean networkAction;
        private final boolean requiresResources;

        Type(boolean busAction, boolean networkAction, boolean requiresResources) {
            this.busAction = busAction;
            this.networkAction = networkAction;
            this.requiresResources = requiresResources;
        }

        public boolean isBusAction() {
            return busAction;
        }

        public boolean isNetworkAction() {
            return networkAction;
        }

        public boolean requiresResources() {
            return requiresResources;
        }
    }
}
