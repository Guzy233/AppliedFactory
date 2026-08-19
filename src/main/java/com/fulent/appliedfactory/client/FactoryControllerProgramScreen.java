package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fulent.appliedfactory.mcp.McpClientManager;
import com.fulent.appliedfactory.mcp.McpToolException;
import com.fulent.appliedfactory.mcp.ScriptBundler;
import com.fulent.appliedfactory.menu.FactoryControllerProgramMenu;
import com.fulent.appliedfactory.network.ControllerProgramContentPayload;
import com.fulent.appliedfactory.network.ControllerProgramSaveResultPayload;
import com.fulent.appliedfactory.network.RequestControllerProgramPayload;
import com.fulent.appliedfactory.network.SaveControllerProgramPayload;
import com.fulent.appliedfactory.network.SetControllerLogSubscriptionPayload;
import com.fulent.appliedfactory.script.ControllerProgram;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Workspace-backed controller editor with a compact file browser. */
public final class FactoryControllerProgramScreen
        extends AbstractContainerScreen<FactoryControllerProgramMenu> {
    private static final int MAX_WIDTH = 920;
    private static final int MAX_HEIGHT = 560;
    private static final int MARGIN = 10;
    private static final int HEADER_HEIGHT = 50;
    private static final int FILES_WIDTH = 190;
    private static final int FILE_ROW_HEIGHT = 19;

    private final UUID viewerId;
    private final List<Button> fileButtons = new ArrayList<>();
    private MultiLineEditBox scriptBox;
    private Checkbox logSubscription;
    private Button uploadButton;
    private Button pullButton;
    private Button mcpButton;
    private List<String> workspaceFiles = List.of();
    private String selectedPath;
    private String remotePath = "";
    private String remoteSource = ControllerProgram.DEFAULT_SOURCE;
    private boolean sourceLoaded;
    private int filePage;
    private Component saveStatus = Component.empty();
    private int saveStatusColor = 0xffa9d8e9;

    public FactoryControllerProgramScreen(
            FactoryControllerProgramMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        viewerId = inventory.player.getUUID();
    }

    @Override
    protected void init() {
        var currentSource = scriptBox == null ? remoteSource : scriptBox.getValue();
        imageWidth = Math.min(MAX_WIDTH, width - 20);
        imageHeight = Math.min(MAX_HEIGHT, height - 20);
        super.init();

        var editorX = leftPos + FILES_WIDTH + MARGIN * 2;
        var editorY = topPos + HEADER_HEIGHT + MARGIN;
        var editorWidth = imageWidth - FILES_WIDTH - MARGIN * 3;
        var editorHeight = imageHeight - HEADER_HEIGHT - MARGIN * 2;
        scriptBox = new MultiLineEditBox(
                font, editorX, editorY, editorWidth, editorHeight,
                Component.translatable("gui.appliedfactory.script"),
                Component.literal(ControllerProgram.DEFAULT_SOURCE));
        scriptBox.setCharacterLimit(ControllerProgram.MAX_SOURCE_LENGTH);
        scriptBox.setValue(currentSource);
        addRenderableWidget(scriptBox);

        uploadButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.appliedfactory.upload_precompiled"),
                ignored -> uploadProgram())
                .bounds(leftPos + imageWidth - 106, topPos + 5, 100, 18).build());
        pullButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.appliedfactory.pull_local"),
                ignored -> pullRemoteProgram())
                .bounds(leftPos + imageWidth - 212, topPos + 5, 100, 18).build());
        mcpButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.appliedfactory.bind_mcp"), ignored -> onMcpButton())
                .bounds(leftPos + imageWidth - 274, topPos + 5, 56, 18).build());

        logSubscription = addRenderableWidget(Checkbox.builder(
                        Component.translatable("gui.appliedfactory.subscribe_logs"), font)
                .pos(leftPos + MARGIN, topPos + 28)
                .selected(menu.isLogSubscribed(viewerId))
                .onValueChange((checkbox, selected) -> setLogSubscription(selected))
                .maxWidth(120).build());

        reloadWorkspaceFiles();
        if (!sourceLoaded) {
            PacketDistributor.sendToServer(new RequestControllerProgramPayload(menu.getBlockPos()));
            setStatus("gui.appliedfactory.loading_program", 0xffffd37a);
        }
        updateButtonStates();
    }

    private void reloadWorkspaceFiles() {
        try {
            workspaceFiles = ScriptWorkspaceFiles.list();
            var pages = Math.max(1, (workspaceFiles.size() + rowsPerPage() - 1) / rowsPerPage());
            filePage = Math.min(filePage, pages - 1);
            rebuildFileButtons();
        } catch (IOException exception) {
            setLiteralStatus("Unable to list appliedscripts: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void rebuildFileButtons() {
        fileButtons.forEach(this::removeWidget);
        fileButtons.clear();
        var first = filePage * rowsPerPage();
        var last = Math.min(workspaceFiles.size(), first + rowsPerPage());
        for (int index = first; index < last; index++) {
            var path = workspaceFiles.get(index);
            var label = font.plainSubstrByWidth(path, FILES_WIDTH - 20);
            var button = Button.builder(Component.literal(label), ignored -> selectFile(path))
                    .bounds(leftPos + MARGIN, topPos + HEADER_HEIGHT + MARGIN
                            + (index - first) * FILE_ROW_HEIGHT,
                            FILES_WIDTH - MARGIN, 18).build();
            fileButtons.add(addRenderableWidget(button));
        }
        var navigationY = topPos + imageHeight - 25;
        fileButtons.add(addRenderableWidget(Button.builder(Component.literal("<"), ignored -> {
            if (filePage > 0) {
                filePage--;
                rebuildFileButtons();
            }
        }).bounds(leftPos + MARGIN, navigationY, 22, 18).build()));
        fileButtons.add(addRenderableWidget(Button.builder(Component.literal(">"), ignored -> {
            if ((filePage + 1) * rowsPerPage() < workspaceFiles.size()) {
                filePage++;
                rebuildFileButtons();
            }
        }).bounds(leftPos + 34, navigationY, 22, 18).build()));
        fileButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("gui.appliedfactory.refresh_files"),
                ignored -> reloadWorkspaceFiles())
                .bounds(leftPos + 60, navigationY, FILES_WIDTH - 60, 18).build()));
    }

    private int rowsPerPage() {
        return Math.max(1, (imageHeight - HEADER_HEIGHT - 45) / FILE_ROW_HEIGHT);
    }

    private void selectFile(String path) {
        try {
            var source = ScriptWorkspaceFiles.read(path);
            if (!ControllerProgram.isWithinLimit(source)) {
                setStatus("gui.appliedfactory.local_source_too_long", 0xffff7d7d);
                return;
            }
            selectedPath = path;
            scriptBox.setValue(source);
            setLiteralStatus(path, 0xffa9d8e9);
            updateButtonStates();
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Unable to read " + path + ": " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void uploadProgram() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.local_backup_required", 0xffff7d7d);
            return;
        }
        var source = scriptBox.getValue();
        final String compiled;
        try {
            ScriptWorkspaceFiles.write(selectedPath, source);
            compiled = ScriptBundler.bundle(source, ScriptWorkspaceFiles.absolute(selectedPath).getParent());
        } catch (IOException | IllegalArgumentException | McpToolException exception) {
            setLiteralStatus("Precompile failed: " + exception.getMessage(), 0xffff7d7d);
            return;
        }
        if (!ControllerProgram.isWithinLimit(source)
                || !ControllerProgram.isWithinLimit(compiled)) {
            setStatus("gui.appliedfactory.source_too_long", 0xffff7d7d,
                    ControllerProgram.MAX_SOURCE_LENGTH);
            return;
        }
        setStatus("gui.appliedfactory.saving", 0xffffd37a);
        PacketDistributor.sendToServer(new SaveControllerProgramPayload(
                menu.getBlockPos(), source, compiled, selectedPath));
    }

    private void pullRemoteProgram() {
        if (!sourceLoaded) {
            return;
        }
        try {
            String path;
            if (!remotePath.isBlank() && ScriptWorkspaceFiles.exists(remotePath)
                    && ScriptWorkspaceFiles.read(remotePath).equals(remoteSource)) {
                path = remotePath;
            } else {
                path = ScriptWorkspaceFiles.availableDownloadPath(remotePath);
                ScriptWorkspaceFiles.write(path, remoteSource);
            }
            selectedPath = path;
            scriptBox.setValue(remoteSource);
            reloadWorkspaceFiles();
            setLiteralStatus("Pulled to appliedscripts/" + path, 0xff8fe3a1);
            updateButtonStates();
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Pull failed: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private boolean boundHere() {
        var binding = McpClientManager.get().binding();
        return binding != null && binding.pos().equals(menu.getBlockPos());
    }

    private void onMcpButton() {
        if (boundHere()) {
            McpClientManager.get().unbind();
        } else {
            McpClientManager.get().requestBind(menu.getBlockPos());
        }
    }

    private void setLogSubscription(boolean subscribed) {
        PacketDistributor.sendToServer(new SetControllerLogSubscriptionPayload(
                menu.getBlockPos(), subscribed));
    }

    public void showSaveResult(ControllerProgramSaveResultPayload payload) {
        if (!menu.getBlockPos().equals(payload.pos())) {
            return;
        }
        if (payload.saved()) {
            remoteSource = scriptBox.getValue();
            remotePath = selectedPath;
            setStatus("gui.appliedfactory.save_success", 0xff8fe3a1);
        } else {
            setStatus("gui.appliedfactory.syntax_error", 0xffff7d7d, payload.message());
        }
    }

    public void showProgramContent(ControllerProgramContentPayload payload) {
        if (!menu.getBlockPos().equals(payload.pos()) || scriptBox == null) {
            return;
        }
        sourceLoaded = true;
        remoteSource = payload.source();
        remotePath = payload.workspacePath();
        if (selectedPath != null) {
            updateButtonStates();
            return;
        }
        selectedPath = null;
        try {
            if (!remotePath.isBlank() && ScriptWorkspaceFiles.exists(remotePath)
                    && ScriptWorkspaceFiles.read(remotePath).equals(remoteSource)) {
                selectedPath = remotePath;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            selectedPath = null;
        }
        scriptBox.setValue(remoteSource);
        setStatus(selectedPath == null
                ? "gui.appliedfactory.remote_unbacked"
                : "gui.appliedfactory.local_file_matched", 0xffa9d8e9);
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (uploadButton != null) {
            uploadButton.active = sourceLoaded && selectedPath != null;
        }
        if (pullButton != null) {
            pullButton.active = sourceLoaded && selectedPath == null;
        }
    }

    private void setStatus(String key, int color, Object... args) {
        saveStatus = Component.translatable(key, args);
        saveStatusColor = color;
    }

    private void setLiteralStatus(String value, int color) {
        saveStatus = Component.literal(value);
        saveStatusColor = color;
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        return scriptBox.mouseDragged(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return scriptBox.mouseReleased(mouseX, mouseY, button)
                || super.mouseReleased(mouseX, mouseY, button);
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
    protected void containerTick() {
        super.containerTick();
        mcpButton.setMessage(Component.translatable(
                boundHere() ? "gui.appliedfactory.unbind_mcp" : "gui.appliedfactory.bind_mcp"));
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (getFocused() == scriptBox && selectedPath == null) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft.options.keyInventory.matches(keyCode, scanCode) && getFocused() == scriptBox) {
            return true;
        }
        if (getFocused() == scriptBox && selectedPath == null) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight, 0xff17232e, 0xff5d95ad);
        drawPanel(graphics, leftPos + 4, topPos + 4, imageWidth - 8, HEADER_HEIGHT - 4,
                0xff263b49, 0xff72b6d1);
        drawPanel(graphics, leftPos + MARGIN - 2, topPos + HEADER_HEIGHT + MARGIN - 2,
                FILES_WIDTH - MARGIN + 4, imageHeight - HEADER_HEIGHT - MARGIN * 2 + 4,
                0xff0e171e, 0xff3f6d81);
        drawPanel(graphics, leftPos + FILES_WIDTH + MARGIN * 2 - 2,
                topPos + HEADER_HEIGHT + MARGIN - 2,
                imageWidth - FILES_WIDTH - MARGIN * 3 + 4,
                imageHeight - HEADER_HEIGHT - MARGIN * 2 + 4,
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
        var path = selectedPath == null
                ? Component.translatable("gui.appliedfactory.no_local_file").getString()
                : selectedPath;
        graphics.drawString(font, font.plainSubstrByWidth(path, 180), FILES_WIDTH + MARGIN * 2, 31,
                selectedPath == null ? 0xffffd37a : 0xff8fe3a1);
        var statusX = MARGIN + logSubscription.getWidth() + 8;
        var statusWidth = FILES_WIDTH - statusX;
        graphics.drawString(font, font.plainSubstrByWidth(saveStatus.getString(), statusWidth),
                statusX, 32, saveStatusColor);
    }
}
