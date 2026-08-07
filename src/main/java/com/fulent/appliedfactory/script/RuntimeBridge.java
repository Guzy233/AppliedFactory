package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryActionExecutor;
import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.part.FactoryBusPart;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Installs and drives the bus-centric script API for one loaded program: the
 * scope-level
 * registration functions, the context object handed to handlers, and the
 * bus/network/block/
 * resource prototypes. Every name installed on the scope is recorded
 * ({@link #scopeExclusions()})
 * so {@link RhinoScriptRuntime} can exclude exactly those (and re-link the
 * context object) when
 * serializing continuations — adding a new prototype or registration function
 * excludes it
 * automatically.
 */
final class RuntimeBridge {
    static final String CONTEXT_NAME = "__factoryContext";
    static final String BUS_PROTOTYPE_NAME = "__factoryBusPrototype";
    static final String BUS_ITEMS_PROTOTYPE_NAME = "__factoryBusItemsPrototype";
    static final String NETWORK_PROTOTYPE_NAME = "__factoryNetworkPrototype";
    static final String NETWORK_ITEMS_PROTOTYPE_NAME = "__factoryNetworkItemsPrototype";
    static final String BLOCK_PROTOTYPE_NAME = "__factoryBlockPrototype";
    static final String RESOURCE_PROTOTYPE_NAME = "__factoryResourcePrototype";
    static final String BUS_ADDRESS_PROPERTY = "__factoryBusAddress";
    static final String NETWORK_SIDE_PROPERTY = "__factoryNetworkSide";
    static final String RESOURCE_KEY_PROPERTY = "__factoryKey";
    static final String RESOURCE_ITEM_PROPERTY = "__factoryItem";
    static final String RESOURCE_OWNER_PROPERTY = "__factoryOwner";
    static final String RESOURCE_NBT_PROPERTY = "__factoryNbt";
    static final String INITIALIZE_NAME = "initialize";
    static final String REGISTER_PATTERNS_NAME = "registerPatterns";
    static final String REGISTER_CONTROLLER_NAME = "registerControllerHandler";
    static final String REGISTER_PASSIVE_NAME = "registerPassive";
    static final String ITEM_NAME = "item";

    private final Context installContext;
    private final Scriptable scope;
    private final Set<String> installedScopeNames = new LinkedHashSet<>();
    private Scriptable busPrototype;
    private Scriptable busItemsPrototype;
    private Scriptable networkPrototype;
    private Scriptable networkItemsPrototype;
    private Scriptable blockPrototype;
    private Scriptable resourcePrototype;
    private Scriptable contextObject;
    private ScriptExecutionContext context;

    RuntimeBridge(Context context, Scriptable scope) {
        installContext = context;
        this.scope = scope;
    }

    void install(Registration registration) {
        busPrototype = Jsify.toScriptable(installContext, scope, null, new BusPrototypeTemplate());
        busItemsPrototype = Jsify.toScriptable(installContext, scope, null,
                new BusItemsPrototypeTemplate());
        networkPrototype = Jsify.toScriptable(installContext, scope, null,
                new NetworkPrototypeTemplate());
        networkItemsPrototype = Jsify.toScriptable(installContext, scope, null,
                new NetworkItemsPrototypeTemplate());
        blockPrototype = Jsify.toScriptable(installContext, scope, null,
                new BlockPrototypeTemplate());
        resourcePrototype = Jsify.toScriptable(installContext, scope, null,
                new ResourcePrototypeTemplate());
        installScope(BUS_PROTOTYPE_NAME, busPrototype);
        installScope(BUS_ITEMS_PROTOTYPE_NAME, busItemsPrototype);
        installScope(NETWORK_PROTOTYPE_NAME, networkPrototype);
        installScope(NETWORK_ITEMS_PROTOTYPE_NAME, networkItemsPrototype);
        installScope(BLOCK_PROTOTYPE_NAME, blockPrototype);
        installScope(RESOURCE_PROTOTYPE_NAME, resourcePrototype);
        installScope(INITIALIZE_NAME, initializeFunction(registration));
        installScope(REGISTER_PATTERNS_NAME, patternsFunction(registration));
        installScope(REGISTER_CONTROLLER_NAME, controllerFunction(registration));
        installScope(REGISTER_PASSIVE_NAME, passiveFunction(registration));
        installScope(ITEM_NAME, itemFunction());
        installScope(CONTEXT_NAME, Undefined.instance);
    }

    /**
     * Records every name this bridge installs on the scope. The same set is what
     * {@link RhinoScriptRuntime} excludes (by name) when serializing continuations,
     * so a newly
     * installed prototype or registration function is automatically excluded — no
     * second list to
     * keep in sync.
     */
    Set<String> scopeExclusions() {
        return Set.copyOf(installedScopeNames);
    }

    private void installScope(String name, Object value) {
        ScriptableObject.putProperty(scope, name, value);
        installedScopeNames.add(name);
    }

    /**
     * Binds this invocation's live data. A resumed live continuation passes the
     * {@code reuseContextObject} it captured at job start, so the object the continuation graph
     * still references stays identical to the one produced here. Fresh starts and disk-restored
     * resumes pass {@code null} to build a new one; the latter is re-linked into the graph by
     * name during deserialization (see {@link #exposeContext()}).
     */
    void bind(
            ScriptExecutionContext context,
            @Nullable Scriptable reuseContextObject) {
        this.context = context;
        contextObject = reuseContextObject != null
                ? reuseContextObject
                : createContextObject(Context.getCurrentContext());
    }

    void unbind() {
        context = null;
        contextObject = null;
    }

    /**
     * Installs the bound context object under {@link #CONTEXT_NAME}. This is only needed around
     * the persistence points: deserialization re-links the restored continuation's {@code ctx}
     * to this exact object by name, and serialization excludes it by name. Script execution
     * itself never reads the slot — the handler receives {@code ctx} as its argument — so
     * {@link #exposeContext()} is called from {@code resume()} just before inflating a
     * disk-restored continuation, and {@code RhinoContinuation.serialize()} reinstalls it while
     * writing.
     */
    void exposeContext() {
        ScriptableObject.putProperty(scope, CONTEXT_NAME, contextObject);
    }

    /** Removes the context object from the scope again after a persistence point. */
    void hideContext() {
        ScriptableObject.putProperty(scope, CONTEXT_NAME, Undefined.instance);
    }

    Scriptable contextObject() {
        return contextObject;
    }

    Scriptable scope() {
        return scope;
    }

    private BaseFunction initializeFunction(Registration registration) {
        return function((cx, args) -> {
            registration.requireOpen();
            requireArity(args, 1, 1, "initialize(definition)");
            if (registration.initializer != null) {
                throw scriptError("initialize may only be called once");
            }
            var definition = requireObject(args[0], "initialize definition");
            registration.initializerNetworks.addAll(parseDirections(
                    requiredProperty(definition, "networks"), "initialize.networks"));
            registration.initializer = requireFunction(
                    requiredProperty(definition, "handler"), "initialize.handler");
            return Undefined.instance;
        });
    }

    private BaseFunction patternsFunction(Registration registration) {
        return function((cx, args) -> {
            registration.requireOpen();
            requireArity(args, 1, 1, "registerPatterns(definition)");
            var definition = requireObject(args[0], "pattern registration");
            var orderSide = requireDirection(
                    requiredProperty(definition, "orderNetwork"), "orderNetwork");
            var patterns = requireArray(
                    requiredProperty(definition, "patterns"), "patterns");
            for (long index = 0; index < patterns.getLength(); index++) {
                var pattern = requireObject(patterns.get((int) index, patterns),
                        "patterns[" + index + "]");
                var id = Context.toString(requiredProperty(pattern, "id"));
                if (id.isBlank() || registration.patterns.stream()
                        .anyMatch(existing -> existing.id().equals(id))) {
                    throw scriptError("Script pattern ids must be non-blank and unique: " + id);
                }
                var inputs = parseResourceSpecs(
                        requiredProperty(pattern, "inputs"), "pattern inputs");
                var outputs = parseResourceSpecs(
                        requiredProperty(pattern, "outputs"), "pattern outputs");
                if (inputs.isEmpty() || outputs.isEmpty()) {
                    throw scriptError("Processing patterns require inputs and outputs");
                }
                var handler = requireFunction(
                        requiredProperty(pattern, "handler"), "pattern.handler");
                var handlerIndex = registration.patternHandlers.size();
                registration.patternHandlers.add(handler);
                var encoded = PatternDetailsHelper.encodeProcessingPattern(
                        genericStacks(inputs), genericStacks(outputs));
                registration.patterns.add(new CompiledControllerProgram.ScriptPattern(
                        id, orderSide, encoded, handlerIndex));
            }
            return Undefined.instance;
        });
    }

    private BaseFunction controllerFunction(Registration registration) {
        return function((cx, args) -> {
            registration.requireOpen();
            requireArity(args, 1, 1, "registerControllerHandler(definition)");
            if (registration.controllerHandler != null) {
                throw scriptError("registerControllerHandler may only be called once");
            }
            var definition = requireObject(args[0], "controller handler definition");
            registration.controllerOrderNetwork = requireDirection(
                    requiredProperty(definition, "orderNetwork"), "orderNetwork");
            registration.controllerHandler = requireFunction(
                    requiredProperty(definition, "handler"), "handler");
            return Undefined.instance;
        });
    }

    private BaseFunction passiveFunction(Registration registration) {
        return function((cx, args) -> {
            registration.requireOpen();
            requireArity(args, 1, 1, "registerPassive(handler)");
            registration.passiveHandlers.add(requireFunction(args[0], "passive handler"));
            return Undefined.instance;
        });
    }

    private BaseFunction itemFunction() {
        return function((cx, args) -> {
            requireArity(args, 2, 3, "item(id, amount[, nbt])");
            var id = ResourceLocation.tryParse(Context.toString(args[0]));
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                throw scriptError("Unknown item id: " + Context.toString(args[0]));
            }
            var amount = positiveLong(args[1], "item amount");
            CompoundTag nbt = null;
            if (args.length == 3) {
                nbt = NbtJs.fromObject(
                        cx, requireObject(args[2], "item nbt"), "item nbt");
            }
            return itemResourceObject(cx, id, amount, nbt);
        });
    }

    private Scriptable createContextObject(Context cx) {
        // The view holds the bridge, not a captured context: this contextObject
        // instance is
        // reused across every resume of a live job (so the continuation graph and
        // CONTEXT_NAME
        // stay identical for serialization), yet each live getter read must reflect the
        // current
        // tick's context. bind() rebinds `context` before each resume; the getters read
        // it live
        // through the bridge. The view is never serialized because the contextObject
        // (and only
        // objects reachable from it) is excluded from continuation serialization by
        // name.
        return Jsify.toScriptable(cx, scope, null, new FactoryContextView());
    }

    /**
     * Java template for the context object handed to handlers. {@link JsLive}
     * getters are
     * re-invoked on every JS read through {@link Jsify}; {@link JsMethod} methods
     * keep the
     * existing argument coercion and arity checks. Processing-only data is
     * {@link JsOptional}
     * and disappears whenever the job carries no order network.
     */
    private final class FactoryContextView {
        @JsLive
        public double getTick() {
            return context.tick();
        }

        @JsLive
        public Object getBuses() {
            return busArray(Context.getCurrentContext(), context.allBuses());
        }

        // Every workflow (processing, passive, initializer) may own cached resources.
        @JsLive
        public Object getOwned() {
            return resourceArray(
                    Context.getCurrentContext(), context.owned(),
                    context.workflowId());
        }

        // Processing-only data: present exactly when the job carries an order network.
        @JsOptional
        @JsLive
        public Object getInputs() {
            return context.orderNetwork() == null
                    ? null
                    : resourceArray(Context.getCurrentContext(),
                            context.inputs(), context.workflowId());
        }

        @JsOptional
        @JsLive
        public Object getOutputs() {
            return context.orderNetwork() == null
                    ? null
                    : resourceArray(
                            Context.getCurrentContext(), context.outputs(), null);
        }

        @JsOptional
        @JsLive
        public Object getOrderNetwork() {
            return context.orderNetwork() == null
                    ? null
                    : networkObject(
                            Context.getCurrentContext(), context.orderNetwork());
        }

        @JsMethod
        public Object network(Context cx, Object[] args) {
            requireArity(args, 1, 1, "ctx.network(side)");
            var side = requireDirection(args[0], "network side");
            if (!context.canAccess(side)) {
                throw scriptError("Network " + side.getName() + " is not accessible here");
            }
            return networkObject(cx, side);
        }

        @JsMethod
        public Object log(Context cx, Object[] args) {
            requireArity(args, 1, 1, "ctx.log(message)");
            AppliedFactory.LOGGER.info("[Factory script] {}", Context.toString(args[0]));
            return Undefined.instance;
        }

        @JsMethod
        public Object sleep(Context cx, Object[] args) {
            requireArity(args, 1, 1, "ctx.sleep(ticks)");
            return suspend(cx, FactoryScriptAction.sleep(
                    nonNegativeInt(args[0], "sleep duration")));
        }

        @JsMethod(name = "yield")
        public Object doYield(Context cx, Object[] args) {
            requireArity(args, 0, 0, "ctx.yield()");
            return suspend(cx, FactoryScriptAction.sleep(1));
        }

        @JsMethod
        public Object fail(Context cx, Object[] args) {
            requireArity(args, 1, 1, "ctx.fail(message)");
            throw scriptError(Context.toString(args[0]));
        }
    }

    // ---- Prototype templates -------------------------------------------------

    /**
     * Shared methods for every bus handle. The receiver carries the durable bus
     * address.
     */
    private final class BusPrototypeTemplate {
        @JsMethod
        public Object exists(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.exists()");
            return resolveBus(requireBus(self)).isPresent();
        }

        @JsMethod
        public Object state(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.state()");
            var bus = resolveBus(requireBus(self)).orElse(null);
            return bus == null ? null : busStateObject(cx, bus);
        }

        @JsMethod
        public Object target(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.target()");
            var bus = resolveBus(requireBus(self)).orElse(null);
            return bus == null ? null
                    : bus.machine().map(machine -> blockObject(cx, machine))
                            .orElse(null);
        }

        @JsMethod
        public Object items(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.items()");
            var handle = requireBus(self);
            var bus = resolveBus(handle).orElse(null);
            if (bus == null || bus.machine()
                    .filter(machine -> machine.hasItemStorage()).isEmpty()) {
                return null;
            }
            return busItemsObject(cx, handle);
        }

        @JsMethod
        public Object detect(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.detect(selector)");
            var bus = resolveBus(requireBus(self)).orElse(null);
            var machine = bus == null ? null : bus.machine().orElse(null);
            return machine != null && machine.matchesBlock(Context.toString(args[0]));
        }

        @JsMethod
        public Object drop(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.drop(resources)");
            return suspend(cx, FactoryScriptAction.busDrop(
                    requireBus(self), parseOwned(args[0])));
        }

        @JsMethod
        public Object use(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.use()");
            return suspend(cx, FactoryScriptAction.busUse(requireBus(self)));
        }

        @JsMethod
        public Object place(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.place(resource)");
            return suspend(cx, FactoryScriptAction.busPlace(
                    requireBus(self), parseOwnedUnit(args[0])));
        }

        @JsMethod(name = "break")
        public Object doBreak(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 1, "bus.break(tool?)");
            if (args.length == 0) {
                return suspend(cx, FactoryScriptAction.busBreak(requireBus(self)));
            }
            return suspend(cx, FactoryScriptAction.busBreakWith(
                    requireBus(self), parseOwnedUnit(args[0])));
        }

        @JsMethod
        public Object redstone(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.redstone(level)");
            var level = nonNegativeInt(args[0], "redstone level");
            if (level > 15) {
                throw scriptError("redstone level must be between 0 and 15");
            }
            return suspend(cx,
                    FactoryScriptAction.busRedstone(requireBus(self), level));
        }
    }

    /** Shared methods for a bus's item storage handle. */
    private final class BusItemsPrototypeTemplate {
        @JsMethod
        public Object read(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.items().read()");
            var bus = resolveBus(requireBus(self)).orElse(null);
            if (bus == null) {
                return cx.newArray(scope, 0);
            }
            var resources = bus.machine().map(machine -> resources(machine.items()))
                    .orElse(List.of());
            return resourceArray(cx, resources, null);
        }

        @JsMethod
        public Object push(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.items().push(resources)");
            return suspend(cx, FactoryScriptAction.busPush(
                    requireBus(self), parseOwned(args[0])));
        }

        @JsMethod
        public Object pushTillFull(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.items().pushTillFull(resources)");
            return suspend(cx, FactoryScriptAction.busPushTillFull(
                    requireBus(self), parseOwned(args[0])));
        }

        @JsMethod
        public Object canPush(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "bus.items().canPush(resources)");
            var resources = parseResourceSpecs(args[0], "bus push resources");
            var bus = resolveBus(requireBus(self)).orElse(null);
            var machine = bus == null ? null : bus.machine().orElse(null);
            if (machine == null) {
                return false;
            }
            var stacks = toItemStacks(resources);
            return stacks != null && machine.canInsertAll(stacks);
        }

        @JsMethod
        public Object extract(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "bus.items().extract()");
            return suspend(cx, FactoryScriptAction.busExtract(requireBus(self)));
        }
    }

    /** Shared methods for a network handle. */
    private final class NetworkPrototypeTemplate {
        @JsMethod
        public Object online(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "network.online()");
            return context.isOnline(requireNetwork(self));
        }

        @JsMethod
        public Object items(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "network.items()");
            return networkItemsObject(cx, requireNetwork(self));
        }
    }

    /** Shared methods for a network's item storage handle. */
    private final class NetworkItemsPrototypeTemplate {
        @JsMethod
        public Object push(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "network.items().push(resources)");
            return suspend(cx, FactoryScriptAction.networkPush(
                    requireNetwork(self), parseOwned(args[0])));
        }

        @JsMethod
        public Object pushTillFull(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "network.items().pushTillFull(resources)");
            return suspend(cx, FactoryScriptAction.networkPushTillFull(
                    requireNetwork(self), parseOwned(args[0])));
        }

        @JsMethod
        public Object canPush(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "network.items().canPush(resources)");
            var resources = parseResourceSpecs(args[0], "network push resources");
            var endpoint = context.networkStorage(requireNetwork(self)).orElse(null);
            if (endpoint == null) {
                return false;
            }
            return resources.stream().allMatch(resource -> endpoint.storage().insert(
                    resource.key(), resource.amount(), Actionable.SIMULATE, endpoint.source())
                    == resource.amount());
        }

        @JsMethod
        public Object extract(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "network.items().extract(requests)");
            return suspend(cx, FactoryScriptAction.networkExtract(
                    requireNetwork(self),
                    parseResourceSpecs(args[0], "network requests")));
        }

        @JsMethod
        public Object read(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "network.items().read()");
            var endpoint = context.networkStorage(requireNetwork(self)).orElse(null);
            if (endpoint == null) {
                return cx.newArray(scope, 0);
            }
            return resourceArray(cx, availableResources(endpoint), null);
        }

        @JsMethod
        public Object count(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "network.items().count(spec)");
            var endpoint = context.networkStorage(requireNetwork(self)).orElse(null);
            if (endpoint == null) {
                return 0.0;
            }
            var selector = args[0];
            long total = 0;
            for (var resource : availableResources(endpoint)) {
                if (matchesResource(resource, selector)) {
                    total = Math.addExact(total, resource.amount());
                }
            }
            return (double) total;
        }
    }

    /** Shared method for every resource object. */
    private final class ResourcePrototypeTemplate {
        @JsMethod
        public Object matches(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "resource.matches(selector)");
            if (self.getPrototype() != resourcePrototype) {
                throw scriptError("Resource method received an invalid receiver");
            }
            var key = resourceKey(self);
            var selector = Context.toString(args[0]);
            if (selector.startsWith("#")) {
                var tagId = ResourceLocation.tryParse(selector.substring(1));
                return tagId != null && key instanceof AEItemKey itemKey
                        && itemKey.isTagged(TagKey.create(Registries.ITEM, tagId));
            }
            var id = ResourceLocation.tryParse(selector);
            return id != null && id.equals(key.getId());
        }

        @JsMethod
        public Object nbt(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 0, 0, "resource.nbt()");
            if (self.getPrototype() != resourcePrototype) {
                throw scriptError("Resource method received an invalid receiver");
            }
            var key = resourceKey(self);
            if (!(key instanceof AEItemKey itemKey)) {
                return cx.newObject(scope);
            }
            return itemNbtObject(cx, itemKey);
        }

        @JsMethod
        public Object rename(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "resource.rename(name)");
            if (self.getPrototype() != resourcePrototype) {
                throw scriptError("Resource method received an invalid receiver");
            }
            var owner = ScriptableObject.getProperty(self, RESOURCE_OWNER_PROPERTY);
            if (owner == Scriptable.NOT_FOUND
                    || !context.workflowId().toString().equals(Context.toString(owner))) {
                throw scriptError("rename only accepts a resource owned by this workflow");
            }
            var name = Context.toString(args[0]);
            if (name.isBlank()) {
                throw scriptError("rename requires a non-blank name");
            }
            return suspend(cx, FactoryScriptAction.renameOwned(parseOwned(self), name));
        }
    }

    /** Shared method for every block view. */
    private final class BlockPrototypeTemplate {
        @JsMethod
        public Object matches(Context cx, Scriptable self, Object[] args) {
            requireArity(args, 1, 1, "block.matches(selector)");
            if (self.getPrototype() != blockPrototype) {
                throw scriptError("Block method received an invalid receiver");
            }
            var idValue = ScriptableObject.getProperty(self, "id");
            var id = idValue == Scriptable.NOT_FOUND
                    ? null
                    : ResourceLocation.tryParse(Context.toString(idValue));
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return false;
            }
            var selector = Context.toString(args[0]);
            if (selector.startsWith("#")) {
                var tagId = ResourceLocation.tryParse(selector.substring(1));
                return tagId != null && BuiltInRegistries.BLOCK.get(id).defaultBlockState()
                        .is(TagKey.create(Registries.BLOCK, tagId));
            }
            var selectorId = ResourceLocation.tryParse(selector);
            // Compare the canonical ids rather than relying on the particular
            // ResourceLocation instance restored by Rhino's script state.
            return selectorId != null && id.toString().equals(selectorId.toString());
        }
    }

    // ---- Object factories ----------------------------------------------------

    private Scriptable busObject(Context cx, FactoryBusPart bus) {
        var address = bus.address().orElseThrow();
        var targetPosition = address.hostPosition().relative(address.side());
        return Jsify.toScriptable(cx, scope, busPrototype, new BusView(
                encodeAddress(address),
                busAddressObject(cx, address),
                blockAddressObject(cx, address.dimension(), targetPosition),
                address.side().getName()));
    }

    private Scriptable busItemsObject(Context cx, FactoryBusAddress address) {
        return Jsify.toScriptable(cx, scope, busItemsPrototype,
                new BusItemsView(encodeAddress(address)));
    }

    private Scriptable networkObject(Context cx, Direction side) {
        return Jsify.toScriptable(cx, scope, networkPrototype,
                new NetworkView(side.getName(), busArray(cx, context.buses(side))));
    }

    private Scriptable networkItemsObject(Context cx, Direction side) {
        return Jsify.toScriptable(cx, scope, networkItemsPrototype,
                new NetworkItemsView(side.getName()));
    }

    private Scriptable busArray(Context cx, List<FactoryBusPart> buses) {
        // 创建java侧总线列表
        var values = buses.stream()
                .filter(bus -> bus.address().isPresent())
                .map(bus -> busObject(cx, bus))
                .toArray();
        // 转换为js对象
        var array = cx.newArray(scope, values);
        return array;
    }

    private Scriptable busAddressObject(Context cx, FactoryBusAddress address) {
        var position = address.hostPosition();
        return Jsify.toScriptable(cx, scope, null, new BusAddressView(
                address.dimension().toString(),
                position.getX(), position.getY(), position.getZ(),
                address.side().getName(),
                encodeAddress(address)));
    }

    private Scriptable blockAddressObject(
            Context cx, ResourceLocation dimension, BlockPos position) {
        return Jsify.toScriptable(cx, scope, null, new BlockAddressView(
                dimension.toString(),
                position.getX(), position.getY(), position.getZ(),
                dimension + ":" + position.asLong()));
    }

    private Scriptable busStateObject(Context cx, FactoryBusPart bus) {
        var upgrades = cx.newObject(scope);
        defineReadOnly(upgrades, "acceleration", bus.accelerationCards());
        return Jsify.toScriptable(cx, scope, null, new BusStateView(
                bus.isActive(),
                bus.isPowered(),
                bus.machine().map(machine -> machine.redstoneLevel()).orElse(0),
                upgrades,
                cx.newObject(scope)));
    }

    private Scriptable blockObject(Context cx,
            com.fulent.appliedfactory.factory.FactoryMachineAccess machine) {
        var state = cx.newObject(scope);
        var blockState = machine.blockState();
        for (var property : blockState.getProperties()) {
            defineReadOnly(state, property.getName(), propertyName(blockState, property));
        }
        var blockEntityType = machine.blockEntityTypeId();
        var blockNbt = machine.blockEntityNbt();
        var nbtObject = blockNbt == null ? null : NbtJs.toJs(cx, scope, blockNbt);
        return Jsify.toScriptable(cx, scope, blockPrototype, new BlockView(
                machine.blockId().toString(),
                state,
                blockEntityType == null ? null : blockEntityType.toString(),
                nbtObject instanceof Scriptable scriptable ? scriptable : null));
    }

    private Scriptable itemResourceObject(
            Context cx, ResourceLocation id, long amount, @Nullable CompoundTag nbt) {
        return Jsify.toScriptable(cx, scope, resourcePrototype,
                new ItemResourceView(id.toString(), (double) amount,
                        nbt == null ? null : nbt.toString()));
    }

    /** Snapshot of everything an attached AE network can currently supply. */
    private List<FactoryResource> availableResources(
            FactoryActionExecutor.NetworkEndpoint endpoint) {
        var amounts = new KeyCounter();
        endpoint.storage().getAvailableStacks(amounts);
        var result = new ArrayList<FactoryResource>();
        for (var entry : amounts) {
            if (entry.getLongValue() > 0) {
                result.add(new FactoryResource(entry.getKey(), entry.getLongValue()));
            }
        }
        return result;
    }

    /**
     * A string selector matches an item id or {@code #tag} loosely (any data
     * components); a resource object matches its exact AE key.
     */
    private boolean matchesResource(FactoryResource resource, Object selector) {
        if (selector instanceof Scriptable scriptable) {
            try {
                return resource.key().equals(resourceKey(scriptable));
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        var text = Context.toString(selector);
        if (text.startsWith("#")) {
            var tagId = ResourceLocation.tryParse(text.substring(1));
            return tagId != null && resource.key() instanceof AEItemKey itemKey
                    && itemKey.isTagged(TagKey.create(Registries.ITEM, tagId));
        }
        var id = ResourceLocation.tryParse(text);
        return id != null && id.equals(resource.key().getId());
    }

    /** The full saved item NBT of one exact item key, as a read-only JS tree. */
    private Scriptable itemNbtObject(Context cx, AEItemKey itemKey) {
        var tag = itemKey.toStack().save(context.registries());
        return (Scriptable) NbtJs.toJs(cx, scope, tag);
    }

    private Scriptable resourceObject(
            Context cx, FactoryResource resource, UUID owner) {
        // Pure snapshot: the durable brand (exact AE key / owner) is carried as
        // non-enumerable
        // string handles so the object survives continuation serialization and
        // resourceKey()
        // still validates it after a disk restore.
        return Jsify.toScriptable(cx, scope, resourcePrototype, new ResourceView(
                resource.id().toString(),
                resource.amount(),
                resource.key().toTagGeneric(context.registries()).toString(),
                owner == null ? "" : owner.toString()));
    }

    /** Java template for the script-facing resource objects. */
    private record ResourceView(
            @JsReadOnly String id,
            @JsReadOnly double amount,
            @JsInternal(name = RESOURCE_KEY_PROPERTY) String key,
            @JsInternal(name = RESOURCE_OWNER_PROPERTY) String owner) {
    }

    /** Java template for a bus handle. */
    private record BusView(
            @JsInternal(name = BUS_ADDRESS_PROPERTY) String busAddress,
            @JsReadOnly Scriptable address,
            @JsReadOnly Scriptable targetAddress,
            @JsReadOnly String targetFace) {
    }

    /** Java template for a bus item storage handle. */
    private record BusItemsView(
            @JsInternal(name = BUS_ADDRESS_PROPERTY) String busAddress) {
    }

    /** Java template for a network handle. */
    private static final class NetworkView {
        private final String side;
        private final Scriptable buses;

        private NetworkView(String side, Scriptable buses) {
            this.side = side;
            this.buses = buses;
        }

        @JsInternal(name = NETWORK_SIDE_PROPERTY)
        public String getNetworkSide() {
            return side;
        }

        @JsReadOnly
        public String getSide() {
            return side;
        }

        @JsReadOnly
        public Scriptable getBuses() {
            return buses;
        }
    }

    /** Java template for a network item storage handle. */
    private static final class NetworkItemsView {
        private final String side;

        private NetworkItemsView(String side) {
            this.side = side;
        }

        @JsInternal(name = NETWORK_SIDE_PROPERTY)
        public String getNetworkSide() {
            return side;
        }
    }

    /** Java template for {@code bus.address}. */
    private record BusAddressView(
            @JsReadOnly String dimension,
            @JsReadOnly int hostX,
            @JsReadOnly int hostY,
            @JsReadOnly int hostZ,
            @JsReadOnly String partSide,
            @JsReadOnly String key) {
    }

    /** Java template for {@code bus.targetAddress}. */
    private record BlockAddressView(
            @JsReadOnly String dimension,
            @JsReadOnly int x,
            @JsReadOnly int y,
            @JsReadOnly int z,
            @JsReadOnly String key) {
    }

    /** Java template for {@code bus.state()}. */
    private record BusStateView(
            @JsReadOnly boolean active,
            @JsReadOnly boolean powered,
            @JsReadOnly int redstone,
            @JsReadOnly Scriptable upgrades,
            @JsReadOnly Scriptable config) {
    }

    /** Java template for {@code bus.target()}. */
    private record BlockView(
            @JsReadOnly String id,
            @JsReadOnly Scriptable state,
            @JsReadOnly String blockEntityType,
            @JsReadOnly Scriptable nbt) {
    }

    /** Java template for the {@code item()} global result. */
    private static final class ItemResourceView {
        private final String id;
        private final double amount;
        private final String nbt;

        private ItemResourceView(String id, double amount, String nbt) {
            this.id = id;
            this.amount = amount;
            this.nbt = nbt;
        }

        @JsReadOnly
        public String getId() {
            return id;
        }

        @JsReadOnly
        public double getAmount() {
            return amount;
        }

        @JsInternal(name = RESOURCE_ITEM_PROPERTY)
        public String getItemId() {
            return id;
        }

        @JsInternal(name = RESOURCE_OWNER_PROPERTY)
        public String getOwner() {
            return "";
        }

        @JsInternal(name = RESOURCE_NBT_PROPERTY)
        @JsOptional
        public String getNbt() {
            return nbt;
        }
    }

    private Scriptable resourceArray(
            Context cx, List<FactoryResource> resources, UUID owner) {
        var values = resources.stream()
                .map(resource -> resourceObject(cx, resource, owner))
                .toArray();
        return cx.newArray(scope, values);
    }

    private List<FactoryResource> parseOwned(Object value) {
        var resources = parseResourceObjects(value, "owned resources");
        var owner = context.workflowId().toString();
        for (var object : resources) {
            var objectOwner = ScriptableObject.getProperty(object, RESOURCE_OWNER_PROPERTY);
            if (objectOwner == Scriptable.NOT_FOUND
                    || !owner.equals(Context.toString(objectOwner))) {
                throw scriptError("push only accepts resources owned by this workflow");
            }
        }
        var parsed = normalizeParsed(resources);
        if (!canSubtract(context.owned(), parsed)) {
            throw scriptError("Workflow no longer owns the requested resource amount");
        }
        return parsed;
    }

    /**
     * Returns exactly one owned item while allowing a larger owned stack to be
     * placed gradually.
     */
    private FactoryResource parseOwnedUnit(Object value) {
        var object = requireResource(value, "placement resource");
        var owner = ScriptableObject.getProperty(object, RESOURCE_OWNER_PROPERTY);
        if (owner == Scriptable.NOT_FOUND
                || !context.workflowId().toString().equals(Context.toString(owner))) {
            throw scriptError("place only accepts a resource owned by this workflow");
        }
        // Read and validate the visible amount even though one item is consumed per
        // call.
        positiveLong(ScriptableObject.getProperty(object, "amount"), "resource amount");
        var resource = new FactoryResource(resourceKey(object), 1);
        if (!canSubtract(context.owned(), List.of(resource))) {
            throw scriptError("Workflow no longer owns a resource that can be placed");
        }
        return resource;
    }

    private List<FactoryResource> parseResourceSpecs(Object value, String name) {
        return normalizeParsed(parseResourceObjects(value, name));
    }

    private List<Scriptable> parseResourceObjects(Object value, String name) {
        var result = new ArrayList<Scriptable>();
        if (value instanceof NativeArray array) {
            for (long index = 0; index < array.getLength(); index++) {
                var candidate = array.get((int) index, array);
                result.add(requireResource(candidate, name + "[" + index + "]"));
            }
        } else {
            result.add(requireResource(value, name));
        }
        if (result.isEmpty()) {
            throw scriptError(name + " cannot be empty");
        }
        return result;
    }

    private Scriptable requireResource(Object value, String name) {
        if (!(value instanceof Scriptable object)) {
            throw scriptError(name + " must contain resources created by this API");
        }

        // Rhino serializes a suspended continuation when an action yields. On resume it
        // can restore the resource object's JavaScript prototype as a distinct Java
        // object,
        // despite retaining all of the immutable descriptor fields. Prototype reference
        // equality would therefore reject an OwnedResource returned by
        // extract()/break().
        // The hidden, permanent fields are the durable API brand; resourceKey() still
        // fully
        // validates their content before anything reaches the game state.
        var exactKey = ScriptableObject.getProperty(object, RESOURCE_KEY_PROPERTY);
        var itemId = ScriptableObject.getProperty(object, RESOURCE_ITEM_PROPERTY);
        var owner = ScriptableObject.getProperty(object, RESOURCE_OWNER_PROPERTY);
        if (owner == Scriptable.NOT_FOUND
                || (exactKey == Scriptable.NOT_FOUND && itemId == Scriptable.NOT_FOUND)) {
            throw scriptError(name + " must contain resources created by this API");
        }
        return object;
    }

    private List<FactoryResource> normalizeParsed(List<Scriptable> objects) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var object : objects) {
            var key = resourceKey(object);
            var amount = positiveLong(
                    ScriptableObject.getProperty(object, "amount"), "resource amount");
            amounts.merge(key, amount, Math::addExact);
        }
        return amounts.entrySet().stream()
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    private AEKey resourceKey(Scriptable resource) {
        var keyTag = ScriptableObject.getProperty(resource, RESOURCE_KEY_PROPERTY);
        if (keyTag != Scriptable.NOT_FOUND) {
            try {
                var key = AEKey.fromTagGeneric(
                        context.registries(), TagParser.parseTag(Context.toString(keyTag)));
                if (key != null) {
                    return key;
                }
            } catch (CommandSyntaxException ignored) {
                // Report the same safe script error for corrupt and forged descriptors.
            }
            throw scriptError("Resource contains an invalid exact AE key");
        }
        var nbtValue = ScriptableObject.getProperty(resource, RESOURCE_NBT_PROPERTY);
        if (nbtValue != Scriptable.NOT_FOUND) {
            return nbtResourceKey(resource, Context.toString(nbtValue));
        }
        var itemIdValue = ScriptableObject.getProperty(resource, RESOURCE_ITEM_PROPERTY);
        var itemId = itemIdValue == Scriptable.NOT_FOUND
                ? null
                : ResourceLocation.tryParse(Context.toString(itemIdValue));
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            throw scriptError("Resource contains an invalid item id");
        }
        return AEItemKey.of(BuiltInRegistries.ITEM.get(itemId));
    }

    /** Rebuilds an exact item key from the component patch carried by an {@code item()} spec. */
    private AEKey nbtResourceKey(Scriptable resource, String nbtTag) {
        final DataComponentPatch patch;
        try {
            patch = DataComponentPatch.CODEC.parse(
                    NbtOps.INSTANCE, TagParser.parseTag(nbtTag))
                    .resultOrPartial(error -> { }).orElse(null);
        } catch (CommandSyntaxException exception) {
            throw scriptError("Resource contains invalid item data components");
        }
        var itemIdValue = ScriptableObject.getProperty(resource, RESOURCE_ITEM_PROPERTY);
        var itemId = itemIdValue == Scriptable.NOT_FOUND
                ? null
                : ResourceLocation.tryParse(Context.toString(itemIdValue));
        if (patch == null || itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            throw scriptError("Resource contains invalid item data components");
        }
        var stack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        stack.applyComponentsAndValidate(patch);
        return AEItemKey.of(stack);
    }

    private FactoryBusAddress requireBus(Scriptable receiver) {
        var encoded = ScriptableObject.getProperty(receiver, BUS_ADDRESS_PROPERTY);
        if (encoded == Scriptable.NOT_FOUND) {
            throw scriptError("Bus handle is invalid");
        }
        return decodeAddress(Context.toString(encoded))
                .orElseThrow(() -> scriptError("Bus address is invalid"));
    }

    private Direction requireNetwork(Scriptable receiver) {
        var sideValue = ScriptableObject.getProperty(receiver, NETWORK_SIDE_PROPERTY);
        var side = sideValue == Scriptable.NOT_FOUND
                ? null
                : Direction.byName(Context.toString(sideValue));
        if (side == null || !context.canAccess(side)) {
            throw scriptError("Network handle is invalid or inaccessible");
        }
        return side;
    }

    private Optional<FactoryBusPart> resolveBus(FactoryBusAddress address) {
        return context.resolveBus(address);
    }

    /**
     * Converts a job-level action result back into the value the suspended API call
     * returns.
     */
    Object resultValue(FactoryActionResult result) {
        return switch (result.kind()) {
            case BOOLEAN -> result.success();
            case RESOURCES -> resourceArray(
                    Context.getCurrentContext(), result.resources(), context.workflowId());
            case REMAINING -> throw new IllegalStateException(
                    "REMAINING results are scheduler-internal and never reach a script");
            case VOID -> Undefined.instance;
        };
    }

    private Object suspend(Context cx, FactoryScriptAction action) {
        var pending = cx.captureContinuation();
        pending.setApplicationState(action);
        throw pending;
    }

    private static List<FactoryResource> resources(List<ItemStack> stacks) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var stack : stacks) {
            if (!stack.isEmpty()) {
                amounts.merge(AEItemKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        return amounts.entrySet().stream()
                .map(entry -> new FactoryResource(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<GenericStack> genericStacks(List<FactoryResource> resources) {
        return resources.stream()
                .map(resource -> new GenericStack(resource.key(), resource.amount()))
                .toList();
    }

    /** Detached item stacks for a resource list, or null when any key is not an item. */
    private static List<ItemStack> toItemStacks(List<FactoryResource> resources) {
        var result = new ArrayList<ItemStack>();
        for (var resource : resources) {
            if (!(resource.key() instanceof AEItemKey itemKey)) {
                return null;
            }
            var remaining = resource.amount();
            while (remaining > 0) {
                var amount = (int) Math.min(remaining, itemKey.getMaxStackSize());
                result.add(itemKey.toStack(amount));
                remaining -= amount;
            }
        }
        return List.copyOf(result);
    }

    private static boolean canSubtract(
            List<FactoryResource> current, List<FactoryResource> requested) {
        var amounts = new LinkedHashMap<AEKey, Long>();
        for (var resource : current) {
            amounts.merge(resource.key(), resource.amount(), Math::addExact);
        }
        for (var resource : requested) {
            if (amounts.getOrDefault(resource.key(), 0L) < resource.amount()) {
                return false;
            }
        }
        return true;
    }

    private static String encodeAddress(FactoryBusAddress address) {
        return address.dimension() + "|" + address.hostPosition().asLong()
                + "|" + address.side().getName();
    }

    private static Optional<FactoryBusAddress> decodeAddress(String encoded) {
        var parts = encoded.split("\\|", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        var dimension = ResourceLocation.tryParse(parts[0]);
        var side = Direction.byName(parts[2]);
        try {
            return dimension == null || side == null
                    ? Optional.empty()
                    : Optional.of(new FactoryBusAddress(
                            dimension, BlockPos.of(Long.parseLong(parts[1])), side));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static <T extends Comparable<T>> String propertyName(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Scriptable requireObject(Object value, String name) {
        if (!(value instanceof Scriptable object) || value instanceof Function) {
            throw scriptError(name + " must be an object");
        }
        return object;
    }

    private static NativeArray requireArray(Object value, String name) {
        if (!(value instanceof NativeArray array)) {
            throw scriptError(name + " must be an array");
        }
        return array;
    }

    private static Function requireFunction(Object value, String name) {
        if (!(value instanceof Function function)) {
            throw scriptError(name + " must be a function");
        }
        return function;
    }

    private static Object requiredProperty(Scriptable object, String name) {
        var value = ScriptableObject.getProperty(object, name);
        if (value == Scriptable.NOT_FOUND || value == Undefined.instance) {
            throw scriptError("Missing required property: " + name);
        }
        return value;
    }

    private static Set<Direction> parseDirections(Object value, String name) {
        var array = requireArray(value, name);
        var result = EnumSet.noneOf(Direction.class);
        for (long index = 0; index < array.getLength(); index++) {
            result.add(requireDirection(array.get((int) index, array), name));
        }
        return result;
    }

    private static Direction requireDirection(Object value, String name) {
        var direction = Direction.byName(Context.toString(value));
        if (direction == null) {
            throw scriptError(name + " must be up, down, north, south, west or east");
        }
        return direction;
    }

    private static void requireArity(
            Object[] args, int minimum, int maximum, String usage) {
        if (args.length < minimum || args.length > maximum) {
            var expected = minimum == maximum
                    ? Integer.toString(minimum)
                    : minimum + " to " + maximum;
            throw scriptError(usage + " expects " + expected + " arguments");
        }
    }

    private static int nonNegativeInt(Object value, String name) {
        var number = Context.toNumber(value);
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < 0 || number > Integer.MAX_VALUE) {
            throw scriptError(name + " must be a non-negative integer");
        }
        return (int) number;
    }

    private static long positiveLong(Object value, String name) {
        var number = Context.toNumber(value);
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number <= 0 || number > Long.MAX_VALUE) {
            throw scriptError(name + " must be a positive integer");
        }
        return (long) number;
    }

    private static RuntimeException scriptError(String message) {
        return Context.reportRuntimeError(message);
    }

    private static BaseFunction function(BridgeCall call) {
        return new BaseFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public Object call(
                    Context context,
                    Scriptable callScope,
                    Scriptable thisObject,
                    Object[] args) {
                return call.call(context, args);
            }
        };
    }

    private static void defineReadOnly(Scriptable object, String name, Object value) {
        Jsify.defineReadOnly(object, name, value);
    }

    @FunctionalInterface
    private interface BridgeCall {
        Object call(Context context, Object[] args);
    }
}
