package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

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

/** State shared by the Java facade graph installed into one GraalJS context. */
final class ScriptApi {
    private final FactoryProgram.Host host;
    private final Registration registration;
    private final JsBridgeBinder binder;
    private final EnumMap<Direction, List<Value>> topologyListeners = new EnumMap<>(Direction.class);
    private ScriptExecutionContext activeContext;

    ScriptApi(
            FactoryProgram.Host host,
            Registration registration,
            Context context) {
        this.host = host;
        this.registration = registration;
        binder = new JsBridgeBinder(context);
    }

    void install() {
        binder.installGlobals(new JsGlobals(this));
        binder.installGlobal("console", new JsConsole(host));
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

    FactoryProgram.Host host() {
        return host;
    }

    Registration registration() {
        return registration;
    }

    void addTopologyListener(Direction side, Value listener) {
        topologyListeners.computeIfAbsent(side, ignored -> new ArrayList<>()).add(listener);
    }

    void fireTopologyListeners() {
        var listeners = topologyListeners.values().stream()
                .flatMap(List::stream)
                .toList();
        for (var listener : listeners) {
            listener.execute();
        }
    }

    Object performNow(FactoryTransferAction action, boolean arrayResult) {
        if (activeContext == null) {
            throw JsValues.error("Action.now() may only run inside a workflow");
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
     * Unified extract query: {@code extract(channel?, key?, amount?)}. Always
     * returns a
     * {@link #resourceArray(FactoryResourceOrigin, List)} (possibly empty, never
     * null).
     * Omitting {@code amount} (or passing -1) means "as much as available"; a
     * positive
     * amount caps the result at that quantity.
     */
    Object extractResources(
            FactoryEndpoint endpoint,
            Object rawChannel,
            Object rawKey,
            Object rawAmount) {
        var origin = FactoryResourceOrigin.endpoint(endpoint);
        if (JsValues.isNullish(rawChannel)) {
            return resourceArray(origin, host.availableResources(endpoint));
        }
        var channel = resolveChannel(JsValues.string(rawChannel));
        var snapshot = host.availableResources(endpoint, channel);
        if (JsValues.isNullish(rawKey)) {
            return resourceArray(origin, snapshot);
        }
        var keyObject = JsValues.object(rawKey, "extract key");
        var key = channel.loadKeyFromTag(
                host.registries(),
                NbtJs.fromObject(keyObject, "key"));
        if (key == null) {
            throw JsValues.error(
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
        if (!JsValues.isNullish(rawAmount)) {
            var number = JsValues.number(rawAmount, "Resource amount");
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || Math.abs(number) > 9_007_199_254_740_991D) {
                throw JsValues.error("Resource amount must be positive or -1");
            }
            var requested = (long) number;
            if (requested == -1) {
                // same as omitted: as much as possible
            } else if (requested <= 0) {
                throw JsValues.error("Resource amount must be positive or -1");
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
        if (JsValues.isNullish(rawChannel)) {
            return resourceArray(origin, host.storageContents(endpoint));
        }
        var channel = resolveChannel(JsValues.string(rawChannel));
        return resourceArray(origin, host.storageContents(endpoint, channel));
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
        throw JsValues.error("target must be a Network or Bus");
    }

    FactoryResourceRef requireResource(Object value) {
        var delegate = binder.delegate(value);
        var resource = delegate instanceof JsResource handle ? handle.resource() : null;
        if (resource == null) {
            throw JsValues.error("resource must be a factory Resource handle");
        }
        return requireResource(resource);
    }

    FactoryResourceRef requireResource(FactoryResourceRef resource) {
        if (resource.origin().kind() == FactoryResourceOrigin.Kind.ESCROW
                && (activeContext == null
                        || !resource.origin().escrowId().equals(activeContext.workflowId()))) {
            throw JsValues.error(
                    "An escrow Resource can only be used by the workflow that owns it");
        }
        return resource;
    }

    FactoryResourceRef requireItemResource(Object value) {
        var resource = requireResource(value);
        if (resource.bundle().size() != 1
                || !(resource.bundle().getFirst().key() instanceof AEItemKey)) {
            throw JsValues.error("operation requires an ae2:i resource");
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
        return binder.arrayWithMethods(values, new JsResourceArray(
                this, new FactoryResourceRef(origin, normalized)));
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
        return NbtJs.toJs(key.toStack(1).save(host.registries()));
    }

    boolean dropItem(com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object item) {
        requireActiveContext("bus.drop(item)");
        return host.dropItem(activeContext.workflowId(), bus, requireItemResource(item));
    }

    boolean useItem(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object item, Object rawShift) {
        requireActiveContext("bus.use(item)");
        var emptyHand = JsValues.isNullish(item) || item instanceof Boolean;
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
        if (JsValues.isNullish(value)) {
            return false;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw JsValues.error(name + " must be a boolean");
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
        if (JsValues.isNullish(level)) {
            return host.busRedstoneLevel(bus);
        }
        var number = JsValues.number(level, "bus.redstone level");
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < 0 || number > 15) {
            throw JsValues.error(
                    "bus.redstone(level) requires an integer level between 0 and 15");
        }
        return host.setBusRedstoneOutput(bus, (int) number);
    }

    private void requireActiveContext(String operation) {
        if (activeContext == null) {
            throw JsValues.error(operation + " may only run inside a workflow");
        }
    }

    static String channel(AEKey key) {
        return key.getType().getId().toString();
    }

    static AEKeyType resolveChannel(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw JsValues.error("Invalid AE resource channel id: " + value);
        }
        try {
            return AEKeyTypes.get(id);
        } catch (IllegalArgumentException exception) {
            throw JsValues.error("Unknown AE resource channel: " + value);
        }
    }

    CompoundTag optionalNbt(Object value, String name) {
        if (JsValues.isNullish(value)) {
            return null;
        }
        return NbtJs.fromObject(JsValues.object(value, name), name);
    }

    static Direction direction(String value) {
        var side = Direction.byName(value);
        if (side == null) {
            throw JsValues.error("Invalid direction: " + value);
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
            default -> throw JsValues.error("Invalid network side: " + value);
        };
    }

    static Object required(Value object, String name) {
        return JsValues.required(object, name);
    }
}

@JsBridge
final class JsGlobals {
    private final ScriptApi api;

    JsGlobals(ScriptApi api) {
        this.api = api;
    }

    public JsNetwork network(String side) {
        return new JsNetwork(api, api.networkSide(side));
    }

    public JsSleepAction sleep(int ticks) {
        return new JsSleepAction(new FactorySleepAction(ticks));
    }

    public Object go(Value factory) {
        api.registration().requireOpen();
        api.registration().passiveHandlers.add(factory);
        return null;
    }

    public Object registerProcessingPattern(Object definitions, Value handler) {
        api.registration().requireOpen();
        var array = JsValues.array(definitions, "registerProcessingPattern definitions");
        var handlerIndex = api.registration().patternHandlers.size();
        api.registration().patternHandlers.add(handler);
        for (long index = 0; index < array.getArraySize(); index++) {
            var definition = JsValues.object(array.getArrayElement(index), "Pattern definition");
            var side = api.networkSide(JsValues.string(
                    ScriptApi.required(definition, "orderNetwork")));
            var inputs = specs(ScriptApi.required(definition, "inputs"), "inputs");
            var outputs = specs(ScriptApi.required(definition, "outputs"), "outputs");
            if (inputs.isEmpty() || outputs.isEmpty()) {
                throw JsValues.error("Processing patterns require inputs and outputs");
            }
            var encoded = PatternDetailsHelper.encodeProcessingPattern(
                    genericStacks(inputs), genericStacks(outputs));
            api.registration().patterns.add(new CompiledControllerProgram.ScriptPattern(
                    side, encoded, handlerIndex));
        }
        return null;
    }

    public Object rename(Object item, String name) {
        return api.renameItem(item, name);
    }

    /**
     * Prints a message to this controller's log subscribers (chat) and the server
     * log.
     */
    public Object log(String message) {
        api.host().log(message);
        return null;
    }

    public Object itemNbt(Object item) {
        return api.itemNbt(item);
    }

    public JsResourceSpec item(String id, long amount, Object components) {
        var resourceId = ResourceLocation.tryParse(id);
        if (resourceId == null) {
            throw JsValues.error("Invalid item id: " + id);
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
        var keyObject = JsValues.object(rawKey, "stack key");
        return spec(
                ScriptApi.resolveChannel(channel),
                NbtJs.fromObject(keyObject, "key"),
                amount);
    }

    private JsResourceSpec spec(AEKeyType channel, CompoundTag keyTag, long amount) {
        requireAmount(amount);
        var key = channel.loadKeyFromTag(api.host().registries(), keyTag);
        if (key == null) {
            throw JsValues.error(
                    "Invalid key for AE resource channel " + channel.getId());
        }
        return new JsResourceSpec(api, key, amount);
    }

    private List<FactoryResource> specs(Object value, String name) {
        var array = JsValues.array(value, name);
        var result = new ArrayList<FactoryResource>();
        for (long index = 0; index < array.getArraySize(); index++) {
            result.add(spec(JsValues.toHost(array.getArrayElement(index)), name));
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
                throw JsValues.error(name + " requires exact positive resource specs");
            }
            return new FactoryResource(spec.key(), spec.amount());
        }
        if (raw instanceof Value object && object.hasMembers()) {
            if (object.hasMember("channel")) {
                var rawChannel = object.getMember("channel");
                var rawKey = object.getMember("key");
                var rawAmount = object.getMember("amount");
                if (rawKey != null && rawKey.hasMembers() && rawAmount != null && !rawAmount.isNull()) {
                    var channel = ScriptApi.resolveChannel(JsValues.string(rawChannel));
                    var key = channel.loadKeyFromTag(
                            api.host().registries(),
                            NbtJs.fromObject(rawKey, name + ".key"));
                    if (key == null) {
                        throw JsValues.error(
                                name + " has an invalid key for channel " + channel.getId());
                    }
                    var amount = JsValues.number(rawAmount, name + ".amount");
                    if (!Double.isFinite(amount) || amount != Math.rint(amount)
                            || amount <= 0) {
                        throw JsValues.error(
                                name + " requires exact positive resource amounts");
                    }
                    return new FactoryResource(key, (long) amount);
                }
            }
        }
        throw JsValues.error(name + " requires resource specs");
    }

    private static List<GenericStack> genericStacks(List<FactoryResource> resources) {
        return resources.stream()
                .map(resource -> new GenericStack(resource.key(), resource.amount()))
                .toList();
    }

    private static void requireAmount(long amount) {
        if (amount != -1 && amount <= 0) {
            throw JsValues.error("Resource amount must be positive or -1");
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
        return NbtJs.toJs(key.toTag(api.host().registries()));
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

    public Object onChange(Value callback) {
        api.addTopologyListener(side, callback);
        return null;
    }

    /**
     * Compares the live AE grid objects; disconnected sides never compare equal.
     */
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
                    "minecraft:air", "minecraft:air",
                    position.getX(), position.getY(), position.getZ(), Map.of(), null, null);
        }
        var state = target.blockState();
        var blockEntityType = target.blockEntityTypeId();
        return new JsBlockView(
                target.blockId().toString(), state.toString(),
                position.getX(), position.getY(), position.getZ(),
                blockStateProperties(state),
                blockEntityType == null ? null : blockEntityType.toString(),
                target.blockEntityNbt());
    }

    private static Map<String, Object> blockStateProperties(BlockState state) {
        var result = new LinkedHashMap<String, Object>();
        state.getValues().forEach((property, value) -> result.put(property.getName(), exposePropertyValue(value)));
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

    /**
     * JS name is {@code break}; the Java name stays breakBlock because break is a
     * keyword.
     */
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
    private final String id;
    private final String state;
    private final int x;
    private final int y;
    private final int z;
    private final Map<String, Object> properties;
    private final String blockEntityType;
    private final CompoundTag nbt;

    JsBlockView(
            String id,
            String state,
            int x,
            int y,
            int z,
            Map<String, Object> properties,
            String blockEntityType,
            CompoundTag nbt) {
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
                : NbtJs.toJs(nbt);
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
        return NbtJs.toJs(resource.bundle().getFirst().key().toTag(api.host().registries()));
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

/**
 * {@code console.log/warn/error} convenience mirror of the {@code log()}
 * global.
 */
@JsBridge
final class JsConsole {
    private final FactoryProgram.Host host;

    JsConsole(FactoryProgram.Host host) {
        this.host = host;
    }

    public Object log(String message) {
        host.log(message);
        return null;
    }

    public Object warn(String message) {
        host.log(message);
        return null;
    }

    public Object error(String message) {
        host.log(message);
        return null;
    }
}
