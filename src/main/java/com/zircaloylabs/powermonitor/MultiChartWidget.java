package com.zircaloylabs.powermonitor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.widget.Widget;

/**
 * Multi-series trend chart, Model v2 display doctrine: shapes always, numbers
 * on demand. Every enabled series renders NORMALIZED to its own min..max (the
 * chart compares shapes across units); hovering a trace -- or its legend entry
 * -- snaps the axis labels to THAT series' true min/max/live values in its own
 * unit. No shared axis exists because no honest one can.
 */
public class MultiChartWidget extends Widget<MultiChartWidget> implements Interactable {

    public static final class Series {

        final String name;
        final int color;
        final String unit;
        final GenericListSyncHandler<Double> data;
        boolean enabled = true;

        public Series(String name, int color, String unit, GenericListSyncHandler<Double> data) {
            this.name = name;
            this.color = color;
            this.unit = unit;
            this.data = data;
        }
    }

    private final List<Series> series = new ArrayList<>();
    /** Visible window in points (1 point = 1s for fuel series). Client display state. */
    /** -1 = none; set by trace proximity or legend hover (client-side only). */
    private int hovered = -1;
    private int legendHover = -1;


    public MultiChartWidget add(Series s) {
        series.add(s);
        return this;
    }

    public List<Series> getSeries() {
        return series;
    }

    public void setLegendHover(int idx) {
        this.legendHover = idx;
    }

    public void toggle(int idx) {
        if (idx >= 0 && idx < series.size()) {
            series.get(idx).enabled = !series.get(idx).enabled;
        }
    }

    public boolean isEnabled(int idx) {
        return idx >= 0 && idx < series.size() && series.get(idx).enabled;
    }

    /** Live (= newest recorded) value of a series, for legend text. */
    public double liveOf(int idx) {
        List<Double> d = idx >= 0 && idx < series.size() ? series.get(idx).data.getValue() : null;
        return d == null || d.isEmpty() ? 0 : d.get(d.size() - 1);
    }

    public String unitOf(int idx) {
        return idx >= 0 && idx < series.size() ? series.get(idx).unit : "";
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        super.draw(context, widgetTheme);
        int w = getArea().width, h = getArea().height;
        int mx = context.getMouseX() - getArea().x, my = context.getMouseY() - getArea().y;
        boolean inPlot = mx >= 0 && mx < w && my >= 0 && my < h;

        // Hover resolution: legend wins; else nearest trace within 5 px.
        int hov = legendHover;
        double bestDist = 6.0;
        // First pass: geometry per series (also finds trace hover).
        int n = series.size();
        double[][] norm = new double[n][];
        double[] mins = new double[n], maxs = new double[n];
        for (int i = 0; i < n; i++) {
            Series s = series.get(i);
            if (!s.enabled) {
                continue;
            }
            // Server-side windowing already sliced and min-max downsampled
            // this series; the client draws what arrives.
            List<Double> d = s.data.getValue();
            if (d == null || d.size() < 2) {
                continue;
            }
            double mn = Double.MAX_VALUE, mxv = -Double.MAX_VALUE;
            for (Double v : d) {
                if (v != null) {
                    mn = Math.min(mn, v);
                    mxv = Math.max(mxv, v);
                }
            }
            if (mxv - mn < 1e-9) {
                mxv = mn + 1; // flat series draws mid-height
            }
            mins[i] = mn;
            maxs[i] = mxv;
            double[] pts = new double[d.size()];
            double vm = Math.max(4, h * 0.12); // breathing room: no trace rides the frame edge
            for (int j = 0; j < d.size(); j++) {
                double v = d.get(j) == null ? mn : d.get(j);
                pts[j] = (h - vm) - ((v - mn) / (mxv - mn)) * (h - 2 * vm);
            }
            norm[i] = pts;
            if (inPlot && legendHover < 0) {
                int j = (int) Math.round((double) mx / Math.max(1, w - 1) * (pts.length - 1));
                j = Math.max(0, Math.min(pts.length - 1, j));
                double dist = Math.abs(pts[j] - my);
                if (dist < bestDist) {
                    bestDist = dist;
                    hov = i;
                }
            }
        }
        hovered = hov;

        // Second pass: draw dimmed non-hovered when something IS hovered.
        // MUI2 pre-translates GL to the widget origin (verified against
        // GT's LineChartWidget) -- draw LOCAL, no translate.
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);
        Tessellator tes = Tessellator.instance;
        for (int i = 0; i < n; i++) {
            if (norm[i] == null) {
                continue;
            }
            int c = series.get(i).color;
            int alpha = (hov >= 0 && hov != i) ? 60 : 255;
            tes.startDrawing(GL11.GL_LINE_STRIP);
            tes.setColorRGBA((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, alpha);
            double[] pts = norm[i];
            for (int j = 0; j < pts.length; j++) {
                tes.addVertex(1 + (double) j / (pts.length - 1) * (w - 2), pts[j], 0);
            }
            tes.draw();
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);

        // Hover axis: the hovered series' TRUE min/max/live, its unit.
        if (hov >= 0 && norm[hov] != null) {
            Series s = series.get(hov);
            List<Double> d = s.data.getValue();
            double live = d.get(d.size() - 1);
            Minecraft.getMinecraft().fontRenderer
                .drawString("\u00a78" + PowerMonitorGui.compact(maxs[hov]) + " " + s.unit, 2, 1, 0xFFFFFF);
            Minecraft.getMinecraft().fontRenderer
                .drawString("\u00a78" + PowerMonitorGui.compact(mins[hov]) + " " + s.unit, 2, h - 9, 0xFFFFFF);
            String liveText = "\u00a7f" + PowerMonitorGui.compact(live) + " " + s.unit;
            int tw = Minecraft.getMinecraft().fontRenderer.getStringWidth(liveText);
            Minecraft.getMinecraft().fontRenderer.drawString(liveText, w - tw - 2, 1, 0xFFFFFF);
        }
        GL11.glPopMatrix();
    }
}
