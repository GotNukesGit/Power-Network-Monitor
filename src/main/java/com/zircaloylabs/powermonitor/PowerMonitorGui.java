package com.zircaloylabs.powermonitor;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.layout.Flow;

import gregtech.api.modularui2.CoverGuiData;
import com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil;
import gregtech.common.gui.modularui.cover.base.CoverBaseGui;
import gregtech.common.gui.modularui.widget.LineChartWidget;

import net.minecraft.network.PacketBuffer;

import java.text.DecimalFormat;

/**
 * MUI2 dashboard for the Power Monitor cover: full live telemetry plus
 * scrolling history charts. Opened by GT's standard cover-GUI gesture
 * (shift-right-click with an empty hand).
 *
 * Architecture notes (all patterns verified against GT 5.09.54.20 source,
 * not guessed):
 *
 *   - Extends CoverBaseGui and implements addUIWidgets(), the extension
 *     point Cover#buildUI routes through (same shape as CoverEUMeterGui).
 *     The base panel auto-sizes to content (coverChildren), supplies the
 *     title row and the tick-rate button.
 *
 *   - The live numbers exist only server-side (doCoverThings runs on the
 *     server), so every displayed value is a getter-only LongSyncValue
 *     registered with the PanelSyncManager: MUI2 polls the supplier
 *     server-side, syncs on change, and IKey.dynamic() reads the synced
 *     client cache each frame.
 *
 *   - Charts are GT's own LineChartWidget fed by GenericListSyncHandler
 *     <Double> -- the exact widget + sync pattern GT's Tesla Tower GUI
 *     uses for its output-current history chart. The series suppliers
 *     return lists cached in the behavior (rebuilt once per 1 Hz sample),
 *     so MUI2's per-tick supplier polling stays cheap.
 */
public class PowerMonitorGui extends CoverBaseGui<PowerMonitorCover> {

    private static final int CHART_WIDTH = 160;
    private static final int CHART_HEIGHT = 44;

    public PowerMonitorGui(PowerMonitorCover cover) {
        super(cover);
    }

    @Override
    public void addUIWidgets(PanelSyncManager syncManager, Flow column, CoverGuiData data) {
        PowerMonitorCoverBehavior b = cover.getBehavior();

        LongSyncValue gen = reg(syncManager, "pm_gen", b::getLiveGenerationEUt);
        LongSyncValue cons = reg(syncManager, "pm_cons", b::getLiveConsumptionEUt);
        LongSyncValue maxGen = reg(syncManager, "pm_maxgen", b::getMaxGenerationEUt);
        LongSyncValue buf = reg(syncManager, "pm_buf", b::getLiveBufferedEU);
        LongSyncValue bufCap = reg(syncManager, "pm_bufcap", b::getLiveBufferCapacityEU);
        LongSyncValue secEmpty = reg(syncManager, "pm_secempty", b::getSecondsToEmpty);
        LongSyncValue secFull = reg(syncManager, "pm_secfull", b::getSecondsToFull);
        LongSyncValue fuel = reg(syncManager, "pm_fuel", b::getFuelReserveEU);
        LongSyncValue fuelSecCur = reg(syncManager, "pm_fuelseccur", b::getFuelSecondsAtCurrentRate);
        LongSyncValue fuelSecMax = reg(syncManager, "pm_fuelsecmax", b::getFuelSecondsAtMaxRate);
        LongSyncValue cable = reg(syncManager, "pm_cable", b::getAnchorThroughputEUt);
        LongSyncValue peakCons = reg(syncManager, "pm_peakcons", b::getPeakConsumptionEUt);
        LongSyncValue peakDef = reg(syncManager, "pm_peakdef", b::getPeakDeficitEUt);

        // --- Live telemetry rows ---

        column.child(IKey.dynamic(() -> {
            long g = gen.getLongValue();
            long m = maxGen.getLongValue();
            String s = "Generation: \u00a7a" + fmt(g) + " EU/t\u00a7r";
            if (m > 0) {
                s += "  (rated " + fmt(m) + ", " + (100L * g / m) + "% in use)";
            }
            return s;
        }).asWidget().marginBottom(2));

        column.child(IKey.dynamic(() -> {
            long c = cons.getLongValue();
            long net = gen.getLongValue() - c;
            return "Consumption: \u00a7c" + fmt(c) + " EU/t\u00a7r   Net: "
                    + (net >= 0 ? "\u00a7a+" : "\u00a7c") + fmt(net) + " EU/t";
        }).asWidget().marginBottom(2));

        column.child(IKey.dynamic(() -> {
            long cap = bufCap.getLongValue();
            if (cap <= 0) {
                return "Buffer: \u00a78none on network";
            }
            long stored = buf.getLongValue();
            String s = "Buffer: " + fmt(stored) + " / " + fmt(cap) + " EU (" + (100L * stored / cap) + "%)";
            long e = secEmpty.getLongValue();
            long f = secFull.getLongValue();
            if (e >= 0) {
                s += "  \u00a7c~" + PowerMonitorCover.formatSeconds(e) + " to empty";
            } else if (f >= 0) {
                s += "  \u00a7a~" + PowerMonitorCover.formatSeconds(f) + " to full";
            }
            return s;
        }).asWidget().marginBottom(2));

        column.child(IKey.dynamic(() -> {
            long fu = fuel.getLongValue();
            if (fu <= 0) {
                return maxGen.getLongValue() > 0 ? "Fuel: \u00a7enone in generator tanks/slots"
                        : "Fuel: \u00a78no generators on network";
            }
            String s = "Fuel: " + fmt(fu) + " EU";
            long atCur = fuelSecCur.getLongValue();
            long atMax = fuelSecMax.getLongValue();
            if (atCur >= 0) {
                s += "  ~" + PowerMonitorCover.formatSeconds(atCur) + " @ current";
            }
            if (atMax >= 0 && atMax != atCur) {
                s += "  ~" + PowerMonitorCover.formatSeconds(atMax) + " @ full burn";
            }
            return s;
        }).asWidget().marginBottom(2));

        column.child(IKey.dynamic(
                () -> "This cable: " + fmt(cable.getLongValue()) + " EU/t max throughput")
                .asWidget().marginBottom(2));

        column.child(IKey.dynamic(
                () -> "Peak draw: \u00a7c" + fmt(peakCons.getLongValue()) + " EU/t\u00a7r   Peak deficit: \u00a7c"
                        + fmt(peakDef.getLongValue()) + " EU/t")
                .asWidget().marginBottom(4));

        // --- History charts (only for tiers that track history) ---

        if (b.getHistory() == null) {
            column.child(IKey.str("\u00a78(Upgrade past ULV for history charts)").asWidget());
            return;
        }

        column.child(IKey.str("\u00a77Consumption history (EU/t)").asWidget().marginBottom(1));
        column.child(makeChart(b::getChartConsumption).marginBottom(4));
        column.child(IKey.str("\u00a77Generation history (EU/t)").asWidget().marginBottom(1));
        column.child(makeChart(b::getChartGeneration));
    }

    private LineChartWidget makeChart(java.util.function.Supplier<java.util.List<Double>> series) {
        // Sync-handler construction copied from GT's Tesla Tower chart --
        // the null slots are the client->server setter (read-only chart)
        // and the deserializer hook we don't need.
        return new LineChartWidget()
                .syncHandler(new GenericListSyncHandler<>(
                        series,
                        null,
                        PacketBuffer::readDouble,
                        PacketBuffer::writeDouble,
                        Double::equals,
                        null))
                .lowerBoundAlwaysZero()
                .lineWidth(2)
                .chartUnit(" EU/t")
                .formatter(new DecimalFormat("#,##0"))
                .size(CHART_WIDTH, CHART_HEIGHT);
    }

    private LongSyncValue reg(PanelSyncManager syncManager, String name, java.util.function.LongSupplier getter) {
        LongSyncValue value = new LongSyncValue(getter);
        syncManager.syncValue(name, 0, value);
        return value;
    }

    private static String fmt(long v) {
        return NumberFormatUtil.formatNumber(v);
    }
}
