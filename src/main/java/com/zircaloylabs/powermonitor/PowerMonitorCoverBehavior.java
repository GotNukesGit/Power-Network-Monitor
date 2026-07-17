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
    // Stage 1: exponential moving average (~4 s time constant) applied at
    // the source to every MEASURED channel. GT delivers energy in discrete
    // voltage packets and averages over a 5-tick window; sampling that at
    // 1 Hz aliases (a constant 120 EU/t draw reads 114/122 alternately as
    // packet boundaries slide across the window). The EMA integrates the
    // packet noise back out; the deadband (stage 2) then pins the display.
    // Charts and peaks intentionally keep RAW samples -- instruments show
    // the smoothed truth, history shows what the meter actually read.
    private long emaConsumption = -1L;
    private long emaGeneration = -1L;
    private long emaBufferIn = -1L;
    private long emaBufferOut = -1L;
    private long emaTopDraw = -1L;
    private String emaTopName = "";

    private long shownConsumptionEUt = 0L;
    private long shownGenerationEUt = 0L;
    private long shownTopDrawEUt = 0L;
    private long shownLineLossEUt = 0L;

    /** Per-name EMA of draw, for a stable top-consumers list. Pruned each sample. */
    private final java.util.HashMap<String, Long> drawEmaByName = new java.util.HashMap<>();
    private volatile String[] topConsumers = new String[0]; // pre-formatted display lines

    // Chat alerting: escalations to ON_STORED/BROWNOUT and named power-loss
    // events get announced to nearby players (the dashboard only helps if
    // you're looking at it). Rate-limited; a recovery message re-arms it.
    private static final int ALERT_RADIUS = 64;
    private static final long ALERT_MIN_INTERVAL_TICKS = 30L * 20L;
    private int lastAlertedStatus = STATUS_HEALTHY;
    private long lastAlertTime = -ALERT_MIN_INTERVAL_TICKS;
    private long shownDemandEUt = 0L;
    private long shownUnmetEUt = 0L;
    private long shownStorageFlowEUt = 0L;
    private long shownBufferInEUt = 0L;
    private long shownBufferOutEUt = 0L;
    private String deadBufferWarning = "";
    // Saturation hysteresis: warning latches ON after SAT_ON_SAMPLES
    // consecutive positive samples and OFF after SAT_OFF_SAMPLES clear ones,
    // so a threshold-straddling network doesn't strobe the warning.
    // Tiered supply status (latched with hysteresis -- escalates after 2
    // consecutive samples at the higher level, de-escalates after 5 below):
    public static final int STATUS_HEALTHY = 0;
    public static final int STATUS_AT_CAPACITY = 1; // generation pinned, no headroom, but keeping up
    public static final int STATUS_ON_STORED = 2; // storage covering the gap; brownout when it empties
    public static final int STATUS_BROWNOUT = 3; // demand measurably exceeds total supply
    private int shownStatus = STATUS_HEALTHY;
    private int statusUpStreak = 0;
    private int statusDownStreak = 0;
    private long storedEtaSeconds = -1L;
    private static final int STATUS_UP_SAMPLES = 2;
    private static final int STATUS_DOWN_SAMPLES = 5;
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
        String machineName = ""; // set for controller power-loss events, "" for network-level shortfall
    }

    /**
     * Controllers whose power-loss shutdown we've already logged, so the
     * persistent wasShutdown() flag produces exactly one outage entry per
     * incident (cleared when the controller runs again). Weak keys: an
     * unloaded/broken controller shouldn't be pinned in memory by its
     * monitor.
     */
    private final java.util.Set<Object> loggedShutdowns = java.util.Collections
            .newSetFromMap(new java.util.WeakHashMap<>());

    private int multiCount = 0;
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
        NetworkDiscovery.MultiblockSummary multis = NetworkDiscovery.resolveMultiblocks(hostTile.getWorld(),
                discovery.members);
        long rawConsumption = snap.totalConsumptionEUt;
        long rawGeneration = snap.totalGenerationEUt;
        emaConsumption = ema(emaConsumption, rawConsumption);
        emaGeneration = ema(emaGeneration, rawGeneration);
        emaBufferIn = ema(emaBufferIn, snap.bufferInEUt);
        emaBufferOut = ema(emaBufferOut, snap.bufferOutEUt);
        liveGenerationEUt = emaGeneration;
        liveConsumptionEUt = emaConsumption;
        liveBufferedEU = snap.totalBufferedEU;
        liveBufferCapacityEU = snap.totalBufferCapacityEU;
        liveFuelReserveEU = snap.totalFuelReserveEU;
        liveMaxGenerationEUt = snap.maxGenerationEUt;
        liveBufferNetChargeEUt = snap.bufferNetChargeEUt;
        lastWorldTime = worldTime;
        liveDemandEUt = snap.totalDemandEUt + multis.demandEUt;
        multiCount = multis.controllers.size();
        // Unmet = demand minus delivered. Conservative when the network has
        // machines outside demand-metering coverage (their demand isn't
        // counted but their delivered draw is) -- so a positive number here
        // is a REAL shortfall, never a metering artifact.
        liveUnmetEUt = Math.max(0L, liveDemandEUt - snap.totalConsumptionEUt);
        liveStorageDischargeCapEUt = snap.storageDischargeCapacityEUt;
        demandMeteredCount = snap.demandMeteredCount;
        generatorCount = snap.generatorCount;
        machineCount = snap.machineCount;
        bufferCount = snap.bufferCount;
        // Top draw, aggregated per OWNER: a multiblock's hatches are ports of
        // one machine, so their draws sum under the controller's name (an
        // EBF on two 62 EU/t hatches is one 124 EU/t consumer, not two).
        computeTopConsumer(discovery.members, multis);

        // Brownout heuristic (see RollingSampleBuffer#record for why the
        // "deficit" series can't detect this): generation is DELIVERED
        // power, so when every generator is pinned at rated output and
        // storage is empty (or absent), any additional demand is invisible
        // to the meters -- machines just starve. Generators >= 95% of rated
        // + buffers <= 2% full is the strongest honest signal available.
        // ---- Loss-aware shortfall ----
        // Delivered is metered at the machines, AFTER cable loss; generation
        // at the sources, BEFORE it. On a healthy network demand can exceed
        // delivered by exactly the line loss (seen in the field: demand 120,
        // delivered 114, generation 132 -- 6 EU/t died in six lossy cables,
        // nothing was starving). So a shortfall only counts when demand
        // exceeds BOTH meters, and only above a 2% jitter/loss floor.
        long effectiveDelivered = Math.max(liveConsumptionEUt, liveGenerationEUt);
        long rawShortfall = Math.max(0L, liveDemandEUt - effectiveDelivered);
        long shortfallFloor = Math.max(2L, liveDemandEUt / 50L);
        liveUnmetEUt = rawShortfall > shortfallFloor ? rawShortfall : 0L;

        // ---- Tiered status ----
        long storageDrainEUt = Math.max(0L, emaBufferOut - emaBufferIn); // >0: storage covering load
        boolean genPinned = liveMaxGenerationEUt > 0 && liveGenerationEUt * 100 >= liveMaxGenerationEUt * 95;
        int rawStatus;
        if (liveUnmetEUt > 0) {
            rawStatus = STATUS_BROWNOUT;
        } else if (storageDrainEUt > shortfallFloor && liveBufferedEU > 0 && genPinned) {
            rawStatus = STATUS_ON_STORED;
        } else if (genPinned) {
            rawStatus = STATUS_AT_CAPACITY;
        } else {
            rawStatus = STATUS_HEALTHY;
        }
        if (rawStatus > shownStatus) {
            statusUpStreak++;
            statusDownStreak = 0;
            if (statusUpStreak >= STATUS_UP_SAMPLES) {
                shownStatus = rawStatus;
                statusUpStreak = 0;
            }
        } else if (rawStatus < shownStatus) {
            statusDownStreak++;
            statusUpStreak = 0;
            if (statusDownStreak >= STATUS_DOWN_SAMPLES) {
                shownStatus = rawStatus;
                statusDownStreak = 0;
            }
        } else {
            statusUpStreak = 0;
            statusDownStreak = 0;
        }
        // Countdown to storage exhaustion at the CURRENT drain rate (from
        // the buffers' own meters, not cons-minus-gen).
        storedEtaSeconds = storageDrainEUt > flowSignificanceFloor() && liveBufferedEU > 0
                ? liveBufferedEU / storageDrainEUt / TICKS_PER_SECOND
                : -1L;
        supplySaturated = shownStatus >= STATUS_ON_STORED;

        // Line loss estimate from energy balance: what generators emit minus
        // what machines receive minus what storage absorbed must have died
        // in the cables. Smoothed inputs, clamped, ~labelled -- packet
        // aliasing leaves a couple EU/t of residue in this number.
        long absorbed = emaBufferIn - emaBufferOut;
        long lossRaw = Math.max(0L, emaGeneration - emaConsumption - absorbed);
        shownLineLossEUt = applyDeadband(shownLineLossEUt, lossRaw);

        alertNearbyPlayers(hostTile, worldTime);

        // Display smoothing: 5% deadband (min 2 EU/t step) on the noisiest
        // readouts; zero always snaps immediately so an idle network reads 0.
        shownDemandEUt = applyDeadband(shownDemandEUt, liveDemandEUt);
        shownUnmetEUt = applyDeadband(shownUnmetEUt, liveUnmetEUt);
        shownStorageFlowEUt = applyDeadband(shownStorageFlowEUt, emaBufferIn - emaBufferOut);
        shownBufferInEUt = applyDeadband(shownBufferInEUt, emaBufferIn);
        shownBufferOutEUt = applyDeadband(shownBufferOutEUt, emaBufferOut);
        shownConsumptionEUt = applyDeadband(shownConsumptionEUt, emaConsumption);
        shownGenerationEUt = applyDeadband(shownGenerationEUt, emaGeneration);
        // Top draw: EMA keyed on the name -- resets instantly when the top
        // consumer CHANGES, smooths while it's the same machine.
        if (topConsumerName.equals(emaTopName)) {
            emaTopDraw = ema(emaTopDraw, topConsumerEUt);
        } else {
            emaTopName = topConsumerName;
            emaTopDraw = topConsumerEUt;
            shownTopDrawEUt = topConsumerEUt;
        }
        shownTopDrawEUt = applyDeadband(shownTopDrawEUt, emaTopDraw);

        if (snap.deadBufferNames.isEmpty()) {
            deadBufferWarning = "";
        } else {
            String first = snap.deadBufferNames.get(0);
            int more = snap.deadBufferNames.size() - 1;
            deadBufferWarning = first + ": 0 batteries -- output side dead" + (more > 0 ? " (+" + more + " more)" : "");
        }

        trackOutage(worldTime);
        trackControllerPowerLoss(multis, worldTime);
        if (lastPowerLossAlert != null) {
            broadcastNearby(hostTile, "\u00a76[Power Monitor] \u00a7c\u26a0 " + lastPowerLossAlert
                    + " shut down: power loss.");
            lastPowerLossAlert = null;
        }

        // Fuel is PER GENERATOR, not a shared pool: when the generator with
        // the least fuel runs dry, network capacity steps down to whatever
        // the remaining generators can supply. These schedules project that
        // staircase, once at rated output and once at each generator's
        // current output.
        fuelScheduleFullBurn = buildFuelSchedule(snap.generatorFuelProfile, true);
        fuelScheduleCurrent = buildFuelSchedule(snap.generatorFuelProfile, false);

        if (history != null) {
            history.record(rawConsumption, rawGeneration, liveDemandEUt, liveBufferedEU,
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

    /**
     * Draw grouped by NAME: a multiblock's hatches sum under its controller's
     * name, and same-named singleblocks sum together too ("3 Macerators
     * pulling 96 total" is the answer a player triaging load actually wants).
     * Per-name EMA keeps the ranking stable against packet aliasing.
     */
    private void computeTopConsumer(java.util.List<gregtech.api.interfaces.tileentity.IBasicEnergyContainer> members,
            NetworkDiscovery.MultiblockSummary multis) {
        java.util.Map<String, Long> draw = new java.util.HashMap<>();
        for (gregtech.api.interfaces.tileentity.IBasicEnergyContainer member : members) {
            if (NetworkDiscovery.isBatteryBuffer(member)) {
                continue;
            }
            long avgIn = member.getAverageElectricInput();
            if (avgIn <= 0) {
                continue;
            }
            String owner = multis.hatchOwnerName.get(member);
            draw.merge(owner != null ? owner : NetworkDiscovery.localNameOf(member), avgIn, Long::sum);
        }
        // EMA per name; prune names that vanished from the network.
        drawEmaByName.keySet().retainAll(draw.keySet());
        for (java.util.Map.Entry<String, Long> e : draw.entrySet()) {
            drawEmaByName.merge(e.getKey(), e.getValue(), (prev, raw) -> ema(prev, raw));
        }
        java.util.List<java.util.Map.Entry<String, Long>> ranked = new java.util.ArrayList<>(
                drawEmaByName.entrySet());
        ranked.sort((a, b2) -> Long.compare(b2.getValue(), a.getValue()));
        String[] lines = new String[Math.min(3, ranked.size())];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = ranked.get(i).getKey() + "\u00a77 (\u00a7f"
                    + com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil
                            .formatNumber(ranked.get(i).getValue())
                    + "\u00a77 EU/t)";
        }
        topConsumers = lines;
        if (!ranked.isEmpty()) {
            topConsumerEUt = ranked.get(0).getValue();
            topConsumerName = ranked.get(0).getKey();
        } else {
            topConsumerEUt = 0L;
            topConsumerName = "";
        }
    }

    /** Pre-formatted top-consumer line for the given rank, or "". */
    public String getTopConsumerLine(int index) {
        String[] lines = topConsumers;
        return index < lines.length ? lines[index] : "";
    }

    /** Estimated cable loss (EU/t): generation minus delivered minus storage absorption. */
    public long getLineLossEUt() {
        return shownLineLossEUt;
    }

    private void alertNearbyPlayers(IGregTechTileEntity hostTile, long worldTime) {
        String message = null;
        if (shownStatus > lastAlertedStatus && shownStatus >= STATUS_ON_STORED
                && worldTime - lastAlertTime >= ALERT_MIN_INTERVAL_TICKS) {
            if (shownStatus >= STATUS_BROWNOUT) {
                message = "\u00a76[Power Monitor] \u00a7c\u26a0 BROWNOUT -- demand exceeds supply!";
            } else {
                long eta = storedEtaSeconds;
                message = "\u00a76[Power Monitor] \u00a76\u26a0 Running on stored EU -- brownout in "
                        + (eta >= 0 ? "~" + PowerMonitorCover.formatSeconds(eta) : "soon") + ".";
            }
            lastAlertedStatus = shownStatus;
            lastAlertTime = worldTime;
        } else if (shownStatus <= STATUS_AT_CAPACITY && lastAlertedStatus >= STATUS_ON_STORED) {
            message = "\u00a76[Power Monitor] \u00a72\u2714 Supply recovered.";
            lastAlertedStatus = shownStatus;
            lastAlertTime = worldTime;
        }
        if (message != null) {
            broadcastNearby(hostTile, message);
        }
    }

    @SuppressWarnings("unchecked")
    private void broadcastNearby(IGregTechTileEntity hostTile, String message) {
        net.minecraft.world.World world = hostTile.getWorld();
        if (world == null) {
            return;
        }
        double x = hostTile.getXCoord() + 0.5;
        double y = hostTile.getYCoord() + 0.5;
        double z = hostTile.getZCoord() + 0.5;
        for (Object p : world.playerEntities) {
            net.minecraft.entity.player.EntityPlayer player = (net.minecraft.entity.player.EntityPlayer) p;
            if (player.getDistanceSq(x, y, z) <= (double) ALERT_RADIUS * ALERT_RADIUS) {
                player.addChatMessage(new net.minecraft.util.ChatComponentText(message));
            }
        }
    }

    /** 0=healthy, 1=at capacity, 2=running on stored EU, 3=brownout. Latched with hysteresis. */
    public int getSupplyStatus() {
        return shownStatus;
    }

    /** Seconds until storage exhausts at current drain (from buffer meters), or -1. */
    public long getStoredEtaSeconds() {
        return storedEtaSeconds;
    }

    /** EMA, alpha = 1/4 (~4 s time constant at 1 Hz). -1 = uninitialized; zero snaps immediately. */
    private static long ema(long prev, long raw) {
        if (prev < 0L || raw == 0L) {
            return raw;
        }
        return prev + Math.round((raw - prev) / 4.0);
    }

    private static long applyDeadband(long displayed, long actual) {
        if (actual == 0L) {
            return 0L;
        }
        long delta = Math.abs(actual - displayed);
        long threshold = Math.max(5L, Math.max(Math.abs(displayed), Math.abs(actual)) / 10L); // 10%
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

    /**
     * Multiblocks that die from power loss ZERO their draw the instant they
     * stop (stopMachine(POWER_LOSS) -- verified against GT source), so the
     * unmet-demand tracker can never see them. The controller's shutdown
     * reason is the reliable signal, and it names the machine. wasShutdown()
     * stays true until the machine restarts, so entries are edge-detected
     * via loggedShutdowns.
     */
    private String lastPowerLossAlert = null;

    private void trackControllerPowerLoss(NetworkDiscovery.MultiblockSummary multis, long worldTime) {
        for (NetworkDiscovery.ControllerState c : multis.controllers) {
            if (c.powerLossShutdown) {
                if (loggedShutdowns.add(c.tileIdentity)) {
                    lastPowerLossAlert = c.name; // picked up by onTick's host reference below
                    Outage o = new Outage();
                    o.startTime = worldTime;
                    o.endTime = worldTime; // instantaneous event: the machine is already dead
                    o.machineName = c.name;
                    o.demandAtPeak = liveDemandEUt;
                    o.deliveredAtPeak = liveConsumptionEUt;
                    outageLog.addFirst(o);
                    while (outageLog.size() > OUTAGE_LOG_SIZE) {
                        outageLog.removeLast();
                    }
                }
            } else {
                loggedShutdowns.remove(c.tileIdentity); // running again -- re-arm for the next incident
            }
        }
    }

    public int getMultiblockCount() {
        return multiCount;
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
        return shownGenerationEUt;
    }

    public long getLiveConsumptionEUt() {
        return shownConsumptionEUt;
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

    /** Gross EU/t entering network storage (deadband-smoothed). */
    public long getBufferInEUt() {
        return shownBufferInEUt;
    }

    /** Gross EU/t leaving network storage (deadband-smoothed). */
    public long getBufferOutEUt() {
        return shownBufferOutEUt;
    }

    /** Named warning for buffers with zero batteries (dead output side), or "" if none. */
    public String getDeadBufferWarning() {
        return deadBufferWarning;
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
        long netFlow = emaBufferIn - emaBufferOut; // the buffers' own charge rate
        if (netFlow >= -flowSignificanceFloor() || liveBufferedEU <= 0) {
            return -1;
        }
        return liveBufferedEU / -netFlow / TICKS_PER_SECOND;
    }

    /**
     * Minimum |net flow| for a time estimate to mean anything: packet noise
     * wobbles the buffer meters by an EU or two, and inside GT's own
     * charge/decharge dead zone (batteries only charge above ~2/3 internal
     * fill, discharge below ~1/3 -- verified against buffer source) tiny
     * flows are the buffer idling by design. Extrapolating "+/-1 EU/t" into
     * an hours-long forecast is noise dressed up as prophecy.
     */
    private long flowSignificanceFloor() {
        return Math.max(4L, (emaBufferIn + emaBufferOut) / 50L); // 2% of gross traffic, min 4 EU/t
    }

    /** Seconds until buffer fills at current net surplus, or -1 if not charging / already full. */
    public long getSecondsToFull() {
        long netFlow = emaBufferIn - emaBufferOut; // the buffers' own charge rate
        long remaining = liveBufferCapacityEU - liveBufferedEU;
        if (netFlow <= flowSignificanceFloor() || remaining <= 0) {
            return -1;
        }
        return remaining / netFlow / TICKS_PER_SECOND;
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
        return shownTopDrawEUt;
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
