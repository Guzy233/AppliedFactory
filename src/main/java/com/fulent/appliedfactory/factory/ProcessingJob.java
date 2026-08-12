package com.fulent.appliedfactory.factory;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptWorkflow;

import net.minecraft.core.Direction;

/** In-memory generator created for one accepted AE processing-pattern push. */
final class ProcessingJob extends FactoryJob {
    private final Direction orderSide;
    private final List<FactoryResource> inputs;
    private final List<FactoryResource> outputs;
    @Nullable
    private final UUID craftingRequestId;

    ProcessingJob(
            UUID id,
            ScriptWorkflow workflow,
            Direction orderSide,
            List<FactoryResource> inputs,
            List<FactoryResource> outputs,
            @Nullable UUID craftingRequestId) {
        super(id, workflow);
        this.orderSide = orderSide;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.craftingRequestId = craftingRequestId;
    }

    Direction orderSide() {
        return orderSide;
    }

    @Nullable
    UUID craftingRequestId() {
        return craftingRequestId;
    }

    @Override
    ScriptExecutionContext context() {
        return new ScriptExecutionContext(id(), orderSide, inputs, outputs);
    }
}
