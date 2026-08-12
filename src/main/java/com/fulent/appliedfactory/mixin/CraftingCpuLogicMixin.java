package com.fulent.appliedfactory.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.factory.CraftingRequestContext;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import net.minecraft.world.level.Level;

/**
 * Links factory jobs to their AE crafting request and fires a synchronous callback when that
 * request ends. AE2 never notifies {@link ICraftingProvider}s of cancellation or completion, so
 * instead of polling the crafting service every tick:
 *
 * <ul>
 *   <li>{@code executeCrafting} pushes patterns for one crafting request at a time — the request's
 *       id is captured into {@link CraftingRequestContext} so the factory can stamp its jobs.
 *   <li>{@code finishJob(success)} is the single funnel for every end of a crafting request
 *       ({@code cancel()}, the canceled-link tick check, {@code ICraftingCPU.cancelJob()}, and a
 *       successful completion once all requested outputs are delivered). A non-successful finish
 *       tells the owning controllers on the CPU's grid the request id so they can cancel their
 *       matching jobs. A successful finish does NOT cancel anything: the workflow itself delivers
 *       the outputs and finishes normally on its own, and killing it mid-run would discard a
 *       still-valid continuation and churn its owned resources.
 * </ul>
 */
@Mixin(CraftingCpuLogic.class)
public abstract class CraftingCpuLogicMixin {
    @Shadow
    private CraftingCPUCluster cluster;

    @Inject(method = "executeCrafting", at = @At("HEAD"))
    private void factoryCaptureCraftingRequestId(
            int maxPatterns, CraftingService craftingService,
            IEnergyService energyService, Level level,
            CallbackInfoReturnable<Integer> cir) {
        try {
            CraftingRequestContext.set(craftingIdOf((CraftingCpuLogic) (Object) this));
        } catch (RuntimeException exception) {
            AppliedFactory.LOGGER.error("Failed to capture the factory crafting request id", exception);
        }
    }

    @Inject(method = "executeCrafting", at = @At("RETURN"))
    private void factoryClearCraftingRequestId(
            int maxPatterns, CraftingService craftingService,
            IEnergyService energyService, Level level,
            CallbackInfoReturnable<Integer> cir) {
        CraftingRequestContext.clear();
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    private void factoryNotifyCraftingRequestFinished(boolean success, CallbackInfo ci) {
        // A successful finish means the request's outputs are already delivered (by the job that
        // pushed them); that job finishes on its own. Only cancelled/failed requests should cancel
        // the still-running factory jobs that were stamped with this request id.
        if (success) {
            return;
        }
        try {
            var craftingId = craftingIdOf((CraftingCpuLogic) (Object) this);
            if (craftingId == null) {
                return;
            }
            var grid = cluster.getGrid();
            if (grid == null) {
                return;
            }
            // The controller only records the id here; the actual job cancellation is deferred
            // to the controller's own server tick so no storage/grid service is re-entered while
            // the crafting service is updating.
            for (var controller : grid.getMachines(FactoryControllerBlockEntity.class)) {
                controller.onCraftingRequestFinished(craftingId);
            }
        } catch (RuntimeException exception) {
            AppliedFactory.LOGGER.error(
                    "Failed to notify factory controllers of a finished crafting request", exception);
        }
    }

    private static UUID craftingIdOf(CraftingCpuLogic logic) {
        ICraftingLink link = logic.getLastLink();
        return link == null ? null : link.getCraftingID();
    }
}
