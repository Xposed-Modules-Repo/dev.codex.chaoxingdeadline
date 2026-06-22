package dev.chaoxingdeadline;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DeadlineNotifier {
    private static final String CHANNEL_ID = "deadline_alerts";
    private static final String PREFS = "deadline_notify";
    private static final String KEY_ALARMS = "scheduled_alarms";
    public static final String EXTRA_DEADLINE_ID = "deadline_id";
    public static final String EXTRA_OFFSET_MILLIS = "offset_millis";

    private static final long SOON_OFFSET = TimeUnit.HOURS.toMillis(3);
    private static final long URGENT_OFFSET = TimeUnit.MINUTES.toMillis(30);
    private static final long TRIGGER_GRACE = TimeUnit.SECONDS.toMillis(15);

    private DeadlineNotifier() {
    }

    public static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "\u622a\u6b62\u63d0\u9192",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("\u5b66\u4e60\u901a\u4f5c\u4e1a\u548c\u8003\u8bd5\u622a\u6b62\u63d0\u9192");
        manager.createNotificationChannel(channel);
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarm == null || alarm.canScheduleExactAlarms();
    }

    public static void maybeNotify(Context context, DeadlineItem item) {
        notifyBestMissedReminder(context, item);
    }

    public static void notifyDue(Context context, String id, long offsetMillis) {
        if (id == null || id.isEmpty()) {
            rescheduleAll(context);
            return;
        }
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        if (item != null) {
            long offset = offsetMillis > 0L ? offsetMillis : AppSettings.notifyOffsetsMillis(context)[0];
            notifyNow(context, item, offset, false);
        }
        rescheduleAll(context);
    }

    public static void checkAll(Context context) {
        List<DeadlineItem> items = new DeadlineStore(context).activeItems();
        for (DeadlineItem item : items) {
            notifyBestMissedReminder(context, item);
        }
        rescheduleAll(context, items);
    }

    public static void scheduleNextCheck(Context context) {
        rescheduleAll(context);
    }

    public static void rescheduleAll(Context context) {
        rescheduleAll(context, new DeadlineStore(context).activeItems(), true);
    }

    public static void rescheduleAll(Context context, List<DeadlineItem> items) {
        rescheduleAll(context, items, true);
    }

    public static void rescheduleUpcomingOnly(Context context) {
        rescheduleUpcomingOnly(context, new DeadlineStore(context).activeItems());
    }

    public static void rescheduleUpcomingOnly(Context context, List<DeadlineItem> items) {
        rescheduleAll(context, items, false);
    }

    private static void rescheduleAll(Context context, List<DeadlineItem> items, boolean allowCatchUp) {
        cancelScheduledAlarms(context);
        cleanupSentMarkers(context, items);
        ensureChannel(context);
        long now = System.currentTimeMillis();
        long[] offsets = AppSettings.notifyOffsetsMillis(context);
        HashSet<String> scheduled = new HashSet<>();
        for (DeadlineItem item : items) {
            if (!canNotify(context, item, now)) {
                continue;
            }
            boolean caughtUp = false;
            for (long offset : offsets) {
                long triggerAt = item.dueAt - offset;
                if (triggerAt <= now + TRIGGER_GRACE) {
                    if (allowCatchUp && !caughtUp && shouldCatchUp(item, offset, now)) {
                        notifyNow(context, item, offset, true);
                        caughtUp = true;
                    }
                    continue;
                }
                String key = alarmKey(item, offset);
                scheduleAlarm(context, key, triggerAt);
                scheduled.add(key);
            }
        }
        prefs(context).edit().putStringSet(KEY_ALARMS, scheduled).apply();
    }

    public static void cancelScheduledAlarms(Context context) {
        SharedPreferences prefs = prefs(context);
        Set<String> scheduled = new HashSet<>(prefs.getStringSet(KEY_ALARMS, new HashSet<>()));
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            for (String key : scheduled) {
                PendingIntent pending = pendingIntent(context, key, PendingIntent.FLAG_NO_CREATE);
                if (pending != null) {
                    alarm.cancel(pending);
                    pending.cancel();
                }
            }
        }
        prefs.edit().remove(KEY_ALARMS).apply();
    }

    public static void ignoreItem(Context context, String id) {
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        if (item == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        for (long offset : AppSettings.notifyOffsetsMillis(context)) {
            editor.putBoolean(sentKey(item, offset), true);
        }
        editor.apply();
        cancelNotification(context, item);
        rescheduleAll(context);
    }

    public static void deleteItem(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        DeadlineStore store = new DeadlineStore(context);
        DeadlineItem item = store.itemById(id);
        store.deleteItem(id);
        if (item != null) {
            cancelNotification(context, item);
        }
        rescheduleAll(context);
        OverlayBridge.publish(context);
        context.sendBroadcast(new Intent(DeadlineReceiver.ACTION_REFRESH).setPackage(context.getPackageName()));
    }

    public static void sendTestNotification(Context context) {
        DeadlineItem item = new DeadlineItem();
        item.id = "test_" + System.currentTimeMillis();
        item.type = "\u4f5c\u4e1a";
        item.title = "\u901a\u77e5\u6548\u679c\u6d4b\u8bd5";
        item.course = "\u8c03\u8bd5\u8bfe\u7a0b";
        item.dueAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        notifyNow(context, item, URGENT_OFFSET, false);
    }

    private static boolean canNotify(Context context, DeadlineItem item, long now) {
        return item != null
                && item.id != null && !item.id.isEmpty()
                && !item.submitted
                && item.dueAt > now
                && AppSettings.shouldNotifyType(context, item.type);
    }

    private static boolean shouldCatchUp(DeadlineItem item, long offset, long now) {
        long delta = item.dueAt - now;
        if (delta <= 0L || delta > offset) {
            return false;
        }
        // If a long-range reminder has already been missed, send exactly one catch-up reminder,
        // then keep the shorter future reminders scheduled. This avoids the old "blast all reminders"
        // behavior when freshly captured items are already inside multiple reminder windows.
        return true;
    }

    private static void notifyBestMissedReminder(Context context, DeadlineItem item) {
        long now = System.currentTimeMillis();
        if (!canNotify(context, item, now)) {
            return;
        }
        for (long offset : AppSettings.notifyOffsetsMillis(context)) {
            if (item.dueAt - offset <= now + TRIGGER_GRACE && shouldCatchUp(item, offset, now)) {
                notifyNow(context, item, offset, true);
                return;
            }
        }
    }

    private static void scheduleAlarm(Context context, String key, long triggerAt) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            return;
        }
        PendingIntent pending = pendingIntent(context, key, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            return;
        }
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
    }

    private static PendingIntent pendingIntent(Context context, String key, int flags) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_NOTIFY)
                .putExtra(EXTRA_DEADLINE_ID, idFromAlarmKey(key))
                .putExtra(EXTRA_OFFSET_MILLIS, offsetFromAlarmKey(key));
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                key.hashCode(),
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyNow(Context context, DeadlineItem item, long offsetMillis, boolean catchUp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String key = sentKey(item, offsetMillis);
        if (prefs.getBoolean(key, false)) {
            return;
        }
        ensureChannel(context);
        PendingIntent contentIntent = openModuleIntent(context, item);
        android.app.Notification.Builder builder = new android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(item.type + "\u5feb\u622a\u6b62\u4e86")
                .setContentText(notificationLine(item, offsetMillis, catchUp))
                .setStyle(new android.app.Notification.BigTextStyle().bigText(bigText(item, offsetMillis, catchUp)))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .addAction(notificationAction(context, android.R.drawable.ic_menu_view,
                        "\u6253\u5f00\u5b66\u4e60\u901a", openChaoxingIntent(context)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_close_clear_cancel,
                        "\u5ffd\u7565\u672c\u6b21", actionIntent(context, DeadlineReceiver.ACTION_IGNORE, item.id)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_delete,
                        "\u5220\u9664\u5f85\u529e", actionIntent(context, DeadlineReceiver.ACTION_DELETE, item.id)));
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(notificationId(item), builder.build());
            prefs.edit().putBoolean(key, true).apply();
        }
    }

    private static android.app.Notification.Action notificationAction(
            Context context,
            int iconRes,
            String title,
            PendingIntent intent) {
        return new android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, iconRes),
                title,
                intent)
                .build();
    }

    private static PendingIntent openModuleIntent(Context context, DeadlineItem item) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openChaoxingIntent(Context context) {
        Intent target = context.getPackageManager().getLaunchIntentForPackage("com.chaoxing.mobile");
        if (target == null) {
            target = new Intent(context, MainActivity.class);
        }
        target.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                "open_chaoxing".hashCode(),
                target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent actionIntent(Context context, String action, String id) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_DEADLINE_ID, id);
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                (action + "|" + id).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelNotification(Context context, DeadlineItem item) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && item != null) {
            manager.cancel(notificationId(item));
        }
    }

    private static int notificationId(DeadlineItem item) {
        String basis = (item.id == null || item.id.isEmpty()) ? item.stableId() : item.id;
        return basis.hashCode();
    }

    private static String notificationLine(DeadlineItem item, long offsetMillis, boolean catchUp) {
        String prefix = reminderLabel(offsetMillis);
        if (catchUp) {
            prefix += "\u00b7\u8865\u53d1";
        }
        return prefix + "\uff1a" + item.title + "\uff0c" + DateText.dueLine(item.dueAt);
    }

    private static String bigText(DeadlineItem item, long offsetMillis, boolean catchUp) {
        StringBuilder builder = new StringBuilder();
        if (item.course != null && !item.course.isEmpty()) {
            builder.append(item.course).append('\n');
        }
        builder.append(item.title).append('\n')
                .append(DateText.dueLine(item.dueAt)).append('\n')
                .append("\u63d0\u9192\uff1a").append(reminderLabel(offsetMillis));
        if (catchUp) {
            builder.append("\uff08\u8865\u53d1\uff09");
        }
        return builder.toString();
    }

    private static String reminderLabel(long offsetMillis) {
        if (offsetMillis >= TimeUnit.HOURS.toMillis(1)) {
            long hours = offsetMillis / TimeUnit.HOURS.toMillis(1);
            return "\u63d0\u524d " + hours + " \u5c0f\u65f6";
        }
        long minutes = Math.max(1L, offsetMillis / TimeUnit.MINUTES.toMillis(1));
        return "\u63d0\u524d " + minutes + " \u5206\u949f";
    }

    private static void cleanupSentMarkers(Context context, List<DeadlineItem> items) {
        SharedPreferences prefs = prefs(context);
        Map<String, ?> all = prefs.getAll();
        if (all.isEmpty()) {
            return;
        }
        HashSet<String> validPrefixes = new HashSet<>();
        for (DeadlineItem item : items) {
            if (item == null || item.id == null || item.id.isEmpty()) {
                continue;
            }
            validPrefixes.add("sent_" + item.id + "_" + item.dueAt + "_");
        }
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : all.keySet()) {
            if (!key.startsWith("sent_")) {
                continue;
            }
            boolean keep = false;
            for (String prefix : validPrefixes) {
                if (key.startsWith(prefix)) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private static String alarmKey(DeadlineItem item, long offsetMillis) {
        return item.id + "|" + item.dueAt + "|" + offsetMillis;
    }

    private static String sentKey(DeadlineItem item, long offsetMillis) {
        return "sent_" + item.id + "_" + item.dueAt + "_" + offsetMillis;
    }

    private static String idFromAlarmKey(String key) {
        int split = key == null ? -1 : key.indexOf('|');
        return split > 0 ? key.substring(0, split) : key;
    }

    private static long offsetFromAlarmKey(String key) {
        if (key == null) {
            return 0L;
        }
        int last = key.lastIndexOf('|');
        if (last < 0 || last + 1 >= key.length()) {
            return 0L;
        }
        try {
            return Long.parseLong(key.substring(last + 1));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
