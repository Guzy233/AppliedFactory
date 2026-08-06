package com.fulent.appliedfactory.block;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.menu.FactoryControllerMenu;
import com.fulent.appliedfactory.menu.FactoryControllerProgramMenu;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;

public final class FactoryControllerBlock extends BaseEntityBlock {
    public static final MapCodec<FactoryControllerBlock> CODEC = simpleCodec(FactoryControllerBlock::new);

    public FactoryControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactoryControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(
                type,
                com.fulent.appliedfactory.AppliedFactory.FACTORY_CONTROLLER_BLOCK_ENTITY.get(),
                FactoryControllerBlockEntity::serverTick);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
            BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof FactoryControllerBlockEntity controller) {
            controller.dropOwnedContents();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // 右键顶面打开编程ui，其他面打开物品页面
    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof FactoryControllerBlockEntity factory) {
            if (hit.getDirection() == Direction.UP) {
                ((IPlayerExtension) serverPlayer).openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new FactoryControllerProgramMenu(
                                containerId, inventory, factory),
                        Component.translatable("gui.mefactorymanager.program")), pos);
            } else {
                ((IPlayerExtension) serverPlayer).openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new FactoryControllerMenu(containerId, inventory, factory),
                        Component.translatable("gui.mefactorymanager.storage")), pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
