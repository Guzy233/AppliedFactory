package com.fulent.appliedfactory.script;

/** Controller-owned scripting engine for one loaded source revision. */
public interface ScriptRuntime {
    ProgramLoadResult<CompiledControllerProgram> loadProgram(String source);

    ProgramLoadResult<ScriptWorkflow> createWorkflow(
            ScriptHandlerRef handler,
            ScriptExecutionContext context);

    ScriptStep advance(
            ScriptWorkflow workflow,
            ScriptExecutionContext context,
            Object result,
            boolean firstStep);

    void runTopologyListeners();
}
