package com.fulent.appliedfactory.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryProgram;

/** Rhino ES6 bytecode runtime driven through native JavaScript generators. */
public final class RhinoScriptRuntime implements ScriptRuntime {
    private static final String SOURCE_NAME = "factory-controller";
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;
    private static final int MAX_INSTRUCTIONS_PER_STEP = 500_000;
    private static final FactoryContextFactory CONTEXT_FACTORY = new FactoryContextFactory();
    private static final ThreadLocal<Integer> INSTRUCTION_COUNT = ThreadLocal.withInitial(() -> 0);

    private final FactoryProgram.Host host;
    private RuntimeEnvironment environment;
    private String lastValueJson;

    public RhinoScriptRuntime(FactoryProgram.Host host) {
        this.host = host;
    }

    @Override
    public ProgramLoadResult<CompiledControllerProgram> loadProgram(
            String source, ScriptExecutionContext topLevelContext) {
        lastValueJson = null;
        if (source.isBlank()) {
            environment = null;
            return ProgramLoadResult.success(CompiledControllerProgram.EMPTY);
        }
        try {
            return inContext(context -> {
                var registration = new Registration();
                Scriptable scope = context.initSafeStandardObjects();
                var api = new ScriptApi(host, registration, scope);
                api.install();
                boolean bound = topLevelContext != null;
                if (bound) {
                    api.bind(topLevelContext);
                }
                try {
                    var value = context.evaluateString(scope, source, SOURCE_NAME, 1, null);
                    if (bound) {
                        var element = JsValueSerializer.serialize(context, scope, value);
                        lastValueJson = element == null ? null : element.toString();
                    }
                } finally {
                    if (bound) {
                        api.unbind();
                    }
                }
                registration.sealed = true;
                environment = new RuntimeEnvironment(scope, registration, api);
                return ProgramLoadResult.success(registration.manifest());
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            environment = null;
            lastValueJson = null;
            var message = messageOf(exception);
            AppliedFactory.LOGGER.warn("Failed to load factory controller program: {}", message);
            return ProgramLoadResult.failure(message);
        }
    }

    @Override
    public String lastValueJson() {
        return lastValueJson;
    }

    @Override
    public ProgramLoadResult<ScriptWorkflow> createWorkflow(
            ScriptHandlerRef handler,
            ScriptExecutionContext context) {
        var loaded = environment;
        if (loaded == null) {
            return ProgramLoadResult.failure("Factory program is not loaded");
        }
        var function = resolveHandler(loaded.registration(), handler);
        if (function == null) {
            return ProgramLoadResult.failure("Factory handler is not registered: " + handler);
        }
        if (!isGeneratorFunction(function)) {
            return ProgramLoadResult.failure("Workflow handler must be a generator function");
        }
        try {
            return inContext(cx -> {
                loaded.api().bind(context);
                try {
                    var args = handler.kind() == ScriptHandlerRef.Kind.PATTERN
                            ? new Object[] { loaded.api().wrap(loaded.api().order(context)) }
                            : new Object[0];
                    var value = function.call(cx, loaded.scope(), loaded.scope(), args);
                    if (!(value instanceof Scriptable generator)
                            || !(ScriptableObject.getProperty(generator, "next")
                                    instanceof Function)) {
                        return ProgramLoadResult.failure(
                                "Workflow handler must be a generator function");
                    }
                    return ProgramLoadResult.success(new ScriptWorkflow(generator));
                } finally {
                    loaded.api().unbind();
                }
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            return ProgramLoadResult.failure(messageOf(exception));
        }
    }

    @Override
    public ScriptStep advance(
            ScriptWorkflow workflow,
            ScriptExecutionContext context,
            Object result,
            boolean firstStep) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        try {
            return inContext(cx -> {
                loaded.api().bind(context);
                try {
                    var next = (Function) ScriptableObject.getProperty(
                            workflow.generator(), "next");
                    var args = firstStep
                            ? new Object[0]
                            : new Object[] { loaded.api().wrap(result) };
                    var raw = next.call(
                            cx, loaded.scope(), workflow.generator(), args);
                    if (!(raw instanceof Scriptable iteratorResult)) {
                        return new ScriptStep.Failed("Generator.next() returned an invalid result");
                    }
                    if (Context.toBoolean(ScriptableObject.getProperty(iteratorResult, "done"))) {
                        return new ScriptStep.Completed();
                    }
                    var yielded = ScriptableObject.getProperty(iteratorResult, "value");
                    var delegate = loaded.api().delegate(yielded);
                    if (delegate instanceof JsTransferAction transfer) {
                        return new ScriptStep.Waiting(transfer.action());
                    }
                    if (delegate instanceof JsSleepAction sleep) {
                        return new ScriptStep.Waiting(sleep.action());
                    }
                    return new ScriptStep.Failed(
                            "Workflow yielded a value that is not a factory Action");
                } finally {
                    loaded.api().unbind();
                }
            });
        } catch (RuntimeException | RhinoInstructionLimitError exception) {
            AppliedFactory.LOGGER.warn("Factory workflow step failed", exception);
            return new ScriptStep.Failed(messageOf(exception));
        }
    }

    @Override
    public void runTopologyListeners() {
        var loaded = environment;
        if (loaded == null) {
            return;
        }
        try {
            inContext(cx -> {
                loaded.api().fireTopologyListeners();
                return Undefined.instance;
            });
        } catch (RhinoInstructionLimitError error) {
            throw new IllegalStateException(messageOf(error), error);
        }
    }

    private static Function resolveHandler(Registration registration, ScriptHandlerRef handler) {
        var handlers = handler.kind() == ScriptHandlerRef.Kind.PATTERN
                ? registration.patternHandlers
                : registration.passiveHandlers;
        return handler.index() < handlers.size() ? handlers.get(handler.index()) : null;
    }

    private static boolean isGeneratorFunction(Function function) {
        return ScriptableObject.getProperty(function, "prototype") instanceof Scriptable prototype
                && ScriptableObject.getProperty(prototype, "next") instanceof Function;
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
            context.setOptimizationLevel(0);
            context.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
            context.setClassShutter(className -> false);
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            var total = INSTRUCTION_COUNT.get() + instructionCount;
            INSTRUCTION_COUNT.set(total);
            if (total > MAX_INSTRUCTIONS_PER_STEP) {
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
            Scriptable scope,
            Registration registration,
            ScriptApi api) {
    }
}
