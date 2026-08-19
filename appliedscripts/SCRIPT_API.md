# Applied Factory 脚本 API

> API 类型与签名以 [applied_factory.d.ts] 为准，本文只描述运行模型与行为语义。

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

## 2. 全局 API

全局函数签名与类型见 [applied_factory.d.ts]，不在此重复。


## 3. 句柄

`Network`、`Bus`、存储端点和资源来源都只持有稳定地址。每次查询和 Action 执行时重新解析地址。

Bus 句柄只绑定总线地址，不保存目标机器身份：

- 原位替换机器后，同一个 Bus 句柄会操作总线当前面对的新机器；
- 暂时离线、区块未加载或当前目标没有对应存储：Action 等待；
- 资源不足或目标已满：Action 等待；
- 总线本身被拆除：句柄无法解析，Action 等待。

若脚本缓存依赖目标机器类型，玩家可以重新上传脚本，或在 `network.onChange` 回调中重新枚举 `network.buses`。

`network.buses` 每次读取均返回当前拓扑快照。`onChange` 只在工厂总线加入或离开 AE 网络时调用，不会因目标方块的一般更新而触发。该回调同步且不可挂起；回调中可以重建脚本保存的句柄数组，但不能 `yield`。

`bus.channels` 返回目标面当前支持输入/输出的资源 channel id 数组（如 `"ae2:i"`、`"ae2:f"`），**不依赖目标是否实际含有资源**：它反映该面对每个已注册 AE channel 是否暴露了能力（如熔炉顶面能放输入、底面能取产物），通过 AE2 的策略注册表查询，覆盖任意扩展 channel，包括但不限于能源、化学品等通道。

`bus.channels` 含有某个通道意味着这个面至少有输入、输出能力中的一个，但不是所有 channels 都能同时进行输入、输出。

## 4. Resource

Resource 是统一、不可变的精确来源句柄 `(origin, channel, key, amount)`。`channel` 直接使用 `AEKeyType#getId()` 的注册表 ID；`key` 是对应 `AEKeyType` codec 自己的 NBT。内置物品和流体 channel 分别为 `"ae2:i"`、`"ae2:f"`，扩展 channel 不需要声明新 Resource 类型或编写 Java 适配器。Resource 不会在创建时提取或锁定普通端点库存。

```js
const products = furnace.extract();
yield products.to(network("north"));
```

`ResourceArray` 继承 ReadonlyArray<Resource>, 可使用只读数组方法，如索引、`find()`、`filter()` 和 `for...of`。`order.input` 同样是 `ResourceArray`，所以可以直接写 `yield order.input.pushExactlyInto(bus)`。它保留注册样板时的输入槽位顺序：重复的同类输入不会在数组中合并，可以分别通过 `order.input[0]`、`order.input[1]` 路由到不同目标；整体批量转移时仍按总量执行。

对 network 或 bus 可调用 `extract(channel?, key?, amount?)` ，三个参数都可选，恒返回 `ResourceArray`；没有满足条件的资源时返回空数组。

```js
const iron = network("north").extract("ae2:i");                                // 该 channel 全部资源
const allCoal = network("north").extract("ae2:i", { id: "minecraft:coal" });   // 尽可能多
const eight = network("north").extract("ae2:i", { id: "minecraft:iron_ingot" }, 8); // 上限 8
```

- 只传 `channel`：返回该 channel 下所有资源，每个为完整可用量；
- 传 `channel` + `key`：返回该 key 的资源，数量为当前可用量（尽可能多）；当前无货时返回空数组；
- 再传 `amount`：数量为 `min(可用量, amount)` 的上限封顶；`amount` 省略或为 `-1` 都表示尽可能多地取。
- `channel` 未注册或 `key` 无法被该 channel 的 codec 解码时抛运行时错误；
- 结果恒为数组：单个命中也是 1 元素数组；空数组的 `.to(target)` 是安全 no-op（立即完成，不移动任何资源）。

对 network 或 bus 可调用 `storage(channel?)` 是只读库存查询：返回目标方块**全部**非空槽位，不受总线所贴面的限制——它以"无面"方式查询，熔炉一次就能看到输入、燃料、输出全部三个槽，包括从任何面都取不出的输入。恒返回 `ResourceArray`，无内容时为空数组；`channel` 可选，只取某个 channel：

```js
const contents = furnace.storage();      // 全部库存（输入/燃料/输出）
const inputs = furnace.storage("ae2:i"); // 只看物品 channel
```

`extract` 只报告该面当前能取出的资源；`storage` 报告方块整体库存，用于探线时排查机器卡料或观察输入。`storage` 的结果是普通 Resource 句柄，可以继续 `.to()`，将转移可用库存，但不建议调用 `pushExactlyInto()` , 可能因为部分资源无法取出导致永远失败。

句柄创建后资源仍在来源中。如果执行前被其他设备消耗，可等待 Action 会等待同一 AEKey 重新满足数量。AEKey 对应的资源是同质的，句柄不追踪某一个槽位或实体。

同一个 Resource 可以创建多个 Action，但它不代表对普通端点的独占所有权。多个 Action 竞争相同来源时由服务器主线程执行顺序决定，后执行者在库存不足时等待。

## 5. Action

Action 是由 generator 产出的动作，将由动作控制器控制执行。

`yield action` 会在当前 tick 立刻尝试该动作，若成功直接调用 next(), 因此连续成功yield会连续执行。若失败则会开始每刻尝试一次直到成功，成功后会在当刻调用 next() 通知脚本。

`action.now()` 立刻尝试动作并返回结果，结果类型由 action 类型而定。

以下是获取 `Action` 的方式

### 5.1 `resource.to(target)`

产生可分转移动作。

`to` 每次转移当前能够转移的部分，当 `yield some_resource.to(somewhere)` 时会立刻首次尝试：

- 来源无资源时等待；
- 目标无容量时等待；
- 每次允许产生部分进度；
- `remaining` 归零时成功，无返回。

```js
some_resource.to(somewhere).now();
```
立刻尝试一次转移，返回未成功转移资源的句柄（资源仍留在来源）。

**`ResourceArray.to(target)`** —— 等价于对数组里的每个资源分别执行一次 `to`：逐个资源独立转移当前可行的部分（每次取 `min(来源可用, 目标容量)`），某个资源暂时缺货或目标放不下**不影响其他资源**移动；一次执行后 `remaining` 保留未移动的部分，再次执行（`.now()` 或调度重试）只处理剩余。数组为空时立即完成（安全 no-op）。

### 5.2 `resource.pushExactlyInto(target)`

产生不可分原子转移动作。

```js
yield some_resource.pushExactlyInto(somewhere);
```
立刻尝试一次；只有在来源拥有完整资源，并且目标能够一次接收完整资源时才执行。任一条件不满足都不移动资源并继续等待。

```js
some_resource.pushExactlyInto(somewhere).now();
```
立刻尝试完整插入，返回是否插入成功。

**`array.pushExactlyInto(target)`** —— 整个数组作为一个不可分割的批次：要求**所有资源同时**满足"来源拥有完整数量、且目标能一次接收完整数量"，任一资源不满足则整批不动并继续等待；条件满足后一次性把整个批次完整转移，不产生部分进度。数组为空时立即成功。

### 5.3 `sleep(ticks)`

该动作将在等待传入的tick数后被判定为成功并继续。

## 6. Codec key、NBT 与物品定位

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
        sword.channel, sword.key, 1
    );
}
```

`stack(channel, key, amount)` 不解释 key 内部字段，而是查找已注册的 `AEKeyType` 并调用其 codec。例如物品 key 为 `{id: "minecraft:stone"}`，Applied Flux 能源则可表示为 `stack("appflux:flux", {type: "FE"}, 10000)`。如果遇到陌生 channel 可以通过脚本探测其具体字段。

`itemNbt(resource)` 返回保存完整 ItemStack 的 NBT `{id, count, components}`；参数不是 `ae2:i` 时抛出运行时错误。`item(id, amount, components?)` 只是创建物品 spec 的便利函数，其可选参数是 1.21 data component patch。

`BlockView.nbt` 是方块实体自身的 NBT 快照，普通方块和空气为 `null`；`blockEntityType` 同样在没有方块实体时为 `null`：

```js
const target = bus.target;
if (target.blockEntityType === "minecraft:chest") {
    const items = target.nbt.Items;
}
```

NBT 只读值会转换为 JavaScript 对象、数组、字符串和数字。超过 JavaScript 安全整数范围的 long 会转换为字符串。快照完全脱离世界状态，修改脚本对象不会写回物品或方块；为避免异常大的第三方 NBT 阻塞控制器，单次转换限制为 24 层和 4096 个节点。

## 7. 世界交互与改名

`use`、`place`、`drop`、`break` 和 `redstone` 是总线与世界的一次性同步交互，`rename` 同样是一次性同步操作。目标不可用或来源不足时，`use`/`place` 返回 `false`，`break` 返回 `null`。所有物品专属函数在运行时验证参数的实际 key 是 `AEItemKey`。

**改名：**

```js
const named = rename(resource, "Factory Pickaxe");
```

改名立即在原来源中以新 key 替换旧 key；不是 `ae2:i` 时抛出运行时错误，资源不足时返回 `null`。改名后的 `named` 是新的来源句柄，可直接交给 `use`/`place`/`drop` 继续使用。

**使用、放置与采掘：**

```js
go(function* () {
    const storage = network("north");
    const bus = storage.buses[0];

    // 使用一个物品：先尝试右键目标方块，再回退到物品的空中使用。
    // 使用后的剩余物或变换物（例如空桶）直接写回 named.origin。
    const used = bus.use(named, true); // 第二个参数表示按住 Shift

    // place 运行时要求 amount=1 且 key 是 BlockItem。
    const stone = storage.extract("ae2:i", { id: "minecraft:stone" }, 1);
    const placed = stone.length > 0 && bus.place(stone[0], false);

    // 工具只是来源句柄，不会转移到控制器。耐久和掉落都立刻写回工具来源。
    const tool = storage.extract("ae2:i", { id: "minecraft:diamond_pickaxe" }, 1);
    if (tool.length === 0) return;
    const drops = bus.break(tool[0]);
    // 非 null 时，drops 是已经存在于 storage 中的 ResourceArray。

    // drop 精确扣除资源，并沿总线朝向生成物品实体。
    const cobble = storage.extract("ae2:i", { id: "minecraft:cobblestone" }, 16);
    const dropped = cobble.length > 0 && bus.drop(cobble[0]);

    // 不传物品时保持空手使用方块的语义。
    const activated = bus.use(true); // 潜行空手使用
});
```

`break` 失败时返回 `null`；成功时返回本次掉落对应的 `ResourceArray`，即使没有掉落也返回空数组。这些只是已写回工具来源的句柄。工具损坏后不再返回工具对象，脚本需要时可重新读取来源。无法正确采掘的方块仍会被破坏，但成功结果为空数组，行为与生存模式玩家规则一致。

所有持物操作都直接修改句柄的 `origin`：执行时精确取出旧 key，完成 Minecraft 交互后把剩余物、新 key、受损工具及掉落重新插入同一来源。工具不需要预先转移或由 workflow 持有。若第三方存储在取出后拒绝写回，无法回写的结果进入 recovery escrow 并使 workflow 失败，避免删除或复制。

**红石：**

总线提供红石读取与输出，两者都是同步查询/设置：

- `bus.redstone()`：读取目标方块面向总线面输出的红石等级（0-15），等价于总线贴在目标方块上的那一面收到的信号；总线或目标不可解析、区块未加载时为 `0`。注意读取的是强信号（`getSignal`），目标方块上的红石粉等弱信号源不会被读到。
- `bus.redstone(level)`：设置总线从物理线缆面向外输出的红石等级（0-15），会更新总线模型朝向的邻居方块；总线不可解析时返回 `false`，成功时返回 `true`。等级不是 0-15 的整数时抛出运行时错误。

```js
go(function* () {
    const bus = network("north").buses[0];
    if (bus.redstone() >= 8) {
        bus.redstone(15); // 用总线自身输出红石信号
    } else {
        bus.redstone(0);
    }
    yield sleep(1);
});
```

输出等级由总线部件持久化（随总线 NBT 保存），服务器重启后保持。

## 8. 脚本内配方获取：require_recipes 预编译宏

`require_recipes(filter)` 是**客户端预编译宏**，不是运行时函数。将脚本上传前，客户端会读取工作区里的 `processing_recipes.json`（用 `machine` 过滤器时还会读 `recipe_types.json`），按过滤器选出配方后，把整个调用替换成配方数组字面量。控制器收到的已经是“烤好”的配方数据，运行时不存在 `require_recipes`。

控制器页面从左侧文件管理器选择 `appliedscripts/` 内的本地文件；上传时先保存本地原始源码，再按该文件所在目录解析 `require_recipes()` 和 `include()`。服务器同时保存用于显示/拉取的原始源码和仅用于执行的展开源码。没有本地文件备份时禁止上传。

每个配方条目为 `{id, type, inputs, outputs, json}`：`inputs`/`outputs` 是 `{channel, key, amount}` 资源声明（与 `stack()` 同形），可以直接引用到 `registerProcessingPattern`；`json` 保留原始配方 JSON 作为只读参考。

一个输入槽对应配方的一个 ingredient 槽位。tag 或多选材料槽（如 `#minecraft:logs_that_burn`）会保留 `options` 数组（完整 `{channel, key, amount}` 条目，列出该槽接受的全部可选项），`key` 是代表物（第一个可选项，可直接用于注册）；配方只需要其中**任意一个**。只有一个可选项时省略 `options`。

**过滤器**是对象字面量，字段值可为单个字符串或字符串数组（any-of）：

| 字段 | 语义 |
| --- | --- |
| `id` | 配方 id 精确匹配 |
| `type` | 配方类型 id 精确匹配，如 `"minecraft:smelting"` |
| `machine` | 可处理该配方的机器方块 id（经 `recipe_types.json` 反查其配方类型），如 `"minecraft:furnace"` |
| `input` / `output` | 任一输入槽的代表物或任一 `option` / 任一输出资源的 `key.id` 精确匹配 |

多个字段为 AND，字段省略表示不限制。过滤出 0 条时展开为 `[]`；`processing_recipes.json` 缺失或过滤器非法（未知字段、非字面量参数）时打包报错并终止本次 execute/upload/GUI 保存。

```js
// 配方类型 → 配方
const smelt = require_recipes({ type: "minecraft:smelting" });

// 机器 → 配方（内部反查 recipe_types.json）
const furnaceRecipes = require_recipes({ machine: "minecraft:furnace" });

// 按输入/输出物品过滤，多字段 AND
const iron = require_recipes({ type: "minecraft:smelting", output: "minecraft:iron_ingot" });

// 直接取规范化资源注册，无需解析原始 JSON
registerProcessingPattern(
  [{ orderNetwork: "west", inputs: iron[0].inputs, outputs: iron[0].outputs }],
  function* (order) {
    yield order.input.pushExactlyInto(furnaceBus);
  }
);
```

- 展开结果是真实数组，可直接 `filter`/`map`/`find`；
- `registerProcessingPattern` 的 `inputs`/`outputs` 接受 `stack()`/`item()` 句柄，也接受普通 `{channel, key, amount}` 对象（与 `Recipe.inputs`/`outputs` 同形，可直接引用）；
- 展开后的源码仍受 128k 程序上限约束，过滤器应尽量收窄到实际需要的配方。

## 9.一般数据获取方法

`include(file)` 是**客户端预编译宏**，行为类似 C++ 的 `#include`：无论目标扩展名是什么，调用文本都会被目标文件原文替换。JSON 文件本身可作为表达式使用，所以 `let data = include("data.json");` 合法；一般 JS 文件则推荐直接使用 `include("helper.js");`。

相对路径按包含者所在目录计算。MCP 的内联源码和控制器 GUI 输入都视为 `appliedscripts/` 根目录中的虚拟文件；MCP 的 `file` 源码以该文件所在目录为根；嵌套 `include()` 则以被包含文件所在目录继续解析。若配方过多而不适合直接使用 `require_recipes`，也可先烘焙成较短的数据文件后 `include`。
