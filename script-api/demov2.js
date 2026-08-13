const production = network("east");
const main = network("west");
const DIRECTIONS = ["up", "down", "north", "south", "west", "east"];
let furnaces_input = [];

let furnaces_output = [];

let lamps = [];

const fluxSpec = stack("appflux:flux", { type: "FE" }, -1);

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
        output.to(main).now();
        log("something returned!");
        for (var lamp of lamps) {
          lamp.redstone(15);
          yield sleep(2);
          lamp.redstone(0);
        }
      }
    }
    machines.forEach((machine) => main.extract(fluxSpec).to(machine).now());
    tanks.forEach((tank) =>
      main
        .extract(stack("appmek:chemical", { id: "mekanism:chlorine" }, -1))
        .to(tank)
        .now(),
    );
    yield sleep(20);
  }
});
