package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.script.CompiledControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgram;
import com.fulent.appliedfactory.script.ControllerProgramCompiler;
import com.fulent.appliedfactory.script.ProgramLoadResult;
import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptHandlerRef;
import com.fulent.appliedfactory.script.ScriptRuntime;
import com.fulent.appliedfactory.script.ScriptStep;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;

/** Owns one compiled GraalJS context and its non-persistent generator jobs. */
public final class FactoryProgram {
    public static final int MAX_JOBS = 64;
    private static final int MAX_IMMEDIATE_TRANSITIONS_PER_TICK = 128;

    public interface Host {
        long tick();

        HolderLookup.Provider registries();

        Map<Direction, List<FactoryBusAddress>> busAddressesByNetwork();

        Set<Direction> onlineNetworks();

        /** Horizontal visual front used to resolve front/back/left/right script selectors. */
        Direction controllerFacing();

        /** True when both physical controller sides currently belong to the same AE grid. */
        boolean isSameNetwork(Direction first, Direction second);

        Optional<FactoryBusTarget> busTarget(FactoryBusAddress address);

        List<FactoryResource> availableResources(FactoryEndpoint endpoint);

        default List<FactoryResource> availableResources(
                FactoryEndpoint endpoint, AEKeyType channel) {
            return availableResources(endpoint).stream()
                    .filter(resource -> resource.key().getType().equals(channel))
                    .toList();
        }

        /**
         * Full current contents of an external endpoint, including slots that
         * reject extraction from the accessed face (e.g. a machine input).
         * Network endpoints equal {@link #availableResources(FactoryEndpoint)}.
         */
        List<FactoryResource> storageContents(FactoryEndpoint endpoint);

        default List<FactoryResource> storageContents(
                FactoryEndpoint endpoint, AEKeyType channel) {
            return storageContents(endpoint).stream()
                    .filter(resource -> resource.key().getType().equals(channel))
                    .toList();
        }

        /**
         * The key-type ids ({"@literal "ae2:i"}, {"@literal "ae2:f"}, ...) a bus target
         * currently supports for input/output, independent of its contents: the set of
         * channels the target face exposes a capability for.
         */
        List<String> channels(FactoryBusAddress bus);

        long availableAmount(FactoryResourceOrigin origin, AEKey key);

        FactoryTransferResult performTransfer(UUID workflowId, FactoryTransferAction action);

        Optional<FactoryResourceRef> renameItem(
                UUID workflowId, FactoryResourceRef item, String name);

        boolean dropItem(
                UUID workflowId, FactoryBusAddress bus, FactoryResourceRef item);

        boolean use(UUID workflowId, FactoryBusAddress bus, boolean shift);

        boolean use(
                UUID workflowId, FactoryBusAddress bus, FactoryResourceRef item, boolean shift);

        boolean place(
                UUID workflowId, FactoryBusAddress bus, FactoryResourceRef block, boolean shift);

        Optional<FactoryResourceRef> breakBlock(
                UUID workflowId, FactoryBusAddress bus, FactoryResourceRef tool);

        /**
         * Redstone level the target block emits toward this bus's face, 0-15.
         * Returns 0 when the bus or its target cannot be resolved or is not loaded.
         */
        int busRedstoneLevel(FactoryBusAddress bus);

        /**
         * Sets the redstone strength this bus emits outward from its physical
         * cable face, 0-15. Returns false when the bus cannot be resolved.
         */
        boolean setBusRedstoneOutput(FactoryBusAddress bus, int level);

        boolean createEscrow(
                UUID workflowId,
                Direction recoverySide,
                List<FactoryResource> resources);

        Set<UUID> escrowIds();

        boolean recoverEscrow(UUID workflowId);

        void reportScriptFailure(String stage, String message);

        /** Forwards a script-printed message to this controller's log subscribers. */
        void log(String message);

        void markChanged();
    }

    private final ScriptRuntime runtime;
    private final CompiledControllerProgram program;
    private final Host host;
    private final List<FactoryJob> jobs = new ArrayList<>();
    private final Set<Integer> stoppedPassives = new HashSet<>();
    private final Set<UUID> pendingCancellations = new HashSet<>();
    private boolean topologyDirty;

    private FactoryProgram(
            ScriptRuntime runtime,
            CompiledControllerProgram program,
            Host host) {
        this.runtime = runtime;
        this.program = program;
        this.host = host;
    }

    public static ProgramLoadResult<FactoryProgram> load(String source, Host host) {
        return load(source, host, null);
    }

    /**
     * Loads a program like {@link #load(String, Host)} but optionally binds a
     * {@link ScriptExecutionContext} for the duration of the top-level evaluation.
     * MCP probe programs use this so {@code .now()} calls in top-level expressions work,
     * matching generator workflows.
     */
    public static ProgramLoadResult<FactoryProgram> load(
            String source,
            Host host,
            @Nullable ScriptExecutionContext topLevelContext) {
        if (!ControllerProgram.isWithinLimit(source)) {
            return ProgramLoadResult.failure(
                    "Factory program exceeds the " + ControllerProgram.MAX_SOURCE_LENGTH
                            + " character source limit");
        }
        var runtime = ControllerProgramCompiler.createRuntime(host);
        var result = runtime.loadProgram(source, topLevelContext);
        if (!result.successful()) {
            return ProgramLoadResult.failure(result.errorMessage());
        }
        return ProgramLoadResult.success(new FactoryProgram(runtime, result.program(), host));
    }

    public static ProgramLoadResult<FactoryProgram> replace(
            FactoryProgram current,
            String newSource,
            Host host) {
        var replacement = load(newSource, host);
        if (!replacement.successful()) {
            return replacement;
        }
        if (current != null) {
            current.discard();
        }
        return replacement;
    }

    public CompiledControllerProgram compiled() {
        return program;
    }

    /** Number of go(function*(){...}) registrations in the evaluated source. */
    public int passiveHandlerCount() {
        return program.passiveHandlerCount();
    }

    public boolean canAcceptJobs() {
        return activeProcessingJobs() < MAX_JOBS && host.escrowIds().size() < MAX_JOBS;
    }

    /** Number of currently live generator jobs (processing, passive or probe). */
    public int activeJobCount() {
        return jobs.size();
    }

    /**
     * The value of the last top-level expression of the evaluated source, serialized to
     * JSON by the runtime (null when blank, unserializable or not captured).
     */
    @Nullable
    public String lastValueJson() {
        return runtime.lastValueJson();
    }

    /** Live jobs, package-private so the MCP probe driver can describe pending waits. */
    List<FactoryJob> activeJobs() {
        return jobs;
    }

    public boolean startJob(
            ScriptHandlerRef handler,
            Direction orderSide,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs) {
        if (!canAcceptJobs()) {
            return false;
        }
        var id = UUID.randomUUID();
        var context = new com.fulent.appliedfactory.script.ScriptExecutionContext(
                id, orderSide, inputs, outputs);
        // Calling a generator function only creates its generator object; its body and any
        // .now() calls begin on the first later scheduler step, after AE accepted the push.
        var workflow = runtime.createWorkflow(handler, context);
        if (!workflow.successful()) {
            host.reportScriptFailure("processing start", workflow.errorMessage());
            return false;
        }
        if (!host.createEscrow(id, orderSide, inputs)) {
            return false;
        }
        jobs.add(new ProcessingJob(
                id,
                workflow.program(),
                orderSide,
                inputs,
                outputs,
                CraftingRequestContext.current()));
        host.markChanged();
        return true;
    }

    public void step() {
        processCancellations();
        if (topologyDirty) {
            topologyDirty = false;
            try {
                runtime.runTopologyListeners();
            } catch (RuntimeException exception) {
                host.reportScriptFailure("network onChange", messageOf(exception));
            }
        }
        startMissingPassives();
        for (var job : List.copyOf(jobs)) {
            if (jobs.contains(job)) {
                advance(job);
            }
        }
        recoverOrphanEscrows();
    }

    public void markEnvironmentChanged() {
        topologyDirty = true;
    }

    public void cancelJobs(UUID craftingRequestId) {
        pendingCancellations.add(craftingRequestId);
    }

    public void discard() {
        jobs.clear();
        stoppedPassives.clear();
        pendingCancellations.clear();
        runtime.close();
    }

    private void processCancellations() {
        if (pendingCancellations.isEmpty()) {
            return;
        }
        jobs.removeIf(job -> job instanceof ProcessingJob processing
                && processing.craftingRequestId() != null
                && pendingCancellations.contains(processing.craftingRequestId()));
        pendingCancellations.clear();
        host.markChanged();
    }

    private void startMissingPassives() {
        for (int index = 0; index < program.passiveHandlerCount(); index++) {
            if (stoppedPassives.contains(index)) {
                continue;
            }
            var passiveIndex = index;
            if (jobs.stream().anyMatch(job -> job instanceof PassiveJob passive
                    && passive.passiveIndex() == passiveIndex)) {
                continue;
            }
            var id = UUID.randomUUID();
            var context = new com.fulent.appliedfactory.script.ScriptExecutionContext(
                    id, null, List.of(), List.of());
            var workflow = runtime.createWorkflow(ScriptHandlerRef.passive(index), context);
            if (!workflow.successful()) {
                stoppedPassives.add(index);
                host.reportScriptFailure("passive " + index, workflow.errorMessage());
                continue;
            }
            jobs.add(new PassiveJob(id, workflow.program(), index));
        }
    }

    private void advance(FactoryJob job) {
        // Advance an immediately satisfiable chain in one scheduler tick. A cap
        // prevents a generator that yields endlessly successful Actions from
        // monopolizing the server thread; its current Action remains pending for
        // the next tick when the cap is reached.
        for (int transitions = 0; transitions < MAX_IMMEDIATE_TRANSITIONS_PER_TICK; transitions++) {
            var pending = job.pendingAction();
            if (pending == null) {
                if (!resumeGenerator(job, null)) {
                    return;
                }
                continue;
            }
            if (pending instanceof FactorySleepAction sleep) {
                if (host.tick() - job.actionStartedTick() < sleep.ticks()) {
                    return;
                }
                job.clearWaiting();
                if (!resumeGenerator(job, null)) {
                    return;
                }
                continue;
            }
            if (pending instanceof FactoryTransferAction transfer) {
                try {
                    var result = host.performTransfer(job.id(), transfer);
                    if (!result.completed()) {
                        job.setWaiting(transfer, job.actionStartedTick());
                        return;
                    }
                    job.clearWaiting();
                    if (!resumeGenerator(job, true)) {
                        return;
                    }
                } catch (RuntimeException exception) {
                    finish(job, messageOf(exception));
                    return;
                }
                continue;
            }
            return;
        }
    }

    /** @return whether the generator yielded an Action that should be advanced now. */
    private boolean resumeGenerator(FactoryJob job, Object result) {
        var step = runtime.advance(
                job.workflow(), job.context(), result, job.firstStep());
        if (step instanceof ScriptStep.Waiting waiting) {
            job.setWaiting(waiting.action(), host.tick());
            return true;
        }
        if (step instanceof ScriptStep.Failed failed) {
            finish(job, failed.message());
            return false;
        }
        finish(job, null);
        return false;
    }

    private void finish(FactoryJob job, String failure) {
        jobs.remove(job);
        if (job instanceof PassiveJob passive) {
            stoppedPassives.add(passive.passiveIndex());
        }
        if (failure != null) {
            AppliedFactory.LOGGER.error("Factory workflow {} failed: {}", job.id(), failure);
            host.reportScriptFailure("workflow", failure);
        }
        host.markChanged();
    }

    private void recoverOrphanEscrows() {
        var active = jobs.stream().map(FactoryJob::id).collect(java.util.stream.Collectors.toSet());
        for (var escrowId : host.escrowIds()) {
            if (!active.contains(escrowId)) {
                try {
                    host.recoverEscrow(escrowId);
                } catch (RuntimeException exception) {
                    AppliedFactory.LOGGER.error(
                            "Factory escrow {} recovery failed", escrowId, exception);
                }
            }
        }
    }

    private int activeProcessingJobs() {
        return (int) jobs.stream().filter(ProcessingJob.class::isInstance).count();
    }

    private static String messageOf(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
