package com.fulent.appliedfactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptWorkflow;

/** One in-memory generator and the action it is currently waiting for. */
abstract class FactoryJob {
    private final UUID id;
    private final ScriptWorkflow workflow;
    @Nullable
    private FactoryAction pendingAction;
    private long actionStartedTick;
    private boolean firstStep = true;

    FactoryJob(UUID id, ScriptWorkflow workflow) {
        this.id = id;
        this.workflow = workflow;
    }

    abstract ScriptExecutionContext context();

    UUID id() {
        return id;
    }

    ScriptWorkflow workflow() {
        return workflow;
    }

    @Nullable
    FactoryAction pendingAction() {
        return pendingAction;
    }

    long actionStartedTick() {
        return actionStartedTick;
    }

    boolean firstStep() {
        return firstStep;
    }

    void setWaiting(FactoryAction action, long startedTick) {
        pendingAction = action;
        actionStartedTick = startedTick;
        firstStep = false;
    }

    void clearWaiting() {
        pendingAction = null;
    }
}
