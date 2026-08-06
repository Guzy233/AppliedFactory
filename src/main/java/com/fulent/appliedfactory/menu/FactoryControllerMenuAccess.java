package com.fulent.appliedfactory.menu;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;

import net.minecraft.core.BlockPos;

/** Common server-side authority for every controller page. */
public interface FactoryControllerMenuAccess {
    FactoryControllerBlockEntity getBlockEntity();

    BlockPos getBlockPos();
}
