// Display-only MVP API reference. Do not import this file into a controller program.

/** @param {string} side @returns {Network} */
function network(side) {}

/** @param {number} ticks @returns {SleepAction} */
function sleep(ticks) {}

/** @param {() => Generator<Action, unknown, unknown>} factory */
function go(factory) {}

/**
 * @param {readonly PatternDefinition[]} patterns
 * @param {(order: Order) => Generator<Action, unknown, unknown>} handler
 */
function registerProcessingPattern(patterns, handler) {}

/** @param {string} id @param {number} amount @returns {ResourceSpec} */
function item(id, amount) {}

/** @param {string} channel @param {string} id @param {number} amount @returns {ResourceSpec} */
function stack(channel, id, amount) {}

/** @param {string} serializedKey @param {number} amount @returns {ResourceSpec} */
function stackTag(serializedKey, amount) {}

// Resource methods:
// resource.to(target)                    -> TransferAction
// resource.pushExactlyInto(target)       -> ExactTransferAction
// resource.to(target).now()              -> remaining Resource
// resource.pushExactlyInto(target).now() -> boolean
//
// A yielded Action waits for both source resources and destination capacity.
