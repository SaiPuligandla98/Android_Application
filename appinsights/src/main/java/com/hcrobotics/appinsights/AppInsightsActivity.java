package com.hcrobotics.appinsights;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.hcrobotics.appinsights.data.InstalledAppScanner;
import com.hcrobotics.appinsights.databinding.AppsActivityInsightsBinding;
import com.hcrobotics.appinsights.internal.Units;
import com.hcrobotics.appinsights.model.InstalledApp;
import com.hcrobotics.appinsights.ui.InstalledAppAdapter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only inventory of the applications installed on this device.
 *
 * <h2>It inspects; it never interferes</h2>
 * Every figure here comes from asking {@code PackageManager} a question or
 * reading an APK's size on disk. Nothing is started, stopped, disabled or
 * modified, and no other application is affected in any way by being listed.
 *
 * <h2>Threading</h2>
 * A full scan resolves the label and icon of every package and takes hundreds
 * of milliseconds on a device with a few hundred apps — far too long for the
 * main thread. It runs on a single-thread executor, with results posted back
 * through a main-thread {@link Handler}.
 *
 * <p>Every posted callback checks the Activity is still alive before touching a
 * view. A scan that completes after the user has left would otherwise write to
 * a destroyed hierarchy.</p>
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class AppInsightsActivity extends AppCompatActivity
        implements InstalledAppAdapter.OnAppClickListener {

    /** Posts scan results back to the main thread. */
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    /** Runs the scan off the main thread. */
    private ExecutorService backgroundExecutor;

    /** Type-safe access to the views in {@code apps_activity_insights.xml}. */
    private AppsActivityInsightsBinding binding;

    /** The list adapter. */
    private InstalledAppAdapter adapter;

    /** Whether apps that shipped with the device are included. */
    private boolean includeSystemApps = false;

    /** Current ordering. */
    private InstalledAppScanner.SortOrder sortOrder = InstalledAppScanner.SortOrder.NAME;

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = AppsActivityInsightsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        backgroundExecutor = Executors.newSingleThreadExecutor();

        adapter = new InstalledAppAdapter(this);
        binding.appsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.appsRecycler.setAdapter(adapter);
        // Every row is the same height, so the RecyclerView can skip
        // re-measuring itself on each change - measurably smoother scrolling.
        binding.appsRecycler.setHasFixedSize(true);

        binding.appsButtonBack.setOnClickListener(v -> finish());
        binding.appsChipSort.setOnClickListener(v -> showSortOptions());
        binding.appsChipSystem.setOnClickListener(v -> {
            includeSystemApps = !includeSystemApps;
            updateFilterLabels();
            startScan();
        });

        updateFilterLabels();
        startScan();
    }

    /**
     * Runs a scan in the background and renders the result.
     */
    private void startScan() {
        binding.appsProgress.setVisibility(View.VISIBLE);
        binding.appsTextSummary.setText(R.string.apps_scanning);

        final boolean systemApps = includeSystemApps;
        final InstalledAppScanner.SortOrder order = sortOrder;

        backgroundExecutor.execute(() -> {
            final List<InstalledApp> apps =
                    InstalledAppScanner.scan(this, systemApps, order);

            mainThreadHandler.post(() -> {
                // The scan can outlive the screen on a slow device.
                if (binding == null || isFinishing() || isDestroyed()) {
                    return;
                }
                binding.appsProgress.setVisibility(View.GONE);
                adapter.submit(apps);
                binding.appsTextSummary.setText(buildSummary(apps));
            });
        });
    }

    /**
     * Builds the one-line summary above the list.
     *
     * <p>Total installed size is the figure people actually want from an app
     * inventory, and it is only meaningful as a sum — reading three hundred
     * individual numbers is not analysis.</p>
     *
     * @param apps the scanned inventory
     * @return a summary such as "184 apps · 12.40 GB total"
     */
    @NonNull
    private String buildSummary(@NonNull List<InstalledApp> apps) {
        long totalBytes = 0L;
        for (InstalledApp app : apps) {
            totalBytes += app.getApkSizeBytes();
        }
        return getString(R.string.apps_summary, apps.size(), Units.bytes(totalBytes));
    }

    /** Reflects the current filter and sort in the two chips. */
    private void updateFilterLabels() {
        binding.appsChipSystem.setText(includeSystemApps
                ? R.string.apps_filter_all
                : R.string.apps_filter_user_only);

        final int sortLabel;
        switch (sortOrder) {
            case SIZE:
                sortLabel = R.string.apps_sort_size;
                break;
            case RECENTLY_UPDATED:
                sortLabel = R.string.apps_sort_updated;
                break;
            case NAME:
            default:
                sortLabel = R.string.apps_sort_name;
                break;
        }
        binding.appsChipSort.setText(sortLabel);
    }

    /** Offers the three sort orders. */
    private void showSortOptions() {
        final String[] options = {
                getString(R.string.apps_sort_name),
                getString(R.string.apps_sort_size),
                getString(R.string.apps_sort_updated)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.apps_sort_title)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 1:
                            sortOrder = InstalledAppScanner.SortOrder.SIZE;
                            break;
                        case 2:
                            sortOrder = InstalledAppScanner.SortOrder.RECENTLY_UPDATED;
                            break;
                        default:
                            sortOrder = InstalledAppScanner.SortOrder.NAME;
                            break;
                    }
                    updateFilterLabels();
                    startScan();
                })
                .show();
    }

    /**
     * Shows everything known about one application.
     *
     * <p>A dialog rather than a second Activity: this is a handful of read-only
     * fields with no interaction beyond dismissing it, and a whole screen with
     * its own lifecycle and manifest entry would be more machinery than the
     * content justifies.</p>
     *
     * @param app the application to describe
     */
    @Override
    public void onAppClick(@NonNull InstalledApp app) {
        final StringBuilder details = new StringBuilder();

        details.append(getString(R.string.apps_detail_package, app.getPackageName()))
                .append("\n\n")
                .append(getString(R.string.apps_detail_version,
                        app.getVersionName(), app.getVersionCode()))
                .append("\n")
                .append(getString(R.string.apps_detail_size,
                        Units.bytes(app.getApkSizeBytes())))
                .append("\n\n")
                .append(getString(R.string.apps_detail_target,
                        Units.androidVersion(app.getTargetSdk())))
                .append("\n")
                .append(getString(R.string.apps_detail_min,
                        Units.androidVersion(app.getMinSdk())))
                .append("\n\n")
                .append(getString(R.string.apps_detail_installed,
                        Units.date(app.getFirstInstalledAt())));

        if (app.hasBeenUpdated()) {
            details.append("\n")
                    .append(getString(R.string.apps_detail_updated,
                            Units.date(app.getLastUpdatedAt())));
        }

        details.append("\n\n")
                .append(getString(R.string.apps_detail_permissions, app.getPermissionCount()))
                .append("\n")
                .append(getString(R.string.apps_detail_type,
                        getString(app.isSystemApp()
                                ? R.string.apps_type_system
                                : R.string.apps_type_user)));

        if (!app.isEnabled()) {
            details.append("\n").append(getString(R.string.apps_detail_disabled));
        }

        new AlertDialog.Builder(this)
                .setTitle(app.getName())
                .setIcon(app.getIcon())
                .setMessage(details.toString())
                .setPositiveButton(R.string.apps_action_close, null)
                .show();
    }

    /** Releases the executor, pending callbacks and the binding. */
    @Override
    protected void onDestroy() {
        mainThreadHandler.removeCallbacksAndMessages(null);
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
            backgroundExecutor = null;
        }
        binding = null;
        super.onDestroy();
    }
}
