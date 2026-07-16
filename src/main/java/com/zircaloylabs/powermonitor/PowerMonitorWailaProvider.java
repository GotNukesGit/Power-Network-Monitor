package com.zircaloylabs.powermonitor;

import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.common.covers.Cover;

import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;
import mcp.mobius.waila.api.IWailaRegistrar;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.event.FMLInterModComms;

import java.util.List;

/**
 * Waila (hover tooltip) integration: shows the Power Monitor's live readout
 * when looking at any cable that has one of our covers on it -- no
 * screwdriver click needed. The screwdriver chat dashboard remains for the
 * full detail dump; the tooltip is the at-a-glance view, with extra lines
 * while sneaking.
 *
 * Architecture (mirrors GT's own gregtech.crossmod.waila.Waila /
 * GregtechTEWailaDataProvider pattern, verified against 5.09.54.20 source):
 *
 *   - Registration goes through Waila's IMC handshake:
 *     FMLInterModComms.sendMessage("Waila", "register", "<this class>.callbackRegister")
 *     sent from the mod's init phase. Waila then calls callbackRegister
 *     reflectively with its IWailaRegistrar. Providers are registered
 *     against BaseMetaPipeEntity.class -- the tile class of GT cables,
 *     which is the only thing this cover can be mounted on.
 *
 *   - GT's pipe tile delegates its own Waila hooks to the pipe's
 *     MetaTileEntity, NOT to covers -- covers have no first-class Waila
 *     hooks in this GT version. Hence a separate provider rather than an
 *     override on PowerMonitorCover.
 *
 *   - The live telemetry exists only server-side (doCoverThings runs on
 *     the server), so getNBTData (server hook, Waila polls it for the
 *     block being looked at) packs the behavior snapshot into the sync
 *     tag, and getWailaBody (client hook) only formats what arrived.
 */
public class PowerMonitorWailaProvider implements IWailaDataProvider {

    private static final String NBT_KEY = "powermonitor";

    /** Send the Waila IMC handshake. Call once from the mod's init phase. */
    public static void init() {
        FMLInterModComms
                .sendMessage("Waila", "register", PowerMonitorWailaProvider.class.getName() + ".callbackRegister");
    }

    /** Called reflectively by Waila in response to the IMC message. */
    public static void callbackRegister(IWailaRegistrar registrar) {
        PowerMonitorWailaProvider provider = new PowerMonitorWailaProvider();
        registrar.registerBodyProvider(provider, BaseMetaPipeEntity.class);
        registrar.registerNBTProvider(provider, BaseMetaPipeEntity.class);
    }

    // --- server side: pack live telemetry into the sync tag ---

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x,
            int y, int z) {
        if (!(tile instanceof ICoverable)) {
            return tag;
        }
        ICoverable coverable = (ICoverable) tile;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            Cover cover = coverable.getCoverAtSide(side);
            if (!(cover instanceof PowerMonitorCover)) {
                continue;
            }
            PowerMonitorCoverBehavior b = ((PowerMonitorCover) cover).getBehavior();
            NBTTagCompound t = new NBTTagCompound();
            t.setString("tier", b.getTier().name());
            t.setBoolean("dead", b.isDestroyed());
            t.setLong("gen", b.getLiveGenerationEUt());
            t.setLong("cons", b.getLiveConsumptionEUt());
            t.setLong("buf", b.getLiveBufferedEU());
            t.setLong("bufCap", b.getLiveBufferCapacityEU());
            t.setLong("fuel", b.getFuelReserveEU());
            t.setLong("maxGen", b.getMaxGenerationEUt());
            t.setLong("cable", b.getAnchorThroughputEUt());
            t.setLong("secEmpty", b.getSecondsToEmpty());
            t.setLong("secFull", b.getSecondsToFull());
            t.setLong("fuelSecCur", b.getFuelSecondsAtCurrentRate());
            t.setLong("fuelSecMax", b.getFuelSecondsAtMaxRate());
            t.setBoolean("trunc", b.isNetworkLargerThanTierSupports());
            t.setBoolean("sat", b.isSupplySaturated());
            t.setLong("unmet", b.getLiveUnmetEUt());
            tag.setTag(NBT_KEY, t);
            break; // network numbers are identical from any monitor on this cable
        }
        return tag;
    }

    // --- client side: format what the server packed ---

    @Override
    public List<String> getWailaBody(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
            IWailaConfigHandler config) {
        NBTTagCompound root = accessor.getNBTData();
        if (root == null || !root.hasKey(NBT_KEY)) {
            return currenttip;
        }
        NBTTagCompound t = root.getCompoundTag(NBT_KEY);

        if (t.getBoolean("dead")) {
            currenttip.add("\u00a74Power Monitor destroyed (overvolted) -- replace it.");
            return currenttip;
        }

        long gen = t.getLong("gen");
        long cons = t.getLong("cons");
        long net = gen - cons;

        currenttip.add("\u00a76Power Monitor [" + t.getString("tier") + "]");
        currenttip.add(
                "Gen " + gen + " | Use " + cons + " EU/t  " + (net >= 0 ? "\u00a7a+" + net : "\u00a7c" + net));

        long bufCap = t.getLong("bufCap");
        if (bufCap > 0) {
            long buf = t.getLong("buf");
            int pct = (int) (100L * buf / bufCap);
            StringBuilder line = new StringBuilder("Buffer: ").append(pct).append('%');
            long secEmpty = t.getLong("secEmpty");
            long secFull = t.getLong("secFull");
            if (secEmpty >= 0) {
                line.append(" \u00a7c(~").append(PowerMonitorCover.formatSeconds(secEmpty)).append(" to empty)");
            } else if (secFull >= 0) {
                line.append(" \u00a7a(~").append(PowerMonitorCover.formatSeconds(secFull)).append(" to full)");
            }
            currenttip.add(line.toString());
        }

        long fuel = t.getLong("fuel");
        long maxGen = t.getLong("maxGen");
        if (fuel > 0) {
            long fuelSecCur = t.getLong("fuelSecCur");
            long fuelSecMax = t.getLong("fuelSecMax");
            if (fuelSecCur >= 0) {
                currenttip.add("Fuel: ~" + PowerMonitorCover.formatSeconds(fuelSecCur) + " at current rate");
            } else if (fuelSecMax >= 0) {
                currenttip.add("Fuel: ~" + PowerMonitorCover.formatSeconds(fuelSecMax) + " at full burn");
            } else {
                currenttip.add("Fuel reserves: " + fuel + " EU");
            }
        } else if (maxGen > 0) {
            currenttip.add("\u00a7eNo fuel in generator tanks/slots.");
        }

        if (accessor.getPlayer() != null && accessor.getPlayer().isSneaking()) {
            if (maxGen > 0) {
                currenttip.add(
                        "\u00a77Capacity: " + maxGen + " EU/t rated (" + (100L * gen / maxGen) + "% in use)");
            }
            if (bufCap > 0) {
                currenttip.add("\u00a77Buffer: " + t.getLong("buf") + " / " + bufCap + " EU");
            }
            if (fuel > 0) {
                currenttip.add("\u00a77Fuel reserves: " + fuel + " EU");
            }
            long cable = t.getLong("cable");
            if (cable > 0) {
                currenttip.add("\u00a77This cable: " + cable + " EU/t max throughput");
            }
        }

        long unmet = t.getLong("unmet");
        if (unmet > 0) {
            currenttip.add("§c⚠ Unmet demand: " + unmet + " EU/t");
        } else if (t.getBoolean("sat")) {
            currenttip.add("§cSupply saturated -- possible brownout");
        }
        if (t.getBoolean("trunc")) {
            currenttip.add("\u00a7eNetwork larger than this tier tracks -- upgrade.");
        }
        return currenttip;
    }

    // --- boilerplate ---

    @Override
    public ItemStack getWailaStack(IWailaDataAccessor accessor, IWailaConfigHandler config) {
        return null;
    }

    @Override
    public List<String> getWailaHead(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
            IWailaConfigHandler config) {
        return currenttip;
    }

    @Override
    public List<String> getWailaTail(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
            IWailaConfigHandler config) {
        return currenttip;
    }
}
