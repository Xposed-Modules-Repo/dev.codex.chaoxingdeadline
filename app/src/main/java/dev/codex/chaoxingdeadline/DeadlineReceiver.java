package dev.codex.chaoxingdeadline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DeadlineReceiver extends BroadcastReceiver {
    public static final String ACTION_ITEM = "dev.codex.chaoxingdeadline.DEADLINE_ITEM";
    public static final String ACTION_STATUS = "dev.codex.chaoxingdeadline.STATUS";
    public static final String ACTION_REFRESH = "dev.codex.chaoxingdeadline.REFRESH";
    public static final String ACTION_CHECK = "dev.codex.chaoxingdeadline.CHECK";
    public static final String ACTION_COURSE = "dev.codex.chaoxingdeadline.COURSE";
    public static final String EXTRA_ITEM_B64 = "item_b64";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SOURCE = "source";
    private static final String TAG = "ChaoxingDeadline";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
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
            return;
        }
        if (ACTION_COURSE.equals(intent.getAction())) {
            new DeadlineStore(context).rememberCourse(intent.getStringExtra("course"));
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
            Log.i(TAG, "Received deadline payload: " + payload);
            DeadlineItem item = DeadlineItem.fromJson(payload);
            DeadlineStore store = new DeadlineStore(context);
            if (item.course == null || item.course.trim().isEmpty()) {
                String inferred = store.inferCourse((item.title == null ? "" : item.title) + "\n" + (item.raw == null ? "" : item.raw));
                if (!inferred.isEmpty()) {
                    item.course = inferred;
                }
            }
            store.upsert(item);
            store.rememberCourse(item.course);
            context.getSharedPreferences("status", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_capture_at", System.currentTimeMillis())
                    .putString("last_capture_source", item.source)
                    .apply();
            Log.i(TAG, "Stored deadline: " + item.title + " @ " + item.dueAt);
            DeadlineNotifier.maybeNotify(context, item);
            DeadlineNotifier.scheduleNextCheck(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to receive deadline", throwable);
        }
    }
}
