const production = network("east");
const main = network("west");
let furnaces = [];

function refreshFurnaces() {
  furnaces = production.buses.filter(
    (bus) =>
      bus.target !== null &&
      bus.target.id === "minecraft:furnace" &&
      bus.targetFace === "up",
  );
}

production.onChange(refreshFurnaces);
refreshFurnaces();

registerProcessingPattern(
  [
    {
      id: "iron",
      orderNetwork: "west",
      inputs: [item("minecraft:iron_ore", 1)],
      outputs: [item("minecraft:iron_ingot", 1)],
    },
  ],
  function* (order) {
    // EXACT waits for all source resources and all destination capacity.
    yield order.input.pushExactlyInto(furnaces[0]);
  },
);

go(function* () {
  while (true) {
    // Non-blocking variants are explicit.
    // const remaining = source.extract(item("minecraft:coal", -1)).to(target).now();
    for (var bus of production.buses) {
      bus.extract().to(main).now();
    }
    yield sleep(20);
  }
});
