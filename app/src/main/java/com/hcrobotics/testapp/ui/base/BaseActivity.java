package com.hcrobotics.testapp.ui.base;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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
        AppLogger.d(getLogTag(), "onCreate | restored="
                + (savedInstanceState != null));
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
