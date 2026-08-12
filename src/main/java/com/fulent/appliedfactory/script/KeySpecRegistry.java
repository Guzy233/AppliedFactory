package com.fulent.appliedfactory.script;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Registry of script-level spec parsers that turn a channel id plus an optional
 * data component patch into an exact {@link AEKey}. The built-in item and fluid
 * channels are seeded here for their component-patch ({@code nbt}) support.
 *
 * <p>Channels without a parser are not a limitation: {@link #parse} falls back
 * to AE2's own generic decoding of {@code {"id": ...}}, which every channel whose
 * key codec stores its primary registry id under {@code "id"} (items, fluids,
 * AppMek chemicals, ...) accepts with zero adapter code. Exotic channels whose
 * codec uses a different field (e.g. Applied Flux's energy key) can be specified
 * through {@code stackTag(...)} with a serialized generic key tag.</p>
 */
public final class KeySpecRegistry {
    private static final Map<AEKeyType, SpecParser> PARSERS = new ConcurrentHashMap<>();

    private KeySpecRegistry() {
    }

    static {
        PARSERS.put(AEKeyType.items(), KeySpecRegistry::itemKey);
        PARSERS.put(AEKeyType.fluids(), KeySpecRegistry::fluidKey);
    }

    /** Registers (or replaces) the spec parser used to build keys of one channel. */
    public static void register(AEKeyType type, SpecParser parser) {
        PARSERS.put(type, parser);
    }

    /** Whether an explicit spec parser is registered for the given channel. */
    public static boolean hasParser(AEKeyType type) {
        return PARSERS.containsKey(type);
    }

    /**
     * Rebuilds an exact key for the channel from a raw id and optional component
     * patch tag. {@code nbt} is the raw SNBT tag parsed from the script spec. Uses
     * the registered parser when present, otherwise decodes AE2's generic
     * {@code {"id": ...}} tag through the channel's own codec.
     */
    public static AEKey parse(
            HolderLookup.Provider registries, AEKeyType channel, String id,
            @Nullable CompoundTag nbt) {
        var resourceId = requireId(channel, id);
        var parser = PARSERS.get(channel);
        if (parser != null) {
            return parser.parse(channel, resourceId, nbt);
        }
        if (nbt != null) {
            throw new IllegalArgumentException(
                    "No component-patch parser for channel " + channel.getId());
        }
        var generic = new CompoundTag();
        generic.putString("id", resourceId.toString());
        var key = channel.loadKeyFromTag(registries, generic);
        if (key == null) {
            throw new IllegalArgumentException(
                    "Channel " + channel.getId() + " cannot build a key from a plain id; "
                            + "use stackTag(tag, amount) instead");
        }
        return key;
    }

    /**
     * Eagerly checks that the id is well-formed (and known for the built-in item /
     * fluid channels) before the script spec object is created. Channels without a
     * parser are allowed here; the exact key is derived later by {@link #parse}.
     */
    public static void validate(AEKeyType channel, String id) {
        requireId(channel, id);
    }

    private static ResourceLocation requireId(AEKeyType channel, String id) {
        var resourceId = ResourceLocation.tryParse(id);
        if (resourceId == null) {
            throw new IllegalArgumentException("Invalid resource id: " + id);
        }
        if (channel == AEKeyType.items() && !BuiltInRegistries.ITEM.containsKey(resourceId)) {
            throw new IllegalArgumentException("Unknown item id: " + id);
        }
        if (channel == AEKeyType.fluids() && !BuiltInRegistries.FLUID.containsKey(resourceId)) {
            throw new IllegalArgumentException("Unknown fluid id: " + id);
        }
        return resourceId;
    }

    private static AEKey itemKey(AEKeyType channel, ResourceLocation id, @Nullable CompoundTag nbt) {
        var item = BuiltInRegistries.ITEM.get(id);
        if (item == null) {
            throw new IllegalArgumentException("Unknown item id: " + id);
        }
        if (nbt == null) {
            return AEItemKey.of(item);
        }
        var stack = new ItemStack(item);
        stack.applyComponentsAndValidate(componentPatch(nbt));
        return AEItemKey.of(stack);
    }

    private static AEKey fluidKey(AEKeyType channel, ResourceLocation id, @Nullable CompoundTag nbt) {
        var fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY) {
            throw new IllegalArgumentException("Unknown fluid id: " + id);
        }
        var stack = new FluidStack(fluid, 1);
        if (nbt != null) {
            stack.applyComponents(componentPatch(nbt));
        }
        var key = AEFluidKey.of(stack);
        if (key == null) {
            throw new IllegalArgumentException("Invalid fluid id: " + id);
        }
        return key;
    }

    private static DataComponentPatch componentPatch(CompoundTag nbt) {
        var patch = DataComponentPatch.CODEC.parse(NbtOps.INSTANCE, nbt)
                .resultOrPartial(error -> {
                })
                .orElse(null);
        if (patch == null) {
            throw new IllegalArgumentException("Invalid data components patch");
        }
        return patch;
    }

    @FunctionalInterface
    public interface SpecParser {
        AEKey parse(AEKeyType channel, ResourceLocation id, @Nullable CompoundTag nbt);
    }
}
