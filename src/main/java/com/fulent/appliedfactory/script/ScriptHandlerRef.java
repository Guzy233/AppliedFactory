package com.fulent.appliedfactory.script;

/** Identifies a processing handler in one evaluated program revision. */
public record ScriptHandlerRef(Kind kind, int index) {
    public ScriptHandlerRef {
        if (kind == Kind.CONTROLLER && index != -1) {
            throw new IllegalArgumentException("Controller handler index must be -1");
        }
        if (kind == Kind.PATTERN && index < 0) {
            throw new IllegalArgumentException("Pattern handler index cannot be negative");
        }
    }

    public static ScriptHandlerRef controller() {
        return new ScriptHandlerRef(Kind.CONTROLLER, -1);
    }

    public static ScriptHandlerRef pattern(int index) {
        return new ScriptHandlerRef(Kind.PATTERN, index);
    }

    public enum Kind {
        CONTROLLER, PATTERN
    }
}
