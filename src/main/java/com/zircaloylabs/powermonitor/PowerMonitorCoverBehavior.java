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
    private static final int CHART_MAX_POINTS = 120;

    // Downsampled chart series, rebuilt once per sample (NOT on GUI poll --
    // MUI2's sync handlers poll their supplier every container tick, so the
    // supplier must be a cheap cached read, same pattern as GT's Tesla Tower
    // chart). Swapped wholesale so GUI reads never see a half-built list.
    private volatile java.util.List<Double> chartConsumption = java.util.Collections.emptyList();
    private volatile java.util.List<Double> chartGeneration = java.util.Collections.emptyList();
    private volatile java.util.List<Double> chartDemand = java.util.Collections.emptyList();
    private volatile java.util.List<Double> chartBuffered = java.util.Collections.emptyList();
    private volatile java.util.List<Double> chartFuel = java.util.Collections.emptyList();

    private long liveDemandEUt = 0L;
    private long liveUnmetEUt = 0L;
    // Deadband-smoothed display copies (see applyDeadband) -- raw values feed
    // history/charts, smoothed values feed all live readouts so single-sample
    // jitter doesn't make the numbers flicker.
    private long shownDemandEUt = 0L;
    private long shownUnmetEUt = 0L;
    private long shownStorageFlowEUt = 0L;
    // Saturation hysteresis: warning latches ON after SAT_ON_SAMPLES
    // consecutive positive samples and OFF after SAT_OFF_SAMPLES clear ones,
    // so a threshold-straddling network doesn't strobe the warning.
    private int satOnStreak = 0;
    private int satOffStreak = 0;
    private boolean satLatched = false;
    private static final int SAT_ON_SAMPLES = 2;
    private static final int SAT_OFF_SAMPLES = 5;
    // Outage black box: opens an event after OUTAGE_ON consecutive samples
    // with unmet demand, closes after OUTAGE_OFF clear samples, and records
    // the worst moment (peak unmet + the demand/delivered pair at that
    // moment) so the player can see exactly why the network browned out.
    private static final int OUTAGE_ON = 2;
    private static final int OUTAGE_OFF = 5;
    private static final int OUTAGE_LOG_SIZE = 3;
    private int outageOnStreak = 0;
    private int outageOffStreak = 0;
    private Outage activeOutage = null;
    private final java.util.ArrayDeque<Outage> outageLog = new java.util.ArrayDeque<>();
    private long lastWorldTime = 0L;

    private static final class Outage {
        long startTime;
        long endTime = -1L; // -1 while ongoing
        long peakUnmet;
        long demandAtPeak;
        long deliveredAtPeak;
    }
    private long liveStorageDischargeCapEUt = 0L;
    private int demandMeteredCount = 0;
    private int generatorCount = 0;
    private int machineCount = 0;
    private int bufferCount = 0;
    private long topConsumerEUt = 0L;
    private String topConsumerName = "";
    private boolean supplySaturated = false;
    private String fuelScheduleFullBurn = "";
    private String fuelScheduleCurrent = "";

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
        lastWorldTime = worldTime;
        liveDemandEUt = snap.totalDemandEUt;
        // Unmet = demand minus delivered. Conservative when the network has
        // machines outside demand-metering coverage (their demand isn't
        // counted but their delivered draw is) -- so a positive number here
        // is a REAL shortfall, never a metering artifact.
        liveUnmetEUt = Math.max(0L, snap.totalDemandEUt - snap.totalConsumptionEUt);
        liveStorageDischargeCapEUt = snap.storageDischargeCapacityEUt;
        demandMeteredCount = snap.demandMeteredCount;
        generatorCount = snap.generatorCount;
        machineCount = snap.machineCount;
        bufferCount = snap.bufferCount;
        topConsumerEUt = snap.topConsumerEUt;
        topConsumerName = snap.topConsumerName;

        // Brownout heuristic (see RollingSampleBuffer#record for why the
        // "deficit" series can't detect this): generation is DELIVERED
        // power, so when every generator is pinned at rated output and
        // storage is empty (or absent), any additional demand is invisible
        // to the meters -- machines just starve. Generators >= 95% of rated
        // + buffers <= 2% full is the strongest honest signal available.
        boolean satRaw = liveUnmetEUt > 0
                || (liveMaxGenerationEUt > 0 && liveGenerationEUt * 100 >= liveMaxGenerationEUt * 95
                        && (liveBufferCapacityEU <= 0 || liveBufferedEU * 50 <= liveBufferCapacityEU));
        if (satRaw) {
            satOnStreak++;
            satOffStreak = 0;
            if (satOnStreak >= SAT_ON_SAMPLES) {
                satLatched = true;
            }
        } else {
            satOffStreak++;
            satOnStreak = 0;
            if (satOffStreak >= SAT_OFF_SAMPLES) {
                satLatched = false;
            }
        }
        supplySaturated = satLatched;

        // Display smoothing: 5% deadband (min 2 EU/t step) on the noisiest
        // readouts; zero always snaps immediately so an idle network reads 0.
        shownDemandEUt = applyDeadband(shownDemandEUt, liveDemandEUt);
        shownUnmetEUt = applyDeadband(shownUnmetEUt, liveUnmetEUt);
        shownStorageFlowEUt = applyDeadband(shownStorageFlowEUt, liveBufferNetChargeEUt);

        trackOutage(worldTime);

        // Fuel is PER GENERATOR, not a shared pool: when the generator with
        // the least fuel runs dry, network capacity steps down to whatever
        // the remaining generators can supply. These schedules project that
        // staircase, once at rated output and once at each generator's
        // current output.
        fuelScheduleFullBurn = buildFuelSchedule(snap.generatorFuelProfile, true);
        fuelScheduleCurrent = buildFuelSchedule(snap.generatorFuelProfile, false);

        if (history != null) {
            history.record(liveConsumptionEUt, liveGenerationEUt, liveDemandEUt, liveBufferedEU,
                    liveFuelReserveEU, worldTime);
            java.util.List<Double> cons = new java.util.ArrayList<>(CHART_MAX_POINTS);
            java.util.List<Double> gen = new java.util.ArrayList<>(CHART_MAX_POINTS);
            java.util.List<Double> dem = new java.util.ArrayList<>(CHART_MAX_POINTS);
            java.util.List<Double> buf = new java.util.ArrayList<>(CHART_MAX_POINTS);
            java.util.List<Double> fu = new java.util.ArrayList<>(CHART_MAX_POINTS);
            history.downsampleInto(cons, gen, dem, buf, fu, CHART_MAX_POINTS);
            chartConsumption = cons;
            chartGeneration = gen;
            chartDemand = dem;
            chartBuffered = buf;
            chartFuel = fu;
        }
    }

    private static long applyDeadband(long displayed, long actual) {
        if (actual == 0L) {
            return 0L;
        }
        long delta = Math.abs(actual - displayed);
        long threshold = Math.max(2L, Math.max(Math.abs(displayed), Math.abs(actual)) / 20L); // 5%
        return delta >= threshold ? actual : displayed;
    }

    private void trackOutage(long worldTime) {
        boolean shortfall = liveUnmetEUt > 0;
        if (shortfall) {
            outageOnStreak++;
            outageOffStreak = 0;
            if (activeOutage == null && outageOnStreak >= OUTAGE_ON) {
                activeOutage = new Outage();
                activeOutage.startTime = worldTime;
                outageLog.addFirst(activeOutage);
                while (outageLog.size() > OUTAGE_LOG_SIZE) {
                    outageLog.removeLast();
                }
            }
            if (activeOutage != null && liveUnmetEUt >= activeOutage.peakUnmet) {
                activeOutage.peakUnmet = liveUnmetEUt;
                activeOutage.demandAtPeak = liveDemandEUt;
                activeOutage.deliveredAtPeak = liveConsumptionEUt;
            }
        } else {
            outageOffStreak++;
            outageOnStreak = 0;
            if (activeOutage != null && outageOffStreak >= OUTAGE_OFF) {
                activeOutage.endTime = worldTime;
                activeOutage = null;
            }
        }
    }

    /** One display line per logged outage (most recent first, up to 2), or "" if the log is empty. */
    public String getOutageSummary(int index) {
        int i = 0;
        for (Outage o : outageLog) {
            if (i++ == index) {
                String when = PowerMonitorCover.formatSeconds(Math.max(0L, (lastWorldTime - o.startTime) / 20L));
                String dur = o.endTime < 0 ? "ongoing"
                        : "lasted " + PowerMonitorCover.formatSeconds(Math.max(1L, (o.endTime - o.startTime) / 20L));
                return when + " ago · " + dur + " · demand "
                        + com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil
                                .formatNumber(o.demandAtPeak)
                        + " vs " + com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil
                                .formatNumber(o.deliveredAtPeak)
                        + " delivered";
            }
        }
        return "";
    }

    /**
     * Builds the stepped capacity schedule, e.g. "128->1h05m, 96->2h10m, 32->3h":
     * the network can supply 128 EU/t until 1h05m (first generator dry), then
     * 96 until 2h10m, and so on. Entries are (rate the network sustains) ->
     * (time at which that rate ends). Capped at 3 steps for display.
     *
     * @param fullBurn true = every generator at rated output; false = at its
     *                 current measured output (generators idle or throttled
     *                 burn slower, so runtimes stretch accordingly).
     */
    private static String buildFuelSchedule(java.util.List<long[]> profile, boolean fullBurn) {
        java.util.List<double[]> items = new java.util.ArrayList<>(); // {rateEUt, secondsUntilDry}
        for (long[] p : profile) {
            long rate = fullBurn ? p[1] : p[0];
            long fuel = p[2];
            if (rate <= 0 || fuel <= 0) {
                continue; // idle or fuel-less generator contributes nothing to the fueled schedule
            }
            items.add(new double[] { rate, fuel / (rate * 20.0) });
        }
        if (items.isEmpty()) {
            return "";
        }
        items.sort(java.util.Comparator.comparingDouble(a -> a[1]));
        long totalRate = 0L;
        for (double[] it : items) {
            totalRate += (long) it[0];
        }
        StringBuilder sb = new StringBuilder();
        int steps = 0;
        for (double[] it : items) {
            if (steps > 0) {
                sb.append(", ");
            }
            sb.append(com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber(totalRate))
                    .append("→").append(PowerMonitorCover.formatSeconds((long) it[1]));
            totalRate -= (long) it[0];
            steps++;
            if (steps >= 2 && totalRate > 0) {
                sb.append(", …");
                break;
            }
        }
        return sb.toString();
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

    /** Net storage flow (deadband-smoothed): >0 buffers charging, <0 discharging (EU/t). */
    public long getBufferNetChargeEUt() {
        return shownStorageFlowEUt;
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

    /** True recipe demand (EU/t, deadband-smoothed for display) -- what the network WANTS. */
    public long getLiveDemandEUt() {
        return shownDemandEUt;
    }

    /** Demand minus delivered (deadband-smoothed): a positive value is real unmet load. */
    public long getLiveUnmetEUt() {
        return shownUnmetEUt;
    }

    /** Max EU/t storage could push when charged: sum of buffer voltage * battery count. */
    public long getStorageDischargeCapEUt() {
        return liveStorageDischargeCapEUt;
    }

    /** Total supply ceiling: rated generation + storage discharge capacity. */
    public long getSupplyCapacityEUt() {
        return liveMaxGenerationEUt + liveStorageDischargeCapEUt;
    }

    /** How many machines the demand meter covers (singleblocks with active recipes). */
    public int getDemandMeteredCount() {
        return demandMeteredCount;
    }

    /** Peak true demand over the history window (0 if no history). */
    public long getPeakDemandEUt() {
        return history != null ? history.getPeakDemand() : 0L;
    }

    /** Downsampled demand series (EU/t, oldest-first) for chart display. Empty if no history. */
    public java.util.List<Double> getChartDemand() {
        return chartDemand;
    }

    public int getGeneratorCount() {
        return generatorCount;
    }

    public int getMachineCount() {
        return machineCount;
    }

    public int getBufferCount() {
        return bufferCount;
    }

    public long getTopConsumerEUt() {
        return topConsumerEUt;
    }

    public String getTopConsumerName() {
        return topConsumerName;
    }

    /** True when generators are pinned near rated output with empty/no storage -- likely brownout. */
    public boolean isSupplySaturated() {
        return supplySaturated;
    }

    /** Stepped capacity schedule at rated output ("128->1h05m, 96->2h10m"), or "" if nothing fueled. */
    public String getFuelScheduleFullBurn() {
        return fuelScheduleFullBurn;
    }

    /** Stepped capacity schedule at current output, or "" if nothing fueled/running. */
    public String getFuelScheduleCurrent() {
        return fuelScheduleCurrent;
    }

    /** Downsampled buffered-EU series (oldest-first) for chart display. Empty if no history. */
    public java.util.List<Double> getChartBuffered() {
        return chartBuffered;
    }

    /** Downsampled fuel-reserve-EU series (oldest-first) for chart display. Empty if no history. */
    public java.util.List<Double> getChartFuel() {
        return chartFuel;
    }

    /** Downsampled consumption series (EU/t, oldest-first) for chart display. Empty if no history. */
    public java.util.List<Double> getChartConsumption() {
        return chartConsumption;
    }

    /** Downsampled generation series (EU/t, oldest-first) for chart display. Empty if no history. */
    public java.util.List<Double> getChartGeneration() {
        return chartGeneration;
    }

    /** Peak consumption over the history window (0 if no history). */
    public long getPeakConsumptionEUt() {
        return history != null ? history.getPeakConsumption() : 0L;
    }

    /** Peak deficit over the history window (0 if no history or never in deficit). */
    public long getPeakDeficitEUt() {
        if (history == null) {
            return 0L;
        }
        long d = history.getPeakDeficit();
        return d == Long.MIN_VALUE ? 0L : Math.max(0L, d);
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
