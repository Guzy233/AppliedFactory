// AppliedFactory demo2：Mekanism 钢粉产线 + Applied Flux 能源注入。
//
// 主动流程（AE 下单）：
//   富集仓       煤 x1               -> 富集碳 x1   （mekanism:enriching）
//   化学氧化机   富集碳 x1           -> 碳 x80      （mekanism:oxidizing）
//   冶金灌注机   铁锭 x1 + 碳 x10    -> 富集铁 x1   （mekanism:metallurgic_infusing）
//   冶金灌注机   富集铁 x1 + 碳 x10  -> 钢粉 x1     （mekanism:metallurgic_infusing）
// 被动流程：每 tick 从主网拉取 FE 能源，注入所有 bus 面对的机器。
//
// 控制器私有缓存需要安装相应存储元件：物品元件、AppMek 化学品元件、Applied Flux 能源元件。

// ---- 网络布局（按实际修改）----
const ORDER_NETWORK = "east"; // 主网：资源所在 / AE 下单
const PRODUCTION_NETWORK = "west"; // 子网：机器所在
// --------------------------------

// 机器方块 id
const ENRICHER = "mekanism:ultimate_enriching_factory"; // 富集仓
const OXIDIZER = "mekanism:chemical_oxidizer"; // 化学氧化机
const INFUSER = "mekanism:ultimate_infusing_factory"; // 冶金灌注机

// 化学品碳：stack() 走 AE2 通用 {id} 反解（零代码）。
// 若该通道无法由 id 构造，改用：stackTag('{"#t":"appmek:chemical",id:"mekanism:carbon"}', amount)
function carbon(amount) {
  return stack("appmek:chemical", "mekanism:carbon", amount);
}

// Applied Flux 能源 key：其 codec 字段是 "type" 而非 "id"，用探测得到的序列化 tag 构造。
const ENERGY_TAG = '{"#t":"appflux:flux",type:"FE"}';
const ENERGY_CHUNK = 20000;

// 由 initializer 填充的机器引用
let machines = { enricher: null, oxidizer: null, infuser: null };
let energyBuses = [];

initialize({
  networks: [PRODUCTION_NETWORK],
  handler: function (ctx) {
    machines = { enricher: null, oxidizer: null, infuser: null };
    energyBuses = [];
    for (var bus of ctx.buses) {
      var target = bus.target();
      if (target === null) {
        continue;
      }
      if (bus.storage("appflux:flux") !== null) {
        energyBuses.push(bus);
      }
      if (bus.storage("ae2:i") !== null) {
        if (target.id === ENRICHER && machines.enricher === null)
          machines.enricher = bus;
        else if (target.id === OXIDIZER && machines.oxidizer === null)
          machines.oxidizer = bus;
        else if (target.id === INFUSER && machines.infuser === null)
          machines.infuser = bus;
      }
    }
  },
});

/**
 * 循环提取目标面的物品，直到拿到期望产物。目标面物品进/出同面时，提取会把未消耗
 * 的输入也带回来，这里按期望产物 id 过滤，其余重新塞回。返回期望产物（owned）。
 */
function collectOutput(ctx, items, expectedId) {
  while (true) {
    ctx.sleep(10);
    var extracted = items.extract();
    if (extracted.length === 0) {
      continue;
    }
    var product = [];
    var rest = [];
    for (var r of extracted) {
      if (r.id === expectedId) product.push(r);
      else rest.push(r);
    }
    if (rest.length !== 0) items.pushTillFull(rest);
    if (product.length !== 0) return product;
  }
}

// 富集仓：煤 -> 富集碳（物品）
function enrich(ctx) {
  var bus = machines.enricher;
  var items = bus === null ? null : bus.items();
  if (items === null) ctx.fail("No enriching factory bus");
  items.push(ctx.inputs);
  var product = collectOutput(ctx, items, "mekanism:enriched_carbon");
  ctx.orderNetwork.items().push(product);
}

// 化学氧化机：富集碳（物品） -> 碳（化学品 x80）
function oxidize(ctx) {
  var bus = machines.oxidizer;
  var items = bus === null ? null : bus.items();
  var chem = bus === null ? null : bus.storage("appmek:chemical");
  if (items === null || chem === null) ctx.fail("No chemical oxidizer bus");
  items.push(ctx.inputs);
}

// 冶金灌注机：铁锭/富集铁（物品） + 碳（化学品 x10） -> 富集铁/钢粉（物品）
function infuse(ctx) {
  var bus = machines.infuser;
  var items = bus === null ? null : bus.items();
  var chem = bus === null ? null : bus.storage("appmek:chemical");
  if (items === null || chem === null) ctx.fail("No infusing factory bus");

  var itemInputs = [];
  var chemInputs = [];
  for (var r of ctx.inputs) {
    if (r.id === "mekanism:carbon") chemInputs.push(r);
    else itemInputs.push(r);
  }
  if (itemInputs.length !== 0) items.push(itemInputs);
  if (chemInputs.length !== 0) chem.push(chemInputs);

  var product = collectOutput(ctx, items, ctx.outputs[0].id);
  ctx.orderNetwork.items().push(product);
}

registerPatterns({
  orderNetwork: ORDER_NETWORK,
  patterns: [
    {
      id: "enrich_carbon",
      inputs: [item("minecraft:coal", 1)],
      outputs: [item("mekanism:enriched_carbon", 1)],
      handler: enrich,
    },
    {
      id: "oxidize_carbon",
      inputs: [item("mekanism:enriched_carbon", 1)],
      outputs: [carbon(80)],
      handler: oxidize,
    },
    {
      id: "infuse_iron",
      inputs: [item("minecraft:iron_ingot", 1), carbon(10)],
      outputs: [item("mekanism:enriched_iron", 1)],
      handler: infuse,
    },
    {
      id: "infuse_steel",
      inputs: [item("mekanism:enriched_iron", 1), carbon(10)],
      outputs: [item("mekanism:dust_steel", 1)],
      handler: infuse,
    },
  ],
});

// 被动：每刻从主网拉取能源，注入所有 bus 面对的机器。
registerPassive(function feedEnergy(ctx) {
  var main = ctx.network(ORDER_NETWORK);
  while (true) {
    if (main.online() && energyBuses.length !== 0) {
      for (var i = 0; i < energyBuses.length; i++) {
        var sink = energyBuses[i].storage("appflux:flux");
        if (sink === null) {
          continue;
        }
        var request = stackTag(ENERGY_TAG, ENERGY_CHUNK);
        if (!sink.canPush(request)) continue;
        var pulled = main.storage("appflux:flux").extract(request);
        if (pulled.length !== 0) sink.push(pulled);
      }
    }
    ctx.sleep(1);
  }
});

registerPassive(function feedEnergy(ctx) {
  var main = ctx.network(ORDER_NETWORK);
  while (true) {
    if (main.online() && energyBuses.length !== 0) {
      for (var i = 0; i < energyBuses.length; i++) {
        var sink = energyBuses[i].storage("appflux:flux");
        if (sink === null) {
          continue;
        }
        var request = stackTag(ENERGY_TAG, ENERGY_CHUNK);
        if (!sink.canPush(request)) continue;
        var pulled = main.storage("appflux:flux").extract(request);
        if (pulled.length !== 0) sink.push(pulled);
      }
    }
    ctx.sleep(1);
  }
});
