package com.fulent.appliedfactory.script;

import org.graalvm.polyglot.Value;

/** Live GraalJS generator. Deliberately in-memory only. */
public record ScriptWorkflow(Value generator) {
}
