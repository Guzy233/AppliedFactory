package com.fulent.appliedfactory.script;

/** Result of starting or resuming one durable script workflow. */
public sealed interface ScriptStep {
    record Suspended(ScriptContinuation continuation, FactoryScriptAction action)
            implements ScriptStep {
    }

    record Completed() implements ScriptStep {
    }

    record Failed(String message) implements ScriptStep {
    }
}
