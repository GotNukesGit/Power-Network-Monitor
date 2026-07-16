package com.zircaloylabs.powermonitor;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.creativetab.CreativeTabs;

import java.util.List;

/**
 * One Item, one metadata value per PowerMonitorTier (standard Forge 1.7.10
 * "meta item" pattern -- same approach GT itself uses for its own cover
 * items via MetaGeneratedItem02, confirmed this session, though we use our
 * own Item class here rather than writing into GT's internal item, since
 * this is a separate mod).
 *
 * itemStack(tier) is the single place that builds a correctly-tiered
 * ItemStack -- use it everywhere else (recipes, registerAllTiers(), etc.)
 * rather than constructing `new ItemStack(this, 1, ordinal)` by hand.
 */
public class ItemPowerMonitorCover extends Item {

    public ItemPowerMonitorCover() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setUnlocalizedName("powermonitor.cover");
        setCreativeTab(CreativeTabs.tabRedstone); // placeholder tab -- swap for your own mod tab if you have one
    }

    public ItemStack itemStack(PowerMonitorTier tier) {
        return new ItemStack(this, 1, tier.ordinal());
    }

    public static PowerMonitorTier tierOf(ItemStack stack) {
        int meta = stack.getItemDamage();
        PowerMonitorTier[] all = PowerMonitorTier.values();
        return all[Math.max(0, Math.min(meta, all.length - 1))];
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        return "item.powermonitor.cover." + tierOf(stack).name();
    }

    @Override
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (PowerMonitorTier tier : PowerMonitorTier.values()) {
            list.add(itemStack(tier));
        }
    }
}
