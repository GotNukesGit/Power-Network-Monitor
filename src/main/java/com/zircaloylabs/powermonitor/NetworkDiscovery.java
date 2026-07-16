package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicBatteryBuffer;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTECable;
import gregtech.api.metatileentity.implementations.MTETransformer;
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
                // Cable-to-cable: keep GT's real material/insulation/color
                // matching via getConnectableMTE -- this check is correct here.
                if (currentCable.getConnectableMTE(side) == null) {
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
                if (beyondMte instanceof MTEBasicBatteryBuffer && beyondTe instanceof IBasicEnergyContainer) {
                    if (addMember(foundMembers, (IBasicEnergyContainer) beyondTe, maxTrackedNodes)) {
                        return true;
                    }
                }
                if (visited.add(beyondGt)) {
                    queue.add(beyondGt);
                }
            } else if (beyondTe instanceof IBasicEnergyContainer) {
                // Machine directly touching the relay's face (no cable in
                // between) -- record it same as a normal terminal.
                if (addMember(foundMembers, (IBasicEnergyContainer) beyondTe, maxTrackedNodes)) {
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

        /** Net storage flow: sum over buffers of (avgIn - avgOut). >0 charging, <0 discharging. */
        public long bufferNetChargeEUt = 0L;

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

        /** Highest average draw among non-buffer members, for "top consumer" display. */
        public long topConsumerEUt = 0L;
        public String topConsumerName = "";

        /**
         * Per-generator fuel profile: one {avgOutputEUt, ratedEUt, fuelReserveEU}
         * entry per single-block generator, for staged runtime projection
         * (fuel is per-machine, NOT a shared pool -- when one generator runs
         * dry, network capacity steps down to the remaining generators).
         */
        public final List<long[]> generatorFuelProfile = new ArrayList<>();
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

            long avgIn = container.getAverageElectricInput();
            long avgOut = container.getAverageElectricOutput();
            if (avgOut > 0) {
                snap.totalGenerationEUt += avgOut;
            }
            if (avgIn > 0) {
                snap.totalConsumptionEUt += avgIn;
                if (avgIn > snap.topConsumerEUt) {
                    snap.topConsumerEUt = avgIn;
                    snap.topConsumerName = localNameOf(container);
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
            long[] storedAndCap = ((MTEBasicBatteryBuffer) mte).getStoredEnergy();
            snap.totalBufferedEU += storedAndCap[0];
            snap.totalBufferCapacityEU += storedAndCap[1];
        } else {
            // Other storage (e.g. LSC found by direct cable contact): tile-level
            // getters are the best generic read available.
            snap.totalBufferedEU += container.getStoredEU();
            snap.totalBufferCapacityEU += container.getEUCapacity();
        }

        snap.bufferNetChargeEUt += container.getAverageElectricInput() - container.getAverageElectricOutput();
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
        snap.generatorFuelProfile.add(new long[] { container.getAverageElectricOutput(), rated, fuelEU });
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
            snap.totalDemandEUt += machine.mEUt;
            snap.demandMeteredCount++;
        }
    }

    private static String localNameOf(IBasicEnergyContainer container) {
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
    private static boolean isBatteryBuffer(IBasicEnergyContainer container) {
        if (!(container instanceof IGregTechTileEntity)) {
            return false;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) container).getMetaTileEntity();
        return mte instanceof MTEBasicBatteryBuffer
                || mte instanceof kekztech.common.tileentities.MTELapotronicSuperCapacitor;
    }
}
