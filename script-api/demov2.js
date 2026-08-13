const production = network("east");
const main = network("west");
let furnaces_input = [];

let furnaces_output = [];

function refreshFurnaces() {
  furnaces_input = production.buses.filter(
    (bus) =>
      bus.target.id === "minecraft:furnace" &&
      bus.targetFace === "up",
  );

  furnaces_output = production.buses.filter(
    (bus) =>
      bus.target.id === "minecraft:furnace" &&
      bus.targetFace === "down",
  );
}

production.onChange(refreshFurnaces);
refreshFurnaces();

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
      bus.extract().to(main).now();
    }
    yield sleep(20);
  }
});
