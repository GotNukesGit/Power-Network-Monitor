package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.IIconContainer;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.eventhandler.SubscribeEvent;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Registers the per-tier animated cover-overlay icons on Forge's block
 * texture map (TextureStitchEvent.Pre -- the standard 1.7.10 mechanism for
 * registering an icon that isn't owned by a single Block/Item's own
 * registerIcons call) and exposes them as IIconContainer instances the
 * cover can layer onto its face texture via TextureFactory.of(...).
 *
 * SEQUENCING (the reason CONTAINERS is built eagerly, not inside the event
 * handler): ModPowerMonitorRegistration#registerAll() runs during preInit,
 * building each ITexture (via TextureFactory.of(IIconContainer)) up front.
 * TextureStitchEvent.Pre fires LATER, during the actual atlas stitch. If
 * the IIconContainer objects were only constructed inside the event
 * handler, getOverlayContainer(tier) would return null at the point
 * registerAll() actually needs it. Fix: create stable, mutable-icon
 * container instances immediately (static initializer below); the event
 * handler only SETS their icon field once icons are actually registered.
 * TextureFactory.of() holds the IIconContainer reference and calls
 * getIcon() lazily at render time, so this is safe -- the icon field just
 * needs to be populated by the time anything actually renders, not by the
 * time the ITexture object is constructed.
 *
 * The underlying PNGs (overlay_powermonitor_TIER.png) are vertically
 * stacked sprite sheets with a sibling .mcmeta animation descriptor --
 * Forge's texture stitcher detects and animates these automatically once
 * registered here. No custom render/tick code needed for the animation
 * itself.
 *
 * IMPORTANT: an instance of this class must be registered on
 * MinecraftForge.EVENT_BUS during client-side preInit for the icons to
 * ever load -- call registerSelf() once from PowerMonitorMod#preInit
 * (client side only, e.g. guarded by
 * FMLCommonHandler.instance().getSide().isClient() or a client proxy).
 */
@SideOnly(Side.CLIENT)
public final class PowerMonitorIcons {

    private static final MutableIconContainer[] CONTAINERS = buildContainers();

    private PowerMonitorIcons() {}

    private static MutableIconContainer[] buildContainers() {
        MutableIconContainer[] arr = new MutableIconContainer[PowerMonitorTier.values().length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new MutableIconContainer();
        }
        return arr;
    }

    /** Call once from the mod's client-side preInit. */
    public static void registerSelf() {
        MinecraftForge.EVENT_BUS.register(new PowerMonitorIcons());
    }

    @SubscribeEvent
    public void onTextureStitch(TextureStitchEvent.Pre event) {
        for (PowerMonitorTier tier : PowerMonitorTier.values()) {
            String path = "powermonitor:blocks/overlay_powermonitor_" + tier.name().toLowerCase();
            IIcon icon = event.map.registerIcon(path);
            CONTAINERS[tier.ordinal()].icon = icon;
        }
    }

    /**
     * Stable reference, safe to call during preInit (before textures are
     * stitched) -- the returned object's icon is populated later. See class
     * javadoc.
     */
    public static IIconContainer getOverlayContainer(PowerMonitorTier tier) {
        return CONTAINERS[tier.ordinal()];
    }

    public static IIcon getOverlayIcon(PowerMonitorTier tier) {
        return CONTAINERS[tier.ordinal()].icon;
    }

    /** IIconContainer wrapping a single icon that's populated after construction. */
    private static final class MutableIconContainer implements IIconContainer {
        volatile IIcon icon;

        @Override
        public IIcon getIcon() {
            return icon;
        }

        @Override
        public IIcon getOverlayIcon() {
            return null;
        }

        @Override
        public ResourceLocation getTextureFile() {
            return TextureMap.locationBlocksTexture;
        }
    }
}
