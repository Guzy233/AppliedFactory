package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ScriptContinuation;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * A suspended script workflow owned by one {@link FactoryProgram}. A job has exactly one
 * active state — suspended. Script advancement is synchronous on the server thread, so a
 * job is either suspended (waiting for a pending action to be retried or resumed) or gone:
 * finishing a job hands any leftover owned resources to the program's recovery queue.
 */
public final class FactoryJob {
    private static final String ID_TAG = "Id";
    private static final String KIND_TAG = "Kind";
    private static final String ORDER_SIDE_TAG = "OrderSide";
    private static final String RECOVERY_SIDE_TAG = "RecoverySide";
    private static final String PASSIVE_INDEX_TAG = "PassiveIndex";
    private static final String INPUTS_TAG = "Inputs";
    private static final String OUTPUTS_TAG = "Outputs";
    private static final String OWNED_TAG = "Owned";
    private static final String CONTINUATION_TAG = "Continuation";
    private static final String ACTION_TAG = "Action";
    private static final String ACTION_STARTED_TAG = "ActionStarted";

    private final UUID id;
    private final Kind kind;
    @Nullable
    private final Direction orderSide;
    /**
     * The last network side the job interacted with; used as the preferred recovery target
     * when the job ends with owned resources still cached. Set by the action executor.
     */
    @Nullable
    private Direction recoverySide;
    private final int passiveIndex;
    private final List<FactoryResource> inputs;
    private final List<FactoryResource> outputs;
    private List<FactoryResource> owned;
    private ScriptContinuation continuation;
    @Nullable
    private FactoryScriptAction pendingAction;
    private long actionStartedTick;

    public static FactoryJob processing(
            UUID id,
            Direction orderSide,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            ScriptContinuation continuation,
            FactoryScriptAction action,
            long actionStartedTick) {
        return new FactoryJob(
                id,
                Kind.PROCESSING,
                orderSide,
                orderSide,
                -1,
                inputs,
                outputs,
                inputs,
                continuation,
                action,
                actionStartedTick);
    }

    public static FactoryJob passive(
            UUID id,
            int passiveIndex,
            ScriptContinuation continuation,
            FactoryScriptAction action,
            long actionStartedTick) {
        return new FactoryJob(
                id,
                Kind.PASSIVE,
                null,
                null,
                passiveIndex,
                List.of(),
                List.of(),
                List.of(),
                continuation,
                action,
                actionStartedTick);
    }

    private FactoryJob(
            UUID id,
            Kind kind,
            @Nullable Direction orderSide,
            @Nullable Direction recoverySide,
            int passiveIndex,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            List<FactoryResource> owned,
            ScriptContinuation continuation,
            @Nullable FactoryScriptAction pendingAction,
            long actionStartedTick) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.orderSide = orderSide;
        this.recoverySide = recoverySide;
        this.passiveIndex = passiveIndex;
        this.inputs = normalize(inputs);
        this.outputs = normalize(outputs);
        this.owned = normalize(owned);
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        this.pendingAction = pendingAction;
        this.actionStartedTick = actionStartedTick;
    }

    public UUID id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    @Nullable
    public Direction orderSide() {
        return orderSide;
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

    public int passiveIndex() {
        return passiveIndex;
    }

    public List<FactoryResource> inputs() {
        return inputs;
    }

    public List<FactoryResource> outputs() {
        return outputs;
    }

    public List<FactoryResource> owned() {
        return owned;
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
     * Serializes this suspended job. Continuation serialization may throw (the program then
     * degrades this job to a recovery entry so its owned resources survive the chunk save).
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putUUID(ID_TAG, id);
        tag.putString(KIND_TAG, kind.name());
        if (orderSide != null) {
            tag.putString(ORDER_SIDE_TAG, orderSide.getName());
        }
        if (recoverySide != null) {
            tag.putString(RECOVERY_SIDE_TAG, recoverySide.getName());
        }
        tag.putInt(PASSIVE_INDEX_TAG, passiveIndex);
        tag.put(INPUTS_TAG, saveResources(inputs, registries));
        tag.put(OUTPUTS_TAG, saveResources(outputs, registries));
        tag.put(OWNED_TAG, saveResources(owned, registries));
        tag.putByteArray(CONTINUATION_TAG, continuation.serialize());
        tag.put(ACTION_TAG, Objects.requireNonNull(pendingAction, "pendingAction").save(registries));
        tag.putLong(ACTION_STARTED_TAG, actionStartedTick);
        return tag;
    }

    public static Optional<FactoryJob> load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(ID_TAG)
                || !tag.contains(KIND_TAG, Tag.TAG_STRING)
                || !tag.contains(PASSIVE_INDEX_TAG, Tag.TAG_INT)
                || !isCompoundList(tag, INPUTS_TAG)
                || !isCompoundList(tag, OUTPUTS_TAG)
                || !isCompoundList(tag, OWNED_TAG)
                || !tag.contains(CONTINUATION_TAG, Tag.TAG_BYTE_ARRAY)
                || !tag.contains(ACTION_STARTED_TAG, Tag.TAG_LONG)) {
            return Optional.empty();
        }

        final Kind kind;
        try {
            kind = Kind.valueOf(tag.getString(KIND_TAG));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        var orderSide = tag.contains(ORDER_SIDE_TAG, Tag.TAG_STRING)
                ? Direction.byName(tag.getString(ORDER_SIDE_TAG))
                : null;
        var recoverySide = tag.contains(RECOVERY_SIDE_TAG, Tag.TAG_STRING)
                ? Direction.byName(tag.getString(RECOVERY_SIDE_TAG))
                : null;
        var passiveIndex = tag.getInt(PASSIVE_INDEX_TAG);
        if (kind == Kind.PROCESSING && (orderSide == null || passiveIndex != -1)
                || kind == Kind.PASSIVE && passiveIndex < 0) {
            return Optional.empty();
        }

        var inputs = loadResources(tag.getList(INPUTS_TAG, Tag.TAG_COMPOUND), registries);
        var outputs = loadResources(tag.getList(OUTPUTS_TAG, Tag.TAG_COMPOUND), registries);
        var owned = loadResources(tag.getList(OWNED_TAG, Tag.TAG_COMPOUND), registries);
        if (inputs.isEmpty() || outputs.isEmpty() || owned.isEmpty()
                || kind == Kind.PROCESSING && (inputs.get().isEmpty() || outputs.get().isEmpty())) {
            return Optional.empty();
        }

        var action = tag.contains(ACTION_TAG, Tag.TAG_COMPOUND)
                ? FactoryScriptAction.load(tag.getCompound(ACTION_TAG), registries).orElse(null)
                : null;
        var continuation = tag.getByteArray(CONTINUATION_TAG);
        if (action == null || continuation.length == 0) {
            return Optional.empty();
        }

        return Optional.of(new FactoryJob(
                tag.getUUID(ID_TAG),
                kind,
                orderSide,
                recoverySide,
                passiveIndex,
                inputs.get(),
                outputs.get(),
                owned.get(),
                ScriptContinuation.ofPersisted(continuation),
                action,
                tag.getLong(ACTION_STARTED_TAG)));
    }

    private static List<FactoryResource> normalize(List<FactoryResource> resources) {
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

    private static ListTag saveResources(
            List<FactoryResource> resources, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var resource : resources) {
            list.add(resource.save(registries));
        }
        return list;
    }

    private static Optional<List<FactoryResource>> loadResources(
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

    private static boolean isCompoundList(CompoundTag tag, String key) {
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

    public enum Kind {
        PROCESSING,
        PASSIVE
    }
}
