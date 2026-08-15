package com.hcrobotics.updater.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hcrobotics.updater.R;
import com.hcrobotics.updater.UpdateInfo;
import com.hcrobotics.updater.databinding.UpdaterActivityUpdateBinding;
import com.hcrobotics.updater.internal.ApkDownloader;
import com.hcrobotics.updater.internal.ApkInstaller;
import com.hcrobotics.updater.internal.AppVersion;
import com.hcrobotics.updater.internal.UpdateStorage;
import com.hcrobotics.updater.internal.UpdaterLog;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screen the user lands on after tapping the update notification.
 *
 * <h2>What it does</h2>
 * Presents the release, then walks the user through download, verification and
 * installation, reporting progress at every step.
 *
 * <h2>The state machine</h2>
 * One button drives the whole flow; its label and behaviour follow the current
 * {@link Stage}. Modelling it as an explicit enum rather than a scatter of
 * boolean flags means every transition is visible in one place, and an
 * impossible combination (downloading AND ready to install) cannot be
 * represented at all.
 *
 * <pre>
 *   READY ──tap──► DOWNLOADING ──ok──► READY_TO_INSTALL ──tap──► INSTALLING
 *     ▲                 │                                            │
 *     │                 └── error ──► FAILED ──tap(retry)──┐         │
 *     └──────────────────────────────────────────────────── ┘        │
 *                                                                     ▼
 *   NEEDS_PERMISSION ──tap──► system settings ──return──► READY   system dialog
 * </pre>
 *
 * <h2>Threading</h2>
 * Downloading and hashing are blocking and must not run on the main thread. A
 * single-thread {@link ExecutorService} does the work; results are posted back
 * with a main-thread {@link Handler}.
 *
 * <p>Every posted callback checks {@link #isFinishing()} and a null binding
 * before touching a view. A download that completes after the user has left
 * would otherwise write to a destroyed view hierarchy - a crash that only
 * happens on slow connections, and therefore only in the field.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class UpdateActivity extends AppCompatActivity {

    /** Intent extra carrying the serialised {@link UpdateInfo}. */
    private static final String EXTRA_UPDATE_JSON = "com.hcrobotics.updater.EXTRA_UPDATE_JSON";

    /** Where the flow currently stands. */
    private enum Stage {
        /** The user must grant "install unknown apps" before anything else. */
        NEEDS_PERMISSION,
        /** Ready to start downloading. */
        READY,
        /** Bytes are being transferred. */
        DOWNLOADING,
        /** A verified APK is on disk, waiting to be installed. */
        READY_TO_INSTALL,
        /** The install session has been committed to the system. */
        INSTALLING,
        /** Something went wrong; the button offers a retry. */
        FAILED
    }

    /** Posts work from the background executor back onto the main thread. */
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    /** Runs the download and digest verification off the main thread. */
    private ExecutorService backgroundExecutor;

    /** Type-safe access to the views in {@code updater_activity_update.xml}. */
    private UpdaterActivityUpdateBinding binding;

    /** The release this screen is offering. */
    private UpdateInfo update;

    /** Current position in the flow. */
    private Stage stage = Stage.READY;

    /** The verified APK, once a download has completed. */
    private File downloadedApk;

    /**
     * Builds an Intent that opens this screen for a specific update.
     *
     * <p>The update travels as a JSON extra rather than being re-fetched. The
     * notification may be tapped hours after the check ran, possibly with no
     * connectivity, and the user should still see what is on offer.</p>
     *
     * @param context any context
     * @param update  the update to present
     * @return an Intent ready to start
     */
    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull UpdateInfo update) {
        final Intent intent = new Intent(context, UpdateActivity.class);
        try {
            intent.putExtra(EXTRA_UPDATE_JSON, update.toJson().toString());
        } catch (Exception e) {
            UpdaterLog.e("Could not attach the update to the Intent", e);
        }
        return intent;
    }

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = UpdaterActivityUpdateBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        update = UpdateInfo.fromStoredString(getIntent().getStringExtra(EXTRA_UPDATE_JSON));
        if (update == null) {
            // Nothing to show. Closing is the honest response; an empty screen
            // would just leave the user stuck.
            UpdaterLog.e("UpdateActivity started without a valid update; closing", null);
            finish();
            return;
        }

        backgroundExecutor = Executors.newSingleThreadExecutor();

        bindUpdateDetails();
        binding.buttonPrimary.setOnClickListener(v -> onPrimaryActionClicked());
        binding.buttonLater.setOnClickListener(v -> finish());
    }

    /**
     * Re-evaluates the install permission every time the screen is shown.
     *
     * <p>This is what makes the settings round-trip work: the user leaves to
     * grant "install unknown apps", comes back, and {@code onResume} notices the
     * permission now exists and moves the flow forward. Checking only in
     * {@code onCreate} would leave them staring at a stale screen.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Do not disturb a download or install that is already under way.
        if (stage == Stage.DOWNLOADING || stage == Stage.INSTALLING) {
            return;
        }
        if (!ApkInstaller.canInstallPackages(this)) {
            applyStage(Stage.NEEDS_PERMISSION);
        } else if (stage == Stage.NEEDS_PERMISSION) {
            applyStage(resumeStageAfterPermission());
        } else {
            applyStage(stage);
        }
    }

    /**
     * Populates the static parts of the screen from the manifest.
     */
    private void bindUpdateDetails() {
        binding.textNewVersion.setText(
                getString(R.string.updater_new_version_format, update.getVersionName()));
        binding.textInstalledVersion.setText(
                getString(R.string.updater_installed_version_format,
                        AppVersion.installedVersionName(this)));

        // Only show a size when the manifest actually published one.
        final String size = update.getFormattedSize();
        if (size.isEmpty()) {
            binding.textSize.setVisibility(View.GONE);
        } else {
            binding.textSize.setText(getString(R.string.updater_size_format, size));
        }

        binding.textReleaseNotes.setText(update.getReleaseNotes().isEmpty()
                ? getString(R.string.updater_release_notes_empty)
                : update.getReleaseNotes());

        // A mandatory update removes the escape hatch entirely.
        if (update.isMandatory()) {
            binding.textMandatoryNotice.setVisibility(View.VISIBLE);
            binding.buttonLater.setVisibility(View.GONE);
            setFinishOnTouchOutside(false);
        }
    }

    /**
     * Handles a tap on the primary button, dispatching on the current stage.
     */
    private void onPrimaryActionClicked() {
        switch (stage) {
            case NEEDS_PERMISSION:
                openUnknownSourcesSettings();
                break;

            case READY:
            case FAILED:
                startDownload();
                break;

            case READY_TO_INSTALL:
                startInstall();
                break;

            case DOWNLOADING:
            case INSTALLING:
                // Button is disabled in these stages; nothing to do.
                break;
        }
    }

    /**
     * Sends the user to the system screen where "install unknown apps" is
     * granted.
     *
     * <p>There is no permission dialog for this one - it can only be granted in
     * Settings. {@link #onResume()} picks the flow back up on return.</p>
     */
    private void openUnknownSourcesSettings() {
        final Intent intent = ApkInstaller.unknownSourcesSettingsIntent(this);
        if (intent == null) {
            // Below Android 8.0 the per-app setting does not exist.
            applyStage(Stage.READY);
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            UpdaterLog.e("Could not open the install-permission settings screen", e);
            showError(getString(R.string.updater_permission_required));
        }
    }

    /**
     * Starts downloading and verifying the APK on a background thread.
     */
    private void startDownload() {
        applyStage(Stage.DOWNLOADING);

        backgroundExecutor.execute(() -> ApkDownloader.download(this, update,
                new ApkDownloader.ProgressListener() {

                    @Override
                    public void onProgress(long bytesDownloaded, long totalBytes, int percent) {
                        postToUi(() -> {
                            if (percent >= 0) {
                                binding.progressDownload.setIndeterminate(false);
                                binding.progressDownload.setProgress(percent);
                                binding.textStatus.setText(
                                        getString(R.string.updater_status_downloading, percent));
                            } else {
                                // No Content-Length: show motion rather than a
                                // progress bar frozen at zero, which reads as a hang.
                                binding.progressDownload.setIndeterminate(true);
                                binding.textStatus.setText(
                                        getString(R.string.updater_status_downloading_unknown));
                            }
                        });
                    }

                    @Override
                    public void onCompleted(@NonNull File apkFile) {
                        postToUi(() -> {
                            downloadedApk = apkFile;
                            binding.textStatus.setText(getString(R.string.updater_status_ready));
                            applyStage(Stage.READY_TO_INSTALL);
                            // Nothing else is required of the user, so continue
                            // straight into the install. The system still shows
                            // its own confirmation dialog before anything happens.
                            startInstall();
                        });
                    }

                    @Override
                    public void onFailed(@NonNull String message, Throwable cause) {
                        postToUi(() -> showError(message));
                    }
                }));
    }

    /**
     * Hands the verified APK to the system installer.
     */
    private void startInstall() {
        if (downloadedApk == null || !downloadedApk.exists()) {
            // Can happen if storage was cleared between download and install.
            showError(getString(R.string.updater_status_ready));
            applyStage(Stage.FAILED);
            return;
        }

        applyStage(Stage.INSTALLING);

        backgroundExecutor.execute(() -> {
            try {
                ApkInstaller.install(this, downloadedApk);
                // From here the system takes over: it shows the confirmation
                // dialog and reports the outcome to InstallResultReceiver.
            } catch (Exception e) {
                UpdaterLog.e("Could not start the install", e);
                postToUi(() -> showError("The installation could not be started. "
                        + (e.getMessage() != null ? e.getMessage() : "")));
            }
        });
    }

    /**
     * Moves the UI into a stage and applies everything that stage implies.
     *
     * <p>Every visibility change and label change for the whole screen happens
     * here. Scattering them across the call sites is how screens end up in
     * states nobody designed - a progress bar left spinning after a failure,
     * say - so they are deliberately centralised.</p>
     *
     * @param newStage the stage to move into
     */
    private void applyStage(@NonNull Stage newStage) {
        stage = newStage;
        if (binding == null) {
            return;
        }

        switch (newStage) {
            case NEEDS_PERMISSION:
                binding.textStatus.setVisibility(View.VISIBLE);
                binding.textStatus.setText(getString(R.string.updater_permission_required));
                binding.progressDownload.setVisibility(View.GONE);
                binding.buttonPrimary.setEnabled(true);
                binding.buttonPrimary.setText(getString(R.string.updater_action_grant_permission));
                break;

            case READY:
                binding.textStatus.setVisibility(View.GONE);
                binding.progressDownload.setVisibility(View.GONE);
                binding.buttonPrimary.setEnabled(true);
                binding.buttonPrimary.setText(getString(R.string.updater_action_download));
                break;

            case DOWNLOADING:
                binding.textStatus.setVisibility(View.VISIBLE);
                binding.progressDownload.setVisibility(View.VISIBLE);
                binding.progressDownload.setProgress(0);
                binding.buttonPrimary.setEnabled(false);
                binding.buttonPrimary.setText(getString(R.string.updater_action_download));
                // Leaving mid-download would abandon the transfer.
                binding.buttonLater.setEnabled(false);
                break;

            case READY_TO_INSTALL:
                binding.textStatus.setVisibility(View.VISIBLE);
                binding.progressDownload.setVisibility(View.VISIBLE);
                binding.progressDownload.setIndeterminate(false);
                binding.progressDownload.setProgress(100);
                binding.buttonPrimary.setEnabled(true);
                binding.buttonPrimary.setText(getString(R.string.updater_action_install));
                binding.buttonLater.setEnabled(true);
                break;

            case INSTALLING:
                binding.textStatus.setVisibility(View.VISIBLE);
                binding.textStatus.setText(getString(R.string.updater_status_installing));
                binding.progressDownload.setVisibility(View.VISIBLE);
                binding.progressDownload.setIndeterminate(true);
                binding.buttonPrimary.setEnabled(false);
                break;

            case FAILED:
                binding.progressDownload.setVisibility(View.GONE);
                binding.buttonPrimary.setEnabled(true);
                binding.buttonPrimary.setText(getString(R.string.updater_action_retry));
                binding.buttonLater.setEnabled(true);
                break;
        }
    }

    /**
     * Decides which stage to enter once the install permission has been granted.
     *
     * <p>If a verified APK is already on disk from an earlier attempt, skip
     * straight to installing rather than downloading the same bytes again.</p>
     *
     * @return the stage to apply
     */
    @NonNull
    private Stage resumeStageAfterPermission() {
        if (UpdateStorage.isAlreadyDownloaded(this, update)) {
            downloadedApk = UpdateStorage.apkFileFor(this, update);
            return Stage.READY_TO_INSTALL;
        }
        return Stage.READY;
    }

    /**
     * Shows a failure and offers a retry.
     *
     * @param message what went wrong, in the user's terms
     */
    private void showError(@NonNull String message) {
        if (binding == null) {
            return;
        }
        binding.textStatus.setVisibility(View.VISIBLE);
        binding.textStatus.setText(message);
        binding.textStatus.setTextColor(
                getResources().getColor(R.color.updater_color_error, getTheme()));
        applyStage(Stage.FAILED);
    }

    /**
     * Runs a block on the main thread, but only while the screen is still alive.
     *
     * <p>This one guard prevents an entire class of crash. A download can finish
     * seconds after the user has left; without the check, the callback would
     * write to views that no longer exist.</p>
     *
     * @param action work that touches the UI
     */
    private void postToUi(@NonNull Runnable action) {
        mainThreadHandler.post(() -> {
            if (isFinishing() || isDestroyed() || binding == null) {
                return;
            }
            action.run();
        });
    }

    /**
     * Releases the executor, pending callbacks and the view binding.
     *
     * <p>All three matter. An un-shut-down executor keeps a thread alive for the
     * life of the process; pending callbacks keep this Activity reachable; the
     * binding keeps the whole view tree in memory.</p>
     */
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
