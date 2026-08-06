package com.fulent.appliedfactory.factory;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Stable world address of a factory bus, suitable for persisted jobs. */
public record FactoryBusAddress(ResourceLocation dimension, BlockPos hostPosition, Direction side) {
    private static final String DIMENSION_TAG = "Dimension";
    private static final String POSITION_TAG = "Position";
    private static final String SIDE_TAG = "Side";

    public FactoryBusAddress {
        hostPosition = hostPosition.immutable();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString(DIMENSION_TAG, dimension.toString());
        tag.putLong(POSITION_TAG, hostPosition.asLong());
        tag.putString(SIDE_TAG, side.getName());
        return tag;
    }

    public static Optional<FactoryBusAddress> load(CompoundTag tag) {
        if (!tag.contains(DIMENSION_TAG, Tag.TAG_STRING)
                || !tag.contains(POSITION_TAG, Tag.TAG_LONG)
                || !tag.contains(SIDE_TAG, Tag.TAG_STRING)) {
            return Optional.empty();
        }
        var dimension = ResourceLocation.tryParse(tag.getString(DIMENSION_TAG));
        var side = Direction.byName(tag.getString(SIDE_TAG));
        if (dimension == null || side == null) {
            return Optional.empty();
        }
        return Optional.of(new FactoryBusAddress(
                dimension,
                BlockPos.of(tag.getLong(POSITION_TAG)),
                side));
    }
}
