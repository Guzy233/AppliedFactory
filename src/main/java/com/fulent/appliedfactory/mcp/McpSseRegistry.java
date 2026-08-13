package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the SSE output streams opened by agent clients via {@code GET /mcp}. */
public final class McpSseRegistry {
    private final Set<OutputStream> streams = ConcurrentHashMap.newKeySet();

    public void register(OutputStream stream) {
        streams.add(stream);
    }

    public void closeAll() {
        for (var stream : streams) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        streams.clear();
    }
}
