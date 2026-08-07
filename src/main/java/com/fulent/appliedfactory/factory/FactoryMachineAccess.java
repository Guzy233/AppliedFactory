package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Capability-based access to the machine face targeted by a factory bus.
 *
 * <p>
 * This class deliberately contains no AE or scripting concepts. The job
 * runtime exposes a narrow wrapper around it to Rhino without teaching the part
 * about recipes or callbacks.
 * </p>
 */
public final class FactoryMachineAccess {
    private static final GameProfile FACTORY_ACTOR = new GameProfile(
            UUID.fromString("e54ef5e3-87f3-4b98-a2c7-3465c3e1b5e4"), "[ME Factory Manager]");
    private static final net.minecraft.world.item.Item MINING_TOOL = Items.DIAMOND_PICKAXE;

    private final Level level;
    private final BlockPos position;
    private final Direction accessDirection;

    /**
     * @param level           The world containing the target block
     * @param position        The target block's position
     * @param accessDirection The direction from the bus toward the target (used for
     *                        capability queries)
     */
    public FactoryMachineAccess(Level level, BlockPos position, Direction accessDirection) {
        this.level = level;
        this.position = position.immutable();
        this.accessDirection = accessDirection;
    }

    public BlockPos position() {
        return position;
    }

    /**
     * Returns the direction from the bus toward the target block.
     * This is the direction used for capability queries.
     */
    public Direction accessDirection() {
        return accessDirection;
    }

    /**
     * Returns the face of the target block being accessed.
     * This is the opposite of the access direction.
     */
    public Direction targetFace() {
        return accessDirection.getOpposite();
    }

    public boolean isLoaded() {
        return level.hasChunkAt(position);
    }

    public ResourceLocation blockId() {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock());
    }

    public BlockState blockState() {
        return level.getBlockState(position);
    }

    @Nullable
    public ResourceLocation blockEntityTypeId() {
        var blockEntity = level.getBlockEntity(position);
        return blockEntity == null
                ? null
                : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
    }

    public int redstoneLevel() {
        return level.getSignal(position, accessDirection);
    }

    /** Tests the current target block against one exact block id or #block tag. */
    public boolean matchesBlock(String selector) {
        if (!isLoaded()) {
            return false;
        }
        if (selector.startsWith("#")) {
            var tagId = ResourceLocation.tryParse(selector.substring(1));
            return tagId != null && blockState().is(TagKey.create(Registries.BLOCK, tagId));
        }
        var selectorId = ResourceLocation.tryParse(selector);
        return selectorId != null && selectorId.equals(blockId());
    }

    /** Uses the target block with an empty-handed MFM fake player. */
    public boolean use() {
        var player = interactionPlayer();
        if (player == null) {
            return false;
        }
        var previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        try {
            return player.gameMode.useItemOn(
                    player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, interactionHit())
                    .consumesAction();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    /**
     * Places one block item into an empty target position using the same fake
     * player.
     */
    public boolean place(ItemStack stack) {
        if (!isLoaded() || !blockState().isAir() || !(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        var player = interactionPlayer();
        if (player == null) {
            return false;
        }
        var previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        var placed = stack.copyWithCount(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, placed);
        try {
            return player.gameMode.useItemOn(
                    player, level, placed, InteractionHand.MAIN_HAND, interactionHit())
                    .consumesAction() && !blockState().isAir();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    /**
     * Returns the target block entity's own NBT data without metadata, or null
     * when the target has no block entity. Read-only: the returned tag is a
     * detached copy.
     */
    @Nullable
    public CompoundTag blockEntityNbt() {
        if (level.isClientSide || !isLoaded()) {
            return null;
        }
        var blockEntity = level.getBlockEntity(position);
        if (blockEntity == null) {
            return null;
        }
        return blockEntity.saveWithoutMetadata(level.registryAccess());
    }

    /** Calculates normal player drops using the built-in mining tool. */
    public List<ItemStack> previewBreakDrops() {
        return previewBreakDrops(null);
    }

    /** Calculates normal player drops using the given tool, or the built-in one. */
    public List<ItemStack> previewBreakDrops(@Nullable ItemStack tool) {
        var player = interactionPlayer();
        if (player == null || !isLoaded() || blockState().isAir()) {
            return List.of();
        }
        return dropsFor(blockState(), player, tool == null ? miningTool() : tool);
    }

    /**
     * Breaks the target with the built-in mining tool after the standard NeoForge
     * break event, returning its player drops instead of spawning them into the
     * world. The caller is responsible for storing those drops.
     */
    public BreakResult breakAndCollect() {
        return breakAndCollect(null);
    }

    /**
     * Breaks the target with the given tool after the standard NeoForge break
     * event, returning its player drops instead of spawning them into the world.
     * The caller is responsible for storing those drops.
     */
    public BreakResult breakAndCollect(@Nullable ItemStack tool) {
        if (!(level instanceof ServerLevel serverLevel) || !isLoaded() || blockState().isAir()) {
            return BreakResult.failed();
        }
        var player = interactionPlayer();
        if (player == null) {
            return BreakResult.failed();
        }
        var previousTool = player.getItemInHand(InteractionHand.MAIN_HAND);
        var usedTool = tool == null ? miningTool() : tool;
        player.setItemInHand(InteractionHand.MAIN_HAND, usedTool);
        try {
            var state = blockState();
            if (CommonHooks.fireBlockBreak(serverLevel, GameType.SURVIVAL, player, position, state)
                    .isCanceled()) {
                return BreakResult.failed();
            }
            var drops = dropsFor(state, player, usedTool);
            if (!serverLevel.destroyBlock(position, false, player)) {
                return BreakResult.failed();
            }
            return new BreakResult(true, drops);
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, previousTool);
        }
    }

    /** Spawns detached item entities from the bus's target face. */
    public boolean throwItems(List<ItemStack> stacks) {
        if (!(level instanceof ServerLevel serverLevel) || stacks.isEmpty()) {
            return false;
        }
        var launchDirection = accessDirection.getOpposite();
        var spawn = Vec3.atCenterOf(position).add(
                launchDirection.getStepX() * 0.35D,
                launchDirection.getStepY() * 0.35D,
                launchDirection.getStepZ() * 0.35D);
        for (var stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            var item = new ItemEntity(serverLevel, spawn.x, spawn.y, spawn.z, stack.copy());
            item.setDefaultPickUpDelay();
            item.setDeltaMovement(
                    launchDirection.getStepX() * 0.18D,
                    launchDirection.getStepY() * 0.18D + 0.08D,
                    launchDirection.getStepZ() * 0.18D);
            serverLevel.addFreshEntity(item);
        }
        return true;
    }

    public boolean hasItemStorage() {
        return itemHandler() != null;
    }

    public List<ItemStack> items() {
        var handler = itemHandler();
        if (handler == null) {
            return List.of();
        }

        var result = new ArrayList<ItemStack>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            var stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                result.add(stack.copy());
            }
        }
        return List.copyOf(result);
    }

    public long countItem(ResourceLocation itemId) {
        long count = 0;
        for (var stack : items()) {
            if (itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Total items currently visible through this physical machine face. */
    public long storedItemCount() {
        long count = 0;
        for (var stack : items()) {
            count = Math.addExact(count, stack.getCount());
        }
        return count;
    }

    /**
     * Counts only matching items that this machine face actually permits
     * extracting.
     */
    public long countExtractable(ItemStack template) {
        var handler = itemHandler();
        if (handler == null || template.isEmpty()) {
            return 0;
        }

        long count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            var candidate = handler.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameComponents(candidate, template)) {
                continue;
            }
            var extracted = handler.extractItem(slot, Integer.MAX_VALUE, true);
            if (ItemStack.isSameItemSameComponents(extracted, template)) {
                count += extracted.getCount();
            }
        }
        return count;
    }

    /** Returns the remainder that the target rejected. */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        var handler = itemHandler();
        if (handler == null || stack.isEmpty()) {
            return stack.copy();
        }

        var remainder = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remainder.isEmpty(); slot++) {
            remainder = handler.insertItem(slot, remainder, simulate);
        }
        return remainder;
    }

    /**
     * Simulates the complete recipe batch against one virtual inventory snapshot.
     */
    public boolean canInsertAll(List<ItemStack> stacks) {
        var handler = itemHandler();
        if (handler == null) {
            return false;
        }

        var slots = virtualSlots(handler);
        for (var stack : stacks) {
            if (!stack.isEmpty()
                    && insertIntoVirtual(handler, slots, stack, stack.getCount()) > 0) {
                return false;
            }
        }
        return true;
    }

    /** Capacity available for one resource after an already planned local batch. */
    public long additionalInsertable(
            List<ItemStack> planned, ItemStack template, long requestedAmount) {
        var handler = itemHandler();
        if (handler == null || template.isEmpty() || requestedAmount <= 0) {
            return 0;
        }

        var slots = virtualSlots(handler);
        for (var stack : planned) {
            if (!stack.isEmpty()
                    && insertIntoVirtual(handler, slots, stack, stack.getCount()) > 0) {
                return 0;
            }
        }
        return requestedAmount
                - insertIntoVirtual(handler, slots, template, requestedAmount);
    }

    /**
     * Commits a previously simulated batch and returns anything unexpectedly
     * rejected by the target. Callers retain ownership of returned stacks.
     */
    public List<ItemStack> insertAll(List<ItemStack> stacks) {
        var rejected = new ArrayList<ItemStack>();
        for (var stack : stacks) {
            var remainder = insert(stack, false);
            if (!remainder.isEmpty()) {
                rejected.add(remainder.copy());
            }
        }
        return List.copyOf(rejected);
    }

    /**
     * Emergency preservation path for a handler that violates simulation semantics.
     */
    public void dropItems(List<ItemStack> stacks) {
        if (level.isClientSide) {
            return;
        }
        for (var stack : stacks) {
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, position.getX(), position.getY(), position.getZ(), stack);
            }
        }
    }

    /**
     * Extracts stacks matching the template, preserving components and slot
     * boundaries. The returned stacks are detached copies owned by the caller.
     */
    public List<ItemStack> extract(ItemStack template, int amount, boolean simulate) {
        if (amount <= 0 || template.isEmpty()) {
            return List.of();
        }

        var handler = itemHandler();
        if (handler == null) {
            return List.of();
        }

        var remaining = amount;
        var result = new ArrayList<ItemStack>();
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            var candidate = handler.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameComponents(candidate, template)) {
                continue;
            }

            var extracted = handler.extractItem(slot, remaining, simulate);
            if (!extracted.isEmpty()) {
                result.add(extracted.copy());
                remaining -= extracted.getCount();
            }
        }
        return List.copyOf(result);
    }

    /** Extracts every stack currently permitted by this sided capability. */
    public List<ItemStack> extractAll(boolean simulate) {
        var handler = itemHandler();
        if (handler == null) {
            return List.of();
        }

        var result = new ArrayList<ItemStack>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            var extracted = handler.extractItem(slot, Integer.MAX_VALUE, simulate);
            if (!extracted.isEmpty()) {
                result.add(extracted.copy());
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    private IItemHandler itemHandler() {
        if (!isLoaded()) {
            return null;
        }
        // Use the access direction for capability queries. For sided machines like
        // furnaces,
        // this determines which slots are exposed (e.g., furnace top = input, bottom =
        // output).
        return level.getCapability(Capabilities.ItemHandler.BLOCK, position, accessDirection);
    }

    @Nullable
    private FakePlayer interactionPlayer() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        var player = FakePlayerFactory.get(serverLevel, FACTORY_ACTOR);
        player.setGameMode(GameType.SURVIVAL);
        var actorDirection = accessDirection;
        var actorPosition = Vec3.atCenterOf(position).add(
                actorDirection.getStepX() * 1.25D,
                actorDirection.getStepY() * 1.25D,
                actorDirection.getStepZ() * 1.25D);
        player.setPos(actorPosition.x, actorPosition.y, actorPosition.z);
        return player;
    }

    private BlockHitResult interactionHit() {
        var hit = Vec3.atCenterOf(position).add(
                accessDirection.getStepX() * 0.5D,
                accessDirection.getStepY() * 0.5D,
                accessDirection.getStepZ() * 0.5D);
        return new BlockHitResult(hit, accessDirection, position, false);
    }

    private List<ItemStack> dropsFor(BlockState state, FakePlayer player, ItemStack tool) {
        BlockEntity blockEntity = level.getBlockEntity(position);
        return List.copyOf(Block.getDrops(state, (ServerLevel) level, position, blockEntity,
                player, tool));
    }

    private static ItemStack miningTool() {
        return new ItemStack(MINING_TOOL);
    }

    public record BreakResult(boolean destroyed, List<ItemStack> drops) {
        public BreakResult {
            drops = List.copyOf(drops);
        }

        private static BreakResult failed() {
            return new BreakResult(false, List.of());
        }
    }

    private static int simulatedCapacity(IItemHandler handler, int slot, ItemStack template) {
        var probe = template.copyWithCount(template.getMaxStackSize());
        var remainder = handler.insertItem(slot, probe, true);
        return probe.getCount() - remainder.getCount();
    }

    private static VirtualSlot[] virtualSlots(IItemHandler handler) {
        var result = new VirtualSlot[handler.getSlots()];
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            result[slot] = new VirtualSlot(handler.getStackInSlot(slot));
        }
        return result;
    }

    private static long insertIntoVirtual(
            IItemHandler handler,
            VirtualSlot[] slots,
            ItemStack input,
            long amount) {
        var remaining = amount;
        for (int slot = 0; slot < slots.length && remaining > 0; slot++) {
            var virtual = slots[slot];
            if (!virtual.accepts(input)) {
                continue;
            }

            if (!virtual.initialized()) {
                var accepted = simulatedCapacity(handler, slot, input);
                if (accepted <= 0) {
                    continue;
                }
                virtual.initialize(input, accepted);
            }

            var inserted = (int) Math.min(remaining, virtual.remainingCapacity());
            virtual.insert(input, inserted);
            remaining -= inserted;
        }
        return remaining;
    }

    private static final class VirtualSlot {
        private ItemStack stack;
        private int capacity;

        private VirtualSlot(ItemStack initial) {
            stack = initial.copy();
            capacity = -1;
        }

        private boolean accepts(ItemStack input) {
            return stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, input);
        }

        private boolean initialized() {
            return capacity >= 0;
        }

        private void initialize(ItemStack input, int accepted) {
            if (stack.isEmpty()) {
                stack = input.copyWithCount(0);
                capacity = accepted;
            } else {
                capacity = stack.getCount() + accepted;
            }
        }

        private int remainingCapacity() {
            return Math.max(0, capacity - stack.getCount());
        }

        private void insert(ItemStack input, int amount) {
            if (amount <= 0) {
                return;
            }
            if (stack.isEmpty()) {
                stack = input.copyWithCount(amount);
            } else {
                stack.grow(amount);
            }
        }
    }
}
