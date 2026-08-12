package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.List;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;

/** Mutable only while one source file is being evaluated. */
final class Registration {
    final List<CompiledControllerProgram.ScriptPattern> patterns = new ArrayList<>();
    final List<Function> patternHandlers = new ArrayList<>();
    final List<Function> passiveHandlers = new ArrayList<>();
    boolean sealed;

    void requireOpen() {
        if (sealed) {
            throw Context.reportRuntimeError(
                    "Registration APIs may only be called while loading controller source");
        }
    }

    CompiledControllerProgram manifest() {
        return new CompiledControllerProgram(patterns, passiveHandlers.size());
    }
}
