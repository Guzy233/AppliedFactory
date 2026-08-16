package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import appeng.api.stacks.AEItemKey;

/** Shared recipe helpers used by the reference export ({@code /appliedfactory export}). */
public final class FactoryRecipes {
    private FactoryRecipes() {
    }

    public static boolean isCraftingType(String typeId) {
        return typeId.equals("minecraft:crafting")
                || typeId.equals("minecraft:stonecutting")
                || typeId.equals("minecraft:smithing");
    }

    /**
     * Namespaced id of a recipe type (e.g. {@code minecraft:crafting}). Vanilla
     * {@code RecipeType#toString()} drops the namespace, so look the key up in the
     * registry first and only fall back to the raw string for unregistered types.
     */
    public static String typeId(RecipeType<?> type) {
        var key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return key != null ? key.toString() : type.toString();
    }

    /**
     * The item id of the machine this recipe runs in ({@link Recipe#getToastSymbol()},
     * what the recipe book and JEI use as the machine icon), or null when the recipe
     * has no machine symbol.
     *
     * <p>{@link Recipe#getToastSymbol()} defaults to the crafting table for recipe
     * types that do not declare a machine icon. Crafting-table types are excluded
     * from the export, so a crafting-table symbol means "no machine information",
     * not a real machine: reporting it would map unrelated processing recipe types
     * (chargers, assemblers, ...) to {@code minecraft:crafting_table}.
     */
    @Nullable
    public static String toastMachine(Recipe<?> recipe) {
        var stack = recipe.getToastSymbol();
        if (stack.isEmpty() || stack.getItem() == Items.AIR
                || stack.getItem() == Items.CRAFTING_TABLE) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * Re-encodes a decoded recipe back to its original JSON, or null when the
     * recipe cannot be re-encoded.
     */
    @Nullable
    public static JsonElement rawJson(Recipe<?> recipe) {
        try {
            return Recipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).result().orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * One input slot of a recipe: the representative option (the first usable
     * item of the ingredient) plus every alternative the ingredient accepts.
     * A tag or multi-choice ingredient is one slot with many options — the
     * recipe needs {@em any one} of them, not all.
     */
    public record InputSlot(FactoryResource representative, List<FactoryResource> options) {
        public InputSlot {
            options = List.copyOf(options);
        }
    }

    /**
     * Best-effort server-side normalization of a recipe's input slots. One slot
     * per {@link Recipe#getIngredients()} entry; each slot carries the full list
     * of items the ingredient accepts (tag members and explicit alternatives),
     * deduplicated, with the first as the representative. Ingredient entries
     * carry no count on the {@code Recipe} contract, so amounts are 1. Fluids,
     * chemicals and input counts larger than 1 are not available generically and
     * are left to the raw JSON reference ({@code json} in the exported entries).
     */
    public static List<InputSlot> inputSlots(Recipe<?> recipe) {
        var result = new ArrayList<InputSlot>();
        for (var ingredient : recipe.getIngredients()) {
            var options = new ArrayList<FactoryResource>();
            for (var stack : ingredient.getItems()) {
                if (stack.isEmpty()) {
                    continue;
                }
                var resource = new FactoryResource(AEItemKey.of(stack), 1);
                if (options.stream().noneMatch(existing -> existing.key().equals(resource.key()))) {
                    options.add(resource);
                }
            }
            if (!options.isEmpty()) {
                result.add(new InputSlot(options.getFirst(), options));
            }
        }
        return result;
    }

    /**
     * Best-effort server-side normalization of a recipe's primary output: the
     * result item with its exact count.
     */
    public static List<FactoryResource> outputs(Recipe<?> recipe, HolderLookup.Provider registries) {
        var stack = recipe.getResultItem(registries);
        if (stack.isEmpty()) {
            return List.of();
        }
        return List.of(new FactoryResource(AEItemKey.of(stack), stack.getCount()));
    }
}
