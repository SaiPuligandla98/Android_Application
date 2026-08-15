package com.hcrobotics.testapp;

import android.app.Application;

import com.hcrobotics.testapp.core.util.AppLogger;

/**
 * Process-wide entry point for the HC Robotics Android application.
 *
 * <h2>What this class is for</h2>
 * Android instantiates exactly one {@code Application} object per app process,
 * and it does so <em>before</em> any {@link android.app.Activity},
 * {@link android.app.Service} or {@link android.content.BroadcastReceiver} is
 * created. That makes it the correct — and only — place to perform
 * initialisation that the whole app depends on.
 *
 * <h2>Typical responsibilities (as the project grows)</h2>
 * <ul>
 *   <li>Constructing the dependency graph (Hilt/Dagger, or a manual service locator).</li>
 *   <li>Initialising crash reporting and analytics SDKs.</li>
 *   <li>Installing a global uncaught-exception handler.</li>
 *   <li>Configuring persistence (Room, DataStore) and networking (Retrofit/OkHttp) singletons.</li>
 * </ul>
 *
 * <h2>Rules to keep in mind</h2>
 * <ul>
 *   <li>{@link #onCreate()} runs on the main thread and blocks app startup —
 *       keep it fast, and push anything slow onto a background thread.</li>
 *   <li>Never hold a reference to an Activity here; the Application outlives
 *       every Activity and doing so leaks the whole view hierarchy.</li>
 * </ul>
 *
 * <p>Registered in {@code AndroidManifest.xml} via
 * {@code android:name=".HcRoboticsApplication"}.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class HcRoboticsApplication extends Application {

    /** Log tag identifying messages emitted by this class. */
    private static final String TAG = "HcRoboticsApplication";

    /**
     * Called by the Android runtime when the application process is created,
     * before any component of this app is instantiated.
     *
     * <p>Right now it only records a startup breadcrumb. As real subsystems are
     * introduced, wire their initialisation in below the {@code super} call —
     * and keep each one behind a small, individually testable method.</p>
     */
    @Override
    public void onCreate() {
        super.onCreate();

        AppLogger.i(TAG, "Application process starting"
                + " | versionName=" + BuildConfig.VERSION_NAME
                + " | buildType=" + BuildConfig.BUILD_TYPE
                + " | applicationId=" + BuildConfig.APPLICATION_ID);

        // ---------------------------------------------------------------------
        // Future initialisation hooks go here, for example:
        //
        //   CrashReporter.install(this);
        //   ServiceLocator.initialise(this);
        //   ThemeManager.applyPersistedNightMode(this);
        // ---------------------------------------------------------------------
    }
}
