package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;

import java.util.List;

/**
 * Core behavior for the Power Monitor cover, independent of the specific
 * Cover subclass wiring (GT's cover GUI stack -- ModularScreen/PanelSyncManager
 * -- is version-coupled enough that the actual `extends Cover` subclass and
 * its GUI should be written against your live dev environment rather than
 * blind here; this class holds the logic that GUI/tick-handler calls into).
 *
 * Wiring sketch (to complete against your Cover/CoverContext constructor
 * signatures once you're set up locally):
 *
 *   public class PowerMonitorCover extends Cover {
 *       private final PowerMonitorCoverBehavior behavior;
 *       ...
 *       @Override
 *       public void doCoverThings(byte aRedstone, long aTickTimer) {
 *           behavior.onTick(aTickTimer, (IGregTechTileEntity) getTile());
 *       }
 *   }
 *
 * One instance of this class per placed cover (holds this cover's own
 * history buffer and tier state -- NOT shared/static).
 */
public class PowerMonitorCoverBehavior {

    private PowerMonitorTier tier;
    private RollingSampleBuffer history; // null if tier.historySeconds == 0
    private boolean destroyed = false;

    // Live values, refreshed once per second, read by the HUD/GUI layer.
    private long liveGenerationEUt = 0L;
    private long liveConsumptionEUt = 0L;
    private long liveBufferedEU = 0L;
    private long liveBufferCapacityEU = 0L;
    private boolean lastDiscoveryTruncated = false;

    private static final int TICKS_PER_SAMPLE = 20; // 1 Hz at 20 tps

    public PowerMonitorCoverBehavior(PowerMonitorTier startingTier) {
        setTier(startingTier);
    }

    private void setTier(PowerMonitorTier newTier) {
        this.tier = newTier;
        this.history = newTier.hasHistory() ? new RollingSampleBuffer(newTier.historySeconds) : null;
    }

    /** Call from the cover's screwdriver/upgrade-item interaction handler. */
    public boolean tryUpgrade(PowerMonitorTier newTier) {
        if (newTier.ordinal() <= tier.ordinal()) {
            return false; // not an upgrade
        }
        setTier(newTier);
        return true;
    }

    /**
     * Call once per tick from Cover#doCoverThings. Internally throttles to 1 Hz
     * for the actual discovery+sample work -- BFS-ing the whole network every
     * single tick would be needless overhead for a 2-hour rolling window.
     */
    private long lastSampleWorldTime = -1L;

    /**
     * Call once per tick from Cover#doCoverThings. Internally throttles to ~1 Hz
     * for the actual discovery+sample work.
     *
     * CONFIRMED BUG (found via in-game test, MV tier): originally gated on
     * `tickTimer % TICKS_PER_SAMPLE == 0` using the aTickTimer parameter GT
     * passes into doCoverThings(). That parameter's real semantics were never
     * verified -- GT covers throttle how often doCoverThings fires at all via
     * tick-rate settings, so aTickTimer almost certainly isn't "world tick
     * count incrementing by 1" the way this assumed, and the modulo gate
     * apparently never landed on zero: peak deficit stayed at Long.MIN_VALUE
     * (the never-recorded sentinel) with live gen/consumption stuck at 0 on
     * a network that was genuinely producing and drawing power. Fixed by
     * gating on real world time (hostTile.getWorld().getTotalWorldTime(),
     * already fetched independently) instead of GT's internal counter.
     */
    public void onTick(long tickTimer, IGregTechTileEntity hostTile) {
        if (destroyed) {
            return;
        }

        long worldTime = hostTile.getWorld() != null ? hostTile.getWorld().getTotalWorldTime() : 0L;
        if (lastSampleWorldTime >= 0 && worldTime - lastSampleWorldTime < TICKS_PER_SAMPLE) {
            return;
        }
        lastSampleWorldTime = worldTime;

        long observedVoltage = hostTile.getInputVoltage() > hostTile.getOutputVoltage()
                ? hostTile.getInputVoltage()
                : hostTile.getOutputVoltage();

        if (observedVoltage > tier.maxSafeVoltage) {
            destroy(hostTile, observedVoltage);
            return;
        }

        NetworkDiscovery.Result discovery = NetworkDiscovery.discover(hostTile, tier.maxTrackedNodes);
        lastDiscoveryTruncated = discovery.truncated;

        NetworkDiscovery.Snapshot snap = NetworkDiscovery.summarize(discovery.members);
        liveGenerationEUt = snap.totalGenerationEUt;
        liveConsumptionEUt = snap.totalConsumptionEUt;
        liveBufferedEU = snap.totalBufferedEU;
        liveBufferCapacityEU = snap.totalBufferCapacityEU;

        if (history != null) {
            history.record(liveConsumptionEUt, liveGenerationEUt, worldTime);
        }
    }

    /**
     * Same failure mode as jamming a high-voltage line into a low-tier cable --
     * this cover is rated for its tier same as a transformer, so it dies the
     * same way. Reuses GT's OWN overvolt handling rather than inventing a
     * separate explosion/burn effect.
     *
     * CONFIRMED this session: BaseMetaTileEntity#injectEnergyUnits() checks
     * `aVoltage > this.getInputVoltage()` and calls `this.doExplosion(aVoltage)`
     * on overvolt (gregtech.api.metatileentity.BaseMetaTileEntity, ~line 1785).
     * `doExplosion(long)` is declared directly on IGregTechTileEntity as a
     * public method, so it's callable on hostTile with no casting. This is
     * the literal same call GT's own cables/machines use when overvolted --
     * not a lookalike effect, the real mechanism.
     */
    private void destroy(IGregTechTileEntity hostTile, long observedVoltage) {
        destroyed = true;
        hostTile.doExplosion(observedVoltage);
    }

    // --- Read-only accessors for HUD/GUI ---

    public PowerMonitorTier getTier() {
        return tier;
    }

    public long getLiveGenerationEUt() {
        return liveGenerationEUt;
    }

    public long getLiveConsumptionEUt() {
        return liveConsumptionEUt;
    }

    public long getLiveNetEUt() {
        return liveGenerationEUt - liveConsumptionEUt;
    }

    public long getLiveBufferedEU() {
        return liveBufferedEU;
    }

    public long getLiveBufferCapacityEU() {
        return liveBufferCapacityEU;
    }

    public boolean isNetworkLargerThanTierSupports() {
        return lastDiscoveryTruncated;
    }

    /** Seconds until buffer empties at current net drain, or -1 if not draining. */
    public long getSecondsToEmpty() {
        long net = getLiveNetEUt();
        if (net >= 0 || liveBufferedEU <= 0) {
            return -1;
        }
        return liveBufferedEU / -net;
    }

    /** Seconds until buffer fills at current net surplus, or -1 if not charging / already full. */
    public long getSecondsToFull() {
        long net = getLiveNetEUt();
        long remaining = liveBufferCapacityEU - liveBufferedEU;
        if (net <= 0 || remaining <= 0) {
            return -1;
        }
        return remaining / net;
    }

    /** Null if this tier has no history (ULV). */
    public RollingSampleBuffer getHistory() {
        return history;
    }

    public void resetHistory() {
        if (history != null) {
            history.reset();
        }
    }

    public boolean isDestroyed() {
        return destroyed;
    }
}
