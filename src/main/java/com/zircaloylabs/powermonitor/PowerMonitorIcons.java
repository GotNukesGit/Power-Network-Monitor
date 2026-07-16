package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.IIconContainer;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Registers the per-tier cover-overlay icons on Forge's BLOCK texture map
 * (TextureStitchEvent.Pre) and exposes them as IIconContainer instances
 * the cover can layer onto its face texture via TextureFactory.of(...).
 *
 * ATLAS PATH RULE (this was the magenta-cover-face bug -- do not regress):
 * TextureMap resolves a registered name as basePath + "/" + name + ".png",
 * where basePath is "textures/blocks" for the block atlas and
 * "textures/items" for the items atlas. The atlas ALREADY supplies the
 * blocks/ (or items/) directory -- putting "blocks/" inside the registered
 * name double-prefixes the path:
 *
 *   "powermonitor:blocks/overlay_x" on the BLOCK atlas
 *       -> textures/blocks/blocks/overlay_x.png   (does not exist -> missingno)
 *   "powermonitor:overlay_x" on the BLOCK atlas
 *       -> textures/blocks/overlay_x.png          (correct)
 *
 * Registered names only contain a subdirectory when the file actually
 * lives in one (e.g. GT's "gregtech:iconsets/X" maps to
 * textures/blocks/iconsets/X.png).
 *
 * ATLAS GUARD (do not remove): TextureStitchEvent.Pre fires once per
 * texture map -- blocks (type 0) AND items (type 1). Without the type
 * check, this handler also registers on the ITEMS atlas, and CONTAINERS
 * keeps whichever map stitched last (resource reloads iterate
 * TextureManager's HashMap, so the order is effectively arbitrary),
 * randomly corrupting the cover face with wrong-atlas UV coordinates.
 * The inventory/NEI icon is handled separately by
 * ItemPowerMonitorCover.registerIcons() on the items atlas; this class
 * is block-atlas only.
 *
 * SEQUENCING (the reason CONTAINERS is built eagerly, not inside the event
 * handler): ModPowerMonitorRegistration#registerAll() runs during preInit,
 * building each ITexture (via TextureFactory.of(IIconContainer)) up front.
 * TextureStitchEvent.Pre fires LATER, during the actual atlas stitch, so
 * stable container instances must exist before the icons do; the event
 * handler only populates their icon field. TextureFactory.of() calls
 * getIcon() lazily at render time, so this is safe.
 *
 * IMPORTANT: an instance of this class must be registered on
 * MinecraftForge.EVENT_BUS during client-side preInit -- call
 * registerSelf() once from PowerMonitorMod#preInit (client side only).
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
        if (event.map.getTextureType() != 0) {
            return; // block atlas only -- see ATLAS GUARD note in class javadoc
        }
        for (PowerMonitorTier tier : PowerMonitorTier.values()) {
            // resolves to textures/blocks/overlay_powermonitor_<tier>.png -- see ATLAS PATH RULE
            String path = "powermonitor:overlay_powermonitor_" + tier.name().toLowerCase();
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
