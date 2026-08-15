package com.hcrobotics.updater.work;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hcrobotics.updater.OtaConfig;
import com.hcrobotics.updater.OtaUpdater;
import com.hcrobotics.updater.UpdateInfo;
import com.hcrobotics.updater.internal.AppVersion;
import com.hcrobotics.updater.internal.ManifestFetcher;
import com.hcrobotics.updater.internal.UpdaterLog;
import com.hcrobotics.updater.notify.UpdateNotifier;

import java.io.IOException;

/**
 * Background job that fetches the manifest and decides whether an update
 * should be offered.
 *
 * <h2>Why WorkManager rather than a timer or a service</h2>
 * The check has to survive the app being closed, the device rebooting, and
 * Android's aggressive background restrictions. Nothing else meets all three:
 *
 * <table border="1">
 *   <caption>Background scheduling options</caption>
 *   <tr><th>Approach</th><th>Why it fails</th></tr>
 *   <tr><td>{@code Handler.postDelayed}</td>
 *       <td>Dies with the process. Gone the moment the app is closed.</td></tr>
 *   <tr><td>{@code AlarmManager}</td>
 *       <td>Survives, but is deferred by Doze and lost on reboot unless a
 *           BOOT_COMPLETED receiver reschedules it by hand.</td></tr>
 *   <tr><td>Foreground service</td>
 *       <td>Works, but shows a permanent notification and drains battery for
 *           a task that runs for two seconds every few hours.</td></tr>
 *   <tr><td><b>WorkManager</b></td>
 *       <td>Persists the schedule in its own database, restores it after
 *           reboot, honours constraints, and retries with backoff.</td></tr>
 * </table>
 *
 * <h2>What "periodic" really means</h2>
 * Android batches background work to save battery, so a six-hour interval means
 * "at least six hours", not "exactly every six hours". A device in Doze may go
 * considerably longer. That is correct behaviour for a background check, and it
 * is why {@link OtaUpdater#checkNow(Context)} exists for the cases where you
 * genuinely need an immediate answer.
 *
 * <h2>Why this class must not be obfuscated</h2>
 * WorkManager stores the worker's fully-qualified class NAME in its database
 * and reflectively instantiates it later. If R8 renamed the class, that lookup
 * would fail after the app was updated. {@code consumer-rules.pro} keeps it.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class UpdateCheckWorker extends Worker {

    /**
     * Required by WorkManager, which constructs workers reflectively.
     *
     * @param context    the application context
     * @param parameters WorkManager's execution parameters
     */
    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    /**
     * Performs one update check.
     *
     * <p>Runs on a WorkManager background thread, so blocking network calls are
     * expected and correct here.</p>
     *
     * <h3>Return values and what they mean</h3>
     * <ul>
     *   <li>{@code success()} - the check completed. Note this means "the check
     *       ran", not "an update was found"; finding nothing is a successful
     *       check.</li>
     *   <li>{@code retry()} - a transient problem such as a dropped connection.
     *       WorkManager retries with exponential backoff.</li>
     *   <li>{@code failure()} - a permanent problem such as missing
     *       configuration, where retrying would only waste battery.</li>
     * </ul>
     *
     * @return the outcome of this run
     */
    @NonNull
    @Override
    public Result doWork() {
        final Context context = getApplicationContext();

        final OtaConfig config = OtaConfig.load(context);
        if (config == null) {
            UpdaterLog.e("Update check skipped: no configuration was found. "
                    + "Call OtaUpdater.initialise() from your Application.onCreate().", null);
            // Permanent until the app is fixed; retrying cannot help.
            return Result.failure();
        }

        UpdaterLog.setDebugLoggingEnabled(config.isDebugLogging());

        final long installedVersion = AppVersion.installedVersionCode(context);
        UpdaterLog.i("Running update check | installedVersionCode=" + installedVersion);

        final UpdateInfo update;
        try {
            update = ManifestFetcher.fetch(config.getManifestUrl());
        } catch (IOException e) {
            UpdaterLog.w("Update check could not reach the manifest, will retry: " + e.getMessage());
            // Network problems are transient by nature.
            return Result.retry();
        }

        UpdaterLog.i("Manifest reports version " + update.getVersionCode()
                + " (" + update.getVersionName() + ")");

        // ---- Decision 1: is the published build actually newer? -------------
        if (update.getVersionCode() <= installedVersion) {
            UpdaterLog.i("Already up to date; nothing to do");
            OtaUpdater.clearPendingUpdate(context);
            return Result.success();
        }

        // ---- Decision 2: can THIS device even run it? -----------------------
        // Without this check a fleet containing older tablets would be nagged
        // about an update they are structurally unable to install.
        if (update.getMinSdk() > 0 && Build.VERSION.SDK_INT < update.getMinSdk()) {
            UpdaterLog.w("Version " + update.getVersionName() + " requires API "
                    + update.getMinSdk() + " but this device is API " + Build.VERSION.SDK_INT
                    + "; not offering it.");
            return Result.success();
        }

        // ---- An update applies: remember it and tell the user ---------------
        UpdaterLog.i("Update available: " + installedVersion + " -> " + update.getVersionCode());
        OtaUpdater.storePendingUpdate(context, update);
        UpdateNotifier.showUpdateAvailable(context, update);

        return Result.success();
    }
}
