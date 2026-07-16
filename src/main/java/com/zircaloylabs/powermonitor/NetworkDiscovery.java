package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTECable;
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
                IMetaTileEntity neighborMte = currentCable.getConnectableMTE(side);
                if (neighborMte == null) {
                    continue; // not electrically connected on this side
                }

                TileEntity neighborTe = current.getTileEntityAtSide(side);

                if (neighborMte instanceof MTECable) {
                    if (!(neighborTe instanceof IGregTechTileEntity)) {
                        continue;
                    }
                    IGregTechTileEntity neighborGt = (IGregTechTileEntity) neighborTe;
                    if (visitedCables.add(neighborGt)) {
                        queue.add(neighborGt);
                    }
                    continue;
                }

                // Terminal: a machine/generator/buffer, not another cable segment.
                // The underlying TileEntity implements IBasicEnergyContainer if it's
                // a real GT energy-handling block (BaseMetaTileEntity does).
                if (neighborTe instanceof IBasicEnergyContainer) {
                    IBasicEnergyContainer container = (IBasicEnergyContainer) neighborTe;
                    if (foundMembers.add(container)) {
                        if (foundMembers.size() >= maxTrackedNodes) {
                            result.truncated = true;
                            result.members.addAll(foundMembers);
                            return result;
                        }
                    }
                }
            }
        }

        result.members.addAll(foundMembers);
        return result;
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
