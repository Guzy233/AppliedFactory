package com.fulent.appliedfactory.script;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Immutable registration manifest produced when a controller source is evaluated. */
public record CompiledControllerProgram(
        Set<Direction> initializerNetworks,
        @Nullable Direction controllerOrderNetwork,
        List<ScriptPattern> scriptPatterns,
        int passiveHandlerCount) {
    public static final CompiledControllerProgram EMPTY =
            new CompiledControllerProgram(Set.of(), null, List.of(), 0);

    public CompiledControllerProgram {
        initializerNetworks = Set.copyOf(initializerNetworks);
        scriptPatterns = List.copyOf(scriptPatterns);
        if (passiveHandlerCount < 0) {
            throw new IllegalArgumentException("Passive handler count cannot be negative");
        }
    }

    public boolean hasControllerHandler() {
        return controllerOrderNetwork != null;
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
