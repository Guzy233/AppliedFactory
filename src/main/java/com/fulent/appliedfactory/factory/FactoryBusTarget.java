package com.fulent.appliedfactory.factory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import appeng.api.behaviors.ExternalStorageStrategy;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.parts.automation.StackWorldBehaviors;

/**
 * Capability-based access to the block face targeted by a factory bus.
 *
 * <p>The target is not required to be a machine at all — it may be any block
 * or even air. World-state operations ({@code use}, {@code place},
 * {@code break}) are inherently item-centric because they physically interact
 * with block entities and item entities. Resource storage, however, is exposed
 * per AE2 key channel through AE2's own
 * {@link ExternalStorageStrategy external storage strategy} registry
 * ({@link StackWorldBehaviors}): every channel that AE2 or an addon registers a
 * strategy for (items, fluids, addon chemicals/energy cells, ...) is adapted to
 * an {@link MEStorage} automatically, with no per-type code in this class.</p>
 */
public final class FactoryBusTarget {
    private static final GameProfile FACTORY_ACTOR = new GameProfile(
            UUID.fromString("e54ef5e3-87f3-4b98-a2c7-3465c3e1b5e4"), "[ME Factory Manager]");
    private static final net.minecraft.world.item.Item MINING_TOOL = Items.DIAMOND_PICKAXE;
    private static final Runnable NO_CHANGE_LISTENER = () -> {
    };

    private final Level level;
    private final BlockPos position;
    private final Direction accessDirection;
    @Nullable
    private Map<AEKeyType, ExternalStorageStrategy> strategies;
    /**
     * Strategies created with a {@code null} side, exposing the target block's
     * whole container instead of one face's slot subset.
     */
    @Nullable
    private Map<AEKeyType, ExternalStorageStrategy> allStrategies;

    /**
     * @param level           The world containing the target block
     * @param position        The target block's position
     * @param accessDirection The direction of the factory bus on its host, pointing
     *                        from the host toward the target block. All capability
     *                        and world queries use {@link #targetFace()}, the face
     *                        of the target the bus actually touches.
     */
    public FactoryBusTarget(Level level, BlockPos position, Direction accessDirection) {
        this.level = level;
        this.position = position.immutable();
        this.accessDirection = accessDirection;
    }

    public BlockPos position() {
        return position;
    }

    /**
     * The direction of the bus on its host, pointing from the host toward the
     * target block.
     */
    public Direction accessDirection() {
        return accessDirection;
    }

    /**
     * The face of the target block being accessed, i.e. the face the bus touches
     * (the opposite of {@link #accessDirection}). Capability queries and world
     * interactions all use this face.
     */
    public Direction targetFace() {
        return accessDirection.getOpposite();
    }

    public boolean isLoaded() {
        return level.isLoaded(position);
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
        return level.getSignal(position, targetFace());
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

    // ---- World operations (item-centric) ------------------------------------

    /** Uses the target block with an empty-handed MFM fake player. */
    public boolean use(boolean shift) {
        var player = interactionPlayer();
        if (player == null) {
            return false;
        }
        var previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        var previousShift = player.isShiftKeyDown();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(shift);
        try {
            return player.gameMode.useItemOn(
                    player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, interactionHit())
                    .consumesAction();
        } finally {
            player.closeContainer();
            player.setShiftKeyDown(previousShift);
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    /** Uses one supplied item on the target, falling back to its in-air use. */
    public ItemUseResult useItem(ItemStack stack, boolean shift) {
        if (!isLoaded() || stack.isEmpty()) {
            return ItemUseResult.failed();
        }
        var player = interactionPlayer();
        if (player == null) {
            return ItemUseResult.failed();
        }
        var previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        var previousShift = player.isShiftKeyDown();
        var held = stack.copyWithCount(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.setShiftKeyDown(shift);
        try {
            var result = player.gameMode.useItemOn(
                    player, level, held, InteractionHand.MAIN_HAND, interactionHit());
            if (!result.consumesAction()) {
                result = player.gameMode.useItem(
                        player, level, player.getItemInHand(InteractionHand.MAIN_HAND),
                        InteractionHand.MAIN_HAND);
            }
            if (result.consumesAction() && player.isUsingItem()) {
                var hand = player.getUsedItemHand();
                var using = player.getUseItem();
                if (using.useOnRelease()) {
                    player.releaseUsingItem();
                } else {
                    var original = using.copy();
                    var finished = net.neoforged.neoforge.event.EventHooks.onItemUseFinish(
                            player,
                            original,
                            player.getUseItemRemainingTicks(),
                            using.finishUsingItem(level, player));
                    player.setItemInHand(hand, finished);
                    player.stopUsingItem();
                }
            }
            return new ItemUseResult(
                    result.consumesAction(),
                    result.consumesAction()
                            ? player.getItemInHand(InteractionHand.MAIN_HAND).copy()
                            : stack.copyWithCount(1));
        } finally {
            player.stopUsingItem();
            player.closeContainer();
            player.setShiftKeyDown(previousShift);
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    /**
     * Places one block item into an empty target position using the same fake
     * player.
     */
    public boolean place(ItemStack stack, boolean shift) {
        return placeAndCollect(stack, shift).successful();
    }

    /** Places one block item and returns the possibly transformed held-item remainder. */
    public ItemUseResult placeAndCollect(ItemStack stack, boolean shift) {
        if (!isLoaded() || !blockState().isAir() || !(stack.getItem() instanceof BlockItem)) {
            return ItemUseResult.failed();
        }
        var player = interactionPlayer();
        if (player == null) {
            return ItemUseResult.failed();
        }
        var previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        var previousShift = player.isShiftKeyDown();
        var placed = stack.copyWithCount(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, placed);
        player.setShiftKeyDown(shift);
        try {
            var result = player.gameMode.useItemOn(
                    player, level, placed, InteractionHand.MAIN_HAND, interactionHit())
                    .consumesAction() && !blockState().isAir();
            return new ItemUseResult(
                    result,
                    result
                            ? player.getItemInHand(InteractionHand.MAIN_HAND).copy()
                            : stack.copyWithCount(1));
        } finally {
            player.closeContainer();
            player.setShiftKeyDown(previousShift);
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
            if (state.getBlock() instanceof GameMasterBlock
                    && !player.canUseGameMasterBlocks()) {
                return BreakResult.failed();
            }
            if (player.blockActionRestricted(serverLevel, position, GameType.SURVIVAL)) {
                return BreakResult.failed();
            }
            if (CommonHooks.fireBlockBreak(serverLevel, GameType.SURVIVAL, player, position, state)
                    .isCanceled()) {
                return BreakResult.failed();
            }
            var blockEntity = level.getBlockEntity(position);
            var originalTool = usedTool.copy();
            var destroyedState = state.getBlock().playerWillDestroy(
                    serverLevel, position, state, player);
            var canHarvest = destroyedState.canHarvestBlock(serverLevel, position, player);
            var drops = canHarvest
                    ? dropsFor(destroyedState, player, originalTool, blockEntity)
                    : List.<ItemStack>of();
            usedTool.mineBlock(serverLevel, destroyedState, position, player);
            var removed = destroyedState.onDestroyedByPlayer(
                    serverLevel, position, player, canHarvest,
                    serverLevel.getFluidState(position));
            if (!removed) {
                return BreakResult.failed();
            }
            destroyedState.getBlock().destroy(serverLevel, position, destroyedState);
            if (usedTool.isEmpty() && !originalTool.isEmpty()) {
                net.neoforged.neoforge.event.EventHooks.onPlayerDestroyItem(
                        player, originalTool, InteractionHand.MAIN_HAND);
            }
            return new BreakResult(true, drops, usedTool.copy());
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, previousTool);
        }
    }

    /** Spawns detached item entities outward from the bus through its target position. */
    public boolean throwItems(List<ItemStack> stacks) {
        if (!(level instanceof ServerLevel serverLevel) || !isLoaded() || stacks.isEmpty()) {
            return false;
        }
        var launchDirection = accessDirection();
        var spawn = Vec3.atCenterOf(position).add(
                launchDirection.getStepX() * 0.35D,
                launchDirection.getStepY() * 0.35D,
                launchDirection.getStepZ() * 0.35D);
        var spawned = new java.util.ArrayList<ItemEntity>();
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
            if (!serverLevel.addFreshEntity(item)) {
                spawned.forEach(ItemEntity::discard);
                return false;
            }
            spawned.add(item);
        }
        return true;
    }

    // ---- Generic per-channel storage facade --------------------------------

    /**
     * Returns the {@link MEStorage} AE2's strategy registry produces for one key
     * channel on this target face, or null when the channel has no registered
     * strategy or the target exposes no matching capability. Both the channel
     * space and the capability adapters are owned by AE2, so third-party channels
     * are picked up automatically.
     *
     * <p>The wrapper is created in extractable-only mode, matching AE2's default
     * storage-bus filter ({@code StorageFilter.EXTRACTABLE_ONLY}). A face may
     * expose slots it can list but not pull from (e.g. a furnace's fuel slot is
     * visible from its bottom face yet rejects {@code extractItem}), so
     * {@code getAvailableStacks} must report only resources {@code extract} can
     * actually move.
     */
    @Nullable
    public MEStorage storage(AEKeyType type) {
        if (!(level instanceof ServerLevel serverLevel) || !isLoaded()) {
            return null;
        }
        var strategy = strategies(serverLevel).get(type);
        return strategy == null ? null : strategy.createWrapper(true, NO_CHANGE_LISTENER);
    }

    /**
     * Same as {@link #storage(AEKeyType)} but the wrapper is built from
     * strategies queried with a {@code null} side, so it exposes the target
     * block's <em>whole container</em> instead of the single face's slot subset:
     * e.g. a furnace lists input, fuel and output slots regardless of which face
     * the bus touches. Extraction from slots that reject it still returns 0 —
     * the wrapper only exists for read-only inspection.
     */
    @Nullable
    public MEStorage storageAll(AEKeyType type) {
        if (!(level instanceof ServerLevel serverLevel) || !isLoaded()) {
            return null;
        }
        var strategy = allStrategies(serverLevel).get(type);
        return strategy == null ? null : strategy.createWrapper(false, NO_CHANGE_LISTENER);
    }

    /**
     * The key channels the target block exposes when queried without a face,
     * i.e. the channels that can list the block's whole container.
     */
    public Set<AEKeyType> channelsAll() {
        if (!(level instanceof ServerLevel serverLevel) || !isLoaded()) {
            return Set.of();
        }
        var result = new LinkedHashSet<AEKeyType>();
        for (var entry : allStrategies(serverLevel).entrySet()) {
            if (entry.getValue().createWrapper(false, NO_CHANGE_LISTENER) != null) {
                result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    /**
     * The key channels this target face currently exposes.
     */
    public Set<AEKeyType> channels() {
        if (!(level instanceof ServerLevel serverLevel) || !isLoaded()) {
            return Set.of();
        }
        var result = new LinkedHashSet<AEKeyType>();
        for (var entry : strategies(serverLevel).entrySet()) {
            if (entry.getValue().createWrapper(true, NO_CHANGE_LISTENER) != null) {
                result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    private Map<AEKeyType, ExternalStorageStrategy> strategies(ServerLevel serverLevel) {
        if (strategies == null) {
            strategies = StackWorldBehaviors.createExternalStorageStrategies(
                    serverLevel, position, targetFace());
        }
        return strategies;
    }

    /**
     * Strategies built with a {@code null} side so they wrap the whole container.
     * If a registered addon strategy cannot handle a null side, falls back to the
     * single-face strategies so {@link #storageAll(AEKeyType)} still works.
     */
    private Map<AEKeyType, ExternalStorageStrategy> allStrategies(ServerLevel serverLevel) {
        if (allStrategies == null) {
            try {
                allStrategies = StackWorldBehaviors.createExternalStorageStrategies(
                        serverLevel, position, null);
            } catch (RuntimeException exception) {
                AppliedFactory.LOGGER.warn(
                        "An external storage strategy rejected a null side at {}; "
                                + "falling back to the single-face view for storage()",
                        position, exception);
                allStrategies = strategies(serverLevel);
            }
        }
        return allStrategies;
    }

    @Nullable
    private FakePlayer interactionPlayer() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        var player = FakePlayerFactory.get(serverLevel, FACTORY_ACTOR);
        player.setGameMode(GameType.SURVIVAL);
        var actorDirection = targetFace();
        var actorPosition = Vec3.atCenterOf(position).add(
                actorDirection.getStepX() * 1.25D,
                actorDirection.getStepY() * 1.25D,
                actorDirection.getStepZ() * 1.25D);
        player.setPos(actorPosition.x, actorPosition.y, actorPosition.z);
        return player;
    }

    private BlockHitResult interactionHit() {
        var face = targetFace();
        var hit = Vec3.atCenterOf(position).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        return new BlockHitResult(hit, face, position, false);
    }

    private List<ItemStack> dropsFor(BlockState state, FakePlayer player, ItemStack tool) {
        return dropsFor(state, player, tool, level.getBlockEntity(position));
    }

    private List<ItemStack> dropsFor(
            BlockState state,
            FakePlayer player,
            ItemStack tool,
            @Nullable BlockEntity blockEntity) {
        return List.copyOf(Block.getDrops(state, (ServerLevel) level, position, blockEntity,
                player, tool));
    }

    private static ItemStack miningTool() {
        return new ItemStack(MINING_TOOL);
    }

    public record ItemUseResult(boolean successful, ItemStack remainder) {
        public ItemUseResult {
            remainder = remainder.copy();
        }

        private static ItemUseResult failed() {
            return new ItemUseResult(false, ItemStack.EMPTY);
        }
    }

    public record BreakResult(boolean destroyed, List<ItemStack> drops, ItemStack tool) {
        public BreakResult {
            drops = List.copyOf(drops);
            tool = tool.copy();
        }

        private static BreakResult failed() {
            return new BreakResult(false, List.of(), ItemStack.EMPTY);
        }
    }
}
