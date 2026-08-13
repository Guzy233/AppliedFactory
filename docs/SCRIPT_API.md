# Applied Factory 脚本 API

> 状态：破坏性重构后的 MVP 契约。旧 `ctx` API、Rhino continuation、任务调用栈持久化和玩家安装的缓存元件均不再兼容。

> API 类型与签名以 [factory-controller.d.ts](../script-api/factory-controller.d.ts) 为准，本文只描述运行模型与行为语义。

## 1. 运行模型

控制器脚本使用 Rhino ES6 编译模式。脚本在控制器加载时求值一次，用全局函数取得句柄、注册 processing pattern，并启动 generator workflow。

```js
const production = network("south");

registerProcessingPattern(
    [{
        orderNetwork: "north",
        inputs: [item("minecraft:iron_ore", 1)],
        outputs: [item("minecraft:iron_ingot", 1)]
    }],
    function* (order) {
        const furnace = production.buses.find(bus =>
            bus.target.id === "minecraft:furnace"
        );

        yield order.input.pushExactlyInto(furnace);
        yield sleep(200);
        yield furnace.extract().to(order.network);
    }
);
```

`yield` 是 JavaScript generator 的普通语义，不是 Rhino continuation。Java 调度器反复调用 generator 的 `next(result)`：

- generator 产出 `Action` 时，任务等待该 Action；
- Action 成功后，其结果通过下一次 `next(result)` 返回给脚本；
- `done === true` 时任务结束；
- generator 抛出异常时任务失败。

generator 只存在于当前 JVM 内存中，不序列化。服务器重启或脚本重载会终止正在运行的 workflow。processing job 的剩余托管资源进入恢复流程；被动 workflow 从入口重新启动。

## 2. 全局 API

全局函数签名与类型见 [factory-controller.d.ts](../script-api/factory-controller.d.ts)，不在此重复。

`go(function* () { ... })` 注册一条被动 workflow。顶层脚本本身不是 generator，因此不能在顶层直接写 `yield`。

```js
go(function* () {
    while (true) {
        const coal = network("east").extract(item("minecraft:coal", -1));
        const remaining = coal === null ? null : coal.to(bus).now();
        yield sleep(1);
    }
});
```

## 3. 句柄

`Network`、`Bus`、存储端点和资源来源都只持有稳定地址，不持有 `BlockEntity`、AE grid、capability 或 `MEStorage` 实例。每次查询和 Action 执行时重新解析地址。

Bus 句柄只绑定总线地址，不保存目标机器身份：

- 原位替换机器后，同一个 Bus 句柄会操作总线当前面对的新机器；
- 暂时离线、区块未加载或当前目标没有对应存储：Action 等待；
- 资源不足或目标已满：Action 等待；
- 总线本身被拆除：句柄无法解析，Action 等待。

若脚本缓存依赖目标机器类型，玩家可以重新保存脚本，或在 `network.onChange` 回调中重新枚举 `network.buses`。MVP 不提供机器身份追踪、自动重新选择目标或机器池。

`network.buses` 每次读取均返回当前拓扑快照。`onChange` 注册同步、不可挂起的拓扑变化回调；回调中可以重建脚本保存的句柄数组，但不能 `yield`。

## 4. Resource

Resource 是统一、不可变的精确来源句柄 `(origin, channel, key, amount)`。`channel` 直接使用 `AEKeyType#getId()` 的注册表 ID；`key` 是对应 `AEKeyType` codec 自己的 NBT。内置物品和流体 channel 分别为 `"ae2:i"`、`"ae2:f"`，扩展 channel 不需要声明新 Resource 类型或编写 Java 适配器。Resource 不会在创建时提取或锁定普通端点库存。

无参数 `extract()` 返回来源当前每个精确 AEKey 的 `ResourceArray`。它是真正的只读 JavaScript 数组，同时可以为整个快照创建一个批量转移动作：

```js
const products = furnace.extract();
yield products.to(network("north"));
```

仍可使用索引、`find()`、`filter()` 和 `for...of`。`order.input` 同样是 `ResourceArray`，所以可以直接写 `yield order.input.pushExactlyInto(bus)`。

`extract(spec)` 返回一个精确 Resource。固定正数量的 spec 即使来源暂时缺货也会生成句柄，后续 Action 可以等待；`-1` 会在调用时固化为当前可用量，数量为零时返回 `null`。

```js
const iron = network("north").extract(item("minecraft:iron_ingot", 8));
const allCoal = network("north").extract(item("minecraft:coal", -1));
```

句柄创建后资源仍在来源中。如果执行前被其他设备消耗，可等待 Action 会等待同一 AEKey 重新满足数量。AEKey 对应的资源是同质的，句柄不追踪某一个槽位或实体。

同一个 Resource 可以创建多个 Action，但它不代表对普通端点的独占所有权。多个 Action 竞争相同来源时由服务器主线程执行顺序决定，后执行者在库存不足时等待。

## 5. 隐形订单托管

AE2 把 processing pattern 输入交给控制器时，控制器将实际资源写入内部托管区。玩家不需要安装存储元件，托管区也不作为普通 AE 存储对外暴露。

每笔订单拥有独立分配：

```text
OrderEscrowOrigin(orderId)
└── bundle：该订单尚未消费的输入
```

`order.input` 是指向该分配的 `ResourceArray`，因此不会被其他订单或外部设备消费，并可作为一个批次转移。

物理数据可以聚合存放，但账本必须按 `orderId` 隔离。不得只维护一个全局 `AEKey → amount` 池，否则相同输入的订单会互相消费。

隐形托管必须有背压。MVP 可以先限制控制器活动 processing job 数；达到限制时拒绝新的 pattern push，让 AE2 稍后重试。

## 6. Action 和等待条件

Action 持有执行状态，Resource 不持有进度。

```text
TransferAction
├── source
├── target
├── remaining
└── mode：PARTIAL | EXACT
```

同一个 Action 在 `.now()` 或调度重试后会更新自己的 `remaining`；再次执行这个 Action 只处理剩余数量。

每次尝试都重新解析 source 和 target，并查询：

```text
来源当前库存
&& 目标当前容量
```

### 6.1 `to(target)`

`to` 每次转移当前能够转移的部分：

```text
min(remaining, source.available, target.capacity)
```

- 来源无资源时等待；
- 目标无容量时等待；
- 每次允许产生部分进度；
- `remaining` 归零时成功。

```js
yield resource.to(target);
```

### 6.2 `pushExactlyInto(target)`

精确转移只有在来源拥有完整 `remaining`，并且目标能够一次接收完整 `remaining` 时才执行。任一条件不满足都不移动资源并继续等待。

```js
yield resource.pushExactlyInto(target);
```

### 6.3 `.now()`

JavaScript 无法让 `to()` 知道它的返回值之后是否被外层 `yield`，因此非阻塞操作显式使用 `.now()`：

```js
const remaining = resource.to(target).now();
const success = resource.pushExactlyInto(target).now();
```

- `to(...).now()` 只尝试一次，返回尚未转移的 Resource；全部移走时返回 `null`；
- `ResourceArray.to(...).now()` 返回仍未转移的 `ResourceArray`，全部移走时返回 `null`；
- `pushExactlyInto(...).now()` 只尝试一次，返回 boolean；
- `.now()` 不进入调度器，不跨 tick 等待。

## 7. Codec key、NBT 与物品定位

每个 Resource 都暴露统一的 `channel` 和 `key`。`key` 可原样传回 `stack()`，由对应 channel 的 codec 解码：

```js
const snapshot = network("north").extract();
const sword = snapshot.find(resource =>
    resource.channel === "ae2:i" &&
    resource.id === "minecraft:diamond_sword"
);

if (sword !== undefined) {
    const nbt = itemNbt(sword);
    const damage = nbt.components["minecraft:damage"];
    const exactSword = network("north").extract(
        stack(sword.channel, sword.key, 1)
    );
}
```

`stack(channel, key, amount)` 不解释 key 内部字段，而是查找已注册的 `AEKeyType` 并调用其 codec。例如物品 key 为 `{id: "minecraft:stone"}`，Applied Flux 能源则可表示为 `stack("appflux:flux", {type: "FE"}, 10000)`。这使未来扩展 channel 无需修改控制器代码。

`itemNbt(resource)` 返回完整 ItemStack 保存 NBT `{id, count, components}`；参数不是 `ae2:i` 时抛出运行时错误。`item(id, amount, components?)` 只是创建物品 spec 的便利函数，其可选参数是 1.21 data component patch。

`BlockView.nbt` 是方块实体自身的 NBT 快照，普通方块和空气为 `null`；`blockEntityType` 同样在没有方块实体时为 `null`：

```js
const target = bus.target;
if (target.blockEntityType === "minecraft:chest") {
    const items = target.nbt.Items;
}
```

NBT 只读值会转换为 JavaScript 对象、数组、字符串和数字。超过 JavaScript 安全整数范围的 long 会转换为字符串。快照完全脱离世界状态，修改脚本对象不会写回物品或方块；为避免异常大的第三方 NBT 阻塞控制器，单次转换限制为 24 层和 4096 个节点。

## 8. 改名与总线世界交互

`rename(resource, name)`、`bus.drop(resource)`、`use()`、`place()` 和 `breakBlock()` 都是 workflow 内的一次性同步操作，不进入调度器，也不应 `yield`。每次调用只尝试一次；目标不可用或来源不足时，`use`/`place` 返回 `false`，`breakBlock` 返回 `null`。所有物品专属函数在运行时验证参数的实际 key 是 `AEItemKey`。

```js
go(function* () {
    const storage = network("north");
    const bus = storage.buses[0];

    // 改名立即在原来源中以新 key 替换旧 key；不足时返回 null。
    const pickaxe = storage.extract(item("minecraft:diamond_pickaxe", 1));
    if (pickaxe === null) return;
    const named = rename(pickaxe, "Factory Pickaxe");
    if (named === null) return;

    // 使用一个物品：先尝试右键目标方块，再回退到物品的空中使用。
    // 使用后的剩余物或变换物（例如空桶）直接写回 named.origin。
    const used = bus.use(named);

    // place 运行时要求 amount=1 且 key 是 BlockItem。
    const stone = storage.extract(item("minecraft:stone", 1));
    const placed = stone !== null && bus.place(stone);

    // 工具只是来源句柄，不会转移到控制器。耐久和掉落都写回工具来源。
    const tool = storage.extract(item("minecraft:diamond_pickaxe", 1));
    if (tool === null) return;
    const drops = bus.breakBlock(tool);
    // 非 null 时，drops 是已经存在于 storage 中的 ResourceArray。

    // drop 精确扣除资源，并沿总线朝向生成物品实体。
    const cobble = storage.extract(item("minecraft:cobblestone", 16));
    const dropped = cobble !== null && bus.drop(cobble);

    // 不传物品时保持空手使用方块的语义。
    const activated = bus.use();
});
```

所有持物操作都直接修改句柄的 `origin`：执行时精确取出旧 key，完成 Minecraft 交互后把剩余物、新 key、受损工具及掉落重新插入同一来源。工具不需要预先转移或由 workflow 持有。若第三方存储在取出后拒绝写回，无法回写的结果进入 recovery escrow 并使 workflow 失败，避免删除或复制。

`breakBlock` 失败时返回 `null`；成功时返回本次掉落对应的 `ResourceArray`，即使没有掉落也返回空数组。这些只是已写回工具来源的句柄。工具损坏后不再返回工具对象，脚本需要时可重新读取来源。无法正确采掘的方块仍会被破坏，但成功结果为空数组，行为与生存模式玩家规则一致。

## 9. 原子性与恢复

所有资源操作都在服务器线程执行，仍然需要处理第三方存储模拟结果与实际执行不一致的问题。

- EXACT Action 先模拟来源和目标，再执行完整转移；
- 目标实际拒绝时优先回滚到来源；
- 无法完整回滚时写入控制器内部 recovery escrow，禁止删除或复制资源；
- PARTIAL Action 只从进度中扣除实际成功进入目标的数量。

订单托管区和 recovery escrow 是内部正确性机制，不是玩家容量玩法。

## 10. Java 桥接边界

脚本 API 由专门的 Java facade 类实现。桥接类中的自声明 public 方法默认全部暴露给 JS：

```java
@JsBridge
public final class JsResource {
    private final ResourceRef handle;

    public JsTransferAction to(JsEndpoint target) { ... }
    public JsTransferAction pushExactlyInto(JsEndpoint target) { ... }
}
```

绑定器只扫描 `getDeclaredMethods()`，忽略继承方法、synthetic/bridge 方法和未标记类。普通 Minecraft、AE2 与业务层 Java 对象不得直接包装到 JS。

桥接方法只接受或返回：

- JS facade；
- 字符串、boolean 和安全范围内数字；
- 显式转换的数组或只读数据；
- Action；
- `null` / `undefined`。

核心资源和调度类型不依赖 Rhino；facade 只持有核心对象或句柄。

## 11. 生命周期与失败语义

- 脚本保存或重载：先创建新 runtime；成功后终止旧 generator，其托管资源进入恢复流程；
- 服务器重启：不恢复 generator 调用点；
- processing generator 丢失：订单进入失败/恢复状态，玩家可以取消并重新下单；
- passive generator 丢失：控制器加载后从 `go` 的入口重新创建；
- 总线暂时无法解析或目标机器不提供所需存储：Action 保持等待；
- 单纯缺少来源资源或目标容量：保持等待，并向 UI 暴露缺少的资源或目标满状态。

隐形托管资源的数据量很小，与控制器 NBT 一起保存。
