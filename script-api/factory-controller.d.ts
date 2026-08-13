/** IDE-only declarations for the Applied Factory MVP global script API. */

type Direction = "up" | "down" | "north" | "south" | "west" | "east";
type Action = SleepAction | TransferAction;

interface ResourceSpec {
    readonly id: string;
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

    /** Moves any currently possible portion and waits until the entire bundle has moved. */
    to(target: ResourceTarget): TransferAction;
    /** Waits until the complete bundle can move atomically. */
    pushExactlyInto(target: ResourceTarget): TransferAction;
}

interface ResourceOrigin {
    readonly kind: "network" | "bus" | "order";
    readonly endpoint: Network | Bus | null;
}

interface TransferAction {
    /** PARTIAL returns a remaining Resource; EXACT returns whether the one attempt succeeded. */
    now(): Resource | boolean;
}

interface SleepAction {}

interface BlockView {
    readonly id: string;
}

interface Bus {
    readonly exists: boolean;
    readonly targetFace: Direction;
    readonly target: BlockView;

    /** Captures the source's current concrete bundle without removing it. */
    extract(spec?: ResourceSpec): Resource;
}

interface Network {
    readonly side: Direction;
    readonly online: boolean;
    readonly buses: readonly Bus[];

    onChange(callback: () => void): void;
    /** Captures the source's current concrete bundle without removing it. */
    extract(spec?: ResourceSpec): Resource;
}

type ResourceTarget = Network | Bus;

interface Order {
    /** Input is isolated in this order's invisible controller escrow. */
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
