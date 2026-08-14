package com.fulent.appliedfactory.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Matcher;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.factory.FactoryRecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;

/**
 * Local-only reference export: {@code /appliedfactory export [dir]} dumps every
 * registered recipe (id, type, ingredients, result) and every registered AE
 * key-type channel into {@code <dir or game root>/appliedscripts/}. Designed to
 * run in a local save with the same modpack, so no network round-trip is needed
 * even when the real gameplay server is remote.
 */
public final class ExportCommand {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ExportCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("appliedfactory")
                .then(Commands.literal("export")
                        .executes(ctx -> export(ctx.getSource(), null))
                        .then(Commands.argument("dir", StringArgumentType.greedyString())
                                .executes(ctx -> export(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "dir"))))));
    }

    private static int export(CommandSourceStack source, @Nullable String dir) {
        var server = source.getServer();
        try {
            var root = resolveRoot(server, dir);
            Files.createDirectories(root);
            writeRecipes(server, root);
            writeChannels(root);
            patchChannelDeclaration(root);
            source.sendSuccess(
                    () -> Component.literal("appliedfactory: exported recipes/channels to " + root),
                    false);
        } catch (IOException exception) {
            source.sendFailure(Component.literal(
                    "appliedfactory: export failed: " + exception.getMessage()));
        }
        return 1;
    }

    private static Path resolveRoot(MinecraftServer server, @Nullable String dir) {
        var serverDir = server.getServerDirectory();
        if (dir != null && !dir.isBlank()) {
            var given = Path.of(dir);
            return given.isAbsolute() ? given : serverDir.resolve(given);
        }
        return serverDir.resolve("appliedscripts");
    }

    private static void writeRecipes(MinecraftServer server, Path root) throws IOException {
        var result = new JsonObject();
        var recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(holder -> holder.id().toString()));
        for (var holder : recipes) {
            var recipe = holder.value();
            var typeId = FactoryRecipes.typeId(recipe.getType());
            // Crafting-table, stonecutter and smithing recipes are AE crafting
            // patterns, not machine processing, so they are excluded.
            if (FactoryRecipes.isCraftingType(typeId)) {
                continue;
            }
            var obj = new JsonObject();
            obj.addProperty("type", typeId);
            var machine = FactoryRecipes.toastMachine(recipe);
            if (machine != null) {
                obj.addProperty("machine", machine);
            }
            var raw = FactoryRecipes.rawJson(recipe);
            if (raw != null) {
                obj.add("recipe", raw);
            }
            result.add(holder.id().toString(), obj);
        }
        Files.writeString(
                root.resolve("processing_recipes.json"), GSON.toJson(result), StandardCharsets.UTF_8);
    }

    private static void writeChannels(Path root) throws IOException {
        var types = new ArrayList<AEKeyType>(AEKeyTypes.getAll());
        types.sort(Comparator.comparing(type -> type.getId().toString()));
        var array = new JsonArray();
        for (var type : types) {
            var obj = new JsonObject();
            obj.addProperty("id", type.getId().toString());
            obj.addProperty("name", type.getDescription().getString());
            array.add(obj);
        }
        Files.writeString(root.resolve("channels.json"), GSON.toJson(array), StandardCharsets.UTF_8);
    }

    /**
     * Best-effort update of the {@code applied_factory.d.ts}
     * {@code type ResourceChannel} union so the IDE offers autocomplete. Runs
     * against the declaration in the export target directory, so it only takes
     * effect when the exported {@code appliedscripts/} folder is the dev
     * working copy used by the script editor.
     */
    private static void patchChannelDeclaration(Path root) throws IOException {
        var candidate = root.resolve("applied_factory.d.ts");
        if (!Files.isRegularFile(candidate)) {
            return;
        }
        var union = AEKeyTypes.getAll().stream()
                .map(type -> "\"" + type.getId() + "\"")
                .sorted()
                .collect(java.util.stream.Collectors.joining(" | "));
        var line = "type ResourceChannel = " + union + ";";
        var content = Files.readString(candidate, StandardCharsets.UTF_8);
        String patched;
        if (content.contains("type ResourceChannel")) {
            patched = content.replaceFirst(
                    "type ResourceChannel = .*;", Matcher.quoteReplacement(line));
        } else {
            patched = content + (content.isBlank() ? "" : "\n") + line + "\n";
        }
        Files.writeString(candidate, patched, StandardCharsets.UTF_8);
    }
}
