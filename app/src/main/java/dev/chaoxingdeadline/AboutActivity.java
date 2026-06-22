package dev.chaoxingdeadline;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AboutActivity extends BaseActivity {
    private static final String GITHUB_URL = "https://github.com/Xposed-Modules-Repo/dev.chaoxingdeadline";
    private static final String LICENSE_URL = GITHUB_URL + "/blob/master/LICENSE";
    private static final String NOTICE_URL = GITHUB_URL + "/blob/master/NOTICE";

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

        root.addView(titleBar("关于"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, dp(20), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // App icon
        TextView logo = new TextView(this);
        logo.setText("📋");
        logo.setTextSize(48);
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, 0, 0, dp(12));
        content.addView(logo, new LinearLayout.LayoutParams(-1, -2));

        TextView appName = text("学习通截止提醒", 22, true, UiTheme.text(this));
        appName.setGravity(Gravity.CENTER);
        appName.setPadding(0, 0, 0, dp(4));
        content.addView(appName, new LinearLayout.LayoutParams(-1, -2));

        TextView version = text("版本 1.2", 14, false, UiTheme.muted(this));
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 0, 0, dp(28));
        content.addView(version, new LinearLayout.LayoutParams(-1, -2));

        // Links
        View source = actionRow("查看源代码", "在 GitHub 上查看完整源代码");
        source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))));
        content.addView(source, groupParams());

        View license = actionRow("开源许可", "本项目基于 Apache License 2.0 开源");
        license.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL))));
        content.addView(license, groupParams());

        View thirdParty = actionRow("第三方组件", "包含 libxposed API 102 与本地 patched AAR，组件遵循各自许可证");
        thirdParty.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(NOTICE_URL))));
        content.addView(thirdParty, groupParams());

        View disclaimer = actionRow("免责声明", "本工具仅用于个人待办提醒；项目不隶属于超星、学习通或学校平台，请遵守相关使用规则。");
        content.addView(disclaimer, groupParams());

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

    private View actionRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setMinimumHeight(dp(72));
        row.setBackground(UiTheme.cardBg(this));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
            sub.setPadding(0, dp(3), 0, 0);
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
