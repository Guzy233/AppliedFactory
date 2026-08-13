package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;

import net.minecraft.server.MinecraftServer;

/**
 * Read-only index of every processing recipe (crafting/stonecutter/smithing
 * excluded) plus the bidirectional machine-to-recipe-type lookup. Built once
 * from {@link MinecraftServer#getRecipeManager()}.
 */
public final class FactoryRecipeIndex {
    private static final Gson GSON = new Gson();

    private final List<FactoryRecipe> recipes;
    private final Map<String, List<FactoryRecipe>> byType;
    private final Map<String, List<String>> machineTypes;
    private final Map<String, List<String>> typeMachines;

    private FactoryRecipeIndex(
            List<FactoryRecipe> recipes,
            Map<String, List<FactoryRecipe>> byType,
            Map<String, List<String>> machineTypes,
            Map<String, List<String>> typeMachines) {
        this.recipes = recipes;
        this.byType = byType;
        this.machineTypes = machineTypes;
        this.typeMachines = typeMachines;
    }

    public static FactoryRecipeIndex build(MinecraftServer server) {
        var recipes = new ArrayList<FactoryRecipe>();
        var byType = new HashMap<String, List<FactoryRecipe>>();
        var machineTypes = new HashMap<String, Set<String>>();
        var typeMachines = new HashMap<String, Set<String>>();
        for (var holder : server.getRecipeManager().getRecipes()) {
            var recipe = holder.value();
            var typeId = FactoryRecipes.typeId(recipe.getType());
            if (FactoryRecipes.isCraftingType(typeId)) {
                continue;
            }
            var id = holder.id().toString();
            var machine = FactoryRecipes.toastMachine(recipe);
            var json = FactoryRecipes.rawJson(recipe);
            var info = new FactoryRecipe(id, typeId, machine,
                    json == null ? null : GSON.fromJson(json, Object.class));
            recipes.add(info);
            byType.computeIfAbsent(typeId, ignored -> new ArrayList<>()).add(info);
            if (machine != null) {
                machineTypes.computeIfAbsent(machine, ignored -> new LinkedHashSet<>()).add(typeId);
                typeMachines.computeIfAbsent(typeId, ignored -> new LinkedHashSet<>()).add(machine);
            }
        }
        recipes.sort(Comparator.comparing(FactoryRecipe::id));
        return new FactoryRecipeIndex(
                List.copyOf(recipes),
                frozen(byType),
                frozenStrings(machineTypes),
                frozenStrings(typeMachines));
    }

    public List<FactoryRecipe> all() {
        return recipes;
    }

    public List<FactoryRecipe> ofType(String typeId) {
        return byType.getOrDefault(typeId, List.of());
    }

    /** Recipe types that recipes with the given machine toast symbol use. */
    public List<String> typesOfMachine(String machineId) {
        return machineTypes.getOrDefault(machineId, List.of());
    }

    /** Machine toast symbols found among recipes of the given type. */
    public List<String> machinesOfType(String typeId) {
        return typeMachines.getOrDefault(typeId, List.of());
    }

    private static <T> Map<String, List<T>> frozen(Map<String, List<T>> source) {
        var result = new HashMap<String, List<T>>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> frozenStrings(Map<String, Set<String>> source) {
        var result = new HashMap<String, List<String>>();
        source.forEach((key, value) -> result.put(key,
                value.stream().sorted().toList()));
        return Map.copyOf(result);
    }
}
