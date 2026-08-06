package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.network.ControllerProgramSaveResultPayload;

import net.minecraft.client.Minecraft;

/** Client-only bridge keeping client GUI references out of server-side controller logic. */
public final class ClientControllerProgramPayloadHandler {
    private ClientControllerProgramPayloadHandler() {
    }

    public static void handleSaveResult(ControllerProgramSaveResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof FactoryControllerProgramScreen editor) {
            editor.showSaveResult(payload);
        }
    }
}
