package com.hcrobotics.testapp.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hcrobotics.testapp.BuildConfig;
import com.hcrobotics.testapp.R;
import com.hcrobotics.testapp.core.util.AppLogger;
import com.hcrobotics.testapp.databinding.ActivitySettingsBinding;
import com.hcrobotics.testapp.ui.base.BaseActivity;
import com.hcrobotics.updater.OtaUpdater;
import com.hcrobotics.updater.UpdateInfo;

import java.util.concurrent.TimeUnit;

/**
 * Update control, modelled on Android's own "System update" screen.
 *
 * <h2>Why that shape</h2>
 * Every mature updater — Android System Update, Chrome, Windows Update — uses
 * the same layout, because it answers the user's questions in the order they
 * ask them:
 *
 * <ol>
 *   <li><b>Am I up to date?</b> — one prominent status line.</li>
 *   <li><b>How do you know?</b> — when it was last actually checked.</li>
 *   <li><b>Check again.</b> — an explicit button.</li>
 *   <li><b>What would I get?</b> — details, only when there is something.</li>
 *   <li><b>Something is wrong.</b> — problems, only when there are any.</li>
 *   <li><b>Take me back.</b> — rollback, below the fold.</li>
 * </ol>
 *
 * <h2>Why "last checked" earns its place</h2>
 * "Up to date" on its own is unfalsifiable — it looks identical whether
 * checking is working perfectly or has been broken for a week. Pairing it with
 * "checked 3 minutes ago" makes the claim verifiable, and "checked 6 days ago"
 * exposes a device whose checking has silently stopped. On a fleet you cannot
 * physically reach, that one line is the difference between noticing and not.
 *
 * <h2>Edge cases handled here</h2>
 * <ul>
 *   <li>Notifications disabled → a visible warning row, not silent failure.</li>
 *   <li>Install permission missing → likewise, with a tap-through to grant it.</li>
 *   <li>Pending update already installed → filtered out by
 *       {@code OtaUpdater.getPendingUpdate}, so the screen cannot offer it.</li>
 *   <li>Rollback target equal to or newer than what is installed → hidden.</li>
 *   <li>Rollback target below this device's API level → hidden.</li>
 *   <li>No check ever completed → "Last checked: never" rather than a
 *       misleading timestamp.</li>
 * </ul>
 *
 * @author HC Robotics
 * @since 1.4.0
 */
public final class SettingsActivity extends BaseActivity {

    /** Log tag for this screen. */
    private static final String TAG = "SettingsActivity";

    /**
     * How long a manual check is treated as "in progress" before the screen
     * stops saying so.
     *
     * <p>The check runs in WorkManager and reports back through preferences
     * rather than a callback, so there is nothing to await. Rather than invent
     * a progress API for a request that finishes in under a second, the screen
     * simply refreshes shortly afterwards.</p>
     */
    private static final long CHECK_FEEDBACK_DELAY_MS = 2_500L;

    /** Type-safe accessor for the views in {@code activity_settings.xml}. */
    private ActivitySettingsBinding binding;

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.textAppNameValue.setText(getString(R.string.app_name));
        binding.textPackageValue.setText(BuildConfig.APPLICATION_ID);

        binding.buttonBack.setOnClickListener(v -> finish());
        binding.buttonCheckUpdates.setOnClickListener(v -> checkForUpdates());
        binding.buttonInstallUpdate.setOnClickListener(v -> OtaUpdater.openUpdateScreen(this));
        binding.rowNotifications.setOnClickListener(v -> openNotificationSettings());
        binding.rowNotificationsWarning.setOnClickListener(v -> openNotificationSettings());
        binding.rowInstallWarning.setOnClickListener(v -> openInstallPermissionSettings());
        binding.rowRollback.setOnClickListener(v -> confirmRollback());

        AppLogger.i(TAG, "Settings opened");
    }

    /**
     * Rebuilds the whole screen from current state.
     *
     * <p>Everything shown here can change while the screen is open: a check may
     * complete, the user may return from a system settings page having granted
     * a permission, or an install may finish. Re-reading on every resume is what
     * keeps the screen from confidently displaying a stale answer.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** Re-reads every piece of state and updates the UI to match. */
    private void refresh() {
        if (binding == null) {
            return;
        }
        bindStatus();
        bindAvailableUpdate();
        bindWarnings();
        bindNotificationState();
        bindRollback();
    }

    /**
     * Fills in the headline status block.
     */
    private void bindStatus() {
        final UpdateInfo pending = OtaUpdater.getPendingUpdate(this);

        binding.textStatusTitle.setText(pending != null
                ? R.string.settings_status_update_available
                : R.string.settings_status_up_to_date);

        binding.textStatusSubtitle.setText(getString(
                R.string.settings_status_detail_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                formatLastChecked()));
    }

    /**
     * Shows the available-update card, or hides it when nothing is waiting.
     *
     * <p>{@code OtaUpdater.getPendingUpdate} already discards a record whose
     * version is at or below the installed one, so this card can never offer an
     * "update" to the version already running — the bug that previously made
     * the app reinstall itself and appear to crash.</p>
     */
    private void bindAvailableUpdate() {
        final UpdateInfo pending = OtaUpdater.getPendingUpdate(this);

        if (pending == null) {
            binding.cardUpdateDetails.setVisibility(View.GONE);
            return;
        }

        binding.cardUpdateDetails.setVisibility(View.VISIBLE);
        binding.textAvailableVersion.setText(getString(
                R.string.settings_available_version_format, pending.getVersionName()));

        final String size = pending.getFormattedSize();
        if (size.isEmpty()) {
            binding.textAvailableSize.setVisibility(View.GONE);
        } else {
            binding.textAvailableSize.setVisibility(View.VISIBLE);
            binding.textAvailableSize.setText(getString(
                    R.string.settings_available_size_format, size));
        }

        if (pending.getReleaseNotes().isEmpty()) {
            binding.textAvailableNotes.setVisibility(View.GONE);
        } else {
            binding.textAvailableNotes.setVisibility(View.VISIBLE);
            binding.textAvailableNotes.setText(pending.getReleaseNotes());
        }
    }

    /**
     * Surfaces the two conditions that break updates silently.
     *
     * <p>Both of these fail with no error, no dialog and no log entry on the
     * device. Naming them on this screen is the only way a user ever discovers
     * why updates stopped arriving.</p>
     */
    private void bindWarnings() {
        binding.rowNotificationsWarning.setVisibility(
                OtaUpdater.canPostNotifications(this) ? View.GONE : View.VISIBLE);

        binding.rowInstallWarning.setVisibility(
                OtaUpdater.needsInstallPermission(this) ? View.VISIBLE : View.GONE);
    }

    /** Shows whether update notifications would actually be delivered. */
    private void bindNotificationState() {
        binding.textNotificationsState.setText(OtaUpdater.canPostNotifications(this)
                ? R.string.settings_notifications_enabled
                : R.string.settings_notifications_disabled);
    }

    /**
     * Shows the rollback target, or disables the row when going back is not
     * genuinely possible.
     *
     * <p>{@code OtaUpdater.canRollBack} checks all three conditions at once: a
     * previous release exists, it is actually older than what is installed, and
     * this device meets its minSdk. Offering a control that cannot work is
     * worse than not offering it.</p>
     */
    private void bindRollback() {
        final UpdateInfo previousRelease = OtaUpdater.getPreviousVersion(this);

        if (!OtaUpdater.canRollBack(this) || previousRelease == null) {
            binding.rowRollback.setEnabled(false);
            binding.textRollbackSubtitle.setText(R.string.settings_rollback_unavailable);
            binding.textRollbackNote.setVisibility(View.GONE);
            return;
        }

        binding.rowRollback.setEnabled(true);
        binding.textRollbackSubtitle.setText(getString(
                R.string.settings_rollback_version_format, previousRelease.getVersionName()));
        binding.textRollbackNote.setVisibility(View.VISIBLE);
    }

    /**
     * Renders the last-checked time as a relative phrase.
     *
     * <p>Relative beats absolute here. "14:32 on 15 August" makes the reader do
     * arithmetic; "3 minutes ago" is the answer they wanted. The thresholds are
     * coarse on purpose — nobody needs second-level precision about an update
     * check.</p>
     *
     * @return a phrase such as "just now", "4 hours ago" or "never"
     */
    @NonNull
    private String formatLastChecked() {
        final long lastCheck = OtaUpdater.getLastCheckTime(this);
        if (lastCheck <= 0L) {
            return getString(R.string.settings_last_checked_never);
        }

        final long elapsed = System.currentTimeMillis() - lastCheck;
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed);

        if (minutes < 1) {
            return getString(R.string.settings_last_checked_just_now);
        }
        if (minutes < 60) {
            return getString(R.string.settings_last_checked_minutes, minutes);
        }

        final long hours = TimeUnit.MILLISECONDS.toHours(elapsed);
        if (hours < 24) {
            return getString(R.string.settings_last_checked_hours, hours);
        }
        return getString(R.string.settings_last_checked_days,
                TimeUnit.MILLISECONDS.toDays(elapsed));
    }

    /**
     * Runs an immediate check and refreshes the screen once it has had time to
     * complete.
     *
     * <p>The check is handed to WorkManager and reports back through
     * preferences, so there is nothing to await. Showing "Checking…" and
     * refreshing a moment later is honest and far simpler than inventing a
     * progress API for a request that finishes in under a second.</p>
     */
    private void checkForUpdates() {
        AppLogger.i(TAG, "Manual update check requested");
        binding.textStatusTitle.setText(R.string.settings_status_checking);
        binding.buttonCheckUpdates.setEnabled(false);

        OtaUpdater.checkNow(this);

        binding.getRoot().postDelayed(() -> {
            if (binding == null || isFinishing()) {
                return;
            }
            binding.buttonCheckUpdates.setEnabled(true);
            refresh();
        }, CHECK_FEEDBACK_DELAY_MS);
    }

    /**
     * Opens the system notification settings for this app.
     *
     * <p>Notification permission cannot be re-requested once the user has
     * denied it twice — Android stops showing the dialog entirely. Sending them
     * to the settings page is then the only route, which is why this is a
     * navigation rather than a permission request.</p>
     */
    private void openNotificationSettings() {
        try {
            final Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (Exception e) {
            AppLogger.e(TAG, "Could not open notification settings", e);
            Toast.makeText(this, R.string.settings_notifications_disabled,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Opens the "install unknown apps" settings page for this app. */
    private void openInstallPermissionSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            AppLogger.e(TAG, "Could not open the install-permission settings", e);
        }
    }

    /**
     * Confirms a rollback before starting it.
     *
     * <p>Going backwards is destructive in a way updating is not: if the
     * platform refuses the downgrade, the only route back is uninstalling the
     * app and losing its data. The confirmation states that plainly rather than
     * letting the user find out from a failure message.</p>
     */
    private void confirmRollback() {
        final UpdateInfo previousRelease = OtaUpdater.getPreviousVersion(this);
        if (previousRelease == null || !OtaUpdater.canRollBack(this)) {
            return;
        }

        // These strings belong to the :updater module and must be fully
        // qualified: nonTransitiveRClass keeps library resources out of the
        // app's own R class.
        new AlertDialog.Builder(this)
                .setTitle(com.hcrobotics.updater.R.string.updater_rollback_title)
                .setMessage(getString(com.hcrobotics.updater.R.string.updater_rollback_message,
                        BuildConfig.VERSION_NAME, previousRelease.getVersionName()))
                .setPositiveButton(com.hcrobotics.updater.R.string.updater_action_rollback,
                        (dialog, which) -> {
                            AppLogger.w(TAG, "User confirmed rollback to "
                                    + previousRelease.getVersionName());
                            OtaUpdater.startRollback(this);
                        })
                .setNegativeButton(com.hcrobotics.updater.R.string.updater_action_cancel, null)
                .show();
    }

    /** {@inheritDoc} */
    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected String getLogTag() {
        return TAG;
    }
}
