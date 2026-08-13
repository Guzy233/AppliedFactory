package com.fulent.appliedfactory.mcp;

import net.minecraft.core.BlockPos;

/** The controller the player linked to MCP; the AI operates on it implicitly. */
public record McpBinding(String dimension, BlockPos pos, String label) {
}
