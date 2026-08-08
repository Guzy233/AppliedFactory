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
import com.fulent.appliedfactory.factory.FactoryCellCache;
import com.fulent.appliedfactory.factory.FactoryJob;
import com.fulent.appliedfactory.factory.FactoryProgram;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.part.FactoryBusPart;
import com.fulent.appliedfactory.script.CompiledControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgram;
import com.fulent.appliedfactory.script.FactoryActionResult;
import com.fulent.appliedfactory.script.FactoryScriptAction;
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
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Six independent AE endpoints coordinated by one bus-centric script runtime. */
public final class FactoryControllerBlockEntity extends BlockEntity
        implements IGridNodeListener<FactoryControllerBlockEntity>,
        IInWorldGridNodeHost, IPowerChannelState, FactoryProgram.Host {
    public static final int PATTERN_SLOTS = 9;
    public static final int CACHE_SLOTS = 3;
    private static final double MAX_POWER_TRANSFER_PER_NETWORK = 1_024.0D;
    private static final double POWER_EPSILON = 0.0001D;
    private static final String PATTERNS_NBT_KEY = "Patterns";
    private static final String CACHE_NBT_KEY = "FactoryCache";
    private static final String ERROR_SUBSCRIBERS_NBT_KEY = "ErrorSubscribers";
    private static final String ERROR_SUBSCRIBER_ID_NBT_KEY = "Id";
    private static final ResourceLocation AE2_PROCESSING_PATTERN_ID =
            ResourceLocation.fromNamespaceAndPath("ae2", "processing_pattern");

    private final ItemStackHandler patternInventory = new ItemStackHandler(PATTERN_SLOTS) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return AE2_PROCESSING_PATTERN_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }

        @Override
        protected void onContentsChanged(int slot) {
            invalidatePatterns();
            markChangedAndSync();
        }
    };
    private final FactoryCellCache cache = new FactoryCellCache(CACHE_SLOTS, this::setChanged);
    private final FactoryActionExecutor actionExecutor = new FactoryActionExecutor(
            cache, this::resolveBus, this::getNetworkStorage, this::setChanged);
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
    /**
     * Set while building a client update tag. Jobs (and their continuations) are never synced to
     * clients, so we skip serializing them there — otherwise every block update would pay the full
     * continuation serialization cost only to have {@link #getUpdateTag} strip the result.
     */
    private transient boolean suppressJobPersistence;

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
        if (tag.contains(PATTERNS_NBT_KEY, Tag.TAG_COMPOUND)) {
            patternInventory.deserializeNBT(registries, tag.getCompound(PATTERNS_NBT_KEY));
        }
        if (tag.contains(CACHE_NBT_KEY, Tag.TAG_COMPOUND)) {
            cache.inventory().deserializeNBT(registries, tag.getCompound(CACHE_NBT_KEY));
        }
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
        if (program != null) {
            program.loadJobs(tag, registries);
        }
        networkNodes.values().forEach(node -> node.loadFromNBT(tag));
        invalidatePatterns();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        cache.persistCells();
        tag.put(PATTERNS_NBT_KEY, patternInventory.serializeNBT(registries));
        tag.put(CACHE_NBT_KEY, cache.inventory().serializeNBT(registries));
        tag.putString(ControllerProgram.NBT_KEY, controllerProgram);
        if (!suppressJobPersistence && program != null) {
            program.saveJobs(tag, registries);
        }
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
        suppressJobPersistence = true;
        try {
            return saveWithoutMetadata(registries);
        } finally {
            suppressJobPersistence = false;
        }
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
        // Grid state changed: re-run the initializer on the next step even if the watched
        // bus set looks unchanged (e.g. a grid identity change).
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

    public IItemHandler getPatternInventory() {
        return patternInventory;
    }

    public IItemHandler getCacheInventory() {
        return cache.inventory();
    }

    public boolean isCacheLocked() {
        return program != null && program.hasLockedCache();
    }

    public String getControllerProgram() {
        return controllerProgram;
    }

    public CompiledControllerProgram getCompiledProgram() {
        return program == null ? CompiledControllerProgram.EMPTY : program.compiled();
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
     * Compiles before committing so an invalid edit cannot replace the currently running
     * program. A successful replacement cancels every job of the old revision (their
     * continuations are bound to the old Rhino scope); their owned resources move to the new
     * program's recovery queue and are returned to the network as soon as it accepts them.
     */
    public ProgramLoadResult<FactoryProgram> updateControllerProgram(String source) {
        var result = FactoryProgram.replace(program, source, this);
        if (!result.successful()) {
            return result;
        }
        controllerProgram = source;
        program = result.program();
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
        if (level != null && compiled.hasControllerHandler()) {
            for (int slot = 0; slot < patternInventory.getSlots(); slot++) {
                var details = PatternDetailsHelper.decodePattern(
                        patternInventory.getStackInSlot(slot), level);
                if (details != null) {
                    offers.add(new OfferedPattern(
                            details,
                            compiled.controllerOrderNetwork(),
                            ScriptHandlerRef.controller()));
                }
            }
        }
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
        if (offered == null || !cache.hasStorageCell()) {
            return false;
        }

        var inputs = collectInputs(patternDetails, inputHolder);
        var outputs = collectOutputs(patternDetails);
        if (inputs.isEmpty() || outputs.isEmpty()
                || inputs.stream().anyMatch(resource -> !(resource.key() instanceof AEItemKey))
                || outputs.stream().anyMatch(resource -> !(resource.key() instanceof AEItemKey))
                || !cache.storeAll(inputs)) {
            return false;
        }

        // The program owns the job from here on: inputs are committed to the cache. On any
        // failure the cache rollback below restores them so the network can retry.
        if (!program.startJob(offered.handler, networkSide, inputs, outputs)) {
            requireCacheRollback(inputs);
            return false;
        }
        setChanged();
        return true;
    }

    private void requireCacheRollback(List<FactoryResource> resources) {
        if (!cache.removeAll(resources)) {
            throw new IllegalStateException("Factory cache could not roll back rejected pattern input");
        }
    }

    private boolean isBusy(Direction side) {
        return program == null
                || !program.canAcceptJobs()
                || !cache.hasStorageCell()
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
    public boolean returnOwned(List<FactoryResource> owned, Direction side) {
        return actionExecutor.returnOwned(owned, side);
    }

    @Override
    public FactoryActionResult performAction(
            FactoryJob job, FactoryScriptAction action) {
        return actionExecutor.perform(job, action);
    }

    @Override
    public Optional<FactoryActionExecutor.NetworkEndpoint> networkStorage(Direction side) {
        return getNetworkStorage(side);
    }

    @Override
    public void markChanged() {
        setChanged();
    }

    @Override
    public long requestedCraftingAmount(Direction side, AEKey key) {
        var node = networkNodes.get(side);
        var grid = node == null ? null : node.getGrid();
        if (grid == null || !node.isOnline()) {
            return 0;
        }
        return grid.getCraftingService().getRequestedAmount(key);
    }

    /**
     * Called by the AE2 grid mixins and the bus part when a factory bus joined/left a network or
     * its target machine changed. Event-driven replacement for the old per-tick topology
     * fingerprint: re-runs the initializer on the next step.
     */
    public void onBusTopologyChanged() {
        if (program != null) {
            program.markEnvironmentChanged();
        }
    }

    /**
     * Called by the crafting CPU mixin when one of our crafting requests ends:
     * both explicit cancellation and successful completion (the request's outputs
     * are already satisfied, possibly by another source). In either case every
     * still-running job for that request is redundant and gets cancelled.
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

    private final EnumMap<Direction, String> lastBusDiagSignatures = new EnumMap<>(Direction.class);

    @Override
    public Map<Direction, List<FactoryBusPart>> busesByNetwork() {
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
            var signature = new StringBuilder(side.getName()).append('|');
            for (var bus : collected) {
                signature.append(System.identityHashCode(bus)).append('@')
                        .append(bus.getHostPosition()).append('/')
                        .append(bus.getSide()).append(';');
            }
            if (!signature.toString().equals(lastBusDiagSignatures.get(side))) {
                lastBusDiagSignatures.put(side, signature.toString());
                for (var bus : collected) {
                    AppliedFactory.LOGGER.info(
                            "[bus-diag] side={} partId={} hostBeId={} pos={} partSide={}",
                            side.getName(),
                            System.identityHashCode(bus),
                            System.identityHashCode(bus.hostBlockEntityForDiagnostics()),
                            bus.getHostPosition(),
                            bus.getSide());
                }
            }
            result.put(side, collected);
        }
        return result;
    }

    public List<FactoryBusPart> getFactoryBuses() {
        var unique = new LinkedHashSet<FactoryBusPart>();
        busesByNetwork().values().forEach(unique::addAll);
        return List.copyOf(unique);
    }

    private Optional<FactoryBusPart> resolveBus(
            com.fulent.appliedfactory.factory.FactoryBusAddress address) {
        return getFactoryBuses().stream()
                .filter(bus -> bus.address().filter(address::equals).isPresent())
                .findFirst();
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

    /** Drops pattern and cell items; cached resources remain physically inside cell items. */
    public void dropOwnedContents() {
        if (level == null || level.isClientSide) {
            return;
        }
        dropInventory(patternInventory);
        dropInventory(cache.inventory());
        if (program != null) {
            program.discard();
        }
        setChanged();
    }

    private void dropInventory(ItemStackHandler inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(
                        level,
                        worldPosition.getX(),
                        worldPosition.getY(),
                        worldPosition.getZ(),
                        stack.copy());
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
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
