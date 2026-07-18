package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicBatteryBuffer;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTECable;
import gregtech.api.metatileentity.implementations.MTEExtendedPowerMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTETransformer;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import net.minecraft.world.World;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers every machine/generator/buffer electrically connected to a given
 * starting cable segment, by walking GT's OWN connectivity logic
 * (MTECable#getConnectableMTE) for cable-to-cable links, plus pass-through
 * traversal of "relay" machines that join two cable networks into one
 * logical power system:
 *
 *   - Transformers (MTETransformer, which MTEWetTransformer extends) --
 *     pure voltage conversion, never a member themselves.
 *   - Battery buffers (MTEBasicBatteryBuffer) -- these ARE members (their
 *     stored charge is the network's buffer) AND relays: a generator ->
 *     cable -> buffer -> cable -> machine layout is one system, and a
 *     monitor on either segment should see all of it. Verified against GT
 *     source (5.09.54.20): the buffer takes input on every side except its
 *     front and outputs on the front, so walking all six faces covers both
 *     of its cable networks.
 *
 * The BFS is unified: one visited set, one queue holding both cables and
 * relays. When a relay is dequeued its six faces are walked directly (no
 * cable-side connectability check applies -- relay faces are machine
 * faces, not cables), which also makes relay-touching-relay chains work
 * (e.g. transformer flush against a battery buffer).
 *
 * This is a fresh BFS every call, capped at tier.maxTrackedNodes members
 * to bound the walk; a monitor that hits the cap reports truncation so the
 * player is told to upgrade rather than silently shown partial numbers.
 *
 * NOTE for a future optimization pass: gregtech.api.graphs.PowerNode
 * (BaseMetaPipeEntity#getNode(), public) is a persistent cached graph GT
 * itself builds for amperage routing, with public mNeighbourNodes/mConsumers
 * fields. It's likely a faster data source than re-deriving connectivity via
 * BFS every poll -- but its exact semantics (whether a given PowerNode's
 * mConsumers represents the FULL network or a directed subset resolved from
 * one specific generator's routing pass) aren't confirmed yet. Don't switch
 * to it without verifying that against GT's pathing code -- an incorrect
 * read there would silently under/over-count the network, which is worse
 * than the current BFS being merely unoptimized.
 */
public final class NetworkDiscovery {

    private NetworkDiscovery() {}

    public static final class Result {
        public final List<IBasicEnergyContainer> members = new ArrayList<>();
        public boolean truncated = false; // hit maxTrackedNodes before finishing
        public int cablesVisited = 0; // diagnostic: how much of the network the BFS actually walked
    }

    /**
     * @param anchorTile the IGregTechTileEntity the Power Monitor cover is attached to.
     *                   Must be a cable segment (MTECable) for this to do anything --
     *                   if the cover is somehow on a non-cable, return an empty result.
     */
    public static Result discover(IGregTechTileEntity anchorTile, int maxTrackedNodes) {
        Result result = new Result();

        IMetaTileEntity anchorMte = anchorTile.getMetaTileEntity();
        if (!(anchorMte instanceof MTECable)) {
            return result; // not attached to a cable, nothing to walk
        }

        Set<IGregTechTileEntity> visited = new HashSet<>(); // cables AND relays
        Set<IBasicEnergyContainer> foundMembers = new HashSet<>();
        ArrayDeque<IGregTechTileEntity> queue = new ArrayDeque<>();

        visited.add(anchorTile);
        queue.add(anchorTile);

        boolean truncated = false;

        while (!queue.isEmpty() && !truncated) {
            IGregTechTileEntity current = queue.poll();
            IMetaTileEntity currentMte = current.getMetaTileEntity();

            if (currentMte instanceof MTECable) {
                result.cablesVisited++;
                truncated = walkCableSides(current, (MTECable) currentMte, visited, foundMembers, queue,
                        maxTrackedNodes);
            } else {
                // Relay node (transformer or battery buffer): walk all faces.
                truncated = walkRelaySides(current, visited, foundMembers, queue, maxTrackedNodes);
            }
        }

        result.truncated = truncated;
        result.members.addAll(foundMembers);
        return result;
    }

    private static boolean walkCableSides(
            IGregTechTileEntity current,
            MTECable currentCable,
            Set<IGregTechTileEntity> visited,
            Set<IBasicEnergyContainer> foundMembers,
            ArrayDeque<IGregTechTileEntity> queue,
            int maxTrackedNodes) {

        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighborTe = current.getTileEntityAtSide(side);
            if (!(neighborTe instanceof IGregTechTileEntity)) {
                continue;
            }
            IGregTechTileEntity neighborGt = (IGregTechTileEntity) neighborTe;
            IMetaTileEntity neighborMte = neighborGt.getMetaTileEntity();

            if (neighborMte instanceof MTECable) {
                // Walk by GT's LIVE connection state (mConnections bitmask),
                // not getConnectableMTE: that helper governs whether a NEW
                // join may FORM (same material + insulation + color), but GT
                // routes power by the formed-connection state, which happily
                // spans mixed materials and respects wire-cutter snips and
                // soldered joins. Field-verified: a lossless-to-Tin junction
                // conducted in game while the forming-rule check stopped the
                // walk -- hiding half a base from the monitor.
                MTECable neighborCable = (MTECable) neighborMte;
                if (!currentCable.isConnectedAtSide(side) || !neighborCable.isConnectedAtSide(side.getOpposite())) {
                    continue;
                }
                if (visited.add(neighborGt)) {
                    queue.add(neighborGt);
                }
                continue;
            }

            if (isRelay(neighborMte)) {
                // Battery buffers are members as well as relays.
                if (neighborMte instanceof MTEBasicBatteryBuffer && neighborTe instanceof IBasicEnergyContainer) {
                    if (addMember(foundMembers, (IBasicEnergyContainer) neighborTe, maxTrackedNodes)) {
                        return true;
                    }
                }
                if (visited.add(neighborGt)) {
                    queue.add(neighborGt);
                }
                continue;
            }

            // Terminal machine: facing check only. Deliberately NOT using
            // getConnectableMTE here -- it requires the cable's rated voltage
            // to EXACTLY equal the machine's (metaTile.maxEUInput() ==
            // this.mVoltage, strict ==, confirmed against GT source), which
            // would hide any machine whose tier doesn't exactly match the
            // cable tier (extremely common, e.g. an MV cable run feeding an
            // LV machine for lower loss).
            if (neighborMte instanceof MetaTileEntity && neighborTe instanceof IBasicEnergyContainer) {
                MetaTileEntity mte = (MetaTileEntity) neighborMte;
                boolean facesUs = (mte.isEnetInput() && mte.isInputFacing(side.getOpposite()))
                        || (mte.isEnetOutput() && mte.isOutputFacing(side.getOpposite()));
                if (facesUs && addMember(foundMembers, (IBasicEnergyContainer) neighborTe, maxTrackedNodes)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A relay isn't walked with cable connectability rules -- its faces are
     * machine faces. Cables beyond it join the BFS; machines directly
     * touching it are recorded as members (lenient, no facing check, same
     * as the transformer handling that's been verified in-game); further
     * relays chain.
     */
    private static boolean walkRelaySides(
            IGregTechTileEntity relayTile,
            Set<IGregTechTileEntity> visited,
            Set<IBasicEnergyContainer> foundMembers,
            ArrayDeque<IGregTechTileEntity> queue,
            int maxTrackedNodes) {

        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity beyondTe = relayTile.getTileEntityAtSide(side);
            if (!(beyondTe instanceof IGregTechTileEntity)) {
                continue;
            }
            IGregTechTileEntity beyondGt = (IGregTechTileEntity) beyondTe;
            IMetaTileEntity beyondMte = beyondGt.getMetaTileEntity();

            if (beyondMte instanceof MTECable) {
                if (visited.add(beyondGt)) {
                    queue.add(beyondGt);
                }
            } else if (isRelay(beyondMte)) {
                // Same as the cable-walk site: relays are members (classified
                // and metering-excluded in summarize) AND traversal nodes.
                if (beyondTe instanceof IBasicEnergyContainer) {
                    if (addMember(foundMembers, (IBasicEnergyContainer) beyondTe, maxTrackedNodes)) {
                        return true;
                    }
                }
                if (visited.add(beyondGt)) {
                    queue.add(beyondGt);
                }
            } else if (beyondTe instanceof IBasicEnergyContainer) {
                // Machine directly touching the relay's face (no cable in
                // between). Membership requires an ACTUAL energy connection
                // across the touching faces -- one side must output where
                // the other inputs, mirroring GT's own transfer condition.
                // Without this, any machine merely PLACED beside a buffer or
                // transformer got swept into the network (field-observed:
                // census counting adjacent-but-unrelated tiles, whose frozen
                // recipes then produced phantom demand).
                IBasicEnergyContainer beyond = (IBasicEnergyContainer) beyondTe;
                IBasicEnergyContainer relay = (IBasicEnergyContainer) relayTile;
                ForgeDirection opposite = side.getOpposite();
                boolean electrical = (relay.outputsEnergyTo(side) && beyond.inputEnergyFrom(opposite))
                        || (beyond.outputsEnergyTo(opposite) && relay.inputEnergyFrom(side));
                if (!electrical) {
                    continue;
                }
                if (addMember(foundMembers, beyond, maxTrackedNodes)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return true if the member cap was hit (caller should stop and report truncation). */
    private static boolean addMember(Set<IBasicEnergyContainer> members, IBasicEnergyContainer member, int cap) {
        members.add(member);
        return members.size() >= cap;
    }

    /**
     * Pass-through node types. MTEWetTransformer extends MTETransformer, so
     * one instanceof covers both (verified against GT 5.09.54.20 source).
     */
    private static boolean isRelay(IMetaTileEntity mte) {
        return mte instanceof MTETransformer || mte instanceof MTEBasicBatteryBuffer;
    }

    /**
     * Splits discovered members into generators vs consumers using GT's own
     * isEnetOutput()/isEnetInput() flags, and sums their current averaged
     * EU/t (GT already maintains this 5-tick rolling average per machine --
     * see IBasicEnergyContainer#getAverageElectricInput/Output).
     */
    public static final class Snapshot {
        public long totalGenerationEUt = 0L;
        public long totalConsumptionEUt = 0L;
        public long totalBufferedEU = 0L;
        public long totalBufferCapacityEU = 0L;

        /**
         * EU-equivalent of fuel currently sitting in single-block generators'
         * tanks and input slots on this network. See fuel notes in
         * summarize() for exactly what is and isn't counted.
         */
        public long totalFuelReserveEU = 0L;

        /** Sum of generators' rated output (maxEUOutput), i.e. generation capacity. */
        public long maxGenerationEUt = 0L;

        /**
         * Internal pass-through buffer only (tile getStoredEU/getEUCapacity
         * -- excludes battery items). Batteries = totalBuffered - internal.
         * The split matters: GT's 1/3-2/3 hysteresis band lets the internal
         * level wander during pass-through while batteries sit untouched,
         * so a headline that sums them "drains" while every battery reads
         * 100% (field-observed).
         */
        public long internalBufferEU = 0L;
        public long internalBufferCapacityEU = 0L;

        /** Emission toll paid by fuel-less relays (buffers + transformers) -- real network loss. */
        public long relayOutputTollEUt = 0L;

        /** Net storage flow: sum over buffers of (avgIn - avgOut). >0 charging, <0 discharging. */
        public long bufferNetChargeEUt = 0L;

        /**
         * GROSS storage flow, both directions. Net alone hides a series
         * buffer doing all the work (100 in / 100 out reads "idle"); gross
         * makes topology visible: matched in/out = pass-through, one-sided
         * = parallel reserve.
         */
        public long bufferInEUt = 0L;
        public long bufferOutEUt = 0L;

        /**
         * Battery buffers with ZERO batteries installed: their output side
         * is dead (maxAmperesOut() == mBatteryCount == 0, verified against
         * GT source), which silently kills any series-connected network --
         * the classic forgotten-batteries trap.
         */
        public final List<String> deadBufferNames = new ArrayList<>();

        /**
         * True recipe demand: sum of mEUt over singleblock machines with an
         * active recipe (mMaxProgresstime > 0). Verified against
         * MTEBasicMachine's tick loop (5.09.54.20): a machine that FAILS to
         * drain energy keeps mEUt/mMaxProgresstime set (they only clear on
         * recipe completion), so a starving machine's demand stays readable
         * -- this is the number "delivered" metering fundamentally cannot
         * see. Coverage: singleblock machines only (multiblocks use a
         * different field layout; counted in machineCount but not here --
         * see demandMeteredCount for how complete the metering is).
         */
        public long totalDemandEUt = 0L;
        public int demandMeteredCount = 0;

        /** Human-readable demand attribution ("name @ x,y,z : N EU/t"), incl. excluded-disabled entries. */
        public final List<String> demandLines = new ArrayList<>();

        /**
         * Max EU/t storage could push if fully charged: sum over battery
         * buffers of maxEUOutput() * maxAmperesOut() (= tier voltage *
         * battery count, verified against MTEBasicBatteryBuffer source).
         */
        public long storageDischargeCapacityEUt = 0L;

        // Network census.
        public int generatorCount = 0; // single-block generators (MTEBasicGenerator)
        public int machineCount = 0; // non-buffer, non-generator members
        public int bufferCount = 0;
        public int transformerCount = 0;

        /** Highest average draw among non-buffer members, for "top consumer" display. */
        public long topConsumerEUt = 0L;
        public String topConsumerName = "";
        public IBasicEnergyContainer topConsumerRef = null;

        /**
         * Per-generator fuel profile for staged runtime projection (fuel is
         * per-machine, NOT a shared pool -- when one generator runs dry,
         * network capacity steps down to the remaining generators). Carries
         * the machine identity so the behavior layer can EMA each
         * generator's output individually: raw per-machine averages alias
         * badly (packet boundaries make a steady producer read 77/102
         * alternately), and the schedule needs per-machine rates, so
         * smoothing must be per-machine too.
         */
        public static final class GeneratorProfile {
            public final IBasicEnergyContainer source;
            public final long rawOutEUt;
            public final long ratedEUt;
            public final long fuelEU;

            GeneratorProfile(IBasicEnergyContainer source, long rawOutEUt, long ratedEUt, long fuelEU) {
                this.source = source;
                this.rawOutEUt = rawOutEUt;
                this.ratedEUt = ratedEUt;
                this.fuelEU = fuelEU;
            }
        }

        public final List<GeneratorProfile> generatorFuelProfile = new ArrayList<>();
    }

    public static Snapshot summarize(List<IBasicEnergyContainer> members) {
        Snapshot snap = new Snapshot();
        for (IBasicEnergyContainer container : members) {
            // Battery buffers: counted only toward buffer charge/capacity, excluded
            // from gen/consumption totals (avoids double-counting a buffer as both
            // a "consumer" while charging and "generator" while discharging within
            // the same snapshot).
            if (isBatteryBuffer(container)) {
                snap.bufferCount++;
                accumulateBuffer(snap, container);
                continue;
            }

            // Transformers are RELAYS, not machines: their input meter reads
            // the whole downstream network's power arriving, their output
            // meter reads it all leaving. Metering them as members counts
            // every EU twice -- generation and delivered both read ~2x real
            // and the transformer wins Top draw. Conversion itself is 1:1
            // in GT (loss lives in cables), so excluding them entirely
            // keeps the energy balance exact.
            if (isTransformer(container)) {
                snap.transformerCount++;
                snap.relayOutputTollEUt += outputToll(container, container.getAverageElectricOutput());
                continue;
            }

            long avgIn = container.getAverageElectricInput();
            long avgOut = networkSideOutput(container, container.getAverageElectricOutput());
            if (avgOut > 0) {
                snap.totalGenerationEUt += avgOut;
            }
            if (avgIn > 0) {
                snap.totalConsumptionEUt += avgIn;
                if (avgIn > snap.topConsumerEUt) {
                    snap.topConsumerEUt = avgIn;
                    snap.topConsumerName = localNameOf(container);
                    snap.topConsumerRef = container;
                }
            }

            if (!accumulateGeneratorTelemetry(snap, container)) {
                snap.machineCount++;
                accumulateDemand(snap, container);
            }
        }
        return snap;
    }

    private static void accumulateBuffer(Snapshot snap, IBasicEnergyContainer container) {
        IMetaTileEntity mte = (container instanceof IGregTechTileEntity)
                ? ((IGregTechTileEntity) container).getMetaTileEntity()
                : null;

        if (mte instanceof MTEBasicBatteryBuffer) {
            MTEBasicBatteryBuffer bb = (MTEBasicBatteryBuffer) mte;
            snap.storageDischargeCapacityEUt += bb.maxEUOutput() * bb.maxAmperesOut();
            // getStoredEnergy() (public, verified against GT 5.09.54.20 source)
            // returns {stored, capacity} INCLUDING the charge held in the
            // battery items in its slots. The tile-level getStoredEU()/
            // getEUCapacity() only cover the machine's small internal buffer
            // (V*64 per slot) and ignore the batteries entirely -- using them
            // here would massively under-report the network's real storage.
            snap.internalBufferEU += container.getStoredEU();
            snap.internalBufferCapacityEU += container.getEUCapacity();
            snap.relayOutputTollEUt += outputToll(container, container.getAverageElectricOutput());
            long[] storedAndCap = ((MTEBasicBatteryBuffer) mte).getStoredEnergy();
            snap.totalBufferedEU += storedAndCap[0];
            snap.totalBufferCapacityEU += storedAndCap[1];
        } else {
            // Other storage (e.g. LSC found by direct cable contact): tile-level
            // getters are the best generic read available.
            snap.totalBufferedEU += container.getStoredEU();
            snap.totalBufferCapacityEU += container.getEUCapacity();
        }

        long in = container.getAverageElectricInput();
        long out = container.getAverageElectricOutput();
        snap.bufferNetChargeEUt += in - out;
        snap.bufferInEUt += in;
        snap.bufferOutEUt += out;

        if (mte instanceof MTEBasicBatteryBuffer && ((MTEBasicBatteryBuffer) mte).mBatteryCount == 0) {
            snap.deadBufferNames.add(localNameOf(container));
        }
    }

    /**
     * Fuel accounting for single-block generators (anything extending
     * MTEBasicGenerator: diesel, gas turbine, steam turbine, naquadah, etc.).
     * All semantics verified against MTEBasicGenerator source (5.09.54.20):
     *
     *   Fluid: getFuelValue(fluid, true) returns EU produced per operation,
     *   and one operation consumes consumedFluidPerOperation(fluid) mB
     *   (default 1). GT's own tick burns (amount / consumed) operations, so
     *   reserve EU = (amount / consumedPerOp) * EUperOp -- floor division
     *   matches what GT will actually burn.
     *
     *   Items: getFuelValue(stack, true) returns EU per single item
     *   (increaseStoredEnergyUnits(tFuelValue) per decrStackSize(slot, 1)),
     *   so reserve EU = fuelValue * stackSize for the input slot's stack.
     *
     * NOT counted (documented limitation, not an oversight): multiblock
     * generators (large turbines/combustion engines -- different class
     * hierarchy, MTEMultiBlockBase, with hatch-based fuel), fuel sitting in
     * AE/chests/hoppers that hasn't reached a generator yet, and solar-type
     * generators (no fuel concept).
     */
    /** @return true if this member is a single-block generator (and was accounted as one). */
    private static boolean accumulateGeneratorTelemetry(Snapshot snap, IBasicEnergyContainer container) {
        if (!(container instanceof IGregTechTileEntity)) {
            return false;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) container).getMetaTileEntity();
        if (!(mte instanceof MTEBasicGenerator)) {
            return false;
        }
        MTEBasicGenerator gen = (MTEBasicGenerator) mte;

        snap.generatorCount++;
        long rated = gen.maxEUOutput();
        snap.maxGenerationEUt += rated;

        long fuelEU = 0L;
        FluidStack tankFluid = gen.mFluid;
        if (tankFluid != null && tankFluid.amount > 0) {
            long euPerOp = gen.getFuelValue(tankFluid, true);
            if (euPerOp > 0) {
                long perOpMb = Math.max(1, gen.consumedFluidPerOperation(tankFluid));
                fuelEU += (tankFluid.amount / perOpMb) * euPerOp;
            }
        }
        ItemStack inputStack = gen.mInventory[gen.getInputSlot()];
        if (inputStack != null && inputStack.stackSize > 0) {
            long euPerItem = gen.getFuelValue(inputStack, true);
            if (euPerItem > 0) {
                fuelEU += euPerItem * inputStack.stackSize;
            }
        }
        snap.totalFuelReserveEU += fuelEU;
        snap.generatorFuelProfile
                .add(new Snapshot.GeneratorProfile(container,
                        networkSideOutput(container, container.getAverageElectricOutput()), rated, fuelEU));
        return true;
    }

    /**
     * True-demand metering for singleblock machines. mEUt is the actual
     * post-overclock per-tick draw (set via calculator.getConsumption() when
     * a recipe starts) and stays set while the machine stutters from energy
     * starvation. The Integer.MAX_VALUE - 1 sentinel is GT's "forever
     * recipe" marker and would wreck totals, so it's excluded.
     */
    private static void accumulateDemand(Snapshot snap, IBasicEnergyContainer container) {
        if (!(container instanceof IGregTechTileEntity)) {
            return;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) container).getMetaTileEntity();
        if (!(mte instanceof MTEBasicMachine)) {
            return;
        }
        MTEBasicMachine machine = (MTEBasicMachine) mte;
        if (machine.mMaxProgresstime > 0 && machine.mEUt > 0 && machine.mEUt < Integer.MAX_VALUE - 1) {
            // Player-intent gate: the GUI power switch / soft mallet sets
            // isAllowedToWork=false. A DISABLED machine with a recipe either
            // finishes its current op (healthy) or hard-freezes (starved:
            // stutter sets mProgresstime=-100, and the tick gate skips
            // processing entirely -- verified GT 5.09.54.20 lines 575/601).
            // Frozen-disabled machines accept one internal-buffer fill then
            // refuse input, so their true network draw is ~0: counting them
            // as demand manufactures phantom brownouts.
            boolean allowed = !(container instanceof IMachineProgress)
                    || ((IMachineProgress) container).isAllowedToWork();
            String where = coordsOf(container);
            if (allowed) {
                snap.totalDemandEUt += machine.mEUt;
                snap.demandMeteredCount++;
                snap.demandLines.add(mte.getLocalName() + " @ " + where + " : " + machine.mEUt + " EU/t");
            } else {
                snap.demandLines
                        .add(mte.getLocalName() + " @ " + where + " : " + machine.mEUt + " EU/t (disabled -- excluded)");
            }
        }
    }


    /**
     * OUTPUT LOSS (GT: BaseMetaTileEntity.handleEUOutput, comment "voltage +
     * output loss"): every emitter decrements V + 2^max(0,tier-1) per amp
     * while putting V on the wire. The average-output meter is credited with
     * the DECREMENT, so raw averages overstate what the network receives by
     * the toll: an LV generator at 4A reads 132 while the grid gets 128.
     * These helpers convert meter-basis to network-basis. Generators refill
     * the toll from fuel (burn loop fills toward maxEUStore uncapped), so
     * their toll is invisible to the grid; RELAYS (buffers, transformers)
     * have no fuel and bill their toll to transiting/stored energy -- that
     * toll is real network loss and is accumulated separately.
     */
    public static long networkSideOutput(IBasicEnergyContainer container, long rawAvgOut) {
        long v = container.getOutputVoltage();
        if (v <= 0 || rawAvgOut <= 0) {
            return Math.max(0L, rawAvgOut);
        }
        long toll = 1L << Math.max(0, gregtech.api.util.GTUtility.getTier(v) - 1);
        return rawAvgOut * v / (v + toll);
    }

    public static long outputToll(IBasicEnergyContainer container, long rawAvgOut) {
        return Math.max(0L, rawAvgOut - networkSideOutput(container, rawAvgOut));
    }

    public static String coordsOf(IBasicEnergyContainer container) {
        return container.getXCoord() + "," + container.getYCoord() + "," + container.getZCoord();
    }

    // ==================== Multiblock resolution ====================

    /** Per-controller state for one resolution pass. */
    public static final class ControllerState {
        public final Object tileIdentity; // controller's base tile, for edge-detecting shutdown events
        public final String name;
        public final long demandEUt; // actual usage while running, 0 otherwise
        public final boolean powerLossShutdown;

        ControllerState(Object tileIdentity, String name, long demandEUt, boolean powerLossShutdown) {
            this.tileIdentity = tileIdentity;
            this.name = name;
            this.demandEUt = demandEUt;
            this.powerLossShutdown = powerLossShutdown;
        }
    }

    public static final class MultiblockSummary {
        public long demandEUt = 0L;
        public final List<String> demandLines = new ArrayList<>();
        public final List<ControllerState> controllers = new ArrayList<>();
        /** discovered hatch member -> owning controller name, for display attribution. */
        public final java.util.Map<IBasicEnergyContainer, String> hatchOwnerName = new java.util.IdentityHashMap<>();
    }

    /**
     * Resolves multiblock controllers that own the energy hatches our BFS
     * discovered, and reads their true recipe demand.
     *
     * WHY THE WORLD SCAN: GT hatches hold no reverse pointer to their
     * controller (verified: MTEHatch's only controller list is the private
     * ISmartInputHatch watcher mechanism, input hatches only). Controllers
     * DO hold a public forward list (MTEMultiBlockBase.mEnergyHatches), so
     * we snapshot the world's loaded tile entities -- we're on the server
     * thread inside doCoverThings, so toArray() races nothing -- and match
     * controllers whose hatch list contains one of ours. Identity matching,
     * ~one instanceof per loaded TE, throttled by the 1 Hz sample gate.
     *
     * DEMAND FORMULAS (replicated verbatim from GT source because
     * getActualEnergyUsage() is protected; sign convention: multiblock
     * mEUt/lEUt is NEGATIVE while consuming, opposite of singleblocks):
     *   base:     (-mEUt * 10_000) / max(1000, mEfficiency)
     *   extended: -lEUt * (10000.0 / max(1000, mEfficiency))
     *
     * OUTAGE SIGNAL: a multiblock that loses power calls
     * stopMachine(ShutDownReasonRegistry.POWER_LOSS), which ZEROES its
     * draw -- so unmet-demand math can never see a multi die. The shutdown
     * reason (id "power_loss") on the controller's base tile is the
     * reliable signal, and it comes with the machine's name for free.
     *
     * KNOWN LIMITATION: multis fed exclusively through exotic/multi-amp
     * (TecTech) hatches match via mExoticEnergyHatches, which is protected
     * -- those controllers won't resolve. Standard energy hatches cover
     * the common case.
     */
    public static MultiblockSummary resolveMultiblocks(World world, List<IBasicEnergyContainer> members) {
        MultiblockSummary summary = new MultiblockSummary();
        if (world == null) {
            return summary;
        }

        java.util.Map<Object, IBasicEnergyContainer> ourHatches = new java.util.IdentityHashMap<>();
        for (IBasicEnergyContainer member : members) {
            if (member instanceof IGregTechTileEntity) {
                IMetaTileEntity mte = ((IGregTechTileEntity) member).getMetaTileEntity();
                if (mte instanceof MTEHatchEnergy) {
                    ourHatches.put(mte, member);
                }
            }
        }
        if (ourHatches.isEmpty()) {
            return summary;
        }

        Object[] tiles = world.loadedTileEntityList.toArray(); // same-thread snapshot, CME-free
        for (Object te : tiles) {
            if (!(te instanceof IGregTechTileEntity)) {
                continue;
            }
            IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
            if (!(mte instanceof MTEMultiBlockBase)) {
                continue;
            }
            MTEMultiBlockBase controller = (MTEMultiBlockBase) mte;

            String name = null;
            for (MTEHatchEnergy hatch : controller.mEnergyHatches) {
                IBasicEnergyContainer ours = ourHatches.get(hatch);
                if (ours != null) {
                    if (name == null) {
                        name = controller.getLocalName();
                    }
                    summary.hatchOwnerName.put(ours, name);
                }
            }
            if (name == null) {
                continue; // controller not on our network
            }

            Object base = controller.getBaseMetaTileEntity();
            boolean controllerAllowed = !(base instanceof IMachineProgress)
                    || ((IMachineProgress) base).isAllowedToWork();
            long demand = 0L;
            if (controllerAllowed && controller.mMaxProgresstime > 0) {
                if (controller instanceof MTEExtendedPowerMultiBlockBase) {
                    long lEUt = ((MTEExtendedPowerMultiBlockBase<?>) controller).lEUt;
                    if (lEUt < 0) {
                        demand = (long) (-lEUt * (10000.0 / Math.max(1000, controller.mEfficiency)));
                    }
                } else if (controller.mEUt < 0) {
                    demand = ((long) -controller.mEUt * 10_000) / Math.max(1000, controller.mEfficiency);
                }
            }
            summary.demandEUt += demand;
            if (demand > 0) {
                summary.demandLines.add(name + " : " + demand + " EU/t (multiblock)");
            } else if (!controllerAllowed && controller.mMaxProgresstime > 0) {
                summary.demandLines.add(name + " (multiblock, disabled -- excluded)");
            }

            boolean powerLoss = false;
            if (base instanceof IMachineProgress) {
                IMachineProgress progress = (IMachineProgress) base;
                powerLoss = progress.wasShutdown() && progress.getLastShutDownReason() != null
                        && "power_loss".equals(progress.getLastShutDownReason().getID());
            }
            summary.controllers.add(new ControllerState(base, name, demand, powerLoss));
        }
        return summary;
    }

    public static String localNameOf(IBasicEnergyContainer container) {
        if (container instanceof IGregTechTileEntity) {
            IMetaTileEntity mte = ((IGregTechTileEntity) container).getMetaTileEntity();
            if (mte != null) {
                // getLocalName() is a default method on IMetaTileEntity
                // (verified against GT 5.09.54.20 source).
                String name = mte.getLocalName();
                if (name != null) {
                    return name;
                }
            }
        }
        return "?";
    }

    /**
     * Storage machines counted toward buffer stats instead of gen/consumption.
     * Two classes (deliberately NOT including MTEHatchEnergy -- that's a
     * multiblock EU input port, not a charge-storing buffer, so it stays
     * counted as a consumer):
     *
     *   gregtech.api.metatileentity.implementations.MTEBasicBatteryBuffer
     *   kekztech.common.tileentities.MTELapotronicSuperCapacitor  (LSC)
     *
     * The actual TileEntity found by NetworkDiscovery implements
     * IBasicEnergyContainer via BaseMetaTileEntity, which also implements
     * IGregTechTileEntity -- so we go through getMetaTileEntity() to reach
     * the logic class these instanceof checks need.
     */
    public static boolean isTransformer(IBasicEnergyContainer container) {
        if (!(container instanceof IGregTechTileEntity)) {
            return false;
        }
        return ((IGregTechTileEntity) container).getMetaTileEntity() instanceof MTETransformer;
    }

    public static boolean isBatteryBuffer(IBasicEnergyContainer container) {
        if (!(container instanceof IGregTechTileEntity)) {
            return false;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) container).getMetaTileEntity();
        return mte instanceof MTEBasicBatteryBuffer
                || mte instanceof kekztech.common.tileentities.MTELapotronicSuperCapacitor;
    }
}
