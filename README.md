# Applied Factory

Scriptable, durable automation for Applied Energistics 2 networks.

Write JavaScript workflows that suspend across ticks, survive server restarts, and coordinate ME storage, Factory Buses, and AE2 crafting.

The scripting API is implemented around physical factory buses rather than Java-side logical machines. Its public contract is:

- [Script API design](docs/SCRIPT_API.md)

The data-model document is intentionally not updated by every API experiment;
the script API document is authoritative for the current public surface.

## Target scripting model

Scripts enumerate buses and use ordinary JavaScript to select and combine
them:

```js
const production = ctx.network("south");
const furnaceInputs = production.buses.filter(bus => {
    const target = bus.target();
    return target !== null
        && target.id === "minecraft:furnace"
        && bus.targetFace === "up"
        && bus.items() !== null;
});
```

A Bus is identified by its dimension, multipart host position, and part side.
Different faces pointing at the same block share `bus.targetAddress.key`, so
scripts can implement multi-face machine grouping without a built-in Machine
type.

The first-stage item I/O API is deliberately small:

```js
const items = bus.items();

items.push(ctx.inputs);        // suspends, retries each tick, exact all-or-nothing
items.pushTillFull(ctx.inputs); // suspends, fills whatever fits each tick until done
const ok = items.canPush(ctx.inputs); // synchronous one-shot capacity query
const pulled = items.extract();       // everything currently extractable
```

`push` and `pushTillFull` suspend the workflow and retry once per server tick
until they complete, like an AE crafting order waiting on the input. There are
no partial-transfer result objects; `canPush` is the non-blocking way to ask
whether an exact full push can happen right now.

Ordering and execution networks are separate. Registrations state which
controller side advertises the pattern, while handlers explicitly select any
network used for production:

```js
registerPatterns({
    orderNetwork: "north",
    patterns: [{
        id: "iron",
        inputs: [item("minecraft:iron_ore", 1)],
        outputs: [item("minecraft:iron_ingot", 1)],
        handler: smelt
    }]
});

function smelt(ctx) {
    const production = ctx.network("south");

    // Select production.buses, push inputs, and extract machine output.
    // ctx.orderNetwork is the north-side network that submitted this job.
}
```

The program can also register one common handler for controller pattern slots
and topology initialization can listen only to the networks it actually uses:

```js
initialize({
    networks: ["south"],
    handler(ctx) {
        // Changes on an unrelated north-side main network do not rerun this.
    }
});
```

Passive lines are registered as long-running functions. Each registration has
one continuation and controls its own rhythm with `sleep`; there are no
`interval` or `concurrency` options:

```js
registerPassive(function productionLine(ctx) {
    while (true) {
        // Clear, feed, and collect the line.
        ctx.sleep(20);
    }
});
```

World-changing calls suspend through a Java action boundary so Rhino can later
resume at the call site, but one API call still makes only one attempt at the
selected address.

Owned resources are physically escrowed in AE storage cells installed in the
controller. Losing a JavaScript variable does not delete an item: `ctx.owned`
reconstructs views from the persistent workflow ledger. A controller without a
compatible cell refuses processing input and extracts nothing.

MFM patterns participate in AE's ordinary crafting plan, which already charges
a fixed byte cost per pattern execution. Script source and continuation memory
are not measured, and passive workflows do not occupy AE crafting CPUs.

## Development

Use JDK 21. The current development target is Minecraft 1.21.1 on NeoForge.
AE2 19.2.17 is declared as a compile-time API dependency and a development
runtime dependency in `build.gradle`.

This project intentionally does not depend on Super Factory Manager or KubeJS.
Rhino is embedded as the script engine, while the script API is isolated behind
`ScriptRuntime` so the JavaScript surface remains independent from AE2
internals.

## Script IDE starter

[`script-api`](script-api) contains an IDE-only API declaration, readable JavaScript
reference, and a minimal TypeScript starter. Run `npx tsc -p script-api` to compile
`controller.ts` to `script-api/generated/controller.js`, then paste that JavaScript into
the controller. TypeScript syntax is stripped before Rhino sees the program.
