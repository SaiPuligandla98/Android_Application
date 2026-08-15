package com.hcrobotics.updater.ui;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.hcrobotics.updater.R;
import com.hcrobotics.updater.UpdateInfo;
import com.hcrobotics.updater.internal.AppVersion;
import com.hcrobotics.updater.internal.UpdaterLog;

/**
 * The dialog a user sees when an update is waiting.
 *
 * <h2>Why a dialog and not just the notification</h2>
 * A notification is easy to swipe away and even easier to miss. Someone who
 * opens the app has already given it their attention, and that is the moment to
 * ask. The notification catches people who are not in the app; this catches
 * everyone else.
 *
 * <h2>What it deliberately shows</h2>
 * <ul>
 *   <li><b>Both versions</b> — "1.0.0 → 1.3.0". A single number means nothing
 *       on its own; the pair tells the user how far behind they are.</li>
 *   <li><b>Download size</b>, so someone on mobile data can make an informed
 *       choice rather than being surprised by the bill.</li>
 *   <li><b>Full release notes</b>, not a truncated line. "What am I agreeing
 *       to?" is a reasonable question and this is the only screen that answers
 *       it before the install starts.</li>
 * </ul>
 *
 * <h2>"Not now" is a real answer</h2>
 * Declining dismisses the dialog and nothing more — the pending update stays
 * recorded, so the Settings screen keeps offering it indefinitely. Nagging on
 * every single launch is how users learn to dismiss dialogs without reading
 * them, which is precisely the habit you do not want when a genuinely important
 * update arrives.
 *
 * <p>The one exception is a release marked {@code mandatory}, where the dialog
 * cannot be dismissed and offers no way out but updating.</p>
 *
 * @author HC Robotics
 * @since 1.1.0
 */
public final class UpdatePromptDialog {

    /** Utility class; never instantiated. */
    private UpdatePromptDialog() {
        throw new AssertionError("UpdatePromptDialog is a utility class.");
    }

    /**
     * Builds and shows the update dialog.
     *
     * <p>Must be called from an {@link Activity} that is actually on screen —
     * a dialog needs a live window token, and showing one against a finishing
     * Activity throws {@code BadTokenException}. The guard below makes that
     * impossible rather than merely unlikely.</p>
     *
     * @param activity the Activity to show the dialog over
     * @param update   the update being offered
     */
    public static void show(@NonNull Activity activity, @NonNull UpdateInfo update) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            UpdaterLog.d("Update dialog suppressed: the Activity is going away");
            return;
        }

        final String installedVersion = AppVersion.installedVersionName(activity);

        // ---- Body: versions, size, then the release notes -------------------
        final StringBuilder message = new StringBuilder();
        message.append(activity.getString(
                R.string.updater_dialog_versions, installedVersion, update.getVersionName()));

        final String size = update.getFormattedSize();
        if (!size.isEmpty()) {
            message.append("\n")
                    .append(activity.getString(R.string.updater_dialog_size, size));
        }

        message.append("\n\n")
                .append(activity.getString(R.string.updater_release_notes_heading))
                .append("\n")
                .append(update.getReleaseNotes().isEmpty()
                        ? activity.getString(R.string.updater_release_notes_empty)
                        : update.getReleaseNotes());

        if (update.isMandatory()) {
            message.append("\n\n")
                    .append(activity.getString(R.string.updater_mandatory_notice));
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.updater_dialog_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.updater_action_update_now, (dialog, which) -> {
                    UpdaterLog.i("User accepted the update to " + update.getVersionName());
                    activity.startActivity(UpdateActivity.createIntent(activity, update));
                });

        if (update.isMandatory()) {
            /*
             * A mandatory update offers no exit: no "Not now" button, and the
             * dialog cannot be dismissed with Back or by tapping outside it.
             */
            builder.setCancelable(false);
        } else {
            builder.setNegativeButton(R.string.updater_action_not_now, (dialog, which) -> {
                // Deliberately no state written here. The pending update stays
                // recorded, so Settings keeps offering it until it is installed.
                UpdaterLog.i("User postponed the update; it remains available in Settings");
                dialog.dismiss();
            });
            builder.setCancelable(true);
        }

        builder.show();
        UpdaterLog.d("Showed the update dialog for " + update.getVersionName());
    }
}
