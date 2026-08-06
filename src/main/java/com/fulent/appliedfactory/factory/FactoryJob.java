package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.script.ControllerProgram;
import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ScriptContinuation;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Persistent continuation and cache ownership for one script workflow. */
public final class FactoryJob {
    private static final String ID_TAG = "Id";
    private static final String KIND_TAG = "Kind";
    private static final String ORDER_SIDE_TAG = "OrderSide";
    private static final String RECOVERY_SIDE_TAG = "RecoverySide";
    private static final String PASSIVE_INDEX_TAG = "PassiveIndex";
    private static final String PROGRAM_SOURCE_TAG = "ProgramSource";
    private static final String INPUTS_TAG = "Inputs";
    private static final String OUTPUTS_TAG = "Outputs";
    private static final String OWNED_TAG = "Owned";
    private static final String CONTINUATION_TAG = "Continuation";
    private static final String ACTION_TAG = "Action";
    private static final String ACTION_STARTED_TAG = "ActionStarted";
    private static final String FINISHED_TAG = "Finished";

    private final UUID id;
    private final Kind kind;
    @Nullable
    private final Direction orderSide;
    @Nullable
    private Direction recoverySide;
    private final int passiveIndex;
    private final String programSource;
    private final List<FactoryResource> inputs;
    private final List<FactoryResource> outputs;
    private List<FactoryResource> owned;
    private ScriptContinuation continuation;
    @Nullable
    private FactoryScriptAction pendingAction;
    private long actionStartedTick;
    private boolean finished;

    public static FactoryJob processing(
            UUID id,
            Direction orderSide,
            String programSource,
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
                programSource,
                inputs,
                outputs,
                inputs,
                continuation,
                action,
                actionStartedTick,
                false);
    }

    public static FactoryJob completedProcessing(
            UUID id,
            Direction orderSide,
            String programSource,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs) {
        return new FactoryJob(
                id,
                Kind.PROCESSING,
                orderSide,
                orderSide,
                -1,
                programSource,
                inputs,
                outputs,
                inputs,
                ScriptContinuation.empty(),
                null,
                0,
                true);
    }

    public static FactoryJob passive(
            UUID id,
            String programSource,
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
                programSource,
                List.of(),
                List.of(),
                List.of(),
                continuation,
                action,
                actionStartedTick,
                false);
    }

    public static FactoryJob stoppedPassive(String programSource, int passiveIndex) {
        return new FactoryJob(
                UUID.randomUUID(),
                Kind.PASSIVE,
                null,
                null,
                passiveIndex,
                programSource,
                List.of(),
                List.of(),
                List.of(),
                ScriptContinuation.empty(),
                null,
                0,
                true);
    }

    private FactoryJob(
            UUID id,
            Kind kind,
            @Nullable Direction orderSide,
            @Nullable Direction recoverySide,
            int passiveIndex,
            String programSource,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            List<FactoryResource> owned,
            ScriptContinuation continuation,
            @Nullable FactoryScriptAction pendingAction,
            long actionStartedTick,
            boolean finished) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.orderSide = orderSide;
        this.recoverySide = recoverySide;
        this.passiveIndex = passiveIndex;
        this.programSource = Objects.requireNonNull(programSource, "programSource");
        this.inputs = normalize(inputs);
        this.outputs = normalize(outputs);
        this.owned = normalize(owned);
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        this.pendingAction = pendingAction;
        this.actionStartedTick = actionStartedTick;
        this.finished = finished;
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

    public String programSource() {
        return programSource;
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

    public boolean finished() {
        return finished;
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
        finished = false;
    }

    public void markFinished() {
        continuation = ScriptContinuation.empty();
        pendingAction = null;
        finished = true;
    }

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
        tag.putString(PROGRAM_SOURCE_TAG, programSource);
        tag.put(INPUTS_TAG, saveResources(inputs, registries));
        tag.put(OUTPUTS_TAG, saveResources(outputs, registries));
        tag.put(OWNED_TAG, saveResources(owned, registries));

        // Serialize the live continuation only now, at persist time (chunk save/unload).
        // RhinoContinuation caches its bytes, so repeated saves without an intervening
        // resume are free. On failure, degrade the job to a recoverable finished state
        // rather than corrupting the chunk save: load() returns its owned resources.
        byte[] serialized;
        boolean persistFinished = finished;
        FactoryScriptAction persistAction = pendingAction;
        try {
            serialized = finished ? new byte[0] : continuation.serialize();
        } catch (RuntimeException exception) {
            AppliedFactory.LOGGER.error(
                    "Factory workflow {} continuation could not be serialized; "
                            + "persisting it for resource recovery only",
                    id, exception);
            serialized = new byte[0];
            persistFinished = true;
            persistAction = null;
        }
        tag.putByteArray(CONTINUATION_TAG, serialized);
        if (persistAction != null) {
            tag.put(ACTION_TAG, persistAction.save(registries));
        }
        tag.putLong(ACTION_STARTED_TAG, actionStartedTick);
        tag.putBoolean(FINISHED_TAG, persistFinished);
        return tag;
    }

    public static Optional<FactoryJob> load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(ID_TAG)
                || !tag.contains(KIND_TAG, Tag.TAG_STRING)
                || !tag.contains(PASSIVE_INDEX_TAG, Tag.TAG_INT)
                || !tag.contains(PROGRAM_SOURCE_TAG, Tag.TAG_STRING)
                || !isCompoundList(tag, INPUTS_TAG)
                || !isCompoundList(tag, OUTPUTS_TAG)
                || !isCompoundList(tag, OWNED_TAG)
                || !tag.contains(CONTINUATION_TAG, Tag.TAG_BYTE_ARRAY)
                || !tag.contains(ACTION_STARTED_TAG, Tag.TAG_LONG)
                || !tag.contains(FINISHED_TAG, Tag.TAG_BYTE)) {
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
        var programSource = tag.getString(PROGRAM_SOURCE_TAG);
        if (programSource.isBlank() || programSource.length() > ControllerProgram.MAX_SOURCE_LENGTH
                || kind == Kind.PROCESSING && orderSide == null
                || kind == Kind.PROCESSING && passiveIndex != -1
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

        var finished = tag.getBoolean(FINISHED_TAG);
        FactoryScriptAction action = null;
        if (tag.contains(ACTION_TAG, Tag.TAG_COMPOUND)) {
            action = FactoryScriptAction.load(tag.getCompound(ACTION_TAG), registries).orElse(null);
            if (action == null) {
                return Optional.empty();
            }
        } else if (tag.contains(ACTION_TAG)) {
            return Optional.empty();
        }

        var continuation = tag.getByteArray(CONTINUATION_TAG);
        if (finished && (action != null || continuation.length != 0)
                || !finished && (action == null || continuation.length == 0)) {
            return Optional.empty();
        }

        return Optional.of(new FactoryJob(
                tag.getUUID(ID_TAG),
                kind,
                orderSide,
                recoverySide,
                passiveIndex,
                programSource,
                inputs.get(),
                outputs.get(),
                owned.get(),
                ScriptContinuation.ofPersisted(continuation),
                action,
                tag.getLong(ACTION_STARTED_TAG),
                finished));
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
