package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;

import net.minecraft.core.Direction;

/**
 * Collects the registrations a script makes while its source is evaluated
 * ({@code initialize} / {@code registerPatterns} / {@code registerControllerHandler} /
 * {@code registerPassive}), then seals them into an immutable {@link CompiledControllerProgram}.
 */
final class Registration {
    final Set<Direction> initializerNetworks = EnumSet.noneOf(Direction.class);
    final List<CompiledControllerProgram.ScriptPattern> patterns = new ArrayList<>();
    final List<Function> patternHandlers = new ArrayList<>();
    final List<Function> passiveHandlers = new ArrayList<>();
    Function initializer;
    Function controllerHandler;
    Direction controllerOrderNetwork;
    boolean sealed;

    void requireOpen() {
        if (sealed) {
            throw Context.reportRuntimeError(
                    "Registration APIs may only be called while loading controller source");
        }
    }

    CompiledControllerProgram manifest() {
        return new CompiledControllerProgram(
                initializerNetworks,
                controllerOrderNetwork,
                patterns,
                passiveHandlers.size());
    }
}
