package com.hcrobotics.performance;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hcrobotics.performance.collector.BatteryCollector;
import com.hcrobotics.performance.collector.MemoryCollector;
import com.hcrobotics.performance.collector.NetworkCollector;
import com.hcrobotics.performance.collector.StorageCollector;
import com.hcrobotics.performance.collector.SystemCollector;
import com.hcrobotics.performance.collector.ThroughputSampler;
import com.hcrobotics.performance.internal.Units;
import com.hcrobotics.performance.model.MetricHistory;
import com.hcrobotics.performance.databinding.PerfActivityPerformanceBinding;
import com.hcrobotics.performance.model.Metric;
import com.hcrobotics.performance.model.MetricSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Live device performance report — memory, storage, battery, network, system.
 *
 * <h2>What this can and cannot show</h2>
 * This is the Android equivalent of Windows Task Manager's <em>Performance</em>
 * tab, not its <em>Processes</em> tab. Device-wide figures are readable; the
 * per-process table is not, and has not been since Android 8.0 removed
 * world-readable {@code /proc} and restricted
 * {@code getRunningAppProcesses()} to the caller's own process.
 *
 * <p>The screen says so explicitly at the bottom. Stating a limitation is far
 * better than quietly presenting a number that looks authoritative and is not.</p>
 *
 * <h2>Why the refresh is a posted Runnable rather than a timer</h2>
 * A {@link Handler} posting to the main looper stops naturally when the
 * Activity stops, cannot outlive it once callbacks are removed, and needs no
 * extra thread. Collection takes single-digit milliseconds — all of it is
 * reading system services, none of it is I/O — so it is safe on the main
 * thread and avoids the synchronisation a background thread would need.
 *
 * <h2>Why refreshing stops in onPause</h2>
 * A screen that is not visible must not be waking the CPU every two seconds.
 * That is exactly the behaviour a performance monitor should never exhibit
 * itself.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class PerformanceActivity extends AppCompatActivity {

    /**
     * How often the readings refresh, in milliseconds.
     *
     * <p>Two seconds is fast enough that memory and network figures visibly
     * respond to what the device is doing, and slow enough that the screen
     * stays readable rather than flickering.</p>
     */
    private static final long REFRESH_INTERVAL_MS = 1_000L;

    /**
     * How many samples the live graphs hold.
     *
     * <p>At one sample per second this is a rolling sixty-second window — long
     * enough to show a trend rather than a twitch, short enough that what is on
     * screen is still recognisably "now".</p>
     */
    private static final int HISTORY_SECONDS = 60;

    /** Rolling window of memory usage, as a percentage. */
    private final MetricHistory memoryHistory = new MetricHistory(HISTORY_SECONDS);

    /** Rolling window of total network throughput, in bytes per second. */
    private final MetricHistory networkHistory = new MetricHistory(HISTORY_SECONDS);

    /** Converts cumulative traffic counters into a live rate. */
    private final ThroughputSampler throughputSampler = new ThroughputSampler();

    /**
     * Fires the instant a network connects, disconnects or changes.
     *
     * <p>Polling alone would leave the screen showing "Wi-Fi" for up to a
     * second after the Wi-Fi dropped. A callback makes connectivity changes
     * appear immediately, which is what "dynamic" has to mean for a status
     * display — the one reading a user is most likely to be watching while they
     * deliberately toggle something.</p>
     *
     * <p>Callbacks arrive on a binder thread, so each one posts to the main
     * thread rather than touching views directly.</p>
     */
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    postImmediateRefresh();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    postImmediateRefresh();
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities capabilities) {
                    // Covers Wi-Fi to mobile hand-off and validation changes,
                    // neither of which raises onAvailable or onLost.
                    postImmediateRefresh();
                }
            };

    /** Whether the network callback is currently registered, so it is not
     *  unregistered twice — which throws. */
    private boolean networkCallbackRegistered = false;

    /** Posts the repeating refresh onto the main thread. */
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    /** Retained so the exact same instance can be cancelled in onPause. */
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshMetrics();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    /** Type-safe access to the views in {@code perf_activity_performance.xml}. */
    private PerfActivityPerformanceBinding binding;

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = PerfActivityPerformanceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.perfButtonBack.setOnClickListener(v -> finish());
        binding.perfSwipeRefresh.setOnRefreshListener(() -> {
            refreshMetrics();
            binding.perfSwipeRefresh.setRefreshing(false);
        });

        // Tint the graphs from the theme so they follow light and dark mode.
        final int traceColor = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary);
        binding.perfGraphMemory.setTraceColor(traceColor);
        binding.perfGraphNetwork.setTraceColor(traceColor);
        binding.perfGraphMemory.setHistory(memoryHistory);
        binding.perfGraphNetwork.setHistory(networkHistory);

        // Populate immediately so the screen is never briefly blank.
        refreshMetrics();
    }

    /**
     * Starts the repeating refresh and subscribes to connectivity changes.
     */
    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.post(refreshTask);
        registerNetworkCallback();
    }

    /**
     * Subscribes to connectivity changes so the network card updates the
     * instant something changes, rather than up to a second later.
     */
    private void registerNetworkCallback() {
        if (networkCallbackRegistered) {
            return;
        }
        final ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return;
        }
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (RuntimeException e) {
            // Registration can fail if too many callbacks are already active
            // process-wide. The one-second poll still covers it, so degrade
            // rather than crash a diagnostics screen.
            networkCallbackRegistered = false;
        }
    }

    /** Unsubscribes from connectivity changes. */
    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered) {
            return;
        }
        final ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Already unregistered; nothing to do.
            }
        }
        networkCallbackRegistered = false;
    }

    /**
     * Refreshes on the main thread, from a callback that arrives on a binder
     * thread.
     */
    private void postImmediateRefresh() {
        refreshHandler.post(() -> {
            if (binding != null && !isFinishing()) {
                refreshMetrics();
            }
        });
    }

    /**
     * Resolves a colour from the current theme.
     *
     * <p>Read from the theme rather than hard-coded so the graphs follow light
     * and dark mode, and pick up a host app's brand colour automatically.</p>
     *
     * @param attr a theme attribute such as {@code colorPrimary}
     * @return the resolved colour
     */
    private int resolveThemeColor(int attr) {
        final android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    /**
     * Stops refreshing as soon as the screen is no longer in front.
     *
     * <p>Without this the app would keep waking the CPU every two seconds while
     * sitting in the background — the exact behaviour a performance monitor
     * exists to detect.</p>
     */
    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTask);
        unregisterNetworkCallback();
        super.onPause();
    }

    /**
     * Samples the two live metrics and redraws their graphs.
     *
     * <p>Called before the section rebuild so the headline numbers and the
     * detail below them describe the same instant.</p>
     */
    private void refreshLiveGraphs() {
        // ---- Memory ---------------------------------------------------------
        final ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(info);

            final long used = info.totalMem - info.availMem;
            final int percent = Units.percent(used, info.totalMem);

            memoryHistory.add(percent);
            binding.perfTextLiveMemory.setText(percent + "%");
            binding.perfTextLiveMemoryDetail.setText(
                    Units.bytes(used) + " of " + Units.bytes(info.totalMem));
            binding.perfGraphMemory.invalidate();
        }

        // ---- Network throughput ---------------------------------------------
        throughputSampler.sample();
        final long total = throughputSampler.getTotalBytesPerSecond();
        networkHistory.add(total);

        if (isOffline()) {
            binding.perfTextLiveNetwork.setText(R.string.perf_live_network_offline);
            binding.perfTextLiveNetworkDetail.setText("");
        } else if (total == 0L) {
            binding.perfTextLiveNetwork.setText(R.string.perf_live_network_idle);
            binding.perfTextLiveNetworkDetail.setText("");
        } else {
            binding.perfTextLiveNetwork.setText(Units.bytes(total) + "/s");
            binding.perfTextLiveNetworkDetail.setText(getString(
                    R.string.perf_live_network_detail,
                    Units.bytes(throughputSampler.getDownloadBytesPerSecond()) + "/s",
                    Units.bytes(throughputSampler.getUploadBytesPerSecond()) + "/s"));
        }
        binding.perfGraphNetwork.invalidate();
    }

    /**
     * @return {@code true} if the device currently has no active network
     */
    private boolean isOffline() {
        final ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return true;
        }
        final Network active = connectivity.getActiveNetwork();
        return active == null || connectivity.getNetworkCapabilities(active) == null;
    }

    /**
     * Collects every section and rebuilds the report.
     *
     * <p>The container is cleared and repopulated rather than diffed. With
     * roughly thirty rows that is a fraction of a millisecond, and it removes a
     * whole category of bug where a stale row survives because its section
     * happened to shrink.</p>
     */
    private void refreshMetrics() {
        if (binding == null || isFinishing()) {
            return;
        }

        refreshLiveGraphs();

        final List<MetricSection> sections = new ArrayList<>();
        sections.add(MemoryCollector.collect(this));
        sections.add(StorageCollector.collect(this));
        sections.add(BatteryCollector.collect(this));
        sections.add(NetworkCollector.collect(this));
        sections.add(SystemCollector.collect(this));

        final LinearLayout container = binding.perfContainer;
        container.removeAllViews();

        final LayoutInflater inflater = getLayoutInflater();
        for (MetricSection section : sections) {
            container.addView(createSectionHeader(inflater, container, section));
            for (Metric metric : section.getMetrics()) {
                container.addView(createMetricRow(inflater, container, metric));
            }
        }
    }

    /**
     * Inflates and binds a section heading.
     *
     * @param inflater  layout inflater
     * @param parent    the container, for correct layout params
     * @param section   the section to render
     * @return the bound header view
     */
    @NonNull
    private View createSectionHeader(@NonNull LayoutInflater inflater,
                                     @NonNull LinearLayout parent,
                                     @NonNull MetricSection section) {
        // attachToRoot=false: the caller adds it, so it must not be added twice.
        final View view = inflater.inflate(R.layout.perf_item_section, parent, false);
        ((TextView) view.findViewById(R.id.perf_text_section_title)).setText(section.getTitle());
        ((ImageView) view.findViewById(R.id.perf_image_section_icon))
                .setImageResource(section.getIconRes());
        return view;
    }

    /**
     * Inflates and binds one metric row.
     *
     * <p>The proportion bar and the supporting line are each shown only when
     * the metric actually carries one, so rows stay as compact as their content
     * allows.</p>
     *
     * @param inflater layout inflater
     * @param parent   the container, for correct layout params
     * @param metric   the reading to render
     * @return the bound row view
     */
    @NonNull
    private View createMetricRow(@NonNull LayoutInflater inflater,
                                 @NonNull LinearLayout parent,
                                 @NonNull Metric metric) {
        final View view = inflater.inflate(R.layout.perf_item_metric, parent, false);

        ((TextView) view.findViewById(R.id.perf_text_label)).setText(metric.getLabel());
        ((TextView) view.findViewById(R.id.perf_text_value)).setText(metric.getValue());

        final ProgressBar bar = view.findViewById(R.id.perf_progress);
        if (metric.hasBar()) {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(metric.getPercent());
        } else {
            bar.setVisibility(View.GONE);
        }

        final TextView detail = view.findViewById(R.id.perf_text_detail);
        if (metric.getDetail() == null || metric.getDetail().isEmpty()) {
            detail.setVisibility(View.GONE);
        } else {
            detail.setVisibility(View.VISIBLE);
            detail.setText(metric.getDetail());
        }

        return view;
    }

    /** Releases the binding and any pending refresh. */
    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroy();
    }
}
