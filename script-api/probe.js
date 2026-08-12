// @ts-check
/// <reference path="./factory-controller.d.ts" />

// 探测脚本：把这段代码作为控制器程序保存，运行一次后从日志读取结果。
// 它会枚举主网/子网里所有 AE 通道及资源，并列出所有 bus 的目标方块与可用通道，
// 用来确认 demo2 需要的 key（化学品/能源/物品）和机器方块 id。

// ---- 按你的实际布局修改这两个面 ----
const ORDER_NETWORK = "east";       // 主网：资源所在
const PRODUCTION_NETWORK = "west";  // 子网：机器所在
// ------------------------------------

function dumpNetwork(ctx, label, side) {
  const network = ctx.network(side);
  ctx.log(`=== network ${side} (${label}) online=${network.online()} ===`);
  if (!network.online()) {
    return;
  }
  const channels = network.channels();
  ctx.log(`channels: ${channels.join(", ")}`);
  for (var channel of channels) {
    var resources = network.storage(channel).read();
    ctx.log(`channel ${channel}: ${resources.length} resource(s)`);
    for (var r of resources) {
      // 物品直接 item()/stack("item", ...)；化学品用 stack("appmek:chemical", id, n)；
      // 其他通道（能源 appflux:flux 等）用 stackTag(keyTag, n)。
      if (channel !== "ae2:i") {
        ctx.log(`  ${channel} ${r.id} x${r.amount}  keyTag=${r.keyTag()}`);
      } else {
        ctx.log(`  ${channel} ${r.id} x${r.amount}`);
      }
    }
  }
}

function dumpBuses(ctx) {
  ctx.log(`=== buses (${ctx.buses.length}) ===`);
  for (var bus of ctx.buses) {
    var target = bus.target();
    var id = target === null ? "(none)" : target.id;
    var channels = bus.channels().join(",");
    ctx.log(`bus ${bus.address.key} -> ${id} face=${bus.targetFace} channels=[${channels}]`);
  }
}

registerPassive(function probe(ctx) {
  ctx.log("==== probe start ====");
  dumpNetwork(ctx, "order", ORDER_NETWORK);
  dumpNetwork(ctx, "production", PRODUCTION_NETWORK);
  dumpBuses(ctx);
  ctx.log("==== probe done ====");
});
