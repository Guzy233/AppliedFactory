package com.fulent.appliedfactory.script;

/** Limits and persistent field names for the source owned by one controller. */
public final class ControllerProgram {
    public static final int MAX_SOURCE_LENGTH = 32_768;
    public static final String NBT_KEY = "ControllerProgram";
    public static final String DEFAULT_SOURCE = """
            let furnaceLines = [];

            initialize({
              networks: ["south"],
              handler: function (ctx) {
                const outputs = new Map();
                for (const bus of ctx.buses) {
                  const target = bus.target();
                  if (target !== null && target.id === "minecraft:furnace"
                      && bus.targetFace === "down" && bus.items() !== null) {
                    outputs.set(bus.targetAddress.key, bus);
                  }
                }
                furnaceLines = ctx.buses
                  .filter(bus => {
                    const target = bus.target();
                    return target !== null && target.id === "minecraft:furnace"
                      && bus.targetFace === "up" && bus.items() !== null
                      && outputs.has(bus.targetAddress.key);
                  })
                  .map(input => ({ input, output: outputs.get(input.targetAddress.key) }));
              }
            });

            registerControllerHandler({
              orderNetwork: "north",
              handler: function (ctx) {
                const line = furnaceLines[0];
                if (line === undefined) ctx.fail("No furnace line on south network");
                if (!line.input.items().push(ctx.inputs)) ctx.fail("Furnace input is full");
                while (true) {
                  ctx.sleep(5);
                  const products = line.output.items().extract();
                  if (products.length !== 0) {
                    while (!ctx.orderNetwork.items().push(products)) ctx.sleep(1);
                    return;
                  }
                }
              }
            });
            """;

    private ControllerProgram() {
    }
}
