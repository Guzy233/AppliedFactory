package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.part.FactoryBusPart;
import com.fulent.appliedfactory.script.CompiledControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgramCompiler;
import com.fulent.appliedfactory.script.FactoryActionResult;
import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ProgramLoadResult;
import com.fulent.appliedfactory.script.ScriptContinuation;
import com.fulent.appliedfactory.script.ScriptHandlerRef;
import com.fulent.appliedfactory.script.ScriptRuntime;
import com.fulent.appliedfactory.script.ScriptStep;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Owns the compiled script program and its suspended jobs for exactly one controller block
 * entity. A controller never shares a program instance with another entity: the Rhino scope
 * is bound to this program's runtime, and every job's continuation is only resumable against
 * that same scope.
 *
 * <p>Responsibilities: evaluating the source, running the initializer (as an in-memory
 * {@link InitializerJob}) whenever the host reports a change (grid state, bus topology),
 * offering pattern→handler bindings ({@link #compiled()}), starting processing jobs, cancelling
 * them when their AE crafting request is cancelled, and advancing every suspended job
 * once per server tick via {@link #step()}. A job exists only while suspended; ending a job
 * immediately removes it, handing any leftover owned resources to the recovery queue, which
 * retries returning them until the network accepts them. Recompiling ({@link #replace}) cancels
 * every job of the old program and transfers its recovery entries to the new one.
 */
public final class FactoryProgram {
    public static final int MAX_JOBS = 64;
    private static final String JOBS_NBT_KEY = "FactoryJobs";
    private static final String RECOVERY_NBT_KEY = "FactoryRecovery";

    /** World-facing services the owning block entity provides to the program. */
    public interface Host {
        long tick();

        HolderLookup.Provider registries();

        Map<Direction, List<FactoryBusPart>> busesByNetwork();

        Set<Direction> onlineNetworks();

        boolean returnOwned(List<FactoryResource> owned, Direction side);

        FactoryActionResult performAction(FactoryJob job, FactoryScriptAction action);

        void reportScriptFailure(String stage, String message);

        void markChanged();

        /**
         * Returns the total amount of {@code key} the grid attached to {@code side} is currently
         * crafting for (0 when the side is offline or no CPU awaits it). Used to verify restored
         * processing jobs whose cancellation callback may have been missed while the chunk was
         * unloaded.
         */
        long requestedCraftingAmount(Direction side, AEKey key);
    }

    private final ScriptRuntime runtime;
    private final CompiledControllerProgram program;
    private final Host host;

    private boolean initialized;
    private boolean attempted;
    private boolean environmentDirty;
    /**
     * Topology signature the initializer last ran against. Computed only when a change event marks
     * the environment dirty (never per tick), and compared against the current signature so
     * spurious events (machine interactions, channel recalcs) that leave the watched topology
     * unchanged do not re-run the initializer.
     */
    private long lastSignature;
    /**
     * True while a re-run initializer is in flight after a real topology change. New jobs are not
     * accepted until it completes, mirroring the old fingerprint-based behavior.
     */
    private boolean reinitializing;
    /**
     * Set when jobs are restored from disk: a crafting request may have been cancelled while the
     * controller chunk was unloaded (the cancellation callback was missed), so the restored
     * processing jobs are verified once against the grid's crafting service on a later step.
     */
    private boolean orphanCheckPending;

    private final List<FactoryJob> jobs = new ArrayList<>();
    private final List<RecoveryEntry> recovery = new ArrayList<>();
    private final Set<Integer> stoppedPassives = new HashSet<>();
    private final Set<UUID> reportedRecoveryFailures = new HashSet<>();
    /**
     * Crafting request ids reported cancelled by AE2 CPUs. Processed at the start of the next
     * {@link #step()} so the cancellation never re-enters AE2's storage/grid services from inside
     * their own tick.
     */
    private final Set<UUID> pendingCancellations = new HashSet<>();
    /**
     * The active initializer, in memory only (never persisted — after a chunk reload the
     * program simply re-initializes). Non-null while initialization is pending or suspended.
     */
    private InitializerJob initializerJob;

    private FactoryProgram(
            ScriptRuntime runtime, CompiledControllerProgram program, Host host) {
        this.runtime = runtime;
        this.program = program;
        this.host = host;
    }

    /** Compiles the source into a fresh, isolated program instance. */
    public static ProgramLoadResult<FactoryProgram> load(String source, Host host) {
        var runtime = ControllerProgramCompiler.createRuntime();
        var result = runtime.loadProgram(source);
        if (!result.successful()) {
            return ProgramLoadResult.failure(result.errorMessage());
        }
        return ProgramLoadResult.success(new FactoryProgram(runtime, result.program(), host));
    }

    /**
     * Compiles {@code newSource} first (an invalid edit must not replace the running program);
     * on success cancels every job of {@code current} and transfers its recovery entries to
     * the replacement, then returns the new instance.
     */
    public static ProgramLoadResult<FactoryProgram> replace(
            FactoryProgram current, String newSource, Host host) {
        var result = load(newSource, host);
        if (!result.successful()) {
            return result;
        }
        var replacement = result.program();
        if (current != null) {
            replacement.recovery.addAll(current.cancelAll());
        }
        return ProgramLoadResult.success(replacement);
    }

    // ---- Compiled manifest ---------------------------------------------------

    public CompiledControllerProgram compiled() {
        return program;
    }

    public boolean canAcceptJobs() {
        return ensureInitialized() && activeProcessingJobs() < MAX_JOBS;
    }

    public boolean hasLockedCache() {
        if (!recovery.isEmpty()) {
            return true;
        }
        return jobs.stream().anyMatch(job -> !job.owned().isEmpty());
    }

    /**
     * Forces the initializer to re-run on the next step. Called by the host on grid state
     * changes and on bus topology events (bus added/removed, bus target changed), replacing the
     * old per-tick topology fingerprint.
     */
    public void markEnvironmentChanged() {
        environmentDirty = true;
    }

    // ---- Ticking -------------------------------------------------------------

    /**
     * Advances the initializer, every suspended job and pending recovery by one step. Runs
     * once per server tick. Until initialization completes, no passives start, no jobs advance
     * and no recovery is retried — the factory waits for its initializer.
     */
    public void step() {
        // Cancellations are processed regardless of initialization state: a cancelled job should
        // be cleaned up even while the factory waits for its initializer.
        processCancellations();
        stepInitializer();
        if (!initialized) {
            return;
        }
        startMissingPassives();
        var now = host.tick();
        for (var job : List.copyOf(jobs)) {
            if (!jobs.contains(job)) {
                continue;
            }
            advance(job, now);
        }
        checkRestoredOrphans();
        processRecovery();
    }

    /**
     * Cancels every processing job pushed for the given AE crafting request, called by the host
     * when a crafting CPU reports its request cancelled. The job is finished on the next
     * {@link #step()} (see {@link #pendingCancellations}); owned resources are returned to the
     * network like any other job end.
     */
    public void cancelJobs(UUID craftingRequestId) {
        pendingCancellations.add(craftingRequestId);
    }

    private void processCancellations() {
        if (pendingCancellations.isEmpty()) {
            return;
        }
        for (var craftingId : pendingCancellations) {
            for (var job : List.copyOf(jobs)) {
                if (!(job instanceof ProcessingJob processing)
                        || !craftingId.equals(processing.craftingRequestId())
                        || !jobs.contains(job)) {
                    continue;
                }
                AppliedFactory.LOGGER.info(
                        "Factory workflow {} cancelled: its AE crafting request was cancelled",
                        job.id());
                finishJob(job, null);
            }
        }
        pendingCancellations.clear();
    }

    /**
     * Verifies restored processing jobs once: their crafting request may have been cancelled
     * while the chunk was unloaded, so the cancellation callback was missed. Compares the grid's
     * currently-crafted amounts for the job outputs to the zero they would be if the request is
     * gone. This is a one-shot check, not a per-tick poll.
     */
    private void checkRestoredOrphans() {
        if (!orphanCheckPending) {
            return;
        }
        var allGridsReady = true;
        for (var job : List.copyOf(jobs)) {
            if (!(job instanceof ProcessingJob processing) || !jobs.contains(job)) {
                continue;
            }
            var orderSide = processing.orderSide();
            if (!host.onlineNetworks().contains(orderSide)) {
                allGridsReady = false;
                continue;
            }
            var awaiting = processing.outputKeys().stream()
                    .mapToLong(key -> host.requestedCraftingAmount(orderSide, key))
                    .sum();
            if (awaiting == 0) {
                AppliedFactory.LOGGER.info(
                        "Factory workflow {} cancelled: its AE crafting request is gone", job.id());
                finishJob(job, null);
            }
        }
        if (allGridsReady) {
            orphanCheckPending = false;
        }
    }

    /** Starts a processing job for an offered pattern; false when the job was not accepted. */
    public boolean startJob(
            ScriptHandlerRef handler,
            Direction orderSide,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs) {
        if (!canAcceptJobs()) {
            return false;
        }
        var workflowId = UUID.randomUUID();
        var job = new ProcessingJob(
                workflowId,
                host,
                EnumSet.allOf(Direction.class),
                handler,
                orderSide,
                inputs,
                outputs,
                inputs,
                ScriptContinuation.empty(),
                null,
                0,
                CraftingRequestContext.current());
        var step = job.start(runtime);
        if (step instanceof ScriptStep.Failed failed) {
            host.reportScriptFailure("processing start", failed.message());
            return false;
        }
        if (step instanceof ScriptStep.Suspended suspended) {
            job.setSuspended(suspended.continuation(), suspended.action(), host.tick());
            jobs.add(job);
        } else {
            // The handler returned without suspending: the cached inputs are immediately
            // returned to the order network (or held for recovery while it is offline).
            jobs.add(job);
            finishJob(job, null);
        }
        host.markChanged();
        return true;
    }

    /**
     * Ends every job and collects all pending recovery (existing entries plus each job's owned
     * resources) so the caller can hand them to a replacement program. Passive handlers restart
     * fresh in the new program.
     */
    public List<RecoveryEntry> cancelAll() {
        var entries = new ArrayList<>(recovery);
        recovery.clear();
        for (var job : jobs) {
            if (!job.owned().isEmpty()) {
                entries.add(new RecoveryEntry(job.id(), job.owned(), job.recoverySide()));
            }
        }
        if (initializerJob != null && !initializerJob.owned().isEmpty()) {
            entries.add(new RecoveryEntry(
                    initializerJob.id(), initializerJob.owned(), initializerJob.recoverySide()));
        }
        jobs.clear();
        initializerJob = null;
        stoppedPassives.clear();
        reportedRecoveryFailures.clear();
        initialized = false;
        attempted = false;
        reinitializing = false;
        environmentDirty = true;
        return entries;
    }

    /** Drops every job and recovery entry without returning resources (block destroyed). */
    public void discard() {
        jobs.clear();
        recovery.clear();
        stoppedPassives.clear();
        reportedRecoveryFailures.clear();
        initializerJob = null;
    }

    // ---- Persistence ---------------------------------------------------------

    public void saveJobs(CompoundTag tag, HolderLookup.Provider registries) {
        var savedJobs = new ListTag();
        for (int index = 0; index < jobs.size();) {
            var job = jobs.get(index);
            try {
                savedJobs.add(job.save(registries));
                index++;
            } catch (RuntimeException exception) {
                // A continuation that cannot be serialized means the job cannot survive a
                // chunk save. Degrade it to a recovery entry now (in memory and on disk alike)
                // so its owned resources are still returned once the network accepts them, and
                // no duplicate entries pile up on repeated saves.
                AppliedFactory.LOGGER.error(
                        "Factory workflow {} continuation could not be serialized; "
                                + "returning its owned resources via recovery",
                        job.id(), exception);
                if (!job.owned().isEmpty()) {
                    recovery.add(new RecoveryEntry(job.id(), job.owned(), job.recoverySide()));
                }
                jobs.remove(index);
            }
        }
        if (!savedJobs.isEmpty()) {
            tag.put(JOBS_NBT_KEY, savedJobs);
        }
        var savedRecovery = new ListTag();
        for (var entry : recovery) {
            savedRecovery.add(entry.save(registries));
        }
        if (!savedRecovery.isEmpty()) {
            tag.put(RECOVERY_NBT_KEY, savedRecovery);
        }
    }

    public void loadJobs(CompoundTag tag, HolderLookup.Provider registries) {
        jobs.clear();
        recovery.clear();
        var savedJobs = tag.getList(JOBS_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedJobs.size(); index++) {
            FactoryJob.load(savedJobs.getCompound(index), host, registries).ifPresent(jobs::add);
        }
        var savedRecovery = tag.getList(RECOVERY_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedRecovery.size(); index++) {
            RecoveryEntry.load(savedRecovery.getCompound(index), registries)
                    .ifPresent(recovery::add);
        }
        orphanCheckPending = jobs.stream().anyMatch(job -> job instanceof ProcessingJob);
    }

    // ---- Internals -----------------------------------------------------------

    private int activeProcessingJobs() {
        return (int) jobs.stream().filter(job -> job instanceof ProcessingJob).count();
    }

    /** Whether initialization is complete for the current environment. */
    private boolean ensureInitialized() {
        return initialized && !environmentDirty && !reinitializing;
    }

    /**
     * Creates or advances the initializer until initialization completes or fails. The initial
     * run happens regardless of events; afterwards it only re-runs when an event marks the
     * environment dirty AND the topology signature actually changed, so spurious events that do
     * not alter the watched topology (machine interactions, channel recalculation) are ignored.
     * While the initializer is suspended, a change keeps advancing the same job (its live getters
     * read the new environment) rather than restarting it; a finished or failed attempt is not
     * retried until another event changes the topology again.
     */
    private void stepInitializer() {
        if (!environmentDirty && initialized && initializerJob == null) {
            return;
        }
        if (!environmentDirty && initializerJob == null && attempted) {
            // A previous attempt finished; wait for a change event before retrying.
            return;
        }
        if (initializerJob != null) {
            // A suspended initializer keeps advancing; record the current topology so the next
            // change event can be compared against it. Its live getters read the new topology.
            if (environmentDirty) {
                lastSignature = topologySignature();
                environmentDirty = false;
            }
            advance(initializerJob, host.tick());
            return;
        }
        var signature = topologySignature();
        if (initialized && signature == lastSignature) {
            // An event fired but the watched topology is unchanged — nothing to re-initialize.
            environmentDirty = false;
            return;
        }
        reinitializing = initialized;
        attempted = true;
        lastSignature = signature;
        environmentDirty = false;
        initializerJob = new InitializerJob(UUID.randomUUID(), host,
                program.initializerNetworks());
        var step = initializerJob.start(runtime);
        if (step instanceof ScriptStep.Suspended suspended) {
            initializerJob.setSuspended(
                    suspended.continuation(), suspended.action(), host.tick());
            host.markChanged();
        } else {
            finishJob(initializerJob,
                    step instanceof ScriptStep.Failed failed ? failed.message() : null);
        }
    }

    private void startMissingPassives() {
        for (int index = 0; index < program.passiveHandlerCount(); index++) {
            if (stoppedPassives.contains(index)) {
                continue;
            }
            var passiveIndex = index;
            var exists = jobs.stream().anyMatch(job -> job instanceof PassiveJob passive
                    && passive.passiveIndex() == passiveIndex);
            if (exists) {
                continue;
            }
            var workflowId = UUID.randomUUID();
            var job = new PassiveJob(
                    workflowId, host, EnumSet.allOf(Direction.class), passiveIndex, List.of(),
                    ScriptContinuation.empty(), null, 0);
            var step = job.start(runtime);
            if (step instanceof ScriptStep.Suspended suspended) {
                job.setSuspended(suspended.continuation(), suspended.action(), host.tick());
                jobs.add(job);
            } else {
                if (step instanceof ScriptStep.Failed failed) {
                    host.reportScriptFailure("passive " + passiveIndex + " stopped",
                            failed.message());
                }
                stoppedPassives.add(passiveIndex);
            }
            host.markChanged();
        }
    }

    private void advance(FactoryJob job, long now) {
        var action = job.pendingAction();
        if (action == null) {
            finishJob(job, "Workflow has no pending action");
            return;
        }
        if (action.type() == FactoryScriptAction.Type.SLEEP) {
            if (now - job.actionStartedTick() >= action.sleepTicks()) {
                resume(job, FactoryActionResult.slept());
            }
            return;
        }
        try {
            resume(job, host.performAction(job, action));
        } catch (RuntimeException exception) {
            AppliedFactory.LOGGER.error("Factory action failed", exception);
            finishJob(job, exception.getMessage());
        }
    }

    private void resume(FactoryJob job, FactoryActionResult result) {
        var step = job.resume(runtime, result);
        if (step instanceof ScriptStep.Suspended suspended) {
            job.setSuspended(suspended.continuation(), suspended.action(), host.tick());
            host.markChanged();
        } else if (step instanceof ScriptStep.Completed) {
            finishJob(job, null);
        } else {
            var message = messageOf(step);
            if (job != initializerJob) {
                // Initializer failures are reported by finishJob as "initializer".
                host.reportScriptFailure("workflow resume", message);
            }
            finishJob(job, message);
        }
    }

    /**
     * Ends a job: records the initializer/passive scheduling outcome, reports failures, then
     * returns the job's owned resources (or defers them to the recovery queue).
     */
    private void finishJob(FactoryJob job, @Nullable String failureMessage) {
        if (job == initializerJob) {
            initializerJob = null;
            reinitializing = false;
            if (failureMessage == null) {
                initialized = true;
            } else {
                initialized = false;
                host.reportScriptFailure("initializer", failureMessage);
            }
        } else {
            if (job instanceof PassiveJob passive) {
                stoppedPassives.add(passive.passiveIndex());
            }
            if (failureMessage != null) {
                AppliedFactory.LOGGER.error("Factory workflow {} failed: {}",
                        job.id(), failureMessage);
            }
        }
        finalizeOwned(job);
    }

    /** Removes the job after returning its owned resources; offline/full networks defer them. */
    private void finalizeOwned(FactoryJob job) {
        if (job.owned().isEmpty()) {
            jobs.remove(job);
            host.markChanged();
            return;
        }
        var side = firstOnline(job.recoverySide());
        if (side != null) {
            try {
                if (host.returnOwned(job.owned(), side)) {
                    jobs.remove(job);
                    host.markChanged();
                    return;
                }
            } catch (RuntimeException exception) {
                // e.g. an AE storage violating its insertion simulation; keep the resources
                // owned and retry via the recovery queue instead of aborting this tick.
                AppliedFactory.LOGGER.error(
                        "Factory workflow {} could not return its owned resources; "
                                + "deferring them to recovery",
                        job.id(), exception);
            }
        }
        recovery.add(new RecoveryEntry(job.id(), job.owned(), side));
        jobs.remove(job);
        host.markChanged();
    }

    private void processRecovery() {
        for (var entry : List.copyOf(recovery)) {
            var side = firstOnline(entry.recoverySide());
            if (side == null) {
                continue;
            }
            try {
                if (host.returnOwned(entry.owned(), side)) {
                    recovery.remove(entry);
                    host.markChanged();
                }
            } catch (RuntimeException exception) {
                if (reportedRecoveryFailures.add(entry.id())) {
                    AppliedFactory.LOGGER.error(
                            "Factory recovery {} could not return its owned resources; "
                                    + "retaining it for recovery",
                            entry.id(), exception);
                }
            }
        }
    }

    /** Prefers the given side when it is online, otherwise any online network. */
    @Nullable
    private Direction firstOnline(@Nullable Direction preferred) {
        var online = host.onlineNetworks();
        if (preferred != null && online.contains(preferred)) {
            return preferred;
        }
        return online.isEmpty() ? null : online.iterator().next();
    }

    /**
     * A cheap hash of exactly the watched topology: each initializer network's online state and
     * bus set, plus each bus target's machine identity. Computed only when a change event marks
     * the environment dirty (never per tick) and compared against {@link #lastSignature} to skip
     * spurious initializer re-runs.
     */
    private long topologySignature() {
        long result = 1;
        var buses = host.busesByNetwork();
        var online = host.onlineNetworks();
        for (var side : Direction.values()) {
            if (!program.initializerNetworks().contains(side)) {
                continue;
            }
            result = 31 * result + side.ordinal();
            result = 31 * result + Boolean.hashCode(online.contains(side));
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

    private static String messageOf(ScriptStep step) {
        return step instanceof ScriptStep.Failed failed
                ? failed.message()
                : "Ended unexpectedly: " + step.getClass().getSimpleName();
    }
}
