/// <reference path="./factory-controller.d.ts" />

const production = network("south");
let furnaces: readonly Bus[] = [];

function refreshFurnaces(): void {
    furnaces = production.buses.filter(bus =>
        bus.target !== null && bus.target.id === "minecraft:furnace"
    );
}

production.onChange(refreshFurnaces);
refreshFurnaces();

registerProcessingPattern(
    [{
        id: "iron",
        orderNetwork: "north",
        inputs: [item("minecraft:iron_ore", 1)],
        outputs: [item("minecraft:iron_ingot", 1)]
    }],
    function* (order: Order): Generator<Action, void, unknown> {
        const furnace = furnaces[0];
        if (furnace === undefined) {
            yield sleep(20);
            return;
        }
        yield order.input.pushExactlyInto(furnace);
        yield sleep(200);
        yield furnace.extract().to(order.network);
    }
);
