package com.fulent.appliedfactory.menu;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Slot-free menu that keeps program editing server-authorized and spatially valid. */
public final class FactoryControllerProgramMenu extends AbstractContainerMenu
        implements FactoryControllerMenuAccess {
    private final FactoryControllerBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final BlockPos blockPos;

    public FactoryControllerProgramMenu(
            int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory, data.readBlockPos(), playerInventory.player);
    }

    public FactoryControllerProgramMenu(
            int containerId, Inventory playerInventory, FactoryControllerBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity.getBlockPos(), blockEntity);
    }

    private FactoryControllerProgramMenu(
            int containerId, Inventory playerInventory, BlockPos blockPos, Player player) {
        this(containerId, playerInventory, blockPos, findBlockEntity(player, blockPos));
    }

    private FactoryControllerProgramMenu(
            int containerId,
            Inventory playerInventory,
            BlockPos blockPos,
            FactoryControllerBlockEntity blockEntity) {
        super(AppliedFactory.FACTORY_CONTROLLER_PROGRAM_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.blockPos = blockPos;
        access = blockEntity == null
                ? ContainerLevelAccess.NULL
                : ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
    }

    private static FactoryControllerBlockEntity findBlockEntity(Player player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof FactoryControllerBlockEntity factory) {
            return factory;
        }
        return null;
    }

    @Override
    public FactoryControllerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public BlockPos getBlockPos() {
        return blockPos;
    }

    public String getControllerProgram() {
        return blockEntity == null ? "" : blockEntity.getControllerProgram();
    }

    public boolean isErrorSubscribed(UUID playerId) {
        return blockEntity != null && blockEntity.isErrorSubscribed(playerId);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity == null || stillValid(access, player, AppliedFactory.FACTORY_CONTROLLER.get());
    }
}
