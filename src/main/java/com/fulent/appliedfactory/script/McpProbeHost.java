package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryBusTarget;
import com.fulent.appliedfactory.factory.FactoryEndpoint;
import com.fulent.appliedfactory.factory.FactoryProgram;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.factory.FactoryResourceOrigin;
import com.fulent.appliedfactory.factory.FactoryResourceRef;
import com.fulent.appliedfactory.factory.FactoryTransferAction;
import com.fulent.appliedfactory.factory.FactoryTransferResult;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;

/**
 * {@link FactoryProgram.Host} decorator that lets one MCP probe program run against a
 * controller without touching its production program or escrow ledger. {@code log()}
 * output and script failures are captured for the probe result while world access is
 * delegated to the controller.
 */
public final class McpProbeHost implements FactoryProgram.Host {
    /** Per-line and total caps keep the probe result inside the packet limit. */
    private static final int MAX_LOG_LINE_LENGTH = 16_000;
    private static final int MAX_LOG_LINES = 5_000;
    private static final int MAX_LOG_CHARS = 120_000;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1_000;
    private static final int MAX_ERRORS = 20;

    private final FactoryControllerBlockEntity controller;
    private final List<String> logs = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int logChars;

    public McpProbeHost(FactoryControllerBlockEntity controller) {
        this.controller = controller;
    }

    /** log()/console output produced during this probe run, in order. */
    public List<String> logs() {
        return List.copyOf(logs);
    }

    /** Stage messages of script failures produced during this probe run. */
    public List<String> errors() {
        return List.copyOf(errors);
    }

    @Override
    public long tick() {
        return controller.tick();
    }

    @Override
    public HolderLookup.Provider registries() {
        return controller.registries();
    }

    @Override
    public Map<Direction, List<FactoryBusAddress>> busAddressesByNetwork() {
        return controller.busAddressesByNetwork();
    }

    @Override
    public Set<Direction> onlineNetworks() {
        return controller.onlineNetworks();
    }

    @Override
    public Optional<FactoryBusTarget> busTarget(FactoryBusAddress address) {
        return controller.busTarget(address);
    }

    @Override
    public List<FactoryResource> availableResources(FactoryEndpoint endpoint) {
        return controller.availableResources(endpoint);
    }

    @Override
    public List<FactoryResource> storageContents(FactoryEndpoint endpoint) {
        return controller.storageContents(endpoint);
    }

    @Override
    public List<String> channels(FactoryBusAddress bus) {
        return controller.channels(bus);
    }

    @Override
    public long availableAmount(FactoryResourceOrigin origin, AEKey key) {
        return controller.availableAmount(origin, key);
    }

    @Override
    public FactoryTransferResult performTransfer(
            UUID workflowId, FactoryTransferAction action) {
        return controller.performTransfer(workflowId, action);
    }

    @Override
    public Optional<FactoryResourceRef> renameItem(
            UUID workflowId, FactoryResourceRef item, String name) {
        return controller.renameItem(workflowId, item, name);
    }

    @Override
    public boolean dropItem(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef item) {
        return controller.dropItem(workflowId, bus, item);
    }

    @Override
    public boolean use(UUID workflowId, FactoryBusAddress bus) {
        return controller.use(workflowId, bus);
    }

    @Override
    public boolean use(UUID workflowId, FactoryBusAddress bus, FactoryResourceRef item) {
        return controller.use(workflowId, bus, item);
    }

    @Override
    public boolean place(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef block) {
        return controller.place(workflowId, bus, block);
    }

    @Override
    public Optional<FactoryResourceRef> breakBlock(
            UUID workflowId, FactoryBusAddress bus, FactoryResourceRef tool) {
        return controller.breakBlock(workflowId, bus, tool);
    }

    @Override
    public int busRedstoneLevel(FactoryBusAddress bus) {
        return controller.busRedstoneLevel(bus);
    }

    @Override
    public boolean setBusRedstoneOutput(FactoryBusAddress bus, int level) {
        return controller.setBusRedstoneOutput(bus, level);
    }

    /**
     * Probe programs never own escrow allocations. Probes create no processing orders, and
     * the controller's own program recovers any stray allocation the executor may create on
     * a failed rollback.
     */
    @Override
    public boolean createEscrow(
            UUID workflowId, Direction recoverySide, List<FactoryResource> resources) {
        return false;
    }

    @Override
    public Set<UUID> escrowIds() {
        return Set.of();
    }

    @Override
    public boolean recoverEscrow(UUID workflowId) {
        return true;
    }

    @Override
    public void reportScriptFailure(String stage, String message) {
        if (errors.size() >= MAX_ERRORS) {
            return;
        }
        errors.add(stage + ": " + truncate(message, MAX_ERROR_MESSAGE_LENGTH));
    }

    @Override
    public void log(String message) {
        if (logs.size() >= MAX_LOG_LINES) {
            return;
        }
        var line = truncate(message, MAX_LOG_LINE_LENGTH);
        if (logChars + line.length() > MAX_LOG_CHARS) {
            return;
        }
        logChars += line.length();
        logs.add(line);
        controller.log(line);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void markChanged() {
        controller.markChanged();
    }
}
