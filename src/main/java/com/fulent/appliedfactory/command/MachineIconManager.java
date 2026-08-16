package com.fulent.appliedfactory.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryRecipes;
import com.fulent.appliedfactory.network.MachineIconsPayload;
import com.fulent.appliedfactory.network.RequestMachineIconsPayload;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side half of the JEI machine-icon lookup: tracks in-flight requests
 * started by the export commands and, when the client answers with the
 * category catalysts it resolved through JEI, merges them over the server-side
 * {@code recipe_types.json} — the client's JEI machines are the authoritative
 * "representative machine" for recipe types that do not declare a toast symbol
 * themselves.
 */
public final class MachineIconManager {
    private static final long TIMEOUT_MS = 5 * 60_000L;
    private static final Gson GSON = new Gson();

    private record Pending(MinecraftServer server, Path root, long createdAt) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private MachineIconManager() {
    }

    /** Asks {@code player} for the JEI machine-icon map of its client. */
    public static void request(MinecraftServer server, ServerPlayer player, Path root) {
        purge();
        var id = UUID.randomUUID();
        PENDING.put(id, new Pending(server, root, System.currentTimeMillis()));
        PacketDistributor.sendToPlayer(player, new RequestMachineIconsPayload(id));
    }

    public static void accept(MachineIconsPayload payload) {
        purge();
        var pending = PENDING.remove(payload.requestId());
        if (pending == null) {
            return;
        }
        var server = pending.server();
        if (!payload.available()) {
            broadcast(server, "appliedfactory: client JEI unavailable; recipe_types.json keeps "
                    + "server-side machine data");
            return;
        }
        JsonObject icons;
        try {
            icons = GSON.fromJson(payload.entriesJson(), JsonObject.class);
        } catch (JsonSyntaxException exception) {
            AppliedFactory.LOGGER.warn("appliedfactory: malformed machine-icon response, ignoring");
            return;
        }
        var merged = readRecipeTypes(pending.root());
        var updated = 0;
        for (var entry : icons.entrySet()) {
            var typeId = entry.getKey();
            if (FactoryRecipes.isCraftingType(typeId)) {
                continue;
            }
            if (!(entry.getValue() instanceof JsonArray machines) || machines.isEmpty()) {
                continue;
            }
            merged.add(typeId, machines);
            updated++;
        }
        try {
            ExportCommand.writeRecipeTypesFile(pending.root(), merged);
            broadcast(server, "appliedfactory: recipe_types.json refreshed with client JEI "
                    + "machine icons (" + updated + " recipe types)");
        } catch (IOException exception) {
            AppliedFactory.LOGGER.error(
                    "appliedfactory: failed to write merged recipe types to {}",
                    pending.root().resolve("recipe_types.json"), exception);
        }
    }

    private static JsonObject readRecipeTypes(Path root) {
        var path = root.resolve("recipe_types.json");
        try {
            if (Files.isRegularFile(path)) {
                var parsed = GSON.fromJson(
                        Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (IOException | JsonParseException exception) {
            AppliedFactory.LOGGER.warn("appliedfactory: failed to read {} for merging",
                    path, exception);
        }
        return new JsonObject();
    }

    private static void broadcast(MinecraftServer server, String message) {
        AppliedFactory.LOGGER.info(message);
        for (var player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private static void purge() {
        var now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > TIMEOUT_MS);
    }
}
