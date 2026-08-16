package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.AppliedFactory;

import net.minecraft.resources.ResourceLocation;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;

/**
 * Captures the JEI runtime so the client-side machine-icon lookup can query
 * JEI recipe categories. Only loaded by JEI on the client; never touched on a
 * server.
 */
@JeiPlugin
public final class FactoryJeiPlugin implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    public static IJeiRuntime runtime() {
        return runtime;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        FactoryJeiPlugin.runtime = runtime;
    }
}
