package dev.chaoxingdeadline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DeadlineReceiver extends BroadcastReceiver {
    public static final String ACTION_ITEM = "dev.chaoxingdeadline.DEADLINE_ITEM";
    public static final String ACTION_STATUS = "dev.chaoxingdeadline.STATUS";
    public static final String ACTION_REFRESH = "dev.chaoxingdeadline.REFRESH";
    public static final String ACTION_CHECK = "dev.chaoxingdeadline.CHECK";
    public static final String ACTION_NOTIFY = "dev.chaoxingdeadline.NOTIFY";
    public static final String ACTION_IGNORE = "dev.chaoxingdeadline.IGNORE";
    public static final String ACTION_DELETE = "dev.chaoxingdeadline.DELETE";
    public static final String ACTION_COURSE = "dev.chaoxingdeadline.COURSE";
    public static final String ACTION_COURSE_SCAN_RESULT = "dev.chaoxingdeadline.COURSE_SCAN_RESULT";
    public static final String ACTION_COURSE_SCAN_BATCH = "dev.chaoxingdeadline.COURSE_SCAN_BATCH";
    public static final String ACTION_COURSE_SCAN_PERF = "dev.chaoxingdeadline.COURSE_SCAN_PERF";
    public static final String ACTION_SETTINGS_UPDATE = "dev.chaoxingdeadline.SETTINGS_UPDATE";
    public static final String EXTRA_ITEM_B64 = "item_b64";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SOURCE = "source";
    private static final String TAG = "ChaoxingDeadline";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            DeadlineNotifier.rescheduleAll(context);
            return;
        }
        if (!BridgeAuth.isValid(context, intent)) {
            return;
        }
        if (ACTION_STATUS.equals(intent.getAction())) {
            context.getSharedPreferences("status", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", true)
                    .putLong("last_active_at", System.currentTimeMillis())
                    .putString("last_status", intent.getStringExtra(EXTRA_STATUS))
                    .putString("last_source", intent.getStringExtra(EXTRA_SOURCE))
                    .apply();
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (ACTION_CHECK.equals(intent.getAction())) {
            DeadlineNotifier.checkAll(context);
            OverlayBridge.publish(context);
            return;
        }
        if (ACTION_NOTIFY.equals(intent.getAction())) {
            DeadlineNotifier.notifyDue(context,
                    intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID),
                    intent.getLongExtra(DeadlineNotifier.EXTRA_OFFSET_MILLIS, 0L));
            OverlayBridge.publish(context);
            return;
        }
        if (ACTION_IGNORE.equals(intent.getAction())) {
            DeadlineNotifier.ignoreItem(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            OverlayBridge.publish(context);
            return;
        }
        if (ACTION_DELETE.equals(intent.getAction())) {
            DeadlineNotifier.deleteItem(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            return;
        }
        if (ACTION_COURSE.equals(intent.getAction())) {
            DeadlineStore store = new DeadlineStore(context);
            store.rememberCourse(
                    intent.getStringExtra("course_id"),
                    intent.getStringExtra("class_id"),
                    intent.getStringExtra("cpi"),
                    intent.getStringExtra("uid"),
                    intent.getStringExtra("course"),
                    intent.getStringExtra("raw"));
            OverlayBridge.publish(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (ACTION_COURSE_SCAN_RESULT.equals(intent.getAction())) {
            CourseScanScores.record(context,
                    intent.getStringExtra("course_id"),
                    intent.getStringExtra("class_id"),
                    intent.getBooleanExtra("found_deadline", false));
            return;
        }
        if (ACTION_COURSE_SCAN_BATCH.equals(intent.getAction())) {
            CourseScanScores.recordBatch(context,
                    intent.getStringArrayExtra("course_ids"),
                    intent.getStringArrayExtra("class_ids"),
                    intent.getStringArrayExtra("course_names"),
                    intent.getBooleanArrayExtra("found_deadlines"));
            return;
        }
        if (ACTION_COURSE_SCAN_PERF.equals(intent.getAction())) {
            CourseScanThreads.record(context,
                    intent.getIntExtra("threads", CourseScanThreads.DEFAULT),
                    intent.getLongExtra("elapsed_ms", 0L),
                    intent.getIntExtra("refs", 0),
                    intent.getIntExtra("scheduled", 0),
                    intent.getIntExtra("scanned", 0));
            return;
        }
        if (ACTION_SETTINGS_UPDATE.equals(intent.getAction())) {
            if (intent.hasExtra("overlay_enabled")) {
                AppSettings.setOverlayEnabled(context, intent.getBooleanExtra("overlay_enabled", true));
            }
            if (intent.hasExtra("overlay_window_hours")) {
                AppSettings.setOverlayWindowHours(context,
                        intent.getIntExtra("overlay_window_hours", AppSettings.OVERLAY_WINDOW_ALL));
            }
            OverlayBridge.publish(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (!ACTION_ITEM.equals(intent.getAction())) {
            return;
        }
        try {
            String encoded = intent.getStringExtra(EXTRA_ITEM_B64);
            if (encoded == null || encoded.isEmpty()) {
                return;
            }
            String payload = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            DeadlineItem item = DeadlineItem.fromJson(payload);
            DeadlineStore store = new DeadlineStore(context);
            store.resolveCourse(item);
            store.upsert(item);
            if (item.course != null && !item.course.trim().isEmpty()) {
                store.rememberCourse(item.courseId, item.classId, item.cpi, item.uid, item.course, item.raw);
            }
            context.getSharedPreferences("status", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_capture_at", System.currentTimeMillis())
                    .putString("last_capture_source", item.source)
                    .apply();
            Log.i(TAG, "Stored deadline from " + item.source + " type=" + item.type);
            DeadlineNotifier.rescheduleAll(context);
            OverlayBridge.publish(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to receive deadline", throwable);
        }
    }
}
