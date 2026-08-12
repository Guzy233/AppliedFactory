package com.fulent.appliedfactory.script;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.factory.FactoryResource;

import net.minecraft.core.Direction;

/** Per-generator identity and order data; live world access remains on durable API handles. */
public record ScriptExecutionContext(
        UUID workflowId,
        @Nullable Direction orderNetwork,
        List<FactoryResource> inputs,
        List<FactoryResource> outputs) {

    public ScriptExecutionContext {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }
}
