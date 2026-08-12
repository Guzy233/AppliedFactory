package com.fulent.appliedfactory.factory;

/** Waits at least the requested number of server ticks. */
public record FactorySleepAction(int ticks) implements FactoryAction {
    public FactorySleepAction {
        if (ticks < 0) {
            throw new IllegalArgumentException("Sleep duration cannot be negative");
        }
    }
}
