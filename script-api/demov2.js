const production = network("east");
const main = network("west");
let furnaces_input = [];

let furnaces_output = [];

let lamps = [];

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
    // EXACT waits for all source resources and all destination capacity.
    yield order.input.pushExactlyInto(furnaces_input[0]);
  },
);

go(function* () {
  while (true) {
    // Non-blocking variants are explicit.
    // const remaining = source.extract(item("minecraft:coal", -1)).to(target).now();
    for (var bus of furnaces_output) {
      var output = bus.extract();
      if (output.length > 0) {
        output.to(main).now();
        log("something returned!");
        for (var lamp of lamps) {
          lamp.redstone(15);
          yield sleep(2);
          lamp.redstone(0);
        }
      }
    }
    yield sleep(30);
  }
});
