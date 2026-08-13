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

import com.fulent.appliedfactory.factory.FactoryAction;
import com.fulent.appliedfactory.factory.FactoryEndpoint;
import com.fulent.appliedfactory.factory.FactoryProgram;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.factory.FactoryResourceOrigin;
import com.fulent.appliedfactory.factory.FactoryResourceRef;
import com.fulent.appliedfactory.factory.FactorySleepAction;
import com.fulent.appliedfactory.factory.FactoryTransferAction;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.Direction;
import net.minecraft.nbt.TagParser;
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

    Object performNow(FactoryTransferAction action) {
        if (activeContext == null) {
            throw Context.reportRuntimeError("Action.now() may only run inside a workflow");
        }
        var result = host.performTransfer(activeContext.workflowId(), action);
        if (action.mode() == FactoryTransferAction.Mode.EXACT) {
            return result.completed();
        }
        return new JsResource(this, new FactoryResourceRef(action.source(), result.remaining()));
    }

    JsResource resource(FactoryEndpoint endpoint, Object rawSpec) {
        var origin = FactoryResourceOrigin.endpoint(endpoint);
        if (rawSpec == Undefined.instance || rawSpec == null) {
            return new JsResource(this,
                    new FactoryResourceRef(origin, host.availableResources(endpoint)));
        }
        var spec = binder.unwrap(rawSpec, JsResourceSpec.class, "resource spec");
        var amount = spec.amount();
        if (amount == -1) {
            amount = host.availableAmount(origin, spec.key());
        }
        var bundle = amount <= 0
                ? List.<FactoryResource>of()
                : List.of(new FactoryResource(spec.key(), amount));
        return new JsResource(this, new FactoryResourceRef(origin, bundle));
    }

    JsOrder order(ScriptExecutionContext context) {
        var input = new FactoryResourceRef(
                FactoryResourceOrigin.escrow(context.workflowId()), context.inputs());
        return new JsOrder(
                new JsResource(this, input),
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

    static Direction direction(String value) {
        var side = Direction.byName(value);
        if (side == null) {
            throw Context.reportRuntimeError("Invalid direction: " + value);
        }
        return side;
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
final class JsGlobals {
    private final ScriptApi api;

    JsGlobals(ScriptApi api) {
        this.api = api;
    }

    public JsNetwork network(String side) {
        return new JsNetwork(api, ScriptApi.direction(side));
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
            var side = ScriptApi.direction(Context.toString(
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

    public JsResourceSpec item(String id, long amount) {
        return spec(AEKeyType.items(), id, amount);
    }

    public JsResourceSpec stack(String channel, String id, long amount) {
        return spec(resolveChannel(channel), id, amount);
    }

    public JsResourceSpec stackTag(String serializedKey, long amount) {
        requireAmount(amount);
        try {
            var key = AEKey.fromTagGeneric(api.host().registries(),
                    TagParser.parseTag(serializedKey));
            if (key == null) {
                throw Context.reportRuntimeError("stackTag contains an invalid AE key");
            }
            return new JsResourceSpec(key, amount);
        } catch (CommandSyntaxException exception) {
            throw Context.reportRuntimeError("stackTag requires valid SNBT");
        }
    }

    private JsResourceSpec spec(AEKeyType channel, String id, long amount) {
        requireAmount(amount);
        try {
            return new JsResourceSpec(
                    KeySpecRegistry.parse(api.host().registries(), channel, id, null), amount);
        } catch (IllegalArgumentException exception) {
            throw Context.reportRuntimeError(exception.getMessage());
        }
    }

    private List<FactoryResource> specs(Object value, String name) {
        if (!(value instanceof NativeArray array)) {
            throw Context.reportRuntimeError(name + " must be an array");
        }
        var result = new ArrayList<FactoryResource>();
        for (long index = 0; index < array.getLength(); index++) {
            var delegate = api.delegate(array.get((int) index, array));
            if (!(delegate instanceof JsResourceSpec spec) || spec.amount() <= 0) {
                throw Context.reportRuntimeError(name + " requires exact positive resource specs");
            }
            result.add(new FactoryResource(spec.key(), spec.amount()));
        }
        return FactoryResourceRef.normalize(result);
    }

    private static List<GenericStack> genericStacks(List<FactoryResource> resources) {
        return resources.stream()
                .map(resource -> new GenericStack(resource.key(), resource.amount()))
                .toList();
    }

    private static AEKeyType resolveChannel(String value) {
        return switch (value) {
            case "item" -> AEKeyType.items();
            case "fluid" -> AEKeyType.fluids();
            default -> {
                var id = ResourceLocation.tryParse(value);
                var type = id == null ? null : AEKeyTypes.get(id);
                if (type == null) {
                    throw Context.reportRuntimeError("Unknown AE resource channel: " + value);
                }
                yield type;
            }
        };
    }

    private static void requireAmount(long amount) {
        if (amount != -1 && amount <= 0) {
            throw Context.reportRuntimeError("Resource amount must be positive or -1");
        }
    }
}

@JsBridge
final class JsResourceSpec {
    private final AEKey key;
    private final long amount;

    JsResourceSpec(AEKey key, long amount) {
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
    public String getId() {
        return key.getId().toString();
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

    public JsResource extract(Object spec) {
        return api.resource(FactoryEndpoint.network(side), spec);
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
    public JsBlockView getTarget() {
        var target = api.host().busTarget(address).orElse(null);
        var position = address.hostPosition().relative(address.side());
        if (target == null || !target.isLoaded()) {
            return new JsBlockView(
                    "minecraft:air", "minecraft:air",
                    position.getX(), position.getY(), position.getZ(), Map.of());
        }
        var state = target.blockState();
        return new JsBlockView(
                target.blockId().toString(), state.toString(),
                position.getX(), position.getY(), position.getZ(),
                blockStateProperties(state));
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

    public JsResource extract(Object spec) {
        return api.resource(FactoryEndpoint.bus(address), spec);
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

    JsBlockView(
            String id,
            String state,
            int x,
            int y,
            int z,
            Map<String, Object> properties) {
        this.id = id;
        this.state = state;
        this.x = x;
        this.y = y;
        this.z = z;
        this.properties = Map.copyOf(properties);
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

    @JsProperty
    public JsResourceOrigin getOrigin() {
        return new JsResourceOrigin(api, resource.origin());
    }

    @JsProperty
    public boolean isEmpty() {
        return resource.isEmpty();
    }

    @JsProperty
    public List<JsResourceAmount> getBundle() {
        return resource.bundle().stream().map(JsResourceAmount::new).toList();
    }

    public JsTransferAction to(Object target) {
        return new JsTransferAction(api, new FactoryTransferAction(
                resource.origin(), api.requireEndpoint(target), resource.bundle(),
                FactoryTransferAction.Mode.PARTIAL));
    }

    public JsTransferAction pushExactlyInto(Object target) {
        return new JsTransferAction(api, new FactoryTransferAction(
                resource.origin(), api.requireEndpoint(target), resource.bundle(),
                FactoryTransferAction.Mode.EXACT));
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
            return "order";
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
final class JsResourceAmount {
    private final FactoryResource resource;

    JsResourceAmount(FactoryResource resource) {
        this.resource = resource;
    }

    @JsProperty
    public String getId() {
        return resource.id().toString();
    }

    @JsProperty
    public double getAmount() {
        return resource.amount();
    }
}

@JsBridge
final class JsTransferAction {
    private final ScriptApi api;
    private final FactoryTransferAction action;

    JsTransferAction(ScriptApi api, FactoryTransferAction action) {
        this.api = api;
        this.action = action;
    }

    FactoryAction action() {
        return action;
    }

    public Object now() {
        return api.performNow(action);
    }
}

@JsBridge
final class JsSleepAction {
    private final FactorySleepAction action;

    JsSleepAction(FactorySleepAction action) {
        this.action = action;
    }

    FactoryAction action() {
        return action;
    }
}

@JsBridge
final class JsOrder {
    private final JsResource input;
    private final JsNetwork network;

    JsOrder(JsResource input, JsNetwork network) {
        this.input = input;
        this.network = network;
    }

    @JsProperty
    public JsResource getInput() {
        return input;
    }

    @JsProperty
    public JsNetwork getNetwork() {
        return network;
    }
}
