// require_recipes is a client-side precompile macro: the MCP client replaces
// the call with the matching recipe literals from processing_recipes.json
// before the script is sent, so this file can reference baked recipe data.
const ironSmelt = require_recipes({
  type: "minecraft:smelting",
  output: "minecraft:iron_ingot",
});

const production = network("east");
const main = network("west");
const DIRECTIONS = ["up", "down", "north", "south", "west", "east"];
let furnaces_input = [];

let furnaces_output = [];

let lamps = [];

let machines = [];

let tanks = [];

function init() {
  furnaces_input = production.buses.filter(
    (bus) => bus.target.id === "minecraft:furnace" && bus.targetFace === "up",
  );

  furnaces_output = production.buses.filter(
    (bus) => bus.target.id === "minecraft:furnace" && bus.targetFace === "down",
  );

  lamps = production.buses
    .filter((bus) => bus.target.id === "minecraft:redstone_lamp")
    .sort((a, b) => a.target.x - b.target.x);

  machines = production.buses.filter((bus) =>
    bus.channels.includes("appflux:flux"),
  );

  tanks = production.buses.filter((bus) =>
    bus.channels.includes("appmek:chemical"),
  );
}

production.onChange(init);
init();

registerProcessingPattern(
  [
    {
      orderNetwork: "west",
      inputs: [item("minecraft:iron_ore", 1)],
      outputs: [item("minecraft:iron_ingot", 1)],
    },
  ],
  function* (order) {
    yield order.input.pushExactlyInto(furnaces_input[0]);
  },
);

go(function* () {
  while (true) {
    for (var bus of furnaces_output) {
      var output = bus.extract();
      if (output.length > 0) {
        log(`${output[0].amount} ${output[0].id} returned!`);
        output.to(main).now();
        for (var lamp of lamps) {
          lamp.redstone(15);
          yield sleep(2);
          lamp.redstone(0);
        }
      }
    }
    // demo to transfer energy
    machines.forEach((machine) =>
      main.extract("appflux:flux", { type: "FE" }).to(machine).now(),
    );
    // demo to transfer chemicals
    tanks.forEach((tank) =>
      main
        .extract("appmek:chemical", { id: "mekanism:chlorine" })
        .to(tank)
        .now(),
    );
    yield sleep(20);
  }
});
