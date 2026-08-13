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
        const remaining = network("east")
            .extract(item("minecraft:coal", -1))
            .to(bus)
            .now();
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

Resource 是不可变值 `(origin, bundle)`。它不会在创建时把普通端点中的资源提取到控制器，也不会锁定机器或网络库存。

`extract()` 只读取来源当前可用资源，并生成带来源的 Resource：

```js
const products = furnace.extract();
```

此时资源仍在炉子中。如果在 Action 执行前被其他设备消耗，Action 会等待来源重新拥有足够的相同 AEKey。AEKey 对应的资源是同质的，后续补入来源的同种资源可以满足原 Resource。

`extract(spec)` 将 `-1` 解析为调用时该来源中对应 AEKey 的全部可用数量，并把结果固化进 `bundle`。以后进入来源的新资源不会扩大这个 Resource。调用时数量为零会得到空 Resource；空 Resource 的转移立即成功。

同一个 Resource 可以创建多个 Action，但它不代表对普通端点的独占所有权。多个 Action 竞争相同来源时由服务器主线程执行顺序决定，后执行者在库存不足时等待。

## 5. 隐形订单托管

AE2 把 processing pattern 输入交给控制器时，控制器将实际资源写入内部托管区。玩家不需要安装存储元件，托管区也不作为普通 AE 存储对外暴露。

每笔订单拥有独立分配：

```text
OrderEscrowOrigin(orderId)
└── bundle：该订单尚未消费的输入
```

`order.input` 是指向该分配的 Resource，因此不会被其他订单或外部设备消费。

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

同一个 Action 在 `.now()` 或调度重试后会更新自己的 `remaining`；再次执行这个 Action 只处理剩余部分，不会从初始 bundle 重新搬运。

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

- `to(...).now()` 只尝试一次，返回尚未转移的 Resource；
- `pushExactlyInto(...).now()` 只尝试一次，返回 boolean；
- `.now()` 不进入调度器，不跨 tick 等待。

## 7. 原子性与恢复

所有资源操作都在服务器线程执行，仍然需要处理第三方存储模拟结果与实际执行不一致的问题。

- EXACT Action 先模拟来源和目标，再执行完整转移；
- 目标实际拒绝时优先回滚到来源；
- 无法完整回滚时写入控制器内部 recovery escrow，禁止删除或复制资源；
- PARTIAL Action 只从进度中扣除实际成功进入目标的数量。

订单托管区和 recovery escrow 是内部正确性机制，不是玩家容量玩法。

## 8. Java 桥接边界

脚本 API 由专门的 Java facade 类实现。桥接类中的自声明 public 方法默认全部暴露给 JS：

```java
@JsBridge
public final class JsResource {
    private final ResourceRef resource;

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

## 9. 生命周期与失败语义

- 脚本保存或重载：先创建新 runtime；成功后终止旧 generator，其托管资源进入恢复流程；
- 服务器重启：不恢复 generator 调用点；
- processing generator 丢失：订单进入失败/恢复状态，玩家可以取消并重新下单；
- passive generator 丢失：控制器加载后从 `go` 的入口重新创建；
- 总线暂时无法解析或目标机器不提供所需存储：Action 保持等待；
- 单纯缺少来源资源或目标容量：保持等待，并向 UI 暴露缺少的 bundle 或目标满状态。

隐形托管资源的数据量很小，与控制器 NBT 一起保存。