package com.fulent.appliedfactory.part;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryBusTarget;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.parts.PartModels;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A channel-using AE cable part that exposes the adjacent block to factory
 * controller programs.
 */
public final class FactoryBusPart
        implements IPart, IGridNodeListener<FactoryBusPart> {

    private static final ResourceLocation MODEL_BASE = aeModel("part/import_bus_base");
    private static final ResourceLocation MODEL_OFF = aeModel("part/import_bus_off");
    private static final ResourceLocation MODEL_ON = aeModel("part/import_bus_on");
    private static final ResourceLocation MODEL_HAS_CHANNEL = aeModel("part/import_bus_has_channel");
    private static final IPartModel MODELS_OFF = model(MODEL_BASE, MODEL_OFF);
    private static final IPartModel MODELS_ON = model(MODEL_BASE, MODEL_ON);
    private static final IPartModel MODELS_HAS_CHANNEL = model(MODEL_BASE, MODEL_HAS_CHANNEL);

    private final IPartItem<FactoryBusPart> partItem;
    private final IManagedGridNode mainNode;

    @Nullable
    private IPartHost host;
    @Nullable
    private BlockEntity hostBlockEntity;
    @Nullable
    private Direction side;
    /**
     * Long-lived target facade: its AE2 external-storage strategies own NeoForge capability
     * caches, which observe target-block invalidation themselves. Inventory contents are never
     * cached here.
     */
    @Nullable
    private FactoryBusTarget cachedTarget;
    private boolean clientPowered;
    private boolean clientActive;
    private int redstoneOutput;

    public FactoryBusPart(IPartItem<FactoryBusPart> partItem) {
        this.partItem = Objects.requireNonNull(partItem);
        this.mainNode = GridHelper.createManagedNode(this, this)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.5D)
                .setVisualRepresentation(partItem)
                .setExposedOnSides(Set.of());
    }

    /** Register the borrowed AE2 import-bus models before model loading freezes. */
    public static void registerModels() {
        PartModels.registerModels(MODEL_BASE, MODEL_OFF, MODEL_ON, MODEL_HAS_CHANNEL);
    }

    @Override
    public IPartItem<?> getPartItem() {
        return partItem;
    }

    @Override
    public IGridNode getGridNode() {
        return mainNode.getNode();
    }

    @Override
    public void setPartHostInfo(@Nullable Direction side, IPartHost host, BlockEntity blockEntity) {
        cachedTarget = null;
        this.side = side;
        this.host = host;
        this.hostBlockEntity = blockEntity;
    }

    @Override
    public void addToWorld() {
        if (hostBlockEntity != null && hostBlockEntity.getLevel() != null) {
            mainNode.create(hostBlockEntity.getLevel(), hostBlockEntity.getBlockPos());
        }
    }

    @Override
    public void removeFromWorld() {
        mainNode.destroy();
        cachedTarget = null;
    }

    @Override
    public void onPlacement(Player player) {
        mainNode.setOwningPlayer(player);
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        mainNode.loadFromNBT(data);
        redstoneOutput = clampRedstone(data.getInt("redstoneOutput"));
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        mainNode.saveToNBT(data);
        data.putInt("redstoneOutput", redstoneOutput);
    }

    @Override
    public void writeToStream(RegistryFriendlyByteBuf data) {
        clientPowered = mainNode.isPowered();
        clientActive = mainNode.isOnline();
        data.writeBoolean(clientPowered);
        data.writeBoolean(clientActive);
        data.writeByte(redstoneOutput);
    }

    @Override
    public boolean readFromStream(RegistryFriendlyByteBuf data) {
        var powered = data.readBoolean();
        var active = data.readBoolean();
        var output = data.readUnsignedByte();
        var changed = powered != clientPowered || active != clientActive || output != redstoneOutput;
        clientPowered = powered;
        clientActive = active;
        redstoneOutput = output;
        return changed;
    }

    @Override
    public void writeVisualStateToNBT(CompoundTag data) {
        data.putBoolean("powered", isPowered());
        data.putBoolean("active", isActive());
        data.putInt("redstoneOutput", redstoneOutput);
    }

    @Override
    public void readVisualStateFromNBT(CompoundTag data) {
        clientPowered = data.getBoolean("powered");
        clientActive = data.getBoolean("active");
        redstoneOutput = clampRedstone(data.getInt("redstoneOutput"));
    }

    @Override
    public void onSaveChanges(FactoryBusPart owner, IGridNode node) {
        if (host != null) {
            host.markForSave();
        }
    }

    @Override
    public void onStateChanged(FactoryBusPart owner, IGridNode node, State state) {
        if (host != null) {
            host.markForUpdate();
        }
        var grid = node.getGrid();
        if (grid != null) {
            for (var controller : grid.getMachines(FactoryControllerBlockEntity.class)) {
                controller.onBusTopologyChanged();
            }
        }
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 5;
    }

    @Override
    public AECableType getDesiredConnectionType() {
        return AECableType.GLASS;
    }

    @Override
    public void getBoxes(IPartCollisionHelper helper) {
        helper.addBox(6, 6, 11, 10, 10, 13);
        helper.addBox(5, 5, 13, 11, 11, 14);
        helper.addBox(4, 4, 14, 12, 12, 16);
    }

    @Override
    public IPartModel getStaticModels() {
        if (isActive()) {
            return MODELS_HAS_CHANNEL;
        }
        return isPowered() ? MODELS_ON : MODELS_OFF;
    }

    public boolean isPowered() {
        return isClientSide() ? clientPowered : mainNode.isPowered();
    }

    public boolean isActive() {
        return isClientSide() ? clientActive : mainNode.isOnline();
    }

    private boolean isClientSide() {
        return hostBlockEntity == null || hostBlockEntity.getLevel() == null
                || hostBlockEntity.getLevel().isClientSide;
    }

    /** Sets the redstone strength emitted from this bus's physical cable face. */
    public void setRedstoneOutput(int level) {
        if (level < 0 || level > 15) {
            throw new IllegalArgumentException("Redstone output must be between 0 and 15");
        }
        if (redstoneOutput == level) {
            return;
        }
        redstoneOutput = level;
        if (host != null) {
            host.markForSave();
            host.markForUpdate();
            host.notifyNeighbors();
        }
    }

    @Override
    public boolean canConnectRedstone() {
        return true;
    }

    @Override
    public int isProvidingWeakPower() {
        return redstoneOutput;
    }

    @Override
    public int isProvidingStrongPower() {
        return 0;
    }

    public Optional<FactoryBusTarget> target() {
        if (hostBlockEntity == null || hostBlockEntity.getLevel() == null || side == null) {
            return Optional.empty();
        }
        if (cachedTarget == null) {
            cachedTarget = new FactoryBusTarget(
                    hostBlockEntity.getLevel(),
                    hostBlockEntity.getBlockPos().relative(side),
                    side);
        }
        return Optional.of(cachedTarget);
    }

    public Optional<FactoryBusAddress> address() {
        if (hostBlockEntity == null || hostBlockEntity.getLevel() == null || side == null) {
            return Optional.empty();
        }
        return Optional.of(new FactoryBusAddress(
                hostBlockEntity.getLevel().dimension().location(),
                hostBlockEntity.getBlockPos(),
                side));
    }

    public Optional<ResourceLocation> targetBlockId() {
        return target().map(FactoryBusTarget::blockId);
    }

    @Nullable
    public Direction getSide() {
        return side;
    }

    public BlockPos getHostPosition() {
        return hostBlockEntity == null ? BlockPos.ZERO : hostBlockEntity.getBlockPos();
    }

    @Nullable
    @Override
    public boolean onUseWithoutItem(Player player, Vec3 pos) {
        return false;
    }

    private static ResourceLocation aeModel(String path) {
        return ResourceLocation.fromNamespaceAndPath("ae2", path);
    }

    private static IPartModel model(ResourceLocation... resources) {
        var models = List.of(resources);
        return new IPartModel() {
            @Override
            public List<ResourceLocation> getModels() {
                return models;
            }
        };
    }

    private static int clampRedstone(int level) {
        return Math.max(0, Math.min(15, level));
    }
}
