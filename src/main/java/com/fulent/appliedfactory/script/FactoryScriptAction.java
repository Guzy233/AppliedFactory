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
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * One durable, single-attempt world operation yielded by a script. Bus storage
 * operations carry the AE key channel they target so the executor can dispatch
 * to the correct external storage strategy.
 */
public record FactoryScriptAction(
        Type type,
        @Nullable FactoryBusAddress bus,
        @Nullable Direction networkSide,
        @Nullable AEKeyType channel,
        List<FactoryResource> resources,
        int sleepTicks,
        int redstoneLevel,
        @Nullable String name) {
    private static final String TYPE_TAG = "Type";
    private static final String BUS_TAG = "Bus";
    private static final String NETWORK_TAG = "Network";
    private static final String CHANNEL_TAG = "Channel";
    private static final String RESOURCES_TAG = "Resources";
    private static final String SLEEP_TAG = "Sleep";
    private static final String REDSTONE_TAG = "Redstone";
    private static final String NAME_TAG = "Name";

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
        if (type.usesChannel() != (channel != null)) {
            throw new IllegalArgumentException(type + " has an invalid resource channel");
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
        if (type == Type.RENAME_OWNED) {
            if (name == null) {
                throw new IllegalArgumentException("RENAME_OWNED requires a name");
            }
        } else if (name != null) {
            throw new IllegalArgumentException(type + " cannot carry a name");
        }
        if (type == Type.BUS_PLACE && (resources.size() != 1 || resources.get(0).amount() != 1)) {
            throw new IllegalArgumentException("BUS_PLACE requires exactly one item resource");
        }
        if (type == Type.BUS_BREAK_WITH
                && (resources.size() != 1 || resources.get(0).amount() != 1)) {
            throw new IllegalArgumentException(
                    "BUS_BREAK_WITH requires exactly one owned item unit");
        }
    }

    public static FactoryScriptAction busPush(
            FactoryBusAddress bus, AEKeyType channel, List<FactoryResource> resources) {
        return new FactoryScriptAction(Type.BUS_PUSH, bus, null, channel, resources, 0, 0, null);
    }

    public static FactoryScriptAction busPushTillFull(
            FactoryBusAddress bus, AEKeyType channel, List<FactoryResource> resources) {
        return new FactoryScriptAction(
                Type.BUS_PUSH_TILL_FULL, bus, null, channel, resources, 0, 0, null);
    }

    public static FactoryScriptAction busExtract(FactoryBusAddress bus, AEKeyType channel) {
        return new FactoryScriptAction(
                Type.BUS_EXTRACT, bus, null, channel, List.of(), 0, 0, null);
    }

    public static FactoryScriptAction busDrop(
            FactoryBusAddress bus, List<FactoryResource> resources) {
        return new FactoryScriptAction(Type.BUS_DROP, bus, null, null, resources, 0, 0, null);
    }

    public static FactoryScriptAction busUse(FactoryBusAddress bus) {
        return new FactoryScriptAction(Type.BUS_USE, bus, null, null, List.of(), 0, 0, null);
    }

    public static FactoryScriptAction busPlace(
            FactoryBusAddress bus, FactoryResource resource) {
        return new FactoryScriptAction(
                Type.BUS_PLACE, bus, null, null, List.of(resource), 0, 0, null);
    }

    public static FactoryScriptAction busBreak(FactoryBusAddress bus) {
        return new FactoryScriptAction(Type.BUS_BREAK, bus, null, null, List.of(), 0, 0, null);
    }

    public static FactoryScriptAction busBreakWith(
            FactoryBusAddress bus, FactoryResource tool) {
        return new FactoryScriptAction(
                Type.BUS_BREAK_WITH, bus, null, null, List.of(tool), 0, 0, null);
    }

    public static FactoryScriptAction busRedstone(FactoryBusAddress bus, int level) {
        return new FactoryScriptAction(
                Type.BUS_REDSTONE, bus, null, null, List.of(), 0, level, null);
    }

    public static FactoryScriptAction networkPush(
            Direction side, List<FactoryResource> resources) {
        return new FactoryScriptAction(
                Type.NETWORK_PUSH, null, side, null, resources, 0, 0, null);
    }

    public static FactoryScriptAction networkPushTillFull(
            Direction side, List<FactoryResource> resources) {
        return new FactoryScriptAction(
                Type.NETWORK_PUSH_TILL_FULL, null, side, null, resources, 0, 0, null);
    }

    public static FactoryScriptAction networkExtract(
            Direction side, List<FactoryResource> resources) {
        return new FactoryScriptAction(
                Type.NETWORK_EXTRACT, null, side, null, resources, 0, 0, null);
    }

    public static FactoryScriptAction renameOwned(
            List<FactoryResource> resources, String name) {
        return new FactoryScriptAction(
                Type.RENAME_OWNED, null, null, null, resources, 0, 0, name);
    }

    public static FactoryScriptAction sleep(int ticks) {
        return new FactoryScriptAction(Type.SLEEP, null, null, null, List.of(), ticks, 0, null);
    }

    /**
     * Returns a copy of this action carrying a reduced resource list. Used by the
     * scheduler when a blocking till-full push transfers part of its input each
     * tick: the same continuation stays blocked at the call site while the
     * remaining resources are retried.
     */
    public FactoryScriptAction withResources(List<FactoryResource> remaining) {
        return new FactoryScriptAction(
                type, bus, networkSide, channel, remaining, sleepTicks, redstoneLevel, name);
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
        if (channel != null) {
            tag.putString(CHANNEL_TAG, channel.getId().toString());
        }
        var savedResources = new ListTag();
        for (var resource : resources) {
            savedResources.add(resource.save(registries));
        }
        tag.put(RESOURCES_TAG, savedResources);
        tag.putInt(SLEEP_TAG, sleepTicks);
        tag.putByte(REDSTONE_TAG, (byte) redstoneLevel);
        if (name != null) {
            tag.putString(NAME_TAG, name);
        }
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

        AEKeyType channel = null;
        if (tag.contains(CHANNEL_TAG, Tag.TAG_STRING)) {
            channel = loadChannel(tag.getString(CHANNEL_TAG));
            if (channel == null) {
                return Optional.empty();
            }
        } else if (tag.contains(CHANNEL_TAG)) {
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
                    type, bus, networkSide, channel, resources, tag.getInt(SLEEP_TAG),
                    tag.contains(REDSTONE_TAG, Tag.TAG_BYTE) ? tag.getByte(REDSTONE_TAG) : 0,
                    tag.contains(NAME_TAG, Tag.TAG_STRING) ? tag.getString(NAME_TAG) : null));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    /** Resolves a persisted channel id, or null when that AE key type is not registered. */
    @Nullable
    private static AEKeyType loadChannel(String id) {
        var resourceId = ResourceLocation.tryParse(id);
        if (resourceId == null) {
            return null;
        }
        try {
            return AEKeyTypes.get(resourceId);
        } catch (IllegalArgumentException exception) {
            return null;
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
        BUS_PUSH(true, false, true, true, true),
        BUS_PUSH_TILL_FULL(true, false, true, true, true),
        BUS_EXTRACT(true, false, false, false, true),
        BUS_DROP(true, false, true, false, false),
        BUS_USE(true, false, false, false, false),
        BUS_PLACE(true, false, true, false, false),
        BUS_BREAK(true, false, false, false, false),
        BUS_BREAK_WITH(true, false, true, false, false),
        BUS_REDSTONE(true, false, false, false, false),
        NETWORK_PUSH(false, true, true, true, false),
        NETWORK_PUSH_TILL_FULL(false, true, true, true, false),
        NETWORK_EXTRACT(false, true, true, false, false),
        RENAME_OWNED(false, false, true, false, false),
        SLEEP(false, false, false, false, false);

        private final boolean busAction;
        private final boolean networkAction;
        private final boolean requiresResources;
        private final boolean blocking;
        private final boolean usesChannel;

        Type(boolean busAction, boolean networkAction, boolean requiresResources,
                boolean blocking, boolean usesChannel) {
            this.busAction = busAction;
            this.networkAction = networkAction;
            this.requiresResources = requiresResources;
            this.blocking = blocking;
            this.usesChannel = usesChannel;
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

        public boolean usesChannel() {
            return usesChannel;
        }

        /**
         * Blocking actions keep their pending action across failed attempts: the
         * scheduler retries them on the next server tick instead of resuming the
         * script, and only resumes once the operation succeeds (or, for till-full
         * pushes, once the remaining resource list is empty).
         */
        public boolean isBlocking() {
            return blocking;
        }
    }
}
