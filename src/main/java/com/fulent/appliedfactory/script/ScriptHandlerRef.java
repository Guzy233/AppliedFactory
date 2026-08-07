package com.fulent.appliedfactory.script;

/**
 * Identifies a handler registered by one evaluated program revision. The four kinds cover
 * every script entry point uniformly, so {@link ScriptRuntime} exposes a single
 * {@code startHandler} instead of one method per handler kind.
 */
public record ScriptHandlerRef(Kind kind, int index) {
    public ScriptHandlerRef {
        if ((kind == Kind.CONTROLLER || kind == Kind.INITIALIZER) && index != -1) {
            throw new IllegalArgumentException("Controller/initializer handler index must be -1");
        }
        if ((kind == Kind.PATTERN || kind == Kind.PASSIVE) && index < 0) {
            throw new IllegalArgumentException("Pattern/passive handler index cannot be negative");
        }
    }

    public static ScriptHandlerRef controller() {
        return new ScriptHandlerRef(Kind.CONTROLLER, -1);
    }

    public static ScriptHandlerRef initializer() {
        return new ScriptHandlerRef(Kind.INITIALIZER, -1);
    }

    public static ScriptHandlerRef pattern(int index) {
        return new ScriptHandlerRef(Kind.PATTERN, index);
    }

    public static ScriptHandlerRef passive(int index) {
        return new ScriptHandlerRef(Kind.PASSIVE, index);
    }

    public enum Kind {
        CONTROLLER, INITIALIZER, PATTERN, PASSIVE
    }
}
