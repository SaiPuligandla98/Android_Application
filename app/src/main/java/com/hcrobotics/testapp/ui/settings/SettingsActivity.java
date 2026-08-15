package com.hcrobotics.testapp.ui.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hcrobotics.testapp.BuildConfig;
import com.hcrobotics.testapp.R;
import com.hcrobotics.testapp.core.config.AppConfig;
import com.hcrobotics.testapp.core.util.AppLogger;
import com.hcrobotics.testapp.databinding.ActivitySettingsBinding;
import com.hcrobotics.testapp.ui.base.BaseActivity;
import com.hcrobotics.updater.OtaUpdater;
import com.hcrobotics.updater.UpdateInfo;

/**
 * Settings: build information, update control and rollback.
 *
 * <h2>Why this screen exists</h2>
 * The update dialog is a moment; this is the permanent home. A user who taps
 * "Not now" must still be able to find and install the update afterwards, and a
 * dialog that keeps reappearing on every launch teaches people to dismiss
 * dialogs without reading them.
 *
 * <p>So the dialog asks once per launch, and this screen offers the update
 * indefinitely until it is installed. That is the whole design: <b>ask politely,
 * then stay available</b>.</p>
 *
 * <h2>Why it also offers a rollback</h2>
 * Over-the-air updates make shipping easy, which also makes shipping a bad
 * build easy. On a remote fleet the cost of that is high: a broken release on
 * fifty devices you cannot reach is a very long day.
 *
 * <p>The honest caveat is stated on the screen itself rather than discovered as
 * an error: Android normally refuses to install an older version over a newer
 * one, and only permits it for debuggable builds. On a release build the app
 * must be uninstalled first, which erases its data. Better to know that before
 * tapping than after.</p>
 *
 * <h2>State is read fresh in onResume</h2>
 * A background check can complete while this screen is open, and the user may
 * return from the update screen having installed something. Reading the state
 * in {@link #onResume()} rather than {@link #onCreate} means the screen is
 * never showing a stale answer.
 *
 * @author HC Robotics
 * @since 1.3.0
 */
public final class SettingsActivity extends BaseActivity {

    /** Log tag for this screen. */
    private static final String TAG = "SettingsActivity";

    /** Type-safe accessor for the views in {@code activity_settings.xml}. */
    private ActivitySettingsBinding binding;

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        bindStaticInformation();

        binding.buttonBack.setOnClickListener(v -> finish());
        binding.rowCheckUpdates.setOnClickListener(v -> checkForUpdates());
        binding.rowUpdate.setOnClickListener(v -> OtaUpdater.openUpdateScreen(this));
        binding.rowRollback.setOnClickListener(v -> confirmRollback());

        AppLogger.i(TAG, "Settings opened");
    }

    /**
     * Refreshes everything that can change while this screen is open.
     *
     * <p>A background check may finish at any moment, and the user may return
     * here after installing something. Re-reading on resume is what keeps the
     * screen honest.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        bindUpdateState();
        bindRollbackState();
    }

    /**
     * Fills in the values that cannot change while the app is running.
     *
     * <p>The version deliberately shows BOTH the name and the code. The name is
     * what a user reports; the code is what actually decides whether an update
     * applies. Seeing them together is what makes "why isn't it updating?"
     * answerable at a glance.</p>
     */
    private void bindStaticInformation() {
        binding.textAppNameValue.setText(getString(R.string.app_name));
        binding.textInstalledVersionValue.setText(getString(
                R.string.settings_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE));
        binding.textPackageValue.setText(BuildConfig.APPLICATION_ID);
    }

    /**
     * Shows whether an update is waiting, and makes that row tappable only if
     * one actually is.
     *
     * <p>A disabled row does not ripple, which is the clearest possible signal
     * that there is nothing behind it. Leaving it tappable and doing nothing is
     * how users conclude an app is broken.</p>
     */
    private void bindUpdateState() {
        final UpdateInfo pending = OtaUpdater.getPendingUpdate(this);

        if (pending != null) {
            binding.textUpdateTitle.setText(R.string.settings_update_available);
            binding.textUpdateSubtitle.setText(getString(
                    R.string.settings_update_version_format, pending.getVersionName()));
            binding.rowUpdate.setEnabled(true);
        } else {
            binding.textUpdateTitle.setText(R.string.settings_update_none);
            binding.textUpdateSubtitle.setText(R.string.settings_update_none_subtitle);
            binding.rowUpdate.setEnabled(false);
        }

        binding.textLastChecked.setText(getString(
                R.string.settings_check_interval_format, AppConfig.UPDATE_CHECK_INTERVAL_HOURS));
    }

    /**
     * Shows the rollback target, or hides the option when there is none.
     *
     * <p>There is nothing to roll back to until at least two versions have been
     * published, so the whole section disappears rather than offering a button
     * that cannot work.</p>
     */
    private void bindRollbackState() {
        final UpdateInfo previousRelease = OtaUpdater.getPreviousVersion(this);

        if (previousRelease == null) {
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
     * Runs an immediate update check.
     *
     * <p>If something is already pending there is nothing to discover, so the
     * update screen opens directly instead of making the user wait for a check
     * that would only find what is already known.</p>
     */
    private void checkForUpdates() {
        if (OtaUpdater.getPendingUpdate(this) != null) {
            OtaUpdater.openUpdateScreen(this);
            return;
        }
        AppLogger.i(TAG, "Manual update check requested from Settings");
        Toast.makeText(this, R.string.main_update_check_started, Toast.LENGTH_SHORT).show();
        OtaUpdater.checkNow(this);
    }

    /**
     * Confirms a rollback before starting it.
     *
     * <p>Going backwards is genuinely destructive in a way updating is not: if
     * the platform refuses the downgrade, the only route back is uninstalling
     * the app and losing its data. The confirmation says so in full rather than
     * letting the user find out from a failure message.</p>
     */
    private void confirmRollback() {
        final UpdateInfo previousRelease = OtaUpdater.getPreviousVersion(this);
        if (previousRelease == null) {
            return;
        }

        /*
         * These strings live in the :updater module and must be fully
         * qualified.
         *
         * `android.nonTransitiveRClass=true` in gradle.properties means the
         * app's own R class contains ONLY the app's resources - a library's
         * resources are reached through that library's R. It makes builds
         * faster and dependencies honest, at the cost of writing the package
         * out in full here.
         *
         * Reusing the module's copy rather than duplicating it keeps the
         * rollback warning identical wherever it appears.
         */
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
