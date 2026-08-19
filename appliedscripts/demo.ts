// require_recipes is a client-side macro expanded before this TypeScript is transpiled.
const ironSmelt = require_recipes({
  type: "minecraft:smelting",
  output: "minecraft:iron_ingot",
});

const production = network("east");
const main = network("west");
let furnacesInput: Bus[] = [];
let furnacesOutput: Bus[] = [];
let lamps: Bus[] = [];
let machines: Bus[] = [];
let tanks: Bus[] = [];

function init(): void {
  furnacesInput = production.buses.filter(
    (bus) => bus.target.id === "minecraft:furnace" && bus.targetFace === "up",
  );
  furnacesOutput = production.buses.filter(
    (bus) => bus.target.id === "minecraft:furnace" && bus.targetFace === "down",
  );
  lamps = production.buses
    .filter((bus) => bus.target.id === "minecraft:redstone_lamp")
    .sort((a, b) => a.target.x - b.target.x);
  machines = production.buses.filter((bus) => bus.channels.includes("appflux:flux"));
  tanks = production.buses.filter((bus) => bus.channels.includes("appmek:chemical"));
}

production.onChange(init);
init();

registerProcessingPattern(
  [{
    orderNetwork: "west",
    inputs: [item("minecraft:iron_ore", 1)],
    outputs: [item("minecraft:iron_ingot", 1)],
  }],
  function* (order) {
    yield order.input.pushExactlyInto(furnacesInput[0]);
  },
);

go(function* () {
  while (true) {
    for (const bus of furnacesOutput) {
      const output = bus.extract();
      if (output.length > 0) {
        log(`${output[0].amount} ${output[0].id} returned!`);
        output.to(main).now();
        for (const lamp of lamps) {
          lamp.redstone(15);
          yield sleep(2);
          lamp.redstone(0);
        }
      }
    }
    machines.forEach((machine) =>
      main.extract("appflux:flux", { type: "FE" }).to(machine).now(),
    );
    tanks.forEach((tank) =>
      main.extract("appmek:chemical", { id: "mekanism:chlorine" }).to(tank).now(),
    );
    yield sleep(20);
  }
});
