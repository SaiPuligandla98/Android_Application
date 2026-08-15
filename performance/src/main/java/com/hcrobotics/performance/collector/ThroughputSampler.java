package com.hcrobotics.performance.collector;

import android.net.TrafficStats;
import android.os.SystemClock;

/**
 * Turns cumulative traffic counters into a live transfer rate.
 *
 * <h2>Why a sampler is needed at all</h2>
 * {@link TrafficStats} reports totals since boot — a number that only ever
 * climbs. "4.2 GB received" says nothing about whether anything is moving right
 * now.
 *
 * <p>The rate is the difference between two readings divided by the time
 * between them. That requires state, which is why this is an instance rather
 * than the static collectors used elsewhere in this module.</p>
 *
 * <h2>Why elapsed time is measured, not assumed</h2>
 * The refresh loop aims for one second, but a busy main thread can delay it.
 * Assuming exactly one second would report a rate inflated by however late the
 * sample was. {@link SystemClock#elapsedRealtime()} gives the interval that
 * actually passed.
 *
 * <p>{@code elapsedRealtime} rather than {@code currentTimeMillis} because it
 * cannot jump: a clock correction or timezone change mid-sample would otherwise
 * produce a wildly wrong rate, or a negative one.</p>
 *
 * <h2>The first sample</h2>
 * The first reading has nothing to compare against, so it establishes the
 * baseline and reports zero. Reporting the since-boot total as a one-second
 * rate would put a meaningless spike at the start of every graph.
 *
 * @author HC Robotics
 * @since 1.9.0
 */
public final class ThroughputSampler {

    /** Marks "no previous reading yet". */
    private static final long NO_SAMPLE = -1L;

    private long lastRxBytes = NO_SAMPLE;
    private long lastTxBytes = NO_SAMPLE;
    private long lastSampleAt = NO_SAMPLE;

    private long downloadBytesPerSecond;
    private long uploadBytesPerSecond;

    /**
     * Takes a reading and updates the current rates.
     *
     * <p>Call once per refresh. Rates are then available from
     * {@link #getDownloadBytesPerSecond()} and
     * {@link #getUploadBytesPerSecond()}.</p>
     */
    public void sample() {
        final long rx = TrafficStats.getTotalRxBytes();
        final long tx = TrafficStats.getTotalTxBytes();
        final long now = SystemClock.elapsedRealtime();

        // Some devices do not track traffic at all.
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            downloadBytesPerSecond = 0L;
            uploadBytesPerSecond = 0L;
            return;
        }

        if (lastSampleAt == NO_SAMPLE) {
            // First reading: establish the baseline and report nothing, rather
            // than reporting the since-boot total as a one-second rate.
            lastRxBytes = rx;
            lastTxBytes = tx;
            lastSampleAt = now;
            return;
        }

        final long elapsedMs = now - lastSampleAt;
        if (elapsedMs <= 0) {
            // Two samples within the same millisecond. Keeping the previous
            // rates is better than dividing by zero.
            return;
        }

        /*
         * Counters can go BACKWARDS - they are reset by a reboot, and some
         * devices reset them when the active interface changes. Clamping at
         * zero turns that into a momentary gap in the graph instead of a
         * enormous negative spike.
         */
        downloadBytesPerSecond = Math.max(0L, (rx - lastRxBytes) * 1000L / elapsedMs);
        uploadBytesPerSecond = Math.max(0L, (tx - lastTxBytes) * 1000L / elapsedMs);

        lastRxBytes = rx;
        lastTxBytes = tx;
        lastSampleAt = now;
    }

    /** @return current download rate in bytes per second */
    public long getDownloadBytesPerSecond() {
        return downloadBytesPerSecond;
    }

    /** @return current upload rate in bytes per second */
    public long getUploadBytesPerSecond() {
        return uploadBytesPerSecond;
    }

    /** @return the combined rate, which is what the graph plots */
    public long getTotalBytesPerSecond() {
        return downloadBytesPerSecond + uploadBytesPerSecond;
    }
}
