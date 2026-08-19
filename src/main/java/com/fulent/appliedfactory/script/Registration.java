package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Value;

/** Mutable only while one source file is being evaluated. */
final class Registration {
    final List<CompiledControllerProgram.ScriptPattern> patterns = new ArrayList<>();
    final List<Value> patternHandlers = new ArrayList<>();
    final List<Value> passiveHandlers = new ArrayList<>();
    boolean sealed;

    void requireOpen() {
        if (sealed) {
            throw JsValues.error("Registration APIs may only be called while loading controller source");
        }
    }

    CompiledControllerProgram manifest() {
        return new CompiledControllerProgram(patterns, passiveHandlers.size());
    }
}
