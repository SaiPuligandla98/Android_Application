package com.hcrobotics.performance.collector;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import androidx.annotation.NonNull;

import com.hcrobotics.performance.R;
import com.hcrobotics.performance.internal.Units;
import com.hcrobotics.performance.model.Metric;
import com.hcrobotics.performance.model.MetricSection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports storage capacity and usage.
 *
 * <h2>Why {@link StatFs} rather than {@code File.getFreeSpace()}</h2>
 * {@code File.getFreeSpace()} reports the raw free blocks on the filesystem,
 * including space reserved for the system that an app can never use.
 * {@code StatFs.getAvailableBytes()} reports what is genuinely available to
 * this app — which is the number that matters when the question is "can this
 * update download?".
 *
 * <p>The two differ by several hundred megabytes on a typical device, and it is
 * always the reserved portion that makes the difference between an install
 * succeeding and failing.</p>
 *
 * <h2>Why both internal and external are reported</h2>
 * "Internal" here means the private data partition — where the app's own files
 * and downloaded APKs live. "External" on a modern device is usually the same
 * physical storage presented differently, but on hardware with a real SD card
 * they diverge, and a fleet device with a card is exactly where that matters.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class StorageCollector {

    /** Utility class; never instantiated. */
    private StorageCollector() {
        throw new AssertionError("StorageCollector is a utility class.");
    }

    /**
     * Reads current storage state.
     *
     * @param context any context
     * @return the Storage section of the report
     */
    @NonNull
    public static MetricSection collect(@NonNull Context context) {
        final List<Metric> metrics = new ArrayList<>();

        // ---- Internal (the data partition) ----------------------------------
        addVolume(context, metrics,
                Environment.getDataDirectory(),
                context.getString(R.string.perf_storage_internal));

        // ---- External, when it is a genuinely separate volume ---------------
        final File external = Environment.getExternalStorageDirectory();
        if (external != null && external.exists()
                && !external.getAbsolutePath().equals(
                        Environment.getDataDirectory().getAbsolutePath())) {
            addVolume(context, metrics, external,
                    context.getString(R.string.perf_storage_external));
        }

        // ---- This app's own footprint ---------------------------------------
        // Worth surfacing on a device that updates over the air: downloaded
        // APKs live here, and a device that has run out of room silently stops
        // being able to update itself.
        final long appBytes = directorySize(context.getFilesDir())
                + directorySize(context.getCacheDir());
        metrics.add(new Metric(
                context.getString(R.string.perf_storage_app),
                Units.bytes(appBytes),
                Metric.NO_PERCENT,
                context.getString(R.string.perf_storage_app_detail)));

        return new MetricSection(
                context.getString(R.string.perf_section_storage),
                R.drawable.perf_ic_storage,
                metrics);
    }

    /**
     * Adds a used/total reading for one storage volume.
     *
     * @param context any context
     * @param metrics list to append to
     * @param path    a directory on the volume
     * @param label   display name for the volume
     */
    private static void addVolume(@NonNull Context context,
                                  @NonNull List<Metric> metrics,
                                  @NonNull File path,
                                  @NonNull String label) {
        try {
            final StatFs stat = new StatFs(path.getAbsolutePath());
            final long total = stat.getTotalBytes();
            // Available, not free: excludes space reserved for the system that
            // an app can never actually use.
            final long available = stat.getAvailableBytes();
            final long used = total - available;

            metrics.add(new Metric(
                    label,
                    Units.usedOfTotal(used, total),
                    Units.percent(used, total),
                    context.getString(R.string.perf_storage_free, Units.bytes(available))));
        } catch (IllegalArgumentException e) {
            // The volume was unmounted between listing and reading it. Reporting
            // it as unavailable is better than failing the whole screen.
            metrics.add(new Metric(label, context.getString(R.string.perf_unavailable)));
        }
    }

    /**
     * Sums the size of a directory tree.
     *
     * <p>Recursive, but over the app's own private directories only, which hold
     * a downloaded APK and some caches — tens of files, not a filesystem walk.
     * Symlinks are not followed, since Android does not create them here.</p>
     *
     * @param directory the directory to measure; may be {@code null}
     * @return total bytes, or {@code 0} if unreadable
     */
    private static long directorySize(File directory) {
        if (directory == null || !directory.exists()) {
            return 0L;
        }
        if (directory.isFile()) {
            return directory.length();
        }
        final File[] children = directory.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (File child : children) {
            total += directorySize(child);
        }
        return total;
    }
}
