package com.zircaloylabs.powermonitor;

/**
 * Voltage/capability tiers for the Power Monitor cover.
 *
 * Voltage limits mirror GT's own tier voltage table (GTValues.V[]) so the
 * "overvolts and dies if the cable exceeds my tier" rule is the SAME rule
 * that already governs cables/transformers -- not an invented restriction.
 *
 * historySeconds / maxTrackedNodes are the "soft" progression lever: they
 * gate how much of the dashboard is actually usable at each tier, same
 * spirit as GT gating circuit complexity by tier.
 */
public enum PowerMonitorTier {

    ULV(8L, 16, 0), // no history at ULV -- live snapshot only
    LV(32L, 64, 600), // 10 min
    MV(128L, 256, 1800), // 30 min
    HV(512L, 1024, 3600), // 1 hr
    EV(2048L, Integer.MAX_VALUE, 7200), // 2 hr, uncapped node count
    IV(8192L, Integer.MAX_VALUE, 7200),
    LuV(32768L, Integer.MAX_VALUE, 7200),
    ZPM(131072L, Integer.MAX_VALUE, 7200),
    UV(524288L, Integer.MAX_VALUE, 7200);

    /** Max voltage (EU) this tier can safely have present on its attached cable. */
    public final long maxSafeVoltage;

    /** Max number of network nodes this tier will enumerate before giving up (perf/lag guard). */
    public final int maxTrackedNodes;

    /** Rolling history window, in seconds, at 1 Hz sampling. 0 = no history, live values only. */
    public final int historySeconds;

    PowerMonitorTier(long maxSafeVoltage, int maxTrackedNodes, int historySeconds) {
        this.maxSafeVoltage = maxSafeVoltage;
        this.maxTrackedNodes = maxTrackedNodes;
        this.historySeconds = historySeconds;
    }

    public boolean hasHistory() {
        return historySeconds > 0;
    }

    public PowerMonitorTier next() {
        int i = this.ordinal();
        PowerMonitorTier[] all = values();
        return i + 1 < all.length ? all[i + 1] : this;
    }
}
