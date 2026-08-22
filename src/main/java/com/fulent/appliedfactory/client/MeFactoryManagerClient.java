package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.mcp.McpClientManager;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = AppliedFactory.MOD_ID, dist = Dist.CLIENT)
public final class MeFactoryManagerClient {
    public MeFactoryManagerClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerScreens);
        NeoForge.EVENT_BUS.addListener(MeFactoryManagerClient::onLoggingOut);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AppliedFactory.FACTORY_CONTROLLER_PROGRAM_MENU.get(),
                FactoryControllerProgramScreen::new);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        McpClientManager.get().stop();
    }
}
