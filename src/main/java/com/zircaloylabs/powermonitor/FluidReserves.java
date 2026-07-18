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
        FluidTankInfo[] tanks = ((IFluidHandler) te).getTankInfo(querySide);
        if (tanks == null) {
            return;
        }
        for (FluidTankInfo info : tanks) {
            if (info != null && info.fluid != null && info.fluid.getFluid() != null && info.fluid.amount > 0) {
                result.litersByFluid.merge(info.fluid.getFluid(), (long) info.fluid.amount, Long::sum);
            }
        }
    }

    /** Keep only fluids that at least one connected generator can actually burn. */
    private static void filterToBurnableFluids(Result result, List<IBasicEnergyContainer> members) {
        result.litersByFluid.keySet().removeIf(fluid -> {
            FluidStack probe = new FluidStack(fluid, 1000);
            for (IBasicEnergyContainer member : members) {
                if (member instanceof IGregTechTileEntity) {
                    IMetaTileEntity mte = ((IGregTechTileEntity) member).getMetaTileEntity();
                    if (mte instanceof MTEBasicGenerator && ((MTEBasicGenerator) mte).getFuelValue(probe, true) > 0) {
                        return false; // burnable -- keep
                    }
                }
            }
            return true; // nobody burns it -- drop
        });
    }
}
