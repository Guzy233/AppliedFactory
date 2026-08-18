package com.fulent.appliedfactory.script;

import com.fulent.appliedfactory.factory.FactoryAction;
import com.fulent.appliedfactory.factory.FactoryTransferAction;

/** Script-visible deferred resource transfer. */
@JsBridge
final class JsTransferAction {
    private final ScriptApi api;
    private final FactoryTransferAction action;
    private final boolean arrayResult;

    JsTransferAction(
            ScriptApi api, FactoryTransferAction action, boolean arrayResult) {
        this.api = api;
        this.action = action;
        this.arrayResult = arrayResult;
    }

    FactoryAction action() {
        return action;
    }

    public Object now() {
        return api.performNow(action, arrayResult);
    }
}
