package com.zircaloylabs.powermonitor;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

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
 *
 * BUG FIXED (found via in-game test): this class never overrode
 * registerIcons()/getIconFromDamage(), so it had NO inventory icon at all
 * -- exactly why NEI and the hotbar showed the default missing-texture
 * purple/black checkerboard. Reuses the same per-tier overlay PNG as the
 * cover's face texture (see gen_animated_textures.py) for visual
 * consistency -- one asset per tier instead of a separate flat item icon.
 */
public class ItemPowerMonitorCover extends Item {

    @SideOnly(Side.CLIENT)
    private IIcon[] icons;

    public ItemPowerMonitorCover() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setUnlocalizedName("powermonitor.cover");
        setCreativeTab(CreativeTabs.tabRedstone); // placeholder tab -- swap for your own mod tab if you have one
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register) {
        PowerMonitorMod.LOG.info("[powermonitor] registerIcons() CALLED on ItemPowerMonitorCover");
        PowerMonitorTier[] all = PowerMonitorTier.values();
        icons = new IIcon[all.length];
        for (PowerMonitorTier tier : all) {
            String path = "powermonitor:blocks/overlay_powermonitor_" + tier.name().toLowerCase();
            IIcon icon = register.registerIcon(path);
            icons[tier.ordinal()] = icon;
            PowerMonitorMod.LOG.info("[powermonitor]   registered icon for " + tier.name()
                    + " at path '" + path + "' -> " + icon);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        if (icons == null) {
            PowerMonitorMod.LOG.warn("[powermonitor] getIconFromDamage(" + meta
                    + ") called but icons array is NULL -- registerIcons() never ran or ran after this call");
            return super.getIconFromDamage(meta);
        }
        int idx = Math.max(0, Math.min(meta, icons.length - 1));
        IIcon result = icons[idx];
        // Log the icon's REAL state here (post-stitch, at actual render time) --
        // the earlier registerIcons()-time log always shows zeros for every
        // icon in the game (registerIcon() returns a placeholder immediately;
        // real pixel data loads later during the atlas stitch), so that
        // reading wasn't meaningful. This one fires every time the item is
        // drawn, well after stitching is done, and is the real signal.
        // Only log the first few times to avoid spamming the log every frame.
        if (renderLogCount < 3) {
            renderLogCount++;
            PowerMonitorMod.LOG.info("[powermonitor] getIconFromDamage(" + meta + ") POST-STITCH state -> "
                    + (result == null ? "NULL" : result.toString())
                    + (result == null ? "" : (" iconWidth=" + result.getIconWidth()
                        + " iconHeight=" + result.getIconHeight())));
        }
        if (result == null) {
            PowerMonitorMod.LOG.warn("[powermonitor] getIconFromDamage(" + meta
                    + ") -> icons[" + idx + "] is NULL despite icons array being non-null");
        }
        return result;
    }

    @SideOnly(Side.CLIENT)
    private static int renderLogCount = 0;

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
