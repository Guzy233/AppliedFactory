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

    private final ScriptHandlerRef handler;
    private final Direction orderSide;
    private final List<FactoryResource> inputs;
    private final List<FactoryResource> outputs;

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
            long actionStartedTick) {
        super(id, host, accessibleNetworks, owned, orderSide,
                continuation, action, actionStartedTick);
        this.handler = Objects.requireNonNull(handler, "handler");
        this.orderSide = Objects.requireNonNull(orderSide, "orderSide");
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
    }

    @Override
    ScriptHandlerRef handlerRef() {
        return handler;
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
                actionStartedTick));
    }
}
