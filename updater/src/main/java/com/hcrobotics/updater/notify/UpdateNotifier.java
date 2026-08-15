package com.hcrobotics.updater.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.hcrobotics.updater.OtaConfig;
import com.hcrobotics.updater.R;
import com.hcrobotics.updater.UpdateInfo;
import com.hcrobotics.updater.internal.UpdaterLog;
import com.hcrobotics.updater.ui.UpdateActivity;

/**
 * Builds and posts every notification this module shows.
 *
 * <h2>Why notifications are the right surface here</h2>
 * A background check can find an update at any moment, including while the app
 * is closed. There is no UI to show a dialog in. A notification is the only
 * mechanism Android provides for saying "something happened" to a user who is
 * not currently looking at your app.
 *
 * <h2>The two platform rules that silently break notifications</h2>
 * <ol>
 *   <li><b>Android 8.0 (API 26): channels.</b> A notification posted without a
 *       registered channel is dropped by the system with no error. The channel
 *       is created in {@link #ensureChannel(Context)}, which is called before
 *       every post - creating an existing channel is a cheap no-op, so this is
 *       safer than relying on a one-time init having happened.</li>
 *   <li><b>Android 13 (API 33): POST_NOTIFICATIONS.</b> A runtime permission
 *       the user can decline. Declined, every notification is silently dropped.
 *       {@link #canPostNotifications(Context)} makes that state detectable
 *       instead of mysterious.</li>
 * </ol>
 *
 * <h2>Importance level</h2>
 * The channel uses {@code IMPORTANCE_DEFAULT}: it appears in the shade and
 * makes a sound, but does not take over the screen. An available update is
 * worth noticing and is never worth interrupting someone mid-task for.
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class UpdateNotifier {

    /** Channel id for update notifications. Stable; never change it. */
    private static final String CHANNEL_ID = "hc_ota_updates";

    /** Notification id for the "update available" notification. */
    private static final int NOTIFICATION_ID_AVAILABLE = 6101;

    /** Notification id for the "install failed" notification. */
    private static final int NOTIFICATION_ID_FAILED = 6102;

    /** Request code for the content PendingIntent. */
    private static final int REQUEST_CODE_OPEN_UPDATE = 6100;

    /** Utility class; never instantiated. */
    private UpdateNotifier() {
        throw new AssertionError("UpdateNotifier is a utility class.");
    }

    /**
     * Reports whether this app is currently allowed to post notifications.
     *
     * <p>Checks both the API 33 runtime permission and whether the user has
     * turned the app's notifications off in Settings, since either makes a post
     * a no-op.</p>
     *
     * @param context any context
     * @return {@code true} if a posted notification would actually be shown
     */
    public static boolean canPostNotifications(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            final boolean granted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                return false;
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * Posts the "an update is available" notification.
     *
     * <p>Tapping it opens {@link UpdateActivity} with the update details
     * attached, so the screen does not have to re-fetch the manifest.</p>
     *
     * <p>Safe to call when notifications are blocked: it logs the reason and
     * returns rather than throwing, because a background worker must not crash
     * over a notification the user chose not to receive.</p>
     *
     * @param context any context
     * @param update  the available update
     */
    public static void showUpdateAvailable(@NonNull Context context, @NonNull UpdateInfo update) {
        if (!canPostNotifications(context)) {
            UpdaterLog.w("An update is available but notifications are blocked for this app, so "
                    + "the user will not be told. Request POST_NOTIFICATIONS (Android 13+) or "
                    + "check that notifications are enabled in system Settings.");
            return;
        }

        ensureChannel(context);

        final PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN_UPDATE,
                UpdateActivity.createIntent(context, update),
                pendingIntentFlags());

        final String title = context.getString(R.string.updater_notification_title);
        final String text = context.getString(
                R.string.updater_notification_text, update.getVersionName());

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(smallIcon(context))
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(contentIntent)
                // Dismiss the notification once it has been acted on.
                .setAutoCancel(true);

        // Release notes are usually longer than one line, so expand into
        // BigTextStyle rather than letting the system truncate them.
        if (!update.getReleaseNotes().isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(text + "\n\n" + update.getReleaseNotes()));
        }

        // A mandatory update should not be swipeable away.
        if (update.isMandatory()) {
            builder.setOngoing(true);
            builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        }

        post(context, NOTIFICATION_ID_AVAILABLE, builder.build());
        UpdaterLog.i("Posted the update-available notification for " + update.getVersionName());
    }

    /**
     * Posts a notification explaining why an install failed.
     *
     * @param context any context
     * @param reason  a human-readable explanation
     */
    public static void showInstallFailed(@NonNull Context context, @NonNull String reason) {
        if (!canPostNotifications(context)) {
            return;
        }
        ensureChannel(context);

        final String title = context.getString(R.string.updater_notification_failed_title);

        final Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(smallIcon(context))
                .setContentTitle(title)
                .setContentText(reason)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build();

        post(context, NOTIFICATION_ID_FAILED, notification);
    }

    /**
     * Removes every notification this module has posted.
     *
     * @param context any context
     */
    public static void cancelAll(@NonNull Context context) {
        final NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.cancel(NOTIFICATION_ID_AVAILABLE);
        manager.cancel(NOTIFICATION_ID_FAILED);
    }

    /**
     * Creates the notification channel if it does not already exist.
     *
     * <p>No-op below Android 8.0, where channels do not exist. Above it,
     * re-creating an existing channel is harmless and does not overwrite any
     * preference the user has since changed - which is why this is called
     * before every post rather than once at startup.</p>
     *
     * @param context any context
     */
    private static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.updater_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.updater_channel_description));
        channel.setShowBadge(true);

        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * Chooses the notification's small icon.
     *
     * <p>Uses the host app's icon when {@code OtaConfig.notificationIcon()} was
     * set, otherwise the one bundled with this module. A small icon must be a
     * white-on-transparent silhouette; anything else renders as a solid white
     * blob.</p>
     *
     * @param context any context
     * @return a drawable resource id
     */
    private static int smallIcon(@NonNull Context context) {
        final OtaConfig config = OtaConfig.load(context);
        if (config != null && config.getNotificationIconRes() != 0) {
            return config.getNotificationIconRes();
        }
        return R.drawable.updater_ic_system_update;
    }

    /**
     * Posts a notification, tolerating a revoked permission.
     *
     * <p>{@code NotificationManagerCompat.notify} is annotated as requiring
     * POST_NOTIFICATIONS, and can throw if the permission is revoked between
     * the check above and this call. Catching keeps a background worker alive
     * through a race it cannot prevent.</p>
     *
     * @param context      any context
     * @param id           notification id
     * @param notification the notification to show
     */
    private static void post(@NonNull Context context, int id, @NonNull Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException e) {
            UpdaterLog.e("Notification permission was revoked before the post completed", e);
        }
    }

    /**
     * @return PendingIntent flags appropriate for this API level
     */
    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 12 requires an explicit mutability flag. This PendingIntent
            // carries everything it needs, so it can safely be immutable.
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }
}
