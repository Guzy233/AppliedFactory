package com.fulent.appliedfactory.script;

import java.util.Arrays;

/**
 * A continuation backed only by its serialized byte form (restored from disk, or empty).
 * The runtime re-inflates the bytes against the loaded scope when this is resumed.
 */
final class PersistedContinuation implements ScriptContinuation {
    static final PersistedContinuation EMPTY = new PersistedContinuation(new byte[0]);

    private final byte[] bytes;

    PersistedContinuation(byte[] bytes) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] serialize() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }
}
