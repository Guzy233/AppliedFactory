package com.fulent.appliedfactory.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.networking.IGrid;
import appeng.me.Grid;
import appeng.me.GridNode;
import net.minecraft.nbt.CompoundTag;

/**
 * Turns the per-tick topology fingerprint into an event: every node that joins or leaves a grid
 * flows through {@code Grid.add} / {@code Grid.remove}, so when the node belongs to a
 * {@link FactoryBusPart} the controllers on that grid are told their bus topology changed. Bus
 * target changes are covered separately by {@link FactoryBusPart#onNeighborChanged}.
 */
@Mixin(Grid.class)
public abstract class GridMixin {
    @Inject(method = "add", at = @At("TAIL"))
    private void factoryOnBusAdded(GridNode gridNode, @Nullable CompoundTag savedData, CallbackInfo ci) {
        notifyBusTopologyChanged(gridNode);
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void factoryOnBusRemoved(GridNode gridNode, CallbackInfo ci) {
        notifyBusTopologyChanged(gridNode);
    }

    private void notifyBusTopologyChanged(GridNode gridNode) {
        if (!(gridNode.getOwner() instanceof FactoryBusPart)) {
            return;
        }
        for (var controller : ((IGrid) (Object) this)
                .getMachines(FactoryControllerBlockEntity.class)) {
            controller.onBusTopologyChanged();
        }
    }
}
