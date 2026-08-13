package com.fulent.appliedfactory.mcp;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.fulent.appliedfactory.network.McpBindResultPayload;
import com.fulent.appliedfactory.network.McpCodeResultPayload;
import com.fulent.appliedfactory.network.UploadResultPayload;

/** Maps request ids issued by the HTTP server to the futures completed by network replies. */
public final class McpRequestRegistry {
    private final ConcurrentHashMap<UUID, CompletableFuture<McpCodeResultPayload>> code =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<UploadResultPayload>> upload =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<McpBindResultPayload>> bind =
            new ConcurrentHashMap<>();

    public CompletableFuture<McpCodeResultPayload> awaitCode(UUID requestId) {
        var future = new CompletableFuture<McpCodeResultPayload>();
        code.put(requestId, future);
        return future;
    }

    public void completeCode(McpCodeResultPayload payload) {
        var future = code.remove(payload.requestId());
        if (future != null) {
            future.complete(payload);
        }
    }

    public CompletableFuture<UploadResultPayload> awaitUpload(UUID requestId) {
        var future = new CompletableFuture<UploadResultPayload>();
        upload.put(requestId, future);
        return future;
    }

    public void completeUpload(UploadResultPayload payload) {
        var future = upload.remove(payload.requestId());
        if (future != null) {
            future.complete(payload);
        }
    }

    public CompletableFuture<McpBindResultPayload> awaitBind(UUID requestId) {
        var future = new CompletableFuture<McpBindResultPayload>();
        bind.put(requestId, future);
        return future;
    }

    public void completeBind(McpBindResultPayload payload) {
        var future = bind.remove(payload.requestId());
        if (future != null) {
            future.complete(payload);
        }
    }

    public void clear() {
        code.clear();
        upload.clear();
        bind.clear();
    }
}
