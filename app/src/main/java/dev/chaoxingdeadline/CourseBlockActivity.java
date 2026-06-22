package dev.chaoxingdeadline;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class CourseBlockActivity extends BaseActivity {
    private DeadlineStore store;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        store = new DeadlineStore(this);
        setContentView(buildContent());
        reload();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(20), statusBarHeight() + dp(8), dp(20), dp(16));

        root.addView(titleBar("课程管理"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        View clear = actionRow("↻", "清空手动屏蔽", "恢复所有课程的作业和考试显示");
        clear.setOnClickListener(v -> {
            store.clearBlockedCourses();
            OverlayBridge.publish(this);
            DeadlineNotifier.rescheduleUpcomingOnly(this);
            Toast.makeText(this, "操作成功", Toast.LENGTH_SHORT).show();
            reload();
        });
        content.addView(clear, groupParams());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void reload() {
        list.removeAllViews();
        List<String> courses = store.knownCourses();
        if (courses.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(36), dp(24), dp(36));
            TextView t = text("还没有读取到课程。\n打开学习通后会自动读取课程。", 14, false, UiTheme.muted(this));
            t.setGravity(Gravity.CENTER);
            empty.addView(t, new LinearLayout.LayoutParams(-1, -2));
            list.addView(empty, groupParams());
            return;
        }
        for (String course : courses) list.addView(courseCard(course), groupParams());
    }

    private View courseCard(String course) {
        LinearLayout card = card();
        card.addView(baseRow("", course, ""));
        LinearLayout checks = new LinearLayout(this);
        checks.setGravity(Gravity.CENTER_VERTICAL);
        checks.setPadding(dp(20), dp(4), dp(18), dp(16));
        addTypeCheck(checks, course, "作业");
        addTypeCheck(checks, course, "考试");
        card.addView(checks, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void addTypeCheck(LinearLayout parent, String course, String type) {
        CheckBox cb = new CheckBox(this);
        cb.setText("查询" + type);
        cb.setTextSize(14);
        cb.setTextColor(UiTheme.text(this));
        cb.setChecked(store.isCourseTypeEnabled(course, type));
        cb.setOnCheckedChangeListener((b, c) -> {
            store.setCourseTypeEnabled(course, type, c);
            OverlayBridge.publish(this);
            DeadlineNotifier.rescheduleUpcomingOnly(this);
        });
        parent.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
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

    private View actionRow(String iconValue, String title, String subtitle) {
        LinearLayout row = baseRow(iconValue, title, subtitle);
        row.setBackground(UiTheme.cardBg(this));
        return row;
    }

    private LinearLayout baseRow(String iconValue, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(16), dp(14));
        row.setMinimumHeight(dp(72));
        if (iconValue != null && !iconValue.isEmpty()) {
            TextView ic = icon(iconValue, 28, UiTheme.muted(this));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(44));
            p.rightMargin = dp(10);
            row.addView(ic, p);
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
        return row;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }
}