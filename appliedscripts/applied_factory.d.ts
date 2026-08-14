/**
 * Applied Factory 脚本 API —— 权威类型与签名参考。
 *
 * 类型和签名以此文件为准，appliedscripts/SCRIPT_API.md 只描述运行模型与行为语义。
 * 控制器脚本在 Rhino ES6 编译模式下于控制器加载时求值一次：脚本用全局函数
 * 取得句柄、注册 processing pattern，并通过 `go` 启动被动 generator workflow。
 */

type Direction = "up" | "down" | "north" | "south" | "west" | "east";
type NbtValue =
  | string
  | number
  | boolean
  | readonly NbtValue[]
  | Readonly<Record<string, NbtValue>>
  | null;
type NbtCompound = Readonly<Record<string, NbtValue>>;

/** AEKeyType 的注册表 ID，例如物品为 "ae2:i"、流体为 "ae2:f"。 */
type ResourceChannel = string;

type Action = SleepAction | TransferAction<unknown>;

/** 只描述要匹配的 AE key 和数量，不是可操作的来源句柄。 */
interface ResourceSpec {
  /** 取值参考channels.json */
  readonly channel: string;
  /** 直接交给该 channel 的 AEKey codec 解码。 */
  readonly key: NbtCompound;
  /** 数量为正数表示取到最多这么多资源，或 `-1` 表示尽可能多的资源。 */
  readonly amount: number;
}

interface Resource {
  /** 原始 AEKeyType ID；未知扩展 channel 不需要额外接口或适配器。 */
  readonly channel: string;
  /** 该 channel 自己的 codec NBT，可直接传回 stack(channel, key, amount)。 */
  readonly key: NbtCompound;
  /** 便于显示和筛选的 AEKey ID；不用于重建未知 key。 */
  readonly id: string;
  readonly amount: number;
  readonly origin: ResourceOrigin;

  /** 部分转移：每次移动当前可行的部分，直到该资源全部移走。 */
  to(target: ResourceTarget): TransferAction<Resource | null>;
  /** 精确转移：等待来源和目标能一次处理完整数量时才移动。 */
  pushExactlyInto(target: ResourceTarget): TransferAction<boolean>;
}

/** 真正的 JS 数组，并附带针对整个快照的批量转移方法。 */
interface ResourceArray extends ReadonlyArray<Resource> {
  to(target: ResourceTarget): TransferAction<ResourceArray | null>;
  pushExactlyInto(target: ResourceTarget): TransferAction<boolean>;
}

interface ResourceOrigin {
  readonly kind: "network" | "bus" | "escrow";
  readonly endpoint: Network | Bus | null;
}

interface TransferAction<TResult> {
  now(): TResult;
}

interface SleepAction {}

interface BlockView {
  readonly id: string;
  /** 完整方块状态字符串，如 "minecraft:furnace[facing=north,lit=false]"。 */
  readonly state: string;
  /** 目标方块坐标。 */
  readonly x: number;
  readonly y: number;
  readonly z: number;
  /** 方块状态属性表（属性名 → 值）；布尔/整数保持原类型，其余为字符串。 */
  readonly properties: Readonly<Record<string, boolean | number | string>>;
  /** 方块实体类型 id；没有方块实体时为 null。 */
  readonly blockEntityType: string | null;
  /** 方块实体自身的只读 NBT 快照；没有方块实体时为 null。 */
  readonly nbt: Readonly<Record<string, NbtValue>> | null;

  /** 是否与另一个 BlockView 指向同一格方块（按坐标判等）。 */
  isSameBlock(other: BlockView): boolean;
}

interface Bus {
  /** 总线本身在当前网格中可解析。 */
  readonly exists: boolean;
  /** 总线在宿主上的方向（指向目标方块）。 */
  readonly targetFace: Direction;
  /** 总线面对的方块；无方块或区块未加载时为空气（minecraft:air），永不为 null。 */
  readonly target: BlockView;
  /** 目标面当前支持输入/输出的资源 channel id 列表（如 "ae2:i"、"ae2:f"），不依赖实际库存。 */
  readonly channels: readonly string[];

  /**
   * 统一查询：extract(channel?, key?, amount?)，三个参数都可选，恒返回 ResourceArray。
   * 没有满足条件的资源时返回空数组，绝不返回 null。
   * amount 省略或 -1 表示尽可能多（当前可用量），正数表示上限封顶。
   */
  extract(): ResourceArray;
  extract(channel: ResourceChannel): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound, amount: number): ResourceArray;
  /** 立即尝试精确取走物品并从总线朝向丢出，不进入调度器。 */
  drop(item: Resource): boolean;
  /** 立即空手使用目标方块；本次未成功时返回 false。 */
  use(): boolean;
  /** 立即使用来源中的一个物品；结果物品直接写回同一来源。 */
  use(item: Resource): boolean;
  /** 立即使用一个 BlockItem 放置方块；剩余物直接写回来源。 */
  place(block: Resource): boolean;
  /** 读取目标方块向总线面输出的红石等级（0-15）；总线或目标不可解析时为 0。 */
  redstone(): number;
  /** 设置总线从物理线缆面向外输出的红石等级（0-15）；总线不可解析时返回 false。 */
  redstone(level: number): boolean;
  /** 立即破坏一个方块；失败返回 null，成功返回已写回工具来源的掉落句柄。 */
  break(tool: Resource): ResourceArray | null;
}

interface Network {
  readonly side: Direction;
  readonly online: boolean;
  /** 当前拓扑快照，每次读取都重新枚举。 */
  readonly buses: readonly Bus[];

  /** 拓扑变化时同步调用 */
  onChange(callback: () => void): void;
  /**
   * 统一查询：extract(channel?, key?, amount?)，三个参数都可选，恒返回 ResourceArray。
   * 没有满足条件的资源时返回空数组，绝不返回 null。
   * amount 省略或 -1 表示尽可能多（当前可用量），正数表示上限封顶。
   */
  extract(): ResourceArray;
  extract(channel: ResourceChannel): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound, amount: number): ResourceArray;
}

type ResourceTarget = Network | Bus;

interface PatternDefinition {
  readonly orderNetwork: Direction;
  readonly inputs: readonly ResourceSpec[];
  readonly outputs: readonly ResourceSpec[];
}

/** 获取控制器对应面上的网络 */
declare function network(side: Direction): Network;
/** 获得一个SleepAction，可用于yield等待若干刻 */
declare function sleep(ticks: number): SleepAction;

/** 开始一条被动产线。被动产线不返回，一旦返回不会主动重启。
 * 与主动网络下单配合使用可以用于统一推送能源或拉取返回 */
declare function go(factory: () => Generator<Action, unknown, unknown>): void;

interface Order {
  /** 表示该订单的输入资源 */
  readonly input: ResourceArray;
  /** 下单网络 */
  readonly network: Network;
}
/** 注册若干个处理样板，handler参数为Order */
declare function registerProcessingPattern(
  patterns: readonly PatternDefinition[],
  handler: (order: Order) => Generator<Action, unknown, unknown>,
): void;

/** 把消息推送给订阅了该控制器日志的玩家，并写入服务器日志；
 * 可随时调用。
 * 在MCP执行时会抓取日志作为返回 */
declare function log(message: string): void;

/** components 是 1.21+ data component patch，而不是完整物品保存 NBT。 */
declare function item(
  id: string,
  amount: number,
  components?: NbtCompound,
): ResourceSpec;
/** key 的结构完全由对应 AEKeyType codec 定义。 */
declare function stack(
  channel: ResourceChannel,
  key: NbtCompound,
  amount: number,
): ResourceSpec;
/** 立即原位改名；不是 ae2:i 时抛出运行时错误，资源不足时返回 null。 */
declare function rename(item: Resource, name: string): Resource | null;
/** 读取完整 ItemStack 保存 NBT；不是 ae2:i 时抛出运行时错误。 */
declare function itemNbt(item: Resource): NbtCompound;

/** 处理类配方（已排除合成/切石/锻造）。json 为原始配方 JSON，自行解析取物品注册。 */
interface Recipe {
  readonly id: string;
  /** 配方类型 id，如 "minecraft:smelting"。 */
  readonly type: string;
  /** 机器方块物品 id（Recipe#getToastSymbol()），如 "minecraft:furnace"；无则 null。 */
  readonly machine: string | null;
  /** 原始配方 JSON；无法重编码时为 null。 */
  readonly json: Record<string, NbtValue> | null;
}

/** 配方索引：方块 ↔ 配方类型互查，再由类型查配方（唯一语义）。 */
interface RecipeIndex {
  /** 全部处理配方。 */
  all(): Recipe[];
  /** 按配方类型查配方，如 "minecraft:smelting"。 */
  byType(typeId: string): Recipe[];
  /** 方块可处理的配方类型，如 "minecraft:furnace" → ["minecraft:smelting"]。 */
  typesOfMachine(machineId: string): string[];
  /** 某配方类型涉及的机器方块 id。 */
  machinesOfType(typeId: string): string[];
}

/** 从服务器 RecipeManager 构建的配方索引（脚本运行时惰性构建）。 */
declare function recipes(): RecipeIndex;
