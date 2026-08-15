package com.hcrobotics.testapp.core.theme;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.hcrobotics.testapp.core.util.AppLogger;

/**
 * Stores and applies the user's light/dark theme choice.
 *
 * <h2>How theme switching actually works on Android</h2>
 * There is no need to reload colours by hand, re-create views, or restart the
 * app. {@link AppCompatDelegate#setDefaultNightMode(int)} tells AppCompat which
 * resource configuration to use, and the framework then resolves every
 * {@code values/} versus {@code values-night/} resource accordingly.
 *
 * <p>Every colour in this app is already defined in both, so a theme change is
 * a single call — the entire UI re-colours itself, including screens that are
 * not currently on screen.</p>
 *
 * <h2>Why three options and not a switch</h2>
 * A two-state toggle forces a choice the user may not want to make. "System"
 * is the option most people actually want: the app follows the phone, so it
 * darkens in the evening along with everything else, with no further thought.
 * It is therefore the default.
 *
 * <table border="1">
 *   <caption>Theme modes</caption>
 *   <tr><th>Mode</th><th>Behaviour</th></tr>
 *   <tr><td>SYSTEM</td><td>Follows the device setting. Default.</td></tr>
 *   <tr><td>LIGHT</td><td>Always light, whatever the device says.</td></tr>
 *   <tr><td>DARK</td><td>Always dark, whatever the device says.</td></tr>
 * </table>
 *
 * <h2>Why the choice is applied in Application.onCreate</h2>
 * The mode must be set BEFORE the first Activity inflates its views, or the app
 * paints in the wrong theme for a frame and then visibly flips. Applying it in
 * {@code Application.onCreate()} guarantees it is in place before any UI exists.
 *
 * @author HC Robotics
 * @since 1.6.0
 */
public final class ThemeManager {

    /** Preferences file holding the theme choice. */
    private static final String PREFS_NAME = "hc_appearance";

    /** Key for the stored mode. */
    private static final String KEY_THEME_MODE = "theme_mode";

    /** Log tag. */
    private static final String TAG = "ThemeManager";

    /**
     * The available theme choices.
     *
     * <p>Each carries the {@link AppCompatDelegate} constant it maps to, so the
     * translation lives with the enum rather than in a switch that has to be
     * kept in step with it.</p>
     */
    public enum Mode {

        /** Follow the device's own light/dark setting. The default. */
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),

        /** Always light. */
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),

        /** Always dark. */
        DARK(AppCompatDelegate.MODE_NIGHT_YES);

        @AppCompatDelegate.NightMode
        private final int delegateMode;

        Mode(@AppCompatDelegate.NightMode int delegateMode) {
            this.delegateMode = delegateMode;
        }

        /**
         * Returns the {@link AppCompatDelegate} night-mode constant.
         *
         * <p>The {@code @NightMode} annotation matters: {@code
         * setDefaultNightMode} accepts a restricted set of values, and without
         * it lint cannot prove the value is one of them. Annotating carries the
         * guarantee through the enum, so a wrong constant is caught at build
         * time rather than producing a silently ignored theme change.</p>
         *
         * @return one of the {@code AppCompatDelegate.MODE_NIGHT_*} constants
         */
        @AppCompatDelegate.NightMode
        public int delegateMode() {
            return delegateMode;
        }
    }

    /** Utility class; never instantiated. */
    private ThemeManager() {
        throw new AssertionError("ThemeManager is a utility class.");
    }

    /**
     * Applies the stored theme choice.
     *
     * <p>Call from {@code Application.onCreate()}, before any Activity exists.
     * Applying it later means the first screen paints in the wrong theme and
     * then visibly flips, which looks like a bug even though the end state is
     * correct.</p>
     *
     * @param context any context
     */
    public static void applyStoredMode(@NonNull Context context) {
        final Mode mode = getMode(context);
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode());
        AppLogger.i(TAG, "Applied theme mode: " + mode);
    }

    /**
     * Returns the stored theme choice.
     *
     * @param context any context
     * @return the stored mode, or {@link Mode#SYSTEM} if none has been chosen
     */
    @NonNull
    public static Mode getMode(@NonNull Context context) {
        final String stored = prefs(context).getString(KEY_THEME_MODE, Mode.SYSTEM.name());
        try {
            return Mode.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A value written by an older build that no longer exists. Falling
            // back is better than crashing over a cosmetic preference.
            AppLogger.w(TAG, "Unrecognised stored theme '" + stored + "'; using SYSTEM");
            return Mode.SYSTEM;
        }
    }

    /**
     * Stores and immediately applies a theme choice.
     *
     * <p>The change takes effect at once. AppCompat recreates the Activities it
     * needs to, so the caller does not have to restart anything — and because
     * every colour is defined in both {@code values/} and {@code values-night/},
     * screens that are not currently visible come back in the new theme too.</p>
     *
     * @param context any context
     * @param mode    the choice to apply
     */
    public static void setMode(@NonNull Context context, @NonNull Mode mode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name()).apply();
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode());
        AppLogger.i(TAG, "Theme mode changed to: " + mode);
    }

    /**
     * Returns this module's private preferences.
     *
     * <p>The application context is used deliberately: holding an Activity
     * context in anything long-lived leaks the whole view hierarchy.</p>
     *
     * @param context any context
     * @return the appearance preferences
     */
    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
