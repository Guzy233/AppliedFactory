package com.fulent.appliedfactory.script;

import org.mozilla.javascript.Scriptable;

/** Live Rhino generator. Deliberately in-memory only. */
public record ScriptWorkflow(Scriptable generator) {
}
