package dev.chaoxingdeadline;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends BaseActivity {
    private EditText notifyHours;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(20), statusBarHeight() + dp(8), dp(20), dp(16));

        root.addView(titleBar("设置"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // -- 通用 --
        content.addView(sectionHeader("通用"));
        LinearLayout group1 = card();
        group1.addView(switchRow("隐藏桌面图标", "隐藏后仍可从 LSPosed 模块设置入口打开", AppSettings.launcherHidden(this),
                (b, c) -> AppSettings.setLauncherHidden(this, c)));
        group1.addView(divider());
        group1.addView(switchRow("学习通内弹窗", "打开学习通时显示待办摘要", AppSettings.overlayEnabled(this),
                (b, c) -> AppSettings.setOverlayEnabled(this, c)));
        group1.addView(divider());
        group1.addView(switchRow("自动删除已截止待办", "截止时间已过的项目自动移除", AppSettings.autoDeleteExpired(this),
                (b, c) -> AppSettings.setAutoDeleteExpired(this, c)));
        content.addView(group1, groupParams());

        // -- 通知 --
        content.addView(sectionHeader("通知"));
        LinearLayout group2 = card();
        group2.addView(switchRow("最后提醒", "任务的截止时间到达3小时和30分钟时提醒", AppSettings.finalReminderEnabled(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_enabled", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("作业提醒", "作业到达设定的提醒时间提醒", AppSettings.notifyHomework(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_homework", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("考试提醒", "考试到达设定的提醒时间提醒", AppSettings.notifyExam(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_exam", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(hourRow());
        group2.addView(divider());
        View exactAlarm = innerActionRow("精确闹钟权限", exactAlarmSubtitle());
        exactAlarm.setOnClickListener(v -> openExactAlarmSettings());
        group2.addView(exactAlarm);
        group2.addView(divider());
        View testNotification = innerActionRow("发送测试通知", "立即发送一条作业提醒，用来查看通知效果");
        testNotification.setOnClickListener(v -> sendTestNotification());
        group2.addView(testNotification);
        content.addView(group2, groupParams());

        // -- 管理 --
        content.addView(sectionHeader("管理"));
        View course = actionRow("课程管理", "手动选择哪些课程的作业和考试需要显示");
        course.setOnClickListener(v -> startActivity(new Intent(this, CourseBlockActivity.class)));
        content.addView(course, groupParams());

        // -- 关于 --
        content.addView(sectionHeader("其他"));
        View about = actionRow("关于", "版本信息与开源许可");
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        content.addView(about, groupParams());

        return root;
    }

    private LinearLayout titleBar(String titleValue) {
        LinearLayout top = hbox();
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), -1));
        TextView title = text(titleValue, 22, true, UiTheme.text(this));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(44), -1));
        return top;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(UiTheme.divider(this));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.leftMargin = dp(16);
        p.rightMargin = dp(16);
        v.setLayoutParams(p);
        return v;
    }

    private View hourRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setMinimumHeight(dp(70));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text("提醒时间", 16, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text("小时，范围 1 — 168", 13, false, UiTheme.muted(this));
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        notifyHours = new EditText(this);
        notifyHours.setInputType(InputType.TYPE_CLASS_NUMBER);
        notifyHours.setSingleLine(true);
        notifyHours.setText(String.valueOf(AppSettings.notifyHours(this)));
        notifyHours.setTextColor(UiTheme.text(this));
        notifyHours.setTextSize(16);
        notifyHours.setGravity(Gravity.CENTER);
        notifyHours.setBackground(UiTheme.fillOnly(this, UiTheme.background(this), dp(12)));
        notifyHours.setPadding(dp(12), 0, dp(12), 0);
        row.addView(notifyHours, new LinearLayout.LayoutParams(dp(64), dp(40)));

        TextView save = icon("✓", 20, UiTheme.accent(this));
        save.setPadding(dp(8), 0, dp(4), 0);
        save.setOnClickListener(v -> saveNotifyHours());
        row.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        return row;
    }

    private void saveNotifyHours() {
        int hours;
        try { hours = Integer.parseInt(notifyHours.getText().toString().trim()); }
        catch (Throwable ignored) { hours = 24; }
        hours = Math.max(1, Math.min(168, hours));
        notifyHours.setText(String.valueOf(hours));
        AppSettings.prefs(this).edit().putInt("notify_hours", hours).apply();
        DeadlineNotifier.rescheduleUpcomingOnly(this);
        OverlayBridge.publish(this);
        Toast.makeText(this, "操作成功", Toast.LENGTH_SHORT).show();
    }

    private String exactAlarmSubtitle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "当前系统无需单独授权";
        }
        return DeadlineNotifier.canScheduleExactAlarms(this)
                ? "已允许，提醒会更准时"
                : "未允许，提醒可能延迟，点击前往开启";
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "当前系统无需单独开启精确闹钟", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable throwable) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        }
    }

    private void sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            Toast.makeText(this, "请先允许通知权限", Toast.LENGTH_SHORT).show();
            return;
        }
        DeadlineNotifier.ensureChannel(this);
        DeadlineNotifier.sendTestNotification(this);
        Toast.makeText(this, "已发送测试通知", Toast.LENGTH_SHORT).show();
    }

    private View switchRow(String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setMinimumHeight(dp(68));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setText("");
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View innerActionRow(String title, String subtitle) {
        return actionRow(title, subtitle, false);
    }

    private View actionRow(String title, String subtitle) {
        return actionRow(title, subtitle, true);
    }

    private View actionRow(String title, String subtitle, boolean standalone) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setMinimumHeight(dp(70));
        if (standalone) {
            row.setBackground(UiTheme.cardBg(this));
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = icon("›", 28);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(44)));
        return row;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(16));
        return p;
    }
}
