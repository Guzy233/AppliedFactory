package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.menu.FactoryControllerProgramMenu;
import com.fulent.appliedfactory.network.ControllerProgramSaveResultPayload;
import com.fulent.appliedfactory.network.SaveControllerProgramPayload;
import com.fulent.appliedfactory.network.SetControllerErrorSubscriptionPayload;
import com.fulent.appliedfactory.script.ControllerProgram;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/** Large, slot-free program editor opened only from the controller's top face. */
public final class FactoryControllerProgramScreen
        extends AbstractContainerScreen<FactoryControllerProgramMenu> {
    private static final int MAX_WIDTH = 760;
    private static final int MAX_HEIGHT = 560;
    private static final int MARGIN = 10;
    private static final int HEADER_HEIGHT = 50;

    private MultiLineEditBox scriptBox;
    private Checkbox errorSubscription;
    private final UUID viewerId;
    private Component saveStatus = Component.empty();
    private int saveStatusColor = 0xffa9d8e9;

    public FactoryControllerProgramScreen(
            FactoryControllerProgramMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        viewerId = inventory.player.getUUID();
    }

    @Override
    protected void init() {
        var currentSource = scriptBox == null ? menu.getControllerProgram() : scriptBox.getValue();
        imageWidth = Math.min(MAX_WIDTH, width - 20);
        imageHeight = Math.min(MAX_HEIGHT, height - 20);
        super.init();

        var editorX = leftPos + MARGIN;
        var editorY = topPos + HEADER_HEIGHT + MARGIN;
        var editorWidth = imageWidth - MARGIN * 2;
        var editorHeight = imageHeight - HEADER_HEIGHT - MARGIN * 2;
        scriptBox = new MultiLineEditBox(
                font,
                editorX,
                editorY,
                editorWidth,
                editorHeight,
                Component.translatable("gui.appliedfactory.script"),
                Component.literal(ControllerProgram.DEFAULT_SOURCE));
        scriptBox.setCharacterLimit(ControllerProgram.MAX_SOURCE_LENGTH);
        scriptBox.setValue(currentSource);
        addRenderableWidget(scriptBox);

        addRenderableWidget(Button.builder(
                Component.translatable("gui.appliedfactory.save"), ignored -> saveProgram())
                .bounds(leftPos + imageWidth - 62, topPos + 5, 56, 18)
                .build());

        errorSubscription = addRenderableWidget(Checkbox.builder(
                        Component.translatable("gui.appliedfactory.subscribe_errors"), font)
                .pos(leftPos + MARGIN, topPos + 28)
                .selected(menu.isErrorSubscribed(viewerId))
                .onValueChange((checkbox, selected) -> setErrorSubscription(selected))
                .maxWidth(120)
                .build());
    }

    private void saveProgram() {
        saveStatus = Component.translatable("gui.appliedfactory.saving");
        saveStatusColor = 0xffffd37a;
        PacketDistributor.sendToServer(new SaveControllerProgramPayload(
                menu.getBlockPos(), scriptBox.getValue()));
    }

    private void setErrorSubscription(boolean subscribed) {
        PacketDistributor.sendToServer(new SetControllerErrorSubscriptionPayload(
                menu.getBlockPos(), subscribed));
    }

    /** Called on the client thread by the server-to-client save result payload. */
    public void showSaveResult(ControllerProgramSaveResultPayload payload) {
        if (!menu.getBlockPos().equals(payload.pos())) {
            return;
        }
        if (payload.saved()) {
            saveStatus = Component.translatable("gui.appliedfactory.save_success");
            saveStatusColor = 0xff8fe3af;
        } else {
            saveStatus = Component.translatable(
                    "gui.appliedfactory.syntax_error", payload.message());
            saveStatusColor = 0xffff7d7d;
        }
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        // AbstractContainerScreen consumes drag events before Screen can dispatch them to widgets.
        if (scriptBox.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Release must reach the text widget too, otherwise its scrollbar remains in drag mode.
        boolean releasedByEditor = scriptBox.mouseReleased(mouseX, mouseY, button);
        return releasedByEditor || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scriptBox.isMouseOver(mouseX, mouseY)
                && scriptBox.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight, 0xff17232e, 0xff5d95ad);
        drawPanel(graphics, leftPos + 4, topPos + 4, imageWidth - 8, HEADER_HEIGHT - 4,
                0xff263b49, 0xff72b6d1);
        drawPanel(graphics, leftPos + MARGIN - 2, topPos + HEADER_HEIGHT + MARGIN - 2,
                imageWidth - MARGIN * 2 + 4, imageHeight - HEADER_HEIGHT - MARGIN * 2 + 4,
                0xff0e171e, 0xff3f6d81);
    }

    private static void drawPanel(
            GuiGraphics graphics, int x, int y, int width, int height, int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 10, 10, 0xffd7f4ff);
        var statusX = MARGIN + errorSubscription.getWidth() + 8;
        var statusWidth = imageWidth - statusX - MARGIN;
        var truncatedStatus = font.plainSubstrByWidth(saveStatus.getString(), statusWidth);
        graphics.drawString(font, truncatedStatus, statusX, 32, saveStatusColor);
    }
}
