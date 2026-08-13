package com.fulent.appliedfactory;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.fulent.appliedfactory.block.FactoryControllerBlock;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.item.FactoryBusItem;
import com.fulent.appliedfactory.menu.FactoryBusMenu;
import com.fulent.appliedfactory.menu.FactoryControllerProgramMenu;
import com.fulent.appliedfactory.network.NetworkHandler;
import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.AECapabilities;
import appeng.api.ids.AEItemIds;
import appeng.api.upgrades.Upgrades;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(AppliedFactory.MOD_ID)
public final class AppliedFactory {
    public static final String MOD_ID = "appliedfactory";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 方块注册器
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    // 物品注册器
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    // 方块实体注册器
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister
            .create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    // 菜单注册器
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);

    // 注册方块
    public static final DeferredBlock<Block> FACTORY_CONTROLLER = BLOCKS.register("factory_controller",
            () -> new FactoryControllerBlock(BlockBehaviour.Properties.of().strength(2.5F)));
// 方块物品
    public static final DeferredItem<BlockItem> FACTORY_CONTROLLER_ITEM = ITEMS
            .registerSimpleBlockItem("factory_controller", FACTORY_CONTROLLER);
// 工厂总线物品
    public static final DeferredItem<FactoryBusItem> FACTORY_BUS_ITEM = ITEMS.register("factory_bus",
            () -> new FactoryBusItem(new Item.Properties()));
// 方块实体
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryControllerBlockEntity>> FACTORY_CONTROLLER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES
            .register("factory_controller", () -> BlockEntityType.Builder
                    .of(FactoryControllerBlockEntity::new, FACTORY_CONTROLLER.get()).build(null));
// 控制器面板
// 代码面板
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryControllerProgramMenu>> FACTORY_CONTROLLER_PROGRAM_MENU = MENUS
            .register("factory_controller_program",
                    () -> IMenuTypeExtension.create(FactoryControllerProgramMenu::new));
// 总线面板
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryBusMenu>> FACTORY_BUS_MENU = MENUS
            .register("factory_bus", () -> IMenuTypeExtension.create(FactoryBusMenu::new));

    public AppliedFactory(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);

        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        
        modEventBus.addListener(NetworkHandler::register);
        
        FactoryBusPart.registerModels();

        NeoForge.EVENT_BUS.addListener(
                com.fulent.appliedfactory.command.ExportCommand::register);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> Upgrades.add(BuiltInRegistries.ITEM.get(AEItemIds.SPEED_CARD),
                FACTORY_BUS_ITEM.get(), FactoryBusPart.UPGRADE_SLOTS));
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                FACTORY_CONTROLLER_BLOCK_ENTITY.get(), (factory, context) -> factory);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(FACTORY_CONTROLLER_ITEM);
            event.accept(FACTORY_BUS_ITEM);
        }
    }
}
