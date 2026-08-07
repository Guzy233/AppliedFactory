package com.fulent.appliedfactory.script;

/** Controller-owned scripting engine for one loaded program revision. */
public interface ScriptRuntime {
    ProgramLoadResult<CompiledControllerProgram> loadProgram(String source);

    /**
     * Starts any registered handler (initializer, controller, pattern or passive). The handler
     * may suspend; the returned step then carries its continuation for later {@link #resume}.
     */
    ScriptStep startHandler(ScriptHandlerRef handler, ScriptExecutionContext request);

    ScriptStep resume(
            ScriptExecutionContext request,
            ScriptContinuation continuation,
            FactoryActionResult result);
}
