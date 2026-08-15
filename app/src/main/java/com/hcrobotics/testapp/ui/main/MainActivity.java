package com.hcrobotics.testapp.ui.main;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.updater.OtaUpdater;

import com.hcrobotics.testapp.BuildConfig;
import com.hcrobotics.testapp.R;
import com.hcrobotics.testapp.core.util.AppLogger;
import com.hcrobotics.testapp.databinding.ActivityMainBinding;
import com.hcrobotics.testapp.ui.base.BaseActivity;

/**
 * The application's landing screen, shown once the splash screen hands off.
 *
 * <h2>Current behaviour</h2>
 * Displays a single centred welcome message. This is deliberately minimal: the
 * screen exists as a clean, well-structured starting point for experimenting
 * with Android features, not as a finished piece of product.
 *
 * <h2>Where to add things next</h2>
 * <ul>
 *   <li><b>New views</b> — declare them in {@code res/layout/activity_main.xml}
 *       with an {@code android:id}; View Binding exposes them on
 *       {@link #binding} automatically after the next build. No
 *       {@code findViewById} required.</li>
 *   <li><b>Screen state and logic</b> — as soon as this screen does more than
 *       display static text, move its state into a {@code ViewModel}. That
 *       survives configuration changes (rotation) for free and keeps the
 *       Activity focused purely on rendering.</li>
 *   <li><b>New screens</b> — create a package under {@code ui/} mirroring the
 *       structure of {@code ui/main}, extend {@link BaseActivity}, and declare
 *       the Activity in {@code AndroidManifest.xml}.</li>
 * </ul>
 *
 * <p>Started only by {@code SplashActivity}, hence {@code exported="false"} in
 * the manifest.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class MainActivity extends BaseActivity {

    /** Log tag for this screen. */
    private static final String TAG = "MainActivity";

    /** Type-safe accessor for the views declared in {@code activity_main.xml}. */
    private ActivityMainBinding binding;

    /**
     * Launcher for the Android 13+ notification permission request.
     *
     * <p>Must be created during {@code onCreate}, before the Activity is
     * started — registering later throws. That is why it is a field
     * initialised inline rather than created at the point of use.</p>
     */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
                    AppLogger.i(TAG, "Notification permission " + (granted ? "granted" : "denied")
                            + (granted ? "" : " — update notifications will not be shown")));

    /**
     * Inflates the main layout and attaches it to the window.
     *
     * <p>The welcome text itself lives in {@code res/values/strings.xml} and is
     * applied by the layout, so no text is assigned here. Keeping user-visible
     * copy out of Java is what makes the app translatable without touching a
     * line of code.</p>
     *
     * @param savedInstanceState previously saved UI state, or {@code null}
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Surface the running build. On a fleet that updates over the air this
        // is the fastest way to answer "which version is that device on?"
        // without needing physical access to it.
        binding.textAppVersion.setText(
                getString(R.string.main_version_format, BuildConfig.VERSION_NAME));

        binding.buttonCheckUpdates.setOnClickListener(v -> checkForUpdatesNow());

        requestNotificationPermissionIfNeeded();

        AppLogger.i(TAG, "Main screen ready");
    }

    /**
     * Asks for notification permission on Android 13+, if it is still missing.
     *
     * <p><b>Why this is not optional on a remote fleet.</b> From Android 13
     * (API 33) posting a notification requires a runtime permission the user
     * can decline. Declined — or simply never requested — every update
     * notification is dropped by the system with <em>no error and no log</em>.
     * The update check runs, finds a new version, posts a notification, and
     * nothing whatsoever appears.</p>
     *
     * <p>That failure is completely invisible from the server side, which makes
     * it one of the hardest things to diagnose on a device you cannot pick up.
     * The library cannot request the permission itself — a runtime permission
     * dialog must be launched from an Activity — so the host app must, and this
     * is where.</p>
     *
     * <p>{@code OtaUpdater.canPostNotifications()} covers both the permission
     * and the user having switched notifications off in Settings, so it is a
     * truer test than a bare permission check.</p>
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 notifications need no runtime permission.
            return;
        }
        if (OtaUpdater.canPostNotifications(this)) {
            return;
        }
        AppLogger.i(TAG, "Requesting notification permission so update alerts can be shown");
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * Runs an over-the-air update check immediately.
     *
     * <p>The background check runs every few hours, and Android batches
     * background work aggressively, so its real interval is "at least six
     * hours". That is correct for battery life and useless when you are
     * standing in front of a device wanting to confirm a rollout landed.</p>
     *
     * <p>This is the manual override, and on a remotely deployed fleet it is
     * the difference between verifying a deployment in ten seconds and waiting
     * half a day.</p>
     *
     * <p>{@link OtaUpdater#checkNow} returns immediately; the check itself runs
     * on a background thread and posts a notification if it finds something. If
     * an update is already known about, the update screen is opened directly
     * rather than making the user wait for a second check to rediscover it.</p>
     */
    private void checkForUpdatesNow() {
        if (OtaUpdater.getPendingUpdate(this) != null) {
            AppLogger.i(TAG, "An update is already pending; opening the update screen");
            OtaUpdater.openUpdateScreen(this);
            return;
        }

        AppLogger.i(TAG, "Manual update check requested");
        Toast.makeText(this, R.string.main_update_check_started, Toast.LENGTH_SHORT).show();
        OtaUpdater.checkNow(this);
    }

    /**
     * Re-checks for a discovered update whenever the screen comes forward.
     *
     * <p>A manual check is asynchronous: it is handed to WorkManager and
     * completes a moment later on a background thread, so there is nothing to
     * report at the instant the button is tapped.</p>
     *
     * <p>By the time the user next looks at this screen, the result exists.
     * Opening the update screen here means they are not left wondering whether
     * the button did anything, which is the usual complaint about
     * "check for updates" buttons.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (OtaUpdater.getPendingUpdate(this) != null) {
            AppLogger.i(TAG, "A pending update was found; offering it");
            OtaUpdater.openUpdateScreen(this);
        }
    }

    /**
     * Releases the view binding so the inflated view hierarchy can be garbage
     * collected as soon as the Activity is torn down.
     */
    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected String getLogTag() {
        return TAG;
    }
}
