package com.fulent.appliedfactory.script;

/** Controller-owned scripting engine for one loaded program revision. */
public interface ScriptRuntime {
    ProgramLoadResult<CompiledControllerProgram> loadProgram(String source);

    ScriptStep runInitializer(ScriptExecutionContext request);

    ScriptStep startProcessing(ScriptHandlerRef handler, ScriptExecutionContext request);

    ScriptStep startPassive(int handlerIndex, ScriptExecutionContext request);

    ScriptStep resume(
            ScriptExecutionContext request,
            ScriptContinuation continuation,
            FactoryActionResult result);
}
