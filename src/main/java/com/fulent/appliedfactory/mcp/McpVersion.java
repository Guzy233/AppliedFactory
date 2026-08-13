package com.fulent.appliedfactory.mcp;

import net.neoforged.fml.ModList;

import com.fulent.appliedfactory.AppliedFactory;

/** Current mod version, reported in the MCP initialize handshake. */
public final class McpVersion {
    private McpVersion() {
    }

    public static String current() {
        return ModList.get().getModContainerById(AppliedFactory.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("0.0.0");
    }
}
