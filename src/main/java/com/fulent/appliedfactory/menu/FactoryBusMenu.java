package com.fulent.appliedfactory.menu;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.parts.PartHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** Four acceleration-card slots for a factory bus part. */
public final class FactoryBusMenu extends AbstractContainerMenu {
    public static final int UPGRADE_X = 89;
    public static final int UPGRADE_Y = 38;
    public static final int PLAYER_X = 8;
    public static final int PLAYER_Y = 84;
    public static final int HOTBAR_Y = 142;
    private static final int PLAYER_START = FactoryBusPart.UPGRADE_SLOTS;

    @Nullable
    private final FactoryBusPart part;
    private final BlockPos hostPosition;
    private final Direction side;

    public FactoryBusMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory, data.readBlockPos(), data.readEnum(Direction.class));
    }

    public FactoryBusMenu(int containerId, Inventory playerInventory, FactoryBusPart part) {
        this(containerId, playerInventory, part.getHostPosition(),
                Objects.requireNonNull(part.getSide()), part);
    }

    private FactoryBusMenu(int containerId, Inventory playerInventory, BlockPos hostPosition, Direction side) {
        this(containerId, playerInventory, hostPosition, side,
                findPart(playerInventory.player, hostPosition, side));
    }

    private FactoryBusMenu(int containerId, Inventory playerInventory, BlockPos hostPosition, Direction side,
            @Nullable FactoryBusPart part) {
        super(AppliedFactory.FACTORY_BUS_MENU.get(), containerId);
        this.part = part;
        this.hostPosition = hostPosition.immutable();
        this.side = side;

        IItemHandler upgrades = part == null
                ? new ItemStackHandler(FactoryBusPart.UPGRADE_SLOTS)
                : part.getUpgrades().toItemHandler();
        for (int slot = 0; slot < FactoryBusPart.UPGRADE_SLOTS; slot++) {
            addSlot(new SlotItemHandler(upgrades, slot, UPGRADE_X + slot * 18, UPGRADE_Y));
        }
        addPlayerInventory(playerInventory);
    }

    @Nullable
    private static FactoryBusPart findPart(Player player, BlockPos pos, Direction side) {
        return PartHelper.getPart(AppliedFactory.FACTORY_BUS_ITEM.get(), player.level(), pos, side);
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new net.minecraft.world.inventory.Slot(playerInventory, column + row * 9 + 9,
                        PLAYER_X + column * 18, PLAYER_Y + row * 18));
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            addSlot(new net.minecraft.world.inventory.Slot(playerInventory, slot,
                    PLAYER_X + slot * 18, HOTBAR_Y));
        }
    }

    @Nullable
    public FactoryBusPart getPart() {
        return part;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        var slot = getSlot(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        var original = slot.getItem();
        var moved = original.copy();
        if (index < PLAYER_START) {
            if (!moveItemStackTo(original, PLAYER_START, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(original, 0, PLAYER_START, false)) {
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
        if (part == null) {
            return true;
        }
        return player.distanceToSqr(
                hostPosition.getX() + 0.5,
                hostPosition.getY() + 0.5,
                hostPosition.getZ() + 0.5) <= 64
                && PartHelper.getPart(player.level(), hostPosition, side) == part;
    }
}
