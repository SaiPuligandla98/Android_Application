package com.hcrobotics.updater;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.updater.internal.UpdaterLog;

/**
 * Immutable configuration for the OTA updater, created with a builder.
 *
 * <h2>Typical use</h2>
 * <pre>
 * OtaUpdater.initialise(this, new OtaConfig.Builder()
 *         .manifestUrl("https://raw.githubusercontent.com/OWNER/REPO/master/ota/update-manifest.json")
 *         .checkIntervalHours(6)
 *         .build());
 * </pre>
 *
 * <h2>Why the configuration is persisted</h2>
 * The periodic update check runs inside WorkManager, which may execute it long
 * after the app process was killed - after a reboot, or days later. When that
 * happens the worker needs the manifest URL, but nothing has called
 * {@code initialise()} in that new process yet.
 *
 * <p>Writing the configuration to {@link SharedPreferences} during
 * {@link OtaUpdater#initialise} means the worker can always load it from disk.
 * The alternative - a static field - would be {@code null} in exactly the
 * situation the module exists to handle.</p>
 *
 * <h2>Why HTTPS is enforced</h2>
 * The builder rejects any manifest URL that is not HTTPS. Over plain HTTP an
 * attacker on the same network could rewrite the manifest and point the device
 * at an APK of their choosing. Since this module's entire job is installing
 * executable code, transport security is not optional.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class OtaConfig {

    /** Name of the preferences file holding the persisted configuration. */
    private static final String PREFS_NAME = "hc_ota_updater";

    private static final String KEY_MANIFEST_URL = "manifest_url";
    private static final String KEY_CHECK_INTERVAL_HOURS = "check_interval_hours";
    private static final String KEY_REQUIRE_UNMETERED = "require_unmetered";
    private static final String KEY_NOTIFICATION_ICON = "notification_icon";
    private static final String KEY_DEBUG_LOGGING = "debug_logging";

    /**
     * Default gap between background checks.
     *
     * <p>Six hours is a deliberate balance. More often wastes battery and
     * bandwidth for a fleet that ships updates weekly; much less often means a
     * critical fix can sit undelivered for a day. Instant delivery is what the
     * push trigger is for - see {@link OtaUpdater#checkNow(Context)}.</p>
     */
    public static final long DEFAULT_CHECK_INTERVAL_HOURS = 6L;

    /**
     * Shortest interval WorkManager will honour for periodic work, in minutes.
     *
     * <p>This is a platform limit, not our choice: requesting anything shorter
     * silently gets rounded up to 15 minutes. It is documented here so the
     * builder can validate rather than let a caller be quietly surprised.</p>
     */
    public static final long WORK_MANAGER_MINIMUM_INTERVAL_MINUTES = 15L;

    private final String manifestUrl;
    private final long checkIntervalHours;
    private final boolean requireUnmeteredNetwork;
    private final int notificationIconRes;
    private final boolean debugLogging;

    private OtaConfig(@NonNull Builder builder) {
        this.manifestUrl = builder.manifestUrl;
        this.checkIntervalHours = builder.checkIntervalHours;
        this.requireUnmeteredNetwork = builder.requireUnmeteredNetwork;
        this.notificationIconRes = builder.notificationIconRes;
        this.debugLogging = builder.debugLogging;
    }

    /** @return HTTPS URL of the remote update manifest */
    @NonNull
    public String getManifestUrl() {
        return manifestUrl;
    }

    /** @return hours between automatic background checks */
    public long getCheckIntervalHours() {
        return checkIntervalHours;
    }

    /**
     * @return {@code true} if checks and downloads should wait for an unmetered
     *         network such as Wi-Fi
     */
    public boolean isRequireUnmeteredNetwork() {
        return requireUnmeteredNetwork;
    }

    /**
     * @return drawable resource for the notification's small icon, or {@code 0}
     *         to use the icon bundled with this module
     */
    @DrawableRes
    public int getNotificationIconRes() {
        return notificationIconRes;
    }

    /** @return {@code true} if the module emits debug and info logging */
    public boolean isDebugLogging() {
        return debugLogging;
    }

    /**
     * Writes this configuration to disk so background workers in a future
     * process can read it.
     *
     * @param context any context; the application context is used internally
     */
    public void persist(@NonNull Context context) {
        prefs(context).edit()
                .putString(KEY_MANIFEST_URL, manifestUrl)
                .putLong(KEY_CHECK_INTERVAL_HOURS, checkIntervalHours)
                .putBoolean(KEY_REQUIRE_UNMETERED, requireUnmeteredNetwork)
                .putInt(KEY_NOTIFICATION_ICON, notificationIconRes)
                .putBoolean(KEY_DEBUG_LOGGING, debugLogging)
                .apply();
        UpdaterLog.d("Configuration persisted | manifestUrl=" + manifestUrl
                + " | intervalHours=" + checkIntervalHours);
    }

    /**
     * Reads back a previously persisted configuration.
     *
     * @param context any context
     * @return the stored configuration, or {@code null} if
     *         {@link OtaUpdater#initialise} has never run on this device
     */
    @Nullable
    public static OtaConfig load(@NonNull Context context) {
        final SharedPreferences prefs = prefs(context);
        final String url = prefs.getString(KEY_MANIFEST_URL, null);
        if (url == null) {
            return null;
        }
        return new Builder()
                .manifestUrl(url)
                .checkIntervalHours(prefs.getLong(KEY_CHECK_INTERVAL_HOURS, DEFAULT_CHECK_INTERVAL_HOURS))
                .requireUnmeteredNetwork(prefs.getBoolean(KEY_REQUIRE_UNMETERED, false))
                .notificationIcon(prefs.getInt(KEY_NOTIFICATION_ICON, 0))
                .debugLogging(prefs.getBoolean(KEY_DEBUG_LOGGING, false))
                .build();
    }

    /**
     * Returns the module's private preferences file.
     *
     * <p>The application context is used deliberately: holding an Activity
     * context in a long-lived object leaks the whole view hierarchy.</p>
     *
     * @param context any context
     * @return the module's {@link SharedPreferences}
     */
    @NonNull
    public static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // =========================================================================
    //  Builder
    // =========================================================================

    /**
     * Fluent builder for {@link OtaConfig}.
     *
     * <p>Only {@link #manifestUrl(String)} is required; everything else has a
     * sensible default. Validation happens in {@link #build()} so a
     * misconfiguration fails immediately at startup, with a message naming the
     * problem, rather than silently doing nothing hours later inside a
     * background worker.</p>
     */
    public static final class Builder {

        private String manifestUrl;
        private long checkIntervalHours = DEFAULT_CHECK_INTERVAL_HOURS;
        private boolean requireUnmeteredNetwork = false;
        private int notificationIconRes = 0;
        private boolean debugLogging = BuildConfig.DEBUG;

        /**
         * Sets the HTTPS URL of the update manifest. Required.
         *
         * <p>With releases hosted on GitHub, this is the raw URL of the
         * manifest committed to the repository, for example:</p>
         *
         * <pre>https://raw.githubusercontent.com/OWNER/REPO/master/ota/update-manifest.json</pre>
         *
         * @param url an {@code https://} URL serving the manifest JSON
         * @return this builder
         */
        @NonNull
        public Builder manifestUrl(@NonNull String url) {
            this.manifestUrl = url.trim();
            return this;
        }

        /**
         * Sets how often the background check runs.
         *
         * <p>Note that Android batches background work aggressively to save
         * battery, so the real interval is "at least this long", never exactly
         * this long. Treat it as a floor, not a schedule.</p>
         *
         * @param hours hours between checks; must be at least 1
         * @return this builder
         */
        @NonNull
        public Builder checkIntervalHours(long hours) {
            this.checkIntervalHours = hours;
            return this;
        }

        /**
         * Restricts checks and downloads to unmetered networks.
         *
         * <p>Worth enabling for a fleet on mobile data where APKs are large;
         * the trade-off is that a device that never sees Wi-Fi will never
         * update.</p>
         *
         * @param require {@code true} to wait for Wi-Fi
         * @return this builder
         */
        @NonNull
        public Builder requireUnmeteredNetwork(boolean require) {
            this.requireUnmeteredNetwork = require;
            return this;
        }

        /**
         * Overrides the notification's small icon with one from the host app.
         *
         * <p>Android requires this to be a white-on-transparent silhouette; a
         * full-colour icon renders as a solid white square on most launchers.
         * Leave unset to use the icon bundled with this module.</p>
         *
         * @param iconRes a drawable resource, or {@code 0} for the default
         * @return this builder
         */
        @NonNull
        public Builder notificationIcon(@DrawableRes int iconRes) {
            this.notificationIconRes = iconRes;
            return this;
        }

        /**
         * Enables or disables the module's debug and info logging.
         *
         * <p>Defaults to the module's own build type. Set it explicitly to keep
         * update diagnostics in a release build - useful for a fleet you cannot
         * physically reach.</p>
         *
         * @param enabled {@code true} to emit debug/info lines
         * @return this builder
         */
        @NonNull
        public Builder debugLogging(boolean enabled) {
            this.debugLogging = enabled;
            return this;
        }

        /**
         * Validates the configuration and builds it.
         *
         * @return the immutable configuration
         * @throws IllegalArgumentException if the manifest URL is missing, is
         *         not HTTPS, or the check interval is below one hour
         */
        @NonNull
        public OtaConfig build() {
            if (manifestUrl == null || manifestUrl.isEmpty()) {
                throw new IllegalArgumentException(
                        "OtaConfig requires a manifestUrl. Point it at the update manifest JSON, "
                                + "e.g. https://raw.githubusercontent.com/OWNER/REPO/master/ota/update-manifest.json");
            }
            if (!manifestUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "OtaConfig requires an HTTPS manifestUrl. Plain HTTP would let anyone on "
                                + "the network substitute the manifest and choose which APK this device "
                                + "installs. Refusing to continue with: " + manifestUrl);
            }
            if (checkIntervalHours < 1) {
                throw new IllegalArgumentException(
                        "checkIntervalHours must be at least 1; WorkManager will not run periodic "
                                + "work more often than every " + WORK_MANAGER_MINIMUM_INTERVAL_MINUTES
                                + " minutes in any case. Got: " + checkIntervalHours);
            }
            return new OtaConfig(this);
        }
    }
}
