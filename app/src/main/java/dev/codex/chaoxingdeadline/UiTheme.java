package dev.codex.chaoxingdeadline;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

public final class UiTheme {
    private UiTheme() {}

    public static boolean dark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // -- backgrounds --
    public static int background(Context context) {
        return dark(context) ? Color.rgb(10, 12, 18) : Color.rgb(248, 249, 252);
    }

    public static int card(Context context) {
        return dark(context) ? Color.rgb(24, 28, 39) : Color.rgb(255, 255, 255);
    }

    public static int sheet(Context context) {
        return dark(context) ? Color.rgb(20, 23, 33) : Color.rgb(255, 255, 255);
    }

    // -- strokes & dividers --
    public static int stroke(Context context) {
        return dark(context) ? Color.rgb(42, 52, 68) : Color.rgb(232, 236, 244);
    }

    public static int divider(Context context) {
        return dark(context) ? Color.rgb(42, 52, 68) : Color.rgb(241, 243, 249);
    }

    // -- text --
    public static int text(Context context) {
        return dark(context) ? Color.rgb(235, 240, 247) : Color.rgb(15, 23, 42);
    }

    public static int muted(Context context) {
        return dark(context) ? Color.rgb(148, 163, 184) : Color.rgb(100, 116, 139);
    }

    public static int accent(Context context) {
        return dark(context) ? Color.rgb(99, 153, 255) : Color.rgb(59, 111, 240);
    }

    // -- status colors --

    public static int successText(Context context) {
        return dark(context) ? Color.rgb(148, 231, 198) : Color.rgb(20, 120, 84);
    }


    public static int warningText(Context context) {
        return dark(context) ? Color.rgb(255, 200, 129) : Color.rgb(174, 93, 24);
    }

    public static int dangerBg(Context context) {
        return dark(context) ? Color.rgb(68, 35, 42) : Color.rgb(255, 238, 240);
    }


    // -- badge colors --
    public static int badgeHomework(Context context) {
        return dark(context) ? Color.rgb(99, 153, 255) : Color.rgb(59, 111, 240);
    }

    public static int badgeHomeworkBg(Context context) {
        return dark(context) ? Color.argb(35, 99, 153, 255) : Color.argb(20, 59, 111, 240);
    }

    public static int badgeExam(Context context) {
        return dark(context) ? Color.rgb(236, 134, 72) : Color.rgb(214, 94, 34);
    }

    public static int badgeExamBg(Context context) {
        return dark(context) ? Color.argb(35, 236, 134, 72) : Color.argb(20, 214, 94, 34);
    }

    // -- drawable builders --
    public static GradientDrawable rounded(Context context, int color) {
        return rounded(context, color, dp(context, 8));
    }

    public static GradientDrawable rounded(Context context, int color, int radius) {
        return rounded(context, color, radius, stroke(context));
    }

    public static GradientDrawable rounded(Context context, int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeColor != 0) d.setStroke(dp(context, 1), strokeColor);
        return d;
    }

    public static GradientDrawable cardBg(Context context) {
        return rounded(context, card(context), dp(context, 16), stroke(context));
    }

    public static GradientDrawable cardBg(Context context, int radius) {
        return rounded(context, card(context), radius, stroke(context));
    }

    public static GradientDrawable fillOnly(Context context, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}