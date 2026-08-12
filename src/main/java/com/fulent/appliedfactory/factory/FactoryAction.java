package com.fulent.appliedfactory.factory;

/** One value a JavaScript generator may yield to the controller scheduler. */
public sealed interface FactoryAction permits FactorySleepAction, FactoryTransferAction {
}
