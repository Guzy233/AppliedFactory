package com.fulent.appliedfactory.factory;

import java.util.List;
import java.util.UUID;

import com.fulent.appliedfactory.script.ScriptExecutionContext;
import com.fulent.appliedfactory.script.ScriptWorkflow;

/** In-memory generator created by one go(function* () { ... }) registration. */
final class PassiveJob extends FactoryJob {
    private final int passiveIndex;

    PassiveJob(UUID id, ScriptWorkflow workflow, int passiveIndex) {
        super(id, workflow);
        this.passiveIndex = passiveIndex;
    }

    int passiveIndex() {
        return passiveIndex;
    }

    @Override
    ScriptExecutionContext context() {
        return new ScriptExecutionContext(id(), null, List.of(), List.of());
    }
}
