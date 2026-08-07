package com.fulent.appliedfactory.integration.igtooltip;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import com.fulent.appliedfactory.block.FactoryControllerBlock;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;

import appeng.api.integrations.igtooltip.ClientRegistration;
import appeng.api.integrations.igtooltip.CommonRegistration;
import appeng.api.integrations.igtooltip.TooltipBuilder;
import appeng.api.integrations.igtooltip.TooltipContext;
import appeng.api.integrations.igtooltip.TooltipProvider;
import appeng.api.integrations.igtooltip.providers.BodyProvider;
import appeng.api.integrations.igtooltip.providers.ServerDataProvider;
import appeng.integration.modules.igtooltip.GridNodeState;

/**
 * Shows the factory controller's grid status in the exact wording AE2 uses for its own devices
 * (DeviceOnline / DeviceMissingChannel / DeviceOffline). Registered through AE2's own tooltip
 * abstraction, so it renders with any tooltip mod AE2 integrates (Jade, WTHIT, TOP) — no
 * tooltip-mod-specific code and no duplicated wording.
 */
public final class FactoryControllerTooltipProvider implements TooltipProvider {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("appliedfactory", "factory_controller");
    private static final String TAG_STATE = "gridNodeState";
    private static final GridStateProvider PROVIDER = new GridStateProvider();

    @Override
    public void registerCommon(CommonRegistration registration) {
        registration.addBlockEntityData(ID, FactoryControllerBlockEntity.class, PROVIDER);
    }

    @Override
    public void registerClient(ClientRegistration registration) {
        registration.addBlockEntityBody(
                FactoryControllerBlockEntity.class, FactoryControllerBlock.class, ID, PROVIDER);
    }

    /**
     * The controller is "online" when any of its six networks is active; powered but inactive
     * machines show the missing-channel hint, otherwise it is offline.
     */
    private static final class GridStateProvider
            implements BodyProvider<FactoryControllerBlockEntity>,
            ServerDataProvider<FactoryControllerBlockEntity> {
        @Override
        public void provideServerData(
                Player player, FactoryControllerBlockEntity controller, CompoundTag serverData) {
            GridNodeState state;
            if (controller.isActive()) {
                state = GridNodeState.ONLINE;
            } else if (controller.isPowered()) {
                state = GridNodeState.MISSING_CHANNEL;
            } else {
                state = GridNodeState.OFFLINE;
            }
            serverData.putByte(TAG_STATE, (byte) state.ordinal());
        }

        @Override
        public void buildTooltip(
                FactoryControllerBlockEntity controller,
                TooltipContext context,
                TooltipBuilder tooltip) {
            var data = context.serverData();
            if (data.contains(TAG_STATE, Tag.TAG_BYTE)) {
                var state = GridNodeState.values()[data.getByte(TAG_STATE)];
                tooltip.addLine(state.textComponent().withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
