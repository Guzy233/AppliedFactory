package com.fulent.appliedfactory.script;

import com.fulent.appliedfactory.factory.FactoryProgram;

/** Constructs the isolated GraalJS runtime owned by one controller program revision. */
public final class ControllerProgramCompiler {
    private ControllerProgramCompiler() {
    }

    public static ScriptRuntime createRuntime(FactoryProgram.Host host) {
        return new GraalScriptRuntime(host);
    }
}
