package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.script.FactoryActionResult;
import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ScriptContinuation;
import com.fulent.appliedfactory.script.ScriptExecutionContext;
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
 * A suspended script workflow owned by one {@link FactoryProgram}. Subclasses carry only the
 * parameters that differ at creation time (processing inputs/outputs, passive index, ...); the
 * whole lifecycle — starting, resuming, suspending, resource ownership and persistence — lives
 * here and is identical for every kind. A job exists only while suspended: finishing hands any
 * leftover owned resources to the program's recovery queue.
 */
public abstract class FactoryJob {
    static final String ID_TAG = "Id";
    static final String OWNED_TAG = "Owned";
    static final String RECOVERY_SIDE_TAG = "RecoverySide";
    static final String CONTINUATION_TAG = "Continuation";
    static final String ACTION_TAG = "Action";
    static final String ACTION_STARTED_TAG = "ActionStarted";

    private final UUID id;
    private final FactoryProgram.Host host;
    private final Set<Direction> accessibleNetworks;
    private List<FactoryResource> owned;
    /**
     * The last network side the job interacted with; used as the preferred recovery target
     * when the job ends with owned resources still cached. Set by the action executor.
     */
    @Nullable
    private Direction recoverySide;
    private ScriptContinuation continuation;
    @Nullable
    private FactoryScriptAction pendingAction;
    private long actionStartedTick;

    protected FactoryJob(
            UUID id,
            FactoryProgram.Host host,
            Set<Direction> accessibleNetworks,
            List<FactoryResource> owned,
            @Nullable Direction recoverySide,
            ScriptContinuation continuation,
            @Nullable FactoryScriptAction pendingAction,
            long actionStartedTick) {
        this.id = Objects.requireNonNull(id, "id");
        this.host = Objects.requireNonNull(host, "host");
        this.accessibleNetworks = Set.copyOf(accessibleNetworks);
        this.owned = normalize(owned);
        this.recoverySide = recoverySide;
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        this.pendingAction = pendingAction;
        this.actionStartedTick = actionStartedTick;
    }

    // ---- Subclass hooks ----------------------------------------------------

    /** Which registered handler this job invokes when it starts. */
    abstract ScriptHandlerRef handlerRef();

    /** Builds the per-invocation execution context from this job's parameters. */
    abstract ScriptExecutionContext createContext();

    /** Writes this subclass's parameters into the save tag. */
    abstract void saveParams(CompoundTag tag, HolderLookup.Provider registries);

    // ---- Lifecycle (identical for every kind) ------------------------------

    /** Starts the handler; the returned step carries the continuation if it suspended. */
    ScriptStep start(ScriptRuntime runtime) {
        return runtime.startHandler(handlerRef(), createContext());
    }

    /** Resumes the suspended continuation with the outcome of its last pending action. */
    ScriptStep resume(ScriptRuntime runtime, FactoryActionResult result) {
        return runtime.resume(createContext(), continuation, result);
    }

    // ---- State accessors ---------------------------------------------------

    public UUID id() {
        return id;
    }

    public HolderLookup.Provider registries() {
        return host.registries();
    }

    public List<FactoryResource> owned() {
        return owned;
    }

    @Nullable
    public Direction recoverySide() {
        return recoverySide;
    }

    public void setRecoverySideIfAbsent(Direction side) {
        if (recoverySide == null) {
            recoverySide = Objects.requireNonNull(side, "side");
        }
    }

    public ScriptContinuation continuation() {
        return continuation;
    }

    @Nullable
    public FactoryScriptAction pendingAction() {
        return pendingAction;
    }

    public long actionStartedTick() {
        return actionStartedTick;
    }

    public boolean canConsumeOwned(List<FactoryResource> requested) {
        return canSubtract(owned, requested);
    }

    public void consumeOwned(List<FactoryResource> consumed) {
        owned = subtract(owned, consumed);
    }

    public void addOwned(List<FactoryResource> added) {
        var combined = new ArrayList<>(owned);
        combined.addAll(added);
        owned = normalize(combined);
    }

    public void setSuspended(
            ScriptContinuation continuation,
            FactoryScriptAction action,
            long startedTick) {
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        pendingAction = Objects.requireNonNull(action, "action");
        actionStartedTick = startedTick;
    }

    /**
     * Replaces the pending action without touching the continuation. Used by the
     * scheduler when a blocking till-full push transferred part of its input: the
     * script stays blocked at the call site and only the remaining resources are
     * retried.
     */
    public void setPendingAction(FactoryScriptAction action) {
        pendingAction = Objects.requireNonNull(action, "action");
    }

    // ---- Persistence -------------------------------------------------------

    /**
     * Serializes this suspended job. Continuation serialization may throw (the program then
     * degrades this job to a recovery entry so its owned resources survive the chunk save).
     */
    CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putUUID(ID_TAG, id);
        tag.put(OWNED_TAG, saveResources(owned, registries));
        if (recoverySide != null) {
            tag.putString(RECOVERY_SIDE_TAG, recoverySide.getName());
        }
        tag.putByteArray(CONTINUATION_TAG, continuation.serialize());
        tag.put(ACTION_TAG, Objects.requireNonNull(pendingAction, "pendingAction").save(registries));
        tag.putLong(ACTION_STARTED_TAG, actionStartedTick);
        saveParams(tag, registries);
        return tag;
    }

    /** Restores a persisted job; the subclass is chosen by its persisted parameters. */
    static Optional<FactoryJob> load(
            CompoundTag tag, FactoryProgram.Host host, HolderLookup.Provider registries) {
        if (!tag.hasUUID(ID_TAG)
                || !isCompoundList(tag, OWNED_TAG)
                || !tag.contains(CONTINUATION_TAG, Tag.TAG_BYTE_ARRAY)
                || !tag.contains(ACTION_STARTED_TAG, Tag.TAG_LONG)) {
            return Optional.empty();
        }
        var owned = loadResources(tag.getList(OWNED_TAG, Tag.TAG_COMPOUND), registries);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        var recoverySide = tag.contains(RECOVERY_SIDE_TAG, Tag.TAG_STRING)
                ? Direction.byName(tag.getString(RECOVERY_SIDE_TAG))
                : null;
        var action = tag.contains(ACTION_TAG, Tag.TAG_COMPOUND)
                ? FactoryScriptAction.load(tag.getCompound(ACTION_TAG), registries).orElse(null)
                : null;
        var continuation = tag.getByteArray(CONTINUATION_TAG);
        if (action == null || continuation.length == 0) {
            return Optional.empty();
        }
        var actionStartedTick = tag.getLong(ACTION_STARTED_TAG);
        if (tag.contains(ProcessingJob.ORDER_SIDE_TAG, Tag.TAG_STRING)) {
            return ProcessingJob.loadParams(tag, host, registries, owned.get(), recoverySide,
                    action, continuation, actionStartedTick);
        }
        if (tag.contains(PassiveJob.PASSIVE_INDEX_TAG, Tag.TAG_INT)) {
            return PassiveJob.loadParams(tag, host, registries, owned.get(), recoverySide,
                    action, continuation, actionStartedTick);
        }
        return Optional.empty();
    }

    // ---- Shared helpers ----------------------------------------------------

    /** Builds a context from common state plus subclass-supplied processing data. */
    protected ScriptExecutionContext context(
            @Nullable Direction orderSide,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs) {
        return new ScriptExecutionContext(
                id,
                host.tick(),
                orderSide,
                inputs,
                outputs,
                owned,
                host.busesByNetwork(),
                accessibleNetworks,
                host.onlineNetworks(),
                host.registries(),
                host::networkStorage);
    }

    static List<FactoryResource> normalize(List<FactoryResource> resources) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var resource : resources) {
            Objects.requireNonNull(resource, "resource");
            amounts.merge(resource.key(), resource.amount(), Math::addExact);
        }
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    static ListTag saveResources(
            List<FactoryResource> resources, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var resource : resources) {
            list.add(resource.save(registries));
        }
        return list;
    }

    static Optional<List<FactoryResource>> loadResources(
            ListTag list, HolderLookup.Provider registries) {
        var result = new ArrayList<FactoryResource>(list.size());
        for (int index = 0; index < list.size(); index++) {
            var resource = FactoryResource.load(list.getCompound(index), registries);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            result.add(resource.get());
        }
        try {
            return Optional.of(normalize(result));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    static boolean isCompoundList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            return false;
        }
        var list = (ListTag) tag.get(key);
        return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
    }

    private static boolean canSubtract(
            List<FactoryResource> current, List<FactoryResource> removed) {
        var amounts = amounts(current);
        for (var resource : removed) {
            var available = amounts.getOrDefault(resource.key(), 0L);
            if (available < resource.amount()) {
                return false;
            }
            amounts.put(resource.key(), available - resource.amount());
        }
        return true;
    }

    private static List<FactoryResource> subtract(
            List<FactoryResource> current, List<FactoryResource> removed) {
        var amounts = amounts(current);
        for (var resource : removed) {
            var available = amounts.getOrDefault(resource.key(), 0L);
            if (available < resource.amount()) {
                throw new IllegalArgumentException(
                        "Factory workflow does not own the requested resource");
            }
            amounts.put(resource.key(), available - resource.amount());
        }
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Map<AEKey, Long> amounts(List<FactoryResource> resources) {
        var result = new LinkedHashMap<AEKey, Long>();
        for (var resource : resources) {
            result.merge(resource.key(), resource.amount(), Math::addExact);
        }
        return result;
    }
}
