package com.fulent.appliedfactory.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.network.RecipeDumpChunkPayload;
import com.fulent.appliedfactory.network.RequestRecipeDumpPayload;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;

/**
 * Server-side half of the client recipe dump: tracks in-flight requests started
 * by the export commands, assembles the chunked response and merges the
 * client's JEI-normalized inputs/outputs over the server-side generic entries
 * by recipe id before rewriting {@code processing_recipes.json}.
 *
 * <p>The client's slots are authoritative: JEI shows inputs that live outside
 * {@code Recipe#getIngredients()} (e.g. Mystical Agriculture's infusion altar
 * ingredient) and resolves fluids/chemicals, so the merged file replaces the
 * server's generic normalization for every recipe the dump covers. The
 * server-side id/type/json are kept.
 */
public final class RecipeDumpManager {
    private static final long TIMEOUT_MS = 10 * 60_000L;
    private static final Gson GSON = new GsonBuilder().create();

    private record Pending(
            MinecraftServer server,
            Path root,
            JsonArray generic,
            String[] chunks,
            long createdAt) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private RecipeDumpManager() {
    }

    /**
     * Asks {@code player} to dump its JEI-normalized recipes; the generic
     * entries written by the caller are merged with the response when it
     * arrives.
     */
    public static void request(
            MinecraftServer server, ServerPlayer player, Path root, JsonArray generic) {
        purge();
        var id = UUID.randomUUID();
        PENDING.put(id, new Pending(server, root, generic, new String[0], System.currentTimeMillis()));
        PacketDistributor.sendToPlayer(player, new RequestRecipeDumpPayload(id));
    }

    public static void accept(RecipeDumpChunkPayload payload) {
        purge();
        var pending = PENDING.get(payload.requestId());
        if (pending == null) {
            return;
        }
        if (pending.chunks().length == 0) {
            pending = new Pending(
                    pending.server(), pending.root(), pending.generic(),
                    new String[payload.totalChunks()], pending.createdAt());
            PENDING.put(payload.requestId(), pending);
        }
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= pending.chunks().length) {
            return;
        }
        pending.chunks()[payload.chunkIndex()] = payload.entriesJson();
        for (var chunk : pending.chunks()) {
            if (chunk == null) {
                return;
            }
        }
        complete(payload.requestId(), pending, payload.available());
    }

    private static void complete(UUID id, Pending pending, boolean available) {
        PENDING.remove(id);
        var registries = pending.server().registryAccess();
        var byId = new LinkedHashMap<String, JsonObject>();
        for (var element : pending.generic()) {
            var obj = element.getAsJsonObject();
            byId.put(obj.get("id").getAsString(), obj.deepCopy());
        }
        if (available) {
            var dump = GSON.fromJson(String.join("", pending.chunks()), JsonArray.class);
            for (var element : dump) {
                var obj = element.getAsJsonObject();
                var existing = byId.get(obj.get("id").getAsString());
                if (existing == null) {
                    continue;
                }
                var inputs = parseResources(obj.getAsJsonArray("inputs"), registries);
                var outputs = parseResources(obj.getAsJsonArray("outputs"), registries);
                if (inputs == null || outputs == null) {
                    continue;
                }
                // Keep the server-side id/type/json; only the normalized
                // resources are upgraded from the client dump.
                existing.remove("inputs");
                existing.add("inputs", inputs);
                existing.remove("outputs");
                existing.add("outputs", outputs);
            }
        }
        var merged = new JsonArray();
        byId.values().forEach(merged::add);
        try {
            ExportCommand.writeRecipesFile(pending.root(), merged);
            var message = available
                    ? "appliedfactory: processing_recipes.json refreshed from the client JEI dump ("
                            + merged.size() + " recipes)"
                    : "appliedfactory: client JEI dump unavailable; processing_recipes.json keeps "
                            + "server-side data";
            AppliedFactory.LOGGER.info(message);
            for (var player : pending.server().getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(message));
            }
        } catch (IOException exception) {
            AppliedFactory.LOGGER.error(
                    "Failed to write merged recipes to {}", pending.root(), exception);
        }
    }

    /**
     * Validates and re-serializes one dump resource array (entries may carry an
     * {@code options} array of alternatives, which is validated the same way);
     * returns null when any entry cannot be decoded, in which case the generic
     * entry is kept.
     */
    @Nullable
    private static JsonArray parseResources(JsonArray array, HolderLookup.Provider registries) {
        var result = new JsonArray();
        for (var element : array) {
            var obj = element.getAsJsonObject();
            var parsed = parseResource(obj, registries);
            if (parsed == null) {
                return null;
            }
            if (obj.has("options")) {
                var options = new JsonArray();
                for (var option : obj.getAsJsonArray("options")) {
                    if (!(option instanceof JsonObject candidate)) {
                        return null;
                    }
                    var parsedOption = parseResource(candidate, registries);
                    if (parsedOption == null) {
                        return null;
                    }
                    options.add(parsedOption);
                }
                parsed.add("options", options);
            }
            result.add(parsed);
        }
        return result;
    }

    @Nullable
    private static JsonObject parseResource(JsonObject obj, HolderLookup.Provider registries) {
        var id = ResourceLocation.tryParse(obj.get("channel").getAsString());
        if (id == null) {
            return null;
        }
        AEKeyType channel;
        try {
            channel = AEKeyTypes.get(id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        CompoundTag tag;
        try {
            tag = (CompoundTag) JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, obj.get("key"));
        } catch (RuntimeException exception) {
            return null;
        }
        var key = channel.loadKeyFromTag(registries, tag);
        if (key == null) {
            return null;
        }
        var amount = obj.get("amount").getAsLong();
        if (amount <= 0) {
            return null;
        }
        return ExportCommand.resourceJson(new FactoryResource(key, amount), registries);
    }

    private static void purge() {
        var now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > TIMEOUT_MS);
    }
}
