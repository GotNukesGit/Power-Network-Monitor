package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTECable;

/**
 * Core behavior for the Power Monitor cover, independent of the specific
 * Cover subclass wiring. One instance of this class per placed cover (holds
 * this cover's own history buffer and tier state -- NOT shared/static).
 * PowerMonitorCover#doCoverThings calls onTick(); the readout/GUI layer
 * reads the accessors.
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
    private long liveFuelReserveEU = 0L;
    private long liveMaxGenerationEUt = 0L;
    private long liveBufferNetChargeEUt = 0L;
    private long anchorThroughputEUt = 0L; // this cable's rated V * A
    private boolean lastDiscoveryTruncated = false;
    private int lastCablesVisited = 0; // diagnostic, shown in chat readout while chasing the discovery bug

    private static final int TICKS_PER_SAMPLE = 20; // 1 Hz at 20 tps
    private static final int TICKS_PER_SECOND = 20;

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

    private long lastSampleWorldTime = -1L;

    /**
     * Call once per tick from Cover#doCoverThings. Internally throttles to ~1 Hz
     * for the actual discovery+sample work.
     *
     * Gates on real world time (hostTile.getWorld().getTotalWorldTime()) rather
     * than the aTickTimer parameter GT passes into doCoverThings() -- that
     * parameter's semantics interact with GT's own cover tick-rate throttling,
     * and gating on it was confirmed in-game (MV tier) to never fire: peak
     * deficit stayed at the never-recorded sentinel with live values stuck at 0
     * on a network genuinely producing and drawing power.
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

        // Voltage rating comes from MTECable's own public mVoltage field, NOT
        // hostTile.getInputVoltage()/getOutputVoltage() -- those are hardcoded
        // to return 0L for cable/pipe tile entities (confirmed against
        // BaseMetaPipeEntity: cables are passive conductors, so GT stubs the
        // "port rating" getters that only make sense for machines). Gating the
        // overvolt check on the tile-level getters left it comparing
        // 0 > maxSafeVoltage forever -- confirmed in-game before the fix.
        IMetaTileEntity hostMte = hostTile.getMetaTileEntity();
        long observedVoltage = 0L;
        if (hostMte instanceof MTECable) {
            MTECable cable = (MTECable) hostMte;
            observedVoltage = cable.mVoltage;
            // Rated network throughput at this specific cable: V * A.
            // mVoltage/mAmperage are public final fields on MTECable
            // (verified against GT 5.09.54.20 source).
            anchorThroughputEUt = cable.mVoltage * cable.mAmperage;
        }

        if (observedVoltage > tier.maxSafeVoltage) {
            destroy(hostTile, observedVoltage);
            return;
        }

        NetworkDiscovery.Result discovery = NetworkDiscovery.discover(hostTile, tier.maxTrackedNodes);
        lastDiscoveryTruncated = discovery.truncated;
        lastCablesVisited = discovery.cablesVisited;

        NetworkDiscovery.Snapshot snap = NetworkDiscovery.summarize(discovery.members);
        liveGenerationEUt = snap.totalGenerationEUt;
        liveConsumptionEUt = snap.totalConsumptionEUt;
        liveBufferedEU = snap.totalBufferedEU;
        liveBufferCapacityEU = snap.totalBufferCapacityEU;
        liveFuelReserveEU = snap.totalFuelReserveEU;
        liveMaxGenerationEUt = snap.maxGenerationEUt;
        liveBufferNetChargeEUt = snap.bufferNetChargeEUt;

        if (history != null) {
            history.record(liveConsumptionEUt, liveGenerationEUt, worldTime);
        }
    }

    /**
     * Same failure mode as jamming a high-voltage line into a low-tier cable --
     * this cover is rated for its tier same as a transformer, so it dies the
     * same way. Reuses GT's OWN overvolt handling (doExplosion(long), declared
     * public on IGregTechTileEntity -- the literal same call GT's cables and
     * machines make when overvolted, verified against BaseMetaTileEntity
     * #injectEnergyUnits).
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

    /** EU-equivalent of fuel in generator tanks/input slots. See NetworkDiscovery fuel notes for scope. */
    public long getFuelReserveEU() {
        return liveFuelReserveEU;
    }

    /** Sum of the network's generators' rated output (their maxEUOutput). */
    public long getMaxGenerationEUt() {
        return liveMaxGenerationEUt;
    }

    /** Net storage flow: >0 buffers charging, <0 discharging (EU/t). */
    public long getBufferNetChargeEUt() {
        return liveBufferNetChargeEUt;
    }

    /** Rated throughput of the cable this cover sits on: voltage * amperage (EU/t). */
    public long getAnchorThroughputEUt() {
        return anchorThroughputEUt;
    }

    public boolean isNetworkLargerThanTierSupports() {
        return lastDiscoveryTruncated;
    }

    /** Diagnostic: how many cable segments the BFS actually walked last poll. */
    public int getLastCablesVisited() {
        return lastCablesVisited;
    }

    /**
     * Seconds until buffer empties at current net drain, or -1 if not draining.
     * (EU divided by EU/t yields TICKS -- the /20 to reach seconds was missing
     * originally, overstating every runtime 20x. Same fix in the other
     * time-projection accessors below.)
     */
    public long getSecondsToEmpty() {
        long net = getLiveNetEUt();
        if (net >= 0 || liveBufferedEU <= 0) {
            return -1;
        }
        return liveBufferedEU / -net / TICKS_PER_SECOND;
    }

    /** Seconds until buffer fills at current net surplus, or -1 if not charging / already full. */
    public long getSecondsToFull() {
        long net = getLiveNetEUt();
        long remaining = liveBufferCapacityEU - liveBufferedEU;
        if (net <= 0 || remaining <= 0) {
            return -1;
        }
        return remaining / net / TICKS_PER_SECOND;
    }

    /** Seconds the fuel reserve lasts at the CURRENT generation rate, or -1 if unknowable (no gen / no fuel). */
    public long getFuelSecondsAtCurrentRate() {
        if (liveFuelReserveEU <= 0 || liveGenerationEUt <= 0) {
            return -1;
        }
        return liveFuelReserveEU / liveGenerationEUt / TICKS_PER_SECOND;
    }

    /** Seconds the fuel reserve lasts with every generator at full rated output, or -1 if unknowable. */
    public long getFuelSecondsAtMaxRate() {
        if (liveFuelReserveEU <= 0 || liveMaxGenerationEUt <= 0) {
            return -1;
        }
        return liveFuelReserveEU / liveMaxGenerationEUt / TICKS_PER_SECOND;
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
