package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEFluidPipe;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connected fuel-reserve discovery: walks the FLUID pipe network attached to
 * the power network's generators and totals every reachable tank, per fluid.
 *
 * Design notes (all mirroring choices proven on the energy side):
 *
 *   - Pipes are walked by LIVE connection state (isConnectedAtSide, shared
 *     via MetaPipeEntity with cables) -- the same rule GT routes fluid by,
 *     so the walk cannot disagree with the game about plumbing (the exact
 *     lesson the mixed-material cable bug taught).
 *
 *   - Tanks are anything implementing IFluidHandler -- the universal Forge
 *     interface -- so GT drums, super tanks, multiblock tank masters and
 *     Railcraft coke ovens all answer getTankInfo() without special cases.
 *     Pipe segments' own contents count too (a long 4x line holds real
 *     liters).
 *
 *   - MEASURE, don't classify: whether a reserve is a "battery" (production
 *     charging it -- steam from boilers, creosote from coke ovens) or a
 *     depleting stock is announced by its measured TREND, not by fluid-type
 *     assumptions. This scanner only totals; the behavior layer owns slopes
 *     and runways.
 *
 *   - Only fluids some connected generator actually burns count
 *     (getFuelValue > 0), so a water line never reads as fuel.
 *
 *   - Energy-network members are excluded from tank counting: generators'
 *     internal tanks are already the in-machine fuel number, and the
 *     display's honesty depends on "guaranteed" (in machines) staying
 *     separate from "connected" (assumes the plumbing keeps delivering).
 */
public final class FluidReserves {

    private FluidReserves() {}

    public static final class Result {
        public final Map<Fluid, Long> litersByFluid = new LinkedHashMap<>();
        public boolean truncated = false;
        /** EU-equivalent of all connected reserves, via each fluid's best burning generator. */
        public long totalReserveEU = 0L;
        /** Per-fluid EU-equivalent of shared reserves (for staircase augmentation). */
        public final Map<Fluid, Long> euByFluid = new LinkedHashMap<>();
        /** Diagnostic: every counted tank ("Name @ x,y,z : fluid amounts"). */
        public final java.util.List<String> tankLines = new java.util.ArrayList<>();
        /** Identity set of counted tank tiles -- producer scoping checks output hatches against this. */
        public final java.util.Set<TileEntity> visitedTanks = java.util.Collections
                .newSetFromMap(new java.util.IdentityHashMap<>());
        public int pipesVisited = 0;
    }

    public static Result scan(List<IBasicEnergyContainer> members, int maxPipes) {
        Result result = new Result();

        Set<Object> memberSet = Collections.newSetFromMap(new IdentityHashMap<>());
        memberSet.addAll(members);

        Set<Object> visitedPipes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> countedTanks = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<IGregTechTileEntity> pipeQueue = new ArrayDeque<>();

        // Seed: every side of every generator -- pipes join the walk, and
        // directly-attached tanks (drum with a pump cover, no pipe) count.
        for (IBasicEnergyContainer member : members) {
            if (!(member instanceof IGregTechTileEntity)) {
                continue;
            }
            IGregTechTileEntity gt = (IGregTechTileEntity) member;
            if (!(gt.getMetaTileEntity() instanceof MTEBasicGenerator)) {
                continue;
            }
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                TileEntity neighbor = gt.getTileEntityAtSide(side);
                if (neighbor == null) {
                    continue;
                }
                if (isFluidPipe(neighbor)) {
                    if (visitedPipes.add(neighbor)) {
                        pipeQueue.add((IGregTechTileEntity) neighbor);
                    }
                } else {
                    countTank(result, countedTanks, memberSet, neighbor, side.getOpposite());
                }
            }
        }

        while (!pipeQueue.isEmpty()) {
            if (visitedPipes.size() > maxPipes) {
                result.truncated = true;
                break;
            }
            IGregTechTileEntity pipeGt = pipeQueue.poll();
            MTEFluidPipe pipe = (MTEFluidPipe) pipeGt.getMetaTileEntity();
            if (pipe == null) {
                continue;
            }
            // Pipe segments hold real contents (mFluids: one slot per channel
            // on multi-channel pipes).
            if (pipe.mFluids != null) {
                for (FluidStack fs : pipe.mFluids) {
                    if (fs != null && fs.getFluid() != null && fs.amount > 0) {
                        result.litersByFluid.merge(fs.getFluid(), (long) fs.amount, Long::sum);
                    }
                }
            }
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                if (!pipe.isConnectedAtSide(side)) {
                    continue; // live plumbing state -- snips and unformed joins respected
                }
                TileEntity beyond = pipeGt.getTileEntityAtSide(side);
                if (beyond == null) {
                    continue;
                }
                if (isFluidPipe(beyond)) {
                    if (visitedPipes.add(beyond)) {
                        pipeQueue.add((IGregTechTileEntity) beyond);
                    }
                } else {
                    countTank(result, countedTanks, memberSet, beyond, side.getOpposite());
                }
            }
        }

        result.pipesVisited = visitedPipes.size();
        filterToBurnableFluids(result, members);
        return result;
    }

    private static boolean isFluidPipe(TileEntity te) {
        return te instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) te).getMetaTileEntity() instanceof MTEFluidPipe;
    }

    private static void countTank(Result result, Set<Object> countedTanks, Set<Object> memberSet, TileEntity te,
            ForgeDirection querySide) {
        if (!(te instanceof IFluidHandler) || memberSet.contains(te) || !countedTanks.add(te)) {
            return;
        }
        result.visitedTanks.add(te);
        // FOREIGN MACHINES ARE NOT TANKS. A generator or processing machine
        // belonging to some OTHER network is also an IFluidHandler -- its
        // internal fuel/recipe fluid is its own, not our reserve.
        // Field-observed: two combustion engines touching caused each
        // monitor to count the NEIGHBOR's diesel. Real tanks (GT drums,
        // super/quantum tanks, Railcraft) don't descend from these classes.
        if (te instanceof IGregTechTileEntity) {
            IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
            if (mte instanceof MTEBasicGenerator
                    || mte instanceof gregtech.api.metatileentity.implementations.MTEBasicMachine
                    || mte instanceof gregtech.api.metatileentity.implementations.MTEMultiBlockBase) {
                return;
            }
        }
        FluidTankInfo[] tanks = ((IFluidHandler) te).getTankInfo(querySide);
        if (tanks == null) {
            return;
        }
        StringBuilder diag = null;
        for (FluidTankInfo info : tanks) {
            if (info != null && info.fluid != null && info.fluid.getFluid() != null && info.fluid.amount > 0) {
                result.litersByFluid.merge(info.fluid.getFluid(), (long) info.fluid.amount, Long::sum);
                if (diag == null) {
                    diag = new StringBuilder(tankName(te)).append(" @ ").append(te.xCoord).append(",")
                            .append(te.yCoord).append(",").append(te.zCoord).append(" :");
                }
                diag.append(" ").append(info.fluid.getLocalizedName()).append(" ").append(info.fluid.amount).append("L");
            }
        }
        if (diag != null && result.tankLines.size() < 16) {
            result.tankLines.add(diag.toString());
        }
    }

    private static String tankName(TileEntity te) {
        if (te instanceof IGregTechTileEntity) {
            IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
            if (mte != null) {
                return mte.getLocalName();
            }
        }
        return te.getClass().getSimpleName();
    }

    /** Keep only fluids that at least one connected generator can actually burn. */
    private static void filterToBurnableFluids(Result result, List<IBasicEnergyContainer> members) {
        java.util.Iterator<Map.Entry<Fluid, Long>> it = result.litersByFluid.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Fluid, Long> e = it.next();
            FluidStack probe = new FluidStack(e.getKey(), 1000);
            double bestEuPerMb = 0;
            for (IBasicEnergyContainer member : members) {
                if (member instanceof IGregTechTileEntity) {
                    IMetaTileEntity mte = ((IGregTechTileEntity) member).getMetaTileEntity();
                    if (mte instanceof MTEBasicGenerator) {
                        MTEBasicGenerator gen = (MTEBasicGenerator) mte;
                        long euPerOp = gen.getFuelValue(probe, true);
                        if (euPerOp > 0) {
                            double perMb = euPerOp / (double) Math.max(1, gen.consumedFluidPerOperation(probe));
                            bestEuPerMb = Math.max(bestEuPerMb, perMb);
                        }
                    }
                }
            }
            if (bestEuPerMb <= 0) {
                it.remove(); // nobody burns it
            } else {
                long eu = Math.round(e.getValue() * bestEuPerMb);
                result.totalReserveEU += eu;
                result.euByFluid.put(e.getKey(), eu);
            }
        }
    }
}
