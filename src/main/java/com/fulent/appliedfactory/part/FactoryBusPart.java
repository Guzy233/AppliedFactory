package com.fulent.appliedfactory.part;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryMachineAccess;
import com.fulent.appliedfactory.menu.FactoryBusMenu;

import appeng.api.ids.AEItemIds;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
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
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;

/**
 * A channel-using AE cable part that exposes the adjacent machine to factory
 * controller programs.
 */
public final class FactoryBusPart
        implements IPart, IGridNodeListener<FactoryBusPart>, IUpgradeableObject, ISegmentedInventory {
    public static final int UPGRADE_SLOTS = 4;

    private static final ResourceLocation MODEL_BASE = aeModel("part/import_bus_base");
    private static final ResourceLocation MODEL_OFF = aeModel("part/import_bus_off");
    private static final ResourceLocation MODEL_ON = aeModel("part/import_bus_on");
    private static final ResourceLocation MODEL_HAS_CHANNEL = aeModel("part/import_bus_has_channel");
    private static final IPartModel MODELS_OFF = model(MODEL_BASE, MODEL_OFF);
    private static final IPartModel MODELS_ON = model(MODEL_BASE, MODEL_ON);
    private static final IPartModel MODELS_HAS_CHANNEL = model(MODEL_BASE, MODEL_HAS_CHANNEL);

    private final IPartItem<FactoryBusPart> partItem;
    private final IManagedGridNode mainNode;
    private final IUpgradeInventory upgrades;

    @Nullable
    private IPartHost host;
    @Nullable
    private BlockEntity hostBlockEntity;
    @Nullable
    private Direction side;
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
        this.upgrades = UpgradeInventories.forMachine(partItem, UPGRADE_SLOTS, this::onUpgradesChanged);
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
    }

    @Override
    public void onPlacement(Player player) {
        mainNode.setOwningPlayer(player);
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        mainNode.loadFromNBT(data);
        upgrades.readFromNBT(data, "upgrades", registries);
        redstoneOutput = clampRedstone(data.getInt("redstoneOutput"));
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        mainNode.saveToNBT(data);
        upgrades.writeToNBT(data, "upgrades", registries);
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
    }

    /**
     * AE2 forwards cable neighbor changes to its parts. When the block this bus targets changed,
     * tell the controllers on our grid so their initializer re-runs — this replaces the bus-set
     * and machine-info part of the old per-tick topology fingerprint.
     */
    @Override
    public void onNeighborChanged(BlockGetter level, BlockPos pos, BlockPos neighbor) {
        if (hostBlockEntity == null || side == null
                || !neighbor.equals(hostBlockEntity.getBlockPos().relative(side))) {
            return;
        }
        var node = mainNode.getNode();
        var grid = node == null ? null : node.getGrid();
        if (grid == null) {
            return;
        }
        for (var controller : grid.getMachines(FactoryControllerBlockEntity.class)) {
            controller.onBusTopologyChanged();
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

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Nullable
    @Override
    public InternalInventory getSubInventory(ResourceLocation id) {
        return UPGRADES.equals(id) ? upgrades : null;
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        IPart.super.addAdditionalDrops(drops, wrenched);
        for (var stack : upgrades) {
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
    }

    @Override
    public void clearContent() {
        IPart.super.clearContent();
        upgrades.clear();
    }

    private void onUpgradesChanged() {
        if (host != null) {
            host.markForSave();
        }
    }

    public int accelerationCards() {
        return upgrades.getInstalledUpgrades(BuiltInRegistries.ITEM.get(AEItemIds.SPEED_CARD));
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

    public Optional<FactoryMachineAccess> machine() {
        if (hostBlockEntity == null || hostBlockEntity.getLevel() == null || side == null) {
            return Optional.empty();
        }
        return Optional.of(new FactoryMachineAccess(
                hostBlockEntity.getLevel(),
                hostBlockEntity.getBlockPos().relative(side),
                side));
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
        return machine().map(FactoryMachineAccess::blockId);
    }

    @Nullable
    public Direction getSide() {
        return side;
    }

    public BlockPos getHostPosition() {
        return hostBlockEntity == null ? BlockPos.ZERO : hostBlockEntity.getBlockPos();
    }

    @Nullable
    public BlockEntity hostBlockEntityForDiagnostics() {
        return hostBlockEntity;
    }

    @Override
    public boolean onUseWithoutItem(Player player, Vec3 pos) {
        if (!player.level().isClientSide && player instanceof ServerPlayer serverPlayer && side != null) {
            var title = Component.translatable("item.appliedfactory.factory_bus");
            ((IPlayerExtension) serverPlayer).openMenu(new SimpleMenuProvider(
                    (containerId, inventory, ignored) -> new FactoryBusMenu(containerId, inventory, this),
                    title), data -> {
                        data.writeBlockPos(getHostPosition());
                        data.writeEnum(side);
                    });
        }
        return true;
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
