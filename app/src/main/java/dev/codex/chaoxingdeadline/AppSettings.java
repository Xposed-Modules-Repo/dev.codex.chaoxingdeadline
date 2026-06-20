package dev.codex.chaoxingdeadline;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

public final class AppSettings {
    public static final String PREFS = "app_settings";
    public static final String LAUNCHER_ALIAS = "dev.codex.chaoxingdeadline.LauncherActivity";

    private AppSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean notificationsEnabled(Context context) {
        return prefs(context).getBoolean("notify_enabled", true);
    }

    public static boolean notifyHomework(Context context) {
        return prefs(context).getBoolean("notify_homework", true);
    }

    public static boolean notifyExam(Context context) {
        return prefs(context).getBoolean("notify_exam", true);
    }

    public static int notifyHours(Context context) {
        return Math.max(1, Math.min(168, prefs(context).getInt("notify_hours", 24)));
    }

    public static boolean overlayEnabled(Context context) {
        return prefs(context).getBoolean("overlay_enabled", true);
    }

    public static void setOverlayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("overlay_enabled", enabled).apply();
        try {
            if (App.getService() != null) {
                App.getService().getRemotePreferences("app_settings")
                        .edit()
                        .putBoolean("overlay_enabled", enabled)
                        .apply();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean launcherHidden(Context context) {
        try {
            ComponentName alias = new ComponentName(context, LAUNCHER_ALIAS);
            int state = context.getPackageManager().getComponentEnabledSetting(alias);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return false;
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return prefs(context).getBoolean("launcher_hidden", false);
    }

    public static boolean autoDeleteExpired(Context context) {
        return prefs(context).getBoolean("auto_delete_expired", false);
    }

    public static void setAutoDeleteExpired(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("auto_delete_expired", enabled).apply();
        if (enabled) {
            new DeadlineStore(context).prune();
        }
    }

    public static void setLauncherHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean("launcher_hidden", hidden).apply();
        PackageManager manager = context.getPackageManager();
        ComponentName alias = new ComponentName(context, LAUNCHER_ALIAS);
        manager.setComponentEnabledSetting(
                alias,
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    public static boolean shouldNotifyType(Context context, String type) {
        if (!notificationsEnabled(context)) {
            return false;
        }
        if ("\u4f5c\u4e1a".equals(type)) {
            return notifyHomework(context);
        }
        if ("\u8003\u8bd5".equals(type)) {
            return notifyExam(context);
        }
        return false;
    }
}
