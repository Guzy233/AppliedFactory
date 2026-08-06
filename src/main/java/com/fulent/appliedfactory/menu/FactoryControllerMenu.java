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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Menu containing ordinary AE processing patterns and one controller program.
 */
public final class FactoryControllerMenu extends AbstractContainerMenu
        implements FactoryControllerMenuAccess {
    public static final int PATTERN_SLOT_X = 8;
    public static final int PATTERN_SLOT_Y = 36;
    public static final int PATTERN_SLOT_SPACING = 18;
    public static final int CACHE_SLOT_X = 8;
    public static final int CACHE_SLOT_Y = 74;
    public static final int PLAYER_INVENTORY_X = 8;
    public static final int PLAYER_INVENTORY_Y = 111;
    public static final int HOTBAR_Y = 169;
    private static final int PLAYER_INVENTORY_START = FactoryControllerBlockEntity.PATTERN_SLOTS
            + FactoryControllerBlockEntity.CACHE_SLOTS;

    private final FactoryControllerBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final BlockPos blockPos;

    public FactoryControllerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory, data.readBlockPos(), playerInventory.player);
    }

    public FactoryControllerMenu(int containerId, Inventory playerInventory,
            FactoryControllerBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity.getBlockPos(), blockEntity);
    }

    private FactoryControllerMenu(int containerId, Inventory playerInventory, BlockPos blockPos, Player player) {
        this(containerId, playerInventory, blockPos, findBlockEntity(player, blockPos));
    }

    private FactoryControllerMenu(int containerId, Inventory playerInventory, BlockPos blockPos,
            FactoryControllerBlockEntity blockEntity) {
        super(AppliedFactory.FACTORY_CONTROLLER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.blockPos = blockPos;
        this.access = blockEntity == null
                ? ContainerLevelAccess.NULL
                : ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        IItemHandler patterns = blockEntity == null
                ? new ItemStackHandler(FactoryControllerBlockEntity.PATTERN_SLOTS)
                : blockEntity.getPatternInventory();
        for (int slot = 0; slot < FactoryControllerBlockEntity.PATTERN_SLOTS; slot++) {
            addSlot(new SlotItemHandler(patterns, slot,
                    PATTERN_SLOT_X + slot * PATTERN_SLOT_SPACING,
                    PATTERN_SLOT_Y));
        }

        IItemHandler cache = blockEntity == null
                ? new ItemStackHandler(FactoryControllerBlockEntity.CACHE_SLOTS)
                : blockEntity.getCacheInventory();
        for (int slot = 0; slot < FactoryControllerBlockEntity.CACHE_SLOTS; slot++) {
            addSlot(new SlotItemHandler(cache, slot,
                    CACHE_SLOT_X + slot * PATTERN_SLOT_SPACING,
                    CACHE_SLOT_Y) {
                @Override
                public boolean mayPickup(Player player) {
                    return FactoryControllerMenu.this.blockEntity == null
                            || !FactoryControllerMenu.this.blockEntity.isCacheLocked();
                }
            });
        }

        addPlayerInventory(playerInventory);
    }

    private static FactoryControllerBlockEntity findBlockEntity(Player player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof FactoryControllerBlockEntity factory) {
            return factory;
        }
        return null;
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new net.minecraft.world.inventory.Slot(playerInventory, column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * 18, PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            addSlot(new net.minecraft.world.inventory.Slot(playerInventory, slot,
                    PLAYER_INVENTORY_X + slot * 18, HOTBAR_Y));
        }
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        var cacheStart = FactoryControllerBlockEntity.PATTERN_SLOTS;
        if (index >= cacheStart && index < PLAYER_INVENTORY_START
                && blockEntity != null && blockEntity.isCacheLocked()) {
            return ItemStack.EMPTY;
        }
        var slot = getSlot(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        var original = slot.getItem();
        var moved = original.copy();
        if (index < PLAYER_INVENTORY_START) {
            if (!moveItemStackTo(original, PLAYER_INVENTORY_START, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(original, 0, PLAYER_INVENTORY_START, false)) {
            return ItemStack.EMPTY;
        }

        if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity == null || stillValid(access, player, AppliedFactory.FACTORY_CONTROLLER.get());
    }
}
