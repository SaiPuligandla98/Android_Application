package com.hcrobotics.updater.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * Shared HTTP plumbing for the two things this module fetches: the update
 * manifest and the APK.
 *
 * <h2>Why raw HttpURLConnection rather than OkHttp or Retrofit</h2>
 * Those libraries are excellent, and completely unnecessary here. This module
 * makes exactly two GET requests. Adding a networking stack would mean the
 * library forces a specific OkHttp version onto every host application, which
 * is the single most common source of dependency conflicts in Android builds.
 *
 * <p>{@link HttpURLConnection} ships with the platform, so the module stays
 * genuinely drop-in.</p>
 *
 * <h2>The redirect problem this class solves</h2>
 * {@code HttpURLConnection} follows redirects automatically, but silently
 * refuses to when the protocol changes - including HTTPS to HTTPS across
 * different hosts in some configurations. That matters because a GitHub release
 * download always redirects:
 *
 * <pre>
 *   github.com/OWNER/REPO/releases/download/...
 *        -> 302 -> objects.githubusercontent.com/...
 * </pre>
 *
 * <p>Left to the default behaviour, the download would return a zero-byte body
 * and fail its digest check with no obvious explanation. {@link #open(String)}
 * follows redirects explicitly, and re-validates HTTPS at every hop so a
 * redirect cannot quietly downgrade the connection to plain HTTP.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class HttpSupport {

    /** Time allowed to establish a TCP connection, in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 15_000;

    /** Time allowed between bytes once connected, in milliseconds. */
    public static final int READ_TIMEOUT_MS = 30_000;

    /**
     * Redirect hops to follow before giving up.
     *
     * <p>Five is generous for a static file host and low enough that a
     * misconfigured server redirecting to itself fails fast instead of looping
     * until the timeout.</p>
     */
    private static final int MAX_REDIRECTS = 5;

    /** Identifies this module in server logs, which helps when debugging. */
    private static final String USER_AGENT = "HCRobotics-OtaUpdater/1.0 (Android)";

    /** Utility class; never instantiated. */
    private HttpSupport() {
        throw new AssertionError("HttpSupport is a utility class.");
    }

    /**
     * Opens a connection to {@code url}, following redirects manually.
     *
     * @param url an {@code https://} URL
     * @return a connected {@link HttpURLConnection}; the caller must
     *         {@code disconnect()} it
     * @throws IOException if the URL is not HTTPS, too many redirects occur, or
     *                     the server returns a non-200 status
     */
    @NonNull
    public static HttpURLConnection open(@NonNull String url) throws IOException {
        return open(url, null);
    }

    /**
     * Opens a connection to {@code url} with additional request headers,
     * following redirects manually.
     *
     * <p>The returned connection has already been CONNECTED and its status code
     * checked, so the caller can read the body immediately.</p>
     *
     * <p><b>This is why extra headers must be passed in rather than set
     * afterwards.</b> {@link HttpURLConnection#setRequestProperty} throws
     * {@link IllegalStateException} once a connection has been made, and this
     * method connects internally in order to resolve redirects. A caller doing
     * {@code open(url).setRequestProperty(...)} would crash on the success path
     * only - never on an error path, where the exception is thrown before the
     * call is reached. That makes it exactly the kind of bug that survives
     * testing and fails in the field.</p>
     *
     * <p>Headers are re-applied at every redirect hop, so they survive the
     * hand-off to a CDN host.</p>
     *
     * @param url          an {@code https://} URL
     * @param extraHeaders headers to send, or {@code null} for none
     * @return a connected {@link HttpURLConnection} positioned at the final
     *         resource; the caller must {@code disconnect()} it
     * @throws IOException if the URL is not HTTPS, too many redirects occur, or
     *                     the server returns a non-200 status
     */
    @NonNull
    public static HttpURLConnection open(@NonNull String url,
                                         @Nullable Map<String, String> extraHeaders)
            throws IOException {
        String currentUrl = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requireHttps(currentUrl);

            final HttpURLConnection connection = (HttpURLConnection) new URL(currentUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            // Redirects are handled below so every hop can be re-validated.
            connection.setInstanceFollowRedirects(false);

            final int status = connection.getResponseCode();

            if (isRedirect(status)) {
                final String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Server returned HTTP " + status
                            + " but no Location header, so the redirect cannot be followed: "
                            + currentUrl);
                }
                // A relative Location is resolved against the URL it came from.
                currentUrl = new URL(new URL(currentUrl), location).toString();
                UpdaterLog.d("Following redirect " + (hop + 1) + " to " + currentUrl);
                continue;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("Server returned HTTP " + status + " for " + currentUrl
                        + describeCommonStatus(status));
            }

            return connection;
        }

        throw new IOException("Gave up after " + MAX_REDIRECTS
                + " redirects starting from " + url);
    }

    /**
     * Rejects any URL that is not HTTPS.
     *
     * <p>Applied at every redirect hop, not just the first, so a compromised
     * host cannot bounce the device onto plain HTTP where the manifest or APK
     * could be substituted in transit.</p>
     *
     * @param url the URL about to be requested
     * @throws IOException if {@code url} is not HTTPS
     */
    private static void requireHttps(@NonNull String url) throws IOException {
        if (!url.toLowerCase(Locale.US).startsWith("https://")) {
            throw new IOException("Refusing a non-HTTPS URL. Update traffic decides which code "
                    + "runs on this device and must not travel in the clear: " + url);
        }
    }

    /**
     * @param status an HTTP status code
     * @return {@code true} if it is one of the redirect codes worth following
     */
    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM      // 301
                || status == HttpURLConnection.HTTP_MOVED_TEMP  // 302
                || status == HttpURLConnection.HTTP_SEE_OTHER   // 303
                || status == 307                                // Temporary Redirect
                || status == 308;                               // Permanent Redirect
    }

    /**
     * Turns the statuses this module actually hits into actionable advice.
     *
     * <p>"HTTP 404" in a log tells you nothing at 2am. "the manifest URL is
     * wrong or the release asset was deleted" tells you where to look.</p>
     *
     * @param status the status code received
     * @return a human-readable explanation, or an empty string
     */
    @NonNull
    private static String describeCommonStatus(int status) {
        switch (status) {
            case HttpURLConnection.HTTP_NOT_FOUND:
                return " -- the manifest URL is wrong, the branch/path does not exist, "
                        + "or the release asset was deleted.";
            case HttpURLConnection.HTTP_FORBIDDEN:
                return " -- access denied. If the repository is private, its raw and release "
                        + "URLs require an access token; make the repository public or host the "
                        + "files somewhere the devices can reach anonymously.";
            case 429:
                return " -- rate limited. Increase checkIntervalHours.";
            default:
                return "";
        }
    }
}
