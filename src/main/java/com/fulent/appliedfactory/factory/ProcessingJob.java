package com.fulent.appliedfactory.factory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.script.FactoryScriptAction;
import com.fulent.appliedfactory.script.ScriptContinuation;
import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptHandlerRef;

import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * A job created from an offered pattern (controller or script pattern). The only difference
 * from other jobs is the processing data carried here — order network, inputs and outputs —
 * which the script reads via {@code ctx.inputs} / {@code ctx.outputs} / {@code ctx.orderNetwork}.
 */
final class ProcessingJob extends FactoryJob {
    static final String ORDER_SIDE_TAG = "OrderSide";
    static final String INPUTS_TAG = "Inputs";
    static final String OUTPUTS_TAG = "Outputs";
    static final String CRAFTING_REQUEST_ID_TAG = "CraftingRequestId";

    private final ScriptHandlerRef handler;
    private final Direction orderSide;
    private final List<FactoryResource> inputs;
    private final List<FactoryResource> outputs;
    /**
     * The AE crafting request this job was pushed for (null when the push did not come from a
     * crafting CPU, e.g. in tests). Persisted so a request cancelled while the controller chunk
     * is unloaded still cancels the job once it is restored.
     */
    @Nullable
    private final UUID craftingRequestId;

    ProcessingJob(
            UUID id,
            FactoryProgram.Host host,
            Set<Direction> accessibleNetworks,
            ScriptHandlerRef handler,
            Direction orderSide,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            List<FactoryResource> owned,
            ScriptContinuation continuation,
            @Nullable FactoryScriptAction action,
            long actionStartedTick,
            @Nullable UUID craftingRequestId) {
        super(id, host, accessibleNetworks, owned, orderSide,
                continuation, action, actionStartedTick);
        this.handler = Objects.requireNonNull(handler, "handler");
        this.orderSide = Objects.requireNonNull(orderSide, "orderSide");
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.craftingRequestId = craftingRequestId;
    }

    @Override
    ScriptHandlerRef handlerRef() {
        return handler;
    }

    Direction orderSide() {
        return orderSide;
    }

    @Nullable
    UUID craftingRequestId() {
        return craftingRequestId;
    }

    /** The pattern outputs the ordering CPU awaits; once no CPU waits for them the job is orphaned. */
    List<AEKey> outputKeys() {
        return outputs.stream().map(FactoryResource::key).toList();
    }

    @Override
    ScriptExecutionContext createContext() {
        return context(orderSide, inputs, outputs);
    }

    @Override
    void saveParams(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(ORDER_SIDE_TAG, orderSide.getName());
        tag.put(INPUTS_TAG, saveResources(inputs, registries));
        tag.put(OUTPUTS_TAG, saveResources(outputs, registries));
        if (craftingRequestId != null) {
            tag.putUUID(CRAFTING_REQUEST_ID_TAG, craftingRequestId);
        }
    }

    static Optional<FactoryJob> loadParams(
            CompoundTag tag,
            FactoryProgram.Host host,
            HolderLookup.Provider registries,
            List<FactoryResource> owned,
            @Nullable Direction recoverySide,
            FactoryScriptAction action,
            byte[] continuation,
            long actionStartedTick) {
        if (!tag.contains(ORDER_SIDE_TAG, Tag.TAG_STRING)
                || !isCompoundList(tag, INPUTS_TAG)
                || !isCompoundList(tag, OUTPUTS_TAG)) {
            return Optional.empty();
        }
        var orderSide = Direction.byName(tag.getString(ORDER_SIDE_TAG));
        var inputs = loadResources(tag.getList(INPUTS_TAG, Tag.TAG_COMPOUND), registries);
        var outputs = loadResources(tag.getList(OUTPUTS_TAG, Tag.TAG_COMPOUND), registries);
        if (orderSide == null || inputs.isEmpty() || outputs.isEmpty()
                || inputs.get().isEmpty() || outputs.get().isEmpty()) {
            return Optional.empty();
        }
        var craftingRequestId = tag.hasUUID(CRAFTING_REQUEST_ID_TAG)
                ? tag.getUUID(CRAFTING_REQUEST_ID_TAG)
                : null;
        // The handler identity is only needed to start a job; a restored job is resumed
        // directly, so any ref satisfies the field (controller is the canonical default).
        return Optional.of(new ProcessingJob(
                tag.getUUID(ID_TAG),
                host,
                java.util.EnumSet.allOf(Direction.class),
                ScriptHandlerRef.controller(),
                orderSide,
                inputs.get(),
                outputs.get(),
                owned,
                ScriptContinuation.ofPersisted(continuation),
                action,
                actionStartedTick,
                craftingRequestId));
    }
}
