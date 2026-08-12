package com.fulent.appliedfactory.script;

import com.fulent.appliedfactory.factory.FactoryProgram;

/** Constructs the isolated Rhino runtime owned by one controller program revision. */
public final class ControllerProgramCompiler {
    private ControllerProgramCompiler() {
    }

    public static ScriptRuntime createRuntime(FactoryProgram.Host host) {
        return new RhinoScriptRuntime(host);
    }
}
