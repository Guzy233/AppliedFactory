package com.fulent.appliedfactory.script;

import com.fulent.appliedfactory.factory.FactoryAction;

/** Outcome of advancing one JavaScript generator once. */
public sealed interface ScriptStep {
    record Waiting(FactoryAction action) implements ScriptStep {
    }

    record Completed() implements ScriptStep {
    }

    record Failed(String message) implements ScriptStep {
    }
}
