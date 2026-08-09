// @ts-check
/// <reference path="./factory-controller.d.ts" />

const ORDER_NETWORK = "west";
const PRODUCTION_NETWORK = "east";

/** @typedef {{ id: string, ore: string, raw: string, ingot: string }} Ore */

/** @type {readonly Ore[]} */
const VANILLA_ORES = ["iron", "gold", "copper"].map((ore_name) => ({
  id: ore_name,
  ore: `minecraft:${ore_name}_ore`,
  raw: `minecraft:raw_${ore_name}`,
  ingot: `minecraft:${ore_name}_ingot`,
}));

/**@type {Bus[]} */
var furnaces_inputs = [];
/**@type {Bus[]} */
var furnaces_outputs = [];

initialize({
  networks: [PRODUCTION_NETWORK],
  /** @param {InitializeContext} ctx */
  handler: function (ctx) {
    for (var bus of ctx.buses) {
      if (bus.detect("minecraft:furnace")) {
        if (bus.targetFace == "up") furnaces_inputs.push(bus);
        if (bus.targetFace == "down") furnaces_outputs.push(bus);
      }
    }
  },
});

/**
 * @param {PassiveContext} ctx
 * @param {readonly OwnedResource[]} input
 */
function feedFurnace(ctx, input) {
  while (true) {
    for (var bus of furnaces_inputs) {
      if (bus.items()?.canPush(input)) {
        bus.items()?.push(input);
        return;
      }
    }
    ctx.sleep(10);
  }
}

// 1. Batch-register main-network raw-ore -> ingot processing patterns.
registerPatterns({
  orderNetwork: ORDER_NETWORK,
  patterns: VANILLA_ORES.map((ore) => ({
    id: "smelt_" + ore.id,
    inputs: [item(ore.raw, 1)],
    outputs: [item(ore.ingot, 1)],
    handler: (ctx) => feedFurnace(ctx, ctx.inputs),
  })),
});

/**
 * The processing handler for ore-block -> raw-ore orders: place the block at the
 * dedicated mining bus and capture its normal block drops.
 * @param {ProcessingContext} ctx
 * @returns {void}
 */
function mineOre(ctx) {
  while (true) {
    const buses = ctx.network(PRODUCTION_NETWORK).buses;
    for (let index = 0; index < buses.length; index++) {
      if (
        buses[index].detect("minecraft:air") &&
        buses[index].place(ctx.inputs[0])
      ) {
        ctx.sleep(1);
        ctx.orderNetwork.items().push(buses[index].break());
        return;
      }
    }
    ctx.sleep(10);
  }
}

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

/**
 * Celebration light show: pulse each redstone-lamp bus from west to east.
 * @param {BaseContext} ctx
 * @returns {void}
 */
function celebrate(ctx) {
  var production = ctx.network(PRODUCTION_NETWORK);
  if (!production.online()) {
    return;
  }
  var lamps = [];
  for (var i = 0; i < production.buses.length; i++) {
    var bus = production.buses[i];
    var target = bus.target();
    if (target && target.id == "minecraft:redstone_lamp" && bus.targetFace == "up") {
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

registerPassive(function (ctx) {
  var mainnet = ctx.network(ORDER_NETWORK);
  var subnet = ctx.network(PRODUCTION_NETWORK);
  while (true) {
    for (var index = 0; index < VANILLA_ORES.length; index++) {
      var ore = VANILLA_ORES[index];

      var raw = subnet.items().extract(item(ore.raw, 1));
      if (raw.length !== 0) {
        feedFurnace(ctx, raw);
        raw = [];
        break;
      }
    }
    for (var bus of furnaces_outputs) {
      var output = bus.items()?.extract();
      if (output && output?.length !== 0) {
        mainnet.items().push(output);
        celebrate(ctx);
      }
    }
    ctx.sleep(20);
  }
});
