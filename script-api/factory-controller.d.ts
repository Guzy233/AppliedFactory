/**
 * Applied Factory 脚本 API —— 权威类型与签名参考。
 *
 * 类型和签名以此文件为准，docs/SCRIPT_API.md 只描述运行模型与行为语义。
 * 控制器脚本在 Rhino ES6 编译模式下于控制器加载时求值一次：脚本用全局函数
 * 取得句柄、注册 processing pattern，并通过 `go` 启动被动 generator workflow。
 */

type Direction = "up" | "down" | "north" | "south" | "west" | "east";
type Action = SleepAction | TransferAction;

interface ResourceSpec {
    readonly id: string;
    /** 数量必须为正数，或 `-1`（仅在 extract 中表示按调用时的可用量解析）。 */
    readonly amount: number;
}

interface ResourceAmount {
    readonly id: string;
    readonly amount: number;
}

interface Resource {
    readonly origin: ResourceOrigin;
    readonly empty: boolean;
    readonly bundle: readonly ResourceAmount[];

    /** 部分转移：每次移动当前可行的部分，直到整个 bundle 全部移走。 */
    to(target: ResourceTarget): TransferAction;
    /** 精确转移：等待来源拥有完整 bundle 且目标能一次性接收时才原子移动。 */
    pushExactlyInto(target: ResourceTarget): TransferAction;
}

interface ResourceOrigin {
    readonly kind: "network" | "bus" | "order";
    readonly endpoint: Network | Bus | null;
}

interface TransferAction {
    /** PARTIAL 返回尚未移走的 Resource；EXACT 返回当次尝试是否成功。 */
    now(): Resource | boolean;
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

    /** 读取来源当前可用的资源，不提取、不锁定。 */
    extract(spec?: ResourceSpec): Resource;
}

interface Network {
    readonly side: Direction;
    readonly online: boolean;
    /** 当前拓扑快照，每次读取都重新枚举。 */
    readonly buses: readonly Bus[];

    /** 拓扑变化时同步调用（回调内不可 yield）。 */
    onChange(callback: () => void): void;
    /** 读取网络当前可用的资源，不提取、不锁定。 */
    extract(spec?: ResourceSpec): Resource;
}

type ResourceTarget = Network | Bus;

interface Order {
    /** 输入被隔离在本订单的控制器隐形托管区。 */
    readonly input: Resource;
    readonly network: Network;
}

interface PatternDefinition {
    readonly orderNetwork: Direction;
    readonly inputs: readonly ResourceSpec[];
    readonly outputs: readonly ResourceSpec[];
}

declare function network(side: Direction): Network;
declare function sleep(ticks: number): SleepAction;
declare function go(factory: () => Generator<Action, unknown, unknown>): void;
declare function registerProcessingPattern(
    patterns: readonly PatternDefinition[],
    handler: (order: Order) => Generator<Action, unknown, unknown>
): void;

declare function item(id: string, amount: number): ResourceSpec;
declare function stack(channel: string, id: string, amount: number): ResourceSpec;
declare function stackTag(serializedKey: string, amount: number): ResourceSpec;
