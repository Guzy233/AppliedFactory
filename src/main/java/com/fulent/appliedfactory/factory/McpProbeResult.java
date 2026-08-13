package com.fulent.appliedfactory.factory;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/** Outcome of one MCP probe run, ready to serialize back to the agent. */
public record McpProbeResult(
        String reason,
        String message,
        List<String> logs,
        @Nullable String resultJson,
        List<String> pending,
        long elapsedTicks,
        long steps) {
    public McpProbeResult {
        logs = List.copyOf(logs);
        pending = List.copyOf(pending);
    }
}
