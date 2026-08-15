package com.hcrobotics.testapp.ui.splash;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.testapp.BuildConfig;
import com.hcrobotics.testapp.R;
import com.hcrobotics.testapp.core.config.AppConfig;
import com.hcrobotics.testapp.core.util.AppLogger;
import com.hcrobotics.testapp.databinding.ActivitySplashBinding;
import com.hcrobotics.testapp.ui.base.BaseActivity;
import com.hcrobotics.testapp.ui.main.MainActivity;

/**
 * Branded launch screen — the first thing the user sees after tapping the icon.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Present the HC Robotics brand mark for {@link AppConfig#SPLASH_DISPLAY_DURATION_MS}.</li>
 *   <li>Hand off to {@link MainActivity} and remove itself from the back stack.</li>
 * </ol>
 *
 * <h2>How the "no white flash" trick works</h2>
 * When a cold start begins, the window manager draws the activity's
 * {@code android:windowBackground} <em>before</em> the process has even reached
 * {@code onCreate()}. Most apps leave that background a plain colour, which is
 * why you often see a blank frame before branding appears.
 *
 * <p>Here, {@code Theme.HCRobotics.Splash} sets {@code windowBackground} to
 * {@code @drawable/bg_splash} — a layer-list that already contains the logo on
 * the brand surface colour. The branded frame is therefore on screen from the
 * very first rendered pixel, and the layout inflated in {@link #onCreate} is
 * visually identical to it, so the transition is seamless.</p>
 *
 * <h2>Why the navigation is posted rather than slept</h2>
 * The delay uses {@link Handler#postDelayed} on the main {@link Looper}.
 * {@code Thread.sleep()} would block the UI thread, freeze rendering and risk an
 * "Application Not Responding" dialog. Posting keeps the main thread free to
 * draw and to process input.
 *
 * <h2>Lifecycle safety</h2>
 * A posted {@code Runnable} outlives the Activity that scheduled it. If the user
 * leaves during the delay, firing it later would either leak this Activity or
 * crash with {@code IllegalStateException}. Two safeguards prevent that:
 * {@link #onDestroy()} cancels the pending callback, and {@link #hasNavigated}
 * guarantees the navigation runs at most once.
 *
 * <h2>Replacing the timer with real work</h2>
 * When genuine startup work is introduced (restoring a session, fetching remote
 * config), drop the fixed delay and call {@link #navigateToMainScreen()} from
 * that work's completion callback instead. Users should never wait on a timer
 * that has already finished its purpose.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class SplashActivity extends BaseActivity {

    /** Log tag for this screen. */
    private static final String TAG = "SplashActivity";

    /**
     * Handler bound to the main thread's {@link Looper}, used to schedule the
     * hand-off to {@link MainActivity} without blocking the UI.
     */
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    /**
     * The scheduled navigation task. Retained as a field so the exact same
     * instance can be cancelled in {@link #onDestroy()}.
     */
    private final Runnable navigateRunnable = this::navigateToMainScreen;

    /**
     * Guards against navigating twice — for example if a rapid configuration
     * change let the callback fire more than once.
     */
    private boolean hasNavigated = false;

    /** Type-safe accessor for the views declared in {@code activity_splash.xml}. */
    private ActivitySplashBinding binding;

    /**
     * Inflates the splash layout and schedules the hand-off to the main screen.
     *
     * @param savedInstanceState previously saved UI state, or {@code null}
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // View Binding replaces setContentView(R.layout.activity_splash) +
        // findViewById(). The generated `binding` exposes every view that has an
        // android:id, already correctly typed.
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Surface the build identity on the splash. Invaluable when several
        // test builds are installed side by side and you need to know at a
        // glance which one is running.
        binding.textSplashVersion.setText(
                getString(R.string.splash_version_format, BuildConfig.VERSION_NAME));

        AppLogger.i(TAG, "Splash displayed | handing off in "
                + AppConfig.SPLASH_DISPLAY_DURATION_MS + " ms");

        mainThreadHandler.postDelayed(navigateRunnable, AppConfig.SPLASH_DISPLAY_DURATION_MS);
    }

    /**
     * Starts {@link MainActivity} and finishes this screen.
     *
     * <p>Calling {@link #finish()} explicitly (in addition to the manifest's
     * {@code android:noHistory="true"}) makes the intent unambiguous at the call
     * site: once the hand-off happens, the splash is gone for good and Back from
     * the main screen exits the app.</p>
     *
     * <p>Safe to call more than once; subsequent calls are ignored.</p>
     */
    private void navigateToMainScreen() {
        if (hasNavigated) {
            AppLogger.d(TAG, "navigateToMainScreen ignored | navigation already performed");
            return;
        }
        hasNavigated = true;

        AppLogger.i(TAG, "Navigating to MainActivity");
        startActivity(new Intent(this, MainActivity.class));

        // Cross-fade instead of the default slide, so the branded frame melts
        // into the main screen rather than sliding off it.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        finish();
    }

    /**
     * Cancels the pending navigation and releases the view binding.
     *
     * <p>Both steps matter: an un-cancelled {@code Runnable} keeps a strong
     * reference to this Activity (and therefore its whole view tree) alive until
     * it fires, and a retained binding does the same for the inflated views.</p>
     */
    @Override
    protected void onDestroy() {
        mainThreadHandler.removeCallbacks(navigateRunnable);
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
