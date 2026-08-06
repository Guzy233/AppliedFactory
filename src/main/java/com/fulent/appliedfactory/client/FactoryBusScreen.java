package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.menu.FactoryBusMenu;
import com.fulent.appliedfactory.part.FactoryBusPart;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Compact upgrade and target-status screen for a factory bus. */
public final class FactoryBusScreen extends AbstractContainerScreen<FactoryBusMenu> {
    public FactoryBusScreen(FactoryBusMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = 74;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 74, 0xff8b8b8b);

        for (int slot = 0; slot < FactoryBusPart.UPGRADE_SLOTS; slot++) {
            drawSlot(graphics,
                    leftPos + FactoryBusMenu.UPGRADE_X + slot * 18,
                    topPos + FactoryBusMenu.UPGRADE_Y);
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlot(graphics,
                        leftPos + FactoryBusMenu.PLAYER_X + column * 18,
                        topPos + FactoryBusMenu.PLAYER_Y + row * 18);
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            drawSlot(graphics,
                    leftPos + FactoryBusMenu.PLAYER_X + slot * 18,
                    topPos + FactoryBusMenu.HOTBAR_Y);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        var part = menu.getPart();
        var target = part == null
                ? "?"
                : part.targetBlockId().map(Object::toString).orElse("?");
        var redstone = part == null
                ? 0
                : part.machine().map(machine -> machine.redstoneLevel()).orElse(0);
        graphics.drawString(font,
                Component.translatable("gui.mefactorymanager.factory_bus.target", target),
                8, 19, 0xffe0e0e0, false);
        graphics.drawString(font,
                Component.translatable("gui.mefactorymanager.factory_bus.redstone", redstone),
                8, 29, 0xffe0e0e0, false);
        graphics.drawString(font,
                Component.translatable("gui.mefactorymanager.factory_bus.acceleration"),
                8, 43, 0xffe0e0e0, false);
    }

    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xff8b8b8b);
        graphics.fill(x, y, x + 16, y + 16, 0xff373737);
    }
}
