package com.fulent.appliedfactory.blockentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryActionExecutor;
import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryEndpoint;
import com.fulent.appliedfactory.factory.FactoryEscrow;
import com.fulent.appliedfactory.factory.FactoryProgram;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.factory.FactoryResourceOrigin;
import com.fulent.appliedfactory.factory.FactoryTransferAction;
import com.fulent.appliedfactory.factory.FactoryTransferResult;
import com.fulent.appliedfactory.part.FactoryBusPart;
import com.fulent.appliedfactory.script.CompiledControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgram;
import com.fulent.appliedfactory.script.ProgramLoadResult;
import com.fulent.appliedfactory.script.ScriptHandlerRef;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Six independent AE endpoints coordinated by one bus-centric script runtime. */
public final class FactoryControllerBlockEntity extends BlockEntity
        implements IGridNodeListener<FactoryControllerBlockEntity>,
        IInWorldGridNodeHost, IPowerChannelState, FactoryProgram.Host {
    private static final double MAX_POWER_TRANSFER_PER_NETWORK = 1_024.0D;
    private static final double POWER_EPSILON = 0.0001D;
    private static final String ESCROW_NBT_KEY = "FactoryEscrow";
    private static final String ERROR_SUBSCRIBERS_NBT_KEY = "ErrorSubscribers";
    private static final String ERROR_SUBSCRIBER_ID_NBT_KEY = "Id";
    private final FactoryEscrow escrow = new FactoryEscrow(this::setChanged);
    private final FactoryActionExecutor actionExecutor = new FactoryActionExecutor(
            escrow, this::resolveBusTarget, this::getNetworkStorage, this::setChanged);
    private final Map<Direction, NetworkAttachment> networkAttachments =
            new EnumMap<>(Direction.class);
    private final Map<Direction, IManagedGridNode> networkNodes =
            new EnumMap<>(Direction.class);
    private final Set<UUID> errorSubscribers = new LinkedHashSet<>();
    private final Set<String> reportedScriptFailures = new LinkedHashSet<>();

    private List<OfferedPattern> offeredPatterns = List.of();
    private String controllerProgram = ControllerProgram.DEFAULT_SOURCE;
    private boolean patternsDirty = true;
    /**
     * The compiled program revision for {@link #controllerProgram}, owning all suspended jobs.
     * Null while the current source fails to compile; the source itself is still kept so the
     * player can fix and re-save it.
     */
    private FactoryProgram program;
    private boolean programInitialized;

    public FactoryControllerBlockEntity(BlockPos pos, BlockState state) {
        super(AppliedFactory.FACTORY_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
        for (var direction : Direction.values()) {
            var attachment = new NetworkAttachment(direction);
            networkAttachments.put(direction, attachment);
            networkNodes.put(direction, GridHelper.createManagedNode(this, this)
                    .setInWorldNode(true)
                    .setExposedOnSides(Set.of(direction))
                    .setFlags(GridFlags.REQUIRE_CHANNEL)
                    .setIdlePowerUsage(1.0D / Direction.values().length)
                    .setTagName("network_" + direction.getName())
                    .setVisualRepresentation(Items.IRON_INGOT)
                    .addService(ICraftingProvider.class, attachment));
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GridHelper.onFirstTick(this, FactoryControllerBlockEntity::createGridNodes);
    }

    private void createGridNodes() {
        if (level != null) {
            networkNodes.values().forEach(node -> node.create(level, worldPosition));
            if (!level.isClientSide && !programInitialized) {
                programInitialized = true;
                program = createProgram(controllerProgram);
                invalidatePatterns();
            }
        }
    }

    @Override
    public void setRemoved() {
        destroyGridNodes();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        destroyGridNodes();
        super.onChunkUnloaded();
    }

    private void destroyGridNodes() {
        networkNodes.values().forEach(IManagedGridNode::destroy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        escrow.load(
                tag.contains(ESCROW_NBT_KEY, Tag.TAG_COMPOUND)
                        ? tag.getCompound(ESCROW_NBT_KEY)
                        : new CompoundTag(),
                registries);
        controllerProgram = tag.contains(ControllerProgram.NBT_KEY, Tag.TAG_STRING)
                ? tag.getString(ControllerProgram.NBT_KEY)
                : ControllerProgram.DEFAULT_SOURCE;
        errorSubscribers.clear();
        reportedScriptFailures.clear();
        var savedSubscribers = tag.getList(ERROR_SUBSCRIBERS_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedSubscribers.size(); index++) {
            var subscriber = savedSubscribers.getCompound(index);
            if (subscriber.hasUUID(ERROR_SUBSCRIBER_ID_NBT_KEY)) {
                errorSubscribers.add(subscriber.getUUID(ERROR_SUBSCRIBER_ID_NBT_KEY));
            }
        }
        program = createProgram(controllerProgram);
        programInitialized = true;
        networkNodes.values().forEach(node -> node.loadFromNBT(tag));
        invalidatePatterns();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(ESCROW_NBT_KEY, escrow.save(registries));
        tag.putString(ControllerProgram.NBT_KEY, controllerProgram);
        var savedSubscribers = new ListTag();
        for (var subscriber : errorSubscribers) {
            var subscriberTag = new CompoundTag();
            subscriberTag.putUUID(ERROR_SUBSCRIBER_ID_NBT_KEY, subscriber);
            savedSubscribers.add(subscriberTag);
        }
        tag.put(ERROR_SUBSCRIBERS_NBT_KEY, savedSubscribers);
        networkNodes.values().forEach(node -> node.saveToNBT(tag));
        super.saveAdditional(tag, registries);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = saveWithoutMetadata(registries);
        tag.remove(ESCROW_NBT_KEY);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onSaveChanges(FactoryControllerBlockEntity owner, IGridNode node) {
        setChanged();
    }

    @Override
    public void onStateChanged(
            FactoryControllerBlockEntity owner, IGridNode node, State state) {
        invalidatePatterns();
        // Grid state changed: notify the script's network.onChange listeners on the next step.
        if (program != null) {
            program.markEnvironmentChanged();
        }
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    @Override
    public IGridNode getGridNode(Direction dir) {
        var node = networkNodes.get(dir);
        return node == null ? null : node.getNode();
    }

    @Override
    public boolean isPowered() {
        return networkNodes.values().stream().anyMatch(IManagedGridNode::isPowered);
    }

    @Override
    public boolean isActive() {
        return networkNodes.values().stream().anyMatch(IManagedGridNode::isActive);
    }

    public String getControllerProgram() {
        return controllerProgram;
    }

    public boolean isErrorSubscribed(UUID playerId) {
        return errorSubscribers.contains(playerId);
    }

    public void updateErrorSubscription(UUID playerId, boolean subscribed) {
        if (subscribed ? errorSubscribers.add(playerId) : errorSubscribers.remove(playerId)) {
            markChangedAndSync();
        }
    }

    /**
     * Compiles before committing so an invalid edit cannot replace the running program.
     * Successful replacement discards old in-memory generators; their escrow allocations are
     * recovered by the replacement on its next tick.
     */
    public ProgramLoadResult<FactoryProgram> updateControllerProgram(String source) {
        var result = FactoryProgram.replace(program, source, this);
        if (!result.successful()) {
            return result;
        }
        controllerProgram = source;
        program = result.program();
        programInitialized = true;
        reportedScriptFailures.clear();
        invalidatePatterns();
        markChangedAndSync();
        return result;
    }

    private FactoryProgram createProgram(String source) {
        var result = FactoryProgram.load(source, this);
        if (!result.successful()) {
            reportScriptFailure("program load", result.errorMessage());
            return null;
        }
        return result.program();
    }

    private List<IPatternDetails> availablePatterns(Direction side) {
        rebuildPatternsIfNeeded();
        return offeredPatterns.stream()
                .filter(pattern -> pattern.orderNetwork == side)
                .map(OfferedPattern::details)
                .toList();
    }

    private void rebuildPatternsIfNeeded() {
        if (!patternsDirty) {
            return;
        }
        var compiled = program == null ? CompiledControllerProgram.EMPTY : program.compiled();
        var offers = new ArrayList<OfferedPattern>();
        if (level != null) {
            for (var pattern : compiled.scriptPatterns()) {
                var details = PatternDetailsHelper.decodePattern(pattern.encodedPattern(), level);
                if (details != null) {
                    offers.add(new OfferedPattern(
                            details,
                            pattern.orderNetwork(),
                            ScriptHandlerRef.pattern(pattern.handlerIndex())));
                }
            }
        }
        offeredPatterns = List.copyOf(offers);
        patternsDirty = false;
    }

    private void invalidatePatterns() {
        patternsDirty = true;
        offeredPatterns = List.of();
        networkNodes.values().forEach(ICraftingProvider::requestUpdate);
    }

    private boolean pushPattern(
            Direction networkSide,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder) {
        if (level == null || program == null || !program.canAcceptJobs()) {
            return false;
        }
        rebuildPatternsIfNeeded();
        var offered = offeredPatterns.stream()
                .filter(candidate -> candidate.orderNetwork == networkSide)
                .filter(candidate -> candidate.details == patternDetails)
                .findFirst()
                .or(() -> offeredPatterns.stream()
                        .filter(candidate -> candidate.orderNetwork == networkSide)
                        .filter(candidate -> candidate.details.equals(patternDetails))
                        .findFirst())
                .orElse(null);
        if (offered == null) {
            return false;
        }

        var inputs = collectInputs(patternDetails, inputHolder);
        var outputs = collectOutputs(patternDetails);
        if (inputs.isEmpty() || outputs.isEmpty()) {
            return false;
        }
        if (!program.startJob(offered.handler, networkSide, inputs, outputs)) {
            return false;
        }
        setChanged();
        return true;
    }

    private boolean isBusy(Direction side) {
        return program == null
                || !program.canAcceptJobs()
                || availablePatterns(side).isEmpty();
    }

    // ---- FactoryProgram.Host -------------------------------------------------

    @Override
    public long tick() {
        return level == null ? 0L : level.getGameTime();
    }

    @Override
    public HolderLookup.Provider registries() {
        return level.registryAccess();
    }

    @Override
    public Set<Direction> onlineNetworks() {
        var online = EnumSet.noneOf(Direction.class);
        for (var side : Direction.values()) {
            var node = networkNodes.get(side);
            if (node != null && node.isOnline() && node.getGrid() != null) {
                online.add(side);
            }
        }
        return online;
    }

    @Override
    public Optional<com.fulent.appliedfactory.factory.FactoryBusTarget> busTarget(
            com.fulent.appliedfactory.factory.FactoryBusAddress address) {
        return resolveBusTarget(address);
    }

    @Override
    public List<FactoryResource> availableResources(FactoryEndpoint endpoint) {
        return actionExecutor.available(endpoint);
    }

    @Override
    public long availableAmount(FactoryResourceOrigin origin, AEKey key) {
        return actionExecutor.available(origin, key);
    }

    @Override
    public FactoryTransferResult performTransfer(
            UUID workflowId,
            FactoryTransferAction action) {
        return actionExecutor.perform(workflowId, action);
    }

    @Override
    public boolean createEscrow(
            UUID workflowId,
            Direction recoverySide,
            List<FactoryResource> resources) {
        return escrow.create(workflowId, recoverySide, resources);
    }

    @Override
    public Set<UUID> escrowIds() {
        return escrow.allocationIds();
    }

    @Override
    public boolean recoverEscrow(UUID workflowId) {
        return actionExecutor.recoverEscrow(workflowId);
    }

    @Override
    public void markChanged() {
        setChanged();
    }

    /**
     * Called by the AE2 grid mixins and the bus part when a factory bus joined/left a network or
     * its target machine changed. Event-driven replacement for the old per-tick topology
     * fingerprint: notifies network.onChange listeners on the next step.
     */
    public void onBusTopologyChanged() {
        if (program != null) {
            program.markEnvironmentChanged();
        }
    }

    /**
     * Called by the crafting CPU mixin when one of our crafting requests ends without
     * success (explicit cancellation, a cancelled link, or a failed craft). Jobs that
     * were stamped with that request id are redundant and get cancelled. A successful
     * completion never calls this: the delivering workflow finishes on its own.
     */
    public void onCraftingRequestFinished(UUID craftingRequestId) {
        if (program != null) {
            program.cancelJobs(craftingRequestId);
        }
    }

    @Override
    public void reportScriptFailure(String stage, String message) {
        AppliedFactory.LOGGER.error("Factory {} failed: {}", stage, message);
        if (!reportedScriptFailures.add(stage + '\n' + message)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        var notification = Component.translatable(
                "chat.appliedfactory.script_error",
                Component.literal(worldPosition.toShortString()),
                Component.literal(stage),
                Component.literal(message)).withStyle(ChatFormatting.RED);
        for (var subscriber : errorSubscribers) {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(subscriber);
            if (player != null) {
                player.sendSystemMessage(notification);
            }
        }
    }

    @Override
    public Map<Direction, List<FactoryBusAddress>> busAddressesByNetwork() {
        var result = new EnumMap<Direction, List<FactoryBusAddress>>(Direction.class);
        currentBusesByNetwork().forEach((side, buses) -> result.put(side, buses.stream()
                .flatMap(bus -> bus.address().stream())
                .toList()));
        return result;
    }

    private Map<Direction, List<FactoryBusPart>> currentBusesByNetwork() {
        var result = new EnumMap<Direction, List<FactoryBusPart>>(Direction.class);
        for (var side : Direction.values()) {
            var node = networkNodes.get(side);
            var grid = node == null ? null : node.getGrid();
            if (grid == null || !node.isOnline()) {
                result.put(side, List.of());
                continue;
            }
            var collected = grid.getActiveMachines(FactoryBusPart.class).stream()
                    .sorted(Comparator
                            .comparingLong((FactoryBusPart bus) -> bus.getHostPosition().asLong())
                            .thenComparing(bus -> bus.getSide() == null
                                    ? "" : bus.getSide().getName()))
                    .toList();
            result.put(side, collected);
        }
        return result;
    }

    private List<FactoryBusPart> getFactoryBuses() {
        var unique = new LinkedHashSet<FactoryBusPart>();
        currentBusesByNetwork().values().forEach(unique::addAll);
        return List.copyOf(unique);
    }

    private Optional<FactoryBusPart> resolveBus(
            com.fulent.appliedfactory.factory.FactoryBusAddress address) {
        return getFactoryBuses().stream()
                .filter(bus -> bus.address().filter(address::equals).isPresent())
                .findFirst();
    }

    private Optional<com.fulent.appliedfactory.factory.FactoryBusTarget> resolveBusTarget(
            com.fulent.appliedfactory.factory.FactoryBusAddress address) {
        return resolveBus(address).flatMap(FactoryBusPart::target);
    }

    private Optional<FactoryActionExecutor.NetworkEndpoint> getNetworkStorage(Direction side) {
        var node = networkNodes.get(side);
        var attachment = networkAttachments.get(side);
        if (node == null || attachment == null || !node.isOnline() || node.getGrid() == null) {
            return Optional.empty();
        }
        return Optional.of(new FactoryActionExecutor.NetworkEndpoint(
                node.getGrid().getStorageService().getInventory(),
                IActionSource.ofMachine(attachment)));
    }

    private static List<FactoryResource> collectInputs(
            IPatternDetails details, KeyCounter[] inputHolder) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        details.pushInputsToExternalInventory(inputHolder, (key, amount) -> {
            if (amount > 0) {
                amounts.merge(key, amount, Math::addExact);
            }
        });
        return fromAmounts(amounts);
    }

    private static List<FactoryResource> collectOutputs(IPatternDetails details) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var output : details.getOutputs()) {
            if (output.amount() > 0) {
                amounts.merge(output.what(), output.amount(), Math::addExact);
            }
        }
        return fromAmounts(amounts);
    }

    private static List<FactoryResource> fromAmounts(Map<AEKey, Long> amounts) {
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void markChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // 服务tick入口
    public static void serverTick(
            Level level,
            BlockPos pos,
            BlockState state,
            FactoryControllerBlockEntity controller) {
        if (!level.isClientSide) {
            // 为子网供电
            controller.bridgePowerBetweenNetworks();
            // 推进脚本任务（挂起 job 恢复/重试/终结、被动处理器、资源回收）
            if (controller.program != null) {
                controller.program.step();
            } else {
                controller.recoverAllEscrows();
            }
        }
    }

    /** Recovery must not depend on the current script being valid or loadable. */
    private void recoverAllEscrows() {
        for (var escrowId : escrow.allocationIds()) {
            try {
                actionExecutor.recoverEscrow(escrowId);
            } catch (RuntimeException exception) {
                AppliedFactory.LOGGER.error(
                        "Factory escrow {} recovery failed", escrowId, exception);
            }
        }
    }

    private void bridgePowerBetweenNetworks() {
        var seenGrids = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
        var networks = new ArrayList<EnergyNetwork>();
        for (var node : networkNodes.values()) {
            var grid = node.getGrid();
            if (grid != null && seenGrids.add(grid)) {
                var energy = grid.getEnergyService();
                networks.add(new EnergyNetwork(
                        energy,
                        Math.max(0.0D, energy.getStoredPower()),
                        Math.max(0.0D,
                                energy.getEnergyDemand(MAX_POWER_TRANSFER_PER_NETWORK))));
            }
        }
        if (networks.size() < 2) {
            return;
        }
        var donors = networks.stream()
                .sorted(Comparator.comparingDouble(EnergyNetwork::stored).reversed())
                .toList();
        var receivers = networks.stream()
                .filter(network -> network.demand() > POWER_EPSILON)
                .sorted(Comparator.comparingDouble(EnergyNetwork::stored))
                .toList();
        var budgets = new IdentityHashMap<IEnergyService, Double>();
        for (var donor : donors) {
            var reserve = donor.service.getIdlePowerUsage() * 4.0D + 1.0D;
            budgets.put(donor.service, Math.max(0.0D,
                    Math.min(MAX_POWER_TRANSFER_PER_NETWORK, donor.stored - reserve)));
        }
        for (var receiver : receivers) {
            var demand = Math.min(receiver.demand, MAX_POWER_TRANSFER_PER_NETWORK);
            for (var donor : donors) {
                if (demand <= POWER_EPSILON) {
                    break;
                }
                if (donor.service == receiver.service
                        || donor.stored <= receiver.stored + POWER_EPSILON) {
                    continue;
                }
                var budget = budgets.getOrDefault(donor.service, 0.0D);
                if (budget <= POWER_EPSILON) {
                    continue;
                }
                var requested = Math.min(demand, budget);
                var extractable = donor.service.extractAEPower(
                        requested, Actionable.SIMULATE, PowerMultiplier.ONE);
                if (extractable <= POWER_EPSILON) {
                    continue;
                }
                var accepted = extractable - receiver.service.injectPower(
                        extractable, Actionable.SIMULATE);
                if (accepted <= POWER_EPSILON) {
                    continue;
                }
                var extracted = donor.service.extractAEPower(
                        accepted, Actionable.MODULATE, PowerMultiplier.ONE);
                var overflow = receiver.service.injectPower(extracted, Actionable.MODULATE);
                var delivered = extracted - overflow;
                if (overflow > POWER_EPSILON) {
                    donor.service.injectPower(overflow, Actionable.MODULATE);
                }
                demand -= delivered;
                budgets.put(donor.service, Math.max(0.0D, budget - delivered));
            }
        }
    }

    /** Materializes every hidden escrow resource. */
    public void dropOwnedContents() {
        if (level == null || level.isClientSide) {
            return;
        }
        var escrowDrops = new ArrayList<ItemStack>();
        for (var resource : escrow.allContents()) {
            resource.key().addDrops(resource.amount(), escrowDrops, level, worldPosition);
        }
        for (var stack : escrowDrops) {
            Containers.dropItemStack(
                    level,
                    worldPosition.getX(),
                    worldPosition.getY(),
                    worldPosition.getZ(),
                    stack);
        }
        escrow.clear();
        if (program != null) {
            program.discard();
        }
        setChanged();
    }

    private record OfferedPattern(
            IPatternDetails details, Direction orderNetwork, ScriptHandlerRef handler) {
    }

    private record EnergyNetwork(IEnergyService service, double stored, double demand) {
    }

    private final class NetworkAttachment implements ICraftingProvider, IActionHost {
        private final Direction side;

        private NetworkAttachment(Direction side) {
            this.side = side;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return availablePatterns(side);
        }

        @Override
        public boolean pushPattern(IPatternDetails details, KeyCounter[] inputHolder) {
            return FactoryControllerBlockEntity.this.pushPattern(side, details, inputHolder);
        }

        @Override
        public boolean isBusy() {
            return FactoryControllerBlockEntity.this.isBusy(side);
        }

        @Override
        public IGridNode getActionableNode() {
            return networkNodes.get(side).getNode();
        }
    }
}
