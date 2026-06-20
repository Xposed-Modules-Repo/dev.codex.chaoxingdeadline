package dev.codex.chaoxingdeadline;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * Main screen: displays LSPosed activation status, deadline stats, and the deadline list.
 * Automatically refreshes every 5 seconds. Users can manually trigger an active refresh
 * which sends a command to the hook module via remote SharedPreferences.
 */
public final class MainActivity extends BaseActivity implements App.ServiceListener {
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";

    private final List<DeadlineItem> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DeadlineStore store;
    private LinearLayout itemList;
    private TextView activeBadge;
    private TextView statusText;
    private TextView countPending;
    private TextView countExpired;
    private TextView countBlocked;
    private LinearLayout emptyContainer;

    private final Runnable tick = new Runnable() {
        @Override public void run() { reload(false); handler.postDelayed(this, 5000L); }
    };
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(false); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        store = new DeadlineStore(this);
        setContentView(buildContent());
        DeadlineNotifier.ensureChannel(this);
        requestNotificationPermission();
        reload(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.addServiceListener(this);
        IntentFilter filter = new IntentFilter(DeadlineReceiver.ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(refreshReceiver, filter);
        handler.post(tick);
        DeadlineNotifier.checkAll(this);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        App.removeServiceListener(this);
        unregisterReceiver(refreshReceiver);
        super.onStop();
    }

    @Override
    public void onServiceChanged(XposedService service) {
        runOnUiThread(() -> reload(false));
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiTheme.background(this));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), statusBarHeight() + dp(12), dp(20), dp(24));

        // --- header ---
        LinearLayout header = hbox();
        TextView title = text("学习通截止提醒", 26, true, UiTheme.text(this));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView settingsBtn = icon("⚙", 26, UiTheme.accent(this));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(42), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // --- status card ---
        LinearLayout status = card();
        status.setPadding(dp(18), dp(16), dp(18), dp(16));
        activeBadge = text("", 15, true, UiTheme.text(this));
        activeBadge.setPadding(0, 0, 0, dp(6));
        status.addView(activeBadge, new LinearLayout.LayoutParams(-1, -2));
        statusText = text("", 12, false, UiTheme.muted(this));
        status.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        root.addView(status, marTop(dp(18)));

        // --- stat chips ---
        LinearLayout stats = hbox();
        countPending = new TextView(this);
        countExpired = new TextView(this);
        countBlocked = new TextView(this);
        stats.addView(statChip("⏳", "待办", countPending), chipParams(1f));
        stats.addView(space(dp(10)));
        stats.addView(statChip("✓", "已截止", countExpired), chipParams(1f));
        stats.addView(space(dp(10)));
        stats.addView(statChip("⊘", "已屏蔽", countBlocked), chipParams(1f));
        root.addView(stats, marTop(dp(16)));

        // --- section + refresh ---
        root.addView(sectionHeader("待办事项"));

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(14);
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(dp(36), dp(11), dp(36), dp(11));
        refreshBtn.setBackground(UiTheme.fillOnly(this, UiTheme.accent(this), dp(20)));
        refreshBtn.setOnClickListener(v -> { requestActiveRefresh(true); reload(false); });
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(6), 0, dp(12));
        actions.addView(refreshBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        // --- item list ---
        itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        root.addView(itemList, new LinearLayout.LayoutParams(-1, -2));

        // --- empty state (initially hidden) ---
        emptyContainer = new LinearLayout(this);
        emptyContainer.setGravity(Gravity.CENTER);
        emptyContainer.setPadding(dp(8), dp(20), dp(8), dp(20));
        TextView emptyText = text("", 15, false, UiTheme.muted(this));
        emptyText.setGravity(Gravity.CENTER);
        emptyContainer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        root.addView(emptyContainer, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout statChip(String iconChar, String label, TextView countView) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(14), dp(12), dp(14));
        chip.setBackground(UiTheme.cardBg(this, 14));

        countView.setText("0");
        countView.setTextSize(22);
        countView.setTextColor(UiTheme.text(this));
        countView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(0, 0, 0, dp(3));
        chip.addView(countView, new LinearLayout.LayoutParams(-1, -2));

        TextView lbl = text(label, 11, false, UiTheme.muted(this));
        lbl.setGravity(Gravity.CENTER);
        chip.addView(lbl, new LinearLayout.LayoutParams(-1, -2));
        return chip;
    }

    private LinearLayout.LayoutParams chipParams(float weight) {
        return new LinearLayout.LayoutParams(0, -2, weight);
    }

    private View space(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 0));
        return v;
    }

    private LinearLayout.LayoutParams marTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    private void reload(boolean firstLoad) {
        items.clear();
        items.addAll(store.activeItems());
        rebuildItemList();

        Activation activation = readActivation();
        activeBadge.setText(activation.title);
        activeBadge.setTextColor(activation.active ? UiTheme.successText(this) : UiTheme.warningText(this));
        statusText.setText(activation.detail);

        int pending = 0, expired = 0, blocked = store.blockedCourses().size();
        long now = System.currentTimeMillis();
        for (DeadlineItem item : items) {
            if (item.dueAt <= now) expired++;
            else pending++;
        }

        countPending.setText(String.valueOf(pending));
        countExpired.setText(String.valueOf(expired));
        countBlocked.setText(String.valueOf(blocked));

        boolean empty = items.isEmpty();
        itemList.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
        ((TextView) emptyContainer.getChildAt(0)).setText(activation.active
                ? "暂无待办\n\n打开学习通后会自动捕获作业和考试截止时间。"
                : "尚未检测到激活\n\n请在 LSPosed 中勾选本模块，作用域选择「学习通」。");

        if (firstLoad && activation.active) requestActiveRefresh(false);
    }

    private void rebuildItemList() {
        itemList.removeAllViews();
        // sort: active first (earliest deadline top), then expired (most recent first)
        long now = System.currentTimeMillis();
        List<DeadlineItem> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> {
            boolean aExp = a.dueAt <= now, bExp = b.dueAt <= now;
            if (aExp != bExp) return aExp ? 1 : -1;
            // both active: sooner first; both expired: more recently expired first
            return aExp ? Long.compare(b.dueAt, a.dueAt) : Long.compare(a.dueAt, b.dueAt);
        });
        for (DeadlineItem item : sorted) itemList.addView(itemRow(item), rowParams());
    }

    private View itemRow(DeadlineItem item) {
        long delta = item.dueAt - System.currentTimeMillis();
        boolean expired = delta <= 0L, urgent = !expired && delta <= 24L * 60L * 60L * 1000L;

        // outer wrapper: swipe on expired items
        LinearLayout swipeWrapper = new LinearLayout(this);
        swipeWrapper.setOrientation(LinearLayout.HORIZONTAL);
        swipeWrapper.setClipChildren(false);
        swipeWrapper.setClipToPadding(false);

        // the card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(urgent ? UiTheme.fillOnly(this, UiTheme.dangerBg(this), dp(16)) : UiTheme.cardBg(this, 16));
        card.setAlpha(expired ? 0.55f : 1f);

        TextView badge = new TextView(this);
        badge.setText("作业".equals(item.type) ? "作" : "考");
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        int badgeColor = "作业".equals(item.type) ? UiTheme.badgeHomework(this) : UiTheme.badgeExam(this);
        int badgeBg = "作业".equals(item.type) ? UiTheme.badgeHomeworkBg(this) : UiTheme.badgeExamBg(this);
        badge.setTextColor(badgeColor);
        badge.setBackground(UiTheme.fillOnly(this, badgeBg, dp(10)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36), dp(36));
        bp.rightMargin = dp(14);
        bp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(badge, bp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);

        int titleColor = expired ? UiTheme.muted(this) : UiTheme.text(this);
        TextView tvTitle = text(item.title, 15, true, titleColor);
        texts.addView(tvTitle, new LinearLayout.LayoutParams(-1, -2));

        String courseLabel = item.course == null || item.course.isEmpty() ? "未识别课程" : item.course;
        TextView tvCourse = text(courseLabel, 12, false, UiTheme.muted(this));
        tvCourse.setPadding(0, dp(4), 0, 0);
        texts.addView(tvCourse, new LinearLayout.LayoutParams(-1, -2));

        int dueColor = expired ? UiTheme.muted(this) : UiTheme.accent(this);
        TextView tvDue = text(DateText.dueLine(item.dueAt), 12, false, dueColor);
        tvDue.setPadding(0, dp(4), 0, 0);
        texts.addView(tvDue, new LinearLayout.LayoutParams(-1, -2));

        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        // "已截止" label on the right for expired items
        if (expired) {
            TextView expiredLabel = text("已截止", 13, false, UiTheme.muted(this));
            expiredLabel.setPadding(dp(10), 0, 0, 0);
            expiredLabel.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(expiredLabel, new LinearLayout.LayoutParams(-2, -2));
        }

        swipeWrapper.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // swipe-to-delete for expired items
        if (expired) {
            card.setTag("swipeable");
            card.setOnTouchListener(new SwipeDismissListener(item, swipeWrapper, card));
        }

        return swipeWrapper;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }

    private Activation readActivation() {
        SharedPreferences prefs = getSharedPreferences("status", MODE_PRIVATE);
        long activeAt = prefs.getLong("last_active_at", 0L);
        long captureAt = prefs.getLong("last_capture_at", 0L);
        String lastStatus = prefs.getString("last_status", "");
        String lastSource = prefs.getString("last_source", "");

        XposedService service = App.getService();
        boolean serviceReady = service != null;
        boolean targetRunning = false;
        String framework = "";
        if (service != null) {
            try {
                framework = service.getFrameworkName() + " API " + service.getApiVersion();
                for (HookedTarget target : service.getRunningTargets())
                    if (TARGET_PACKAGE.equals(target.getProcessName())) { targetRunning = true; break; }
            } catch (Throwable ignored) {}
        }

        boolean active = targetRunning || activeAt > 0L;
        StringBuilder detail = new StringBuilder();
        detail.append(serviceReady ? "LSPosed 已连接" : "LSPosed 未连接");
        if (!framework.isEmpty()) detail.append(" · ").append(framework);
        detail.append("\n学习通进程：").append(targetRunning ? "运行中" : "未运行");
        if (targetRunning) detail.append("\n最近加载：当前运行中");
        else if (activeAt > 0L) detail.append("\n最近加载：").append(format(activeAt));
        if (captureAt > 0L) detail.append("\n最近捕获：").append(format(captureAt));
        String title = targetRunning ? "LSPosed 已激活" : (active ? "LSPosed 状态：已加载" : "LSPosed 状态：未激活");
        return new Activation(title, detail.toString(), active);
    }

    private void requestActiveRefresh(boolean launchIfNeeded) {
        XposedService service = App.getService();
        if (service != null) {
            try {
                sendRefreshCommand(service);
                getSharedPreferences("status", MODE_PRIVATE).edit()
                        .putString("last_status", "已请求主动刷新").putString("last_source", "本软件").apply();
            } catch (Throwable t) {
                Toast.makeText(this, "刷新失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
            }
        }
        if (launchIfNeeded) {
            boolean running = false;
            if (service != null) try {
                for (HookedTarget target : service.getRunningTargets())
                    if (TARGET_PACKAGE.equals(target.getProcessName())) running = true;
            } catch (Throwable ignored) {}
            if (!running) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
                if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
            }
        }
    }

    private void sendRefreshCommand(XposedService service) {
        try { service.getRemotePreferences("commands").edit().putLong("refresh_seq", System.currentTimeMillis()).apply(); }
        catch (Throwable t) { Toast.makeText(this, "失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show(); }
    }

    private String format(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(millis));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }

    private class SwipeDismissListener implements View.OnTouchListener {
        private float startX;
        private boolean swiping;
        private final DeadlineItem item;
        private final View wrapper;
        private final View card;

        SwipeDismissListener(DeadlineItem item, View wrapper, View card) {
            this.item = item;
            this.wrapper = wrapper;
            this.card = card;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    swiping = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX;
                    if (dx < -dp(16)) swiping = true;
                    if (swiping && dx < 0) {
                        card.setTranslationX(dx);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = card.getTranslationX();
                    int threshold = wrapper.getWidth() / 3;
                    if (swiping && dx < -threshold) {
                        // dismiss
                        card.animate().translationX(-wrapper.getWidth()).alpha(0f)
                                .setDuration(250)
                                .withEndAction(() -> {
                                    store.deleteItem(item.id);
                                    items.remove(item);
                                    itemList.removeView(wrapper);
                                    long now = System.currentTimeMillis();
                                    int pending = 0, expired = 0;
                                    for (DeadlineItem it : items) {
                                        if (it.dueAt <= now) expired++; else pending++;
                                    }
                                    countPending.setText(String.valueOf(pending));
                                    countExpired.setText(String.valueOf(expired));
                                    if (items.isEmpty()) {
                                        itemList.setVisibility(View.GONE);
                                        emptyContainer.setVisibility(View.VISIBLE);
                                    }
                                })
                                .start();
                    } else {
                        // spring back
                        card.animate().translationX(0f).setDuration(200).start();
                    }
                    swiping = false;
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Activation {
        final String title, detail; final boolean active;
        Activation(String t, String d, boolean a) { title = t; detail = d; active = a; }
    }
}
