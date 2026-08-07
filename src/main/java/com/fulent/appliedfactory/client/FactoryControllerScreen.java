package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.menu.FactoryControllerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** AE-inspired storage page for patterns and private storage cells. */
public final class FactoryControllerScreen extends AbstractContainerScreen<FactoryControllerMenu> {
    public FactoryControllerScreen(FactoryControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 184;
        imageHeight = 198;
        inventoryLabelY = 99;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight, 0xff17232e, 0xff5d95ad);
        drawPanel(graphics, leftPos + 4, topPos + 4, imageWidth - 8, 18, 0xff263b49, 0xff72b6d1);
        drawPanel(graphics, leftPos + 4, topPos + 24, imageWidth - 8, 30, 0xff20323f, 0xff3f6d81);
        drawPanel(graphics, leftPos + 4, topPos + 62, imageWidth - 8, 30, 0xff20323f, 0xff3f6d81);
        drawPanel(graphics, leftPos + 4, topPos + 100, imageWidth - 8, 94, 0xff20323f, 0xff3f6d81);

        for (int slot = 0; slot < FactoryControllerBlockEntity.PATTERN_SLOTS; slot++) {
            drawSlot(graphics,
                    leftPos + FactoryControllerMenu.PATTERN_SLOT_X
                            + slot * FactoryControllerMenu.PATTERN_SLOT_SPACING,
                    topPos + FactoryControllerMenu.PATTERN_SLOT_Y);
        }
        for (int slot = 0; slot < FactoryControllerBlockEntity.CACHE_SLOTS; slot++) {
            drawSlot(graphics,
                    leftPos + FactoryControllerMenu.CACHE_SLOT_X
                            + slot * FactoryControllerMenu.PATTERN_SLOT_SPACING,
                    topPos + FactoryControllerMenu.CACHE_SLOT_Y);
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlot(graphics,
                        leftPos + FactoryControllerMenu.PLAYER_INVENTORY_X + column * 18,
                        topPos + FactoryControllerMenu.PLAYER_INVENTORY_Y + row * 18);
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            drawSlot(graphics,
                    leftPos + FactoryControllerMenu.PLAYER_INVENTORY_X + slot * 18,
                    topPos + FactoryControllerMenu.HOTBAR_Y);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xff0f171d);
        graphics.fill(x, y, x + 16, y + 16, 0xff344e5c);
        graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xff1c2b34);
    }

    private static void drawPanel(
            GuiGraphics graphics, int x, int y, int width, int height, int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 9, 0xffd7f4ff);
        graphics.drawString(font, Component.translatable("gui.appliedfactory.patterns"), 8, 26, 0xffa9d8e9);
        graphics.drawString(font, Component.translatable("gui.appliedfactory.cache"), 8, 64, 0xffa9d8e9);
        graphics.drawString(font, Component.translatable("gui.appliedfactory.inventory"), 8, 102, 0xffa9d8e9);
    }
}
