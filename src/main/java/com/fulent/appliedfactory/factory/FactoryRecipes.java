package com.fulent.appliedfactory.factory;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

/** Shared recipe helpers used by the reference export and the script query index. */
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
     */
    @Nullable
    public static String toastMachine(Recipe<?> recipe) {
        var stack = recipe.getToastSymbol();
        if (stack.isEmpty() || stack.getItem() == Items.AIR) {
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
}
