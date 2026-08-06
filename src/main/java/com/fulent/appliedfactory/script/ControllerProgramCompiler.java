package com.fulent.appliedfactory.script;

/**
 * Creates isolated runtimes; a controller must never share Rhino state with
 * another controller.
 */
public final class ControllerProgramCompiler {
    private ControllerProgramCompiler() {
    }

    public static ScriptRuntime createRuntime() {
        return new RhinoScriptRuntime();
    }
}
