package com.hcrobotics.testapp.ui.base;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.hcrobotics.testapp.core.util.AppLogger;

/**
 * Common base class for every {@code Activity} in the application.
 *
 * <h2>What it provides today</h2>
 * <ul>
 *   <li>Consistent lifecycle tracing through {@link AppLogger}, so the exact
 *       order of screen transitions is visible in logcat without adding a
 *       single log statement to any subclass.</li>
 *   <li>A single, enforced log tag per screen via {@link #getLogTag()}.</li>
 * </ul>
 *
 * <h2>What it is designed to grow into</h2>
 * A shared base is the natural home for behaviour that must be identical on
 * every screen — for example:
 * <ul>
 *   <li>Applying edge-to-edge window insets.</li>
 *   <li>A common error/offline banner.</li>
 *   <li>Session-expiry checks that bounce the user to a login screen.</li>
 *   <li>Analytics screen-view tracking.</li>
 * </ul>
 *
 * <h2>A word of caution</h2>
 * Base classes are convenient but they are also inheritance, and inheritance is
 * hard to opt out of. Add something here only when it genuinely applies to
 * <em>every</em> screen. Anything narrower is better expressed as a helper
 * class or a delegate that individual screens choose to use.
 *
 * <p>Extends {@link AppCompatActivity} rather than {@code Activity} so that
 * modern theming (Material 3, DayNight) and API back-ports work on every
 * supported Android version.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public abstract class BaseActivity extends AppCompatActivity {

    /**
     * Returns the tag used for all log output produced by this screen.
     *
     * <p>Implementations should return a short, stable, human-readable name —
     * conventionally the class's simple name, e.g. {@code "SplashActivity"}.
     * {@link AppLogger} prefixes it automatically, so do not include a prefix
     * here.</p>
     *
     * @return a non-null, non-empty log tag for this screen
     */
    @NonNull
    protected abstract String getLogTag();

    /**
     * {@inheritDoc}
     *
     * <p>Subclasses must call {@code super.onCreate(savedInstanceState)} first;
     * doing so both restores framework state and emits the lifecycle trace.</p>
     *
     * @param savedInstanceState previously saved UI state, or {@code null} on a
     *                           fresh creation
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableFullScreenMode();
        AppLogger.d(getLogTag(), "onCreate | restored="
                + (savedInstanceState != null));
    }

    /**
     * Puts the screen into full-screen (immersive) mode.
     *
     * <h3>What the user sees</h3>
     * The status bar and navigation bar are hidden, so the app owns the entire
     * display. Swiping from an edge brings them back temporarily; they hide
     * themselves again after a moment.
     *
     * <h3>Why "transient bars by swipe" rather than simply hiding them</h3>
     * {@code BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} is the only behaviour that
     * is both immersive and escapable. The alternatives are worse:
     *
     * <ul>
     *   <li>Hiding the bars with no way back would strand the user on a device
     *       using gesture navigation — there would be no way to leave the app.</li>
     *   <li>Letting a swipe restore the bars <em>permanently</em> means the
     *       first accidental edge swipe ends full-screen mode for good.</li>
     * </ul>
     *
     * <p>Transient bars overlay the content briefly and then withdraw, so the
     * layout never reflows and nothing jumps under the user's finger.</p>
     *
     * <h3>Why this lives in BaseActivity</h3>
     * Full-screen has to be re-applied on every Activity, because each one gets
     * its own window. Putting it here means a new screen is immersive simply by
     * extending this class — it cannot be forgotten.
     *
     * @see #onWindowFocusChanged(boolean) for why it is also re-applied later
     */
    private void enableFullScreenMode() {
        // Draw behind the system bars rather than being laid out around them.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        final WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * Re-applies full-screen mode whenever the window regains focus.
     *
     * <p>The system restores the bars whenever anything takes focus away — a
     * permission dialog, the notification shade, the install confirmation
     * prompt, a phone call. Without re-hiding them on the way back, the app
     * quietly stops being full-screen after the first interruption and never
     * recovers.</p>
     *
     * <p>Only re-applied when focus is GAINED. Doing it on focus loss would
     * fight whatever is currently on top for control of the bars.</p>
     *
     * @param hasFocus whether this window now has focus
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableFullScreenMode();
        }
    }

    /** {@inheritDoc} Logs that the screen is about to become visible. */
    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.d(getLogTag(), "onStart | screen becoming visible");
    }

    /** {@inheritDoc} Logs that the screen is now in the foreground and interactive. */
    @Override
    protected void onResume() {
        super.onResume();
        AppLogger.d(getLogTag(), "onResume | screen is interactive");
    }

    /** {@inheritDoc} Logs that the screen is losing focus. */
    @Override
    protected void onPause() {
        AppLogger.d(getLogTag(), "onPause | screen losing focus");
        super.onPause();
    }

    /** {@inheritDoc} Logs that the screen is no longer visible. */
    @Override
    protected void onStop() {
        AppLogger.d(getLogTag(), "onStop | screen no longer visible");
        super.onStop();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs whether the destruction is a configuration change (rotation,
     * theme switch, etc.) or a genuine teardown. This distinction is the single
     * most useful piece of information when debugging state loss.</p>
     */
    @Override
    protected void onDestroy() {
        AppLogger.d(getLogTag(), "onDestroy | configurationChange="
                + isChangingConfigurations());
        super.onDestroy();
    }
}
