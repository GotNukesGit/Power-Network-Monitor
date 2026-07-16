package com.zircaloylabs.powermonitor;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import java.util.List;

/**
 * Real Cover subclass, written against gregtech.common.covers.Cover as
 * decompiled from the live 2.9.0-beta-2 jar this session (constructor
 * signature and method set confirmed against
 * gregtech.common.covers.redstone.CoverWirelessMaintenanceDetector, a real
 * working GT cover used as a template).
 *
 * v1 deliberately skips a custom ModularUI2 GUI (createScreen/getCoverGui) --
 * the base Cover class already supplies working defaults for those, and a
 * screwdriver-click chat readout is a fast, genuinely functional first
 * version. A proper HUD panel is a natural v2 once this compiles and runs
 * in your dev environment.
 *
 * Known limitation:
 *   - Tier persistence: readDataFromNbt/saveDataToNbt below persist the tier
 *     ordinal, but NOT the RollingSampleBuffer history (history resets on
 *     chunk unload/reload). Acceptable v1 limitation -- flag if you want
 *     history persisted across reloads, that's a bit more NBT work.
 */
public class PowerMonitorCover extends Cover {

    private final PowerMonitorCoverBehavior behavior;

    public PowerMonitorCover(CoverContext context, ITexture coverTexture, PowerMonitorTier startingTier) {
        super(context, coverTexture);
        this.behavior = new PowerMonitorCoverBehavior(startingTier);
    }

    /**
     * THE ACTUAL ROOT CAUSE of every "0/0, 0 cables visited" symptom tonight.
     * Confirmed via decompile of gregtech.api.metatileentity.CoverableTileEntity
     * #tickCoverAtSide(): `if (tCoverTickRate > 0 && aTickTimer % tCoverTickRate == 0)
     * cover.doCoverThings(...)`. Cover's base getMinimumTickRate() defaults to 0,
     * and getDefaultTickRate() just returns that. A tick rate of 0 means the
     * `tCoverTickRate > 0` check fails FOREVER, so doCoverThings() is never called
     * at all -- not throttled, not delayed, literally never invoked. Every BFS/
     * voltage-matching fix made earlier tonight was correct code that never got
     * a chance to run, because the cover was never ticking in the first place.
     * Fix: override to a real tick rate (20 = once per second at 20 tps).
     */
    @Override
    public int getMinimumTickRate() {
        return 20;
    }

    @Override
    public void doCoverThings(byte aRedstone, long aTickTimer) {
        if (behavior.isDestroyed()) {
            return;
        }
        ICoverable tile = getTile();
        if (tile instanceof IGregTechTileEntity) {
            behavior.onTick(aTickTimer, (IGregTechTileEntity) tile);
        }
    }

    /**
     * v1 "GUI": right-click with a screwdriver prints the live dashboard to
     * chat. Base Cover#onCoverScrewdriverClick signature confirmed against
     * the decompiled base class this session.
     */
    @Override
    public void onCoverScrewdriverClick(EntityPlayer aPlayer, float aX, float aY, float aZ) {
        if (behavior.isDestroyed()) {
            aPlayer.addChatMessage(new ChatComponentText("§4Power Monitor destroyed (overvolted) -- replace it."));
            return;
        }

        PowerMonitorTier tier = behavior.getTier();
        long gen = behavior.getLiveGenerationEUt();
        long cons = behavior.getLiveConsumptionEUt();
        long net = behavior.getLiveNetEUt();

        aPlayer.addChatMessage(new ChatComponentText("§6-- Power Monitor [" + tier.name() + "] --"));
        aPlayer.addChatMessage(new ChatComponentText("§7[debug] cables visited: " + behavior.getLastCablesVisited()));
        aPlayer.addChatMessage(new ChatComponentText("Generation: " + gen + " EU/t   Consumption: " + cons + " EU/t"));
        aPlayer.addChatMessage(new ChatComponentText((net >= 0 ? "§aSurplus: +" : "§cDeficit: ") + net + " EU/t"));

        long cableLimit = behavior.getAnchorThroughputEUt();
        if (cableLimit > 0) {
            aPlayer.addChatMessage(new ChatComponentText("§7This cable: " + cableLimit + " EU/t max throughput"));
        }

        long maxGen = behavior.getMaxGenerationEUt();
        long fuel = behavior.getFuelReserveEU();
        if (maxGen > 0) {
            aPlayer.addChatMessage(new ChatComponentText(
                    "Generation capacity: " + maxGen + " EU/t rated (" + (100L * gen / maxGen) + "% in use)"));
        }
        if (fuel > 0) {
            StringBuilder fuelLine = new StringBuilder("Fuel reserves: ").append(fuel).append(" EU");
            long atCurrent = behavior.getFuelSecondsAtCurrentRate();
            long atMax = behavior.getFuelSecondsAtMaxRate();
            if (atCurrent >= 0) {
                fuelLine.append("  (~").append(formatSeconds(atCurrent)).append(" at current rate");
                if (atMax >= 0 && atMax != atCurrent) {
                    fuelLine.append(", ~").append(formatSeconds(atMax)).append(" at full burn");
                }
                fuelLine.append(')');
            } else if (atMax >= 0) {
                fuelLine.append("  (~").append(formatSeconds(atMax)).append(" at full burn)");
            }
            aPlayer.addChatMessage(new ChatComponentText(fuelLine.toString()));
        } else if (maxGen > 0) {
            aPlayer.addChatMessage(new ChatComponentText("§eNo fuel in generator tanks/input slots."));
        }

        long bufCap = behavior.getLiveBufferCapacityEU();
        if (bufCap > 0) {
            long buf = behavior.getLiveBufferedEU();
            int pct = (int) (100L * buf / bufCap);
            aPlayer.addChatMessage(new ChatComponentText("Buffer: " + buf + " / " + bufCap + " EU (" + pct + "%)"));
            long toEmpty = behavior.getSecondsToEmpty();
            long toFull = behavior.getSecondsToFull();
            if (toEmpty >= 0) {
                aPlayer.addChatMessage(new ChatComponentText("§cTime to empty: ~" + formatSeconds(toEmpty)));
            } else if (toFull >= 0) {
                aPlayer.addChatMessage(new ChatComponentText("§aTime to full: ~" + formatSeconds(toFull)));
            }
        }

        RollingSampleBuffer history = behavior.getHistory();
        if (history != null) {
            aPlayer.addChatMessage(new ChatComponentText(String.format(
                    "Peak draw (%s window): %d EU/t",
                    formatSeconds(tier.historySeconds), history.getPeakConsumption())));
            aPlayer.addChatMessage(new ChatComponentText(String.format(
                    "Peak deficit: %d EU/t", history.getPeakDeficit())));
        } else {
            aPlayer.addChatMessage(new ChatComponentText("§7(Upgrade past ULV for history tracking)"));
        }

        if (behavior.isNetworkLargerThanTierSupports()) {
            aPlayer.addChatMessage(new ChatComponentText(
                    "§eNetwork is larger than this tier can fully track -- upgrade for complete readings."));
        }
    }

    static String formatSeconds(long totalSeconds) { // package-private: also used by PowerMonitorWailaProvider
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        return h > 0 ? (h + "h " + m + "m") : (m + "m");
    }

    /** Call from your upgrade-item right-click handler once that item exists. */
    public boolean upgradeTier(PowerMonitorTier newTier) {
        return behavior.tryUpgrade(newTier);
    }

    public PowerMonitorCoverBehavior getBehavior() {
        return behavior;
    }

    @Override
    public List<String> getAdditionalTooltip() {
        List<String> tip = super.getAdditionalTooltip();
        tip.add("§7Power Monitor [" + behavior.getTier().name() + "]");
        tip.add("§7Right-click with screwdriver for live readout.");
        return tip;
    }

    // --- Persistence: tier only (see class javadoc re: history) ---
    // NOTE: Cover#readFromNbt / writeToNBT are declared `final` on the base
    // class -- confirmed this session. The real overridable hooks (per the
    // working CoverWirelessMaintenanceDetector template) are
    // readDataFromNbt(NBTBase) / saveDataToNbt(), called internally by
    // those final wrappers.

    @Override
    protected NBTBase saveDataToNbt() {
        NBTTagCompound tag = (NBTTagCompound) super.saveDataToNbt();
        tag.setInteger("tier", behavior.getTier().ordinal());
        return tag;
    }

    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        super.readDataFromNbt(nbt);
        NBTTagCompound tag = (NBTTagCompound) nbt;
        if (tag.hasKey("tier")) {
            int ord = tag.getInteger("tier");
            PowerMonitorTier[] all = PowerMonitorTier.values();
            if (ord >= 0 && ord < all.length) {
                behavior.tryUpgrade(all[ord]); // no-op if not actually higher; fine for restore-to-same-tier
            }
        }
    }

    /**
     * Registration now lives in ModPowerMonitorRegistration.registerAll() --
     * it needs the ItemPowerMonitorCover instance and per-tier textures,
     * which don't belong on this class. Call that from your mod's preInit.
     */
}
