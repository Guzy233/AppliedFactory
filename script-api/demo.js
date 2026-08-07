// @ts-check
/// <reference path="./factory-controller.d.ts" />

//   Connect two Factory Buses in that subnet to a furnace's
//   input/output faces, and one extra Factory Bus to an empty air block.
// - install a storage cell in the controller before ordering a pattern.
const ORDER_NETWORK = "west";
const PRODUCTION_NETWORK = "east";
const SMELT_TIME = 200;
const PASSIVE_TARGET_PER_TYPE = 16;

/** @typedef {{ id: string, ore: string, raw: string, ingot: string }} Ore */
/** @typedef {Bus & { items(): BusItems }} ItemBus */
/** @typedef {{ input: ItemBus, output: ItemBus }} FurnaceLine */

// Kept intentionally explicit for the first video. JEI-driven discovery can replace this table.
/** @type {readonly Ore[]} */
const VANILLA_ORES = [
  {
    id: "iron",
    ore: "minecraft:iron_ore",
    raw: "minecraft:raw_iron",
    ingot: "minecraft:iron_ingot",
  },
  {
    id: "gold",
    ore: "minecraft:gold_ore",
    raw: "minecraft:raw_gold",
    ingot: "minecraft:gold_ingot",
  },
  {
    id: "copper",
    ore: "minecraft:copper_ore",
    raw: "minecraft:raw_copper",
    ingot: "minecraft:copper_ingot",
  },
];

/**
 * Pair each furnace input Bus with its output Bus using their shared target address.
 * Cache target() results immediately to avoid stale reads.
 * @param {readonly Bus[]} buses
 * @returns {FurnaceLine[]}
 */
function discoverFurnaces(buses) {
  /** @type {Map<string, ItemBus>} */
  const outputs = new Map();

  // First pass: collect all output buses.
  // NOTE: loop-body declarations use `var`, not `const`/`let`. Rhino's interpreted
  // mode (required for continuations) does not re-initialize block-scoped bindings
  // per iteration (mozilla/rhino#326), so a captured `const bus = buses[index]`
  // stays frozen on buses[0]. `var` is function-scoped and reassigns correctly.
  for (var index = 0; index < buses.length; index++) {
    var bus = buses[index];
    var targetFace = bus.targetFace;
    var items = bus.items();
    var target = bus.target();
    var addr = bus.targetAddress.key;

    if (
      target !== null &&
      target.id === "minecraft:furnace" &&
      targetFace === "down" &&
      items !== null
    ) {
      outputs.set(addr, /** @type {ItemBus} */ (bus));
    }
  }

  // Second pass: match input buses with their outputs (same `var` rule as above).
  var lines = [];
  for (var i = 0; i < buses.length; i++) {
    var inputBus = buses[i];
    var inputTargetFace = inputBus.targetFace;
    var inputItems = inputBus.items();
    var inputTarget = inputBus.target();
    var inputAddr = inputBus.targetAddress.key;

    if (
      inputTarget !== null &&
      inputTarget.id === "minecraft:furnace" &&
      inputTargetFace === "up" &&
      inputItems !== null &&
      outputs.has(inputAddr)
    ) {
      lines.push({
        input: /** @type {ItemBus} */ (inputBus),
        output: /** @type {ItemBus} */ (outputs.get(inputAddr)),
      });
    }
  }

  return lines;
}

/**
 * Describe every Bus visible from the production controller face. This runs only
 * during initialization, so a wiring mistake is visible in the server log without
 * turning an expected, temporary full furnace input into a warning spam loop.
 * @param {readonly Bus[]} buses
 * @returns {string}
 */
function describeProductionBuses(buses) {
  if (buses.length === 0) {
    return "none";
  }

  return buses
    .map(function (bus) {
      const target = bus.target();
      const targetId = target === null ? "unresolved" : target.id;
      return (
        targetId +
        "@" +
        bus.targetFace +
        " items=" +
        (bus.items() !== null) +
        " address=" +
        bus.targetAddress.key
      );
    })
    .join("; ");
}

/**
 * Wait for any currently discoverable furnace input to accept these owned resources.
 * @param {BaseContext} ctx
 * @param {readonly OwnedResource[]} resources
 * @returns {FurnaceLine}
 */
function feedFurnace(ctx, resources) {
  const production = ctx.network(PRODUCTION_NETWORK);
  if (production.online()) {
    const buses = production.buses;
    const lines = discoverFurnaces(buses);

    for (var index = 0; index < lines.length; index++) {
      var line = lines[index];
      if (line.input.items()?.push(resources)) {
        return line;
      }
    }
  }
}

/**
 * Wait for a furnace output face to expose any completed items.
 * @param {BaseContext} ctx
 * @param {ItemBus} output
 * @returns {readonly OwnedResource[]}
 */
function collectFurnaceOutput(ctx, output) {
  while (true) {
    var products = output.items().extract();
    if (products.length !== 0) {
      return products;
    }
    ctx.sleep(20);
  }
}

/**
 * Return owned resources to an AE network, retrying only while it is full/offline.
 * @param {BaseContext} ctx
 * @param {Network} network
 * @param {readonly OwnedResource[]} resources
 */
function deliverToNetwork(ctx, network, resources) {
  while (!network.items().push(resources)) {
    ctx.sleep(5);
  }
}

/**
 * Locate the dedicated mining Bus. Its target must be an air block before each operation.
 * @param {BaseContext} ctx
 * @returns {Bus | null}
 */
function findMiningBus(ctx) {
  const buses = ctx.network(PRODUCTION_NETWORK).buses;
  for (let index = 0; index < buses.length; index++) {
    if (buses[index].detect("minecraft:air")) {
      return buses[index];
    }
  }
  return null;
}

/**
 * The normal processing handler: raw ore becomes an ingot and returns to the order network.
 * @param {ProcessingContext} ctx
 * @returns {void}
 */
function smelt(ctx) {
  const line = feedFurnace(ctx, ctx.inputs);
  ctx.sleep(SMELT_TIME);
  deliverToNetwork(
    ctx,
    ctx.orderNetwork,
    collectFurnaceOutput(ctx, line.output),
  );

  // Celebration light show: pulse each redstone-lamp bus (targetFace=down) from west to east.
  var production = ctx.network(PRODUCTION_NETWORK);
  if (production.online()) {
    var buses = production.buses;
    var lamps = [];
    for (var i = 0; i < buses.length; i++) {
      var bus = buses[i];
      var target = bus.target();
      if (target !== null && bus.targetFace == "up") {
        lamps.push(bus);
      }
    }
    lamps.sort(function (a, b) {
      var ax = a.targetAddress.x;
      var bx = b.targetAddress.x;
      if (ax !== bx) return ax - bx;
      return a.targetAddress.z - b.targetAddress.z;
    });
    for (var j = 0; j < lamps.length; j++) {
      lamps[j].redstone(15);
      ctx.sleep(1);
      lamps[j].redstone(0);
    }
  }
}

/**
 * Place one ore block into the dedicated air work cell, then capture its normal block drops.
 * @param {ProcessingContext} ctx
 * @returns {void}
 */
function mineOre(ctx) {
  /** @type {Bus | null} */
  let miningBus = null;
  while (miningBus === null) {
    miningBus = findMiningBus(ctx);
    if (miningBus === null || !miningBus.place(ctx.inputs[0])) {
      miningBus = null;
      ctx.sleep(10);
    }
  }

  ctx.sleep(1);
  const drops = miningBus.break();
  if (drops.length === 0) {
    ctx.fail("The mining Bus could not capture an ore drop");
  }
  deliverToNetwork(ctx, ctx.orderNetwork, drops);
}

initialize({
  networks: [PRODUCTION_NETWORK],
  /** @param {InitializeContext} ctx */
  handler: function (ctx) {
    ctx.log(
      "Initializer completed for production network: " + PRODUCTION_NETWORK,
    );
  },
});

// 1. Batch-register main-network raw-ore -> ingot processing patterns.
registerPatterns({
  orderNetwork: ORDER_NETWORK,
  patterns: VANILLA_ORES.map(function (ore) {
    return {
      id: "smelt_" + ore.id,
      inputs: [item(ore.raw, 1)],
      outputs: [item(ore.ingot, 1)],
      handler: smelt,
    };
  }),
});

// 2. Batch-register ore-block -> raw-ore patterns. Copper keeps vanilla's variable loot;
// the one-item declared output is the AE planning contract, while captured extra drops are real.
registerPatterns({
  orderNetwork: ORDER_NETWORK,
  patterns: VANILLA_ORES.map(function (ore) {
    return {
      id: "mine_" + ore.id,
      inputs: [item(ore.ore, 1)],
      outputs: [item(ore.raw, 1)],
      handler: mineOre,
    };
  }),
});

// 3. Passive production: pull raw ore from south, smelt it, and return ingots to south until
// this program revision has delivered PASSIVE_TARGET_PER_TYPE ingots of each hard-coded type.
registerPassive(
  /** @param {PassiveContext} ctx */ function (ctx) {
    /** @type {Record<string, number>} */
    const produced = Object.create(null);
    for (let index = 0; index < VANILLA_ORES.length; index++) {
      produced[VANILLA_ORES[index].id] = 0;
    }

    while (true) {
      const source = ctx.network(PRODUCTION_NETWORK);
      let didWork = false;

      if (source.online()) {
        for (var index = 0; index < VANILLA_ORES.length; index++) {
          var ore = VANILLA_ORES[index];
          if (produced[ore.id] >= PASSIVE_TARGET_PER_TYPE) {
            continue;
          }

          var raw = source.items().extract(item(ore.raw, 1));
          if (raw.length === 0) {
            continue;
          }

          var line = feedFurnace(ctx, raw);
          ctx.sleep(SMELT_TIME);
          var products = collectFurnaceOutput(ctx, line.output);
          deliverToNetwork(ctx, source, products);

          for (
            var productIndex = 0;
            productIndex < products.length;
            productIndex++
          ) {
            if (products[productIndex].id === ore.ingot) {
              produced[ore.id] += products[productIndex].amount;
            }
          }
          ctx.log(
            ore.id +
              " passive total: " +
              produced[ore.id] +
              "/" +
              PASSIVE_TARGET_PER_TYPE,
          );
          didWork = true;
        }
      }

      if (!didWork) {
        ctx.sleep(20);
      }
    }
  },
);
