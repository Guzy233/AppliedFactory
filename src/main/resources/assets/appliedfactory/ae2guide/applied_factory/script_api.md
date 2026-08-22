---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: Script API
  icon: appliedfactory:factory_controller
---

# Applied Factory Script API

The complete TypeScript signatures are available in `applied_factory.d.ts` inside the exported workspace.

## Quick example

```ts
const production = network("front");

registerProcessingPattern(
  [{
    orderNetwork: "back",
    inputs: [item("minecraft:iron_ore", 1)],
    outputs: [item("minecraft:iron_ingot", 1)]
  }],
  function* (order) {
    const furnace = production.buses.find(bus =>
      bus.target.id === "minecraft:furnace"
    );
    if (furnace === undefined) return;

    yield order.input.pushExactlyInto(furnace);
    yield sleep(200);
    yield furnace.extract().to(order.network);
  }
);
```

## Networks and buses

Use `network(side)` to access an AE network connected to the controller. A side may be an absolute world direction (`north`, `south`, `east`, `west`, `up`, `down`) or a direction relative to the controller front (`front`, `back`, `left`, `right`).

`network.buses` lists the Factory Buses currently found on that network. Each bus exposes its target block, supported resource channels, inventory queries and world interaction functions.

```ts
const machines = network("front");
const furnaces = machines.buses.filter(bus =>
  bus.target.id === "minecraft:furnace"
);
```

Use `network.onChange(callback)` to rebuild cached bus lists after the network topology changes. Use `isSameNetwork()` when comparing controller sides.

## Resources

`extract(channel?, key?, amount?)` returns a `ResourceArray`. With no arguments it returns every currently extractable resource. Add a channel, key or amount to narrow the query.

```ts
const allItems = network("back").extract("ae2:i");
const coal = network("back").extract(
  "ae2:i", { id: "minecraft:coal" }, 8
);
```

`storage(channel?)` shows the complete target inventory. On a bus this may include machine slots that cannot be extracted from the bus face.

Resource channels are AEKeyType IDs. Items use `ae2:i`, fluids use `ae2:f`, and addons may provide additional channels.

## Transfers and actions

`resource.to(target)` moves whatever amount is currently possible and keeps waiting for the remainder when yielded.

`resource.pushExactlyInto(target)` moves nothing until the complete amount fits. Calling it on a `ResourceArray` treats the entire array as one atomic batch.

```ts
yield resources.to(network("back"));
yield order.input.pushExactlyInto(machine);
```

Use `.now()` for a single immediate attempt instead of waiting:

```ts
const remaining = resources.to(target).now();
const inserted = resources.pushExactlyInto(target).now();
```

`yield sleep(ticks)` pauses a generator workflow.

## Processing patterns

`registerProcessingPattern(patterns, handler)` registers processing patterns with AE2. Each definition selects the ordering network and declares its inputs and outputs. The handler receives the order inputs and the network that placed the order.

```ts
registerProcessingPattern(
  [{
    orderNetwork: "back",
    inputs: [item("minecraft:sand", 1)],
    outputs: [item("minecraft:glass", 1)]
  }],
  function* (order) {
    yield order.input.pushExactlyInto(furnace);
  }
);
```

Use `go(function* () { ... })` for passive workflows such as collecting machine outputs or supplying energy.

## Bus interactions

Factory Buses can use items, place blocks, break blocks, drop items and read or emit redstone:

```ts
bus.use(true);
bus.place(block, false);
const drops = bus.break(tool);
bus.drop(itemResource);
const inputSignal = bus.redstone();
bus.redstone(15);
```

These functions attempt the operation immediately and return whether it succeeded. Item results and tool damage are returned to the original resource source.

## Item and NBT helpers

- `item(id, amount, components?)` creates an item specification.
- `stack(channel, key, amount)` creates a specification for any registered resource channel.
- `itemNbt(resource)` reads the complete saved ItemStack NBT.
- `rename(resource, name)` renames an item in its current source.
- `bus.target` provides the target block state, coordinates, block entity type and read-only NBT.

## Exported recipes

`require_recipes(filter)` selects recipes from the workspace export and embeds them into the uploaded script. Filters support `id`, `type`, `machine`, `input` and `output`.

```ts
const iron = require_recipes({
  machine: "minecraft:furnace",
  output: "minecraft:iron_ingot"
});
```

Run `/appliedfactory export` after recipes in the modpack change.

## JSON imports

Scripts may use relative default JSON imports:

```ts
import settings from "./settings.json";
```

Paths are resolved from the selected TypeScript file and must remain inside `appliedscripts/`. TypeScript and JavaScript module imports are not supported.

## Troubleshooting

- No buses: verify that the Factory Buses and the selected controller side share an AE network.
- `storage()` has entries but `extract()` is empty: the machine face does not allow those entries to be extracted.
- A workflow keeps waiting: check source amounts, target capacity and whether the bus still exists.
- Recipe export is missing: run `/appliedfactory export` or `/appliedfactory setupworkspace`.
