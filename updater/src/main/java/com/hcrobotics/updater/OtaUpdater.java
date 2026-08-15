package com.hcrobotics.updater;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.hcrobotics.updater.internal.ApkInstaller;
import com.hcrobotics.updater.internal.UpdaterLog;
import com.hcrobotics.updater.notify.UpdateNotifier;
import com.hcrobotics.updater.ui.UpdateActivity;
import com.hcrobotics.updater.ui.UpdatePromptDialog;
import com.hcrobotics.updater.work.UpdateCheckWorker;

import org.json.JSONException;

import java.util.concurrent.TimeUnit;

/**
 * The complete public API of the OTA updater module.
 *
 * <h2>Plugging this into an application</h2>
 * One call, in {@code Application.onCreate()}:
 *
 * <pre>
 * public final class MyApplication extends Application {
 *     &#64;Override public void onCreate() {
 *         super.onCreate();
 *
 *         OtaUpdater.initialise(this, new OtaConfig.Builder()
 *                 .manifestUrl("https://raw.githubusercontent.com/OWNER/REPO/master/ota/update-manifest.json")
 *                 .checkIntervalHours(6)
 *                 .build());
 *     }
 * }
 * </pre>
 *
 * <p>That is the entire integration. Everything else - scheduling, fetching,
 * comparing versions, notifying, downloading, verifying and installing - is
 * handled inside the module.</p>
 *
 * <h2>Why a static facade</h2>
 * The module is called from four places that cannot share an object reference:
 * the host {@code Application}, a WorkManager worker in a possibly different
 * process lifetime, a notification tap, and optionally a push message handler.
 * A static entry point backed by {@link android.content.SharedPreferences} is
 * the simplest thing that works from all four; a singleton instance would just
 * be a static field with more ceremony.
 *
 * <h2>Adding instant push (optional)</h2>
 * Polling every few hours is enough for most fleets and needs no server at all.
 * When an update must land immediately, call {@link #checkNow(Context)} from a
 * Firebase Cloud Messaging handler:
 *
 * <pre>
 * public final class MyMessagingService extends FirebaseMessagingService {
 *     &#64;Override public void onMessageReceived(&#64;NonNull RemoteMessage message) {
 *         OtaUpdater.checkNow(this);
 *     }
 * }
 * </pre>
 *
 * <p>Note that this module has NO Firebase dependency. The hook is one method
 * call, so adding push is the host app's choice and never this library's
 * problem. FCM itself is free and unlimited.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class OtaUpdater {

    /**
     * Unique name for the recurring check.
     *
     * <p>Enqueuing under a unique name means calling {@code initialise()} on
     * every app launch cannot pile up duplicate schedules - WorkManager
     * recognises the name and keeps a single job.</p>
     */
    private static final String WORK_NAME_PERIODIC = "hc_ota_periodic_check";

    /** Unique name for a one-shot, on-demand check. */
    private static final String WORK_NAME_ONE_SHOT = "hc_ota_immediate_check";

    /** Preference key holding the most recently discovered update. */
    private static final String KEY_PENDING_UPDATE = "pending_update";

    /**
     * Preference key holding the last manifest successfully fetched.
     *
     * <p>Kept separately from the pending update. Once the app is current the
     * pending update is cleared, but the manifest is still needed so a Settings
     * screen can offer a rollback to the previous published version.</p>
     */
    private static final String KEY_LAST_MANIFEST = "last_manifest";

    /** Utility class; never instantiated. */
    private OtaUpdater() {
        throw new AssertionError("OtaUpdater is a static facade.");
    }

    /**
     * Configures the module and schedules recurring update checks.
     *
     * <p>Call once from {@code Application.onCreate()}. Calling it again is
     * harmless and is in fact the correct way to change the configuration: the
     * new settings are persisted and the existing schedule is updated in place
     * rather than duplicated.</p>
     *
     * <p>Cheap and non-blocking - it persists a handful of preferences and hands
     * a request to WorkManager. No network access happens here.</p>
     *
     * @param context any context; the application context is used internally
     * @param config  the configuration to apply
     */
    public static void initialise(@NonNull Context context, @NonNull OtaConfig config) {
        final Context appContext = context.getApplicationContext();

        UpdaterLog.setDebugLoggingEnabled(config.isDebugLogging());
        config.persist(appContext);

        final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class, config.getCheckIntervalHours(), TimeUnit.HOURS)
                .setConstraints(buildConstraints(config))
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                // UPDATE applies configuration changes to the existing schedule
                // without resetting its timer. KEEP would ignore a changed
                // interval; CANCEL_AND_REENQUEUE would restart the countdown on
                // every app launch, so on a frequently-opened app the check
                // might never actually fire.
                ExistingPeriodicWorkPolicy.UPDATE,
                request);

        /*
         * Run a check NOW as well as scheduling the recurring one.
         *
         * WHY THIS IS NOT REDUNDANT
         * -------------------------
         * A PeriodicWorkRequest does NOT run when it is enqueued. WorkManager is
         * free to place the first execution anywhere inside the first interval,
         * and in practice it usually lands near the END of it. A six-hour
         * interval therefore means a freshly installed device can sit for most
         * of a day before its very first update check.
         *
         * That was measured, not assumed: on a real device the first run was
         * scheduled for "+5h47m" after install.
         *
         * For a fleet deployed in the field that behaviour is unacceptable. A
         * device that has just been switched on, or an app the user has just
         * opened, should learn about a waiting update in seconds - not
         * eventually.
         *
         * The one-shot costs a single HTTPS GET of a few hundred bytes, and
         * `initialise()` is called once per process from Application.onCreate(),
         * so it cannot run away with battery.
         */
        checkNow(appContext);

        UpdaterLog.i("OTA updater initialised | interval=" + config.getCheckIntervalHours()
                + "h | unmeteredOnly=" + config.isRequireUnmeteredNetwork()
                + " | immediate check requested");
    }

    /**
     * Runs an update check immediately, outside the normal schedule.
     *
     * <p>Use for a "Check for updates" button, or from a push message handler
     * when an update must reach the fleet without waiting for the next periodic
     * run.</p>
     *
     * <p>Returns straight away; the check runs in the background and posts a
     * notification if it finds something. Requires {@link #initialise} to have
     * been called at least once on this device.</p>
     *
     * @param context any context
     */
    public static void checkNow(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();

        final OtaConfig config = OtaConfig.load(appContext);
        if (config == null) {
            UpdaterLog.e("checkNow() ignored: OtaUpdater.initialise() has never been called.", null);
            return;
        }

        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateCheckWorker.class)
                .setConstraints(buildConstraints(config))
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_NAME_ONE_SHOT,
                // REPLACE: if a check is already queued, the newer request wins.
                // Two simultaneous checks would only duplicate work.
                ExistingWorkPolicy.REPLACE,
                request);

        UpdaterLog.i("Immediate update check requested");
    }

    /**
     * Cancels all scheduled update checks.
     *
     * <p>Provided for completeness - for instance to stop checks on a device
     * being decommissioned. {@link #initialise} restores them.</p>
     *
     * @param context any context
     */
    public static void cancelChecks(@NonNull Context context) {
        final WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
        workManager.cancelUniqueWork(WORK_NAME_PERIODIC);
        workManager.cancelUniqueWork(WORK_NAME_ONE_SHOT);
        UpdaterLog.i("All scheduled update checks cancelled");
    }

    /**
     * Opens the update screen for whichever update is currently pending.
     *
     * <p>Does nothing if no update has been discovered, so it is safe to wire
     * straight to a menu item without checking first.</p>
     *
     * @param context any context
     */
    public static void openUpdateScreen(@NonNull Context context) {
        final UpdateInfo pending = getPendingUpdate(context);
        if (pending == null) {
            UpdaterLog.d("openUpdateScreen() ignored: no update is pending");
            return;
        }
        final Intent intent = UpdateActivity.createIntent(context, pending);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Shows the update dialog if an update is waiting, otherwise does nothing.
     *
     * <p>Designed to be called unconditionally from a screen's {@code onResume},
     * so the host app needs no state of its own:</p>
     *
     * <pre>
     * &#64;Override protected void onResume() {
     *     super.onResume();
     *     OtaUpdater.showUpdateDialogIfAvailable(this);
     * }
     * </pre>
     *
     * <p>The dialog presents both version numbers, the download size and the
     * full release notes, with "Update now" and "Not now". Declining leaves the
     * update recorded, so {@link #getPendingUpdate} keeps returning it and a
     * Settings screen can keep offering it until it is installed.</p>
     *
     * @param activity the Activity to show the dialog over
     * @return {@code true} if a dialog was shown
     */
    public static boolean showUpdateDialogIfAvailable(@NonNull Activity activity) {
        final UpdateInfo pending = getPendingUpdate(activity);
        if (pending == null) {
            return false;
        }
        UpdatePromptDialog.show(activity, pending);
        return true;
    }

    /**
     * Returns the release published immediately before the latest one.
     *
     * <p>This is the rollback target — the version {@link #startRollback} would
     * restore. {@code null} means no rollback is available, either because the
     * manifest carries no {@code previous} entry or because no check has run
     * yet.</p>
     *
     * <p>Note this reflects the last manifest seen, so it is the version before
     * the newest PUBLISHED release, which is not necessarily the version that
     * was installed before the current one.</p>
     *
     * @param context any context
     * @return the previous release, or {@code null} if rollback is unavailable
     */
    @Nullable
    public static UpdateInfo getPreviousVersion(@NonNull Context context) {
        final UpdateInfo latest = getLastSeenManifest(context);
        return latest != null ? latest.getPrevious() : null;
    }

    /**
     * Starts a rollback to the previous published version.
     *
     * <p><b>Android resists this, and you should know why before offering it.</b>
     * The platform normally refuses to install an APK whose {@code versionCode}
     * is lower than the installed one, failing with
     * {@code INSTALL_FAILED_VERSION_DOWNGRADE}. The exception is a debuggable
     * build, where downgrades are permitted — which is why this works during
     * development and can fail on a signed release build.</p>
     *
     * <p>When the platform refuses, the only route back is uninstalling the app
     * and installing the older APK by hand, which erases all of its saved data.
     * The confirmation dialog says so plainly rather than discovering it
     * afterwards.</p>
     *
     * <p>The rollback APK is downloaded and checksum-verified exactly like an
     * update. Going backwards is not a reason to relax verification.</p>
     *
     * @param activity the Activity to show progress in
     * @return {@code true} if a rollback was started
     */
    public static boolean startRollback(@NonNull Activity activity) {
        final UpdateInfo previousRelease = getPreviousVersion(activity);
        if (previousRelease == null) {
            UpdaterLog.d("startRollback() ignored: no previous version is published");
            return false;
        }
        UpdaterLog.i("Starting rollback to " + previousRelease.getVersionName());
        activity.startActivity(UpdateActivity.createRollbackIntent(activity, previousRelease));
        return true;
    }

    /**
     * Returns the last manifest this device successfully fetched.
     *
     * <p>Stored separately from the pending update: once the app is up to date
     * the pending update is cleared, but the manifest is still needed so
     * Settings can offer a rollback.</p>
     *
     * @param context any context
     * @return the last manifest seen, or {@code null} if no check has succeeded
     */
    @Nullable
    public static UpdateInfo getLastSeenManifest(@NonNull Context context) {
        return UpdateInfo.fromStoredString(
                OtaConfig.prefs(context).getString(KEY_LAST_MANIFEST, null));
    }

    /**
     * Records the manifest a check fetched, whether or not it implied an update.
     *
     * <p>Public because {@link UpdateCheckWorker} lives in another package; it
     * is an internal detail.</p>
     *
     * @param context  any context
     * @param manifest the manifest just fetched
     */
    public static void storeLastSeenManifest(@NonNull Context context, @NonNull UpdateInfo manifest) {
        try {
            OtaConfig.prefs(context).edit()
                    .putString(KEY_LAST_MANIFEST, manifest.toJson().toString())
                    .apply();
        } catch (JSONException e) {
            UpdaterLog.e("Could not store the fetched manifest", e);
        }
    }

    /**
     * Returns the most recently discovered update, if any.
     *
     * <p>Useful for showing an in-app badge or an "Update available" row in a
     * settings screen, rather than relying only on the notification.</p>
     *
     * @param context any context
     * @return the pending update, or {@code null} if the app is up to date
     */
    @Nullable
    public static UpdateInfo getPendingUpdate(@NonNull Context context) {
        return UpdateInfo.fromStoredString(
                OtaConfig.prefs(context).getString(KEY_PENDING_UPDATE, null));
    }

    /**
     * Records the update discovered by a check.
     *
     * <p>Public because {@link UpdateCheckWorker} lives in a different package;
     * it is an internal detail and host applications should not call it.</p>
     *
     * @param context any context
     * @param update  the update to remember
     */
    public static void storePendingUpdate(@NonNull Context context, @NonNull UpdateInfo update) {
        try {
            OtaConfig.prefs(context).edit()
                    .putString(KEY_PENDING_UPDATE, update.toJson().toString())
                    .apply();
        } catch (JSONException e) {
            UpdaterLog.e("Could not store the pending update", e);
        }
    }

    /**
     * Forgets any pending update and clears its notification.
     *
     * <p>Called when a check finds the app is already current - which happens
     * after a successful install, and also if a release is withdrawn.</p>
     *
     * @param context any context
     */
    public static void clearPendingUpdate(@NonNull Context context) {
        OtaConfig.prefs(context).edit().remove(KEY_PENDING_UPDATE).apply();
        UpdateNotifier.cancelAll(context);
    }

    /**
     * Reports whether the user must still grant "install unknown apps".
     *
     * <p>Worth checking at a calm moment - during onboarding, say - rather than
     * discovering it halfway through an urgent update.</p>
     *
     * @param context any context
     * @return {@code true} if the permission still needs granting
     */
    public static boolean needsInstallPermission(@NonNull Context context) {
        return !ApkInstaller.canInstallPackages(context);
    }

    /**
     * Reports whether notifications would currently be shown to the user.
     *
     * <p>If this returns {@code false} on Android 13+, the host app should
     * request {@code POST_NOTIFICATIONS}. The module cannot request it itself:
     * a runtime permission dialog must be launched from an Activity, and asking
     * at a moment the user has no context for is the fastest way to get it
     * permanently denied.</p>
     *
     * @param context any context
     * @return {@code true} if update notifications will be visible
     */
    public static boolean canPostNotifications(@NonNull Context context) {
        return UpdateNotifier.canPostNotifications(context);
    }

    /**
     * Builds the WorkManager constraints implied by the configuration.
     *
     * <p>A network constraint means the job is not even started while offline,
     * rather than starting and failing. That is the difference between a check
     * that costs nothing when there is no signal and one that wakes the device
     * to time out.</p>
     *
     * @param config the active configuration
     * @return constraints for the check job
     */
    @NonNull
    private static Constraints buildConstraints(@NonNull OtaConfig config) {
        return new Constraints.Builder()
                .setRequiredNetworkType(config.isRequireUnmeteredNetwork()
                        ? NetworkType.UNMETERED
                        : NetworkType.CONNECTED)
                .build();
    }
}
