package com.hcrobotics.testapp.core.config;

/**
 * Single source of truth for application-wide tuning constants.
 *
 * <h2>Why a dedicated configuration class?</h2>
 * Values such as "how long the splash screen stays up" are the sort of thing
 * that gets hard-coded inline, duplicated, and then quietly diverges. Keeping
 * them here means:
 *
 * <ul>
 *   <li>A reviewer can see every behavioural knob in one file.</li>
 *   <li>Tests can assert against the same constant the production code uses,
 *       instead of repeating a magic number.</li>
 *   <li>Changing a value is a one-line, self-documenting diff.</li>
 * </ul>
 *
 * <h2>What belongs here, and what does not</h2>
 * <p><b>Belongs here:</b> compile-time behavioural constants that are identical
 * on every device and in every environment.</p>
 *
 * <p><b>Does not belong here:</b></p>
 * <ul>
 *   <li>Anything user-visible — that goes in {@code res/values/strings.xml} so
 *       it can be translated.</li>
 *   <li>Anything device-dependent (sizes, spacings) — that goes in
 *       {@code res/values/dimens.xml} so it can vary by screen configuration.</li>
 *   <li>Secrets, API keys or endpoints — those belong in build configuration or
 *       a secure store, never in source control.</li>
 * </ul>
 *
 * <p>This class is stateless and cannot be instantiated.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class AppConfig {

    /**
     * How long, in milliseconds, the splash screen remains visible before the
     * app navigates to {@code MainActivity}.
     *
     * <p>1500&nbsp;ms is a deliberate compromise: long enough for the brand mark
     * to register, short enough that it never feels like the app is stalling.
     * Google's own guidance is to keep branded launch screens under two
     * seconds.</p>
     *
     * <p><b>Important:</b> this is a <em>presentation</em> delay, not a loading
     * mechanism. Once real startup work exists (session restore, config fetch),
     * navigate as soon as that work completes rather than waiting out a fixed
     * timer — an artificial delay on top of real work is time stolen from the
     * user.</p>
     */
    public static final long SPLASH_DISPLAY_DURATION_MS = 1_500L;

    /**
     * HTTPS location of the over-the-air update manifest.
     *
     * <p>The manifest is a small JSON file committed to the project repository
     * and served through GitHub's raw endpoint. Publishing an update means
     * updating this file and attaching the APK to a GitHub Release — both free,
     * and neither requires running a server.</p>
     *
     * <p><b>The branch in this URL matters.</b> It points at {@code master},
     * the production branch, so devices in the field only ever see releases
     * that have been merged all the way through. Pointing it at {@code dev}
     * would ship every work-in-progress build to the whole fleet.</p>
     *
     * <p>Generated and updated by {@code tools/publish-update.ps1}.</p>
     */
    public static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/SaiPuligandla98/Android_Application/master/ota/update-manifest.json";

    /**
     * Hours between automatic background update checks.
     *
     * <p>Six hours means a released fix reaches the fleet within a quarter of a
     * day without waking devices needlessly. Android batches background work,
     * so treat this as a floor rather than a precise schedule.</p>
     *
     * <p>For an update that cannot wait, trigger
     * {@code OtaUpdater.checkNow(context)} from a push message — see the
     * updater module's README.</p>
     */
    public static final long UPDATE_CHECK_INTERVAL_HOURS = 6L;

    /** Utility class — never instantiated. */
    private AppConfig() {
        throw new AssertionError("AppConfig is a constants holder and must not be instantiated.");
    }
}
