package com.fulent.appliedfactory.script;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.serialize.ScriptableInputStream;
import org.mozilla.javascript.serialize.ScriptableOutputStream;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.part.FactoryBusPart;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Rhino implementation of the bus-centric controller API. */
public final class RhinoScriptRuntime implements ScriptRuntime {
    private static final String SOURCE_NAME = "factory-controller";
    private static final String CONTEXT_NAME = "__factoryContext";
    private static final String BUS_PROTOTYPE_NAME = "__factoryBusPrototype";
    private static final String BUS_ITEMS_PROTOTYPE_NAME = "__factoryBusItemsPrototype";
    private static final String NETWORK_PROTOTYPE_NAME = "__factoryNetworkPrototype";
    private static final String NETWORK_ITEMS_PROTOTYPE_NAME = "__factoryNetworkItemsPrototype";
    private static final String BLOCK_PROTOTYPE_NAME = "__factoryBlockPrototype";
    private static final String RESOURCE_PROTOTYPE_NAME = "__factoryResourcePrototype";
    private static final String BUS_ADDRESS_PROPERTY = "__factoryBusAddress";
    private static final String NETWORK_SIDE_PROPERTY = "__factoryNetworkSide";
    private static final String RESOURCE_KEY_PROPERTY = "__factoryKey";
    private static final String RESOURCE_ITEM_PROPERTY = "__factoryItem";
    private static final String RESOURCE_OWNER_PROPERTY = "__factoryOwner";
    private static final String INITIALIZE_NAME = "initialize";
    private static final String REGISTER_PATTERNS_NAME = "registerPatterns";
    private static final String REGISTER_CONTROLLER_NAME = "registerControllerHandler";
    private static final String REGISTER_PASSIVE_NAME = "registerPassive";
    private static final String ITEM_NAME = "item";
    private static final int MAX_CONTINUATION_BYTES = 1_048_576;
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;
    private static final int MAX_INSTRUCTIONS_PER_RESUME = 500_000;
    private static final FactoryContextFactory CONTEXT_FACTORY = new FactoryContextFactory();
    private static final ThreadLocal<Integer> INSTRUCTION_COUNT = ThreadLocal.withInitial(() -> 0);

    private RuntimeEnvironment environment;

    @Override
    public ProgramLoadResult loadProgram(String source) {
        if (source.isBlank()) {
            environment = null;
            return ProgramLoadResult.success(CompiledControllerProgram.EMPTY);
        }
        try {
            return inContext(context -> {
                var registration = new Registration();
                Scriptable scope = context.initSafeStandardObjects();
                var bridge = new RuntimeBridge(context, scope);
                bridge.install(registration);

                // 对脚本求值获取注册
                context.evaluateString(scope, source, SOURCE_NAME, 1, null);
                registration.sealed = true;
                environment = new RuntimeEnvironment(scope, registration, bridge);
                return ProgramLoadResult.success(registration.manifest());
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            environment = null;
            var message = messageOf(exception);
            AppliedFactory.LOGGER.warn("Failed to load factory controller program: {}", message);
            return ProgramLoadResult.failure(message);
        }
    }

    @Override
    public ScriptStep runInitializer(ScriptExecutionContext request) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        var initializer = loaded.registration().initializer;
        if (initializer == null) {
            return new ScriptStep.Completed();
        }
        try {
            return inContext(context -> {
                loaded.bridge().bind(request, InvocationKind.INITIALIZER, null);
                try {
                    initializer.call(context, loaded.scope(), loaded.scope(),
                            new Object[] { loaded.bridge().contextObject() });
                    return new ScriptStep.Completed();
                } finally {
                    loaded.bridge().unbind();
                }
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            AppliedFactory.LOGGER.warn("Factory initializer failed", exception);
            return new ScriptStep.Failed(messageOf(exception));
        }
    }

    @Override
    public ScriptStep startProcessing(
            ScriptHandlerRef handler, ScriptExecutionContext request) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        Function function;
        if (handler.kind() == ScriptHandlerRef.Kind.CONTROLLER) {
            function = loaded.registration().controllerHandler;
        } else {
            function = handler.index() < loaded.registration().patternHandlers.size()
                    ? loaded.registration().patternHandlers.get(handler.index())
                    : null;
        }
        return start(loaded, function, request, InvocationKind.PROCESSING,
                "Processing handler is not registered");
    }

    @Override
    public ScriptStep startPassive(int handlerIndex, ScriptExecutionContext request) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        var function = handlerIndex >= 0 && handlerIndex < loaded.registration().passiveHandlers.size()
                ? loaded.registration().passiveHandlers.get(handlerIndex)
                : null;
        return start(loaded, function, request, InvocationKind.PASSIVE,
                "Passive handler is not registered");
    }

    private ScriptStep start(
            RuntimeEnvironment loaded,
            Function function,
            ScriptExecutionContext request,
            InvocationKind kind,
            String missingMessage) {
        if (function == null) {
            return new ScriptStep.Failed(missingMessage);
        }
        try {
            return inContext(context -> {
                loaded.bridge().bind(request, kind, null);
                try {
                    try {
                        context.callFunctionWithContinuations(function, loaded.scope(),
                                new Object[] { loaded.bridge().contextObject() });
                        return new ScriptStep.Completed();
                    } catch (ContinuationPending pending) {
                        return suspend(loaded.bridge(), pending);
                    }
                } finally {
                    loaded.bridge().unbind();
                }
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            AppliedFactory.LOGGER.warn("Failed to start factory workflow", exception);
            return new ScriptStep.Failed(messageOf(exception));
        }
    }

    @Override
    public ScriptStep resume(
            ScriptExecutionContext request,
            ScriptContinuation continuation,
            FactoryActionResult result) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        if (continuation == null || continuation.isEmpty()) {
            return new ScriptStep.Failed("Invalid or missing factory script continuation");
        }
        try {
            return inContext(context -> {
                // A live continuation still references the contextObject it captured at job
                // start; reuse it so the graph and CONTEXT_NAME stay identical. A disk-restored
                // one has none — bind builds a fresh contextObject that deserialization re-links.
                var reuse = continuation instanceof RhinoContinuation live
                        ? live.contextObject()
                        : null;
                loaded.bridge().bind(request,
                        request.orderNetwork() == null ? InvocationKind.PASSIVE : InvocationKind.PROCESSING,
                        reuse);
                try {
                    final Object restored;
                    try {
                        restored = inflate(continuation, loaded.scope());
                    } catch (IOException | ClassNotFoundException exception) {
                        throw new IllegalStateException(
                                "Cannot restore factory script continuation", exception);
                    }
                    try {
                        context.resumeContinuation(restored, loaded.scope(),
                                loaded.bridge().resultValue(result));
                        return new ScriptStep.Completed();
                    } catch (ContinuationPending pending) {
                        return suspend(loaded.bridge(), pending);
                    }
                } finally {
                    loaded.bridge().unbind();
                }
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            AppliedFactory.LOGGER.warn("Failed to resume factory workflow", exception);
            return new ScriptStep.Failed(messageOf(exception));
        }
    }

    private static ScriptStep suspend(RuntimeBridge bridge, ContinuationPending pending) {
        if (!(pending.getApplicationState() instanceof FactoryScriptAction action)) {
            return new ScriptStep.Failed("Factory script suspended without a factory action");
        }
        // Keep the continuation live on the heap. Serialization is deferred to
        // saveAdditional() (chunk save/unload) via RhinoContinuation.serialize(). The captured
        // contextObject is the one the continuation graph references; serialize() reinstalls it
        // under CONTEXT_NAME so ScriptableOutputStream can exclude it by name.
        return new ScriptStep.Suspended(
                new RhinoContinuation(pending.getContinuation(), bridge.scope, bridge.contextObject),
                action);
    }

    /**
     * Returns the raw Rhino continuation object ready for {@code resumeContinuation}. A live
     * {@link RhinoContinuation} is resumed as-is (no IO); a disk-restored one is inflated once.
     */
    private static Object inflate(ScriptContinuation continuation, Scriptable scope)
            throws IOException, ClassNotFoundException {
        if (continuation instanceof RhinoContinuation live) {
            return live.raw();
        }
        var bytes = continuation.serialize();
        if (bytes.length > MAX_CONTINUATION_BYTES) {
            throw new IOException("Factory continuation exceeds " + MAX_CONTINUATION_BYTES + " bytes");
        }
        return deserializeContinuation(bytes, scope);
    }

    private static byte[] serializeContinuation(Object continuation, Scriptable scope)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ScriptableOutputStream(bytes, scope)) {
            output.addExcludedName(CONTEXT_NAME);
            output.addExcludedName(BUS_PROTOTYPE_NAME);
            output.addExcludedName(BUS_ITEMS_PROTOTYPE_NAME);
            output.addExcludedName(NETWORK_PROTOTYPE_NAME);
            output.addExcludedName(NETWORK_ITEMS_PROTOTYPE_NAME);
            output.addExcludedName(BLOCK_PROTOTYPE_NAME);
            output.addExcludedName(RESOURCE_PROTOTYPE_NAME);
            output.addExcludedName(INITIALIZE_NAME);
            output.addExcludedName(REGISTER_PATTERNS_NAME);
            output.addExcludedName(REGISTER_CONTROLLER_NAME);
            output.addExcludedName(REGISTER_PASSIVE_NAME);
            output.addExcludedName(ITEM_NAME);
            output.writeObject(continuation);
        }
        var result = bytes.toByteArray();
        if (result.length > MAX_CONTINUATION_BYTES) {
            throw new IOException("Factory continuation exceeds " + MAX_CONTINUATION_BYTES + " bytes");
        }
        return result;
    }

    private static Object deserializeContinuation(byte[] bytes, Scriptable scope)
            throws IOException, ClassNotFoundException {
        try (var input = new ScriptableInputStream(new ByteArrayInputStream(bytes), scope)) {
            input.setObjectInputFilter(RhinoScriptRuntime::filterSerializedClass);
            return input.readObject();
        }
    }

    private static ObjectInputFilter.Status filterSerializedClass(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > 128 || info.references() > 100_000
                || info.arrayLength() > 1_000_000) {
            return ObjectInputFilter.Status.REJECTED;
        }
        var type = info.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive()) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        var name = type.getName();
        return name.startsWith("org.mozilla.javascript.")
                || name.startsWith("java.lang.")
                || name.startsWith("java.util.")
                || name.startsWith("java.math.")
                ? ObjectInputFilter.Status.ALLOWED
                : ObjectInputFilter.Status.REJECTED;
    }

    /**
     * A live continuation held on the JVM heap between ticks. Resumed directly without any IO;
     * serialized (once, then cached) only when the controller is persisted. A live continuation
     * is only valid within the runtime instance that captured it — the scheduler always resumes
     * a job on the runtime keyed by its own program source, so the captured scope matches.
     */
    private static final class RhinoContinuation implements ScriptContinuation {
        private final Object continuation;
        private final Scriptable scope;
        private final Scriptable capturedContextObject;
        private byte[] cached;

        private RhinoContinuation(
                Object continuation, Scriptable scope, Scriptable capturedContextObject) {
            this.continuation = continuation;
            this.scope = scope;
            this.capturedContextObject = capturedContextObject;
        }

        private Object raw() {
            return continuation;
        }

        private Scriptable contextObject() {
            return capturedContextObject;
        }

        @Override
        public byte[] serialize() {
            if (cached == null) {
                // Serialization runs at chunk-save time, outside any script invocation, so no
                // Rhino Context is bound to the thread. ScriptableOutputStream lazily materializes
                // standard objects (e.g. RegExp) through the current Context while excluding them,
                // which fails without one. Enter a Context for the duration of the write.
                cached = inContext(cx -> {
                    // The continuation graph references capturedContextObject via the script's
                    // ctx local. ScriptableOutputStream can only exclude it (its closures are not
                    // serializable) if CONTEXT_NAME resolves to that exact instance. bind/unbind
                    // has since cleared it, so reinstall it for the write, then restore.
                    var previous = ScriptableObject.getProperty(scope, CONTEXT_NAME);
                    ScriptableObject.putProperty(scope, CONTEXT_NAME, capturedContextObject);
                    try {
                        return serializeContinuation(continuation, scope);
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                                "Cannot persist factory script continuation", exception);
                    } finally {
                        ScriptableObject.putProperty(scope, CONTEXT_NAME,
                                previous == Scriptable.NOT_FOUND ? Undefined.instance : previous);
                    }
                });
            }
            return Arrays.copyOf(cached, cached.length);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }

    private static <T> T inContext(ContextAction<T> action) {
        INSTRUCTION_COUNT.set(0);
        try {
            return CONTEXT_FACTORY.call(action);
        } finally {
            INSTRUCTION_COUNT.remove();
        }
    }

    private static String messageOf(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private static final class FactoryContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            var context = super.makeContext();
            context.setLanguageVersion(Context.VERSION_ES6);
            context.setInterpretedMode(true);
            context.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            var total = INSTRUCTION_COUNT.get() + instructionCount;
            INSTRUCTION_COUNT.set(total);
            if (total > MAX_INSTRUCTIONS_PER_RESUME) {
                throw new RhinoInstructionLimitError();
            }
        }
    }

    private static final class RhinoInstructionLimitError extends Error {
        private static final long serialVersionUID = 1L;

        private RhinoInstructionLimitError() {
            super("Factory script exceeded its instruction budget");
        }
    }

    private static final class Registration {
        private final Set<Direction> initializerNetworks = EnumSet.noneOf(Direction.class);
        private final List<CompiledControllerProgram.ScriptPattern> patterns = new ArrayList<>();
        private final List<Function> patternHandlers = new ArrayList<>();
        private final List<Function> passiveHandlers = new ArrayList<>();
        private Function initializer;
        private Function controllerHandler;
        private Direction controllerOrderNetwork;
        private boolean sealed;

        private void requireOpen() {
            if (sealed) {
                throw Context.reportRuntimeError(
                        "Registration APIs may only be called while loading controller source");
            }
        }

        private CompiledControllerProgram manifest() {
            return new CompiledControllerProgram(
                    initializerNetworks,
                    controllerOrderNetwork,
                    patterns,
                    passiveHandlers.size());
        }
    }

    private record RuntimeEnvironment(
            Scriptable scope, Registration registration, RuntimeBridge bridge) {
    }

    private enum InvocationKind {
        INITIALIZER,
        PROCESSING,
        PASSIVE
    }

    private static final class RuntimeBridge {
        private final Context installContext;
        private final Scriptable scope;
        private Scriptable busPrototype;
        private Scriptable busItemsPrototype;
        private Scriptable networkPrototype;
        private Scriptable networkItemsPrototype;
        private Scriptable blockPrototype;
        private Scriptable resourcePrototype;
        private Scriptable contextObject;
        private ScriptExecutionContext request;
        private InvocationKind invocationKind;

        private RuntimeBridge(Context context, Scriptable scope) {
            installContext = context;
            this.scope = scope;
        }

        private void install(Registration registration) {
            busPrototype = createBusPrototype();
            busItemsPrototype = createBusItemsPrototype();
            networkPrototype = createNetworkPrototype();
            networkItemsPrototype = createNetworkItemsPrototype();
            blockPrototype = createBlockPrototype();
            resourcePrototype = createResourcePrototype();
            ScriptableObject.putProperty(scope, BUS_PROTOTYPE_NAME, busPrototype);
            ScriptableObject.putProperty(scope, BUS_ITEMS_PROTOTYPE_NAME, busItemsPrototype);
            ScriptableObject.putProperty(scope, NETWORK_PROTOTYPE_NAME, networkPrototype);
            ScriptableObject.putProperty(scope, NETWORK_ITEMS_PROTOTYPE_NAME, networkItemsPrototype);
            ScriptableObject.putProperty(scope, BLOCK_PROTOTYPE_NAME, blockPrototype);
            ScriptableObject.putProperty(scope, RESOURCE_PROTOTYPE_NAME, resourcePrototype);
            ScriptableObject.putProperty(scope, INITIALIZE_NAME, initializeFunction(registration));
            ScriptableObject.putProperty(scope, REGISTER_PATTERNS_NAME, patternsFunction(registration));
            ScriptableObject.putProperty(scope, REGISTER_CONTROLLER_NAME,
                    controllerFunction(registration));
            ScriptableObject.putProperty(scope, REGISTER_PASSIVE_NAME, passiveFunction(registration));
            ScriptableObject.putProperty(scope, ITEM_NAME, itemFunction());
            ScriptableObject.putProperty(scope, CONTEXT_NAME, Undefined.instance);
        }

        /**
         * Binds this invocation's live data. A resumed live continuation passes the
         * {@code reuseContextObject} it captured at job start, so the object the continuation
         * graph still references stays identical to the one installed under {@link #CONTEXT_NAME}.
         * Fresh starts and disk-restored resumes pass {@code null} to build a new one (the latter
         * gets re-linked into the graph by name during deserialization).
         */
        private void bind(
                ScriptExecutionContext request,
                InvocationKind kind,
                @Nullable Scriptable reuseContextObject) {
            this.request = request;
            invocationKind = kind;
            contextObject = reuseContextObject != null
                    ? reuseContextObject
                    : createContextObject(Context.getCurrentContext());
            ScriptableObject.putProperty(scope, CONTEXT_NAME, contextObject);
        }

        private void unbind() {
            request = null;
            invocationKind = null;
            contextObject = null;
            ScriptableObject.putProperty(scope, CONTEXT_NAME, Undefined.instance);
        }

        private Scriptable contextObject() {
            return contextObject;
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
                requireArity(args, 2, 2, "item(id, amount)");
                var id = ResourceLocation.tryParse(Context.toString(args[0]));
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                    throw scriptError("Unknown item id: " + Context.toString(args[0]));
                }
                return itemResourceObject(cx, id, positiveLong(args[1], "item amount"));
            });
        }

        private Scriptable createContextObject(Context cx) {
            var result = cx.newObject(scope);
            // Snapshot fields are live getters, not fixed values: this contextObject instance is
            // reused across every resume of the job (so the continuation graph and CONTEXT_NAME
            // stay identical for serialization), yet each read must reflect the current tick's
            // request. bind() rebinds `request` before each resume; the getters read it live.
            defineLiveReadOnly(result, "tick", () -> (double) request.tick());
            defineLiveReadOnly(result, "buses",
                    () -> busArray(Context.getCurrentContext(), request.allBuses()));
            defineReadOnly(result, "network", function((callContext, args) -> {
                requireArity(args, 1, 1, "ctx.network(side)");
                var side = requireDirection(args[0], "network side");
                if (!request.canAccess(side)) {
                    throw scriptError("Network " + side.getName() + " is not accessible here");
                }
                return networkObject(callContext, side);
            }));
            defineReadOnly(result, "log", function((callContext, args) -> {
                requireArity(args, 1, 1, "ctx.log(message)");
                AppliedFactory.LOGGER.info("[Factory script] {}", Context.toString(args[0]));
                return Undefined.instance;
            }));
            if (invocationKind != InvocationKind.INITIALIZER) {
                defineReadOnly(result, "sleep", function((callContext, args) -> {
                    requireArity(args, 1, 1, "ctx.sleep(ticks)");
                    return suspend(callContext, FactoryScriptAction.sleep(
                            nonNegativeInt(args[0], "sleep duration")));
                }));
                defineReadOnly(result, "yield", function((callContext, args) -> {
                    requireArity(args, 0, 0, "ctx.yield()");
                    return suspend(callContext, FactoryScriptAction.sleep(1));
                }));
                defineReadOnly(result, "fail", function((callContext, args) -> {
                    requireArity(args, 1, 1, "ctx.fail(message)");
                    throw scriptError(Context.toString(args[0]));
                }));
            }
            if (invocationKind == InvocationKind.PROCESSING) {
                defineLiveReadOnly(result, "inputs", () -> resourceArray(
                        Context.getCurrentContext(), request.inputs(), request.workflowId()));
                defineLiveReadOnly(result, "outputs", () -> resourceArray(
                        Context.getCurrentContext(), request.outputs(), null));
                defineLiveReadOnly(result, "owned", () -> resourceArray(
                        Context.getCurrentContext(), request.owned(), request.workflowId()));
                if (request.orderNetwork() == null) {
                    throw new IllegalStateException("Processing context has no order network");
                }
                defineLiveReadOnly(result, "orderNetwork", () -> {
                    var orderSide = request.orderNetwork();
                    if (orderSide == null) {
                        throw scriptError("Processing context has no order network");
                    }
                    return networkObject(Context.getCurrentContext(), orderSide);
                });
            } else if (invocationKind == InvocationKind.PASSIVE) {
                defineLiveReadOnly(result, "owned", () -> resourceArray(
                        Context.getCurrentContext(), request.owned(), request.workflowId()));
            }
            return result;
        }

        private Scriptable createBusPrototype() {
            var result = installContext.newObject(scope);
            defineReadOnly(result, "exists", method((cx, self, args) -> {
                requireArity(args, 0, 0, "bus.exists()");
                return resolveBus(requireBus(self)).isPresent();
            }));
            defineReadOnly(result, "state", method((cx, self, args) -> {
                requireArity(args, 0, 0, "bus.state()");
                var bus = resolveBus(requireBus(self)).orElse(null);
                return bus == null ? null : busStateObject(cx, bus);
            }));
            defineReadOnly(result, "target", method((cx, self, args) -> {
                requireArity(args, 0, 0, "bus.target()");
                var bus = resolveBus(requireBus(self)).orElse(null);
                return bus == null ? null : bus.machine().map(machine -> blockObject(cx, machine))
                        .orElse(null);
            }));
            defineReadOnly(result, "items", method((cx, self, args) -> {
                requireArity(args, 0, 0, "bus.items()");
                var handle = requireBus(self);
                var bus = resolveBus(handle).orElse(null);
                if (bus == null || bus.machine().filter(machine -> machine.hasItemStorage()).isEmpty()) {
                    return null;
                }
                return busItemsObject(cx, handle);
            }));
            defineReadOnly(result, "detect", method((cx, self, args) -> {
                requireArity(args, 1, 1, "bus.detect(selector)");
                var bus = resolveBus(requireBus(self)).orElse(null);
                var machine = bus == null ? null : bus.machine().orElse(null);
                return machine != null && machine.matchesBlock(Context.toString(args[0]));
            }));
            defineReadOnly(result, "drop", method((cx, self, args) -> {
                requireWorkflow("bus.drop");
                requireArity(args, 1, 1, "bus.drop(resources)");
                return suspend(cx, FactoryScriptAction.busDrop(
                        requireBus(self), parseOwned(args[0])));
            }));
            defineReadOnly(result, "use", method((cx, self, args) -> {
                requireWorkflow("bus.use");
                requireArity(args, 0, 0, "bus.use()");
                return suspend(cx, FactoryScriptAction.busUse(requireBus(self)));
            }));
            defineReadOnly(result, "place", method((cx, self, args) -> {
                requireWorkflow("bus.place");
                requireArity(args, 1, 1, "bus.place(resource)");
                return suspend(cx, FactoryScriptAction.busPlace(
                        requireBus(self), parseOwnedUnit(args[0])));
            }));
            defineReadOnly(result, "break", method((cx, self, args) -> {
                requireWorkflow("bus.break");
                requireArity(args, 0, 0, "bus.break()");
                return suspend(cx, FactoryScriptAction.busBreak(requireBus(self)));
            }));
            defineReadOnly(result, "redstone", method((cx, self, args) -> {
                requireWorkflow("bus.redstone");
                requireArity(args, 1, 1, "bus.redstone(level)");
                var level = nonNegativeInt(args[0], "redstone level");
                if (level > 15) {
                    throw scriptError("redstone level must be between 0 and 15");
                }
                return suspend(cx, FactoryScriptAction.busRedstone(requireBus(self), level));
            }));
            return result;
        }

        private Scriptable createBusItemsPrototype() {
            var result = installContext.newObject(scope);
            defineReadOnly(result, "read", method((cx, self, args) -> {
                requireArity(args, 0, 0, "bus.items().read()");
                var bus = resolveBus(requireBus(self)).orElse(null);
                if (bus == null) {
                    return cx.newArray(scope, 0);
                }
                var resources = bus.machine().map(machine -> resources(machine.items()))
                        .orElse(List.of());
                return resourceArray(cx, resources, null);
            }));
            defineReadOnly(result, "push", method((cx, self, args) -> {
                requireWorkflow("bus.items().push");
                requireArity(args, 1, 1, "bus.items().push(resources)");
                return suspend(cx, FactoryScriptAction.busPush(
                        requireBus(self), parseOwned(args[0])));
            }));
            defineReadOnly(result, "extract", method((cx, self, args) -> {
                requireWorkflow("bus.items().extract");
                requireArity(args, 0, 0, "bus.items().extract()");
                return suspend(cx, FactoryScriptAction.busExtract(requireBus(self)));
            }));
            return result;
        }

        private Scriptable createNetworkPrototype() {
            var result = installContext.newObject(scope);
            defineReadOnly(result, "online", method((cx, self, args) -> {
                requireArity(args, 0, 0, "network.online()");
                return request.isOnline(requireNetwork(self));
            }));
            defineReadOnly(result, "items", method((cx, self, args) -> {
                requireArity(args, 0, 0, "network.items()");
                return networkItemsObject(cx, requireNetwork(self));
            }));
            return result;
        }

        private Scriptable createNetworkItemsPrototype() {
            var result = installContext.newObject(scope);
            defineReadOnly(result, "push", method((cx, self, args) -> {
                requireWorkflow("network.items().push");
                requireArity(args, 1, 1, "network.items().push(resources)");
                return suspend(cx, FactoryScriptAction.networkPush(
                        requireNetwork(self), parseOwned(args[0])));
            }));
            defineReadOnly(result, "extract", method((cx, self, args) -> {
                requireWorkflow("network.items().extract");
                requireArity(args, 1, 1, "network.items().extract(requests)");
                return suspend(cx, FactoryScriptAction.networkExtract(
                        requireNetwork(self), parseResourceSpecs(args[0], "network requests")));
            }));
            return result;
        }

        private Scriptable createResourcePrototype() {
            var result = installContext.newObject(scope);
            defineReadOnly(result, "matches", method((cx, self, args) -> {
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
            }));
            return result;
        }

        private Scriptable createBlockPrototype() {
            var result = installContext.newObject(scope);
            defineReadOnly(result, "matches", method((cx, self, args) -> {
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
            }));
            return result;
        }

        private Scriptable busObject(Context cx, FactoryBusPart bus) {
            var address = bus.address().orElseThrow();
            var result = cx.newObject(scope);
            result.setPrototype(busPrototype);
            defineInternal(result, BUS_ADDRESS_PROPERTY, encodeAddress(address));
            defineReadOnly(result, "address", busAddressObject(cx, address));
            var targetPosition = address.hostPosition().relative(address.side());
            defineReadOnly(result, "targetAddress",
                    blockAddressObject(cx, address.dimension(), targetPosition));
            defineReadOnly(result, "targetFace", address.side().getName());

            return result;
        }

        private Scriptable busItemsObject(Context cx, FactoryBusAddress address) {
            var result = cx.newObject(scope);
            result.setPrototype(busItemsPrototype);
            defineInternal(result, BUS_ADDRESS_PROPERTY, encodeAddress(address));
            return result;
        }

        private Scriptable networkObject(Context cx, Direction side) {
            var result = cx.newObject(scope);
            result.setPrototype(networkPrototype);
            defineInternal(result, NETWORK_SIDE_PROPERTY, side.getName());
            defineReadOnly(result, "side", side.getName());
            defineReadOnly(result, "buses", busArray(cx, request.buses(side)));
            return result;
        }

        private Scriptable networkItemsObject(Context cx, Direction side) {
            var result = cx.newObject(scope);
            result.setPrototype(networkItemsPrototype);
            defineInternal(result, NETWORK_SIDE_PROPERTY, side.getName());
            return result;
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
            var result = cx.newObject(scope);
            defineReadOnly(result, "dimension", address.dimension().toString());
            defineReadOnly(result, "hostX", address.hostPosition().getX());
            defineReadOnly(result, "hostY", address.hostPosition().getY());
            defineReadOnly(result, "hostZ", address.hostPosition().getZ());
            defineReadOnly(result, "partSide", address.side().getName());
            defineReadOnly(result, "key", encodeAddress(address));
            return result;
        }

        private Scriptable blockAddressObject(
                Context cx, ResourceLocation dimension, BlockPos position) {
            var result = cx.newObject(scope);
            defineReadOnly(result, "dimension", dimension.toString());
            defineReadOnly(result, "x", position.getX());
            defineReadOnly(result, "y", position.getY());
            defineReadOnly(result, "z", position.getZ());
            defineReadOnly(result, "key", dimension + ":" + position.asLong());
            return result;
        }

        private Scriptable busStateObject(Context cx, FactoryBusPart bus) {
            var result = cx.newObject(scope);
            defineReadOnly(result, "active", bus.isActive());
            defineReadOnly(result, "powered", bus.isPowered());
            defineReadOnly(result, "redstone",
                    bus.machine().map(machine -> machine.redstoneLevel()).orElse(0));
            var upgrades = cx.newObject(scope);
            defineReadOnly(upgrades, "acceleration", bus.accelerationCards());
            defineReadOnly(result, "upgrades", upgrades);
            defineReadOnly(result, "config", cx.newObject(scope));
            return result;
        }

        private Scriptable blockObject(Context cx,
                com.fulent.appliedfactory.factory.FactoryMachineAccess machine) {
            var result = cx.newObject(scope);
            result.setPrototype(blockPrototype);
            defineReadOnly(result, "id", machine.blockId().toString());
            var state = cx.newObject(scope);
            var blockState = machine.blockState();
            for (var property : blockState.getProperties()) {
                defineReadOnly(state, property.getName(), propertyName(blockState, property));
            }
            defineReadOnly(result, "state", state);
            var blockEntityType = machine.blockEntityTypeId();
            defineReadOnly(result, "blockEntityType",
                    blockEntityType == null ? null : blockEntityType.toString());
            return result;
        }

        private Scriptable itemResourceObject(Context cx, ResourceLocation id, long amount) {
            var result = cx.newObject(scope);
            result.setPrototype(resourcePrototype);
            defineReadOnly(result, "id", id.toString());
            defineReadOnly(result, "amount", (double) amount);
            defineInternal(result, RESOURCE_ITEM_PROPERTY, id.toString());
            defineInternal(result, RESOURCE_OWNER_PROPERTY, "");
            return result;
        }

        private Scriptable resourceObject(
                Context cx, FactoryResource resource, UUID owner) {
            var result = cx.newObject(scope);
            result.setPrototype(resourcePrototype);
            defineReadOnly(result, "id", resource.id().toString());
            defineReadOnly(result, "amount", (double) resource.amount());
            defineInternal(result, RESOURCE_KEY_PROPERTY,
                    resource.key().toTagGeneric(request.registries()).toString());
            defineInternal(result, RESOURCE_OWNER_PROPERTY,
                    owner == null ? "" : owner.toString());
            return result;
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
            var owner = request.workflowId().toString();
            for (var object : resources) {
                var objectOwner = ScriptableObject.getProperty(object, RESOURCE_OWNER_PROPERTY);
                if (objectOwner == Scriptable.NOT_FOUND
                        || !owner.equals(Context.toString(objectOwner))) {
                    throw scriptError("push only accepts resources owned by this workflow");
                }
            }
            var parsed = normalizeParsed(resources);
            if (!canSubtract(request.owned(), parsed)) {
                throw scriptError("Workflow no longer owns the requested resource amount");
            }
            return parsed;
        }

        /** Returns exactly one owned item while allowing a larger owned stack to be placed gradually. */
        private FactoryResource parseOwnedUnit(Object value) {
            var object = requireResource(value, "placement resource");
            var owner = ScriptableObject.getProperty(object, RESOURCE_OWNER_PROPERTY);
            if (owner == Scriptable.NOT_FOUND
                    || !request.workflowId().toString().equals(Context.toString(owner))) {
                throw scriptError("place only accepts a resource owned by this workflow");
            }
            // Read and validate the visible amount even though one item is consumed per call.
            positiveLong(ScriptableObject.getProperty(object, "amount"), "resource amount");
            var resource = new FactoryResource(resourceKey(object), 1);
            if (!canSubtract(request.owned(), List.of(resource))) {
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

            // Rhino serializes a suspended continuation when an action yields.  On resume it
            // can restore the resource object's JavaScript prototype as a distinct Java object,
            // despite retaining all of the immutable descriptor fields.  Prototype reference
            // equality would therefore reject an OwnedResource returned by extract()/break().
            // The hidden, permanent fields are the durable API brand; resourceKey() still fully
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
                            request.registries(), TagParser.parseTag(Context.toString(keyTag)));
                    if (key != null) {
                        return key;
                    }
                } catch (CommandSyntaxException ignored) {
                    // Report the same safe script error for corrupt and forged descriptors.
                }
                throw scriptError("Resource contains an invalid exact AE key");
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
            if (side == null || !request.canAccess(side)) {
                throw scriptError("Network handle is invalid or inaccessible");
            }
            return side;
        }

        private Optional<FactoryBusPart> resolveBus(FactoryBusAddress address) {
            return request.resolveBus(address);
        }

        private Object resultValue(FactoryActionResult result) {
            return switch (result.kind()) {
                case BOOLEAN -> result.success();
                case RESOURCES -> resourceArray(
                        Context.getCurrentContext(), result.resources(), request.workflowId());
                case VOID -> Undefined.instance;
            };
        }

        private Object suspend(Context cx, FactoryScriptAction action) {
            var pending = cx.captureContinuation();
            pending.setApplicationState(action);
            throw pending;
        }

        private void requireWorkflow(String operation) {
            if (invocationKind == InvocationKind.INITIALIZER) {
                throw scriptError(operation + " is not available in initialize");
            }
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

        private static BaseFunction method(BridgeMethod call) {
            return new BaseFunction() {
                private static final long serialVersionUID = 1L;

                @Override
                public Object call(
                        Context context,
                        Scriptable callScope,
                        Scriptable thisObject,
                        Object[] args) {
                    return call.call(context, thisObject, args);
                }
            };
        }

        private static void defineReadOnly(Scriptable object, String name, Object value) {
            if (object instanceof ScriptableObject scriptable) {
                scriptable.defineProperty(
                        name, value, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
                return;
            }
            throw new IllegalStateException("Factory script objects must support fixed properties");
        }

        /**
         * A read-only property whose value is recomputed from the live bridge state on each read.
         * The getter Supplier captures the bridge (not serializable), which is fine: contextObject
         * is excluded by name at serialization, so these slots are never written to disk. The
         * setter rejects writes to keep the same contract as {@link #defineReadOnly}.
         */
        private static void defineLiveReadOnly(
                Scriptable object, String name, Supplier<Object> getter) {
            if (object instanceof ScriptableObject scriptable) {
                scriptable.defineProperty(
                        name,
                        getter,
                        value -> {
                            throw scriptError("ctx." + name + " is read-only");
                        },
                        ScriptableObject.PERMANENT);
                return;
            }
            throw new IllegalStateException("Factory script objects must support fixed properties");
        }

        private static void defineInternal(Scriptable object, String name, Object value) {
            if (object instanceof ScriptableObject scriptable) {
                scriptable.defineProperty(
                        name,
                        value,
                        ScriptableObject.READONLY
                                | ScriptableObject.PERMANENT
                                | ScriptableObject.DONTENUM);
                return;
            }
            throw new IllegalStateException("Factory script objects must support internal properties");
        }

        @FunctionalInterface
        private interface BridgeCall {
            Object call(Context context, Object[] args);
        }

        @FunctionalInterface
        private interface BridgeMethod {
            Object call(Context context, Scriptable receiver, Object[] args);
        }
    }
}
