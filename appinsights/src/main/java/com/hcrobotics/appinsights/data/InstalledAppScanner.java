package com.hcrobotics.appinsights.data;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.hcrobotics.appinsights.model.InstalledApp;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the inventory of installed applications.
 *
 * <h2>Read-only, by design</h2>
 * This class asks {@code PackageManager} questions and reads file sizes. It
 * never starts, stops, disables or modifies another application, and it holds
 * no reference to one. Inspecting an app has no effect on it whatsoever.
 *
 * <h2>Package visibility on Android 11+</h2>
 * From API 30 an app sees only its own package and a few it explicitly
 * declares, unless it holds {@code QUERY_ALL_PACKAGES}. Without that
 * permission this scan would return one result — itself — which looks like a
 * bug rather than a restriction.
 *
 * <p>The permission is declared in this module's manifest. Google Play treats
 * it as restricted and requires justification, which is acceptable here because
 * these builds are distributed outside Play; a device-inventory feature is
 * exactly the use it exists for.</p>
 *
 * <h2>Why APK size and not the full storage breakdown</h2>
 * {@code new File(sourceDir).length()} gives the installed APK size and needs
 * no permission at all. The richer figures — user data, cache — come from
 * {@code StorageStatsManager}, which requires the special "usage access" grant
 * the user must enable in system settings.
 *
 * <p>Demanding a special access grant to show a diagnostics list is a poor
 * trade, so this module reports the number it can read honestly and the UI says
 * what it is measuring.</p>
 *
 * <h2>Threading</h2>
 * A full scan resolves labels and icons for every installed package and takes
 * hundreds of milliseconds on a device with a few hundred apps. It is annotated
 * {@link WorkerThread} and must never be called on the main thread.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class InstalledAppScanner {

    /** How the inventory can be ordered. */
    public enum SortOrder {
        /** Alphabetical by display name. */
        NAME,
        /** Largest APK first — the usual reason for looking at this list. */
        SIZE,
        /** Most recently updated first. */
        RECENTLY_UPDATED
    }

    /** Utility class; never instantiated. */
    private InstalledAppScanner() {
        throw new AssertionError("InstalledAppScanner is a utility class.");
    }

    /**
     * Scans every visible installed application.
     *
     * <p>Blocking; call from a background thread.</p>
     *
     * @param context           any context
     * @param includeSystemApps whether to include apps that shipped with the device
     * @param sortOrder         how to order the result
     * @return the inventory, ordered as requested
     */
    @WorkerThread
    @NonNull
    public static List<InstalledApp> scan(@NonNull Context context,
                                          boolean includeSystemApps,
                                          @NonNull SortOrder sortOrder) {
        final PackageManager packageManager = context.getPackageManager();
        final List<InstalledApp> results = new ArrayList<>();

        // GET_PERMISSIONS so the declared-permission count is populated. Without
        // the flag, requestedPermissions is null rather than empty, which is a
        // silent and easily-missed difference.
        final List<PackageInfo> packages =
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS);

        for (PackageInfo info : packages) {
            final ApplicationInfo appInfo = info.applicationInfo;
            if (appInfo == null) {
                continue;
            }

            final boolean isSystem = isSystemApp(appInfo);
            if (isSystem && !includeSystemApps) {
                continue;
            }

            try {
                results.add(toModel(packageManager, info, appInfo, isSystem));
            } catch (Exception e) {
                /*
                 * A package can be uninstalled between listing and inspecting
                 * it, and a badly-formed one can throw while its resources are
                 * loaded. Skipping the entry is right: one unreadable app must
                 * not empty the whole inventory.
                 */
            }
        }

        sort(results, sortOrder);
        return results;
    }

    /**
     * Converts platform records into the module's own model.
     *
     * @param packageManager the package manager
     * @param info           package metadata
     * @param appInfo        application metadata
     * @param isSystem       whether this is a system application
     * @return the populated model
     */
    @NonNull
    private static InstalledApp toModel(@NonNull PackageManager packageManager,
                                        @NonNull PackageInfo info,
                                        @NonNull ApplicationInfo appInfo,
                                        boolean isSystem) {
        // Reads the APK on disk. No permission needed, and it is the number a
        // user recognises as "how big is this app".
        long apkSize = 0L;
        if (appInfo.sourceDir != null) {
            apkSize = new File(appInfo.sourceDir).length();
        }

        // getLongVersionCode() from API 28; the int field before that.
        final long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;

        // minSdkVersion only became public API in API 24, which is this
        // module's minimum, so it is always readable here.
        final int minSdk = appInfo.minSdkVersion;

        final String versionName = info.versionName != null ? info.versionName : "-";
        final int permissionCount =
                info.requestedPermissions == null ? 0 : info.requestedPermissions.length;

        return new InstalledApp(
                packageManager.getApplicationLabel(appInfo).toString(),
                info.packageName,
                versionName,
                versionCode,
                appInfo.targetSdkVersion,
                minSdk,
                apkSize,
                info.firstInstallTime,
                info.lastUpdateTime,
                isSystem,
                permissionCount,
                appInfo.enabled,
                packageManager.getApplicationIcon(appInfo));
    }

    /**
     * Decides whether an application is part of the system image.
     *
     * <p>{@code FLAG_SYSTEM} alone is not sufficient: a pre-installed app that
     * has since been updated from the Play Store carries
     * {@code FLAG_UPDATED_SYSTEM_APP} instead. Checking only the first flag
     * misclassifies exactly the apps users are most likely to ask about.</p>
     *
     * @param appInfo application metadata
     * @return {@code true} if it shipped with the device
     */
    private static boolean isSystemApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /**
     * Orders the inventory in place.
     *
     * @param apps      the list to sort
     * @param sortOrder the requested order
     */
    private static void sort(@NonNull List<InstalledApp> apps, @NonNull SortOrder sortOrder) {
        switch (sortOrder) {
            case SIZE:
                // Descending: the reason to sort by size is to find the big ones.
                apps.sort(Comparator.comparingLong(InstalledApp::getApkSizeBytes).reversed());
                break;
            case RECENTLY_UPDATED:
                apps.sort(Comparator.comparingLong(InstalledApp::getLastUpdatedAt).reversed());
                break;
            case NAME:
            default:
                // Case-insensitive, or "Zoom" would sort before "adb".
                apps.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                break;
        }
    }
}
