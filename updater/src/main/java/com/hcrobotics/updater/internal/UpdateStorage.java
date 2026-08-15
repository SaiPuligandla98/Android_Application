package com.hcrobotics.updater.internal;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hcrobotics.updater.UpdateInfo;

import java.io.File;

/**
 * Decides where downloaded APKs live on disk, and cleans up after them.
 *
 * <h2>Why internal storage, not external</h2>
 * Downloads go to {@code getFilesDir()/ota/} - the app's private internal
 * storage. That choice buys three things:
 *
 * <ul>
 *   <li><b>No permissions.</b> Writing anywhere outside private storage would
 *       drag in the storage-permission maze that changed in API 29, 30 and 33.
 *       Private storage needs nothing on any version.</li>
 *   <li><b>No tampering.</b> No other app can read or replace a file here, so
 *       an APK cannot be swapped between the digest check and the install.</li>
 *   <li><b>Automatic cleanup.</b> Uninstalling the app removes the directory,
 *       leaving nothing orphaned on the device.</li>
 * </ul>
 *
 * <p>Handing that private file to the system installer is not a problem:
 * {@code ApkInstaller} streams the bytes into a {@code PackageInstaller}
 * session rather than sharing a file path, so the installer never needs
 * read access to our directory.</p>
 *
 * <h2>Housekeeping</h2>
 * Each APK is named after its version code, so downloads for different versions
 * never collide. {@link #deleteAllExcept(Context, File)} removes older
 * downloads, which matters on a device that has taken several updates: without
 * it, every APK ever downloaded would sit in storage forever.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class UpdateStorage {

    /** Subdirectory of internal storage holding downloaded APKs. */
    private static final String DIRECTORY_NAME = "ota";

    /** Utility class; never instantiated. */
    private UpdateStorage() {
        throw new AssertionError("UpdateStorage is a utility class.");
    }

    /**
     * Returns the download directory, creating it if necessary.
     *
     * @param context any context
     * @return the directory downloads are written to
     */
    @NonNull
    public static File downloadDirectory(@NonNull Context context) {
        final File directory = new File(context.getFilesDir(), DIRECTORY_NAME);
        if (!directory.exists() && !directory.mkdirs()) {
            UpdaterLog.w("Could not create the download directory: " + directory.getAbsolutePath());
        }
        return directory;
    }

    /**
     * Returns the file a given update downloads to.
     *
     * <p>Naming the file after the version code means a half-finished download
     * of version 3 can never be mistaken for a complete download of version 2.</p>
     *
     * @param context any context
     * @param update  the update being downloaded
     * @return the target file, which may or may not already exist
     */
    @NonNull
    public static File apkFileFor(@NonNull Context context, @NonNull UpdateInfo update) {
        return new File(downloadDirectory(context), "update-" + update.getVersionCode() + ".apk");
    }

    /**
     * Deletes every downloaded APK except one.
     *
     * <p>Called once a download has been verified, to reclaim the space used by
     * previous updates while keeping the file about to be installed.</p>
     *
     * @param context any context
     * @param keep    the file to preserve; pass {@code null} to delete everything
     */
    public static void deleteAllExcept(@NonNull Context context, File keep) {
        final File[] files = downloadDirectory(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (keep != null && file.getAbsolutePath().equals(keep.getAbsolutePath())) {
                continue;
            }
            if (file.delete()) {
                UpdaterLog.d("Removed stale download: " + file.getName());
            }
        }
    }

    /**
     * Checks whether an update has already been downloaded and verified.
     *
     * <p>Lets the update screen skip straight to installing when the user taps
     * the notification a second time, instead of downloading the same bytes
     * again. The digest is re-checked rather than trusted, since a file on disk
     * proves only that a download finished, not that it finished correctly.</p>
     *
     * @param context any context
     * @param update  the update to look for
     * @return {@code true} if a verified copy is already on disk
     */
    public static boolean isAlreadyDownloaded(@NonNull Context context, @NonNull UpdateInfo update) {
        final File file = apkFileFor(context, update);
        if (!file.exists() || file.length() == 0) {
            return false;
        }
        try {
            final boolean valid = Digest.matches(update.getSha256(), Digest.sha256(file));
            if (!valid) {
                UpdaterLog.w("Discarding a previously downloaded APK: digest does not match");
                // A corrupt file must not linger; it would fail every future check.
                if (!file.delete()) {
                    UpdaterLog.w("Could not delete the corrupt download: " + file.getName());
                }
            }
            return valid;
        } catch (Exception e) {
            UpdaterLog.e("Could not verify the existing download", e);
            return false;
        }
    }
}
