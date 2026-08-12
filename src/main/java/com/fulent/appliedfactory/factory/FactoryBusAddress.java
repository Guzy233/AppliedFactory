package com.fulent.appliedfactory.factory;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Stable world address of a factory bus. It contains no target-machine identity. */
public record FactoryBusAddress(ResourceLocation dimension, BlockPos hostPosition, Direction side) {
    public FactoryBusAddress {
        Objects.requireNonNull(dimension, "dimension");
        hostPosition = Objects.requireNonNull(hostPosition, "hostPosition").immutable();
        Objects.requireNonNull(side, "side");
    }
}
