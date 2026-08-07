package com.fulent.appliedfactory.script;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.util.Arrays;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.serialize.ScriptableInputStream;
import org.mozilla.javascript.serialize.ScriptableOutputStream;

import com.fulent.appliedfactory.AppliedFactory;

/** Rhino implementation of the bus-centric controller API. */
public final class RhinoScriptRuntime implements ScriptRuntime {
    private static final String SOURCE_NAME = "factory-controller";
    private static final int MAX_CONTINUATION_BYTES = 1_048_576;
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;
    private static final int MAX_INSTRUCTIONS_PER_RESUME = 500_000;
    private static final FactoryContextFactory CONTEXT_FACTORY = new FactoryContextFactory();
    private static final ThreadLocal<Integer> INSTRUCTION_COUNT = ThreadLocal.withInitial(() -> 0);

    private RuntimeEnvironment environment;

    @Override
    public ProgramLoadResult<CompiledControllerProgram> loadProgram(String source) {
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
    public ScriptStep startHandler(
            ScriptHandlerRef handler, ScriptExecutionContext context) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        var function = resolveHandler(loaded.registration(), handler);
        if (function == null) {
            if (handler.kind() == ScriptHandlerRef.Kind.INITIALIZER) {
                // No initialize() registration means initialization is trivially complete.
                return new ScriptStep.Completed();
            }
            return new ScriptStep.Failed("Factory handler is not registered: " + handler.kind());
        }
        try {
            return inContext(cx -> {
                loaded.bridge().bind(context, null);
                try {
                    try {
                        cx.callFunctionWithContinuations(function, loaded.scope(),
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

    private static @Nullable Function resolveHandler(
            Registration registration, ScriptHandlerRef handler) {
        return switch (handler.kind()) {
            case CONTROLLER -> registration.controllerHandler;
            case INITIALIZER -> registration.initializer;
            case PATTERN -> handler.index() < registration.patternHandlers.size()
                    ? registration.patternHandlers.get(handler.index())
                    : null;
            case PASSIVE -> handler.index() < registration.passiveHandlers.size()
                    ? registration.passiveHandlers.get(handler.index())
                    : null;
        };
    }

    @Override
    public ScriptStep resume(
            ScriptExecutionContext context,
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
            return inContext(cx -> {
                // A live continuation still references the contextObject it captured at job
                // start; reuse it so the graph and CONTEXT_NAME stay identical. A disk-restored
                // one has none — bind builds a fresh contextObject that deserialization re-links.
                var reuse = continuation instanceof RhinoContinuation live
                        ? live.contextObject()
                        : null;
                loaded.bridge().bind(context, reuse);
                // Only a disk-restored continuation re-links its ctx by name during inflate, so
                // the fresh context object must be resolvable under CONTEXT_NAME for that one
                // persistence step. Live resumes reference the object directly through the graph.
                var relink = !(continuation instanceof RhinoContinuation);
                if (relink) {
                    loaded.bridge().exposeContext();
                }
                try {
                    final Object restored;
                    try {
                        restored = inflate(continuation, loaded.scope());
                    } catch (IOException | ClassNotFoundException exception) {
                        throw new IllegalStateException(
                                "Cannot restore factory script continuation", exception);
                    }
                    try {
                        cx.resumeContinuation(restored, loaded.scope(),
                                loaded.bridge().resultValue(result));
                        return new ScriptStep.Completed();
                    } catch (ContinuationPending pending) {
                        return suspend(loaded.bridge(), pending);
                    }
                } finally {
                    if (relink) {
                        loaded.bridge().hideContext();
                    }
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
                new RhinoContinuation(pending.getContinuation(), bridge.scope(),
                        bridge.contextObject(), bridge.scopeExclusions()),
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

    private static byte[] serializeContinuation(
            Object continuation, Scriptable scope, Set<String> exclusions) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ScriptableOutputStream(bytes, scope)) {
            // Exclude every scope name the bridge installs (prototypes, registration functions,
            // the context object): those carry live bridge state that must never be persisted.
            for (var name : exclusions) {
                output.addExcludedName(name);
            }
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
        private final Set<String> exclusions;
        private byte[] cached;

        private RhinoContinuation(
                Object continuation,
                Scriptable scope,
                Scriptable capturedContextObject,
                Set<String> exclusions) {
            this.continuation = continuation;
            this.scope = scope;
            this.capturedContextObject = capturedContextObject;
            this.exclusions = Set.copyOf(exclusions);
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
                    // serializable) if CONTEXT_NAME resolves to that exact instance. bind() no
                    // longer manages the slot (script execution never reads it), so install it
                    // here for the write, then restore.
                    var previous = ScriptableObject.getProperty(scope, RuntimeBridge.CONTEXT_NAME);
                    ScriptableObject.putProperty(scope, RuntimeBridge.CONTEXT_NAME, capturedContextObject);
                    try {
                        return serializeContinuation(continuation, scope, exclusions);
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                                "Cannot persist factory script continuation", exception);
                    } finally {
                        ScriptableObject.putProperty(scope, RuntimeBridge.CONTEXT_NAME,
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

    private record RuntimeEnvironment(
            Scriptable scope, Registration registration, RuntimeBridge bridge) {
    }

}
