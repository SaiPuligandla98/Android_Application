package com.hcrobotics.performance.collector;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.hcrobotics.performance.R;
import com.hcrobotics.performance.internal.Units;
import com.hcrobotics.performance.model.Metric;
import com.hcrobotics.performance.model.MetricSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports device identity, CPU topology and uptime.
 *
 * <h2>Why there is no CPU usage percentage here</h2>
 * A Windows Task Manager shows per-process CPU. Android has not permitted that
 * since 8.0: {@code /proc/stat} and other processes' {@code /proc} entries are
 * no longer world-readable, precisely because they leaked information about
 * what other apps were doing.
 *
 * <p>What remains readable is the CPU's <em>shape</em> — core count, supported
 * instruction sets, hardware name — which is genuinely useful when comparing
 * fleet devices, plus uptime. Rather than compute a plausible-looking CPU
 * figure from data that no longer exists, this collector reports what is real
 * and the screen states the limitation outright.</p>
 *
 * <p>Reading {@code /proc/self/stat} would give this app's own CPU time, but
 * that describes the monitoring, not the device, so it would mislead more than
 * it informs.</p>
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class SystemCollector {

    /** Utility class; never instantiated. */
    private SystemCollector() {
        throw new AssertionError("SystemCollector is a utility class.");
    }

    /**
     * Reads device and system information.
     *
     * @param context any context
     * @return the System section of the report
     */
    @NonNull
    public static MetricSection collect(@NonNull Context context) {
        final List<Metric> metrics = new ArrayList<>();

        metrics.add(new Metric(
                context.getString(R.string.perf_sys_device),
                Build.MANUFACTURER + " " + Build.MODEL,
                Metric.NO_PERCENT,
                Build.DEVICE));

        metrics.add(new Metric(
                context.getString(R.string.perf_sys_android),
                Build.VERSION.RELEASE,
                Metric.NO_PERCENT,
                context.getString(R.string.perf_sys_api, Build.VERSION.SDK_INT)));

        metrics.add(new Metric(
                context.getString(R.string.perf_sys_build),
                Build.DISPLAY));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != null) {
            // Only exposed from Android 12; before that there is no reliable
            // public way to name the chipset.
            metrics.add(new Metric(
                    context.getString(R.string.perf_sys_soc),
                    Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL));
        }

        metrics.add(new Metric(
                context.getString(R.string.perf_sys_cores),
                String.valueOf(Runtime.getRuntime().availableProcessors()),
                Metric.NO_PERCENT,
                context.getString(R.string.perf_sys_cores_detail)));

        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            metrics.add(new Metric(
                    context.getString(R.string.perf_sys_abi),
                    Build.SUPPORTED_ABIS[0],
                    Metric.NO_PERCENT,
                    String.join(", ", Build.SUPPORTED_ABIS)));
        }

        /*
         * Two different clocks, and the difference is the point.
         *
         *   elapsedRealtime() counts wall-clock time since boot, INCLUDING
         *   deep sleep.
         *   uptimeMillis()    counts only time the CPU was actually awake.
         *
         * A large gap between them means the device is spending most of its
         * life asleep - which is exactly what you want on battery, and exactly
         * what delays background update checks.
         */
        final long sinceBoot = SystemClock.elapsedRealtime();
        final long awake = SystemClock.uptimeMillis();

        metrics.add(new Metric(
                context.getString(R.string.perf_sys_uptime),
                Units.duration(sinceBoot),
                Metric.NO_PERCENT,
                context.getString(R.string.perf_sys_awake,
                        Units.duration(awake), Units.percent(awake, sinceBoot))));

        return new MetricSection(
                context.getString(R.string.perf_section_system),
                R.drawable.perf_ic_system,
                metrics);
    }
}
