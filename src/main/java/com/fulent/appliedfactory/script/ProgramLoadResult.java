package com.fulent.appliedfactory.script;

import java.util.Objects;

/**
 * Result of loading a controller-program source into a runtime. {@code T} is the compiled
 * artifact carried on success — historically {@link CompiledControllerProgram}, now also
 * {@code FactoryProgram} when the whole program (runtime + jobs) is loaded at once.
 */
public record ProgramLoadResult<T>(T program, String errorMessage) {
    public static <T> ProgramLoadResult<T> success(T program) {
        return new ProgramLoadResult<>(Objects.requireNonNull(program, "program"), null);
    }

    public static <T> ProgramLoadResult<T> failure(String errorMessage) {
        return new ProgramLoadResult<>(null,
                Objects.requireNonNullElse(errorMessage, "Unknown script error"));
    }

    public boolean successful() {
        return errorMessage == null;
    }
}
