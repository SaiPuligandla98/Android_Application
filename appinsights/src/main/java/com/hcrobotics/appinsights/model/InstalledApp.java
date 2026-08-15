package com.hcrobotics.appinsights.model;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One application installed on the device.
 *
 * <h2>Everything here is read without special access</h2>
 * All of these fields come from {@code PackageManager} and from reading the
 * APK file's own size on disk. None of it requires the "usage access" grant,
 * none of it involves touching the other application, and none of it can
 * affect how that application behaves.
 *
 * <h2>Why the icon is held as a Drawable</h2>
 * {@code PackageManager.getApplicationIcon()} is comparatively expensive — it
 * opens the other app's resources — so it is resolved once during the scan
 * rather than on every list bind. A few hundred icons is a few megabytes,
 * which is an acceptable trade for a list that scrolls smoothly.
 *
 * <h2>Immutability</h2>
 * Instances are produced on a background thread and read on the main thread.
 * Making every field {@code final} means they cross that boundary safely with
 * no synchronisation.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class InstalledApp {

    private final String name;
    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final int targetSdk;
    private final int minSdk;
    private final long apkSizeBytes;
    private final long firstInstalledAt;
    private final long lastUpdatedAt;
    private final boolean systemApp;
    private final int permissionCount;
    private final boolean enabled;
    private final Drawable icon;

    /**
     * Creates a record of one installed application.
     *
     * @param name             user-visible application label
     * @param packageName      unique package identifier
     * @param versionName      human-readable version, e.g. "1.4.2"
     * @param versionCode      internal version number
     * @param targetSdk        API level the app targets
     * @param minSdk           lowest API level it supports
     * @param apkSizeBytes     size of the installed APK on disk
     * @param firstInstalledAt epoch millis of first install
     * @param lastUpdatedAt    epoch millis of the most recent update
     * @param systemApp        whether it ships with the device
     * @param permissionCount  number of permissions it declares
     * @param enabled          whether it is currently enabled
     * @param icon             its launcher icon
     */
    public InstalledApp(@NonNull String name,
                        @NonNull String packageName,
                        @NonNull String versionName,
                        long versionCode,
                        int targetSdk,
                        int minSdk,
                        long apkSizeBytes,
                        long firstInstalledAt,
                        long lastUpdatedAt,
                        boolean systemApp,
                        int permissionCount,
                        boolean enabled,
                        @Nullable Drawable icon) {
        this.name = name;
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.targetSdk = targetSdk;
        this.minSdk = minSdk;
        this.apkSizeBytes = apkSizeBytes;
        this.firstInstalledAt = firstInstalledAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.systemApp = systemApp;
        this.permissionCount = permissionCount;
        this.enabled = enabled;
        this.icon = icon;
    }

    /** @return user-visible application label */
    @NonNull public String getName() { return name; }

    /** @return unique package identifier */
    @NonNull public String getPackageName() { return packageName; }

    /** @return human-readable version */
    @NonNull public String getVersionName() { return versionName; }

    /** @return internal version number, the one that governs updates */
    public long getVersionCode() { return versionCode; }

    /** @return API level the app targets */
    public int getTargetSdk() { return targetSdk; }

    /** @return lowest API level the app supports */
    public int getMinSdk() { return minSdk; }

    /** @return size of the installed APK on disk, in bytes */
    public long getApkSizeBytes() { return apkSizeBytes; }

    /** @return epoch millis when the app was first installed */
    public long getFirstInstalledAt() { return firstInstalledAt; }

    /** @return epoch millis of the most recent update */
    public long getLastUpdatedAt() { return lastUpdatedAt; }

    /**
     * @return {@code true} if the app shipped with the device or lives on the
     *         system partition
     */
    public boolean isSystemApp() { return systemApp; }

    /** @return how many permissions the app declares in its manifest */
    public int getPermissionCount() { return permissionCount; }

    /** @return {@code false} if the app has been disabled */
    public boolean isEnabled() { return enabled; }

    /** @return the app's launcher icon, or {@code null} if it has none */
    @Nullable public Drawable getIcon() { return icon; }

    /**
     * @return {@code true} if the app has been updated since it was installed
     */
    public boolean hasBeenUpdated() {
        // A one-second tolerance: the two timestamps are written separately and
        // can differ by a few milliseconds on a fresh install without the app
        // ever having been updated.
        return lastUpdatedAt - firstInstalledAt > 1000L;
    }
}
