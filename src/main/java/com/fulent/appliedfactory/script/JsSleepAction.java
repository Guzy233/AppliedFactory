package com.fulent.appliedfactory.script;

import com.fulent.appliedfactory.factory.FactoryAction;
import com.fulent.appliedfactory.factory.FactorySleepAction;

/** Script-visible deferred sleep. */
@JsBridge
final class JsSleepAction {
    private final FactorySleepAction action;

    JsSleepAction(FactorySleepAction action) {
        this.action = action;
    }

    FactoryAction action() {
        return action;
    }
}
