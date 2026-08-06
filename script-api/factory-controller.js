// Display-only Factory Controller API reference.
//
// Do NOT import this file from a controller program: these bodies are documentation stubs,
// not the runtime API. The complete IDE declarations are in factory-controller.d.ts.

/**
 * Create an immutable item-resource specification.
 * @param {string} id A Minecraft item id, such as "minecraft:iron_ore".
 * @param {number} amount A positive, exact amount.
 * @returns {Resource}
 */
function item(id, amount) {}

/**
 * Declare the networks whose topology should rebuild the script's cached state.
 * @param {InitializerDefinition} definition
 */
function initialize(definition) {}

/**
 * Register virtual AE processing patterns for one controller face.
 * @param {PatternRegistration} definition
 */
function registerPatterns(definition) {}

/**
 * Register the handler used by processing patterns inserted into the controller itself.
 * @param {ControllerHandlerDefinition} definition
 */
function registerControllerHandler(definition) {}

/**
 * Start a long-running passive workflow. Use ctx.sleep() to set its pace.
 * @param {(ctx: PassiveContext) => void} handler
 */
function registerPassive(handler) {}

// A Bus also exposes the following instance operations in controller scripts:
//
// bus.detect("minecraft:furnace"); // block id or #block tag -> boolean
// bus.drop(ctx.inputs);             // releases owned resources as item entities -> boolean
// bus.use();                        // empty-hand interaction -> boolean
// bus.place(ctx.owned[0]);          // places one owned block item -> boolean
// bus.break();                      // breaks the target and captures drops -> OwnedResource[]
// bus.redstone(15);                 // drives this physical bus face with 0..15 -> boolean
