package com.hcrobotics.testapp.ui.main;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

        AppLogger.i(TAG, "Main screen ready");
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
