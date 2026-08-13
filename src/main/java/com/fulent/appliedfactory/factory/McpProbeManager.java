package com.fulent.appliedfactory.factory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.script.McpProbeHost;
import com.fulent.appliedfactory.script.ScriptExecutionContext;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side driver that steps MCP probe programs like ordinary jobs on every server tick.
 * A probe program is a plain {@link FactoryProgram} loaded against a {@link McpProbeHost};
 * its {@code go()} passives run with exactly the production scheduler's waiting semantics.
 */
public final class McpProbeManager {
    /** Hard ceiling for probes whose caller specified no timeout (1 hour of game ticks). */
    public static final long HARD_TIMEOUT_TICKS = 72_000L;

    private static final Map<UUID, ActiveProbe> ACTIVE = new HashMap<>();
    private static boolean registered;

    private McpProbeManager() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(McpProbeManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(McpProbeManager::onPlayerLogout);
    }

    /**
     * Loads and runs one probe program. When the source registers passive handlers and
     * {@code timeoutTicks > 0} the run is stepped every tick until all jobs settle or the
     * timeout elapses; otherwise the result is returned immediately.
     *
     * @param timeoutTicks {@code 0} evaluates only (generators are not started),
     *                     {@code -1} waits without a caller timeout (hard ceiling still applies)
     */
    public static void execute(
            UUID playerId,
            UUID requestId,
            FactoryControllerBlockEntity controller,
            String code,
            long timeoutTicks,
            McpProbeSink sink) {
        var host = new McpProbeHost(controller);
        var topLevel = new ScriptExecutionContext(
                UUID.randomUUID(), null, List.of(), List.of());
        var result = FactoryProgram.load(code, host, topLevel);
        if (!result.successful()) {
            sink.onResult(requestId, new McpProbeResult(
                    "error", result.errorMessage(), host.logs(), null, List.of(), 0, 0));
            return;
        }
        var program = result.program();
        if (program.passiveHandlerCount() == 0 || timeoutTicks == 0) {
            program.discard();
            sink.onResult(requestId, new McpProbeResult(
                    "eval_only", "", host.logs(), capJson(program.lastValueJson()), List.of(), 0, 0));
            return;
        }
        var server = controller.getLevel() == null ? null : controller.getLevel().getServer();
        long startedAt = server == null ? 0 : server.getTickCount();
        ACTIVE.put(requestId, new ActiveProbe(
                playerId, host, program, startedAt, timeoutTicks, sink));
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** Cancels and discards every live probe program (player stop / disconnect). */
    public static void cancelAll() {
        for (var probe : List.copyOf(ACTIVE.values())) {
            probe.program().discard();
        }
        ACTIVE.clear();
    }

    /** Cancels every live probe owned by one player (player disconnected / MCP stopped). */
    public static void cancelFor(UUID playerId) {
        var cancelled = ACTIVE.entrySet().stream()
                .filter(entry -> entry.getValue().playerId().equals(playerId))
                .toList();
        for (var entry : cancelled) {
            entry.getValue().program().discard();
            ACTIVE.remove(entry.getKey());
        }
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        cancelFor(event.getEntity().getUUID());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long now = event.getServer().getTickCount();
        for (var entry : List.copyOf(ACTIVE.entrySet())) {
            var probe = entry.getValue();
            probe.program().step();
            probe.bumpSteps();
            long elapsed = now - probe.startedAt();
            if (probe.program().activeJobCount() == 0) {
                ACTIVE.remove(entry.getKey());
                entry.getValue().sink().onResult(entry.getKey(), completed(probe, elapsed));
            } else if (elapsed >= HARD_TIMEOUT_TICKS
                    || (probe.timeoutTicks() > 0 && elapsed >= probe.timeoutTicks())) {
                ACTIVE.remove(entry.getKey());
                probe.program().discard();
                var message = elapsed >= HARD_TIMEOUT_TICKS
                        ? "hard timeout after " + elapsed + " ticks"
                        : "timeout after " + elapsed + " ticks";
                entry.getValue().sink().onResult(entry.getKey(), timeout(probe, elapsed, message));
            }
        }
    }

    private static McpProbeResult completed(ActiveProbe probe, long elapsedTicks) {
        var errors = probe.host().errors();
        return new McpProbeResult(
                errors.isEmpty() ? "completed" : "error",
                errors.isEmpty() ? "" : String.join("\n", errors),
                probe.host().logs(),
                capJson(probe.program().lastValueJson()),
                List.of(),
                elapsedTicks,
                probe.steps());
    }

    private static McpProbeResult timeout(ActiveProbe probe, long elapsedTicks, String message) {
        return new McpProbeResult(
                "timeout",
                message,
                probe.host().logs(),
                capJson(probe.program().lastValueJson()),
                pendingSummaries(probe.program()),
                elapsedTicks,
                probe.steps());
    }

    /** Keeps the result JSON small enough to fit the result packet even with CJK text. */
    private static String capJson(String json) {
        if (json == null || json.length() <= 40_000) {
            return json;
        }
        return "{\"truncated\":true,\"size\":" + json.length() + "}";
    }

    private static final int MAX_PENDING_ENTRIES = 40;
    private static final int MAX_PENDING_LENGTH = 1_500;

    private static List<String> pendingSummaries(FactoryProgram program) {
        return program.activeJobs().stream()
                .map(McpProbeManager::describePending)
                .filter(Objects::nonNull)
                .limit(MAX_PENDING_ENTRIES)
                .map(entry -> entry.length() > MAX_PENDING_LENGTH
                        ? entry.substring(0, MAX_PENDING_LENGTH)
                        : entry)
                .toList();
    }

    private static String describePending(FactoryJob job) {
        var action = job.pendingAction();
        if (action instanceof FactoryTransferAction transfer) {
            var kinds = transfer.remaining().stream()
                    .map(resource -> resource.key().getDisplayName().getString()
                            + " x" + resource.amount())
                    .toList();
            return "transfer [" + String.join(", ", kinds) + "] "
                    + describeEndpoint(transfer.source().endpoint()) + " -> "
                    + describeEndpoint(transfer.target())
                    + " (" + transfer.mode().name().toLowerCase() + ")";
        }
        if (action instanceof FactorySleepAction sleep) {
            return "sleep " + sleep.ticks() + " ticks";
        }
        return null;
    }

    private static String describeEndpoint(FactoryEndpoint endpoint) {
        return endpoint.kind() == FactoryEndpoint.Kind.NETWORK
                ? "network(" + endpoint.networkSide().getName() + ")"
                : "bus@" + endpoint.bus().hostPosition().toShortString()
                        + " side=" + endpoint.bus().side().getName();
    }

    @FunctionalInterface
    public interface McpProbeSink {
        void onResult(UUID requestId, McpProbeResult result);
    }

    private static final class ActiveProbe {
        private final UUID playerId;
        private final McpProbeHost host;
        private final FactoryProgram program;
        private final long startedAt;
        private final long timeoutTicks;
        private final McpProbeSink sink;
        private long steps;

        private ActiveProbe(
                UUID playerId,
                McpProbeHost host,
                FactoryProgram program,
                long startedAt,
                long timeoutTicks,
                McpProbeSink sink) {
            this.playerId = playerId;
            this.host = host;
            this.program = program;
            this.startedAt = startedAt;
            this.timeoutTicks = timeoutTicks;
            this.sink = sink;
        }

        UUID playerId() {
            return playerId;
        }

        McpProbeHost host() {
            return host;
        }

        FactoryProgram program() {
            return program;
        }

        long startedAt() {
            return startedAt;
        }

        long timeoutTicks() {
            return timeoutTicks;
        }

        McpProbeSink sink() {
            return sink;
        }

        long steps() {
            return steps;
        }

        void bumpSteps() {
            steps++;
        }
    }
}
