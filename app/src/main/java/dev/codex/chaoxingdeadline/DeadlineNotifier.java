package dev.codex.chaoxingdeadline;

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

import java.util.concurrent.TimeUnit;

public final class DeadlineNotifier {
    private static final String CHANNEL_ID = "deadline_alerts";
    private static final String PREFS = "deadline_notify";
    private static final int CHECK_REQUEST = 41027;

    private DeadlineNotifier() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "\u622a\u6b62\u63d0\u9192",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("\u5b66\u4e60\u901a\u4f5c\u4e1a\u548c\u8003\u8bd5\u622a\u6b62\u63d0\u9192");
        manager.createNotificationChannel(channel);
    }

    public static void maybeNotify(Context context, DeadlineItem item) {
        long delta = item.dueAt - System.currentTimeMillis();
        if (item.submitted || delta <= 0L || !AppSettings.shouldNotifyType(context, item.type)) {
            return;
        }
        if (delta > TimeUnit.HOURS.toMillis(AppSettings.notifyHours(context))) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = "sent_" + item.id + "_" + AppSettings.notifyHours(context);
        if (prefs.getBoolean(key, false)) {
            return;
        }
        ensureChannel(context);
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(item.type + "\u5feb\u622a\u6b62\u4e86")
                .setContentText(item.title + "\uff0c" + DateText.dueLine(item.dueAt))
                .setStyle(new android.app.Notification.BigTextStyle()
                        .bigText((item.course == null || item.course.isEmpty() ? "" : item.course + "\n")
                                + item.title + "\n" + DateText.dueLine(item.dueAt)))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(item.id.hashCode(), notification);
        prefs.edit().putBoolean(key, true).apply();
    }

    public static void checkAll(Context context) {
        for (DeadlineItem item : new DeadlineStore(context).activeItems()) {
            maybeNotify(context, item);
        }
        scheduleNextCheck(context);
    }

    public static void scheduleNextCheck(Context context) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        long lead = TimeUnit.HOURS.toMillis(AppSettings.notifyHours(context));
        for (DeadlineItem item : new DeadlineStore(context).activeItems()) {
            if (!AppSettings.shouldNotifyType(context, item.type) || item.dueAt <= now) {
                continue;
            }
            long trigger = Math.max(now + TimeUnit.MINUTES.toMillis(1), item.dueAt - lead);
            if (trigger < next) {
                next = trigger;
            }
        }
        if (next == Long.MAX_VALUE) {
            return;
        }
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_CHECK);
        BridgeAuth.attach(context, intent);
        PendingIntent pending = PendingIntent.getBroadcast(
                context,
                CHECK_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
        }
    }
}
