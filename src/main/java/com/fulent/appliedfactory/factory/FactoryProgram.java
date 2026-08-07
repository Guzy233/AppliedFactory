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
import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptHandlerRef;
import com.fulent.appliedfactory.script.ScriptRuntime;
import com.fulent.appliedfactory.script.ScriptStep;

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
 * <p>Responsibilities: evaluating the source, running the initializer when the watched
 * topology changes, offering pattern→handler bindings ({@link #compiled()}), starting
 * processing jobs, and advancing every suspended job once per server tick via {@link #step()}.
 * A job exists only while suspended; ending a job immediately removes it, handing any leftover
 * owned resources to the recovery queue, which retries returning them until the network accepts
 * them. Recompiling ({@link #replace}) cancels every job of the old program and transfers its
 * recovery entries to the new one.
 */
public final class FactoryProgram {
    public static final int MAX_JOBS = 16;
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
    }

    private final ScriptRuntime runtime;
    private final CompiledControllerProgram program;
    private final Host host;

    private boolean initialized;
    private boolean attempted;
    private long lastTopology;
    private long lastAttemptedTopology;
    private boolean environmentDirty;

    private final List<FactoryJob> jobs = new ArrayList<>();
    private final List<RecoveryEntry> recovery = new ArrayList<>();
    private final Set<Integer> stoppedPassives = new HashSet<>();
    private final Set<UUID> reportedRecoveryFailures = new HashSet<>();

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
     * Forces the initializer to re-run on the next step even if the topology fingerprint is
     * unchanged. Called by the host when grid state changes; the per-tick fingerprint check
     * already covers this, the explicit call just removes the fingerprint cache hit.
     */
    public void markEnvironmentChanged() {
        environmentDirty = true;
    }

    // ---- Ticking -------------------------------------------------------------

    /**
     * Advances every suspended job by one step (resume, retry a pending action, or finish),
     * starts missing passive handlers, and retries pending recovery. Runs once per server tick.
     */
    public void step() {
        if (!ensureInitialized()) {
            return;
        }
        startMissingPassives();
        var now = host.tick();
        for (var job : List.copyOf(jobs)) {
            if (!jobs.contains(job)) {
                continue;
            }
            var action = job.pendingAction();
            if (action == null) {
                terminate(job, "Workflow has no pending action");
                continue;
            }
            if (action.type() == FactoryScriptAction.Type.SLEEP) {
                if (now - job.actionStartedTick() >= action.sleepTicks()) {
                    resume(job, FactoryActionResult.slept());
                }
                continue;
            }
            try {
                resume(job, host.performAction(job, action));
            } catch (RuntimeException exception) {
                AppliedFactory.LOGGER.error("Factory action failed", exception);
                terminate(job, exception.getMessage());
            }
        }
        processRecovery();
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
        var context = new ScriptExecutionContext(
                workflowId,
                host.tick(),
                orderSide,
                inputs,
                outputs,
                inputs,
                host.busesByNetwork(),
                EnumSet.allOf(Direction.class),
                host.onlineNetworks(),
                host.registries());
        var step = runtime.startProcessing(handler, context);
        if (step instanceof ScriptStep.Failed failed) {
            host.reportScriptFailure("processing start", failed.message());
            return false;
        }
        if (step instanceof ScriptStep.Suspended suspended) {
            jobs.add(FactoryJob.processing(
                    workflowId,
                    orderSide,
                    inputs,
                    outputs,
                    suspended.continuation(),
                    suspended.action(),
                    host.tick()));
        } else {
            // The handler returned without suspending: the cached inputs are immediately
            // returned to the order network (or held for recovery while it is offline).
            var completed = FactoryJob.processing(
                    workflowId,
                    orderSide,
                    inputs,
                    outputs,
                    ScriptContinuation.empty(),                    null,
                    host.tick());
            jobs.add(completed);
            terminate(completed, null);
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
        jobs.clear();
        stoppedPassives.clear();
        reportedRecoveryFailures.clear();
        initialized = false;
        attempted = false;
        environmentDirty = true;
        return entries;
    }

    /** Drops every job and recovery entry without returning resources (block destroyed). */
    public void discard() {
        jobs.clear();
        recovery.clear();
        stoppedPassives.clear();
        reportedRecoveryFailures.clear();
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
            FactoryJob.load(savedJobs.getCompound(index), registries).ifPresent(jobs::add);
        }
        var savedRecovery = tag.getList(RECOVERY_NBT_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < savedRecovery.size(); index++) {
            RecoveryEntry.load(savedRecovery.getCompound(index), registries)
                    .ifPresent(recovery::add);
        }
    }

    // ---- Internals -----------------------------------------------------------

    private int activeProcessingJobs() {
        return (int) jobs.stream()
                .filter(job -> job.kind() == FactoryJob.Kind.PROCESSING)
                .count();
    }

    private boolean ensureInitialized() {
        var fingerprint = topologyFingerprint();
        if (!environmentDirty && initialized && lastTopology == fingerprint) {
            return true;
        }
        if (attempted && !environmentDirty && lastAttemptedTopology == fingerprint) {
            return false;
        }
        attempted = true;
        lastAttemptedTopology = fingerprint;
        environmentDirty = false;
        var step = runtime.runInitializer(new ScriptExecutionContext(
                new UUID(0, 0),
                host.tick(),
                null,
                List.of(),
                List.of(),
                List.of(),
                host.busesByNetwork(),
                program.initializerNetworks(),
                host.onlineNetworks(),
                host.registries()));
        if (step instanceof ScriptStep.Completed) {
            initialized = true;
            lastTopology = fingerprint;
            return true;
        }
        initialized = false;
        host.reportScriptFailure("initializer", messageOf(step));
        return false;
    }

    private void startMissingPassives() {
        for (int index = 0; index < program.passiveHandlerCount(); index++) {
            if (stoppedPassives.contains(index)) {
                continue;
            }
            var passiveIndex = index;
            var exists = jobs.stream().anyMatch(job -> job.kind() == FactoryJob.Kind.PASSIVE
                    && job.passiveIndex() == passiveIndex);
            if (exists) {
                continue;
            }
            var workflowId = UUID.randomUUID();
            var step = runtime.startPassive(index, new ScriptExecutionContext(
                    workflowId,
                    host.tick(),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    host.busesByNetwork(),
                    EnumSet.allOf(Direction.class),
                    host.onlineNetworks(),
                    host.registries()));
            if (step instanceof ScriptStep.Suspended suspended) {
                jobs.add(FactoryJob.passive(
                        workflowId,
                        index,
                        suspended.continuation(),
                        suspended.action(),
                        host.tick()));
            } else {
                host.reportScriptFailure("passive " + index + " stopped", messageOf(step));
                stoppedPassives.add(index);
            }
            host.markChanged();
        }
    }

    private void resume(FactoryJob job, FactoryActionResult result) {
        var step = runtime.resume(
                new ScriptExecutionContext(
                        job.id(),
                        host.tick(),
                        job.orderSide(),
                        job.inputs(),
                        job.outputs(),
                        job.owned(),
                        host.busesByNetwork(),
                        EnumSet.allOf(Direction.class),
                        host.onlineNetworks(),
                        host.registries()),
                job.continuation(),
                result);
        if (step instanceof ScriptStep.Suspended suspended) {
            job.setSuspended(suspended.continuation(), suspended.action(), host.tick());
            host.markChanged();
        } else if (step instanceof ScriptStep.Completed) {
            terminate(job, null);
        } else {
            host.reportScriptFailure("workflow resume", messageOf(step));
            terminate(job, messageOf(step));
        }
    }

    private void terminate(FactoryJob job, @Nullable String message) {
        if (message != null) {
            AppliedFactory.LOGGER.error("Factory workflow {} failed: {}", job.id(), message);
        }
        if (job.kind() == FactoryJob.Kind.PASSIVE) {
            stoppedPassives.add(job.passiveIndex());
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

    private long topologyFingerprint() {
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
