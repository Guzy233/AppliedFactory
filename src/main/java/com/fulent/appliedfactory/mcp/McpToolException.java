package com.fulent.appliedfactory.mcp;

/** A JSON-RPC error carrying an MCP error code, thrown by tool handlers. */
public final class McpToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int code;

    public McpToolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
