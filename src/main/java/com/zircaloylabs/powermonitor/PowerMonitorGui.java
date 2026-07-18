package com.zircaloylabs.powermonitor;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
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
        LongSyncValue lineLoss = reg(syncManager, "pm_lineloss", b::getLineLossEUt);
        LongSyncValue peakDemand = reg(syncManager, "pm_peakdemand", b::getPeakDemandEUt);
        LongSyncValue peakDrain = reg(syncManager, "pm_peakdrain", b::getPeakDeficitEUt);
        LongSyncValue genCount = reg(syncManager, "pm_gencount", b::getGeneratorCount);
        LongSyncValue machCount = reg(syncManager, "pm_machcount", b::getMachineCount);
        LongSyncValue bufCount = reg(syncManager, "pm_bufcount", b::getBufferCount);
        LongSyncValue cables = reg(syncManager, "pm_cables", b::getLastCablesVisited);
        LongSyncValue multiCountSync = reg(syncManager, "pm_multis", b::getMultiblockCount);
        LongSyncValue xfmrCount = reg(syncManager, "pm_xfmr", b::getTransformerCount);
        LongSyncValue topEUt = reg(syncManager, "pm_topeut", b::getTopConsumerEUt);
        LongSyncValue status = reg(syncManager, "pm_status", b::getSupplyStatus);
        LongSyncValue storedEta = reg(syncManager, "pm_storedeta", b::getStoredEtaSeconds);
        LongSyncValue truncated = reg(syncManager, "pm_trunc", () -> b.isNetworkLargerThanTierSupports() ? 1L : 0L);

        StringSyncValue top0 = regStr(syncManager, "pm_top0", () -> b.getTopConsumerLine(0));
        StringSyncValue top1 = regStr(syncManager, "pm_top1", () -> b.getTopConsumerLine(1));
        StringSyncValue top2 = regStr(syncManager, "pm_top2", () -> b.getTopConsumerLine(2));
        StringSyncValue schedFull = regStr(syncManager, "pm_schedfull", b::getFuelScheduleFullBurn);
        StringSyncValue schedCur = regStr(syncManager, "pm_schedcur", b::getFuelScheduleCurrent);
        StringSyncValue deadBuffer = regStr(syncManager, "pm_deadbuf", b::getDeadBufferWarning);
        StringSyncValue outage0 = regStr(syncManager, "pm_outage0", () -> b.getOutageSummary(0));
        StringSyncValue outage1 = regStr(syncManager, "pm_outage1", () -> b.getOutageSummary(1));

        // ================= POWER =================
        section(column, "POWER",
                "\u00a7fEU/t\u00a77 = energy units per game tick (20 ticks = 1s).",
                "\u00a7fDemand\u00a77: what running recipes want right now.",
                "\u00a7fDelivered\u00a77: what machines actually received",
                "\u00a77(always a bit less than generation -- cable loss).",
                "\u00a7fCapacity\u00a77: rated maximums, not current output.");

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
            String s = "\u00a77Generation: \u00a7a" + fmt(g) + "\u00a7r";
            if (m > 0) {
                s += " / " + fmt(m) + " EU/t (" + (100L * g / m) + "%)";
            } else {
                s += " EU/t";
            }
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

        row(column, () -> {
            long loss = lineLoss.getLongValue();
            long g = gen.getLongValue();
            if (loss <= 0 || g <= 0) {
                return "";
            }
            long pct = 100L * loss / g;
            String c = pct >= 10 ? "\u00a7e" : "\u00a7f";
            return "\u00a77Line loss: " + c + "~" + fmt(loss) + " EU/t\u00a77 (" + c + pct + "%\u00a77)";
        });

        divider(column);

        // ================= STORAGE =================
        section(column, "STORAGE",
                "\u00a77Battery buffers store EU in their \u00a7fbattery items\u00a77.",
                "\u00a77Output rate = tier voltage \u00d7 battery count --",
                "\u00a7fno batteries = dead output side\u00a77, a classic trap.",
                "\u00a77Buffers idle between ~1/3 and ~2/3 internal charge",
                "\u00a77by design, so small flows there are normal.");

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
        section(column, "FUEL",
                "\u00a77Fuel sits in \u00a7feach generator's own tank/slot\u00a77 --",
                "\u00a77not a shared pool. As each one runs dry, network",
                "\u00a77capacity steps down: \u00a7f128\u21921h05m\u00a77 means \u00a7f128 EU/t",
                "\u00a7funtil 1h05m\u00a77, then the next step takes over.");

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
        section(column, "NETWORK",
                "\u00a77Everything electrically connected through this cable,",
                "\u00a77including across transformers and battery buffers.",
                "\u00a7fLine loss\u00a77 scales with cable length and quality --",
                "\u00a77shorter runs and better cables waste less EU.",
                "\u00a7fPeak drain\u00a77: fastest the buffers have discharged.");

        row(column, () -> {
            String s = "\u00a7f" + genCount.getLongValue() + "\u00a77 gen \u00b7 \u00a7f"
                    + machCount.getLongValue() + "\u00a77 machines \u00b7 \u00a7f" + multiCountSync.getLongValue()
                    + "\u00a77 multis \u00b7 \u00a7f" + bufCount.getLongValue() + "\u00a77 buffers";
            long x = xfmrCount.getLongValue();
            if (x > 0) {
                s += " \u00b7 \u00a7f" + x + "\u00a77 xfmr";
            }
            return s + " \u00b7 \u00a7f" + cables.getLongValue() + "\u00a77 cables";
        });

        row(column, () -> {
            String line = top0.getStringValue();
            return line.isEmpty() ? "\u00a77Top draw: \u00a7fnone" : "\u00a77Top draw: \u00a7f" + line;
        });
        row(column, () -> {
            String line = top1.getStringValue();
            return line.isEmpty() ? "" : "\u00a77         \u00a7f" + line;
        });
        row(column, () -> {
            String line = top2.getStringValue();
            return line.isEmpty() ? "" : "\u00a77         \u00a7f" + line;
        });

        row(column, () -> {
            String s = "\u00a77This cable: \u00a7f" + fmt(cable.getLongValue()) + "\u00a77 EU/t max  Peak demand: \u00a7f"
                    + fmt(peakDemand.getLongValue());
            if (bufCap.getLongValue() > 0) { // "drain" is storage drain by definition -- meaningless without storage
                s += "\u00a77  Peak drain: \u00a7f" + fmt(peakDrain.getLongValue());
            }
            return s;
        });

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
        column.child(new ButtonWidget<>()
                .overlay(IKey.str("\u00a7fack"))
                .size(28, 11)
                .tooltipStatic(t -> t.addLine("\u00a77Acknowledge outages (clears the log)"))
                .syncHandler(new InteractionSyncHandler().setOnMousePressed(mouseData -> {
                    if (!mouseData.isClient()) {
                        b.acknowledgeOutages();
                    }
                }))
                .setEnabledIf(w -> !outage0.getStringValue().isEmpty())
                .marginBottom(2));

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

    private void section(Flow column, String title, String... tooltipLines) {
        com.cleanroommc.modularui.widget.Widget<?> header = IKey.str("\u00a76\u00a7l" + title).asWidget();
        if (tooltipLines.length > 0) {
            header.tooltipStatic(t -> {
                for (String line : tooltipLines) {
                    t.addLine(line);
                }
            });
        }
        column.child(header.marginBottom(2));
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
