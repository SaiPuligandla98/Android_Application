package com.hcrobotics.testapp.ui.main;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.updater.OtaUpdater;

import com.hcrobotics.testapp.BuildConfig;
import com.hcrobotics.testapp.R;
import com.hcrobotics.testapp.core.util.AppLogger;
import com.hcrobotics.testapp.ui.settings.SettingsActivity;
import com.hcrobotics.appinsights.AppInsightsActivity;
import com.hcrobotics.performance.PerformanceActivity;
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
     * Whether the update dialog has already been offered during this visit.
     *
     * <p>Prevents the dialog reappearing every time the user returns from
     * Settings or the update screen. Not persisted deliberately: a fresh app
     * launch should ask again, because an update that is still outstanding
     * tomorrow is worth mentioning again tomorrow.</p>
     */
    private boolean updateDialogShownThisSession = false;

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
        // Name AND code. The name is what a user reports; the code is what
        // actually decides whether an update applies, so on a remote fleet it
        // is the number you need when an update does not arrive.
        binding.textAppVersion.setText(getString(
                R.string.main_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE));

        /*
         * The top-bar title is deliberately left EMPTY on the dashboard.
         *
         * The app name appears on the splash screen and under the launcher
         * icon; repeating it here tells the user nothing they do not already
         * know, and the space is better spent on the cards below. The TextView
         * itself stays, because it is what pushes Home and Settings to opposite
         * edges of the bar.
         */
        binding.topBar.textTopBarTitle.setText("");

        binding.cardPerformance.setOnClickListener(v ->
                startActivity(new Intent(this, PerformanceActivity.class)));
        binding.cardApps.setOnClickListener(v ->
                startActivity(new Intent(this, AppInsightsActivity.class)));

        binding.topBar.buttonSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        /*
         * Home is already where we are, so tapping it should do nothing visible.
         *
         * The button stays PRESENT and enabled rather than being hidden on this
         * one screen: a control that appears and disappears is harder to rely on
         * than one that is simply always in the same place. Consistency is worth
         * more here than removing a tap that happens to be a no-op.
         */
        binding.topBar.buttonHome.setOnClickListener(v ->
                AppLogger.d(TAG, "Home tapped while already on the home screen"));

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
     * Offers any waiting update, once per visit to this screen.
     *
     * <p>A manual check is asynchronous — handed to WorkManager and completed a
     * moment later on a background thread — so there is nothing to report at the
     * instant the button is tapped. By the time the user next looks at this
     * screen the result exists, which makes {@code onResume} the natural place
     * to surface it.</p>
     *
     * <p>{@link #updateDialogShownThisSession} stops the dialog reappearing
     * every time the user returns from Settings or the update screen. Being
     * asked once is a prompt; being asked repeatedly is nagging, and nagging
     * teaches people to dismiss dialogs without reading them — exactly the
     * habit you do not want when a genuinely important update arrives.</p>
     *
     * <p>Declining costs the user nothing permanent: the update stays listed in
     * Settings until it is installed.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();

        // The badge reflects current state on every return to this screen: a
        // background check may have found something while the user was
        // elsewhere, or an install may have just cleared it.
        final boolean updateWaiting = OtaUpdater.getPendingUpdate(this) != null;
        binding.topBar.viewUpdateBadge.setVisibility(updateWaiting ? View.VISIBLE : View.GONE);

        if (updateDialogShownThisSession || !updateWaiting) {
            return;
        }
        if (OtaUpdater.showUpdateDialogIfAvailable(this)) {
            updateDialogShownThisSession = true;
            AppLogger.i(TAG, "Offered a pending update to the user");
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
