package com.hcrobotics.performance.collector;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;

import androidx.annotation.NonNull;

import com.hcrobotics.performance.R;
import com.hcrobotics.performance.internal.Units;
import com.hcrobotics.performance.model.Metric;
import com.hcrobotics.performance.model.MetricSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports device RAM usage.
 *
 * <h2>What Android permits, and what it does not</h2>
 * Whole-device memory is freely readable through
 * {@link ActivityManager.MemoryInfo}: total RAM, available RAM, and the
 * threshold below which the system starts killing background processes.
 *
 * <p><b>Per-application memory is not.</b> Since Android 8.0,
 * {@code getRunningAppProcesses()} returns only the calling app's own process,
 * and {@code /proc} is no longer world-readable. A Windows-style "which app is
 * using 300 MB" table is therefore impossible for a normal app on a modern
 * device — not difficult, impossible. Any app appearing to show one is either a
 * system app, holds privileged permissions, or is guessing.</p>
 *
 * <p>This collector reports what is real: device-wide figures, plus this app's
 * own footprint, which IS readable and is genuinely useful when diagnosing the
 * app itself.</p>
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class MemoryCollector {

    /** Utility class; never instantiated. */
    private MemoryCollector() {
        throw new AssertionError("MemoryCollector is a utility class.");
    }

    /**
     * Reads current memory state.
     *
     * @param context any context
     * @return the Memory section of the report
     */
    @NonNull
    public static MetricSection collect(@NonNull Context context) {
        final List<Metric> metrics = new ArrayList<>();

        final ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager != null) {
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(info);

            final long used = info.totalMem - info.availMem;

            metrics.add(new Metric(
                    context.getString(R.string.perf_memory_used),
                    Units.usedOfTotal(used, info.totalMem),
                    Units.percent(used, info.totalMem),
                    context.getString(R.string.perf_memory_available,
                            Units.bytes(info.availMem))));

            metrics.add(new Metric(
                    context.getString(R.string.perf_memory_total),
                    Units.bytes(info.totalMem)));

            /*
             * The low-memory threshold is the level at which the system begins
             * killing background processes. Showing it turns "available memory"
             * from a bare number into something interpretable: 400 MB free is
             * comfortable on one device and about to start killing apps on
             * another, and the threshold is what distinguishes the two.
             */
            metrics.add(new Metric(
                    context.getString(R.string.perf_memory_threshold),
                    Units.bytes(info.threshold),
                    Metric.NO_PERCENT,
                    context.getString(R.string.perf_memory_threshold_detail)));

            metrics.add(new Metric(
                    context.getString(R.string.perf_memory_low),
                    context.getString(info.lowMemory
                            ? R.string.perf_yes
                            : R.string.perf_no)));
        }

        // ---- This app's own footprint ---------------------------------------
        // Readable because it is our own process. Useful when the question is
        // "is OUR app the problem?", which device-wide figures cannot answer.
        final Runtime runtime = Runtime.getRuntime();
        final long heapUsed = runtime.totalMemory() - runtime.freeMemory();

        metrics.add(new Metric(
                context.getString(R.string.perf_memory_app_heap),
                Units.usedOfTotal(heapUsed, runtime.maxMemory()),
                Units.percent(heapUsed, runtime.maxMemory()),
                context.getString(R.string.perf_memory_app_heap_detail)));

        final Debug.MemoryInfo debugInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(debugInfo);
        // getTotalPss() is in kilobytes.
        metrics.add(new Metric(
                context.getString(R.string.perf_memory_app_pss),
                Units.bytes(debugInfo.getTotalPss() * 1024L),
                Metric.NO_PERCENT,
                context.getString(R.string.perf_memory_app_pss_detail)));

        return new MetricSection(
                context.getString(R.string.perf_section_memory),
                R.drawable.perf_ic_memory,
                metrics);
    }
}
