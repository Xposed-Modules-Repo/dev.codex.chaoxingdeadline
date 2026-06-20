package dev.codex.chaoxingdeadline;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DateText {
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA));

    private DateText() {
    }

    public static String dueLine(long dueAt) {
        long delta = dueAt - System.currentTimeMillis();
        if (delta <= 0L) {
            return "已截止：" + FORMAT.get().format(new Date(dueAt));
        }
        long hours = Math.max(1L, TimeUnit.MILLISECONDS.toHours(delta));
        long days = hours / 24L;
        long leftHours = hours % 24L;
        String left = days > 0 ? days + "天 " + leftHours + "小时" : hours + "小时";
        return FORMAT.get().format(new Date(dueAt)) + " 截止，还剩 " + left;
    }
}
