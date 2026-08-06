/**
 * IDE-only declarations for the Factory Controller script API.
 *
 * This file is never loaded by Minecraft or Rhino. Keep runtime scripts free of
 * `import` / `export`; the controller evaluates their compiled JavaScript as a global script.
 */

type Direction = "up" | "down" | "north" | "south" | "west" | "east";

interface Resource {
    readonly id: string;
    readonly amount: number;

    matches(selector: string): boolean;
}

/** A resource currently owned by this workflow and therefore valid for `push`. */
interface OwnedResource extends Resource {
}

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

interface BlockView {
    readonly id: string;
    readonly state: Readonly<Record<string, string>>;
    readonly blockEntityType: string | null;

    matches(selector: string): boolean;
}

interface BusState {
    readonly active: boolean;
    readonly powered: boolean;
    readonly redstone: number;
    readonly upgrades: Readonly<Record<string, number>>;
    readonly config: Readonly<Record<string, string | number | boolean | null>>;
}

interface BusItems {
    read(): readonly Resource[];
    push(resources: OwnedResource | readonly OwnedResource[]): boolean;
    extract(): readonly OwnedResource[];
}

interface Bus {
    readonly address: FactoryBusAddress;
    readonly targetAddress: BlockAddress;
    readonly targetFace: Direction;

    exists(): boolean;
    state(): BusState | null;
    target(): BlockView | null;
    items(): BusItems | null;
    detect(selector: string): boolean;

    /** Releases the complete owned resource list as item entities from this bus. */
    drop(resources: OwnedResource | readonly OwnedResource[]): boolean;
    /** Uses the target with an empty-handed MFM fake player. */
    use(): boolean;
    /** Places one owned block item at an empty target position. */
    place(resource: OwnedResource): boolean;
    /** Breaks the target and returns its normal drops as owned resources. */
    "break"(): readonly OwnedResource[];
    /** Sets this bus's physical redstone output to an exact strength from 0 through 15. */
    redstone(level: number): boolean;
}

interface NetworkItems {
    push(resources: OwnedResource | readonly OwnedResource[]): boolean;
    extract(requests: Resource | readonly Resource[]): readonly OwnedResource[];
}

interface Network {
    readonly side: Direction;
    readonly buses: readonly Bus[];

    online(): boolean;
    items(): NetworkItems;
}

interface BaseContext {
    readonly tick: number;
    readonly buses: readonly Bus[];

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
    readonly owned: readonly OwnedResource[];
}

interface PassiveContext extends BaseContext {
    readonly owned: readonly OwnedResource[];
}

interface InitializeContext {
    readonly tick: number;
    readonly buses: readonly Bus[];

    network(side: Direction): Network;
    log(message: string): void;
}

interface InitializerDefinition {
    readonly networks: readonly Direction[];
    readonly handler: (ctx: InitializeContext) => void;
}

interface ScriptPatternDefinition {
    readonly id: string;
    readonly inputs: readonly Resource[];
    readonly outputs: readonly Resource[];
    readonly handler: (ctx: ProcessingContext) => void;
}

interface PatternRegistration {
    readonly orderNetwork: Direction;
    readonly patterns: readonly ScriptPatternDefinition[];
}

interface ControllerHandlerDefinition {
    readonly orderNetwork: Direction;
    readonly handler: (ctx: ProcessingContext) => void;
}

declare function item(id: string, amount: number): Resource;
declare function initialize(definition: InitializerDefinition): void;
declare function registerPatterns(definition: PatternRegistration): void;
declare function registerControllerHandler(definition: ControllerHandlerDefinition): void;
declare function registerPassive(handler: (ctx: PassiveContext) => void): void;
