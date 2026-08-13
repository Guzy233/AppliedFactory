# Applied Factory

Applied Factory 使用 JavaScript generator 编排 Applied Energistics 2 网络和工厂总线。

当前版本正在进行破坏性 MVP 重构，公开契约见 [脚本 API](docs/SCRIPT_API.md)。旧版 `ctx`、Rhino continuation、任务调用栈序列化和玩家安装的控制器缓存元件不再兼容。

目标脚本形式：

```js
const production = network("south");

registerProcessingPattern(patterns, function* (order) {
    const furnace = production.buses.find(bus =>
        bus.target.id === "minecraft:furnace"
    );

    yield order.input.pushExactlyInto(furnace);
    yield sleep(200);
    yield furnace.extract().to(order.network);
});

go(function* () {
    while (true) {
        // 被动产线。
        yield sleep(20);
    }
});
```

核心模型是不可变的 `Resource(origin, bundle)`。普通资源在创建 Resource 时仍留在机器或网络中；Action 在每次重试时同时等待来源库存和目标容量。AE processing 输入则进入按订单隔离的控制器隐形托管区，不需要玩家提供存储元件。

Bus 句柄只绑定总线，不保存目标机器身份。原位替换机器后同一句柄会解析并操作新机器；依赖机器类型的脚本缓存由玩家重新保存脚本，或通过 `network.onChange` 自行刷新。

服务器重启时不恢复 generator 调用点。processing 托管资源进入恢复流程，被动 workflow 从入口重新开始。

## Development

项目目标为 Minecraft 1.21.1、NeoForge、AE2 19.2.17 和 JDK 21。Rhino 以 ES6 编译模式运行。

仓库中的 `script-api` 目录提供脚本示例与 IDE 参考。
