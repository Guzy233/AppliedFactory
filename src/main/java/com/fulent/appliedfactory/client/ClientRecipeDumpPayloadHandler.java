package com.fulent.appliedfactory.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.factory.FactoryRecipes;
import com.fulent.appliedfactory.network.RecipeDumpChunkPayload;
import com.fulent.appliedfactory.network.RequestRecipeDumpPayload;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.stacks.GenericStack;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import tamaized.ae2jeiintegration.api.integrations.jei.IngredientConverters;

/**
 * Client-only answer to the server's recipe-dump request: iterates every JEI
 * category, resolves each recipe's input/output slots through the JEI recipe
 * layout and converts the slot ingredients to AE resources with the
 * ae2-jei-integration {@link IngredientConverters} registry (items and fluids
 * built-in, chemicals when Applied Mekanistics is installed), and sends the
 * result back as {@link RecipeDumpChunkPayload} chunks keyed by the server
 * recipe id.
 *
 * <p>Slots are exported whole: a slot's representative is its first ingredient
 * and all of its alternatives become the {@code options} array, so recipes
 * whose inputs live outside {@code Recipe#getIngredients()} (e.g. Mystical
 * Agriculture's infusion altar ingredient) and tag/multi-choice slots keep
 * their full information instead of being flattened.
 */
public final class ClientRecipeDumpPayloadHandler {
    private static final Gson GSON = new GsonBuilder().create();

    private ClientRecipeDumpPayloadHandler() {
    }

    public static void handleRequest(RequestRecipeDumpPayload payload) {
        sendDump(payload.requestId());
    }

    private static void sendDump(UUID requestId) {
        if (!ModList.get().isLoaded("ae2jeiintegration")) {
            sendChunk(requestId, 0, 1, false, "[]");
            return;
        }
        var entries = new JsonArray();
        var runtime = FactoryJeiPlugin.runtime();
        if (runtime != null) {
            var level = Minecraft.getInstance().level;
            var registries = level == null ? null : level.registryAccess();
            var ids = recipeIds(level);
            var focusGroup = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            for (var category : runtime.getRecipeManager().createRecipeCategoryLookup().get().toList()) {
                var typeId = category.getRecipeType().getUid().toString();
                if (FactoryRecipes.isCraftingType(typeId)) {
                    continue;
                }
                for (var recipe : runtime.getRecipeManager()
                        .createRecipeLookup(category.getRecipeType()).get().toList()) {
                    var id = recipeId(category, recipe, ids);
                    if (id == null || registries == null) {
                        continue;
                    }
                    List<JsonObject> inputs;
                    List<JsonObject> outputs;
                    try {
                        // The raw IRecipeCategory cast erases the layout's type
                        // parameter, so resolve the Optional value by instanceof.
                        var maybe = runtime.getRecipeManager()
                                .createRecipeLayoutDrawable((IRecipeCategory) category, recipe, focusGroup);
                        if (maybe.isPresent() && maybe.get() instanceof IRecipeLayoutDrawable<?> drawable) {
                            var slots = drawable.getRecipeSlotsView();
                            inputs = resources(slots.getSlotViews(RecipeIngredientRole.INPUT), registries);
                            outputs = resources(slots.getSlotViews(RecipeIngredientRole.OUTPUT), registries);
                        } else {
                            var supplier = runtime.getRecipeManager()
                                    .getRecipeIngredients((IRecipeCategory) category, recipe);
                            inputs = flatResources(supplier.getIngredients(RecipeIngredientRole.INPUT), registries);
                            outputs = flatResources(supplier.getIngredients(RecipeIngredientRole.OUTPUT), registries);
                        }
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    if (inputs.isEmpty() || outputs.isEmpty()) {
                        continue;
                    }
                    var entry = new JsonObject();
                    entry.addProperty("id", id);
                    entry.addProperty("type", typeId);
                    entry.add("inputs", toArray(inputs));
                    entry.add("outputs", toArray(outputs));
                    entries.add(entry);
                }
            }
        }
        var json = GSON.toJson(entries);
        var chunkCount = (json.length() + RecipeDumpChunkPayload.MAX_CHUNK_CHARS - 1)
                / RecipeDumpChunkPayload.MAX_CHUNK_CHARS;
        var total = Math.max(chunkCount, 1);
        for (int index = 0; index < total; index++) {
            var from = index * RecipeDumpChunkPayload.MAX_CHUNK_CHARS;
            var to = Math.min(json.length(), from + RecipeDumpChunkPayload.MAX_CHUNK_CHARS);
            sendChunk(requestId, index, total, runtime != null, json.substring(from, to));
        }
    }

    private static void sendChunk(
            UUID requestId, int index, int total, boolean available, String json) {
        PacketDistributor.sendToServer(new RecipeDumpChunkPayload(
                requestId, index, total, available, json));
    }

    /**
     * One {@code {channel, key, amount}} entry per slot, with an
     * {@code options} array of every alternative the slot accepts (tags,
     * choices, or the JEI layout's multi-ingredient slots). The representative
     * is the first convertible ingredient of the slot.
     */
    private static List<JsonObject> resources(
            List<IRecipeSlotView> slots, HolderLookup.Provider registries) {
        var result = new ArrayList<JsonObject>();
        for (var slot : slots) {
            var options = new ArrayList<JsonObject>();
            var seen = new LinkedHashSet<String>();
            for (var typed : slot.getAllIngredientsList()) {
                var obj = resource(typed, registries);
                if (obj == null || !seen.add(obj.get("key").toString())) {
                    continue;
                }
                options.add(obj);
            }
            if (options.isEmpty()) {
                continue;
            }
            var representative = options.getFirst().deepCopy();
            if (options.size() > 1) {
                var array = new JsonArray();
                options.forEach(array::add);
                representative.add("options", array);
            }
            result.add(representative);
        }
        return result;
    }

    /** Fallback for recipes whose layout cannot be built: flat per-ingredient entries. */
    private static List<JsonObject> flatResources(
            List<ITypedIngredient<?>> ingredients, HolderLookup.Provider registries) {
        var result = new ArrayList<JsonObject>();
        var seen = new LinkedHashSet<String>();
        for (var typed : ingredients) {
            var obj = resource(typed, registries);
            if (obj == null || !seen.add(obj.get("key").toString())) {
                continue;
            }
            result.add(obj);
        }
        return result;
    }

    @Nullable
    private static JsonObject resource(ITypedIngredient<?> typed, HolderLookup.Provider registries) {
        var converter = IngredientConverters.getConverter((IIngredientType) typed.getType());
        if (converter == null) {
            return null;
        }
        GenericStack stack;
        try {
            stack = converter.getStackFromIngredient(typed.getIngredient());
        } catch (RuntimeException ignored) {
            return null;
        }
        if (stack == null || stack.amount() <= 0) {
            return null;
        }
        var obj = new JsonObject();
        obj.addProperty("channel", stack.what().getType().getId().toString());
        obj.add("key", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, stack.what().toTag(registries)));
        obj.addProperty("amount", stack.amount());
        return obj;
    }

    /**
     * Maps each recipe value of the client's own recipe manager to its id by
     * identity, so JEI recipes that are raw {@code Recipe} instances (rather
     * than {@link RecipeHolder}s) can still be keyed to the server's recipe
     * ids.
     */
    private static Map<Recipe<?>, ResourceLocation> recipeIds(ClientLevel level) {
        var result = new IdentityHashMap<Recipe<?>, ResourceLocation>();
        if (level == null) {
            return result;
        }
        for (var holder : level.getRecipeManager().getRecipes()) {
            result.put(holder.value(), holder.id());
        }
        return result;
    }

    @Nullable
    private static String recipeId(
            IRecipeCategory<?> category, Object recipe, Map<Recipe<?>, ResourceLocation> ids) {
        try {
            var id = ((IRecipeCategory) category).getRegistryName(recipe);
            if (id != null) {
                return id.toString();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the identity lookup.
        }
        if (recipe instanceof RecipeHolder<?> holder) {
            return holder.id().toString();
        }
        if (recipe instanceof Recipe<?> value) {
            var id = ids.get(value);
            if (id != null) {
                return id.toString();
            }
        }
        return null;
    }

    private static JsonArray toArray(List<JsonObject> values) {
        var array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
