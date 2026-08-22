package com.fulent.appliedfactory.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryRecipes;
import com.fulent.appliedfactory.factory.FactoryResource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.JsonOps;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;

/**
 * Local-only reference export for the coding-agent workspace.
 *
 * <p>{@code /appliedfactory export [dir]} writes the dynamic data — recipes and
 * AE key-type channels — into {@code <dir or game root>/appliedscripts/}.
 * {@code /appliedfactory setupworkspace [dir]} additionally restores the
 * localized files bundled in {@code assets/appliedfactory/appliedscripts/}
 * and the matching Guide API page, reproducing the whole agent workspace.
 *
 * <p>Recipes are exported as {@code {id, type, inputs, outputs, json}} entries
 * where inputs/outputs use the same {@code {channel, key, amount}} shape the
 * script API's {@code stack(channel, key, amount)} accepts. When a player runs
 * the command, the client re-dumps every recipe through JEI
 * (ae2-jei-integration converters) and the response — including inputs that
 * live outside {@code Recipe#getIngredients()} (e.g. Mystical Agriculture's
 * infusion altar ingredient), fluids and chemicals — is merged over the
 * server-side data by recipe id. The exported {@code processing_recipes.json}
 * is the data source for the client-side {@code require_recipes(filter)}
 * macro: client-authored scripts select the recipes they need with a filter and
 * the client bakes the matching entries into the script before sending or saving it.
 *
 * <p>Both commands require permission level 2 (ops/cheats) because they write
 * files into the server directory. Only the exported files are written or
 * overwritten; unrelated files already present in the target directory (e.g.
 * agent configs) are left untouched.
 */
public final class ExportCommand {
    private static final String WORKSPACE_RESOURCE = "assets/appliedfactory/appliedscripts";
    private static final String GUIDE_RESOURCE = "assets/appliedfactory/ae2guide";
    private static final Set<String> DYNAMIC_FILES =
            Set.of("channels.json", "processing_recipes.json", "recipe_types.json");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ExportCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("appliedfactory")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("export")
                        .executes(ctx -> export(ctx.getSource(), null))
                        .then(Commands.argument("dir", StringArgumentType.greedyString())
                                .executes(ctx -> export(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "dir")))))
                .then(Commands.literal("setupworkspace")
                        .executes(ctx -> setupWorkspace(ctx.getSource(), null))
                        .then(Commands.argument("dir", StringArgumentType.greedyString())
                                .executes(ctx -> setupWorkspace(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "dir"))))));
    }

    private static int export(CommandSourceStack source, @Nullable String dir) {
        var server = source.getServer();
        try {
            var root = resolveRoot(server, dir);
            Files.createDirectories(root);
            writeRecipeTypes(server, root);
            exportRecipes(server, root, source.getPlayer());
            writeChannels(root);
            patchChannelDeclaration(root);
            requestMachineIcons(source, root);
            source.sendSuccess(
                    () -> Component.literal("appliedfactory: exported recipes/channels to " + root),
                    false);
        } catch (IOException exception) {
            source.sendFailure(Component.literal(
                    "appliedfactory: export failed: " + exception.getMessage()));
        }
        return 1;
    }

    private static int setupWorkspace(CommandSourceStack source, @Nullable String dir) {
        var server = source.getServer();
        try {
            var root = resolveRoot(server, dir);
            Files.createDirectories(root);
            var player = source.getPlayer();
            var language = normalizeLanguage(player == null ? "en_us" : player.getLanguage());
            var docs = copyPackagedDocs(root, language);
            writeRecipeTypes(server, root);
            exportRecipes(server, root, source.getPlayer());
            writeChannels(root);
            patchChannelDeclaration(root);
            requestMachineIcons(source, root);
            source.sendSuccess(
                    () -> Component.literal("appliedfactory: workspace ready at " + root
                            + " (" + docs + " doc files, recipe types, channels, recipes)"),
                    false);
        } catch (IOException exception) {
            source.sendFailure(Component.literal(
                    "appliedfactory: setupworkspace failed: " + exception.getMessage()));
        }
        return 1;
    }

    /**
     * Asks the executing player's client for the JEI machine-icon map when a
     * player ran the command; {@link MachineIconManager} merges the answer
     * (the machines JEI shows next to each recipe type) over the freshly
     * written {@code recipe_types.json}. Without a player there is no client
     * to ask, so the file keeps the server-side toast-symbol data.
     */
    private static void requestMachineIcons(CommandSourceStack source, Path root) {
        var player = source.getPlayer();
        if (player != null) {
            MachineIconManager.request(source.getServer(), player, root);
        } else {
            AppliedFactory.LOGGER.info(
                    "appliedfactory: no player to ask for JEI machine icons; recipe_types.json "
                            + "keeps server-side machine data");
        }
    }

    private static Path resolveRoot(MinecraftServer server, @Nullable String dir) {
        var serverDir = server.getServerDirectory();
        if (dir != null && !dir.isBlank()) {
            var given = Path.of(dir);
            return given.isAbsolute() ? given : serverDir.resolve(given);
        }
        return serverDir.resolve("appliedscripts");
    }

    /**
     * Writes the server-side recipe normalization into
     * {@code processing_recipes.json}; the file is the data source the client
     * uses to expand {@code require_recipes()} macros in client-authored scripts. When a
     * player executed the command, that player's client is asked to re-dump
     * every recipe through JEI (ae2-jei-integration converters), and
     * {@link RecipeDumpManager} upgrades the file's inputs/outputs with the
     * client's slots — including inputs that live outside
     * {@code Recipe#getIngredients()} (e.g. Mystical Agriculture's infusion
     * altar ingredient) and fluids/chemicals — once the dump arrives.
     */
    private static void exportRecipes(
            MinecraftServer server, Path root, @Nullable ServerPlayer player) throws IOException {
        var entries = buildRecipeEntries(server);
        writeRecipesFile(root, entries);
        if (player != null) {
            RecipeDumpManager.request(server, player, root, entries);
        } else {
            AppliedFactory.LOGGER.info(
                    "appliedfactory: no player to ask for a JEI recipe dump; keeping server-side data");
        }
    }

    private static JsonArray buildRecipeEntries(MinecraftServer server) {
        var registries = server.registryAccess();
        var entries = new JsonArray();
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
            obj.addProperty("id", holder.id().toString());
            obj.addProperty("type", typeId);
            obj.add("inputs", inputSlotsJson(FactoryRecipes.inputSlots(recipe), registries));
            obj.add("outputs", resourcesJson(FactoryRecipes.outputs(recipe, registries), registries));
            var raw = FactoryRecipes.rawJson(recipe);
            if (raw != null) {
                obj.add("json", raw);
            }
            entries.add(obj);
        }
        return entries;
    }

    /** Serializes resources in the {@code {channel, key, amount}} export shape. */
    static JsonArray resourcesJson(List<FactoryResource> resources, HolderLookup.Provider registries) {
        var array = new JsonArray();
        for (var resource : resources) {
            array.add(resourceJson(resource, registries));
        }
        return array;
    }

    /**
     * Serializes input slots in the {@code {channel, key, amount}} shape. A slot
     * whose ingredient accepts more than one item also carries an
     * {@code options} array of full {@code {channel, key, amount}} entries, so
     * tag/choice slots keep their alternatives (the recipe needs any one of
     * them) instead of being flattened into required items.
     */
    static JsonArray inputSlotsJson(
            List<FactoryRecipes.InputSlot> slots, HolderLookup.Provider registries) {
        var array = new JsonArray();
        for (var slot : slots) {
            var obj = resourceJson(slot.representative(), registries);
            if (slot.options().size() > 1) {
                var options = new JsonArray();
                for (var option : slot.options()) {
                    options.add(resourceJson(option, registries));
                }
                obj.add("options", options);
            }
            array.add(obj);
        }
        return array;
    }

    /** {@code {channel, key, amount}} — the exact shape {@code stack()} accepts. */
    static JsonObject resourceJson(FactoryResource resource, HolderLookup.Provider registries) {
        var obj = new JsonObject();
        obj.addProperty("channel", resource.key().getType().getId().toString());
        obj.add("key", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, resource.key().toTag(registries)));
        obj.addProperty("amount", resource.amount());
        return obj;
    }

    /** Writes the recipe entries to {@code processing_recipes.json} in the workspace root. */
    static void writeRecipesFile(Path root, JsonArray entries) throws IOException {
        Files.writeString(
                root.resolve("processing_recipes.json"), GSON.toJson(entries), StandardCharsets.UTF_8);
    }

    /**
     * Separate {@code recipe_types.json} declaring, per recipe type, the machine
     * blocks that process it (the toast symbols the recipes themselves report),
     * so the per-recipe export can stay {@code {id, type, inputs, outputs, json}}.
     * When a player runs the command, {@link MachineIconManager} later merges
     * the client's JEI catalyst machines over this file; recipe types without a
     * declared machine (the crafting-table default is treated as "none") stay
     * absent until JEI reports a machine for them.
     */
    private static void writeRecipeTypes(MinecraftServer server, Path root) throws IOException {
        var typeMachines = new LinkedHashMap<String, Set<String>>();
        for (var holder : server.getRecipeManager().getRecipes()) {
            var recipe = holder.value();
            var typeId = FactoryRecipes.typeId(recipe.getType());
            if (FactoryRecipes.isCraftingType(typeId)) {
                continue;
            }
            var machine = FactoryRecipes.toastMachine(recipe);
            if (machine != null) {
                typeMachines.computeIfAbsent(typeId, ignored -> new LinkedHashSet<>()).add(machine);
            }
        }
        var result = new JsonObject();
        typeMachines.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var array = new JsonArray();
                    entry.getValue().stream().sorted().forEach(array::add);
                    result.add(entry.getKey(), array);
                });
        writeRecipeTypesFile(root, result);
    }

    /** Package-visible so the JEI machine-icon merge rewrites the same file. */
    static void writeRecipeTypesFile(Path root, JsonObject types) throws IOException {
        Files.writeString(
                root.resolve("recipe_types.json"), GSON.toJson(types), StandardCharsets.UTF_8);
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

    /**
     * Restores the workspace assets, then applies the player's language overlay.
     * Sources are probed in
     * order: classpath directory resources (dev runs read the built resources
     * directory), then the mod jar's zip entries (production), then a hardcoded
     * fallback list. Each file is read through the classloader, so jar and
     * directory classpaths behave identically.
     *
     * @return the number of doc files written
     */
    private static int copyPackagedDocs(Path root, String language) throws IOException {
        var copied = copyPackagedLayer(root, WORKSPACE_RESOURCE, true);
        if (!"en_us".equals(language)) {
            copied += copyPackagedLayer(
                    root, WORKSPACE_RESOURCE + "/_" + language, false);
        }
        copyApiReference(root.resolve("SCRIPT_API.md"), language);
        return copied + 1;
    }

    private static int copyPackagedLayer(
            Path root, String resourceRoot, boolean baseLayer) throws IOException {
        var names = packagedDocNames(resourceRoot, baseLayer);
        var copied = 0;
        var loader = ExportCommand.class.getClassLoader();
        for (var name : names) {
            try (var in = loader.getResourceAsStream(resourceRoot + "/" + name)) {
                if (in == null) {
                    continue;
                }
                var output = root.resolve(name);
                Files.createDirectories(output.getParent());
                Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        return copied;
    }

    private static Set<String> packagedDocNames(
            String resourceRoot, boolean baseLayer) throws IOException {
        var names = new LinkedHashSet<String>();
        // 1. Classpath directories used by development runs.
        var loader = ExportCommand.class.getClassLoader();
        var resources = loader.getResources(resourceRoot);
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(url.toURI());
            } catch (java.net.URISyntaxException exception) {
                continue;
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                        .map(path -> dir.relativize(path).toString().replace('\\', '/'))
                        .forEach(names::add);
            }
        }
        if (names.isEmpty()) {
            // 2. Mod jar: production keeps the packaged docs inside the jar.
            var modFile = ModList.get().getModFileById(AppliedFactory.MOD_ID);
            var filePath = modFile == null ? null : modFile.getFile().getFilePath();
            if (filePath != null && Files.isRegularFile(filePath)) {
                try (var zip = new ZipFile(filePath.toFile())) {
                    var prefix = resourceRoot + "/";
                    for (var entry : Collections.list(zip.entries())) {
                        var name = entry.getName();
                        if (!entry.isDirectory() && name.startsWith(prefix)) {
                            names.add(name.substring(prefix.length()));
                        }
                    }
                }
            }
        }
        if (baseLayer) {
            names.removeIf(name -> name.startsWith("_"));
        }
        names.removeAll(DYNAMIC_FILES);
        return names;
    }

    private static void copyApiReference(Path output, String language) throws IOException {
        var loader = ExportCommand.class.getClassLoader();
        var localized = "en_us".equals(language)
                ? null
                : GUIDE_RESOURCE + "/_" + language + "/applied_factory/script_api.md";
        var in = localized == null ? null : loader.getResourceAsStream(localized);
        if (in == null) {
            in = loader.getResourceAsStream(
                    GUIDE_RESOURCE + "/applied_factory/script_api.md");
        }
        if (in == null) {
            throw new IOException("Missing bundled script API reference");
        }
        try (InputStream selected = in) {
            var markdown = new String(selected.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(output, stripFrontmatter(markdown), StandardCharsets.UTF_8);
        }
    }

    private static String stripFrontmatter(String markdown) {
        var normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return markdown;
        }
        var end = normalized.indexOf("\n---\n", 4);
        return end < 0 ? markdown : normalized.substring(end + 5);
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "en_us";
        }
        return language.toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
