package com.zircaloylabs.powermonitor;

/**
 * Fixed-size ring buffer of (consumption, netDeficit) samples taken at 1 Hz.
 *
 * Tracks running max for both series so "peak over window" lookups are O(1)
 * instead of re-scanning the whole buffer every time the HUD redraws.
 *
 * Sized per-tier via PowerMonitorTier.historySeconds. A tier with
 * historySeconds == 0 should simply not allocate one of these (live-only
 * mode) -- see PowerMonitorCoverBehavior.
 */
public class RollingSampleBuffer {

    private final long[] consumptionSamples;
    private final long[] deficitSamples;
    private final long[] sampleTimestamps; // world time (ticks) at capture, for "peak at HH:MM" display
    private int writeIndex = 0;
    private int filledCount = 0;

    private long runningMaxConsumption = 0L;
    private long runningMaxConsumptionTimestamp = 0L;
    private long runningMaxDeficit = Long.MIN_VALUE;
    private long runningMaxDeficitTimestamp = 0L;

    public RollingSampleBuffer(int capacitySeconds) {
        if (capacitySeconds <= 0) {
            throw new IllegalArgumentException("RollingSampleBuffer requires capacitySeconds > 0; tier has no history, don't allocate one.");
        }
        this.consumptionSamples = new long[capacitySeconds];
        this.deficitSamples = new long[capacitySeconds];
        this.sampleTimestamps = new long[capacitySeconds];
    }

    /**
     * Record one sample. Call this once per second (not per tick) from the
     * cover's tick handler -- gate with a tick-counter modulo, GT covers
     * tick far more often than 1 Hz.
     *
     * @param consumption total EU/t drawn by machines on the network right now
     * @param generation  total EU/t produced by generators on the network right now
     * @param worldTime   current world total time, for timestamping the peak
     */
    public void record(long consumption, long generation, long worldTime) {
        long deficit = consumption - generation; // positive = net draw exceeding supply

        consumptionSamples[writeIndex] = consumption;
        deficitSamples[writeIndex] = deficit;
        sampleTimestamps[writeIndex] = worldTime;

        writeIndex = (writeIndex + 1) % consumptionSamples.length;
        if (filledCount < consumptionSamples.length) {
            filledCount++;
        }

        // Running max is cheap to maintain incrementally as long as we don't
        // need "max excluding the sample that's about to be overwritten" --
        // for a 2-hour window that staleness is fine; if precision matters,
        // recompute on overwrite of the previous max's slot (see note below).
        if (consumption > runningMaxConsumption) {
            runningMaxConsumption = consumption;
            runningMaxConsumptionTimestamp = worldTime;
        }
        if (deficit > runningMaxDeficit) {
            runningMaxDeficit = deficit;
            runningMaxDeficitTimestamp = worldTime;
        }

        // If the slot we just overwrote WAS the source of the current max,
        // the max is now stale (could be lower than reality allows, since
        // we don't know if a bigger value existed elsewhere). Full rescan
        // only in that edge case -- rare, and only costs O(n) occasionally
        // rather than every sample.
        if (needsRescanCheck()) {
            rescanMax();
        }
    }

    private boolean needsRescanCheck() {
        // Cheap heuristic: only worth checking once buffer is full (before that,
        // nothing has been overwritten yet, so max can't have gone stale).
        return filledCount == consumptionSamples.length;
    }

    private void rescanMax() {
        long maxC = 0L, maxD = Long.MIN_VALUE;
        long maxCTime = 0L, maxDTime = 0L;
        for (int i = 0; i < filledCount; i++) {
            if (consumptionSamples[i] > maxC) {
                maxC = consumptionSamples[i];
                maxCTime = sampleTimestamps[i];
            }
            if (deficitSamples[i] > maxD) {
                maxD = deficitSamples[i];
                maxDTime = sampleTimestamps[i];
            }
        }
        runningMaxConsumption = maxC;
        runningMaxConsumptionTimestamp = maxCTime;
        runningMaxDeficit = maxD;
        runningMaxDeficitTimestamp = maxDTime;
    }

    public long getPeakConsumption() {
        return runningMaxConsumption;
    }

    public long getPeakConsumptionTimestamp() {
        return runningMaxConsumptionTimestamp;
    }

    public long getPeakDeficit() {
        return runningMaxDeficit;
    }

    public long getPeakDeficitTimestamp() {
        return runningMaxDeficitTimestamp;
    }

    public void reset() {
        java.util.Arrays.fill(consumptionSamples, 0L);
        java.util.Arrays.fill(deficitSamples, 0L);
        java.util.Arrays.fill(sampleTimestamps, 0L);
        writeIndex = 0;
        filledCount = 0;
        runningMaxConsumption = 0L;
        runningMaxDeficit = Long.MIN_VALUE;
    }

    /**
     * Downsampled dual-series extraction for chart rendering: fills the two
     * lists (cleared first) with up to maxPoints values each, oldest-first,
     * covering the whole filled window. Each bucket keeps its MAX so short
     * spikes stay visible instead of being averaged away. Generation is
     * reconstructed as consumption - deficit (the two stored series).
     */
    public void downsampleInto(java.util.List<Double> consumptionOut, java.util.List<Double> generationOut,
            int maxPoints) {
        consumptionOut.clear();
        generationOut.clear();
        if (filledCount == 0 || maxPoints <= 0) {
            return;
        }
        int n = consumptionSamples.length;
        int start = (writeIndex - filledCount + n) % n;
        int bucketSize = Math.max(1, (filledCount + maxPoints - 1) / maxPoints);
        for (int b = 0; b < filledCount; b += bucketSize) {
            long maxC = Long.MIN_VALUE;
            long maxG = Long.MIN_VALUE;
            int end = Math.min(b + bucketSize, filledCount);
            for (int i = b; i < end; i++) {
                int idx = (start + i) % n;
                long c = consumptionSamples[idx];
                long g = c - deficitSamples[idx]; // generation = consumption - deficit
                if (c > maxC) maxC = c;
                if (g > maxG) maxG = g;
            }
            consumptionOut.add((double) maxC);
            generationOut.add((double) maxG);
        }
    }

    /** Returns samples oldest-first, for sparkline rendering. */
    public long[] getConsumptionHistoryOrdered() {
        long[] out = new long[filledCount];
        int start = (writeIndex - filledCount + consumptionSamples.length) % consumptionSamples.length;
        for (int i = 0; i < filledCount; i++) {
            out[i] = consumptionSamples[(start + i) % consumptionSamples.length];
        }
        return out;
    }
}
