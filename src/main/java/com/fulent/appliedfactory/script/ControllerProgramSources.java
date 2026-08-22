package com.fulent.appliedfactory.script;

/** The editable and executable forms of one controller program. */
public record ControllerProgramSources(
        String source, String compiledSource, String workspacePath, long updatedAt) {
    public ControllerProgramSources {
        source = source == null ? "" : source;
        compiledSource = compiledSource == null ? "" : compiledSource;
        workspacePath = workspacePath == null ? "" : workspacePath;
        updatedAt = Math.max(0L, updatedAt);
    }
}
