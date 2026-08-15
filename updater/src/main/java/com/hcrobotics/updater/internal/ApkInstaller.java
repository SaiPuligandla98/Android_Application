package com.hcrobotics.updater.internal;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a verified APK to the Android package installer.
 *
 * <h2>Why PackageInstaller and not an install Intent</h2>
 * The old approach - {@code ACTION_VIEW} on a
 * {@code application/vnd.android.package-archive} URI - still works, but it
 * requires exposing the APK through a {@code FileProvider} and gives no result
 * back. You fire the Intent and never learn what happened.
 *
 * <p>{@link PackageInstaller} is the modern API and is better on both counts:</p>
 * <ul>
 *   <li>Bytes are STREAMED into a session, so the file never leaves private
 *       storage and no {@code FileProvider} is needed at all.</li>
 *   <li>The outcome arrives as a broadcast, so success and each distinct
 *       failure can be reported instead of guessed at.</li>
 * </ul>
 *
 * <h2>The two permissions involved</h2>
 * <ol>
 *   <li>{@code REQUEST_INSTALL_PACKAGES} in the manifest. Granted at install
 *       time; the library declares it.</li>
 *   <li>"Install unknown apps", granted by the user per-app in Settings on
 *       Android 8.0+. This one cannot be requested with a normal permission
 *       dialog - the user has to visit a settings screen.
 *       {@link #canInstallPackages(Context)} detects it and
 *       {@link #unknownSourcesSettingsIntent(Context)} navigates straight
 *       there.</li>
 * </ol>
 *
 * <h2>The signature rule that catches everyone out</h2>
 * Android refuses to update an app with an APK signed by a different key. In
 * practice:
 *
 * <ul>
 *   <li>debug build updating a debug build - works (same debug keystore).</li>
 *   <li>release updating release with the SAME keystore - works.</li>
 *   <li>release updating a debug build, or a rebuilt keystore - FAILS with
 *       {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}.</li>
 * </ul>
 *
 * <p>Every OTA build must be signed with the identical keystore, and that
 * keystore must be backed up. Lose it and no device can ever be updated
 * again - they must be uninstalled and reinstalled by hand.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class ApkInstaller {

    /** Broadcast action identifying an install-session result for this module. */
    public static final String ACTION_INSTALL_RESULT =
            "com.hcrobotics.updater.INSTALL_RESULT";

    /** Name of the single APK written into each install session. */
    private static final String SESSION_FILE_NAME = "base.apk";

    /** 16 KB per write, matching the downloader. */
    private static final int BUFFER_SIZE = 16 * 1024;

    /** Utility class; never instantiated. */
    private ApkInstaller() {
        throw new AssertionError("ApkInstaller is a utility class.");
    }

    /**
     * Reports whether the user has granted "install unknown apps" to this app.
     *
     * <p>Always {@code true} below Android 8.0, where the setting was global
     * rather than per-app and the manifest permission alone was sufficient.</p>
     *
     * @param context any context
     * @return {@code true} if an install can proceed without a settings trip
     */
    public static boolean canInstallPackages(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Builds an Intent opening the "install unknown apps" screen for this app.
     *
     * <p>Deep-linked with a {@code package:} URI so the user lands on the toggle
     * for this specific app rather than a list of every app on the device.</p>
     *
     * @param context any context
     * @return an Intent to start, or {@code null} below Android 8.0 where the
     *         screen does not exist
     */
    public static Intent unknownSourcesSettingsIntent(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * Streams {@code apkFile} into a new install session and commits it.
     *
     * <p>Returns as soon as the session is committed - the install itself is
     * asynchronous. What happens next is:</p>
     *
     * <ol>
     *   <li>The system posts {@code STATUS_PENDING_USER_ACTION} to
     *       {@link InstallResultReceiver}.</li>
     *   <li>That receiver launches the confirmation dialog the system provides.</li>
     *   <li>The user confirms, and the final status arrives at the same
     *       receiver.</li>
     * </ol>
     *
     * <p>Blocking on the streaming step; call from a background thread.</p>
     *
     * @param context any context
     * @param apkFile a downloaded APK whose digest has ALREADY been verified
     * @throws IOException if the session cannot be created, written or committed
     */
    @WorkerThread
    public static void install(@NonNull Context context, @NonNull File apkFile) throws IOException {
        if (!apkFile.exists() || apkFile.length() == 0) {
            throw new IOException("The APK to install is missing or empty: " + apkFile.getAbsolutePath());
        }

        final PackageInstaller installer = context.getPackageManager().getPackageInstaller();

        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        // Naming the target package lets the system validate the APK against the
        // app being replaced before the user is ever shown a dialog.
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setSize(apkFile.length());
        }

        final int sessionId = installer.createSession(params);
        UpdaterLog.i("Created install session " + sessionId
                + " for " + apkFile.getName() + " (" + apkFile.length() + " bytes)");

        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);

            try (OutputStream out = session.openWrite(SESSION_FILE_NAME, 0, apkFile.length());
                 InputStream in = new FileInputStream(apkFile)) {
                final byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                // fsync forces the bytes to durable storage. Without it the
                // session can commit against a partially flushed file and fail
                // with an opaque error.
                session.fsync(out);
            }

            session.commit(buildResultSender(context, sessionId).getIntentSender());
            UpdaterLog.i("Install session " + sessionId + " committed; awaiting user confirmation");

        } catch (IOException e) {
            // Abandon the session so it does not linger and consume storage.
            try {
                installer.abandonSession(sessionId);
            } catch (Exception ignored) {
                // Nothing useful to do if cleanup itself fails.
            }
            throw e;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Creates the {@link PendingIntent} the system uses to report the outcome.
     *
     * <p>{@code FLAG_MUTABLE} is required from Android 12 (API 31): the system
     * fills in the status extras before delivering the broadcast, and an
     * immutable PendingIntent would make that impossible. Below API 31 the flag
     * does not exist and no mutability flag is needed.</p>
     *
     * <p>The session id is used as the request code so concurrent sessions
     * cannot overwrite one another's PendingIntent.</p>
     *
     * @param context   any context
     * @param sessionId the session this result belongs to
     * @return a PendingIntent targeting {@link InstallResultReceiver}
     */
    @NonNull
    private static PendingIntent buildResultSender(@NonNull Context context, int sessionId) {
        final Intent intent = new Intent(context, InstallResultReceiver.class)
                .setAction(ACTION_INSTALL_RESULT)
                .setPackage(context.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags);
    }
}
