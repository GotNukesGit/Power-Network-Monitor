package com.zircaloylabs.powermonitor;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.widgets.layout.Flow;

import gregtech.api.modularui2.CoverGuiData;
import gregtech.api.modularui2.GTWidgetThemes;
import gregtech.common.gui.modularui.cover.base.CoverBaseGui;
import gregtech.common.gui.modularui.widget.LineChartWidget;
import gregtech.api.util.GTUtility;

import com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil;

import net.minecraft.network.PacketBuffer;

import java.text.DecimalFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * MUI2 dashboard for the Power Monitor cover. Opened by GT's standard
 * cover-GUI gesture (shift-right-click with an empty hand).
 *
 * Layout: sectioned like a real SCADA panel -- POWER (the three-line truth:
 * demand / delivered / unmet, then supply capacity), STORAGE (level + live
 * flow), FUEL (per-generator staged schedules), NETWORK (census, top draw,
 * cable, peaks), then a 2x2 grid of history charts. Warnings render in a
 * fixed slot at the bottom of NETWORK so the panel doesn't reflow when a
 * warning appears.
 *
 * Data-accounting notes (why the numbers mean what they say):
 *
 *   - Demand is TRUE recipe draw (mEUt of singleblock machines with an
 *     active recipe -- readable even while the machine starves; verified
 *     against MTEBasicMachine's tick loop). Delivered is GT's measured
 *     average transfer. Unmet = demand - delivered, and is conservative:
 *     machines outside metering coverage add delivered draw but no demand,
 *     so a positive Unmet is always a real shortfall.
 *
 *   - Storage flow is metered on the buffers themselves (avg in - out),
 *     which is more trustworthy than consumption-minus-generation: those
 *     two are metered on opposite sides of cable loss and never quite
 *     reconcile.
 *
 * All sync/widget/theme patterns verified against GT 5.09.54.20 source
 * (CoverEUMeterGui for the extension point, Tesla Tower GUI for the chart
 * + GenericListSyncHandler pattern and the TESLA_TOWER_CHART theme).
 */
public class PowerMonitorGui extends CoverBaseGui<PowerMonitorCover> {

    private static final int CHART_WIDTH = 96;
    private static final int CHART_HEIGHT = 40;
    private static final int CHART_GAP = 6;
    private static final int CONTENT_WIDTH = CHART_WIDTH * 2 + CHART_GAP;
    private static final int DIVIDER_COLOR = 0x38FFFFFF; // subtle light rule on the dark panel
    private static final int PANEL_BG = 0xF60D0F12; // near-black, slightly translucent
    private static final int CHART_BG = 0xFF10141A; // dark navy plot area, overrides the theme's purple

    public PowerMonitorGui(PowerMonitorCover cover) {
        super(cover);
    }

    /**
     * Base title theme (TEXT_TITLE) is tuned for GT's stock panel and reads
     * nearly invisible on our near-black background -- same icon + name, in
     * bold white.
     */
    @Override
    protected void addTitleToUI(Flow titleRow, CoverGuiData data) {
        net.minecraft.item.ItemStack coverItem = GTUtility.intToStack(cover.getCoverID());
        if (coverItem == null) {
            return;
        }
        titleRow.child(new ItemDrawable(coverItem).asWidget())
                .child(IKey.str("\u00a7f\u00a7l" + coverItem.getDisplayName()).asWidget().marginLeft(4)
                        .verticalCenter());
    }

    /**
     * Same panel the base class builds, on a near-black background --
     * explicit widget backgrounds take precedence over the theme's, so this
     * darkens the panel without touching GT's theme registry.
     */
    @Override
    protected ModularPanel createBasePanel(PanelSyncManager syncManager, UISettings uiSettings, CoverGuiData data) {
        ModularPanel panel = super.createBasePanel(syncManager, uiSettings, data);
        panel.background(new Rectangle().color(PANEL_BG).cornerRadius(4));
        return panel;
    }

    @Override
    public void addUIWidgets(PanelSyncManager syncManager, Flow column, CoverGuiData data) {
        PowerMonitorCoverBehavior b = cover.getBehavior();

        LongSyncValue gen = reg(syncManager, "pm_gen", b::getLiveGenerationEUt);
        LongSyncValue cons = reg(syncManager, "pm_cons", b::getLiveConsumptionEUt);
        LongSyncValue demand = reg(syncManager, "pm_demand", b::getLiveDemandEUt);
        LongSyncValue unmet = reg(syncManager, "pm_unmet", b::getLiveUnmetEUt);
        LongSyncValue maxGen = reg(syncManager, "pm_maxgen", b::getMaxGenerationEUt);
        LongSyncValue storageCap = reg(syncManager, "pm_storagecap", b::getStorageDischargeCapEUt);
        LongSyncValue storageIn = reg(syncManager, "pm_storagein", b::getBufferInEUt);
        LongSyncValue storageOut = reg(syncManager, "pm_storageout", b::getBufferOutEUt);
        LongSyncValue buf = reg(syncManager, "pm_buf", b::getLiveBufferedEU);
        LongSyncValue bufCap = reg(syncManager, "pm_bufcap", b::getLiveBufferCapacityEU);
        LongSyncValue secEmpty = reg(syncManager, "pm_secempty", b::getSecondsToEmpty);
        LongSyncValue secFull = reg(syncManager, "pm_secfull", b::getSecondsToFull);
        LongSyncValue fuel = reg(syncManager, "pm_fuel", b::getFuelReserveEU);
        LongSyncValue cable = reg(syncManager, "pm_cable", b::getAnchorThroughputEUt);
        LongSyncValue peakDemand = reg(syncManager, "pm_peakdemand", b::getPeakDemandEUt);
        LongSyncValue peakDrain = reg(syncManager, "pm_peakdrain", b::getPeakDeficitEUt);
        LongSyncValue genCount = reg(syncManager, "pm_gencount", b::getGeneratorCount);
        LongSyncValue machCount = reg(syncManager, "pm_machcount", b::getMachineCount);
        LongSyncValue bufCount = reg(syncManager, "pm_bufcount", b::getBufferCount);
        LongSyncValue cables = reg(syncManager, "pm_cables", b::getLastCablesVisited);
        LongSyncValue multiCountSync = reg(syncManager, "pm_multis", b::getMultiblockCount);
        LongSyncValue topEUt = reg(syncManager, "pm_topeut", b::getTopConsumerEUt);
        LongSyncValue status = reg(syncManager, "pm_status", b::getSupplyStatus);
        LongSyncValue storedEta = reg(syncManager, "pm_storedeta", b::getStoredEtaSeconds);
        LongSyncValue truncated = reg(syncManager, "pm_trunc", () -> b.isNetworkLargerThanTierSupports() ? 1L : 0L);

        StringSyncValue topName = regStr(syncManager, "pm_topname", b::getTopConsumerName);
        StringSyncValue schedFull = regStr(syncManager, "pm_schedfull", b::getFuelScheduleFullBurn);
        StringSyncValue schedCur = regStr(syncManager, "pm_schedcur", b::getFuelScheduleCurrent);
        StringSyncValue deadBuffer = regStr(syncManager, "pm_deadbuf", b::getDeadBufferWarning);
        StringSyncValue outage0 = regStr(syncManager, "pm_outage0", () -> b.getOutageSummary(0));
        StringSyncValue outage1 = regStr(syncManager, "pm_outage1", () -> b.getOutageSummary(1));

        // ================= POWER =================
        section(column, "POWER");

        row(column, () -> {
            long d = demand.getLongValue();
            long delivered = cons.getLongValue();
            long u = unmet.getLongValue();
            String s = "\u00a77Demand: \u00a7f" + fmt(d) + "\u00a7r \u00a77Delivered: \u00a7f" + fmt(delivered) + "\u00a7r EU/t";
            if (u > 0) {
                s += "   \u00a7c\u26a0 Unmet: " + fmt(u);
            }
            return s;
        });

        row(column, () -> {
            long g = gen.getLongValue();
            long m = maxGen.getLongValue();
            long net = g - cons.getLongValue();
            String s = "\u00a77Generation: \u00a7a" + fmt(g) + "\u00a7r";
            if (m > 0) {
                s += " / " + fmt(m) + " EU/t (" + (100L * g / m) + "%)";
            } else {
                s += " EU/t";
            }
            s += " \u00a77Net: " + (net >= 0 ? "\u00a7a+" : "\u00a7c") + fmt(net);
            return s;
        });

        // Deliberately NOT summed into one "ceiling": whether storage adds to
        // or gates generation depends on topology (a buffer in SERIES with
        // the load caps the whole network at ITS output, it doesn't add).
        // Two honest components beat one number that's wrong half the time.
        row(column, () -> {
            String s = "\u00a77Capacity: gen \u00a7f" + fmt(maxGen.getLongValue()) + "\u00a7r EU/t";
            long sc = storageCap.getLongValue();
            if (sc > 0) {
                s += "\u00a77 \u00b7 storage can push \u00a7f" + fmt(sc) + "\u00a7r EU/t";
            }
            return s;
        });

        divider(column);

        // ================= STORAGE =================
        section(column, "STORAGE");

        row(column, () -> {
            long cap = bufCap.getLongValue();
            if (cap <= 0) {
                return "\u00a7fNo storage on network";
            }
            long stored = buf.getLongValue();
            String s = "\u00a77Charge: \u00a7f" + fmt(stored) + "\u00a7r / " + fmt(cap) + " EU (" + (100L * stored / cap)
                    + "%)";
            long e = secEmpty.getLongValue();
            long f = secFull.getLongValue();
            if (e >= 0) {
                s += "  \u00a7c~" + PowerMonitorCover.formatSeconds(e) + " to empty";
            } else if (f >= 0) {
                s += "  \u00a7a~" + PowerMonitorCover.formatSeconds(f) + " to full";
            }
            return s;
        });

        // Gross both-direction flow: net alone hides a series buffer doing
        // all the work (100 in / 100 out nets to "idle" while every joule
        // in the base transits it). Matched in/out = pass-through topology,
        // one-sided = parallel reserve -- readable at a glance.
        row(column, () -> {
            if (bufCap.getLongValue() <= 0) {
                return "";
            }
            long in = storageIn.getLongValue();
            long out = storageOut.getLongValue();
            long net = in - out;
            String s;
            if (in == 0 && out == 0) {
                s = "\u00a77Flow: \u00a7fidle";
            } else {
                s = "\u00a77Flow: \u00a7ain " + fmt(in) + "\u00a7r / \u00a7cout " + fmt(out) + "\u00a7r EU/t (net "
                        + (net >= 0 ? "\u00a7a+" : "\u00a7c") + fmt(net) + "\u00a7r)";
            }
            return s + " \u00a77Can push up to \u00a7f" + fmt(storageCap.getLongValue()) + "\u00a77 EU/t";
        });

        // Root-cause diagnostic for the classic trap: buffer inline,
        // batteries forgotten, mystery blackout.
        row(column, () -> {
            String warn = deadBuffer.getStringValue();
            return warn.isEmpty() ? "" : "\u00a7c\u26a0 " + warn;
        });

        divider(column);

        // ================= FUEL =================
        section(column, "FUEL");

        row(column, () -> {
            if (fuel.getLongValue() <= 0) {
                return maxGen.getLongValue() > 0 ? "\u00a7eNo fuel in generator tanks/slots"
                        : "\u00a7fNo generators on network";
            }
            String sched = schedFull.getStringValue();
            return "\u00a77Full burn:  " + (sched.isEmpty() ? "\u00a7fn/a" : "\u00a7f" + sched);
        });

        row(column, () -> {
            if (fuel.getLongValue() <= 0) {
                return "";
            }
            String sched = schedCur.getStringValue();
            return sched.isEmpty() ? "\u00a77Current:    \u00a7fgenerators idle" : "\u00a77Current:    \u00a7f" + sched;
        });

        divider(column);

        // ================= NETWORK =================
        section(column, "NETWORK");

        row(column, () -> "\u00a7f" + genCount.getLongValue() + "\u00a77 gen \u00b7 \u00a7f"
                + machCount.getLongValue() + "\u00a77 machines \u00b7 \u00a7f" + multiCountSync.getLongValue()
                + "\u00a77 multis \u00b7 \u00a7f" + bufCount.getLongValue() + "\u00a77 buffers \u00b7 \u00a7f"
                + cables.getLongValue() + "\u00a77 cables");

        row(column, () -> {
            long t = topEUt.getLongValue();
            return t <= 0 ? "\u00a77Top draw: \u00a7fnone"
                    : "\u00a77Top draw: \u00a7f" + topName.getStringValue() + "\u00a7r \u00a77(\u00a7f" + fmt(t) + "\u00a77 EU/t)";
        });

        row(column, () -> "\u00a77This cable: \u00a7f" + fmt(cable.getLongValue()) + "\u00a77 EU/t max  Peak demand: \u00a7f"
                + fmt(peakDemand.getLongValue()) + "\u00a77  Peak drain: \u00a7f" + fmt(peakDrain.getLongValue()));

        // Status slot -- always present so the panel doesn't reflow. Tiered:
        // a network coasting on storage is NOT browning out yet, and the
        // wording says so (with the countdown from the buffers' own meters).
        row(column, () -> {
            long st = status.getLongValue();
            if (st >= 3) {
                return "\u00a7c\u26a0 BROWNOUT -- demand exceeds supply";
            }
            if (st == 2) {
                long eta = storedEta.getLongValue();
                String t = eta >= 0 ? "~" + PowerMonitorCover.formatSeconds(eta) : "soon";
                return "\u00a76\u26a0 Running on stored EU -- brownout in " + t + " if load continues";
            }
            if (st == 1) {
                return "\u00a7e\u26a0 Generation at capacity -- no headroom";
            }
            if (truncated.getLongValue() != 0) {
                return "\u00a7e\u26a0 Network larger than this tier tracks -- upgrade";
            }
            return "\u00a72\u2714 Supply healthy";
        });

        // Outage black box: why did the lights go out, and how badly.
        row(column, () -> {
            String o = outage0.getStringValue();
            return o.isEmpty() ? "" : "\u00a7cOutage: " + o;
        });
        row(column, () -> {
            String o = outage1.getStringValue();
            return o.isEmpty() ? "" : "\u00a7cOutage: " + o;
        });

        divider(column);

        // ================= CHARTS (2x2) =================

        if (b.getHistory() == null) {
            column.child(IKey.str("\u00a7f(Upgrade past ULV for history charts)").asWidget());
            return;
        }

        column.child(Flow.row().coverChildren()
                .child(chartColumn("Demand (EU/t)", b::getChartDemand, " EU/t").marginRight(CHART_GAP))
                .child(chartColumn("Generation (EU/t)", b::getChartGeneration, " EU/t"))
                .marginBottom(4));
        column.child(Flow.row().coverChildren()
                .child(chartColumn("Storage (EU)", b::getChartBuffered, " EU").marginRight(CHART_GAP))
                .child(chartColumn("Fuel reserve (EU)", b::getChartFuel, " EU")));
    }

    // --- layout helpers ---

    private void section(Flow column, String title) {
        column.child(IKey.str("\u00a76\u00a7l" + title).asWidget().marginBottom(2));
    }

    private void row(Flow column, Supplier<String> text) {
        column.child(IKey.dynamic(text::get).asWidget().marginBottom(2));
    }

    private void divider(Flow column) {
        column.child(new Rectangle().color(DIVIDER_COLOR).asWidget().size(CONTENT_WIDTH, 1)
                .marginTop(1).marginBottom(3));
    }

    private Flow chartColumn(String label, Supplier<List<Double>> series, String unit) {
        return Flow.column().coverChildren()
                .child(IKey.str("\u00a7f" + label).asWidget().marginBottom(1))
                .child(makeChart(series, unit));
    }

    private LineChartWidget makeChart(Supplier<List<Double>> series, String unit) {
        // Sync-handler construction copied from GT's Tesla Tower chart; the
        // null slots are the client->server setter (read-only chart) and the
        // deserializer hook we don't need. TESLA_TOWER_CHART is GT's
        // purpose-built electricity-chart theme: bright green line,
        // near-white min/max labels on a dark plot background.
        return new LineChartWidget()
                .syncHandler(new GenericListSyncHandler<>(
                        series,
                        null,
                        PacketBuffer::readDouble,
                        PacketBuffer::writeDouble,
                        Double::equals,
                        null))
                .widgetTheme(GTWidgetThemes.TESLA_TOWER_CHART)
                .background(new Rectangle().color(CHART_BG)) // explicit bg wins over the theme's purple
                .lowerBoundAlwaysZero()
                .lineWidth(2)
                .chartUnit(unit)
                .formatter(new DecimalFormat("#,##0"))
                .size(CHART_WIDTH, CHART_HEIGHT);
    }

    private LongSyncValue reg(PanelSyncManager syncManager, String name, java.util.function.LongSupplier getter) {
        LongSyncValue value = new LongSyncValue(getter);
        syncManager.syncValue(name, 0, value);
        return value;
    }

    private StringSyncValue regStr(PanelSyncManager syncManager, String name, Supplier<String> getter) {
        StringSyncValue value = new StringSyncValue(getter);
        syncManager.syncValue(name, 0, value);
        return value;
    }

    private static String fmt(long v) {
        return NumberFormatUtil.formatNumber(v);
    }
}
