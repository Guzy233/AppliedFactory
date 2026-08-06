/// <reference path="./factory-controller.d.ts" />

function smelt(ctx: ProcessingContext): void {
    const production: Network = ctx.network("south");
    if (!production.online()) {
        ctx.sleep(20);
        return;
    }

    // This is intentionally a typed starting point. Replace it with your production logic.
    ctx.log("Production buses: " + production.buses.length);
}

registerPatterns({
    orderNetwork: "north",
    patterns: [{
        id: "iron",
        inputs: [item("minecraft:iron_ore", 1)],
        outputs: [item("minecraft:iron_ingot", 1)],
        handler: smelt
    }]
});
