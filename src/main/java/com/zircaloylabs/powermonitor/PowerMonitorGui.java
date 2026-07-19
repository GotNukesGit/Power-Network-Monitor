package com.zircaloylabs.powermonitor;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widget.ParentWidget;
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
    private static final int CONTENT_WIDTH = (CHART_WIDTH + 54) * 2 + CHART_GAP; // plot + live-value gutter, x2
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
        LongSyncValue buf = reg(syncManager, "pm_buf", b::getBatteryOnlyEU); // gutter matches the batteries-only series
        LongSyncValue bufCap = reg(syncManager, "pm_bufcap", b::getLiveBufferCapacityEU);
        LongSyncValue bat = reg(syncManager, "pm_bat", b::getBatteryEU);
        LongSyncValue batCap = reg(syncManager, "pm_batcap", b::getBatteryCapacityEU);
        LongSyncValue internal = reg(syncManager, "pm_internal", b::getInternalEU);
        LongSyncValue internalCap = reg(syncManager, "pm_internalcap", b::getInternalCapacityEU);
        LongSyncValue voltage = reg(syncManager, "pm_voltage", b::getAnchorVoltage);
        LongSyncValue relayToll = reg(syncManager, "pm_relaytoll", b::getRelayTollEUt);
        LongSyncValue supplyLines = reg(syncManager, "pm_supplylines", () -> (long) b.getSupplyLines());
        LongSyncValue secEmpty = reg(syncManager, "pm_secempty", b::getSecondsToEmpty);
        LongSyncValue secFull = reg(syncManager, "pm_secfull", b::getSecondsToFull);
        LongSyncValue fuel = reg(syncManager, "pm_fuel", b::getLimitingPoolEU); // gutter matches the limiting-pool series
        StringSyncValue fuelTitle = regStr(syncManager, "pm_fueltitle", b::getFuelChartTitle);
        final net.minecraft.entity.player.EntityPlayer guiPlayer = data.getPlayer();
        dbgSync = new com.cleanroommc.modularui.value.sync.BooleanSyncValue(b::isDebugMode, v -> {});
        syncManager.syncValue("pm_dbg", dbgSync);
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
        StringSyncValue res0 = regStr(syncManager, "pm_res0", () -> b.getFuelRow(0));
        StringSyncValue res1 = regStr(syncManager, "pm_res1", () -> b.getFuelRow(1));
        StringSyncValue res2 = regStr(syncManager, "pm_res2", () -> b.getFuelRow(2));
        StringSyncValue res3 = regStr(syncManager, "pm_res3", () -> b.getFuelRow(3));
        StringSyncValue res4 = regStr(syncManager, "pm_res4", () -> b.getFuelRow(4));
        StringSyncValue res5 = regStr(syncManager, "pm_res5", () -> b.getFuelRow(5));
        StringSyncValue cycles = regStr(syncManager, "pm_cycles", b::getCyclesLine);
        StringSyncValue outage0 = regStr(syncManager, "pm_outage0", () -> b.getOutageSummary(0));
        StringSyncValue outage1 = regStr(syncManager, "pm_outage1", () -> b.getOutageSummary(1));

        // ================= POWER =================
        section(column, "POWER",
                "\u00a7fEU/t\u00a77 = energy units per game tick (20 ticks = 1s).",
                "\u00a7fDemand\u00a77: what running recipes want right now.",
                "\u00a7fDelivered\u00a77: what machines actually received",
                "\u00a77(always a bit less than generation -- cable loss).",
                "\u00a7fCapacity\u00a77: rated maximums, not current output.",
                "\u00a77Amps are computed at \u00a7fthis cable's voltage\u00a77.",
                "",
                "\u00a76Output loss\u00a77: every GT emitter pays \u00a7fV + 2^(tier-1)\u00a77 from",
                "\u00a77its buffer to put \u00a7fV\u00a77 on the wire (an LV emitter pays 33 per 32).",
                "\u00a77Generators cover their toll with \u00a7fextra fuel\u00a77; buffers and",
                "\u00a77transformers have no fuel, so their toll drains \u00a7fstorage\u00a77 --",
                "\u00a77shown here as Output loss. Every relay stage costs ~3%.");

        rowP(column, () -> {
            long d = demand.getLongValue();
            long delivered = cons.getLongValue();
            long u = unmet.getLongValue();
            long v = voltage.getLongValue();
            String s = "\u00a77Demand: \u00a7f" + fmt(d) + amps(d, v) + "\u00a7r \u00a77Delivered: \u00a7f" + fmt(delivered)
                    + amps(delivered, v) + "\u00a7r EU/t";
            if (u > 0) {
                s += "   \u00a7c\u26a0 Unmet: " + fmt(u);
            }
            return s;
        }, "power", b, guiPlayer);

        row(column, () -> {
            long g = gen.getLongValue();
            long m = maxGen.getLongValue();
            String s = "\u00a77Generation: \u00a7a" + fmt(g) + amps(g, voltage.getLongValue()) + "\u00a7r";
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

        rowP(column, () -> {
            long loss = lineLoss.getLongValue();
            long g = gen.getLongValue();
            if (loss <= 0 || g <= 0) {
                return "";
            }
            // Split total destroyed energy into cable dissipation vs the
            // emission toll paid by fuel-less relays (buffers/transformers
            // decrement V + 2^(tier-1) per amp to put V on the wire --
            // GT's output loss). Generators' own toll is fuel-paid and
            // intentionally absent from both figures.
            long toll = Math.min(loss, Math.max(0L, relayToll.getLongValue()));
            long line = loss - toll;
            long pct = 100L * loss / g;
            String pctText = pct == 0 ? "<1" : String.valueOf(pct);
            String c = pct >= 10 ? "\u00a7e" : "\u00a7f";
            long v = voltage.getLongValue();
            // The full ledger, closing by construction: total = gen - delivered.
            String s = "\u00a77Losses: " + c + "~" + fmt(loss) + " EU/t" + amps(loss, v) + "\u00a77 (cable ~"
                    + fmt(line);
            if (toll > 0) {
                s += " \u00b7 relay ~" + fmt(toll);
            }
            return s + ") (" + c + pctText + "%\u00a77)";
        }, "loss", b, guiPlayer);

        divider(column);

        // ================= STORAGE =================
        section(column, "STORAGE",
                "\u00a77Battery buffers store EU in their \u00a7fbattery items\u00a77.",
                "\u00a77Output rate = tier voltage \u00d7 battery count --",
                "\u00a7fno batteries = dead output side\u00a77, a classic trap.",
                "\u00a77Buffers idle between ~1/3 and ~2/3 internal charge",
                "\u00a77by design, so small flows there are normal.");

        rowP(column, () -> {
            long cap = bufCap.getLongValue();
            if (cap <= 0) {
                return "\u00a7fNo storage on network";
            }
            long batteries = bat.getLongValue();
            long batteriesCap = Math.max(1L, batCap.getLongValue());
            String s = "\u00a77Charge: \u00a7f" + fmt(batteries) + "\u00a7r / " + fmt(batteriesCap) + " EU batteries ("
                    + (100L * batteries / batteriesCap) + "%) \u00a78tank " + fmt(internal.getLongValue()) + "/"
                    + fmt(internalCap.getLongValue());
            long e = secEmpty.getLongValue();
            long f = secFull.getLongValue();
            if (e >= 0) {
                s += "  \u00a7c~" + PowerMonitorCover.formatSeconds(e) + " to empty";
            } else if (f >= 0) {
                s += "  \u00a7a~" + PowerMonitorCover.formatSeconds(f) + " to full";
            }
            return s;
        }, "charge", b, guiPlayer);

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
                long v = voltage.getLongValue();
                s = "\u00a77Flow: \u00a7ain " + fmt(in) + amps(in, v) + "\u00a7r / \u00a7cout " + fmt(out) + amps(out, v)
                        + "\u00a7r EU/t (net " + (net >= 0 ? "\u00a7a+" : "\u00a7c") + fmt(net) + "\u00a7r)";
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

        genTTField = new StringSyncValue[6];
        for (int i = 0; i < 6; i++) {
            final int gi = i;
            genTTField[i] = regStr(syncManager, "pm_gtt" + i, () -> b.getGenRowTooltip(gi));
        }
        rowPT(column, () -> {
            if (fuel.getLongValue() <= 0) {
                return maxGen.getLongValue() > 0 ? "\u00a7eNo fuel in generator tanks/slots"
                        : "\u00a7fNo generators on network";
            }
            String sched = schedFull.getStringValue();
            return "\u00a77Full burn:  " + (sched.isEmpty() ? "\u00a7fn/a" : "\u00a7f" + sched);
        }, "ladder", b, guiPlayer);

        rowP(column, () -> {
            if (fuel.getLongValue() <= 0) {
                return "";
            }
            String sched = schedCur.getStringValue();
            return sched.isEmpty() ? "\u00a77Current:    \u00a7fgenerators idle" : "\u00a77Current:    \u00a7f" + sched;
        }, "ladder", b, guiPlayer);

        // Connected reserves: tanks + pipes on the generators' plumbing.
        // In-machine fuel above is GUARANTEED runway; these lines assume the
        // plumbing keeps delivering -- and each reserve's measured trend
        // announces whether production is keeping it charged.
        // PANEL v2: per-generator rows live in the Full burn row's hover
        // card; only the shared pools stay on the panel face.
        StringSyncValue sh0 = regStr(syncManager, "pm_sh0", () -> b.getSharedRow(0));
        StringSyncValue sh1 = regStr(syncManager, "pm_sh1", () -> b.getSharedRow(1));
        rowP(column, () -> sh0.getStringValue(), "shared:0", b, guiPlayer);
        rowP(column, () -> sh1.getStringValue(), "shared:1", b, guiPlayer);

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
        rowP(column, () -> cycles.getStringValue(), "cycles", b, guiPlayer);
        StringSyncValue segLine = regStr(syncManager, "pm_segs", b::getSegmentLine);
        rowP(column, () -> segLine.getStringValue(), "segments", b, guiPlayer);
        // Network-scope peaks (moved from the per-segment cable row).
        row(column, () -> {
            String s = "\u00a77Peak demand: \u00a7f" + fmt(peakDemand.getLongValue());
            if (bufCap.getLongValue() > 0) { // storage drain meaningless without storage
                s += "\u00a77  Peak drain: \u00a7f" + fmt(peakDrain.getLongValue());
            }
            return s;
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
            // Per-SEGMENT rating only -- network-scope stats (peaks) live in
            // NETWORK. Field-confirmed confusion: "cable 256 / peak demand
            // 480" read as 187% overload when the flow actually splits
            // across supply lines.
            String s = "\u00a77This cable: \u00a7f" + fmt(cable.getLongValue()) + "\u00a77 EU/t max";
            long lines = supplyLines.getLongValue();
            if (lines > 1) {
                s += "  \u00a781 of " + lines + " supply lines";
            }
            return s;
        });

        // Status slot -- always present so the panel doesn't reflow. Tiered:
        // a network coasting on storage is NOT browning out yet, and the
        // wording says so (with the countdown from the buffers' own meters).
        row(column, () -> {
            long st = status.getLongValue();
            if (st >= 3) {
                return "\u00a7c\u26a0 BROWNOUT -- machines starving for power";
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
            return ""; // PANEL v2: healthy is silent -- only warnings speak
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
        // Ack (clears outage log) + diag (prints the full member/demand dump
        // to chat). Diag lives HERE, not only on sneak-screwdriver: vanilla
        // 1.7.10 skips block activation when sneaking with an item in hand
        // unless the item opts out, so the sneak gesture's delivery depends
        // on GT tool internals -- a GUI button cannot be eaten by vanilla
        // sneak semantics.
        column.child(Flow.row().coverChildren()
                .child(new ButtonWidget<>()
                        .overlay(IKey.str("\u00a7fack"))
                        .size(28, 11)
                        .tooltipStatic(t -> t.addLine("\u00a77Acknowledge outages (clears the log)"))
                        .syncHandler(new InteractionSyncHandler().setOnMousePressed(mouseData -> {
                            if (!mouseData.isClient()) {
                                b.acknowledgeOutages();
                            }
                        }))
                        .setEnabledIf(w -> !outage0.getStringValue().isEmpty())
                        .marginRight(4))
                .child(new ButtonWidget<>()
                        .overlay(IKey.str("\u00a78dbg"))
                        .size(24, 11)
                        .tooltipStatic(t -> t.addLine("\u00a77Toggle debug mode")
                                .addLine("\u00a78(shows diag + per-row \u00a7f?\u00a78 provenance buttons)"))
                        .syncHandler(new InteractionSyncHandler().setOnMousePressed(mouseData -> {
                            if (!mouseData.isClient()) {
                                b.toggleDebugMode();
                            }
                        })))
                .child(new ButtonWidget<>()
                        .background(com.cleanroommc.modularui.drawable.UITexture.EMPTY)
                        .hoverBackground(com.cleanroommc.modularui.drawable.UITexture.EMPTY)
                        .overlay(IKey.dynamic(() -> dbgSync != null && dbgSync.getBoolValue() ? "\u00a7f[diag]" : ""))
                        .size(34, 11)
                        .marginLeft(3)
                        .tooltipStatic(t -> t.addLine("\u00a77Print network diagnostics to chat")
                                .addLine("\u00a78(every member + demand source, with coordinates)"))
                        .syncHandler(new InteractionSyncHandler().setOnMousePressed(mouseData -> {
                            if (!mouseData.isClient() && guiPlayer != null && b.isDebugMode()) {
                                for (String line : b.buildDiagnostics()) {
                                    guiPlayer.addChatMessage(new net.minecraft.util.ChatComponentText(line));
                                }
                            }
                        })))
                .marginBottom(2));

        divider(column);

        // ================= CHARTS (2x2) =================

        if (b.getHistory() == null) {
            column.child(IKey.str("\u00a7f(Upgrade past ULV for history charts)").asWidget());
            return;
        }

        // ===== PANEL v2: one large multi-series chart + toggle legend =====
        MultiChartWidget chart = new MultiChartWidget();
        chart.add(new MultiChartWidget.Series("Demand", 0x55FF55, "EU/t", listSync(syncManager, "mc_dem", b::getChartDemand)));
        chart.add(new MultiChartWidget.Series("Generation", 0x55FFFF, "EU/t", listSync(syncManager, "mc_gen", b::getChartGeneration)));
        chart.add(new MultiChartWidget.Series("Losses", 0xFF5555, "EU/t", listSync(syncManager, "mc_loss", b::getChartLosses)));
        chart.add(new MultiChartWidget.Series("Batteries", 0xFFAA00, "EU", listSync(syncManager, "mc_bat", b::getChartBuffered)));
        int[] fuelColors = { 0xAA66DD, 0x66DDAA, 0xDDDD66, 0xDD66AA, 0x66AADD, 0xAADD66 };
        StringSyncValue[] poolNames = new StringSyncValue[6];
        for (int i = 0; i < 6; i++) {
            final int slot = i;
            poolNames[i] = regStr(syncManager, "mc_fpn" + i, () -> b.getFuelPoolName(slot));
            chart.add(new MultiChartWidget.Series("", fuelColors[i], "EU",
                    listSync(syncManager, "mc_fp" + i, () -> b.getFuelPoolSeries(slot))));
        }
        chart.size(CHART_WIDTH * 2 + CHART_GAP, 85);
        Flow legend = Flow.column().coverChildren();
        for (int i = 0; i < chart.getSeries().size(); i++) {
            final int idx = i;
            final MultiChartWidget.Series ser = chart.getSeries().get(i);
            legend.child(new ButtonWidget<>()
                    .background(com.cleanroommc.modularui.drawable.UITexture.EMPTY)
                    .hoverBackground(com.cleanroommc.modularui.drawable.UITexture.EMPTY)
                    .overlay(IKey.dynamic(() -> {
                        String nm = idx >= 4 ? poolNames[idx - 4].getStringValue() : ser.name;
                        if (nm.isEmpty()) {
                            return "";
                        }
                        String dot = chart.isEnabled(idx) ? "\u25a0 " : "\u25a1 ";
                        return colorHex(ser.color) + dot + "\u00a77" + nm + " \u00a7f"
                                + compact(chart.liveOf(idx));
                    }))
                    .size(76, 9)
                    .marginBottom(1)
                    .onUpdateListener(w -> {
                        if (w.isHovering()) {
                            chart.setLegendHover(idx);
                        } else if (!w.isHovering()) {
                            // release only our own claim
                        }
                    })
                    .syncHandler(new InteractionSyncHandler().setOnMousePressed(md -> {
                        if (md.isClient()) {
                            chart.toggle(idx);
                        }
                    })));
        }
        legend.onUpdateListener(w -> {
            boolean any = false;
            for (com.cleanroommc.modularui.api.widget.IWidget c : w.getChildren()) {
                if (c.isHovering()) {
                    any = true;
                }
            }
            if (!any) {
                chart.setLegendHover(-1);
            }
        });
        column.child(Flow.row().coverChildren()
                .child(chart.marginRight(4))
                .child(legend));
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

    private static GenericListSyncHandler<Double> listSync(PanelSyncManager sm, String key,
            Supplier<List<Double>> src) {
        GenericListSyncHandler<Double> h = new GenericListSyncHandler<>(src, null, PacketBuffer::readDouble,
                PacketBuffer::writeDouble, Double::equals, null);
        sm.syncValue(key, h);
        return h;
    }

    private static String colorHex(int c) {
        // nearest MC color code for the legend dot
        return "\u00a7f";
    }

    /** Compact number for chart axis labels: 1.2M, 34k, 487. */
    public static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000) return String.format("%.1fB", v / 1_000_000_000.0);
        if (a >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (a >= 10_000) return String.format("%.0fk", v / 1_000.0);
        return String.format("%.0f", v);
    }

    private void row(Flow column, Supplier<String> text) {
        column.child(IKey.dynamic(text::get).asWidget().marginBottom(2));
    }

    /** rowP plus a hover card: the per-generator fuel rows, live. */
    private void rowPT(Flow column, Supplier<String> text, String provKey, PowerMonitorCoverBehavior b,
            net.minecraft.entity.player.EntityPlayer player) {
        column.child(Flow.row().coverChildren().marginBottom(2)
                .child(IKey.dynamic(text::get).asWidget()
                        .tooltipStatic(t -> {
                            t.addLine(IKey.str("\u00a77Per-generator tanks:"));
                            for (int i = 0; i < 6; i++) {
                                final int gi = i;
                                t.addLine(IKey.dynamic(() -> genTTField[gi].getStringValue()));
                            }
                        }))
                .child(provButton(provKey, b, player)));
    }

    /** Row with a per-line provenance button: click prints THIS row's math to chat. */
    private com.cleanroommc.modularui.value.sync.BooleanSyncValue dbgSync;
    private StringSyncValue[] genTTField;

    private void rowP(Flow column, Supplier<String> text, String provKey, PowerMonitorCoverBehavior b,
            net.minecraft.entity.player.EntityPlayer player) {
        column.child(Flow.row().coverChildren().marginBottom(2)
                .child(IKey.dynamic(text::get).asWidget())
                .child(provButton(provKey, b, player)));
    }

    private com.cleanroommc.modularui.widget.Widget<?> provButton(String provKey, PowerMonitorCoverBehavior b,
            net.minecraft.entity.player.EntityPlayer player) {
        final Supplier<String> text = () -> "x"; // non-empty: gating below uses debug only
        return new ButtonWidget<>()
                        .background(com.cleanroommc.modularui.drawable.UITexture.EMPTY)
                        .hoverBackground(com.cleanroommc.modularui.drawable.UITexture.EMPTY)
                        .overlay(IKey.dynamic(() -> dbgSync != null && dbgSync.getBoolValue() && !text.get().isEmpty() ? "\u00a78?" : ""))
                        .size(7, 9)
                        .marginLeft(3)
                        .tooltipStatic(t -> t.addLine("\u00a77Show this row's math in chat \u00a78(debug mode)"))
                        .syncHandler(new InteractionSyncHandler().setOnMousePressed(mouseData -> {
                            if (!mouseData.isClient() && player != null && b.isDebugMode()) {
                                for (String line : b.buildProvenance(provKey)) {
                                    player.addChatMessage(new net.minecraft.util.ChatComponentText(line));
                                }
                            }
                        }));
    }

    private void divider(Flow column) {
        column.child(new Rectangle().color(DIVIDER_COLOR).asWidget().size(CONTENT_WIDTH, 1)
                .marginTop(1).marginBottom(3));
    }

    /**
     * One chart cell: title (states the unit ONCE), plot with our own axis
     * labels, and a live value to the right.
     *
     * The widget's built-in min/max text is disabled (renderMinMaxText
     * false) because it string-concatenates the raw double with the unit --
     * its formatter field is never applied to that text (verified in GT
     * source, line 239: renderer.draw(maxValue + chartUnit)), which is where
     * "5.30739E7 EU" came from. We overlay our own: axis bounds in dim gray
     * (clearly secondary, can't be mistaken for a live reading), unit-free
     * since the title already said it; the LIVE value sits to the right of
     * the plot in bright white.
     */
    private Flow chartCell(String title, Supplier<List<Double>> series, LongSyncValue realtime) {
        return chartCell(title, series, realtime, true);
    }

    /**
     * zeroAnchored=true: flow charts (Demand/Generation) -- their language is
     * steps-from-zero and headroom. zeroAnchored=false: stock charts
     * (Batteries/Fuel) -- WINDOWED min..max of visible history so a 1% drift
     * on a huge axis fills the frame and the slope becomes readable; both
     * axis labels show true values, so the window's narrowness self-declares.
     */
    private Flow chartCell(String title, Supplier<List<Double>> series, LongSyncValue realtime, boolean zeroAnchored) {
        return chartCell(IKey.str("\u00a7f" + title), series, realtime, zeroAnchored);
    }

    private Flow chartCell(IKey titleKey, Supplier<List<Double>> series, LongSyncValue realtime, boolean zeroAnchored) {
        GenericListSyncHandler<Double> handler = new GenericListSyncHandler<>(
                series, null, PacketBuffer::readDouble, PacketBuffer::writeDouble, Double::equals, null);
        LineChartWidget chart = new LineChartWidget()
                .syncHandler(handler)
                .widgetTheme(GTWidgetThemes.TESLA_TOWER_CHART)
                .background(new Rectangle().color(CHART_BG)) // explicit bg wins over the theme's purple
                .lineWidth(2)
                .renderMinMaxText(false)
                .size(CHART_WIDTH, CHART_HEIGHT);
        if (zeroAnchored) {
            chart.lowerBoundAlwaysZero();
        }
        ParentWidget<?> plot = new ParentWidget<>()
                .size(CHART_WIDTH, CHART_HEIGHT)
                .child(chart)
                .child(IKey.dynamic(() -> "\u00a78" + fmt(seriesMax(handler))).asWidget().top(1).left(2))
                .child(zeroAnchored ? IKey.str("\u00a780").asWidget().bottom(1).left(2)
                        : IKey.dynamic(() -> "\u00a78" + fmt(seriesMin(handler))).asWidget().bottom(1).left(2));
        return Flow.column().coverChildren()
                .child(titleKey.asWidget().marginBottom(1))
                .child(Flow.row().coverChildren()
                        .child(plot)
                        .child(IKey.dynamic(() -> "\u00a7f" + fmt(realtime.getLongValue())).asWidget()
                                .marginLeft(4).verticalCenter()));
    }

    private static long seriesMin(GenericListSyncHandler<Double> handler) {
        List<Double> list = handler.getValue();
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        double min = Double.MAX_VALUE;
        for (Double d : list) {
            if (d != null && d < min) {
                min = d;
            }
        }
        return min == Double.MAX_VALUE ? 0L : Math.round(min);
    }

    /** Max of the synced series (the chart's effective upper axis bound with lowerBoundAlwaysZero). */
    private static long seriesMax(GenericListSyncHandler<Double> handler) {
        List<Double> list = handler.getValue();
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        double max = 0;
        for (Double d : list) {
            if (d != null && d > max) {
                max = d;
            }
        }
        return Math.round(max);
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

    /** " (0.94A)" at this cable's voltage; empty when voltage is unknown. */
    private static String amps(long eut, long voltage) {
        if (voltage <= 0) {
            return "";
        }
        return "\u00a77 (" + String.format("%.2f", eut / (double) voltage) + "A)";
    }
}
