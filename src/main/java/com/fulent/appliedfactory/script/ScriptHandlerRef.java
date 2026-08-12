package com.fulent.appliedfactory.script;

/** Index of a generator factory registered by one evaluated source revision. */
public record ScriptHandlerRef(Kind kind, int index) {
    public ScriptHandlerRef {
        if (index < 0) {
            throw new IllegalArgumentException("Handler index cannot be negative");
        }
    }

    public static ScriptHandlerRef pattern(int index) {
        return new ScriptHandlerRef(Kind.PATTERN, index);
    }

    public static ScriptHandlerRef passive(int index) {
        return new ScriptHandlerRef(Kind.PASSIVE, index);
    }

    public enum Kind {
        PATTERN,
        PASSIVE
    }
}
