package com.fulent.appliedfactory.script;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Immutable registration manifest produced when a controller source is evaluated. */
public record CompiledControllerProgram(
        List<ScriptPattern> scriptPatterns,
        int passiveHandlerCount) {
    public static final CompiledControllerProgram EMPTY =
            new CompiledControllerProgram(List.of(), 0);

    public CompiledControllerProgram {
        scriptPatterns = List.copyOf(scriptPatterns);
        if (passiveHandlerCount < 0) {
            throw new IllegalArgumentException("Passive handler count cannot be negative");
        }
    }

    public record ScriptPattern(
            String id,
            Direction orderNetwork,
            ItemStack encodedPattern,
            int handlerIndex) {
        public ScriptPattern {
            if (id.isBlank()) {
                throw new IllegalArgumentException("Script pattern id cannot be blank");
            }
            Objects.requireNonNull(orderNetwork, "orderNetwork");
            encodedPattern = encodedPattern.copy();
            if (encodedPattern.isEmpty()) {
                throw new IllegalArgumentException("Encoded script pattern cannot be empty");
            }
            if (handlerIndex < 0) {
                throw new IllegalArgumentException("Handler index cannot be negative");
            }
        }

        @Override
        public ItemStack encodedPattern() {
            return encodedPattern.copy();
        }
    }
}
