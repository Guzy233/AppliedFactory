package com.fulent.appliedfactory.script;

import java.util.Objects;

/** Result of compiling one isolated controller-program runtime. */
public record ProgramLoadResult(CompiledControllerProgram program, String errorMessage) {
    public ProgramLoadResult {
        Objects.requireNonNull(program, "program");
    }

    public static ProgramLoadResult success(CompiledControllerProgram program) {
        return new ProgramLoadResult(program, null);
    }

    public static ProgramLoadResult failure(String errorMessage) {
        return new ProgramLoadResult(CompiledControllerProgram.EMPTY,
                Objects.requireNonNullElse(errorMessage, "Unknown script error"));
    }

    public boolean successful() {
        return errorMessage == null;
    }
}
