package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.mcp.McpClientManager;
import com.fulent.appliedfactory.network.McpBindResultPayload;
import com.fulent.appliedfactory.network.McpCodeResultPayload;
import com.fulent.appliedfactory.network.UploadResultPayload;

/** Client-only bridge completing the pending HTTP futures with server replies. */
public final class ClientMcpPayloadHandler {
    private ClientMcpPayloadHandler() {
    }

    public static void handleCodeResult(McpCodeResultPayload payload) {
        McpClientManager.get().registry().completeCode(payload);
    }

    public static void handleUploadResult(UploadResultPayload payload) {
        McpClientManager.get().registry().completeUpload(payload);
    }

    public static void handleBindResult(McpBindResultPayload payload) {
        McpClientManager.get().registry().completeBind(payload);
    }
}
