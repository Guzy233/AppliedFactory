package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryEndpoint;
import com.fulent.appliedfactory.factory.FactoryProgram;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.factory.FactoryResourceOrigin;
import com.fulent.appliedfactory.factory.FactoryResourceRef;
import com.fulent.appliedfactory.factory.FactorySleepAction;
import com.fulent.appliedfactory.factory.FactoryTransferAction;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** State shared by the small Java facade graph installed into one Rhino scope. */
final class ScriptApi {
    private final FactoryProgram.Host host;
    private final Registration registration;
    private final Scriptable scope;
    private final JsBridgeBinder binder;
    private final EnumMap<Direction, List<Function>> topologyListeners =
            new EnumMap<>(Direction.class);
    private ScriptExecutionContext activeContext;

    ScriptApi(
            FactoryProgram.Host host,
            Registration registration,
            Scriptable scope) {
        this.host = host;
        this.registration = registration;
        this.scope = scope;
        binder = new JsBridgeBinder(scope);
    }

    void install() {
        binder.installGlobals(new JsGlobals(this));
        ScriptableObject.putProperty(scope, "console", binder.wrap(new JsConsole(host)));
    }

    void bind(ScriptExecutionContext context) {
        if (activeContext != null) {
            throw new IllegalStateException("Factory script execution is already active");
        }
        activeContext = context;
    }

    void unbind() {
        activeContext = null;
    }

    Object wrap(Object value) {
        if (value instanceof FactoryResourceRef resource) {
            return binder.wrap(resourceArray(resource.origin(), resource.bundle()));
        }
        return binder.wrap(value);
    }

    Object delegate(Object value) {
        return binder.delegate(value);
    }

    Scriptable scope() {
        return scope;
    }

    FactoryProgram.Host host() {
        return host;
    }

    Registration registration() {
        return registration;
    }

    void addTopologyListener(Direction side, Function listener) {
        topologyListeners.computeIfAbsent(side, ignored -> new ArrayList<>()).add(listener);
    }

    void fireTopologyListeners() {
        var listeners = topologyListeners.values().stream()
                .flatMap(List::stream)
                .toList();
        for (var listener : listeners) {
            listener.call(Context.getCurrentContext(), scope, scope, new Object[0]);
        }
    }

    Object performNow(FactoryTransferAction action, boolean arrayResult) {
        if (activeContext == null) {
            throw Context.reportRuntimeError("Action.now() may only run inside a workflow");
        }
        var result = host.performTransfer(activeContext.workflowId(), action);
        if (action.mode() == FactoryTransferAction.Mode.EXACT) {
            return result.completed();
        }
        return result.remaining().isEmpty()
                ? null
                : arrayResult
                        ? resourceArray(action.source(), result.remaining())
                        : resourceValue(new FactoryResourceRef(
                                action.source(), result.remaining()));
    }

    /**
     * Unified extract query: {@code extract(channel?, key?, amount?)}. Always returns a
     * {@link #resourceArray(FactoryResourceOrigin, List)} (possibly empty, never null).
     * Omitting {@code amount} (or passing -1) means "as much as available"; a positive
     * amount caps the result at that quantity.
     */
    Object extractResources(
            FactoryEndpoint endpoint,
            Object rawChannel,
            Object rawKey,
            Object rawAmount) {
        var origin = FactoryResourceOrigin.endpoint(endpoint);
        if (rawChannel == Undefined.instance || rawChannel == null) {
            return resourceArray(origin, host.availableResources(endpoint));
        }
        var channel = resolveChannel(Context.toString(rawChannel));
        var snapshot = host.availableResources(endpoint);
        if (rawKey == Undefined.instance || rawKey == null) {
            return resourceArray(origin, snapshot.stream()
                    .filter(resource -> ScriptApi.channel(resource.key())
                            .equals(channel.getId().toString()))
                    .toList());
        }
        if (!(rawKey instanceof Scriptable keyObject)) {
            throw Context.reportRuntimeError("extract key must be an NBT object");
        }
        var key = channel.loadKeyFromTag(
                host.registries(),
                NbtJs.fromObject(Context.getCurrentContext(), keyObject, "key"));
        if (key == null) {
            throw Context.reportRuntimeError(
                    "Invalid key for AE resource channel " + channel.getId());
        }
        var available = snapshot.stream()
                .filter(resource -> resource.key().equals(key))
                .mapToLong(FactoryResource::amount)
                .findFirst()
                .orElse(0L);
        if (available <= 0) {
            return resourceArray(origin, List.of());
        }
        long amount = available;
        if (rawAmount != Undefined.instance && rawAmount != null) {
            var number = Context.toNumber(rawAmount);
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || Math.abs(number) > 9_007_199_254_740_991D) {
                throw Context.reportRuntimeError("Resource amount must be positive or -1");
            }
            var requested = (long) number;
            if (requested == -1) {
                // same as omitted: as much as possible
            } else if (requested <= 0) {
                throw Context.reportRuntimeError("Resource amount must be positive or -1");
            } else {
                amount = Math.min(available, requested);
            }
        }
        return resourceArray(origin, List.of(new FactoryResource(key, amount)));
    }

    /**
     * Read-only view of an endpoint's full contents, including slots that reject
     * extraction from the accessed face. Actions created from such entries wait
     * exactly like entries that do not exist; {@code channel} is optional.
     */
    Object storage(FactoryEndpoint endpoint, Object rawChannel) {
        var origin = FactoryResourceOrigin.endpoint(endpoint);
        var contents = host.storageContents(endpoint);
        if (rawChannel == Undefined.instance || rawChannel == null) {
            return resourceArray(origin, contents);
        }
        var channel = resolveChannel(Context.toString(rawChannel));
        return resourceArray(origin, contents.stream()
                .filter(resource -> ScriptApi.channel(resource.key())
                        .equals(channel.getId().toString()))
                .toList());
    }

    List<String> channels(FactoryBusAddress bus) {
        return host.channels(bus);
    }

    JsOrder order(ScriptExecutionContext context) {
        var origin = FactoryResourceOrigin.escrow(context.workflowId());
        return new JsOrder(
                orderedResourceArray(origin, context.inputs()),
                new JsNetwork(this, context.orderNetwork()));
    }

    FactoryEndpoint requireEndpoint(Object value) {
        var delegate = binder.delegate(value);
        if (delegate instanceof JsNetwork network) {
            return FactoryEndpoint.network(network.side());
        }
        if (delegate instanceof JsBus bus) {
            return FactoryEndpoint.bus(bus.address());
        }
        throw Context.reportRuntimeError("target must be a Network or Bus");
    }

    FactoryResourceRef requireResource(Object value) {
        var delegate = binder.delegate(value);
        var resource = delegate instanceof JsResource handle ? handle.resource() : null;
        if (resource == null) {
            throw Context.reportRuntimeError("resource must be a factory Resource handle");
        }
        return requireResource(resource);
    }

    FactoryResourceRef requireResource(FactoryResourceRef resource) {
        if (resource.origin().kind() == FactoryResourceOrigin.Kind.ESCROW
                && (activeContext == null
                        || !resource.origin().escrowId().equals(activeContext.workflowId()))) {
            throw Context.reportRuntimeError(
                    "An escrow Resource can only be used by the workflow that owns it");
        }
        return resource;
    }

    FactoryResourceRef requireItemResource(Object value) {
        var resource = requireResource(value);
        if (resource.bundle().size() != 1
                || !(resource.bundle().getFirst().key() instanceof AEItemKey)) {
            throw Context.reportRuntimeError("operation requires an ae2:i resource");
        }
        return resource;
    }

    JsResource resourceFacade(FactoryResourceRef resource) {
        if (resource.bundle().size() != 1) {
            throw new IllegalArgumentException("A script Resource must contain one exact AE key");
        }
        return new JsResource(this, resource);
    }

    Object resourceValue(FactoryResourceRef resource) {
        return resource.bundle().size() == 1
                ? resourceFacade(resource)
                : resourceArray(resource.origin(), resource.bundle());
    }

    Object resourceArray(
            FactoryResourceOrigin origin, List<FactoryResource> resources) {
        return resourceArray(origin, resources, false);
    }

    /**
     * Creates the order-input view. Repeated AE keys remain distinct visible
     * entries so a processing script can route each encoded input slot, while
     * the array's bulk actions still operate on the normalized total bundle.
     */
    private Object orderedResourceArray(
            FactoryResourceOrigin origin, List<FactoryResource> resources) {
        return resourceArray(origin, resources, true);
    }

    private Object resourceArray(
            FactoryResourceOrigin origin, List<FactoryResource> resources, boolean preserveEntries) {
        var normalized = FactoryResourceRef.normalize(resources);
        var visibleResources = preserveEntries
                ? resources.stream().filter(resource -> resource.amount() > 0).toList()
                : normalized;
        var values = visibleResources.stream()
                .map(resource -> binder.wrap(resourceFacade(new FactoryResourceRef(
                        origin, List.of(resource)))))
                .toArray();
        var array = (ScriptableObject) Context.getCurrentContext().newArray(scope, values);
        var methods = (Scriptable) binder.wrap(new JsResourceArray(
                this, new FactoryResourceRef(origin, normalized)));
        for (var name : List.of("to", "pushExactlyInto")) {
            array.defineProperty(
                    name,
                    ScriptableObject.getProperty(methods, name),
                    ScriptableObject.READONLY
                            | ScriptableObject.PERMANENT
                            | ScriptableObject.DONTENUM);
        }
        array.sealObject();
        return array;
    }

    JsTransferAction transfer(
            FactoryResourceRef resource,
            Object target,
            FactoryTransferAction.Mode mode,
            boolean arrayResult) {
        var usable = requireResource(resource);
        return new JsTransferAction(
                this,
                new FactoryTransferAction(
                        usable.origin(), requireEndpoint(target), usable.bundle(), mode),
                arrayResult);
    }

    Object renameItem(Object item, String name) {
        requireActiveContext("rename(item, name)");
        return host.renameItem(activeContext.workflowId(), requireItemResource(item), name)
                .map(this::resourceValue)
                .orElse(null);
    }

    Object itemNbt(Object item) {
        var resource = requireItemResource(item);
        var key = (AEItemKey) resource.bundle().getFirst().key();
        return NbtJs.toJs(
                Context.getCurrentContext(), scope,
                key.toStack(1).save(host.registries()));
    }

    boolean dropItem(com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object item) {
        requireActiveContext("bus.drop(item)");
        return host.dropItem(activeContext.workflowId(), bus, requireItemResource(item));
    }

    boolean useItem(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object item, Object rawShift) {
        requireActiveContext("bus.use(item)");
        var emptyHand = item == Undefined.instance || item == null || item instanceof Boolean;
        var shift = optionalBoolean(item instanceof Boolean ? item : rawShift, "bus.use shift");
        if (emptyHand) {
            return host.use(activeContext.workflowId(), bus, shift);
        }
        return host.use(
                activeContext.workflowId(), bus, requireItemResource(item), shift);
    }

    boolean placeItem(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus,
            Object item,
            Object rawShift) {
        requireActiveContext("bus.place(item)");
        return host.place(
                activeContext.workflowId(), bus, requireItemResource(item),
                optionalBoolean(rawShift, "bus.place shift"));
    }

    private static boolean optionalBoolean(Object value, String name) {
        if (value == Undefined.instance || value == null) {
            return false;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw Context.reportRuntimeError(name + " must be a boolean");
        }
        return booleanValue;
    }

    Object breakBlock(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object tool) {
        requireActiveContext("bus.break(tool)");
        return host.breakBlock(
                        activeContext.workflowId(), bus, requireItemResource(tool))
                .map(result -> resourceArray(result.origin(), result.bundle()))
                .orElse(null);
    }

    Object busRedstone(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object level) {
        if (level == Undefined.instance || level == null) {
            return host.busRedstoneLevel(bus);
        }
        var number = Context.toNumber(level);
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < 0 || number > 15) {
            throw Context.reportRuntimeError(
                    "bus.redstone(level) requires an integer level between 0 and 15");
        }
        return host.setBusRedstoneOutput(bus, (int) number);
    }

    private void requireActiveContext(String operation) {
        if (activeContext == null) {
            throw Context.reportRuntimeError(operation + " may only run inside a workflow");
        }
    }

    static String channel(AEKey key) {
        return key.getType().getId().toString();
    }

    static AEKeyType resolveChannel(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw Context.reportRuntimeError("Invalid AE resource channel id: " + value);
        }
        try {
            return AEKeyTypes.get(id);
        } catch (IllegalArgumentException exception) {
            throw Context.reportRuntimeError("Unknown AE resource channel: " + value);
        }
    }

    CompoundTag optionalNbt(Object value, String name) {
        if (value == Undefined.instance || value == null) {
            return null;
        }
        if (!(value instanceof Scriptable object)) {
            throw Context.reportRuntimeError(name + " must be an NBT object");
        }
        return NbtJs.fromObject(Context.getCurrentContext(), object, name);
    }

    static Direction direction(String value) {
        var side = Direction.byName(value);
        if (side == null) {
            throw Context.reportRuntimeError("Invalid direction: " + value);
        }
        return side;
    }

    Direction networkSide(String value) {
        var absolute = Direction.byName(value);
        if (absolute != null) {
            return absolute;
        }
        var front = host.controllerFacing();
        return switch (value) {
            case "front" -> front;
            case "back" -> front.getOpposite();
            case "left" -> front.getCounterClockWise();
            case "right" -> front.getClockWise();
            default -> throw Context.reportRuntimeError("Invalid network side: " + value);
        };
    }

    static Object required(Scriptable object, String name) {
        var value = ScriptableObject.getProperty(object, name);
        if (value == Scriptable.NOT_FOUND || value == Undefined.instance) {
            throw Context.reportRuntimeError("Missing property: " + name);
        }
        return value;
    }
}

@JsBridge
final class JsGlobals {    private final ScriptApi api;

    JsGlobals(ScriptApi api) {
        this.api = api;
    }

    public JsNetwork network(String side) {
        return new JsNetwork(api, api.networkSide(side));
    }

    public JsSleepAction sleep(int ticks) {
        return new JsSleepAction(new FactorySleepAction(ticks));
    }

    public Object go(Function factory) {
        api.registration().requireOpen();
        api.registration().passiveHandlers.add(factory);
        return Undefined.instance;
    }

    public Object registerProcessingPattern(Object definitions, Function handler) {
        api.registration().requireOpen();
        if (!(definitions instanceof NativeArray array)) {
            throw Context.reportRuntimeError("registerProcessingPattern requires an array");
        }
        var handlerIndex = api.registration().patternHandlers.size();
        api.registration().patternHandlers.add(handler);
        for (long index = 0; index < array.getLength(); index++) {
            var value = array.get((int) index, array);
            if (!(value instanceof Scriptable definition)) {
                throw Context.reportRuntimeError("Pattern definition must be an object");
            }
            var side = api.networkSide(Context.toString(
                    ScriptApi.required(definition, "orderNetwork")));
            var inputs = specs(ScriptApi.required(definition, "inputs"), "inputs");
            var outputs = specs(ScriptApi.required(definition, "outputs"), "outputs");
            if (inputs.isEmpty() || outputs.isEmpty()) {
                throw Context.reportRuntimeError("Processing patterns require inputs and outputs");
            }
            var encoded = PatternDetailsHelper.encodeProcessingPattern(
                    genericStacks(inputs), genericStacks(outputs));
            api.registration().patterns.add(new CompiledControllerProgram.ScriptPattern(
                    side, encoded, handlerIndex));
        }
        return Undefined.instance;
    }

    public Object rename(Object item, String name) {
        return api.renameItem(item, name);
    }

    /** Prints a message to this controller's log subscribers (chat) and the server log. */
    public Object log(String message) {
        api.host().log(message);
        return Undefined.instance;
    }

    public Object itemNbt(Object item) {
        return api.itemNbt(item);
    }

    public JsResourceSpec item(String id, long amount, Object components) {
        var resourceId = ResourceLocation.tryParse(id);
        if (resourceId == null) {
            throw Context.reportRuntimeError("Invalid item id: " + id);
        }
        var key = new CompoundTag();
        key.putString("id", resourceId.toString());
        var componentPatch = api.optionalNbt(components, "components");
        if (componentPatch != null) {
            key.put("components", componentPatch);
        }
        return spec(AEKeyType.items(), key, amount);
    }

    public JsResourceSpec stack(String channel, Object rawKey, long amount) {
        if (!(rawKey instanceof Scriptable keyObject)) {
            throw Context.reportRuntimeError("stack key must be an NBT object");
        }
        return spec(
                ScriptApi.resolveChannel(channel),
                NbtJs.fromObject(Context.getCurrentContext(), keyObject, "key"),
                amount);
    }

    private JsResourceSpec spec(AEKeyType channel, CompoundTag keyTag, long amount) {
        requireAmount(amount);
        var key = channel.loadKeyFromTag(api.host().registries(), keyTag);
        if (key == null) {
            throw Context.reportRuntimeError(
                    "Invalid key for AE resource channel " + channel.getId());
        }
        return new JsResourceSpec(api, key, amount);
    }

    private List<FactoryResource> specs(Object value, String name) {
        if (!(value instanceof NativeArray array)) {
            throw Context.reportRuntimeError(name + " must be an array");
        }
        var result = new ArrayList<FactoryResource>();
        for (long index = 0; index < array.getLength(); index++) {
            result.add(spec(array.get((int) index, array), name));
        }
        return FactoryResourceRef.normalize(result);
    }

    /**
     * Accepts either a {@code stack()}/{@code item()} spec handle or a plain
     * {@code {channel, key, amount}} object literal (the same shape the recipe
     * reference exports and {@code Recipe.inputs}/{@code Recipe.outputs} use), so
     * baked recipe globals can be referenced at registration directly.
     */
    private FactoryResource spec(Object raw, String name) {
        var delegate = api.delegate(raw);
        if (delegate instanceof JsResourceSpec spec) {
            if (spec.amount() <= 0) {
                throw Context.reportRuntimeError(name + " requires exact positive resource specs");
            }
            return new FactoryResource(spec.key(), spec.amount());
        }
        if (raw instanceof Scriptable object) {
            var rawChannel = ScriptableObject.getProperty(object, "channel");
            if (rawChannel != Scriptable.NOT_FOUND && rawChannel != Undefined.instance) {
                var rawKey = ScriptableObject.getProperty(object, "key");
                var rawAmount = ScriptableObject.getProperty(object, "amount");
                if (rawKey instanceof Scriptable keyObject
                        && rawAmount != Scriptable.NOT_FOUND && rawAmount != Undefined.instance) {
                    var channel = ScriptApi.resolveChannel(Context.toString(rawChannel));
                    var key = channel.loadKeyFromTag(
                            api.host().registries(),
                            NbtJs.fromObject(Context.getCurrentContext(), keyObject, name + ".key"));
                    if (key == null) {
                        throw Context.reportRuntimeError(
                                name + " has an invalid key for channel " + channel.getId());
                    }
                    var amount = Context.toNumber(rawAmount);
                    if (!Double.isFinite(amount) || amount != Math.rint(amount)
                            || amount <= 0) {
                        throw Context.reportRuntimeError(
                                name + " requires exact positive resource amounts");
                    }
                    return new FactoryResource(key, (long) amount);
                }
            }
        }
        throw Context.reportRuntimeError(name + " requires resource specs");
    }

    private static List<GenericStack> genericStacks(List<FactoryResource> resources) {
        return resources.stream()
                .map(resource -> new GenericStack(resource.key(), resource.amount()))
                .toList();
    }

    private static void requireAmount(long amount) {
        if (amount != -1 && amount <= 0) {
            throw Context.reportRuntimeError("Resource amount must be positive or -1");
        }
    }
}

@JsBridge
final class JsResourceSpec {
    private final ScriptApi api;
    private final AEKey key;
    private final long amount;

    JsResourceSpec(ScriptApi api, AEKey key, long amount) {
        this.api = api;
        this.key = key;
        this.amount = amount;
    }

    AEKey key() {
        return key;
    }

    long amount() {
        return amount;
    }

    @JsProperty
    public String getChannel() {
        return ScriptApi.channel(key);
    }

    @JsProperty
    public Object getKey() {
        return NbtJs.toJs(
                Context.getCurrentContext(), api.scope(),
                key.toTag(api.host().registries()));
    }

    @JsProperty
    public double getAmount() {
        return amount;
    }
}

@JsBridge
final class JsNetwork {
    private final ScriptApi api;
    private final Direction side;

    JsNetwork(ScriptApi api, Direction side) {
        this.api = api;
        this.side = side;
    }

    Direction side() {
        return side;
    }

    @JsProperty
    public String getSide() {
        return side.getName();
    }

    @JsProperty
    public boolean isOnline() {
        return api.host().onlineNetworks().contains(side);
    }

    @JsProperty
    public List<JsBus> getBuses() {
        return api.host().busAddressesByNetwork().getOrDefault(side, List.of()).stream()
                .map(address -> new JsBus(api, address))
                .toList();
    }

    public Object onChange(Function callback) {
        api.addTopologyListener(side, callback);
        return Undefined.instance;
    }

    /** Compares the live AE grid objects; disconnected sides never compare equal. */
    public boolean isSameNetwork(JsNetwork other) {
        return api.host().isSameNetwork(side, other.side);
    }

    public Object extract(Object channel, Object key, Object amount) {
        return api.extractResources(FactoryEndpoint.network(side), channel, key, amount);
    }

    public Object storage(Object channel) {
        return api.storage(FactoryEndpoint.network(side), channel);
    }
}

@JsBridge
final class JsBus {
    private final ScriptApi api;
    private final com.fulent.appliedfactory.factory.FactoryBusAddress address;

    JsBus(
            ScriptApi api,
            com.fulent.appliedfactory.factory.FactoryBusAddress address) {
        this.api = api;
        this.address = address;
    }

    com.fulent.appliedfactory.factory.FactoryBusAddress address() {
        return address;
    }

    @JsProperty
    public boolean isExists() {
        return api.host().busTarget(address).isPresent();
    }

    @JsProperty
    public String getTargetFace() {
        return address.side().getOpposite().getName();
    }

    @JsProperty
    public List<String> getChannels() {
        return api.channels(address);
    }

    @JsProperty
    public JsBlockView getTarget() {
        var target = api.host().busTarget(address).orElse(null);
        var position = address.hostPosition().relative(address.side());
        if (target == null || !target.isLoaded()) {
            return new JsBlockView(
                    api, "minecraft:air", "minecraft:air",
                    position.getX(), position.getY(), position.getZ(), Map.of(), null, null);
        }
        var state = target.blockState();
        var blockEntityType = target.blockEntityTypeId();
        return new JsBlockView(
                api, target.blockId().toString(), state.toString(),
                position.getX(), position.getY(), position.getZ(),
                blockStateProperties(state),
                blockEntityType == null ? null : blockEntityType.toString(),
                target.blockEntityNbt());
    }

    private static Map<String, Object> blockStateProperties(BlockState state) {
        var result = new LinkedHashMap<String, Object>();
        state.getValues().forEach((property, value) ->
                result.put(property.getName(), exposePropertyValue(value)));
        return result;
    }

    private static Object exposePropertyValue(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value.toString();
    }

    public Object extract(Object channel, Object key, Object amount) {
        return api.extractResources(FactoryEndpoint.bus(address), channel, key, amount);
    }

    public Object storage(Object channel) {
        return api.storage(FactoryEndpoint.bus(address), channel);
    }

    public boolean drop(Object item) {
        return api.dropItem(address, item);
    }

    public boolean use(Object resource, Object shift) {
        return api.useItem(address, resource, shift);
    }

    public boolean place(Object resource, Object shift) {
        return api.placeItem(address, resource, shift);
    }

    /** JS name is {@code break}; the Java name stays breakBlock because break is a keyword. */
    @JsName("break")
    public Object breakBlock(Object tool) {
        return api.breakBlock(address, tool);
    }

    /** Reads (no args) or sets (with a 0-15 level) this bus's redstone. */
    public Object redstone(Object level) {
        return api.busRedstone(address, level);
    }
}

@JsBridge
final class JsBlockView {
    private final ScriptApi api;
    private final String id;
    private final String state;
    private final int x;
    private final int y;
    private final int z;
    private final Map<String, Object> properties;
    private final String blockEntityType;
    private final CompoundTag nbt;

    JsBlockView(
            ScriptApi api,
            String id,
            String state,
            int x,
            int y,
            int z,
            Map<String, Object> properties,
            String blockEntityType,
            CompoundTag nbt) {
        this.api = api;
        this.id = id;
        this.state = state;
        this.x = x;
        this.y = y;
        this.z = z;
        this.properties = Map.copyOf(properties);
        this.blockEntityType = blockEntityType;
        this.nbt = nbt == null ? null : nbt.copy();
    }

    @JsProperty
    public String getId() {
        return id;
    }

    @JsProperty
    public String getState() {
        return state;
    }

    @JsProperty
    public int getX() {
        return x;
    }

    @JsProperty
    public int getY() {
        return y;
    }

    @JsProperty
    public int getZ() {
        return z;
    }

    @JsProperty
    public Map<String, Object> getProperties() {
        return properties;
    }

    @JsProperty
    public String getBlockEntityType() {
        return blockEntityType;
    }

    @JsProperty
    public Object getNbt() {
        return nbt == null
                ? null
                : NbtJs.toJs(Context.getCurrentContext(), api.scope(), nbt);
    }

    public boolean isSameBlock(JsBlockView other) {
        return x == other.x && y == other.y && z == other.z;
    }
}

@JsBridge
final class JsResource {
    private final ScriptApi api;
    private final FactoryResourceRef resource;

    JsResource(ScriptApi api, FactoryResourceRef resource) {
        this.api = api;
        this.resource = resource;
    }

    FactoryResourceRef resource() {
        return resource;
    }

    @JsProperty
    public JsResourceOrigin getOrigin() {
        return new JsResourceOrigin(api, resource.origin());
    }

    @JsProperty
    public String getChannel() {
        return ScriptApi.channel(resource.bundle().getFirst().key());
    }

    @JsProperty
    public String getId() {
        return resource.bundle().getFirst().id().toString();
    }

    @JsProperty
    public double getAmount() {
        return resource.bundle().getFirst().amount();
    }

    @JsProperty
    public Object getKey() {
        return NbtJs.toJs(
                Context.getCurrentContext(), api.scope(),
                resource.bundle().getFirst().key().toTag(api.host().registries()));
    }

    public JsTransferAction to(Object target) {
        return api.transfer(resource, target, FactoryTransferAction.Mode.PARTIAL, false);
    }

    public JsTransferAction pushExactlyInto(Object target) {
        return api.transfer(resource, target, FactoryTransferAction.Mode.EXACT, false);
    }
}

@JsBridge
final class JsResourceArray {
    private final ScriptApi api;
    private final FactoryResourceRef resources;

    JsResourceArray(ScriptApi api, FactoryResourceRef resources) {
        this.api = api;
        this.resources = resources;
    }

    public JsTransferAction to(Object target) {
        return api.transfer(resources, target, FactoryTransferAction.Mode.PARTIAL, true);
    }

    public JsTransferAction pushExactlyInto(Object target) {
        return api.transfer(resources, target, FactoryTransferAction.Mode.EXACT, true);
    }
}

@JsBridge
final class JsResourceOrigin {
    private final ScriptApi api;
    private final FactoryResourceOrigin origin;

    JsResourceOrigin(ScriptApi api, FactoryResourceOrigin origin) {
        this.api = api;
        this.origin = origin;
    }

    @JsProperty
    public String getKind() {
        if (origin.kind() == FactoryResourceOrigin.Kind.ESCROW) {
            return "escrow";
        }
        return origin.endpoint().kind() == FactoryEndpoint.Kind.NETWORK
                ? "network"
                : "bus";
    }

    @JsProperty
    public Object getEndpoint() {
        if (origin.kind() == FactoryResourceOrigin.Kind.ESCROW) {
            return null;
        }
        var endpoint = origin.endpoint();
        return endpoint.kind() == FactoryEndpoint.Kind.NETWORK
                ? new JsNetwork(api, endpoint.networkSide())
                : new JsBus(api, endpoint.bus());
    }
}

@JsBridge
final class JsOrder {
    private final Object input;
    private final JsNetwork network;

    JsOrder(Object input, JsNetwork network) {
        this.input = input;
        this.network = network;
    }

    @JsProperty
    public Object getInput() {
        return input;
    }

    @JsProperty
    public JsNetwork getNetwork() {
        return network;
    }
}

/** {@code console.log/warn/error} convenience mirror of the {@code log()} global. */
@JsBridge
final class JsConsole {
    private final FactoryProgram.Host host;

    JsConsole(FactoryProgram.Host host) {
        this.host = host;
    }

    public Object log(String message) {
        host.log(message);
        return Undefined.instance;
    }

    public Object warn(String message) {
        host.log(message);
        return Undefined.instance;
    }

    public Object error(String message) {
        host.log(message);
        return Undefined.instance;
    }
}
