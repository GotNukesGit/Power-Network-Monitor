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
 * items via MetaGeneratedItem02, though we use our own Item class here
 * rather than writing into GT's internal item, since this is a separate mod).
 *
 * itemStack(tier) is the single place that builds a correctly-tiered
 * ItemStack -- use it everywhere else (recipes, registerAllTiers(), etc.)
 * rather than constructing `new ItemStack(this, 1, ordinal)` by hand.
 *
 * TEXTURE ATLAS RULE (this was the NEI magenta-square bug -- do not regress):
 * In 1.7.10 there are TWO texture atlases with different base paths:
 *
 *   blocks atlas (type 0): resolves "modid:name" -> assets/modid/textures/blocks/name.png
 *   items  atlas (type 1): resolves "modid:name" -> assets/modid/textures/items/name.png
 *
 * Item.registerIcons() is ALWAYS handed the ITEMS atlas. Registering a
 * "powermonitor:blocks/..." path here therefore resolved to
 * assets/powermonitor/textures/items/blocks/overlay_powermonitor_*.png --
 * a file that does not exist. A sprite that fails to load is silently
 * given the missing-texture tile's atlas coordinates (vanilla
 * TextureMap.loadTextureAtlas does sprite.copyFrom(missingImage) for every
 * registered-but-unloaded sprite), which is why a post-stitch diagnostic
 * shows a "valid" sprite with real x/y/UV values for a FAILED load: those
 * UVs point at the magenta/black checkerboard tile.
 *
 * So: item icons registered here MUST use plain "powermonitor:<name>"
 * paths backed by PNGs in textures/items/ (see gen_item_icons.py). The
 * cover FACE texture is handled separately in PowerMonitorIcons on the
 * block atlas, using the same prefix-free name resolved against
 * textures/blocks/.
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
        PowerMonitorTier[] all = PowerMonitorTier.values();
        icons = new IIcon[all.length];
        for (PowerMonitorTier tier : all) {
            // ITEMS atlas path -- resolves to textures/items/overlay_powermonitor_<tier>.png
            String path = "powermonitor:overlay_powermonitor_" + tier.name().toLowerCase();
            icons[tier.ordinal()] = register.registerIcon(path);
        }
        PowerMonitorMod.LOG.debug("[powermonitor] registered {} item icons on the items atlas", all.length);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        if (icons == null) {
            return super.getIconFromDamage(meta);
        }
        int idx = Math.max(0, Math.min(meta, icons.length - 1));
        return icons[idx];
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
        // Creative tab / NEI listing: enabled tiers only. Damage-based
        // lookups (icons, tierOf) intentionally keep the FULL range so any
        // higher-tier item that already exists in a world still renders and
        // resolves rather than crashing.
        for (PowerMonitorTier tier : PowerMonitorTier.enabledTiers()) {
            list.add(itemStack(tier));
        }
    }
}
