package com.hcrobotics.updater.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.updater.notify.UpdateNotifier;

/**
 * Receives the outcome of a {@link PackageInstaller} session.
 *
 * <h2>Why installing needs a receiver at all</h2>
 * Committing an install session does not install anything immediately. The
 * system validates the APK, decides whether it needs the user's confirmation,
 * shows its own dialog, and only then performs the install. Each stage reports
 * back here as a broadcast.
 *
 * <p>The most important of those is {@code STATUS_PENDING_USER_ACTION}. It is
 * not an error - it means "the user must confirm, here is the Intent that asks
 * them". Miss it, and the update simply never happens: no crash, no dialog,
 * nothing at all. That silent failure is the single most common bug in
 * hand-rolled OTA implementations.</p>
 *
 * <h2>Declared in the library manifest</h2>
 * Registered with {@code exported="false"} so no other app can forge an install
 * result. See {@code updater/src/main/AndroidManifest.xml}.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class InstallResultReceiver extends BroadcastReceiver {

    /**
     * Handles one status update from the package installer.
     *
     * @param context the receiver context
     * @param intent  carries {@link PackageInstaller#EXTRA_STATUS} and, when the
     *                status is pending, {@link Intent#EXTRA_INTENT}
     */
    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        final String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {

            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                launchConfirmationDialog(context, intent);
                break;

            case PackageInstaller.STATUS_SUCCESS:
                UpdaterLog.i("Update installed successfully");
                UpdateNotifier.cancelAll(context);
                // No success notification: the app is about to be restarted by
                // the system, and a notification about an update that already
                // happened is noise.
                break;

            default:
                final String reason = describeFailure(status, message);
                UpdaterLog.e("Install failed | status=" + status + " | " + reason, null);
                UpdateNotifier.showInstallFailed(context, reason);
                break;
        }
    }

    /**
     * Launches the system's install-confirmation dialog.
     *
     * <p>The Intent is supplied by the system in {@link Intent#EXTRA_INTENT}.
     * {@code FLAG_ACTIVITY_NEW_TASK} is mandatory: a BroadcastReceiver has no
     * Activity of its own to launch from, and starting an Activity without it
     * throws.</p>
     *
     * @param context the receiver context
     * @param intent  the broadcast carrying the confirmation Intent
     */
    private void launchConfirmationDialog(@NonNull Context context, @NonNull Intent intent) {
        final Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirmation == null) {
            UpdaterLog.e("The installer asked for user confirmation but supplied no Intent", null);
            UpdateNotifier.showInstallFailed(context,
                    "The system could not open the install confirmation dialog.");
            return;
        }
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(confirmation);
            UpdaterLog.i("Showing the system install confirmation dialog");
        } catch (Exception e) {
            UpdaterLog.e("Could not show the install confirmation dialog", e);
            UpdateNotifier.showInstallFailed(context,
                    "Could not open the install confirmation dialog.");
        }
    }

    /**
     * Turns a {@link PackageInstaller} status code into a diagnosis.
     *
     * <p>The raw codes are close to meaningless on their own, and the one that
     * matters most in practice - a signature mismatch - is easy to hit and hard
     * to guess at, so it is spelled out explicitly.</p>
     *
     * @param status  the status code received
     * @param message the system's message, or {@code null}
     * @return an explanation suitable for a log and for the user
     */
    @NonNull
    private String describeFailure(int status, @Nullable String message) {
        final String detail = message != null ? " (" + message + ")" : "";
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "The update was cancelled." + detail;

            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "The system blocked the install. Check that \"install unknown apps\" is "
                        + "still enabled for this app." + detail;

            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "The update conflicts with the installed app. This almost always means the "
                        + "new APK was signed with a DIFFERENT keystore than the installed build. "
                        + "Every OTA release must be signed with the same key." + detail;

            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "The update is not compatible with this device - typically its minSdk is "
                        + "higher than this Android version, or the ABI does not match." + detail;

            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "The APK is corrupt or malformed." + detail;

            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "There is not enough free storage on the device to install the update." + detail;

            default:
                return "The update could not be installed." + detail;
        }
    }
}
