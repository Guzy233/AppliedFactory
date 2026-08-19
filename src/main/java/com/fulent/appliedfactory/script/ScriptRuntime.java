package com.fulent.appliedfactory.script;

import org.jetbrains.annotations.Nullable;

/** Controller-owned scripting engine for one loaded source revision. */
public interface ScriptRuntime {
    default ProgramLoadResult<CompiledControllerProgram> loadProgram(String source) {
        return loadProgram(source, null);
    }

    /**
     * @param topLevelContext when non-null, binds a transient workflow context during the
     *                        top-level evaluation so {@code .now()} works there too
     */
    ProgramLoadResult<CompiledControllerProgram> loadProgram(
            String source, @Nullable ScriptExecutionContext topLevelContext);

    ProgramLoadResult<ScriptWorkflow> createWorkflow(
            ScriptHandlerRef handler,
            ScriptExecutionContext context);

    ScriptStep advance(
            ScriptWorkflow workflow,
            ScriptExecutionContext context,
            Object result,
            boolean firstStep);

    void runTopologyListeners();

    /** Releases the engine context and every live guest value owned by it. */
    default void close() {
    }

    /** JSON-serialized value of the last top-level expression, or null when unavailable. */
    @Nullable
    default String lastValueJson() {
        return null;
    }
}
