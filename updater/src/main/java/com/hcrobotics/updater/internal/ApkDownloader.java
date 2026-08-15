package com.hcrobotics.updater.internal;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.hcrobotics.updater.UpdateInfo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Downloads an update's APK and verifies it before letting anything install it.
 *
 * <h2>The contract this class enforces</h2>
 * A download either produces a file whose SHA-256 matches the manifest exactly,
 * or it produces no file at all. There is no in-between state, and a failed
 * verification deletes the partial file rather than leaving it for something
 * else to pick up later.
 *
 * <p>That is the difference between an update system and a way to install
 * arbitrary bytes.</p>
 *
 * <h2>Download to a temporary name, then rename</h2>
 * Bytes are written to {@code <target>.part} and renamed onto the real filename
 * only after the digest check passes. If the process is killed mid-download -
 * the user swipes the app away, or Android reclaims memory - the final filename
 * never comes into existence, so a truncated file can never be mistaken for a
 * complete one.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class ApkDownloader {

    /** 16 KB per read: fewer syscalls than 8 KB, still a trivial allocation. */
    private static final int BUFFER_SIZE = 16 * 1024;

    /** Suffix marking a download that has not yet been verified. */
    private static final String PARTIAL_SUFFIX = ".part";

    /**
     * Receives progress updates and the final outcome of a download.
     *
     * <p>Every method is invoked on the calling (background) thread. Anything
     * that touches the UI must hop to the main thread itself; the update screen
     * does this with a {@code Handler}.</p>
     */
    public interface ProgressListener {

        /**
         * Reports download progress.
         *
         * @param bytesDownloaded bytes written so far
         * @param totalBytes      expected total, or {@code -1} if the server did
         *                        not report a length
         * @param percent         completion from 0 to 100, or {@code -1} if the
         *                        total is unknown
         */
        void onProgress(long bytesDownloaded, long totalBytes, int percent);

        /**
         * Called once the download has completed AND its digest has been
         * verified. The file is safe to install.
         *
         * @param apkFile the verified APK
         */
        void onCompleted(@NonNull File apkFile);

        /**
         * Called when the download or verification failed. No file remains.
         *
         * @param message a human-readable explanation
         * @param cause   the underlying exception, or {@code null}
         */
        void onFailed(@NonNull String message, Throwable cause);
    }

    /** Utility class; never instantiated. */
    private ApkDownloader() {
        throw new AssertionError("ApkDownloader is a utility class.");
    }

    /**
     * Downloads and verifies the APK for {@code update}.
     *
     * <p>Blocking; call from a background thread. Progress and the outcome are
     * reported through {@code listener}.</p>
     *
     * <p>If a verified copy is already on disk the download is skipped entirely
     * and {@link ProgressListener#onCompleted} fires immediately - so tapping
     * the notification twice does not re-download several megabytes.</p>
     *
     * @param context  any context
     * @param update   the update to fetch
     * @param listener receives progress and the result
     */
    @WorkerThread
    public static void download(@NonNull Context context,
                                @NonNull UpdateInfo update,
                                @NonNull ProgressListener listener) {

        final File target = UpdateStorage.apkFileFor(context, update);

        if (UpdateStorage.isAlreadyDownloaded(context, update)) {
            UpdaterLog.i("A verified copy of version " + update.getVersionCode()
                    + " is already on disk; skipping the download");
            listener.onCompleted(target);
            return;
        }

        final File partial = new File(target.getAbsolutePath() + PARTIAL_SUFFIX);
        HttpURLConnection connection = null;

        try {
            UpdaterLog.i("Downloading APK for version " + update.getVersionCode()
                    + " from " + update.getApkUrl());

            connection = HttpSupport.open(update.getApkUrl());

            // Prefer the server's Content-Length; fall back to the manifest's
            // declared size so the progress bar still works if the CDN omits it.
            long totalBytes = connection.getContentLengthLong();
            if (totalBytes <= 0) {
                totalBytes = update.getSizeBytes() > 0 ? update.getSizeBytes() : -1L;
            }

            transfer(connection, partial, totalBytes, listener);

            // ---- Verification: the step that makes this safe --------------
            final String actualDigest = Digest.sha256(partial);
            if (!Digest.matches(update.getSha256(), actualDigest)) {
                deleteQuietly(partial);
                listener.onFailed("The downloaded file does not match the published checksum, so "
                        + "it will not be installed. Expected " + update.getSha256()
                        + " but computed " + actualDigest
                        + ". Re-publish the manifest with the correct sha256, or retry the download.", null);
                return;
            }

            // Atomically promote the verified file to its final name.
            deleteQuietly(target);
            if (!partial.renameTo(target)) {
                deleteQuietly(partial);
                listener.onFailed("Could not finalise the downloaded file on disk.", null);
                return;
            }

            UpdateStorage.deleteAllExcept(context, target);
            UpdaterLog.i("Download verified: " + target.getName()
                    + " (" + target.length() + " bytes)");
            listener.onCompleted(target);

        } catch (IOException e) {
            deleteQuietly(partial);
            UpdaterLog.e("APK download failed", e);
            listener.onFailed(describeFailure(e), e);
        } catch (Exception e) {
            deleteQuietly(partial);
            UpdaterLog.e("Unexpected failure during download", e);
            listener.onFailed("Unexpected error while downloading the update: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Streams the response body to disk, reporting progress as it goes.
     *
     * <p>Progress is only reported when the whole-number percentage actually
     * changes. Without that guard a 5 MB download would fire several hundred
     * callbacks, each posting to the main thread, and the progress bar would
     * cost more than the download.</p>
     *
     * @param connection connected HTTP connection positioned at the APK
     * @param destination file to write to
     * @param totalBytes expected size, or {@code -1} if unknown
     * @param listener   receives progress
     * @throws IOException if reading or writing fails
     */
    private static void transfer(@NonNull HttpURLConnection connection,
                                 @NonNull File destination,
                                 long totalBytes,
                                 @NonNull ProgressListener listener) throws IOException {

        long downloaded = 0L;
        int lastReportedPercent = -1;

        try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
             OutputStream out = new FileOutputStream(destination)) {

            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;

                final int percent = totalBytes > 0
                        ? (int) ((downloaded * 100L) / totalBytes)
                        : -1;

                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent;
                    listener.onProgress(downloaded, totalBytes, percent);
                }
            }
            out.flush();
        }

        if (totalBytes > 0 && downloaded != totalBytes) {
            throw new IOException("The download ended early: expected " + totalBytes
                    + " bytes but received " + downloaded + ".");
        }
    }

    /**
     * Converts a network exception into something worth showing a user.
     *
     * @param e the failure
     * @return a message that suggests what to do about it
     */
    @NonNull
    private static String describeFailure(@NonNull IOException e) {
        final String detail = e.getMessage() != null ? e.getMessage() : e.toString();
        if (e instanceof java.net.UnknownHostException) {
            return "Could not reach the update server. Check the device's internet connection.";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "The download timed out. The connection may be too slow or unstable; "
                    + "it will be retried on the next check.";
        }
        return "The update could not be downloaded. " + detail;
    }

    /**
     * Deletes a file, logging rather than throwing if it cannot be removed.
     *
     * @param file the file to remove; may not exist
     */
    private static void deleteQuietly(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            UpdaterLog.w("Could not delete " + file.getName());
        }
    }
}
