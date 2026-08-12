package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.AppliedFactory;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@Mod(value = AppliedFactory.MOD_ID, dist = Dist.CLIENT)
public final class MeFactoryManagerClient {
    public MeFactoryManagerClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerScreens);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AppliedFactory.FACTORY_CONTROLLER_PROGRAM_MENU.get(),
                FactoryControllerProgramScreen::new);
        event.register(AppliedFactory.FACTORY_BUS_MENU.get(), FactoryBusScreen::new);
    }
}
