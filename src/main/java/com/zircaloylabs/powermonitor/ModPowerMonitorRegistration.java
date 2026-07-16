package com.zircaloylabs.powermonitor;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTModHandler;
import net.minecraft.item.ItemStack;

/**
 * Wires ItemPowerMonitorCover + PowerMonitorCover + textures + recipes
 * together. Call registerAll() once from your mod's preInit or init.
 *
 * Registration pattern (item -> texture -> CoverRegistry.registerCover)
 * confirmed this session against gregtech.common.items.MetaGeneratedItem02
 * #registerCovers(), the real method GT uses to register its own covers:
 *
 *   CoverRegistry.registerCover(
 *       ItemList.Cover_WirelessNeedsMaintainance.get(1L, new Object[0]),
 *       TextureFactory.of(Textures.BlockIcons.MACHINE_CASINGS[2][0],
 *                          TextureFactory.of(Textures.BlockIcons.OVERLAY_WIRELESS_MAINTENANCE_DETECTOR)),
 *       context -> new CoverWirelessMaintenanceDetector(context, TextureFactory.of(...))
 *   );
 *
 * We follow the same shape, substituting our own registered item (since
 * ItemList is GT's internal enum, not something a separate mod writes into)
 * and our own overlay textures (see gen_textures.py / assets/powermonitor/
 * textures/blocks/) layered over GT's existing MACHINE_CASINGS art so the
 * cover still reads visually as GT machinery.
 *
 * MACHINE_CASINGS[tier][x] indexing: CONFIRMED this session by cross-checking
 * gregtech.api.metatileentity.implementations.MTETransformer (a real tiered
 * GT machine), which indexes as MACHINE_CASINGS[this.mTier][...] where mTier
 * is GT's standard tier integer (0=ULV, 1=LV, 2=MV... matching GTValues.V[]
 * ordering). PowerMonitorTier's enum ordinals were deliberately declared in
 * the same ULV-first order, so tier.ordinal() lines up correctly -- not a
 * coincidence, confirmed by this cross-check rather than assumed.
 */
public class ModPowerMonitorRegistration {

    public static ItemPowerMonitorCover coverItem;

    public static void registerAll() {
        coverItem = new ItemPowerMonitorCover();
        GameRegistry.registerItem(coverItem, "powermonitor_cover");

        for (PowerMonitorTier tier : PowerMonitorTier.values()) {
            registerTier(tier);
        }
    }

    private static void registerTier(PowerMonitorTier tier) {
        ItemStack coverStack = coverItem.itemStack(tier);

        // Base GT machine-casing layer + our own animated CRT-readout overlay
        // layered on top (PowerMonitorIcons registers/animates the overlay
        // icon via TextureStitchEvent -- see that class for the sequencing
        // note on why getOverlayContainer() is safe to call here during
        // preInit even though textures aren't stitched yet).
        ITexture baseLayer = TextureFactory.of(
                Textures.BlockIcons.MACHINE_CASINGS[tier.ordinal()][0]
        );
        ITexture readoutLayer = TextureFactory.of(PowerMonitorIcons.getOverlayContainer(tier));
        ITexture overlay = TextureFactory.of(baseLayer, readoutLayer);

        CoverRegistry.registerCover(
                coverStack,
                overlay,
                context -> new PowerMonitorCover(context, overlay, tier)
        );

        registerRecipe(tier, coverStack);
    }

    /**
     * Recipe uses GT's real circuit tier items (confirmed via strings scan
     * of gregtech.api.enums.ItemList.class this session: Circuit_Primitive,
     * Circuit_Basic, Circuit_Good, Circuit_Advanced, Circuit_Data,
     * Circuit_Elite, Circuit_Master all confirmed present).
     *
     * ZPM/UV circuit mapping is a WEAKER claim: only 7 plain circuit tiers
     * were found for 9 PowerMonitorTiers, so ZPM/UV fall back to reusing
     * Circuit_Master below rather than guessing at Circuit_Biowarecomputer/
     * Circuit_Biowaresupercomputer's actual tier alignment (those exist in
     * the jar but their tier correspondence wasn't verified). Cheap to fix
     * once you've confirmed the right constants -- just swap the two lines
     * marked below.
     *
     * Redstone is the second ingredient (vanilla, no verification needed,
     * thematically fits a "detector/monitor" cover). Casing/plate material
     * scaling by tier was deliberately left OUT of this recipe rather than
     * guessed at -- GT's real tier-material convention (e.g. which metal
     * plate belongs at which voltage tier) wasn't decompiled/verified this
     * session. Add a tier-appropriate plate ingredient once you've checked
     * that, if you want period-accurate material cost.
     */
    private static void registerRecipe(PowerMonitorTier tier, ItemStack output) {
        ItemStack circuit = circuitForTier(tier);
        if (circuit == null) {
            return; // unresolved circuit item for this tier -- skip rather than register a broken recipe
        }

        GTModHandler.addCraftingRecipe(output, new Object[]{
                " C ",
                "cRc",
                " C ",
                'C', circuit,
                'c', circuit, // TODO replace with a real tier-matched cable ItemStack once verified
                'R', new ItemStack(net.minecraft.init.Items.redstone)
        });
    }

    private static ItemStack circuitForTier(PowerMonitorTier tier) {
        switch (tier) {
            case ULV: return ItemList.Circuit_Primitive.get(1L, new Object[0]);
            case LV:  return ItemList.Circuit_Basic.get(1L, new Object[0]);
            case MV:  return ItemList.Circuit_Good.get(1L, new Object[0]);
            case HV:  return ItemList.Circuit_Advanced.get(1L, new Object[0]);
            case EV:  return ItemList.Circuit_Data.get(1L, new Object[0]);
            case IV:  return ItemList.Circuit_Elite.get(1L, new Object[0]);
            case LuV: return ItemList.Circuit_Master.get(1L, new Object[0]);
            case ZPM: return ItemList.Circuit_Master.get(1L, new Object[0]); // TODO verify -- see class javadoc
            case UV:  return ItemList.Circuit_Master.get(1L, new Object[0]); // TODO verify -- see class javadoc
            default:  return null;
        }
    }
}
