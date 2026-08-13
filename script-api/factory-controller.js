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

/** @param {string} id @param {number} amount @param {object} [components] @returns {ResourceSpec} */
function item(id, amount, components) {}

/** @param {string} channel @param {object} key @param {number} amount @returns {ResourceSpec} */
function stack(channel, key, amount) {}

/** @param {Resource} resource @param {string} name @returns {Resource|null} */
function rename(resource, name) {}

/** @param {Resource} resource @returns {object} */
function itemNbt(resource) {}

// Resource methods:
// resource.to(target)                    -> TransferAction
// resource.pushExactlyInto(target)       -> ExactTransferAction
// resource.to(target).now()              -> remaining Resource
// resource.pushExactlyInto(target).now() -> boolean
// resourceArray.to(target)                -> one batch TransferAction
// rename(resource, name)                 -> renamed Resource | null (immediate)
// itemNbt(resource)                      -> full ItemStack NBT
// bus.drop(resource)                     -> boolean (immediate)
// bus.use(resource)                      -> boolean; source item updated in place
// bus.place(resource)                    -> boolean; source item updated in place
// bus.break(tool)                        -> ResourceArray | null; synchronous
// bus.redstone()                        -> number 0-15 (read)
// bus.redstone(level)                   -> boolean (set, 0-15, immediate)
//
// A yielded Action waits for both source resources and destination capacity.
