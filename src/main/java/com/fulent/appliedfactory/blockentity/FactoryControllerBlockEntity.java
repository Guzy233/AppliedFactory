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
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.part.FactoryBusPart;
import com.fulent.appliedfactory.script.CompiledControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgramCompiler;
import com.fulent.appliedfactory.script.FactoryActionResult;
import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ProgramLoadResult;
import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptHandlerRef;
import com.fulent.appliedfactory.script.ScriptRuntime;
import com.fulent.appliedfactory.script.ScriptStep;

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
        IInWorldGridNodeHost, IPowerChannelState {
    public static final int PATTERN_SLOTS = 9;
    public static final int CACHE_SLOTS = 3;
    private static final int MAX_FACTORY_JOBS = 64;
    private static final double MAX_POWER_TRANSFER_PER_NETWORK = 1_024.0D;
    private static final double POWER_EPSILON = 0.0001D;
    private static final String PATTERNS_NBT_KEY = "Patterns";
    private static final String CACHE_NBT_KEY = "FactoryCache";
    private static final String JOBS_NBT_KEY = "FactoryJobs";
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
    private final Map<String, RuntimeState> runtimes = new LinkedHashMap<>();
    private final List<FactoryJob> jobs = new ArrayList<>();
    private final Set<UUID> reportedRecoveryFailures = new LinkedHashSet<>();
    private final Set<UUID> errorSubscribers = new LinkedHashSet<>();
    private final Set<String> reportedScriptFailures = new LinkedHashSet<>();

    private List<OfferedPattern> offeredPatterns = List.of();
    private CompiledControllerProgram compiledProgram = CompiledControllerProgram.EMPTY;
    private String controllerProgram = ControllerProgram.DEFAULT_SOURCE;
    private boolean patternsDirty = true;
    private boolean programDirty = true;
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
        jobs.clear();
        reportedRecoveryFailures.clear();
        errorSubscribers.clear();
        reportedScriptFailures.clear();
        var savedSubscribers = tag.getList(ERROR_SUBSCRIBERS_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedSubscribers.size(); index++) {
            var subscriber = savedSubscribers.getCompound(index);
            if (subscriber.hasUUID(ERROR_SUBSCRIBER_ID_NBT_KEY)) {
                errorSubscribers.add(subscriber.getUUID(ERROR_SUBSCRIBER_ID_NBT_KEY));
            }
        }
        var savedJobs = tag.getList(JOBS_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedJobs.size(); index++) {
            FactoryJob.load(savedJobs.getCompound(index), registries).ifPresent(jobs::add);
        }
        networkNodes.values().forEach(node -> node.loadFromNBT(tag));
        runtimes.clear();
        invalidateProgramAndPatterns();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        cache.persistCells();
        tag.put(PATTERNS_NBT_KEY, patternInventory.serializeNBT(registries));
        tag.put(CACHE_NBT_KEY, cache.inventory().serializeNBT(registries));
        tag.putString(ControllerProgram.NBT_KEY, controllerProgram);
        if (!suppressJobPersistence) {
            var savedJobs = new ListTag();
            for (var job : jobs) {
                savedJobs.add(job.save(registries));
            }
            tag.put(JOBS_NBT_KEY, savedJobs);
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
            var tag = saveWithoutMetadata(registries);
            tag.remove(JOBS_NBT_KEY);
            return tag;
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
        return jobs.stream().anyMatch(job -> !job.owned().isEmpty());
    }

    public String getControllerProgram() {
        return controllerProgram;
    }

    public CompiledControllerProgram getCompiledProgram() {
        ensureCurrentProgramLoaded();
        return compiledProgram;
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
     * Compiles before committing so an invalid edit cannot replace the currently running program.
     */
    public ProgramLoadResult updateControllerProgram(String source) {
        var runtime = ControllerProgramCompiler.createRuntime();
        var result = runtime.loadProgram(source);
        if (!result.successful()) {
            return result;
        }
        // A continuation captures its original script globals and local handles. Keeping a
        // processing job alive after replacing its program means it can keep retrying an
        // obsolete action while holding resources in the private cache. Mark every live job
        // for normal recovery instead; tickFactoryJobs() returns its exact owned resources to
        // the job's recorded order/recovery network before removing it.
        //
        // For passive jobs of the *current* program, finishJob() intentionally doesn't remove
        // them (they restart automatically). But if we're reloading the exact same source,
        // those finished passive jobs would never restart. Remove them explicitly so
        // startMissingPassives() can launch fresh ones.
        for (var job : jobs) {
            if (!job.finished()) {
                job.markFinished();
            }
        }
        jobs.removeIf(job -> job.kind() == FactoryJob.Kind.PASSIVE
                && job.programSource().equals(controllerProgram)
                && job.finished());
        controllerProgram = source;
        runtimes.clear();
        runtimes.put(source, new RuntimeState(runtime, result.program(), true));
        reportedScriptFailures.clear();
        invalidateProgramAndPatterns();
        markChangedAndSync();
        return result;
    }

    private void ensureCurrentProgramLoaded() {
        if (!programDirty) {
            return;
        }
        var state = runtimeFor(controllerProgram);
        compiledProgram = state == null
                ? CompiledControllerProgram.EMPTY
                : state.program;
        programDirty = false;
    }

    private RuntimeState runtimeFor(String source) {
        var existing = runtimes.get(source);
        if (existing != null) {
            return existing.valid ? existing : null;
        }
        var runtime = ControllerProgramCompiler.createRuntime();
        var loaded = runtime.loadProgram(source);
        var state = loaded.successful()
                ? new RuntimeState(runtime, loaded.program(), true)
                : new RuntimeState(runtime, CompiledControllerProgram.EMPTY, false);
        runtimes.put(source, state);
        if (!loaded.successful()) {
            reportScriptFailure("program load", loaded.errorMessage());
        }
        return state.valid ? state : null;
    }

    private boolean ensureInitialized(RuntimeState state) {
        if (level == null || !state.valid) {
            return false;
        }
        var fingerprint = topologyFingerprint(state.program.initializerNetworks());
        if (state.initialized && state.lastTopology == fingerprint) {
            return true;
        }
        if (state.attempted && state.lastAttemptedTopology == fingerprint) {
            return false;
        }
        state.attempted = true;
        state.lastAttemptedTopology = fingerprint;
        var step = state.runtime.runInitializer(createContext(
                new UUID(0, 0), null, List.of(), List.of(), List.of(),
                state.program.initializerNetworks()));
        if (step instanceof ScriptStep.Completed) {
            state.initialized = true;
            state.lastTopology = fingerprint;
            return true;
        }
        state.initialized = false;
        logScriptFailure("initializer", step);
        return false;
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
        ensureCurrentProgramLoaded();
        var offers = new ArrayList<OfferedPattern>();
        if (level != null && compiledProgram.hasControllerHandler()) {
            for (int slot = 0; slot < patternInventory.getSlots(); slot++) {
                var details = PatternDetailsHelper.decodePattern(
                        patternInventory.getStackInSlot(slot), level);
                if (details != null) {
                    offers.add(new OfferedPattern(
                            details,
                            compiledProgram.controllerOrderNetwork(),
                            ScriptHandlerRef.controller()));
                }
            }
        }
        if (level != null) {
            for (var pattern : compiledProgram.scriptPatterns()) {
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

    private void invalidateProgramAndPatterns() {
        programDirty = true;
        compiledProgram = CompiledControllerProgram.EMPTY;
        invalidatePatterns();
    }

    private boolean pushPattern(
            Direction networkSide,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder) {
        if (level == null || activeProcessingJobs() >= MAX_FACTORY_JOBS) {
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

        var runtime = runtimeFor(controllerProgram);
        if (runtime == null || !ensureInitialized(runtime)) {
            requireCacheRollback(inputs);
            return false;
        }
        var workflowId = UUID.randomUUID();
        var context = createContext(
                workflowId, networkSide, inputs, outputs, inputs,
                EnumSet.allOf(Direction.class));
        var step = runtime.runtime.startProcessing(offered.handler, context);
        if (step instanceof ScriptStep.Failed) {
            requireCacheRollback(inputs);
            logScriptFailure("processing start", step);
            return false;
        }
        if (step instanceof ScriptStep.Suspended suspended) {
            jobs.add(FactoryJob.processing(
                    workflowId,
                    networkSide,
                    controllerProgram,
                    inputs,
                    outputs,
                    suspended.continuation(),
                    suspended.action(),
                    level.getGameTime()));
        } else {
            jobs.add(FactoryJob.completedProcessing(
                    workflowId, networkSide, controllerProgram, inputs, outputs));
        }
        setChanged();
        return true;
    }

    private void requireCacheRollback(List<FactoryResource> resources) {
        if (!cache.removeAll(resources)) {
            throw new IllegalStateException("Factory cache could not roll back rejected pattern input");
        }
    }

    private int activeProcessingJobs() {
        return (int) jobs.stream()
                .filter(job -> job.kind() == FactoryJob.Kind.PROCESSING && !job.finished())
                .count();
    }

    private boolean isBusy(Direction side) {
        var state = runtimeFor(controllerProgram);
        return state == null
                || !ensureInitialized(state)
                || !cache.hasStorageCell()
                || activeProcessingJobs() >= MAX_FACTORY_JOBS
                || availablePatterns(side).isEmpty();
    }

    private void tickFactoryJobs() {
        if (level == null || level.isClientSide) {
            return;
        }
        ensureCurrentProgramLoaded();
        var currentRuntime = runtimeFor(controllerProgram);
        if (currentRuntime != null && ensureInitialized(currentRuntime)) {
            startMissingPassives(currentRuntime);
        }

        var now = level.getGameTime();
        for (var job : List.copyOf(jobs)) {
            if (job.finished()) {
                try {
                    finishJob(job);
                    if (!jobs.contains(job)) {
                        reportedRecoveryFailures.remove(job.id());
                    }
                } catch (RuntimeException exception) {
                    if (reportedRecoveryFailures.add(job.id())) {
                        AppliedFactory.LOGGER.error(
                                "Factory workflow {} could not return its owned resources; retaining it for recovery",
                                job.id(), exception);
                    }
                }
                continue;
            }
            var runtime = runtimeFor(job.programSource());
            if (runtime == null || !ensureInitialized(runtime)) {
                continue;
            }
            var action = job.pendingAction();
            if (action == null) {
                failJob(job, "Workflow has no pending action");
                continue;
            }
            if (action.type() == FactoryScriptAction.Type.SLEEP) {
                if (now - job.actionStartedTick() >= action.sleepTicks()) {
                    resumeJob(runtime, job, FactoryActionResult.slept());
                }
                continue;
            }
            try {
                var result = actionExecutor.perform(job, action);
                resumeJob(runtime, job, result);
            } catch (RuntimeException exception) {
                AppliedFactory.LOGGER.error("Factory action failed", exception);
                failJob(job, exception.getMessage());
            }
        }
        pruneUnusedRuntimes();
    }

    private void startMissingPassives(RuntimeState runtime) {
        for (int index = 0; index < runtime.program.passiveHandlerCount(); index++) {
            var passiveIndex = index;
            var exists = jobs.stream().anyMatch(job -> job.kind() == FactoryJob.Kind.PASSIVE
                    && job.programSource().equals(controllerProgram)
                    && job.passiveIndex() == passiveIndex);
            if (exists) {
                continue;
            }
            var workflowId = UUID.randomUUID();
            var step = runtime.runtime.startPassive(index, createContext(
                    workflowId, null, List.of(), List.of(), List.of(),
                    EnumSet.allOf(Direction.class)));
            if (step instanceof ScriptStep.Suspended suspended) {
                jobs.add(FactoryJob.passive(
                        workflowId,
                        controllerProgram,
                        index,
                        suspended.continuation(),
                        suspended.action(),
                        level.getGameTime()));
            } else {
                logScriptFailure("passive " + index + " stopped", step);
                jobs.add(FactoryJob.stoppedPassive(controllerProgram, index));
            }
            setChanged();
        }
    }

    private void resumeJob(
            RuntimeState runtime, FactoryJob job, FactoryActionResult result) {
        var step = runtime.runtime.resume(
                createContext(
                        job.id(),
                        job.orderSide(),
                        job.inputs(),
                        job.outputs(),
                        job.owned(),
                        EnumSet.allOf(Direction.class)),
                job.continuation(),
                result);
        if (step instanceof ScriptStep.Suspended suspended) {
            job.setSuspended(
                    suspended.continuation(), suspended.action(), level.getGameTime());
        } else if (step instanceof ScriptStep.Completed) {
            job.markFinished();
        } else {
            logScriptFailure("workflow resume", step);
            job.markFinished();
        }
        setChanged();
    }

    private void failJob(FactoryJob job, String message) {
        AppliedFactory.LOGGER.error("Factory workflow {} failed: {}", job.id(), message);
        job.markFinished();
        setChanged();
    }

    private void finishJob(FactoryJob job) {
        if (!job.owned().isEmpty()) {
            var recoverySide = job.recoverySide();
            if (recoverySide == null) {
                recoverySide = firstOnlineNetwork().orElse(null);
            }
            if (recoverySide == null || !actionExecutor.returnOwned(job, recoverySide)) {
                return;
            }
        }
        if (job.kind() == FactoryJob.Kind.PASSIVE
                && job.programSource().equals(controllerProgram)) {
            return;
        }
        jobs.remove(job);
        setChanged();
    }

    private Optional<Direction> firstOnlineNetwork() {
        for (var side : Direction.values()) {
            if (networkNodes.get(side).isOnline()) {
                return Optional.of(side);
            }
        }
        return Optional.empty();
    }

    private ScriptExecutionContext createContext(
            UUID workflowId,
            Direction orderNetwork,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            List<FactoryResource> owned,
            Set<Direction> accessibleNetworks) {
        var buses = busesByNetwork();
        var online = EnumSet.noneOf(Direction.class);
        for (var side : Direction.values()) {
            var node = networkNodes.get(side);
            if (node != null && node.isOnline() && node.getGrid() != null) {
                online.add(side);
            }
        }
        return new ScriptExecutionContext(
                workflowId,
                level == null ? 0L : level.getGameTime(),
                orderNetwork,
                inputs,
                outputs,
                owned,
                buses,
                accessibleNetworks,
                online,
                level.registryAccess());
    }

    private final EnumMap<Direction, String> lastBusDiagSignatures = new EnumMap<>(Direction.class);

    private Map<Direction, List<FactoryBusPart>> busesByNetwork() {
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

    private long topologyFingerprint(Set<Direction> watchedNetworks) {
        long result = 1;
        var buses = busesByNetwork();
        for (var side : Direction.values()) {
            if (!watchedNetworks.contains(side)) {
                continue;
            }
            var node = networkNodes.get(side);
            result = 31 * result + side.ordinal();
            result = 31 * result + Boolean.hashCode(node != null && node.isOnline());
            result = 31 * result + System.identityHashCode(node == null ? null : node.getGrid());
            for (var bus : buses.getOrDefault(side, List.of())) {
                result = 31 * result + bus.address().hashCode();
                var machine = bus.machine().orElse(null);
                result = 31 * result + (machine == null ? 0 : machine.blockId().hashCode());
                result = 31 * result + Boolean.hashCode(
                        machine != null && machine.hasItemStorage());
                result = 31 * result + (machine == null || machine.blockEntityTypeId() == null
                        ? 0 : machine.blockEntityTypeId().hashCode());
            }
        }
        return result;
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

    private void pruneUnusedRuntimes() {
        var usedSources = new LinkedHashSet<String>();
        usedSources.add(controllerProgram);
        jobs.stream().filter(job -> !job.finished()).map(FactoryJob::programSource)
                .forEach(usedSources::add);
        runtimes.keySet().removeIf(source -> !usedSources.contains(source));
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

    private void logScriptFailure(String stage, ScriptStep step) {
        if (step instanceof ScriptStep.Failed failed) {
            reportScriptFailure(stage, failed.message());
        } else if (!(step instanceof ScriptStep.Completed)) {
            reportScriptFailure(stage, "Ended unexpectedly: " + step.getClass().getSimpleName());
        }
    }

    private void reportScriptFailure(String stage, String message) {
        AppliedFactory.LOGGER.error("Factory {} failed: {}", stage, message);
        if (!reportedScriptFailures.add(stage + '\n' + message)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        var notification = Component.translatable(
                "chat.mefactorymanager.script_error",
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
            // 运行刻任务
            controller.tickFactoryJobs();
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
        jobs.clear();
        reportedRecoveryFailures.clear();
        runtimes.clear();
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

    private static final class RuntimeState {
        private final ScriptRuntime runtime;
        private final CompiledControllerProgram program;
        private final boolean valid;
        private boolean initialized;
        private boolean attempted;
        private long lastTopology;
        private long lastAttemptedTopology;

        private RuntimeState(
                ScriptRuntime runtime, CompiledControllerProgram program, boolean valid) {
            this.runtime = runtime;
            this.program = program;
            this.valid = valid;
        }
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
