package com.fulent.appliedfactory.script;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryProgram;

/** GraalJS runtime owned by one controller program revision. */
public final class GraalScriptRuntime implements ScriptRuntime {
    private static final String SOURCE_NAME = "factory-controller.js";
    private static final Engine ENGINE = Engine.create("js");
    private static final long EXECUTION_BUDGET_MILLIS = 100;
    private static final long LOAD_BUDGET_MILLIS = 5_000;
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "AppliedFactory-GraalJS-Watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final FactoryProgram.Host host;
    private RuntimeEnvironment environment;
    private String lastValueJson;

    public GraalScriptRuntime(FactoryProgram.Host host) {
        this.host = host;
    }

    @Override
    public ProgramLoadResult<CompiledControllerProgram> loadProgram(
            String source, ScriptExecutionContext topLevelContext) {
        lastValueJson = null;
        closeEnvironment();
        if (source.isBlank()) {
            return ProgramLoadResult.success(CompiledControllerProgram.EMPTY);
        }
        Context context = null;
        try {
            context = newContext();
            var registration = new Registration();
            var api = new ScriptApi(host, registration, context);
            api.install();
            if (topLevelContext != null) {
                api.bind(topLevelContext);
            }
            Value value;
            try {
                var activeContext = context;
                value = withBudget(activeContext, LOAD_BUDGET_MILLIS,
                        () -> activeContext.eval(
                                Source.newBuilder("js", source, SOURCE_NAME).buildLiteral()));
                if (topLevelContext != null) {
                    var element = JsValueSerializer.serialize(value);
                    lastValueJson = element == null ? null : element.toString();
                }
            } finally {
                if (topLevelContext != null) {
                    api.unbind();
                }
            }
            registration.sealed = true;
            environment = new RuntimeEnvironment(context, registration, api);
            return ProgramLoadResult.success(registration.manifest());
        } catch (RuntimeException exception) {
            if (context != null) {
                context.close(true);
            }
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
            ScriptHandlerRef handler, ScriptExecutionContext executionContext) {
        var loaded = environment;
        if (loaded == null) {
            return ProgramLoadResult.failure("Factory program is not loaded");
        }
        var function = resolveHandler(loaded.registration(), handler);
        if (function == null) {
            return ProgramLoadResult.failure("Factory handler is not registered: " + handler);
        }
        try {
            loaded.api().bind(executionContext);
            try {
                var generator = withBudget(loaded.context(), () ->
                        handler.kind() == ScriptHandlerRef.Kind.PATTERN
                                ? function.execute(loaded.api().wrap(loaded.api().order(executionContext)))
                                : function.execute());
                if (!generator.hasMember("next") || !generator.getMember("next").canExecute()) {
                    return ProgramLoadResult.failure("Workflow handler must be a generator function");
                }
                return ProgramLoadResult.success(new ScriptWorkflow(generator));
            } finally {
                loaded.api().unbind();
            }
        } catch (RuntimeException exception) {
            return ProgramLoadResult.failure(messageOf(exception));
        }
    }

    @Override
    public ScriptStep advance(
            ScriptWorkflow workflow,
            ScriptExecutionContext executionContext,
            Object result,
            boolean firstStep) {
        var loaded = environment;
        if (loaded == null) {
            return new ScriptStep.Failed("Factory program is not loaded");
        }
        try {
            loaded.api().bind(executionContext);
            try {
                var iteratorResult = withBudget(loaded.context(), () -> firstStep
                        ? workflow.generator().invokeMember("next")
                        : workflow.generator().invokeMember("next", loaded.api().wrap(result)));
                if (!iteratorResult.hasMembers()) {
                    return new ScriptStep.Failed("Generator.next() returned an invalid result");
                }
                if (iteratorResult.getMember("done").asBoolean()) {
                    return new ScriptStep.Completed();
                }
                var delegate = loaded.api().delegate(iteratorResult.getMember("value"));
                if (delegate instanceof JsTransferAction transfer) {
                    return new ScriptStep.Waiting(transfer.action());
                }
                if (delegate instanceof JsSleepAction sleep) {
                    return new ScriptStep.Waiting(sleep.action());
                }
                return new ScriptStep.Failed("Workflow yielded a value that is not a factory Action");
            } finally {
                loaded.api().unbind();
            }
        } catch (RuntimeException exception) {
            AppliedFactory.LOGGER.warn("Factory workflow step failed", exception);
            return new ScriptStep.Failed(messageOf(exception));
        }
    }

    @Override
    public void runTopologyListeners() {
        var loaded = environment;
        if (loaded != null) {
            withBudget(loaded.context(), () -> {
                loaded.api().fireTopologyListeners();
                return null;
            });
        }
    }

    @Override
    public void close() {
        closeEnvironment();
    }

    private void closeEnvironment() {
        if (environment != null) {
            environment.context().close(true);
            environment = null;
        }
    }

    private static Context newContext() {
        return Context.newBuilder("js")
                .engine(ENGINE)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(ignored -> false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .option("js.ecmascript-version", "2022")
                .build();
    }

    private static Value resolveHandler(Registration registration, ScriptHandlerRef handler) {
        var handlers = handler.kind() == ScriptHandlerRef.Kind.PATTERN
                ? registration.patternHandlers : registration.passiveHandlers;
        return handler.index() < handlers.size() ? handlers.get(handler.index()) : null;
    }

    private static <T> T withBudget(Context context, Supplier<T> action) {
        return withBudget(context, EXECUTION_BUDGET_MILLIS, action);
    }

    private static <T> T withBudget(Context context, long budgetMillis, Supplier<T> action) {
        var running = new AtomicBoolean(true);
        var interrupt = WATCHDOG.schedule(() -> {
            if (!running.get()) {
                return;
            }
            try {
                context.interrupt(Duration.ofMillis(250));
            } catch (TimeoutException exception) {
                AppliedFactory.LOGGER.warn("Timed out interrupting a factory controller script", exception);
            }
        }, budgetMillis, TimeUnit.MILLISECONDS);
        try {
            return action.get();
        } finally {
            running.set(false);
            interrupt.cancel(false);
        }
    }

    private static String messageOf(Throwable throwable) {
        if (throwable instanceof PolyglotException polyglot && polyglot.isGuestException()) {
            return polyglot.getMessage();
        }
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private record RuntimeEnvironment(Context context, Registration registration, ScriptApi api) {
    }
}
