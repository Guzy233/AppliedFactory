package com.fulent.appliedfactory.item;

import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.parts.IPartItem;
import appeng.api.parts.PartHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Item form of the factory bus AE cable part. */
public final class FactoryBusItem extends Item implements IPartItem<FactoryBusPart> {
    public FactoryBusItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return PartHelper.usePartItem(context);
    }

    @Override
    public Class<FactoryBusPart> getPartClass() {
        return FactoryBusPart.class;
    }

    @Override
    public FactoryBusPart createPart() {
        return new FactoryBusPart(this);
    }
}
