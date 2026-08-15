package com.hcrobotics.updater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * An immutable description of one published release, parsed from the remote
 * update manifest.
 *
 * <h2>The manifest format</h2>
 * The server side of this module is a single static JSON file. There is no
 * application server, no database and no API - which is precisely what makes
 * the whole system free to run. Host it anywhere that serves a file over
 * HTTPS: GitHub raw, GitHub Pages, S3, or any web server.
 *
 * <pre>
 * {
 *   "versionCode":   2,
 *   "versionName":   "1.1.0",
 *   "apkUrl":        "https://github.com/OWNER/REPO/releases/download/v1.1.0/app.apk",
 *   "sha256":        "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
 *   "sizeBytes":     5538258,
 *   "minSdk":        24,
 *   "mandatory":     false,
 *   "releaseNotes":  "What changed in this release",
 *   "publishedAt":   "2026-08-15T11:00:00Z"
 * }
 * </pre>
 *
 * <h2>Field reference</h2>
 * <table border="1">
 *   <caption>Manifest fields</caption>
 *   <tr><th>Field</th><th>Required</th><th>Purpose</th></tr>
 *   <tr><td>versionCode</td><td>yes</td>
 *       <td>The comparison key. An update is offered only when this is
 *           strictly greater than the installed versionCode.</td></tr>
 *   <tr><td>versionName</td><td>yes</td><td>Shown to the user.</td></tr>
 *   <tr><td>apkUrl</td><td>yes</td>
 *       <td>Direct HTTPS link to the APK.</td></tr>
 *   <tr><td>sha256</td><td>yes</td>
 *       <td>Hex digest of the APK. Verified after download; a mismatch aborts
 *           the install. This is the module's integrity guarantee.</td></tr>
 *   <tr><td>sizeBytes</td><td>no</td>
 *       <td>Enables an accurate progress bar and a download-size warning.</td></tr>
 *   <tr><td>minSdk</td><td>no</td>
 *       <td>Devices below this API level are never offered the update, so an
 *           old tablet is not handed a build it cannot install.</td></tr>
 *   <tr><td>mandatory</td><td>no</td>
 *       <td>When true the update screen hides its "Later" button.</td></tr>
 *   <tr><td>releaseNotes</td><td>no</td><td>Shown to the user.</td></tr>
 *   <tr><td>publishedAt</td><td>no</td><td>Informational only.</td></tr>
 * </table>
 *
 * <h2>Why this class is immutable</h2>
 * An instance is created on a WorkManager background thread, handed to the
 * notification layer, then read again by the update screen on the main thread.
 * Making every field {@code final} means it can cross threads with no
 * synchronisation and no chance of one component mutating another's copy.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class UpdateInfo {

    // ---- JSON keys, declared once so a typo cannot diverge between the -------
    // ---- parser and the serialiser. -----------------------------------------
    private static final String KEY_VERSION_CODE = "versionCode";
    private static final String KEY_VERSION_NAME = "versionName";
    private static final String KEY_APK_URL = "apkUrl";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_SIZE_BYTES = "sizeBytes";
    private static final String KEY_MIN_SDK = "minSdk";
    private static final String KEY_MANDATORY = "mandatory";
    private static final String KEY_RELEASE_NOTES = "releaseNotes";
    private static final String KEY_PUBLISHED_AT = "publishedAt";
    private static final String KEY_PREVIOUS = "previous";

    private final long versionCode;
    private final String versionName;
    private final String apkUrl;
    private final String sha256;
    private final long sizeBytes;
    private final int minSdk;
    private final boolean mandatory;
    private final String releaseNotes;
    private final String publishedAt;

    /**
     * The release published immediately before this one, or {@code null} if
     * this is the first.
     *
     * <p>Carrying the prior release in the manifest is what makes a rollback
     * possible at all. A device that has taken a bad update has no other way to
     * learn where the previous APK lives — it cannot ask a server, because
     * there is no server.</p>
     *
     * <p>Exactly one level deep: a {@code previous} entry never has a
     * {@code previous} of its own. Rollback goes back one version, not to an
     * arbitrary point in history, which keeps both the manifest and the
     * decision simple.</p>
     */
    private final UpdateInfo previous;

    /**
     * Creates an update description. Prefer {@link #fromJson(JSONObject)} for
     * data arriving from the network.
     *
     * @param versionCode  version code of the published build
     * @param versionName  human-readable version of the published build
     * @param apkUrl       HTTPS URL of the APK
     * @param sha256       lowercase hex SHA-256 digest of the APK
     * @param sizeBytes    size of the APK in bytes, or {@code 0} if unknown
     * @param minSdk       minimum API level, or {@code 0} for no restriction
     * @param mandatory    whether the user may postpone the update
     * @param releaseNotes user-facing summary of the release
     * @param publishedAt  ISO-8601 publication timestamp, informational
     * @param previous     the release published before this one, or {@code null}
     */
    public UpdateInfo(long versionCode,
                      @NonNull String versionName,
                      @NonNull String apkUrl,
                      @NonNull String sha256,
                      long sizeBytes,
                      int minSdk,
                      boolean mandatory,
                      @NonNull String releaseNotes,
                      @NonNull String publishedAt,
                      @Nullable UpdateInfo previous) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.minSdk = minSdk;
        this.mandatory = mandatory;
        this.releaseNotes = releaseNotes;
        this.publishedAt = publishedAt;
        this.previous = previous;
    }

    /**
     * Parses a manifest document.
     *
     * <p>Required fields throw if absent, because continuing with a half-formed
     * update descriptor would fail later in a much harder place to diagnose.
     * Optional fields fall back to safe defaults.</p>
     *
     * <p>The {@code sha256} value is lower-cased and stripped of whitespace so
     * that a digest pasted from any tool compares correctly.</p>
     *
     * @param json the parsed manifest document
     * @return the update it describes
     * @throws JSONException if a required field is missing or malformed
     */
    @NonNull
    public static UpdateInfo fromJson(@NonNull JSONObject json) throws JSONException {
        /*
         * The nested "previous" entry is parsed one level deep only. A malformed
         * or absent one is tolerated rather than fatal: losing the ability to
         * roll back is a degraded experience, whereas rejecting the whole
         * manifest would stop the device updating at all. The less important
         * feature must never break the more important one.
         */
        UpdateInfo previousRelease = null;
        final JSONObject previousJson = json.optJSONObject(KEY_PREVIOUS);
        if (previousJson != null) {
            try {
                previousRelease = new UpdateInfo(
                        previousJson.getLong(KEY_VERSION_CODE),
                        previousJson.getString(KEY_VERSION_NAME),
                        previousJson.getString(KEY_APK_URL),
                        previousJson.getString(KEY_SHA256).trim().toLowerCase(java.util.Locale.US),
                        previousJson.optLong(KEY_SIZE_BYTES, 0L),
                        previousJson.optInt(KEY_MIN_SDK, 0),
                        false,
                        previousJson.optString(KEY_RELEASE_NOTES, ""),
                        previousJson.optString(KEY_PUBLISHED_AT, ""),
                        null);
            } catch (JSONException ignored) {
                // Rollback simply becomes unavailable; the update still works.
                previousRelease = null;
            }
        }

        return new UpdateInfo(
                json.getLong(KEY_VERSION_CODE),
                json.getString(KEY_VERSION_NAME),
                json.getString(KEY_APK_URL),
                json.getString(KEY_SHA256).trim().toLowerCase(java.util.Locale.US),
                json.optLong(KEY_SIZE_BYTES, 0L),
                json.optInt(KEY_MIN_SDK, 0),
                json.optBoolean(KEY_MANDATORY, false),
                json.optString(KEY_RELEASE_NOTES, ""),
                json.optString(KEY_PUBLISHED_AT, ""),
                previousRelease);
    }

    /**
     * Serialises this update back to JSON.
     *
     * <p>Used to hand the pending update between components that cannot share
     * object references: from the background worker to the notification, and
     * from the notification into the update screen via an Intent extra.</p>
     *
     * @return a JSON document equivalent to the one this was parsed from
     * @throws JSONException if serialisation fails
     */
    @NonNull
    public JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject()
                .put(KEY_VERSION_CODE, versionCode)
                .put(KEY_VERSION_NAME, versionName)
                .put(KEY_APK_URL, apkUrl)
                .put(KEY_SHA256, sha256)
                .put(KEY_SIZE_BYTES, sizeBytes)
                .put(KEY_MIN_SDK, minSdk)
                .put(KEY_MANDATORY, mandatory)
                .put(KEY_RELEASE_NOTES, releaseNotes)
                .put(KEY_PUBLISHED_AT, publishedAt);
        if (previous != null) {
            json.put(KEY_PREVIOUS, previous.toJson());
        }
        return json;
    }

    /**
     * Returns the release published immediately before this one.
     *
     * <p>This is the rollback target. {@code null} means rollback is
     * unavailable, either because this is the first release or because the
     * manifest omitted the entry.</p>
     *
     * @return the previous release, or {@code null}
     */
    @Nullable
    public UpdateInfo getPrevious() {
        return previous;
    }

    /**
     * Parses a serialised update, tolerating malformed input.
     *
     * @param raw output of {@link #toJson()}, or {@code null}
     * @return the update, or {@code null} if {@code raw} was null or unparseable
     */
    @Nullable
    public static UpdateInfo fromStoredString(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    /** @return version code of the published build */
    public long getVersionCode() {
        return versionCode;
    }

    /** @return human-readable version of the published build */
    @NonNull
    public String getVersionName() {
        return versionName;
    }

    /** @return HTTPS URL the APK is downloaded from */
    @NonNull
    public String getApkUrl() {
        return apkUrl;
    }

    /** @return expected lowercase hex SHA-256 digest of the APK */
    @NonNull
    public String getSha256() {
        return sha256;
    }

    /** @return APK size in bytes, or {@code 0} if the manifest omitted it */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** @return minimum API level required, or {@code 0} for no restriction */
    public int getMinSdk() {
        return minSdk;
    }

    /** @return {@code true} if the user must not be allowed to postpone */
    public boolean isMandatory() {
        return mandatory;
    }

    /** @return user-facing release notes; empty string if none were published */
    @NonNull
    public String getReleaseNotes() {
        return releaseNotes;
    }

    /** @return ISO-8601 publication timestamp; empty string if none */
    @NonNull
    public String getPublishedAt() {
        return publishedAt;
    }

    /**
     * Formats {@link #getSizeBytes()} for display, e.g. {@code "5.3 MB"}.
     *
     * @return a human-readable size, or an empty string if the size is unknown
     */
    @NonNull
    public String getFormattedSize() {
        if (sizeBytes <= 0) {
            return "";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", sizeBytes / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }

    @NonNull
    @Override
    public String toString() {
        return "UpdateInfo{versionCode=" + versionCode
                + ", versionName='" + versionName + '\''
                + ", sizeBytes=" + sizeBytes
                + ", mandatory=" + mandatory + '}';
    }
}
