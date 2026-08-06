package com.fulent.appliedfactory.script;

/**
 * Opaque, resumable script continuation.
 *
 * <p>During normal ticking a continuation is kept live on the JVM heap and resumed
 * directly, avoiding a serialize/deserialize round trip on every script suspend
 * (sleep expiry, bus push, extract, ...). It is converted to a byte array only when
 * the controller block entity is persisted (chunk save/unload) via {@link #serialize()}.
 * The byte form is cached, so repeated saves without an intervening resume are cheap.
 */
public interface ScriptContinuation {
    /** The persistable byte form. Empty only for a finished/absent continuation. */
    byte[] serialize();

    /** Whether this continuation carries no resumable state. */
    boolean isEmpty();

    /** Wraps continuation bytes restored from disk; resumable once the runtime re-inflates them. */
    static ScriptContinuation ofPersisted(byte[] bytes) {
        return bytes.length == 0 ? PersistedContinuation.EMPTY : new PersistedContinuation(bytes);
    }

    /** The empty continuation used by finished/stopped jobs. */
    static ScriptContinuation empty() {
        return PersistedContinuation.EMPTY;
    }
}
