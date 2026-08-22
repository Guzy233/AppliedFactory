/**
 * Applied Factory controller scripting API.
 *
 * This file is the authoritative type and signature reference. See SCRIPT_API.md
 * for the execution model and behavioral details.
 */

type Direction = "up" | "down" | "north" | "south" | "west" | "east";
type RelativeDirection = "front" | "back" | "left" | "right";
type NetworkSide = Direction | RelativeDirection;
type NbtValue =
  | string
  | number
  | boolean
  | readonly NbtValue[]
  | Readonly<Record<string, NbtValue>>
  | null;
type NbtCompound = Readonly<Record<string, NbtValue>>;

/** Registered AEKeyType ID, such as "ae2:i" for items or "ae2:f" for fluids. */
type ResourceChannel = string;

type Action = SleepAction | TransferAction<unknown>;

/** An AE key and amount specification without a movable source. */
interface ResourceSpec {
  readonly channel: string;
  readonly key: NbtCompound;
  readonly amount: number;
}

interface Resource {
  readonly channel: string;
  readonly key: NbtCompound;
  readonly id: string;
  readonly amount: number;
  readonly origin: ResourceOrigin;

  /** Moves all possible amounts over one or more attempts. */
  to(target: ResourceTarget): TransferAction<Resource | null>;
  /** Moves only when the complete amount can be transferred atomically. */
  pushExactlyInto(target: ResourceTarget): TransferAction<boolean>;
}

interface ResourceArray extends ReadonlyArray<Resource> {
  /** Independently advances every resource; now() returns the remaining bundle. */
  to(target: ResourceTarget): TransferAction<ResourceArray | null>;
  /** Transfers the entire array as one atomic batch. */
  pushExactlyInto(target: ResourceTarget): TransferAction<boolean>;
}

interface ResourceOrigin {
  readonly kind: "network" | "bus" | "escrow";
  readonly endpoint: Network | Bus | null;
}

interface TransferAction<TResult> {
  /** Performs one immediate attempt without waiting. */
  now(): TResult;
}

interface SleepAction {}

interface BlockView {
  readonly id: string;
  readonly state: string;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly properties: Readonly<Record<string, boolean | number | string>>;
  readonly blockEntityType: string | null;
  readonly nbt: Readonly<Record<string, NbtValue>> | null;

  isSameBlock(other: BlockView): boolean;
}

interface Bus {
  /** Whether this bus can currently be resolved on its grid. */
  readonly exists: boolean;
  /** Direction from the cable host toward the target block. */
  readonly targetFace: Direction;
  /** Target block, or an air view when unavailable. */
  readonly target: BlockView;
  /** Resource channels supported by the target face. */
  readonly channels: readonly string[];

  extract(): ResourceArray;
  extract(channel: ResourceChannel): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound, amount: number): ResourceArray;
  storage(): ResourceArray;
  storage(channel: ResourceChannel): ResourceArray;
  drop(item: Resource): boolean;
  use(): boolean;
  use(shift: boolean): boolean;
  use(item: Resource, shift?: boolean): boolean;
  place(block: Resource, shift?: boolean): boolean;
  redstone(): number;
  redstone(level: number): boolean;
  break(tool: Resource): ResourceArray | null;
}

interface Network {
  readonly side: Direction;
  readonly online: boolean;
  readonly buses: readonly Bus[];

  onChange(callback: () => void): void;
  isSameNetwork(other: Network): boolean;
  extract(): ResourceArray;
  extract(channel: ResourceChannel): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound): ResourceArray;
  extract(channel: ResourceChannel, key: NbtCompound, amount: number): ResourceArray;
  storage(): ResourceArray;
  storage(channel: ResourceChannel): ResourceArray;
}

type ResourceTarget = Network | Bus;

interface PatternDefinition {
  readonly orderNetwork: NetworkSide;
  readonly inputs: readonly ResourceSpec[];
  readonly outputs: readonly ResourceSpec[];
}

/** Returns the network attached to a controller side. */
declare function network(side: NetworkSide): Network;
declare function sleep(ticks: number): SleepAction;

/** Starts a passive generator workflow. */
declare function go(factory: () => Generator<Action, unknown, unknown>): void;

interface Order {
  readonly input: ResourceArray;
  readonly network: Network;
}

declare function registerProcessingPattern(
  patterns: readonly PatternDefinition[],
  handler: (order: Order) => Generator<Action, unknown, unknown>,
): void;

declare function log(message: string): void;

/** components is a Minecraft 1.21 data component patch. */
declare function item(
  id: string,
  amount: number,
  components?: NbtCompound,
): ResourceSpec;

/** key is decoded by the selected AEKeyType codec. */
declare function stack(
  channel: ResourceChannel,
  key: NbtCompound,
  amount: number,
): ResourceSpec;

declare function rename(item: Resource, name: string): Resource | null;
declare function itemNbt(item: Resource): NbtCompound;

interface RecipeInput extends ResourceSpec {
  /** Alternatives accepted by a tag or choice ingredient slot. */
  readonly options?: readonly ResourceSpec[];
}

interface Recipe {
  readonly id: string;
  readonly type: string;
  readonly inputs: readonly RecipeInput[];
  readonly outputs: readonly ResourceSpec[];
  readonly json: Record<string, NbtValue> | null;
}

interface RecipeFilter {
  readonly id?: string | readonly string[];
  readonly type?: string | readonly string[];
  readonly machine?: string | readonly string[];
  readonly input?: string | readonly string[];
  readonly output?: string | readonly string[];
}

/**
 * Client-side macro expanded from processing_recipes.json before upload.
 * Fields are combined with AND; arrays within one field use any-of matching.
 */
declare function require_recipes(filter?: RecipeFilter): readonly Recipe[];
