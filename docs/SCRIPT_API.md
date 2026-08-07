# Applied Factory 脚本 API 设计

> 状态：第一阶段实现契约。本文只约束脚本可见行为，不规定 Java 内部实现。

## 1. 第一阶段原则

第一阶段只实现已经有明确用途的能力：

- 枚举控制器可见的所有工厂总线；
- 用普通 JavaScript 读取、筛选和组合 Bus；
- 向一个确定端点精确推送完整资源；
- 从一个 Bus 无条件提取当前可提取的全部资源；
- 区分下单网络和脚本选择的执行网络；
- 让 initializer 只监听声明的一个或多个网络；
- 用 `sleep` 编写需要等待或重选的流程；
- 把被动产线注册为由 `sleep` 自行控制节奏的长期函数；
- 注册脚本样板和控制器通用 handler。

暂不设计部分传输结果、失败原因枚举、机器池、租约、自动重试或原生筛选 DSL。没有实际脚本证明其必要性之前，不把这些需求固化进 API。

## 2. 顶层注册 API

程序求值时可以调用：

```ts
initialize(definition)
registerPatterns(definition)
registerControllerHandler(definition)
registerPassive(handler)
```

对应三类 workflow：

1. `registerPatterns`：批量注册脚本样板，每个样板有自己的 handler；
2. `registerControllerHandler`：处理控制器实体槽内的全部 AE processing pattern；
3. `registerPassive`：注册一个启动一次且预期不返回的被动产线函数。

`initialize` 声明它依赖的网络，并只在这些网络的拓扑变化时重建脚本缓存。

源码成功保存后产生新的 program revision。已经接受的 processing 任务继续使用启动时的源码和 continuation；长期被动产线不跨 revision 永久保留，而是在安全挂起点停止并完成资源保全，新 initializer 成功后再启动新 revision 注册的产线。新旧被动产线不会同时运行。

## 3. 下单网络与执行网络

下单网络和执行网络是两个不同概念：

- 下单网络决定哪个 AE 网络能够看到样板并提交 crafting job；
- 执行网络由 handler 在运行时选择，决定使用哪一侧网络及其工厂总线；
- 一个 handler 可以同时访问多个执行网络；
- 输出通常送回下单网络，但 API 不强制这样做。

### 3.1 指定下单网络

脚本样板注册时明确指定控制器面：

```js
registerPatterns({
  orderNetwork: "north",
  patterns: [
    {
      id: "iron",
      inputs: [item("minecraft:iron_ore", 1)],
      outputs: [item("minecraft:iron_ingot", 1)],
      handler: smelt
    },
    {
      id: "gold",
      inputs: [item("minecraft:gold_ore", 1)],
      outputs: [item("minecraft:gold_ingot", 1)],
      handler: smelt
    }
  ]
});
```

```ts
interface PatternRegistration {
  readonly orderNetwork: Direction;
  readonly patterns: readonly ScriptPatternDefinition[];
}
```

同一批注册的样板只向 `orderNetwork` 对应的控制器 AE 节点发布。需要发布到另一网络时再次调用 `registerPatterns`。

控制器实体样板槽也明确指定发布网络：

```js
registerControllerHandler({
  orderNetwork: "north",
  handler: smelt
});
```

```ts
interface ControllerHandlerDefinition {
  readonly orderNetwork: Direction;
  readonly handler: (ctx: ProcessingContext) => void;
}
```

### 3.2 取得执行网络

```ts
ctx.network(side: Direction): Network
```

handler 可以显式取得任意控制器面连接的网络：

```js
const production = ctx.network("south");
if (!production.online()) {
  ctx.fail("Production network is offline");
}
```

控制器六个物理面始终各自拥有一个 `Network` 句柄；未接线、无频道或未供电由 `online() === false` 表示，而不是返回 `null`。initializer 中访问未列入 `initialize.networks` 的 side 仍会抛出脚本错误。

processing handler 还拥有：

```ts
ctx.orderNetwork: Network
```

它表示实际接收该样板并提交本次任务的网络。于是主网下单、子网生产可以直接写成：

```js
const production = ctx.network("south");
const productionBuses = production.buses;

// 使用子网中的 Bus 执行生产。
// 最后把产物送回主网。
ctx.orderNetwork.items().push(products);
```

`ctx.network(side)` 是按控制器物理面寻址，不是 Bus 筛选函数。控制器各面仍是独立 AE 节点，访问它们不会桥接频道。

## 4. Context

```ts
type Direction = "up" | "down" | "north" | "south" | "west" | "east";

interface BaseContext {
  readonly tick: number;
  readonly buses: readonly Bus[];
  // 当前 workflow 仍持有的全部资源，可用于重新取得丢失的局部变量。
  readonly owned: readonly OwnedResource[];

  network(side: Direction): Network;
  sleep(ticks: number): void;
  yield(): void;
  fail(message: string): never;
  log(message: string): void;
}

interface ProcessingContext extends BaseContext {
  readonly orderNetwork: Network;
  readonly inputs: readonly OwnedResource[];
  readonly outputs: readonly Resource[];
}

interface PassiveContext extends BaseContext {
}

interface InitializeContext {
  readonly tick: number;
  // 仅为 initializer 声明监听的网络并集。
  readonly buses: readonly Bus[];

  // 只能访问 initializer 声明监听的 side。
  network(side: Direction): Network;
  log(message: string): void;
}
```

`ctx.sleep(n)` 持久化挂起当前 workflow，至少经过 `n` 个服务器 tick 后从调用点继续。局部变量保存在 Rhino continuation 中。`ctx.yield()` 等价于 `ctx.sleep(1)`。

initializer 只能读取其声明监听的 Bus 和 Network 拓扑，不能转移资源、修改世界或挂起。访问未声明的网络属于脚本错误，防止缓存具有未被监听的隐式依赖。

## 5. Bus 枚举

### 5.1 `ctx.buses`

普通 processing/passive context 中，`ctx.buses` 是控制器当前所有已连接网络中可见 Bus 的并集：

```js
const buses = ctx.buses;
```

每次读取返回当前目录的独立、有序数组快照。脚本修改这个数组只影响本地快照，保存在局部变量中的旧数组也不会自动变化：

```js
const before = ctx.buses;
ctx.sleep(20);
const after = ctx.buses;
```

initializer 是例外：其中的 `ctx.buses` 只包含 `initialize.networks` 声明的网络，见第 6 节。

使用 JavaScript 自带方法筛选：

```js
const furnaces = ctx.buses.filter(bus => {
  const target = bus.target();
  return target !== null && target.id === "minecraft:furnace";
});
```

第一阶段不存在：

```ts
// 不存在
ctx.getMachine(...)
ctx.getMachines(...)
ctx.getBus(filter)
ctx.getBuses(filter)
```

### 5.2 `network.buses`

```ts
interface Network {
  readonly side: Direction;
  readonly buses: readonly Bus[];

  online(): boolean;
  items(): NetworkItems;
}
```

`network.buses` 只包含该控制器面所连 AE grid 中的活动工厂总线。主网与生产子网分离时，handler 通常遍历执行网络的 `buses`，而不是 `ctx.buses` 的并集。

若同一个 AE grid 同时连到控制器多个面，这些 `Network` 可以拥有相同的 Bus 集合；脚本仍按控制器面选择它们。

## 6. initialize

```js
let productionLines = [];

initialize({
  networks: ["south"],
  handler: function (ctx) {
    productionLines = [];

    const production = ctx.network("south");
    if (production.online()) {
      productionLines = buildLines(production.buses);
    }
  }
});
```

```ts
interface InitializerDefinition {
  readonly networks: readonly Direction[];
  readonly handler: (ctx: InitializeContext) => void;
}
```

`networks` 是 initializer 的完整拓扑依赖：

- 可以声明一个或多个控制器面；
- `ctx.buses` 只合并这些网络中的 Bus；
- `ctx.network(side)` 只能读取这些网络；
- 未声明网络发生任何变化都不会触发该 initializer；
- `networks: []` 表示只在程序加载或 revision 更新时运行，不监听网络拓扑。

initializer 在以下情况执行：

- 程序首次加载或 Rhino runtime 重建；
- 控制器保存新 revision；
- 声明监听的控制器面所连 AE grid 改变；
- 声明监听的网络内，工厂总线上下线、换 host、换面或改变可见性；
- 声明监听的网络内，相邻目标方块被替换，或可访问 capability 类型发生结构性变化。

例如 initializer 只声明 `south` 时，主网 `north` 的频道、Bus 或拓扑变化不会让生产子网重新初始化。

若同一个底层 AE grid 同时连接已监听面和未监听面，该 grid 的结构变化仍会通过已监听面触发初始化。

物品数量、机器忙闲、红石值和普通 BlockState 属性变化不属于拓扑变化。动态条件应在 handler 中重新读取。

初始化不是事务。已执行的赋值不会因后续异常回滚，因此建议先清空缓存，再重新构造。

## 7. Bus 地址与读取

```ts
interface FactoryBusAddress {
  readonly dimension: string;
  readonly hostX: number;
  readonly hostY: number;
  readonly hostZ: number;
  readonly partSide: Direction;
  readonly key: string;
}

interface BlockAddress {
  readonly dimension: string;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly key: string;
}
```

- `FactoryBusAddress` 定位 multipart host 上的工厂总线 part；
- `partSide` 从 host 指向相邻目标方块；
- `BlockAddress` 是相邻目标方块的位置；
- `targetFace` 是目标方块被访问的面，即 `partSide` 的反方向；
- 地址相等使用 `.key`，不使用 JavaScript 对象引用 `===`。

```ts
interface Bus {
  readonly address: FactoryBusAddress;
  readonly targetAddress: BlockAddress;
  readonly targetFace: Direction;

  exists(): boolean;
  state(): BusState | null;
  target(): BlockView | null;
  items(): BusItems | null;

  detect(selector: string): boolean;
  drop(resources: OwnedResource | readonly OwnedResource[]): boolean;
  use(): boolean;
  place(resource: OwnedResource): boolean;
  break(): readonly OwnedResource[];
  redstone(level: number): boolean;
}
```

Bus 句柄内部只保留地址。`exists`、`state`、`target` 和 `items` 每次都重新解析当前世界，不持有旧 `BlockEntity` 或 capability。

### 7.1 世界操作与红石输出

`detect(selector)` 同步检测总线当前目标方块；参数可为方块 ID 或 `#` 开头的方块标签。目标不存在、所在区块未加载或不匹配时返回 `false`。

其余操作都只能在 processing/passive workflow 中调用：每次调用只执行一次，并在执行后以 `boolean` 恢复脚本。

- `drop(resources)` 将完整的 owned resource 列表从总线目标面投掷为世界物品实体；成功后资源不再归 workflow 所有。
- `use()` 由名为 `[Applied Factory]` 的空手伪玩家点击目标方块；会经过正常的服务端交互与保护兼容路径。
- `place(resource)` 由同一伪玩家把一个 owned block item 放进空的目标位置。无论资源对象的 `amount` 多大，每次成功调用只消耗一个；目标非空气、资源不是方块物品或放置被拒绝时返回 `false`。
- `break()` 由同一伪玩家使用内置、无附魔的钻石镐破坏当前目标方块，并把普通方块掉落捕获为 owned resources。缓存不能完整接收预估掉落时不破坏方块；若 modded loot 与预估不一致导致实际掉落无法写入缓存，掉落物会保留为世界物品实体而不是被删除。返回空数组表示目标未破坏或没有可捕获掉落。
- `break(tool)` 用 `tool`（一个 owned 物品）作为伪玩家手持工具破坏目标方块，掉落计算遵循该工具的采掘等级、附魔和标签；工具只校验所有权、不消耗、不扣耐久。其余语义与 `break()` 一致。
- `redstone(level)` 将这个 Factory Bus 所在物理面输出设置为 `0` 到 `15`，该值会保存并通知邻居；返回 `false` 仅表示总线已不再可访问。`bus.state().redstone` 仍是目标方块面对总线的输入信号，不是这个输出值。

例如，先识别目标、再让总线作为红石执行器：

```js
if (bus.detect("minecraft:lever")) {
  bus.use();
} else {
  bus.redstone(15);
}
```

```ts
interface BusState {
  readonly active: boolean;
  readonly powered: boolean;
  readonly redstone: number;
  readonly upgrades: Readonly<Record<string, number>>;
  readonly config: Readonly<Record<string, string | number | boolean | null>>;
}

interface BlockView {
  readonly id: string;
  readonly state: Readonly<Record<string, string>>;
  readonly blockEntityType: string | null;
  // 方块实体自身的 NBT 快照（不含 id/坐标元数据）；无方块实体时为 null。
  readonly nbt: Readonly<Record<string, NbtValue>> | null;

  matches(selector: string): boolean;
}
```

`BlockView.nbt` 是 `bus.target()` 调用时刻的不可变快照，使用与 `Resource.nbt()` 相同的有界 NBT 转换。普通 BlockState 属性变化不属于拓扑变化，脚本应在 handler 中重新调用 `bus.target()`。

脚本只能读取 AF 明确转换后的不可变数据，不能取得原始方块实体、capability 或 Java 反射对象。

## 8. 多面方块由脚本组合

Java 层不创建逻辑 Machine。需要同时具有上输入面和下输出面的方块时，脚本按 `targetAddress.key` 求交集：

```js
function buildLines(buses) {
  const outputs = new Map();

  for (const bus of buses) {
    const target = bus.target();
    if (target !== null
        && target.id === "minecraft:furnace"
        && bus.targetFace === "down"
        && bus.items() !== null) {
      outputs.set(bus.targetAddress.key, bus);
    }
  }

  const lines = [];
  for (const input of buses) {
    const target = input.target();
    const output = outputs.get(input.targetAddress.key);

    if (target !== null
        && target.id === "minecraft:furnace"
        && input.targetFace === "up"
        && input.items() !== null
        && output !== undefined) {
      lines.push({ input, output });
    }
  }

  return lines;
}
```

Bus 损坏或移动后旧句柄不会自动换地址。需要重选时，脚本重新遍历 `ctx.buses` 或 `network.buses`。

## 9. 资源

第一阶段只公开物品资源：

```js
item("minecraft:iron_ore", 1)
item("minecraft:iron_ingot", 1)
// 可选第三参：精确数据组件（Data Components），见下。
item("minecraft:iron_pickaxe", 1, { "minecraft:enchantments": { levels: { "minecraft:efficiency": 5 } } })
```

`item` 在样板注册和 Network 精确提取时创建不可变资源请求。不带 `nbt` 时请求的是默认组件；带 `nbt` 时请求的是携带该组件补丁的精确 AE key。

```ts
type NbtValue = string | number | boolean | readonly NbtValue[]
    | Readonly<Record<string, NbtValue>> | null;

interface Resource {
  readonly id: string;
  readonly amount: number;

  matches(selector: string): boolean;
  // 该物品完整存档 NBT 的只读快照：{ id, count, components }。
  nbt(): Readonly<Record<string, NbtValue>>;
}

interface OwnedResource extends Resource {
  // 不可由脚本构造的当前 workflow 所有权
  // 给这个 owned 物品设置自定义显示名，挂起执行，返回改名后的 OwnedResource。
  rename(name: string): OwnedResource;
}
```

`Resource.nbt()` 是有界的只读转换，不能取得原始 `ItemStack`、NBT 对象或组件句柄。整数超过 `2^53` 的长整型以字符串返回，避免精度丢失。超过深度/节点上限的 NBT 会抛出脚本错误。

`OwnedResource.rename(name)` 把该 owned 物品改名为 `name`：普通字符串按纯文本处理，合法 JSON 文本按序列化 Component 解析（例如 `{"text":"绿","color":"green"}`），从而支持颜色和样式。改名是缓存和所有权账本上的键迁移：旧键移除、新键存入，之后返回的句柄携带新键，可用 `push` 推送；旧句柄随余额校验失效。非物品资源（如流体）不支持改名，返回空数组。

- `ctx.inputs` 是 AE 实际交付且由 workflow 拥有的精确资源；
- `ctx.outputs` 是样板声明的期望值，不表示 workflow 已经拥有产物；
- Bus 或 Network 的提取结果是 `OwnedResource`；
- `push` 只接受当前 workflow 拥有的资源；
- Java bridge 内部始终保存完整 AEKey，脚本不能用普通对象或物品 ID 伪造所有权。

`OwnedResource` 是当前 workflow 对资源的持有权视图，不是把物品存在 JavaScript 对象里。控制器会把真实资源放在其私有 AE 存储元件中，同时把所有权账本和 workflow 一起持久化。

因此，忘记输出资源后释放局部变量不会删除物品：

```js
let input = source.items().extract(item("minecraft:iron_ore", 1));
input = null;

// 之后仍可重新取得当前 workflow 持有的资源。
const stillOwned = ctx.owned;
```

`ctx.owned` 每次读取都反映调用点处尚未消费的账本余额。旧的 `OwnedResource` 句柄不会因为其他 `push` 已消费同一余额而继续有效；再次使用时 Java 层会校验余额。

### 9.1 控制器私有缓存

控制器提供专用存储元件槽，作为所有 workflow 共用但按账本隔离的物理 escrow：

- 至少安装一个能存放对应资源类型的 AE 存储元件，processing 输入才会被控制器接受；
- Bus/Network 提取前先模拟完整结果，私有缓存不能完整接收时不改变来源并返回空数组；
- 缓存不挂载到控制器任意一侧的 AE 网络，外部存储总线不能把它当普通网络库存访问；
- 第一阶段只允许放入内容为空的存储元件；已格式化但内容为空的元件可以使用；
- 只要仍有 workflow 持有资源，存储元件槽就锁定，玩家不能取出元件；如果账本损坏或旧版本迁移留下了无人持有的内容，元件解锁后可由玩家连同内容一起取走；
- workflow 正常返回或失败后，框架尝试把仍持有的资源退回下单网络或其已经使用过的恢复网络；网络不可用时资源继续留在缓存中，不会随 JS 变量回收而消失；
- 控制器被破坏时直接掉落带有实际内容的存储元件，不能再额外实体化账本资源，避免复制。

没有兼容存储元件时，`BusItems.extract()` 与 `NetworkItems.extract()` 返回空数组，AE processing 下单则不被控制器接受。`push` 操作消费的是已经位于缓存中的 owned resource，因此不会凭空构造物品。

第一阶段不提供 `slice`、`split`、部分所有权结果或脚本构造任意 component patch。需要不同数量时直接在 AE processing pattern 中编码对应数量。

## 10. Bus 物品 API

```ts
interface BusItems {
  read(): readonly Resource[];
  push(resources: OwnedResource | readonly OwnedResource[]): true;
  pushTillFull(resources: OwnedResource | readonly OwnedResource[]): true;
  canPush(resources: Resource | readonly Resource[]): boolean;
  extract(): readonly OwnedResource[];
}
```

### 10.1 `read()`

返回调用时该目标面可见的物品快照，不修改世界。脚本可以用普通数组方法检查内容。返回值只是观察值，不能传给 `push`。

### 10.2 `push(resources)`

挂起并向这个 Bus 地址精确推送给定的完整资源集合，**每服务端 tick 重试一次，直到全部数量一次性被目标接受后才返回 `true`**：

- 只在目标能完整接收时推送，不允许部分成功；
- 推送失败时脚本不会恢复，同一动作在下一 tick 重试；
- 目标持续无法接收（消失、capability 不存在或容量不足）时，workflow 一直挂起在调用点；
- 需要非阻塞判断时先用 `canPush` 查询，再决定是否等待。

参数非法、资源不属于当前 workflow 或资源数量已被消费时抛出脚本错误。这与 AE 下单语义一致：AE 的 processing 任务同样在目标无法接收全部输入时等待。

### 10.3 `pushTillFull(resources)`

挂起并每 tick 向目标推送**当前能接受的部分输入**，直到整个资源列表全部进入目标后返回 `true`：

- 每 tick 只尝试一次，按能容纳的量逐步填充目标；
- 每 tick 已成功推送的部分会从 workflow 所有权中扣除，剩余部分下一 tick 继续；
- 目标每次只能收一点时（例如熔炉输入槽），用这个函数代替 `push` 可以持续补料直到全部输入被消耗；
- 目标一直不可用时同样持续挂起。

`push` 与 `pushTillFull` 的分工：`push` 要求一次精确全部、绝不部分；`pushTillFull` 允许部分填充、直到全部。

### 10.4 `canPush(resources)`

同步查询，不挂起、不转移资源：返回当前目标能否**一次性**接受精确的完整资源列表。`resources` 可以是普通资源规格（不要求 owned）。

### 10.5 `extract()`

无参数提取该目标面此刻可提取的全部物品：

- 返回实际提取并由 workflow 拥有的资源数组；
- 没有物品、目标消失或 capability 不存在时返回空数组；
- 不传 selector、期望数量或 timeout；
- 不等待产物，不判断批次归属；
- 一次调用只尝试一次。

这种语义适合“机器输出面有什么就全部送回网络”的主要用例。需要区分机器内部不同产物时，先通过方块的物理侧面、Bus 配置或专用脚本解决；第一阶段不为它设计复杂提取结果。

## 11. Network 物品 API

Bus 与 AE 网络不强行实现同一个存储接口。二者都支持精确 `push`，但提取语义不同：从机器输出面可以无条件全部取出，从大型 AE 网络无条件清空显然不安全。

```ts
interface NetworkItems {
  push(resources: OwnedResource | readonly OwnedResource[]): true;
  pushTillFull(resources: OwnedResource | readonly OwnedResource[]): true;
  canPush(resources: Resource | readonly Resource[]): boolean;
  extract(requests: Resource | readonly Resource[]): readonly OwnedResource[];
  read(): readonly Resource[];
  count(spec: string | Resource): number;
}
```

### 11.1 `network.items().push(resources)`

挂起并向指定 AE 网络精确推送完整资源集合，**每 tick 重试直到全部数量一次性被网络接受后返回 `true`**：

- 只在网络能完整接收时推送，不允许部分成功；
- 网络离线、满仓或无法完整接收时持续挂起重试；
- 不自动改送下单网络或其他控制器面。

### 11.2 `network.items().pushTillFull(resources)`

挂起并每 tick 向网络推送当前能接受的部分输入，直到整个资源列表全部进入网络后返回 `true`。与 `bus.items().pushTillFull` 语义一致，用于把产出逐步回流到大型网络。

### 11.3 `network.items().canPush(resources)`

同步查询，不挂起、不转移资源：返回该网络当前能否一次性接受精确的完整资源列表。`resources` 可以是普通资源规格。

### 11.4 `network.items().extract(requests)`

这是被动生产从 AE 网络取得输入所需的最小能力：

- `requests` 必须是精确物品与精确数量；
- 网络能完整提供时返回对应 `OwnedResource[]`；
- 数量不足、网络离线或请求不可用时返回空数组；
- 不支持标签请求、部分数量或复杂结果对象。

示例：

```js
const source = ctx.network("north");
if (!source.online()) return;

const ores = source.items().extract(item("minecraft:iron_ore", 1));
if (ores.length === 0) return;
```

### 11.5 `network.items().read()` 与 `network.items().count(spec)`

两个同步只读操作，不改变网络、不转移资源，也不触发挂起。它们读取当前 tick 该 AE 网格可提供的物品：

- `read()` 返回当前可提供物品的独立快照数组，元素是观察值（不能传给 `push`）；
- `count(spec)` 返回匹配数量；`spec` 为字符串时按物品 id 或 `#tag` 宽松匹配（不区分数据组件），为 `item(...)` 资源对象时精确匹配其 AE key（`item(id, amount, nbt)` 可用于按组件统计）。

网络离线或未接线时 `read()` 返回空数组、`count(spec)` 返回 `0`。`read()` 与 `count()` 是同步只读操作，processing/passive workflow 与 initializer（仅限其声明监听的网络）都可以调用。

```js
const north = ctx.network("north");
if (north.online() && north.items().count("minecraft:iron_ingot") < 64) {
  // 铸造更多铁锭。
}
```

## 12. 脚本样板

```ts
interface ScriptPatternDefinition {
  readonly id: string;
  readonly inputs: readonly Resource[];
  readonly outputs: readonly Resource[];
  readonly handler: (ctx: ProcessingContext) => void;
}
```

```js
registerPatterns({
  orderNetwork: "north",
  patterns: [
    {
      id: "iron",
      inputs: [item("minecraft:iron_ore", 1)],
      outputs: [item("minecraft:iron_ingot", 1)],
      handler: smelt
    }
  ]
});
```

每个样板直接绑定自己的 handler，不进入控制器通用 handler。同一个 handler 可以被多个样板复用。

## 13. 控制器通用 handler

```js
registerControllerHandler({
  orderNetwork: "north",
  handler: smelt
});
```

规则：

- 只处理控制器实体样板槽中的 AE processing pattern；
- 这些样板只发布到指定的 `orderNetwork`；
- 每次 AE 推送产生独立 workflow；
- `ctx.inputs` 和 `ctx.outputs` 来自该次样板执行。

## 14. 被动产线

被动产线直接注册一个长期运行函数：

```ts
registerPassive(handler: (ctx: PassiveContext) => never): void
```

```js
registerPassive(function feedOre(ctx) {
  while (true) {
    const source = ctx.network("north");
    const line = productionLines[0];
    if (!source.online() || line === undefined) {
      ctx.sleep(20);
      continue;
    }

    const output = line.output.items();
    if (output === null
        || !clearBeforeStart(ctx, output, source.items(), 200)) {
      ctx.log("Cannot clear production line");
      ctx.sleep(20);
      continue;
    }

    const input = source.items().extract(item("minecraft:iron_ore", 1));
    if (input.length === 0) {
      ctx.sleep(20);
      continue;
    }

    const targetItems = line.input.items();
    if (targetItems === null) {
      // 目标面消失：先把 input 退回源网络，下一轮再重新选择。
      source.items().push(input);
      continue;
    }

    // push 挂起直到熔炉一次性接受全部输入。
    targetItems.push(input);

    // 产线节奏完全由脚本决定。
    ctx.sleep(20);
  }
});
```

生命周期：

- 程序首次成功初始化后，每个 `registerPassive(handler)` 启动一次；
- 每次注册固定只有一条 continuation，不提供 `concurrency`；
- 没有调度器 `interval`，循环内部用 `sleep` 控制节奏；
- topology initializer 执行期间所有产线暂停调度，initializer 完成后从原调用点继续；
- initializer 报错时产线保持暂停，直到该 revision 的 initializer 成功；
- initializer 更新的全局 Bus 缓存会被产线后续代码看到；
- initializer 不会隐式重启产线或丢弃其局部变量；
- handler 正常返回表示产线主动停止，只在新 program revision 启动新版本时重新创建；
- program revision 更新会在安全挂起点停止旧产线、保全其资源，然后执行新 initializer 并启动新产线；
- handler 必须定期 `sleep` 或 `yield`，纯忙循环会触发脚本指令上限。

被动产线不属于 AE crafting job，不占 AE crafting CPU。多个 `registerPassive` 调用表示多条显式产线，而不是同一产线的并发副本。

## 15. 用户态等待与超时

`push` 与 `pushTillFull` 会挂起并每 tick 重试，直到完成才返回；`extract` 的空数组和 `canPush` 的 `false` 是需要脚本自行决定等待的运行结果。`extract` 没有内置等待，脚本可以用包装函数轮询：

```js
function extractEventually(ctx, items, interval) {
  let result = [];
  while (result.length === 0) {
    result = items.extract();
    if (result.length === 0) ctx.sleep(interval);
  }
  return result;
}
```

需要非阻塞判断推送是否可行时，先查 `canPush`：

```js
function pushIfPossible(ctx, items, resources, interval) {
  while (!items.canPush(resources)) {
    ctx.sleep(interval);
  }
  items.push(resources); // 下一 tick 必然一次性成功
}
```

Bus 地址失效时这些包装函数会一直等待。希望换地址的脚本应在循环中重新读取 `ctx.buses` 或 `network.buses` 并重新选择。框架不会保存筛选函数，也不会自动重新求值动作参数。`push`/`pushTillFull` 挂起期间脚本无法执行其他动作，需要超时或改换目标的产线应当用 `canPush` + `ctx.sleep` 组合自行控制节奏。

### 15.1 deadline

第一阶段不增加异步 `setTimeout(callback)`。这种 API 会在当前产线之外创建第二条 continuation，重新引入隐藏并发和资源所有权问题。

等待超时可以直接用持续更新的 `ctx.tick` 表达：

```js
function waitUntil(ctx, predicate, timeoutTicks, interval) {
  const deadline = ctx.tick + timeoutTicks;

  while (!predicate()) {
    if (ctx.tick >= deadline) return false;
    ctx.sleep(interval);
  }

  return true;
}
```

`ctx.tick` 是读取时的当前服务器 tick，不是 workflow 创建时的固定值。

### 15.2 开始生产前清空机器

产线可以在每轮开始时清空输出面，并用 deadline 防止永久等待：

```js
function clearBeforeStart(ctx, output, sink, timeoutTicks) {
  const deadline = ctx.tick + timeoutTicks;

  while (ctx.tick < deadline) {
    const leftovers = output.extract();
    if (leftovers.length === 0) return true;

    while (!sink.canPush(leftovers)) {
      if (ctx.tick >= deadline) return false;
      ctx.sleep(1);
    }
    sink.push(leftovers);

    // 即使机器持续产生物品，也让服务器 tick 和 deadline 前进。
    ctx.yield();
  }

  return false;
}
```

`sink` 可以是下单网络、生产网络或脚本选择的其他 NetworkItems。清空失败后的停止、报警或继续生产仍由产线代码决定。

## 16. 主网下单、子网生产示例

```js
let furnaces = [];

initialize({
  networks: ["south"],
  handler: function (ctx) {
    furnaces = [];

    const production = ctx.network("south");
    if (production.online()) {
      furnaces = buildLines(production.buses);
    }
  }
});

function smelt(ctx) {
  const line = furnaces.find(candidate =>
    candidate.input.exists() && candidate.output.exists()
  );

  if (line === undefined) {
    ctx.fail("No furnace on production network");
  }

  const input = line.input.items();
  const output = line.output.items();
  if (input === null || output === null) {
    ctx.fail("Furnace bus disappeared");
  }

  // 精确推送：挂起直到熔炉能一次性接收全部输入。
  input.push(ctx.inputs);

  // 输出面有什么就取出什么，不要求框架追踪批次。
  const products = extractEventually(ctx, output, 5);

  // 下单网络是 north；执行 Bus 来自 south 子网。push 会挂起重试直到全部进入网络。
  ctx.orderNetwork.items().push(products);
}

registerPatterns({
  orderNetwork: "north",
  patterns: [
    {
      id: "iron",
      inputs: [item("minecraft:iron_ore", 1)],
      outputs: [item("minecraft:iron_ingot", 1)],
      handler: smelt
    }
  ]
});

registerControllerHandler({
  orderNetwork: "north",
  handler: smelt
});
```

这里没有 `executionNetwork` 全局字段。执行网络是脚本本次通过 `ctx.network("south")` 选择的，因此同一个 handler 可以按配方、在线状态或用户逻辑改用其他网络。

## 17. AE crafting CPU 固定空间

第一阶段不注入虚拟资源，也不根据源码或 continuation 的 Java 内存估算空间。MFM 以普通 `IPatternDetails` 参与 AE crafting plan；AE 原生会按样板执行次数计入固定 crafting bytes，因此脚本样板和控制器槽内样板自然占用 crafting CPU，而不需要“包裹通用堆栈”或额外显示一种资源。

这个成本由样板执行次数决定，不由脚本声明或查询。被动 workflow 不属于 AE crafting job，因此不占 crafting CPU。若实践证明原版的固定成本不足，再单独增加服务端配置和 AE 集成点；它不进入首版脚本 API。

## 18. 暂不设计

- `InsertResult`、`ExtractResult` 或失败原因枚举；
- 部分插入、部分请求、`moved` / `remaining` 资源切片；
- selector/tag 网络提取（`extract` 仍要求精确数量；`count` 支持 tag 字符串）；
- `Resource.slice`、`Resource.split`；
- Java 侧 Machine、机器池、租约或批次归属；
- `getMachine(s)`、原生 Bus filter callback 或查询 DSL；
- 自动换 Bus、自动换网络或参数重新求值（`push`/`pushTillFull` 只对选定地址等待重试，不重新选择目标）；
- 独立并发的 `setTimeout(callback)` 定时任务；
- Bus UUID、移动后保持身份或预期 block ID 校验；
- 流体端点、任意世界操作；
- `break(tool)` 的耐久扣减（工具当前只借用不消耗）；
- 默认机器并发锁；
- 根据 Rhino 实际内存动态计算 crafting CPU bytes。

这些能力以后只在真实脚本证明现有原语无法合理表达时再加入。
