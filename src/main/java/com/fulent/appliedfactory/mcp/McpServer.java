package com.fulent.appliedfactory.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import com.fulent.appliedfactory.AppliedFactory;

/**
 * Localhost HTTP server exposing the MCP Streamable HTTP endpoint at {@code /mcp}. Binds to
 * both IPv4 and IPv6 loopbacks so the endpoint works no matter how a client resolves
 * "localhost" or which loopback family the JVM prefers.
 */
public final class McpServer {
    private final int port;
    private final McpRequestRegistry registry;
    private final McpSseRegistry sse;
    private final String sessionId = "af-" + UUID.randomUUID();
    private final List<HttpServer> servers = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "appliedfactory-mcp");
        thread.setDaemon(true);
        return thread;
    });

    public McpServer(int port, McpRequestRegistry registry, McpSseRegistry sse) {
        this.port = port;
        this.registry = registry;
        this.sse = sse;
    }

    public boolean start() {
        startLoopback(loopback("127.0.0.1"));
        startLoopback(loopback("::1"));
        if (servers.isEmpty()) {
            AppliedFactory.LOGGER.error("Failed to start MCP HTTP server on port {}", port);
            return false;
        }
        return true;
    }

    public void stop() {
        for (var server : List.copyOf(servers)) {
            server.stop(0);
        }
        servers.clear();
        executor.shutdownNow();
        sse.closeAll();
    }

    public boolean isRunning() {
        return !servers.isEmpty();
    }

    public int port() {
        return port;
    }

    public String sessionId() {
        return sessionId;
    }

    private void startLoopback(InetAddress loopback) {
        if (loopback == null) {
            return;
        }
        try {
            var server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
            server.createContext("/mcp", new McpHttpHandler(registry, sse, sessionId));
            server.setExecutor(executor);
            server.start();
            servers.add(server);
        } catch (IOException exception) {
            AppliedFactory.LOGGER.error(
                    "Failed to start MCP HTTP server on {}:{}",
                    loopback.getHostAddress(), port, exception);
        }
    }

    private static InetAddress loopback(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException exception) {
            AppliedFactory.LOGGER.warn("Cannot resolve loopback {}: {}", literal, exception.getMessage());
            return null;
        }
    }
}
