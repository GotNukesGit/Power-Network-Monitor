package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTECable;
import gregtech.api.metatileentity.implementations.MTETransformer;
import gregtech.api.metatileentity.implementations.MTEWetTransformer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers every machine/generator/buffer electrically connected to a given
 * starting cable segment, by walking GT's OWN connectivity logic
 * (MTECable#getConnectableMTE) rather than reinventing material/voltage/
 * colorization matching ourselves.
 *
 * This is a fresh BFS every call. Cap the walk at tier.maxTrackedNodes to
 * avoid a pathological base (or a loop bug) causing a lag spike; a monitor
 * that hits the cap should show "network larger than this tier can track,
 * upgrade to see more" rather than silently truncating without telling
 * the player.
 *
 * NOTE for next session: gregtech.api.graphs.PowerNode/Node/ConsumerNode
 * (BaseMetaPipeEntity#getNode(), public) is a persistent cached graph GT
 * itself builds for amperage routing, with public mNeighbourNodes/mConsumers
 * fields. It's likely a faster data source than re-deriving connectivity via
 * getConnectableMTE every poll -- but its exact semantics (whether a given
 * PowerNode's mConsumers represents the FULL network or a directed subset
 * resolved from one specific generator's routing pass) aren't confirmed yet.
 * Don't switch to it without verifying that against GT's pathing code --
 * an incorrect read there would silently under/over-count the network,
 * which is worse than the current BFS being merely unoptimized.
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

        Set<IGregTechTileEntity> visitedCables = new HashSet<>();
        Set<IGregTechTileEntity> visitedRelays = new HashSet<>(); // transformers already walked through
        Set<IBasicEnergyContainer> foundMembers = new HashSet<>();
        ArrayDeque<IGregTechTileEntity> queue = new ArrayDeque<>();

        visitedCables.add(anchorTile);
        queue.add(anchorTile);

        while (!queue.isEmpty()) {
            IGregTechTileEntity current = queue.poll();
            IMetaTileEntity currentMte = current.getMetaTileEntity();
            if (!(currentMte instanceof MTECable)) {
                continue;
            }
            MTECable currentCable = (MTECable) currentMte;

            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                TileEntity neighborTe = current.getTileEntityAtSide(side);
                if (neighborTe == null) {
                    continue;
                }

                // BUG FOUND after transformer fix still didn't resolve testing:
                // getConnectableMTE() requires the cable's rated voltage to
                // EXACTLY equal the machine's rated voltage
                // (metaTile.maxEUInput() == this.mVoltage -- confirmed via
                // decompile, strict ==, no tolerance). That's correct for GT's
                // own rendering/amperage-routing purposes, but it means any
                // real base where cable tier doesn't exactly match machine
                // tier (extremely common -- e.g. MV cable run to an LV
                // machine for lower loss) would make getConnectableMTE return
                // null for that side, and our BFS would never even see the
                // machine as a network member. Fix: for machine/transformer
                // detection specifically, stop depending on
                // getConnectableMTE's voltage check -- do our own facing-only
                // check instead. Cable-to-cable matching (material/insulation
                // /colorization) is still legitimate and kept as-is below.
                IMetaTileEntity neighborMteRaw = (neighborTe instanceof IGregTechTileEntity)
                        ? ((IGregTechTileEntity) neighborTe).getMetaTileEntity()
                        : null;

                if (neighborMteRaw instanceof MTECable) {
                    // Cable-to-cable: keep GT's real material/insulation/color
                    // matching via getConnectableMTE -- this check is correct here.
                    if (currentCable.getConnectableMTE(side) == null) {
                        continue;
                    }
                    IGregTechTileEntity neighborGt = (IGregTechTileEntity) neighborTe;
                    if (visitedCables.add(neighborGt)) {
                        queue.add(neighborGt);
                    }
                    continue;
                }

                if (neighborMteRaw instanceof MTETransformer || neighborMteRaw instanceof MTEWetTransformer) {
                    IGregTechTileEntity relayGt = (IGregTechTileEntity) neighborTe;
                    if (visitedRelays.add(relayGt)) {
                        exploreRelay(relayGt, visitedCables, visitedRelays, foundMembers, queue, maxTrackedNodes);
                        if (foundMembers.size() >= maxTrackedNodes) {
                            result.truncated = true;
                            result.cablesVisited = visitedCables.size();
                            result.members.addAll(foundMembers);
                            return result;
                        }
                    }
                    continue;
                }

                // Terminal machine: facing check only, no voltage-match requirement.
                if (neighborMteRaw instanceof MetaTileEntity && neighborTe instanceof IBasicEnergyContainer) {
                    MetaTileEntity mte = (MetaTileEntity) neighborMteRaw;
                    boolean facesUs = (mte.isEnetInput() && mte.isInputFacing(side.getOpposite()))
                            || (mte.isEnetOutput() && mte.isOutputFacing(side.getOpposite()));
                    if (facesUs) {
                        IBasicEnergyContainer container = (IBasicEnergyContainer) neighborTe;
                        if (foundMembers.add(container)) {
                            if (foundMembers.size() >= maxTrackedNodes) {
                                result.truncated = true;
                                result.cablesVisited = visitedCables.size();
                                result.members.addAll(foundMembers);
                                return result;
                            }
                        }
                    }
                }
            }
        }

        result.cablesVisited = visitedCables.size();
        result.members.addAll(foundMembers);
        return result;
    }

    /**
     * A transformer/wet-transformer isn't a network member itself (it doesn't
     * generate or consume in the sense our snapshot cares about -- it passes
     * power through). Walk its own connectable sides to find the cable
     * network on its far side and merge that into the same BFS.
     */
    private static void exploreRelay(
            IGregTechTileEntity relayTile,
            Set<IGregTechTileEntity> visitedCables,
            Set<IGregTechTileEntity> visitedRelays,
            Set<IBasicEnergyContainer> foundMembers,
            ArrayDeque<IGregTechTileEntity> queue,
            int maxTrackedNodes) {
        IMetaTileEntity relayMte = relayTile.getMetaTileEntity();
        if (!(relayMte instanceof MetaTileEntity)) {
            return;
        }
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity beyondTe = relayTile.getTileEntityAtSide(side);
            if (!(beyondTe instanceof IGregTechTileEntity)) {
                continue;
            }
            IGregTechTileEntity beyondGt = (IGregTechTileEntity) beyondTe;
            IMetaTileEntity beyondMte = beyondGt.getMetaTileEntity();
            if (beyondMte instanceof MTECable && visitedCables.add(beyondGt)) {
                queue.add(beyondGt);
            } else if (!(beyondMte instanceof MTECable) && beyondTe instanceof IBasicEnergyContainer) {
                // Directly touching a machine on the relay's other face (no
                // cable in between) -- record it same as a normal terminal.
                foundMembers.add((IBasicEnergyContainer) beyondTe);
            }
            if (foundMembers.size() >= maxTrackedNodes) {
                return;
            }
        }
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
    }

    public static Snapshot summarize(List<IBasicEnergyContainer> members) {
        Snapshot snap = new Snapshot();
        for (IBasicEnergyContainer container : members) {
            // Battery buffers: counted only toward buffer charge/capacity, excluded
            // from gen/consumption totals (see design note: avoids double-counting
            // a buffer as both a "consumer" while charging and "generator" while
            // discharging within the same snapshot).
            boolean isBufferOnly = isBatteryBuffer(container);

            if (isBufferOnly) {
                snap.totalBufferedEU += container.getStoredEU();
                snap.totalBufferCapacityEU += container.getEUCapacity();
                continue;
            }

            long avgIn = container.getAverageElectricInput();
            long avgOut = container.getAverageElectricOutput();
            if (avgOut > 0) {
                snap.totalGenerationEUt += avgOut;
            }
            if (avgIn > 0) {
                snap.totalConsumptionEUt += avgIn;
            }
        }
        return snap;
    }

    /**
     * CONFIRMED this session against the decompiled jar. Two real GT/GTNH
     * buffer classes found (deliberately NOT including MTEHatchEnergy --
     * that's a multiblock EU input port, not a charge-storing buffer, so
     * it stays counted as a consumer):
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
        return mte instanceof gregtech.api.metatileentity.implementations.MTEBasicBatteryBuffer
                || mte instanceof kekztech.common.tileentities.MTELapotronicSuperCapacitor;
    }
}
