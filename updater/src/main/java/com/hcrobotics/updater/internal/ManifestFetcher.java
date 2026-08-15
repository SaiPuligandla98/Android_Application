package com.hcrobotics.updater.internal;

import androidx.annotation.NonNull;

import com.hcrobotics.updater.UpdateInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads and parses the remote update manifest.
 *
 * <h2>The one cache trap that breaks OTA on GitHub</h2>
 * {@code raw.githubusercontent.com} serves through a CDN that caches responses
 * for roughly five minutes. Publish a new manifest, check immediately, and the
 * device is handed the OLD document - so the update appears not to exist and
 * you go looking for a bug that is not there.
 *
 * <p>Two defences are applied together:</p>
 * <ul>
 *   <li>A changing query parameter ({@code ?_=<timestamp>}), which makes each
 *       request a distinct cache key.</li>
 *   <li>{@code Cache-Control: no-cache} and {@code Pragma: no-cache} headers,
 *       for intermediate proxies that honour them.</li>
 * </ul>
 *
 * <p>The query parameter is what actually does the work; the headers are belt
 * and braces for corporate proxies.</p>
 *
 * <h2>Size limit</h2>
 * The response is capped at {@link #MAX_MANIFEST_BYTES}. A manifest is a few
 * hundred bytes; anything larger means the URL is wrong (an HTML error page, a
 * redirect to a login screen) and reading it into memory unbounded would be a
 * denial-of-service against our own app.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class ManifestFetcher {

    /**
     * Largest manifest accepted, in bytes.
     *
     * <p>64 KB is roughly a hundred times a realistic manifest, so it will never
     * reject a legitimate document while still bounding memory use.</p>
     */
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    /** Utility class; never instantiated. */
    private ManifestFetcher() {
        throw new AssertionError("ManifestFetcher is a utility class.");
    }

    /**
     * Fetches and parses the manifest.
     *
     * <p>Blocking. Must be called from a background thread; WorkManager provides
     * one, and the update screen uses its own executor.</p>
     *
     * @param manifestUrl HTTPS URL of the manifest JSON
     * @return the update the manifest describes
     * @throws IOException if the request fails, the response is too large, or
     *                     the body is not a valid manifest
     */
    @NonNull
    public static UpdateInfo fetch(@NonNull String manifestUrl) throws IOException {
        final String url = appendCacheBuster(manifestUrl);
        UpdaterLog.d("Fetching update manifest: " + url);

        HttpURLConnection connection = null;
        try {
            /*
             * The no-cache headers MUST be supplied to open(), not set on the
             * returned connection: open() connects internally to resolve
             * redirects, and setRequestProperty() throws once a connection has
             * been made.
             */
            final Map<String, String> noCacheHeaders = new HashMap<>();
            noCacheHeaders.put("Cache-Control", "no-cache, no-store, max-age=0");
            noCacheHeaders.put("Pragma", "no-cache");

            connection = HttpSupport.open(url, noCacheHeaders);

            final String body = readBody(connection);
            UpdaterLog.d("Manifest received (" + body.length() + " chars)");

            try {
                return UpdateInfo.fromJson(new JSONObject(body));
            } catch (JSONException e) {
                throw new IOException("The manifest is not valid JSON, or is missing a required "
                        + "field (versionCode, versionName, apkUrl, sha256). Received: "
                        + preview(body), e);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Appends a changing query parameter so CDNs treat each request as new.
     *
     * @param url the configured manifest URL
     * @return the same URL with a cache-busting parameter appended
     */
    @NonNull
    private static String appendCacheBuster(@NonNull String url) {
        final String separator = url.contains("?") ? "&" : "?";
        return url + separator + "_=" + System.currentTimeMillis();
    }

    /**
     * Reads the response body as UTF-8, refusing anything oversized.
     *
     * @param connection a connected HTTP connection
     * @return the body as a string
     * @throws IOException if reading fails or the body exceeds the size cap
     */
    @NonNull
    private static String readBody(@NonNull HttpURLConnection connection) throws IOException {
        final StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            final char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
                if (builder.length() > MAX_MANIFEST_BYTES) {
                    throw new IOException("The manifest response exceeded " + MAX_MANIFEST_BYTES
                            + " bytes. The URL is almost certainly pointing at an HTML page rather "
                            + "than the manifest JSON. Check that you used the RAW file URL.");
                }
            }
        }
        return builder.toString();
    }

    /**
     * Truncates a response for inclusion in an error message.
     *
     * @param body the response body
     * @return at most 200 characters of it
     */
    @NonNull
    private static String preview(@NonNull String body) {
        final String trimmed = body.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
