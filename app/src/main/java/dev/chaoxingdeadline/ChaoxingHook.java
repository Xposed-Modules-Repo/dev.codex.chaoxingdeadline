package dev.chaoxingdeadline;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LSPosed hook module for Chaoxing (学习通).
 *
 * Architecture:
 * - Passive hooks (JSON/OkHttp/WebView) intercept Chaoxing app traffic to extract deadline data.
 * - Active refresh (fetchUrl) proactively pulls course/work/exam lists via Chaoxing HTTP APIs.
 * - Items flow: inspect() -> DeadlineParser -> enrichCourseName() -> emit() -> DeadlineReceiver -> DeadlineStore.
 * - COURSE_NAMES: in-memory cache populated from active course list fetch, used to resolve course names from IDs.
 */
import io.github.libxposed.api.XposedModule;

public final class ChaoxingHook extends XposedModule {
    private static final String TAG = "ChaoxingDeadline";
    private static final String HOOK_VERSION = "1.2";
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";
    private static final String MODULE_PACKAGE = "dev.chaoxingdeadline";
    private static final long AUTO_REFRESH_MIN_GAP_MS = 3L * 60L * 1000L;
    private static final int COURSE_FETCH_THREADS = 3;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final ThreadLocal<Boolean> PARSING = ThreadLocal.withInitial(() -> false);
    private static final ArrayList<DeadlineItem> PENDING = new ArrayList<>();
    private static final Set<String> FETCHED_URLS = new HashSet<>();
    private static final Map<String, String> COURSE_NAMES = new HashMap<>();
    private static final Map<Object, ParseContext> RESPONSE_CONTEXTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ArrayList<DeadlineItem> RECENT_ITEMS = new ArrayList<>();
    @SuppressLint("StaticFieldLeak")
    private static volatile Context hostContext;
    private static volatile WeakReference<Activity> currentActivityRef;
    private static volatile long antiSpiderUntil;
    private static volatile long lastOverlayAt;
    private static volatile long lastHomeSeenAt;
    private static volatile long lastAutoRefreshAt;
    private static volatile String lastOverlayFingerprint;
    private static volatile int startedActivityCount;
    private static volatile boolean lifecycleCallbacksRegistered;
    private static volatile boolean overlayDialogShowing;
    private static volatile boolean overlayScheduled;
    private static volatile boolean overlayRetryAfterDialog;
    private static volatile boolean activeRefreshRunning;
    private static volatile boolean foregroundRefreshStarted;


    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "hook v" + HOOK_VERSION + " loaded in " + param.getProcessName() + ", framework "
                + getFrameworkName() + " API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (TARGET_PACKAGE.equals(param.getPackageName())) {
            installApplicationHook();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "install hooks for " + param.getPackageName());
        installJsonHooks();
        installOkHttpHooks(param.getClassLoader());
        installWebViewHooks();
        installActivityOverlayHook();
        emitStatus("hooks installed", "onPackageReady");
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        param.setSavedInstanceState("reload");
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        param.getOldHookHandles().forEach(HookHandle::unhook);
    }

    private void installApplicationHook() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach)
                    .setId("application_attach")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            hostContext = ((Context) arg).getApplicationContext();
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof Application) {
                                registerForegroundCallbacks((Application) receiver);
                            }
                            flushPending();
                            emitStatus("active", "Application.attach");
                            log(Log.INFO, TAG, "host context ready");
                        }
                        return result;
                    });
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "failed to hook Application.attach", throwable);
        }
    }

    private void registerForegroundCallbacks(Application application) {
        synchronized (LIFECYCLE_LOCK) {
            if (lifecycleCallbacksRegistered) {
                return;
            }
            lifecycleCallbacksRegistered = true;
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean enteredForeground;
                synchronized (LIFECYCLE_LOCK) {
                    enteredForeground = startedActivityCount == 0;
                    startedActivityCount++;
                }
                currentActivityRef = new WeakReference<>(activity);
                if (enteredForeground) {
                    foregroundRefreshStarted = false;
                }
                maybeStartForegroundRefresh(activity);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                synchronized (LIFECYCLE_LOCK) {
                    if (startedActivityCount > 0) {
                        startedActivityCount--;
                    }
                    if (startedActivityCount == 0) {
                        foregroundRefreshStarted = false;
                    }
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {
                currentActivityRef = new WeakReference<>(activity);
                maybeStartForegroundRefresh(activity);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
        log(Log.INFO, TAG, "foreground lifecycle callbacks installed");
    }

    private void maybeStartForegroundRefresh(Activity activity) {
        if (!isRefreshEligibleActivity(activity)) {
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            if (foregroundRefreshStarted) {
                return;
            }
            foregroundRefreshStarted = true;
        }
        log(Log.INFO, TAG, "Chaoxing entered foreground; start automatic refresh");
        requestActiveRefresh("foregroundSession", false);
    }

    private void installJsonHooks() {
        hookStringConstructor("org.json.JSONObject", "JSONObject");
        hookStringConstructor("org.json.JSONArray", "JSONArray");
        hookStringConstructor("org.json.JSONTokener", "JSONTokener");
    }

    private void hookStringConstructor(String className, String source) {
        try {
            Constructor<?> constructor = Class.forName(className).getDeclaredConstructor(String.class);
            hook(constructor)
                    .setId(source + "_string_ctor")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof String) {
                            inspect((String) arg, source);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, source + " hook installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, source + " hook skipped: " + throwable);
        }
    }

    private void installOkHttpHooks(ClassLoader loader) {
        try {
            Class<?> response = Class.forName("okhttp3.Response", false, loader);
            try {
                hook(response.getDeclaredMethod("body"))
                        .setId("okhttp_response_body_context")
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object receiver = chain.getThisObject();
                            rememberResponseContext(receiver, result);
                            return result;
                        });
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "OkHttp Response.body hook skipped: " + throwable);
            }
            Class<?> responseBuilder = Class.forName("okhttp3.Response$Builder", false, loader);
            try {
                hook(responseBuilder.getDeclaredMethod("build"))
                        .setId("okhttp_response_builder_build_context")
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            rememberResponseContext(result, null);
                            return result;
                        });
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "OkHttp Response.Builder hook skipped: " + throwable);
            }
            Class<?> responseBody = Class.forName("okhttp3.ResponseBody", false, loader);
            hook(responseBody.getDeclaredMethod("string"))
                    .setId("okhttp_response_body_string")
                    .intercept(chain -> {
                        Object body = chain.getThisObject();
                        Object result = chain.proceed();
                        if (result instanceof String) {
                            inspect((String) result, contextForBody(body, "OkHttp.string"));
                        }
                        return result;
                    });
            hook(responseBody.getDeclaredMethod("bytes"))
                    .setId("okhttp_response_body_bytes")
                    .intercept(chain -> {
                        Object body = chain.getThisObject();
                        Object result = chain.proceed();
                        if (result instanceof byte[]) {
                            inspectBytes((byte[]) result, contextForBody(body, "OkHttp.bytes"));
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "OkHttp hooks installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "OkHttp hook skipped: " + throwable);
        }
    }

    private void rememberResponseContext(Object response, Object knownBody) {
        if (response == null) {
            return;
        }
        try {
            Object request = response.getClass().getMethod("request").invoke(response);
            Object urlObject = request == null ? null : request.getClass().getMethod("url").invoke(request);
            String url = urlObject == null ? "" : urlObject.toString();
            Object body = knownBody;
            if (body == null) {
                body = response.getClass().getMethod("body").invoke(response);
            }
            if (body != null && !url.isEmpty()) {
                RESPONSE_CONTEXTS.put(body, contextFromUrl("OkHttp", url));
            }
        } catch (Throwable ignored) {
        }
    }

    private ParseContext contextForBody(Object body, String fallbackSource) {
        ParseContext context = body == null ? null : RESPONSE_CONTEXTS.get(body);
        return context == null ? ParseContext.simple(fallbackSource) : context;
    }

    private ParseContext contextFromUrl(String prefix, String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String source = prefix;
        if (lower.contains("work") || lower.contains("homework")) {
            source = prefix + ".work";
        } else if (lower.contains("exam") || lower.contains("ks_list")) {
            source = prefix + ".exam";
        } else if (lower.contains("backclazzdata") || lower.contains("mycourse") || lower.contains("clazz")) {
            source = prefix + ".course";
        } else if (lower.contains("knowledge") || lower.contains("chapter")) {
            source = prefix + ".chapter";
        }
        String courseId = queryParam(url, "courseId", "courseid", "courseIdStr", "courseidStr");
        String classId = queryParam(url, "classId", "classid", "clazzId", "clazzid", "id");
        String cpi = queryParam(url, "cpi", "cpiId");
        String uid = queryParam(url, "uid", "puid", "personid", "personId");
        String courseName = "";
        synchronized (COURSE_NAMES) {
            if (!courseId.isEmpty() && !classId.isEmpty()) {
                courseName = COURSE_NAMES.get(courseId + "|" + classId);
            }
            if ((courseName == null || courseName.isEmpty()) && !courseId.isEmpty()) {
                courseName = COURSE_NAMES.get(courseId);
            }
        }
        return ParseContext.forCourse(source, url, courseId, classId, cpi, uid, courseName);
    }

    private String queryParam(String url, String... names) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        for (String name : names) {
            Matcher matcher = Pattern.compile("(?i)(?:[?&]|&amp;)" + Pattern.quote(name) + "=([^&#]+)").matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private void installWebViewHooks() {
        try {
            Class<?> webView = Class.forName("android.webkit.WebView");
            hook(webView.getDeclaredMethod("loadUrl", String.class))
                    .setId("webview_load_url")
                    .intercept(chain -> {
                        Object url = chain.getArg(0);
                        if (url instanceof String) {
                            inspectUrl((String) url, "WebView.loadUrl");
                            markHomeUrl((String) url);
                        }
                        return chain.proceed();
                    });
            hook(webView.getDeclaredMethod("loadDataWithBaseURL", String.class, String.class, String.class, String.class, String.class))
                    .setId("webview_load_data_base")
                    .intercept(chain -> {
                        Object base = chain.getArg(0);
                        Object data = chain.getArg(1);
                        if (base instanceof String) {
                            inspectUrl((String) base, "WebView.loadDataWithBaseURL");
                            markHomeUrl((String) base);
                        }
                        if (data instanceof String) {
                            inspect((String) data, "WebView.loadDataWithBaseURL");
                        }
                        return chain.proceed();
                    });
            hook(webView.getDeclaredMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class))
                    .setId("webview_eval_js")
                    .intercept(chain -> {
                        Object script = chain.getArg(0);
                        if (script instanceof String) {
                            inspect((String) script, "WebView.evaluateJavascript");
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "WebView hooks installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "WebView hook skipped: " + throwable);
        }
    }

    private void installActivityOverlayHook() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume)
                    .setId("activity_on_resume_overlay")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof Activity) {
                            Activity activity = (Activity) receiver;
                            currentActivityRef = new WeakReference<>(activity);
                            if (isLikelyHomeActivity(activity)) {
                                lastHomeSeenAt = System.currentTimeMillis();
                                maybeShowOverlay(activity, true);
                            }
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "Activity overlay hook installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Activity overlay hook skipped: " + throwable);
        }
    }

    private void maybeShowOverlay(Activity activity) {
        maybeShowOverlay(activity, true);
    }

    private void maybeShowOverlay(Activity activity, boolean requireHomeActivity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (overlayDialogShowing || overlayScheduled) {
            return;
        }
        if (requireHomeActivity && !isLikelyHomeActivity(activity)) {
            log(Log.INFO, TAG, "overlay skipped: not on home activity");
            return;
        }
        if (activeRefreshRunning) {
            log(Log.INFO, TAG, "todo overlay uses cached data while active refresh continues");
        }
        overlayScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean shown = false;
            try {
                if (activity.isFinishing() || (requireHomeActivity && !isLikelyHomeActivity(activity))) {
                    return;
                }
                if (!overlayEnabled()) {
                    log(Log.INFO, TAG, "overlay skipped: disabled");
                    return;
                }
                List<OverlayTodo> todos = overlayTodos();
                log(Log.INFO, TAG, "todo overlay todos=" + todos.size());
                if (todos.isEmpty()) {
                    return;
                }
                String fingerprint = overlayFingerprint(todos);
                long now = System.currentTimeMillis();
                if (fingerprint.equals(lastOverlayFingerprint)) {
                    log(Log.INFO, TAG, "todo overlay skipped: unchanged");
                    return;
                }
                overlayDialogShowing = true;
                lastOverlayAt = now;
                lastOverlayFingerprint = fingerprint;
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setView(overlayView(activity, todos))
                        .setPositiveButton("\u77e5\u9053\u4e86", null)
                        .show();
                shown = true;
                dialog.setOnDismissListener(d -> {
                    overlayDialogShowing = false;
                    if (overlayRetryAfterDialog) {
                        overlayRetryAfterDialog = false;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> maybeShowOverlay(activity, false), 250L);
                    }
                });
                Window window = dialog.getWindow();
                if (window != null) {
                    int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
                    window.setLayout(width, -2);
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "show overlay failed: " + throwable);
            } finally {
                overlayScheduled = false;
                if (!shown) {
                    overlayDialogShowing = false;
                }
            }
        }, 800L);
    }

    private void maybeShowOverlayAfterRefresh() {
        WeakReference<Activity> ref = currentActivityRef;
        Activity activity = ref == null ? null : ref.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (overlayDialogShowing || overlayScheduled) {
                overlayRetryAfterDialog = true;
            }
            maybeShowOverlay(activity, false);
        }, 900L);
    }

    private boolean overlayEnabled() {
        try {
            return getRemotePreferences("app_settings").getBoolean("overlay_enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private List<OverlayTodo> overlayTodos() {
        ArrayList<OverlayTodo> todos = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<OverlayTodo> suppressed = new ArrayList<>();
        try {
            SharedPreferences prefs = getRemotePreferences(OverlayBridge.PREFS);
            String suppressedPayload = prefs.getString(OverlayBridge.KEY_SUPPRESSED, "[]");
            JSONArray suppressedArray = new JSONArray(suppressedPayload == null ? "[]" : suppressedPayload);
            for (int i = 0; i < suppressedArray.length(); i++) {
                JSONObject json = suppressedArray.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                suppressed.add(new OverlayTodo(
                        json.optString("type", ""),
                        json.optString("title", ""),
                        "",
                        json.optLong("dueAt", 0L)));
            }

            String payload = prefs.getString(OverlayBridge.KEY_ITEMS, "[]");
            JSONArray array = new JSONArray(payload == null ? "[]" : payload);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                if (json.optBoolean("submitted", false)) {
                    continue;
                }
                long dueAt = json.optLong("dueAt", 0L);
                String type = json.optString("type", "\u4e8b\u9879");
                OverlayTodo todo = new OverlayTodo(
                        type,
                        json.optString("title", "\u672a\u547d\u540d"),
                        json.optString("course", ""),
                        dueAt);
                if (!isSuppressedOverlayTodo(todo, suppressed)) {
                    addOverlayTodo(todos, todo, now);
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "read overlay prefs failed: " + throwable);
        }
        synchronized (RECENT_ITEMS) {
            for (int i = RECENT_ITEMS.size() - 1; i >= 0; i--) {
                DeadlineItem item = RECENT_ITEMS.get(i);
                if (item == null || item.submitted) {
                    RECENT_ITEMS.remove(i);
                    continue;
                }
                OverlayTodo todo = new OverlayTodo(item.type, item.title, item.course, item.dueAt);
                if (isSuppressedOverlayTodo(todo, suppressed)) {
                    RECENT_ITEMS.remove(i);
                    continue;
                }
                addOverlayTodo(todos, todo, now);
            }
        }
        todos.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
        return todos;
    }

    private boolean isSuppressedOverlayTodo(OverlayTodo todo, List<OverlayTodo> suppressed) {
        if (todo == null || suppressed == null || suppressed.isEmpty()) {
            return false;
        }
        for (OverlayTodo old : suppressed) {
            if (isSameOverlayTodo(old, todo)) {
                return true;
            }
        }
        return false;
    }

    private void addOverlayTodo(List<OverlayTodo> todos, OverlayTodo todo, long now) {
        if (todo == null || !isUrgentDue(todo.dueAt, now)) {
            return;
        }
        if (!"\u4f5c\u4e1a".equals(todo.type) && !"\u8003\u8bd5".equals(todo.type)) {
            return;
        }
        for (int i = 0; i < todos.size(); i++) {
            OverlayTodo old = todos.get(i);
            if (!isSameOverlayTodo(old, todo)) {
                continue;
            }
            todos.set(i, betterOverlayTodo(old, todo));
            return;
        }
        todos.add(todo);
    }

    private boolean isSameOverlayTodo(OverlayTodo a, OverlayTodo b) {
        if (a == null || b == null) {
            return false;
        }
        if (!safe(a.type).equals(safe(b.type))) {
            return false;
        }
        String titleA = normalizeOverlayText(a.title);
        String titleB = normalizeOverlayText(b.title);
        if (titleA.isEmpty() || !titleA.equals(titleB)) {
            return false;
        }
        return Math.abs(a.dueAt - b.dueAt) <= 5L * 60L * 1000L;
    }

    private OverlayTodo betterOverlayTodo(OverlayTodo a, OverlayTodo b) {
        String courseA = safe(a.course);
        String courseB = safe(b.course);
        String titleA = safe(a.title);
        String titleB = safe(b.title);
        String course = courseA.length() >= courseB.length() ? courseA : courseB;
        String title = titleA.length() >= titleB.length() ? titleA : titleB;
        long dueAt = Math.min(a.dueAt, b.dueAt);
        return new OverlayTodo(safe(a.type).isEmpty() ? b.type : a.type, title, course, dueAt);
    }

    private boolean isUrgentDue(long dueAt, long now) {
        return dueAt > now;
    }

    private String overlayFingerprint(List<OverlayTodo> todos) {
        StringBuilder builder = new StringBuilder();
        for (OverlayTodo todo : todos) {
            if (todo == null) {
                continue;
            }
            builder.append(overlayIdentity(todo)).append('|')
                    .append(todo.dueAt / (5L * 60L * 1000L)).append('\n');
        }
        return builder.toString();
    }

    private String overlayIdentity(OverlayTodo todo) {
        if (todo == null) {
            return "";
        }
        return safe(todo.type) + "|" + normalizeOverlayText(todo.title);
    }

    private String normalizeOverlayText(String value) {
        return safe(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private View overlayView(Activity activity, List<OverlayTodo> todos) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 6));

        TextView title = new TextView(activity);
        title.setText("\u5b66\u4e60\u901a\u5f85\u529e");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(248, 250, 252));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView count = new TextView(activity);
        count.setText("\u5171 " + todos.size() + " \u4e2a\u672a\u5b8c\u6210\u5f85\u529e");
        count.setTextSize(13);
        count.setTextColor(Color.rgb(148, 163, 184));
        count.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        root.addView(count, new LinearLayout.LayoutParams(-1, -2));

        TextView notice = new TextView(activity);
        notice.setText("本次结果仅依据上次刷新得出的判断，无法判断最新待办，仅供参考");
        notice.setTextSize(12);
        notice.setTextColor(Color.rgb(96, 165, 250));
        notice.setLineSpacing(0f, 1.12f);
        notice.setPadding(0, 0, 0, dp(activity, 14));
        root.addView(notice, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (OverlayTodo todo : todos) {
            list.addView(overlayRow(activity, todo));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        int maxHeight = Math.min(dp(activity, 380), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.52f));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));
        return root;
    }

    private View overlayRow(Activity activity, OverlayTodo todo) {
        long delta = todo.dueAt - System.currentTimeMillis();
        boolean urgent = delta <= 12L * 60L * 60L * 1000L;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(urgent ? Color.rgb(64, 42, 42) : Color.rgb(38, 43, 55));
        bg.setCornerRadius(dp(activity, 14));
        bg.setStroke(dp(activity, 1), urgent ? Color.rgb(127, 55, 55) : Color.rgb(51, 65, 85));
        row.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText(todo.title == null || todo.title.isEmpty() ? "\u672a\u547d\u540d" : todo.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(248, 250, 252));
        title.setLineSpacing(0f, 1.08f);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        if (todo.course != null && !todo.course.isEmpty()) {
            TextView course = new TextView(activity);
            course.setText(todo.course);
            course.setTextSize(13);
            course.setTextColor(Color.rgb(148, 163, 184));
            course.setPadding(0, dp(activity, 5), 0, 0);
            row.addView(course, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dp(activity, 10), 0, 0);

        TextView badge = new TextView(activity);
        badge.setText(todo.type);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor("\u8003\u8bd5".equals(todo.type) ? Color.rgb(251, 146, 60) : Color.rgb(96, 165, 250));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor("\u8003\u8bd5".equals(todo.type) ? Color.argb(40, 251, 146, 60) : Color.argb(40, 96, 165, 250));
        badgeBg.setCornerRadius(dp(activity, 999));
        badge.setBackground(badgeBg);
        badge.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
        meta.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView due = new TextView(activity);
        due.setText(DateText.dueLine(todo.dueAt));
        due.setTextSize(13);
        due.setTypeface(Typeface.DEFAULT_BOLD);
        due.setTextColor(urgent ? Color.rgb(248, 113, 113) : Color.rgb(203, 213, 225));
        due.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        due.setLineSpacing(0f, 1.05f);
        LinearLayout.LayoutParams dueParams = new LinearLayout.LayoutParams(0, -2, 1f);
        dueParams.setMargins(dp(activity, 12), 0, 0, 0);
        meta.addView(due, dueParams);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(activity, 10));
        row.setLayoutParams(params);
        return row;
    }

    private boolean isRefreshEligibleActivity(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        return !(name.contains("splash") || name.contains("ad") || name.contains("advert")
                || name.contains("welcome") || name.contains("guide") || name.contains("permission"));
    }

    private boolean isLikelyHomeActivity(Activity activity) {
        if (!isRefreshEligibleActivity(activity)) {
            return false;
        }
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("splash") || name.contains("ad") || name.contains("advert")
                || name.contains("welcome") || name.contains("guide") || name.contains("permission")) {
            return false;
        }
        return name.contains("main") || name.contains("home") || name.contains("tab");
    }

    private void markHomeUrl(String url) {
        if (url == null) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if ((lower.contains("chaoxing") || lower.contains("fanya"))
                && (lower.contains("main") || lower.contains("home") || lower.contains("course"))) {
            lastHomeSeenAt = System.currentTimeMillis();
        }
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class OverlayTodo {
        final String type;
        final String title;
        final String course;
        final long dueAt;

        OverlayTodo(String type, String title, String course, long dueAt) {
            this.type = type;
            this.title = title;
            this.course = course;
            this.dueAt = dueAt;
        }
    }

    private void rememberRecentItem(DeadlineItem item) {
        if (item == null || item.id == null || item.dueAt <= System.currentTimeMillis()) {
            return;
        }
        if (!"\u4f5c\u4e1a".equals(item.type) && !"\u8003\u8bd5".equals(item.type)) {
            return;
        }
        if (item.submitted || item.submissionState == DeadlineItem.SUBMISSION_UNKNOWN) {
            return;
        }
        OverlayTodo current = new OverlayTodo(item.type, item.title, item.course, item.dueAt);
        synchronized (RECENT_ITEMS) {
            long now = System.currentTimeMillis();
            for (int i = RECENT_ITEMS.size() - 1; i >= 0; i--) {
                DeadlineItem old = RECENT_ITEMS.get(i);
                if (old == null || item.id.equals(old.id) || old.dueAt <= now
                        || isSameOverlayTodo(new OverlayTodo(old.type, old.title, old.course, old.dueAt), current)) {
                    RECENT_ITEMS.remove(i);
                }
            }
            RECENT_ITEMS.add(0, item);
            while (RECENT_ITEMS.size() > 12) {
                RECENT_ITEMS.remove(RECENT_ITEMS.size() - 1);
            }
        }
        WeakReference<Activity> ref = currentActivityRef;
        Activity activity = ref == null ? null : ref.get();
        if (activity != null) {
            if (isUrgentDue(item.dueAt, System.currentTimeMillis()) && (overlayDialogShowing || overlayScheduled)) {
                overlayRetryAfterDialog = true;
            }
            maybeShowOverlay(activity);
        }
    }

    private void inspect(String text, String source) {
        inspect(text, ParseContext.fromSource(source, ""));
    }

    private void inspect(String text, ParseContext context) {
        ParseContext ctx = context == null ? ParseContext.simple("") : context;
        if (Boolean.TRUE.equals(PARSING.get())) {
            return;
        }
        collectAndFetchCourseTasks(text, ctx);
        PARSING.set(true);
        try {
            List<DeadlineItem> items = DeadlineParser.parsePayload(text, ctx);
            if (items.isEmpty()) {
                return;
            }
            log(Log.INFO, TAG, String.format(Locale.ROOT, "hook v" + HOOK_VERSION + " found %d deadline items from %s", items.size(), ctx.source));
            for (DeadlineItem item : items) {
                enrichCourseName(item);
                rememberRecentItem(item);
                log(Log.INFO, TAG, "emit parsed item from " + ctx.source);
                emit(item);
            }
            emitStatus("captured " + items.size(), ctx.source);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "parse failed: " + throwable);
        } finally {
            PARSING.set(false);
        }
    }


    private void enrichCourseName(DeadlineItem item) {
        if (item == null) {
            return;
        }
        if (item.courseId == null || item.courseId.isEmpty()) {
            item.courseId = firstRegex(item.raw == null ? "" : item.raw, "(?:courseId|courseid)[\"']?\\s*[:=]\\s*[\"']?([0-9]+)[\"']?");
        }
        if (item.classId == null || item.classId.isEmpty()) {
            item.classId = firstRegex(item.raw == null ? "" : item.raw, "(?:classId|classid|clazzId|clazzid)[\"']?\\s*[:=]\\s*[\"']?([0-9]+)[\"']?");
        }
        synchronized (COURSE_NAMES) {
            String name = "";
            if (item.courseId != null && !item.courseId.isEmpty() && item.classId != null && !item.classId.isEmpty()) {
                name = COURSE_NAMES.get(item.courseId + "|" + item.classId);
            }
            if ((name == null || name.isEmpty()) && item.courseId != null && !item.courseId.isEmpty()) {
                name = COURSE_NAMES.get(item.courseId);
            }
            if (name != null && !name.isEmpty() && (item.course == null || item.course.isEmpty() || item.courseConfidence < 90)) {
                item.course = name;
                item.courseConfidence = 90;
            }
        }
    }

    private String firstRegex(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String urlEncode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable ignored) {
            return value;
        }
    }

    private void inspectBytes(byte[] bytes, String source) {
        inspectBytes(bytes, ParseContext.simple(source));
    }

    private void inspectBytes(byte[] bytes, ParseContext context) {
        if (bytes == null || bytes.length < 8 || bytes.length > 2_000_000) {
            return;
        }
        inspect(new String(bytes, StandardCharsets.UTF_8), context);
    }

    private void inspectUrl(String url, String source) {
        if (url == null) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("course") || lower.contains("work") || lower.contains("exam")
                || lower.contains("mooc") || lower.contains("fanya") || lower.contains("chaoxing")
                || lower.contains("章节") || lower.contains("作业") || lower.contains("考试")) {
            log(Log.INFO, TAG, source + ": " + url);
            emitStatus("url", source);
        }
        inspect(url, ParseContext.fromSource(source, url));
    }

    private void requestActiveRefresh(String reason, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (LIFECYCLE_LOCK) {
            if (!force && now - lastAutoRefreshAt < AUTO_REFRESH_MIN_GAP_MS) {
                log(Log.INFO, TAG, "active refresh skipped by cooldown, remaining="
                        + (AUTO_REFRESH_MIN_GAP_MS - (now - lastAutoRefreshAt)) + "ms reason=" + reason);
                return;
            }
            if (activeRefreshRunning) {
                log(Log.INFO, TAG, "active refresh skipped because another refresh is running, reason=" + reason);
                return;
            }
            lastAutoRefreshAt = now;
            activeRefreshRunning = true;
        }
        activeRefresh(reason);
    }

    private void activeRefresh(String reason) {
        log(Log.INFO, TAG, "active refresh start, reason=" + reason);
        emitStatus("自动更新中", reason);
        Thread worker = new Thread(() -> {
            boolean completed = false;
            try {
                synchronized (FETCHED_URLS) {
                    FETCHED_URLS.clear();
                }
                fetchUrl("https://mooc1-api.chaoxing.com/work/stu-work", "active.workPage");
                fetchUrl("https://mooc1-api.chaoxing.com/exam-ans/exam/phone/examcode", "active.examPage");
                fetchUrl("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1", "active.courseList");
                completed = true;
                emitStatus("自动更新完成", reason);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "active refresh failed: " + throwable);
                emitStatus("自动更新失败：" + throwable.getClass().getSimpleName(), reason);
            } finally {
                synchronized (LIFECYCLE_LOCK) {
                    activeRefreshRunning = false;
                }
                if (completed) {
                    maybeShowOverlayAfterRefresh();
                }
            }
        }, "ChaoxingDeadlineRefresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void fetchUrl(String url, String source) {
        fetchUrl(url, ParseContext.fromSource(source, url));
    }

    private void fetchUrl(String url, ParseContext context) {
        ParseContext ctx = context == null ? ParseContext.fromSource("", url) : context.withUrl(url);
        String source = ctx.source;
        boolean guardedUrl = source != null && source.startsWith("active.courseList");
        if (guardedUrl && System.currentTimeMillis() < antiSpiderUntil) {
            log(Log.WARN, TAG, "skip active fetch while antispider cooldown is active");
            return;
        }
        synchronized (FETCHED_URLS) {
            if (!FETCHED_URLS.add(url)) {
                return;
            }
            if (FETCHED_URLS.size() > 800) {
                FETCHED_URLS.clear();
                FETCHED_URLS.add(url);
            }
        }
        HttpURLConnection connection = null;
        try {
            if (guardedUrl) {
                Thread.sleep(900L);
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; OnePlus 11) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.4_android_phone");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("Accept-Language", "zh_CN");
            connection.setRequestProperty("Connection", "Keep-Alive");
            if (url.contains("mooc1-api.chaoxing.com/gas/clazz")) {
                connection.setRequestProperty("Referer", "https://mooc1-api.chaoxing.com/");
                connection.setRequestProperty("Host", "mooc1-api.chaoxing.com");
            }
            String cookie = cookieFor(url);
            if (!cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && builder.length() < 2_000_000) {
                builder.append(line).append('\n');
            }
            String body = builder.toString();
            log(Log.INFO, TAG, "active fetched " + code + " " + source + " " + url + " len=" + body.length());
            if (body.contains("invalid_verify") || body.contains("请输入验证码")) {
                antiSpiderUntil = System.currentTimeMillis() + 30L * 60L * 1000L;
                log(Log.WARN, TAG, "chapter fetch paused because Chaoxing requested verification");
                emitStatus("章节接口触发验证码，已暂停一会儿", source);
                return;
            }
            inspect(body, ctx);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "fetch failed " + url + ": " + throwable);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String cookieFor(String url) {
        try {
            CookieManager manager = CookieManager.getInstance();
            String cookie = manager.getCookie(url);
            if (cookie == null || cookie.isEmpty()) {
                cookie = manager.getCookie("https://chaoxing.com/");
            }
            if (cookie == null || cookie.isEmpty()) {
                cookie = manager.getCookie("https://mooc1-api.chaoxing.com/");
            }
            return cookie == null ? "" : cookie;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void collectAndFetchCourseTasks(String text, ParseContext context) {
        String source = context == null ? "" : context.source;
        if (!source.startsWith("active.courseList")) {
            return;
        }
        try {
            String trimmed = text.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            ArrayList<CourseRef> refs = new ArrayList<>();
            collectCourseRefs(root, refs, 0);
            for (CourseRef ref : refs) {
                rememberCourse(ref);
            }
            log(Log.INFO, TAG, "course refs cached from " + source + ": " + refs.size());
            fetchCourseDeadlines(refs);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "collect course refs failed: " + throwable);
        }
    }

    private void fetchCourseDeadlines(List<CourseRef> refs) throws InterruptedException {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        String cookieUid = uidFromCookies();
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger scanned = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(COURSE_FETCH_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "ChaoxingDeadlineCourseScan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (CourseRef ref : refs) {
                if (ref == null || ref.courseId.isEmpty() || ref.classId.isEmpty()) {
                    continue;
                }
                int index = submitted.incrementAndGet();
                executor.execute(() -> {
                    try {
                        Thread.sleep((long) (index % COURSE_FETCH_THREADS) * 160L);
                        fetchCourseDeadline(ref, cookieUid);
                        scanned.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG, "course deadline fetch failed: " + throwable.getClass().getSimpleName());
                    }
                });
            }
        } finally {
            executor.shutdown();
        }
        if (!executor.awaitTermination(90L, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            log(Log.WARN, TAG, "course deadline scan timeout: " + scanned.get() + "/" + submitted.get());
        }
        log(Log.INFO, TAG, "course deadline scan complete: " + scanned.get() + "/" + submitted.get()
                + " courses, threads=" + COURSE_FETCH_THREADS);
    }

    private void fetchCourseDeadline(CourseRef ref, String cookieUid) {
        String refUid = ref.uid.isEmpty() ? cookieUid : ref.uid;
        String uid = refUid.isEmpty() ? "" : "&uid=" + urlEncode(refUid);
        String cpi = ref.cpi.isEmpty() ? "" : "&cpi=" + urlEncode(ref.cpi);
        fetchUrl("https://mooc1-api.chaoxing.com/work/task-list?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + cpi,
                parseContextForCourse("active.workList", ref, refUid));
        fetchUrl("https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + uid,
                parseContextForCourse("active.taskList", ref, refUid));
        fetchUrl("https://mooc1-api.chaoxing.com/mooc-ans/exam/phone/task-list?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + cpi,
                parseContextForCourse("active.examList", ref, refUid));
    }

    private void rememberCourse(CourseRef ref) {
        if (ref == null || ref.courseName.isEmpty()) {
            return;
        }
        synchronized (COURSE_NAMES) {
            COURSE_NAMES.put(ref.courseId + "|" + ref.classId, ref.courseName);
            COURSE_NAMES.put(ref.courseId, ref.courseName);
        }
        emitCourse(ref);
    }

    private void emitCourse(CourseRef ref) {
        Context context = ensureHostContext();
        if (context == null || ref == null || ref.courseName == null || ref.courseName.trim().isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("course", ref.courseName.trim());
            intent.putExtra("course_id", ref.courseId);
            intent.putExtra("class_id", ref.classId);
            intent.putExtra("cpi", ref.cpi);
            intent.putExtra("uid", ref.uid);
            intent.putExtra("raw", ref.raw);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "emit course failed: " + throwable.getClass().getSimpleName());
        }
    }

    private ParseContext parseContextForCourse(String prefix, CourseRef ref, String fallbackUid) {
        if (ref == null) {
            return ParseContext.simple(prefix);
        }
        String uid = ref.uid.isEmpty() ? fallbackUid : ref.uid;
        return ParseContext.forCourse(prefix, "", ref.courseId, ref.classId, ref.cpi, uid, ref.courseName);
    }

    private String uidFromCookies() {
        String[] urls = {
                "https://chaoxing.com/",
                "https://mooc1-api.chaoxing.com/",
                "https://mobilelearn.chaoxing.com/"
        };
        for (String url : urls) {
            String cookie = cookieFor(url);
            String uid = cookieValue(cookie, "UID");
            if (!uid.isEmpty()) {
                return uid;
            }
            uid = cookieValue(cookie, "_uid");
            if (!uid.isEmpty()) {
                return uid;
            }
            uid = cookieValue(cookie, "puid");
            if (!uid.isEmpty()) {
                return uid;
            }
        }
        return "";
    }

    private String cookieValue(String cookie, String name) {
        if (cookie == null || cookie.isEmpty()) {
            return "";
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (name.equalsIgnoreCase(trimmed.substring(0, equals).trim())) {
                return trimmed.substring(equals + 1).trim();
            }
        }
        return "";
    }

    private void collectCourseRefs(Object node, List<CourseRef> out, int depth) throws Exception {
        if (node == null || depth > 12 || out.size() > 80) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject content = object.optJSONObject("content");
            if (content != null) {
                CourseRef ref = refFromContent(content);
                if (ref != null) {
                    out.add(ref);
                }
            }
            String courseId = firstString(object, "courseid", "courseId", "courseIdStr", "courseidStr");
            String classId = firstString(object, "classid", "classId", "clazzid", "clazzId", "clazzIdStr", "clazzidStr");
            String uid = firstString(object, "uid", "puid", "personid", "personId");
            String cpi = firstString(object, "cpi", "cpiId");
            String courseName = firstString(object, "courseName", "coursename", "name", "title", "clazzName", "className");
            if (!courseId.isEmpty() && !classId.isEmpty()) {
                out.add(new CourseRef(courseId, classId, uid, cpi, courseName, object.toString()));
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object child = object.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectCourseRefs(child, out, depth + 1);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectCourseRefs(child, out, depth + 1);
                }
            }
        }
    }

    private CourseRef refFromContent(JSONObject content) {
        JSONObject course = content.optJSONObject("course");
        if (course == null) {
            return null;
        }
        JSONObject courseData = null;
        JSONArray data = course.optJSONArray("data");
        if (data != null && data.length() > 0) {
            courseData = data.optJSONObject(0);
        }
        if (courseData == null) {
            courseData = course;
        }
        String courseId = firstString(courseData, "id", "courseid", "courseId", "courseIdStr");
        String classId = firstString(content, "id", "clazzid", "clazzId", "classid", "classId");
        String uid = firstString(content, "uid", "puid", "personid", "personId");
        String cpi = firstString(content, "cpi", "cpiId");
        String courseName = firstString(courseData, "name", "courseName", "coursename", "title");
        if (courseName.isEmpty()) {
            courseName = firstString(content, "courseName", "coursename", "name", "title", "clazzName", "className");
        }
        if (courseId.isEmpty() || classId.isEmpty()) {
            return null;
        }
        return new CourseRef(courseId, classId, uid, cpi, courseName, content.toString());
    }

    private String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private void emit(DeadlineItem item) {
        Context context = ensureHostContext();
        if (context == null) {
            synchronized (PENDING) {
                if (PENDING.size() < 100) {
                    PENDING.add(item);
                }
            }
            log(Log.WARN, TAG, "host context missing, queued item: " + item.title);
            return;
        }
        log(Log.INFO, TAG, "emit deadline item: " + item.title + " @ " + item.dueAt);
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_ITEM);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(DeadlineReceiver.EXTRA_ITEM_B64, Base64.getEncoder()
                    .encodeToString(item.toJson().toString().getBytes(StandardCharsets.UTF_8)));
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "broadcast item failed: " + throwable);
        }
    }

    private void emitStatus(String status, String source) {
        Context context = ensureHostContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_STATUS);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(DeadlineReceiver.EXTRA_STATUS, status);
            intent.putExtra(DeadlineReceiver.EXTRA_SOURCE, source);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "status emit failed: " + throwable);
        }
    }

    @SuppressLint("ApplySharedPref")
    private void attachBridgeToken(Intent intent) {
        try {
            SharedPreferences prefs = getRemotePreferences(BridgeAuth.PREFS_NAME);
            String token = prefs.getString(BridgeAuth.KEY_TOKEN, "");
            if (token == null || token.isEmpty()) {
                token = BridgeAuth.newToken();
                prefs.edit().putString(BridgeAuth.KEY_TOKEN, token).commit();
            }
            if (token != null && !token.isEmpty()) {
                intent.putExtra(BridgeAuth.EXTRA_TOKEN, token);
            }
        } catch (Throwable throwable) {
            intent.putExtra(BridgeAuth.EXTRA_TOKEN, BridgeAuth.FALLBACK_TOKEN);
            log(Log.WARN, TAG, "bridge token unavailable, using fallback token: " + throwable);
        }
    }

    private Context ensureHostContext() {
        Context context = hostContext;
        if (context != null) {
            return context;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            if (app instanceof Application) {
                hostContext = ((Application) app).getApplicationContext();
                log(Log.INFO, TAG, "host context recovered from currentApplication");
                flushPending();
                return hostContext;
            }
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            Object thread = currentActivityThread.invoke(null);
            if (thread != null) {
                Field initialApplication = activityThread.getDeclaredField("mInitialApplication");
                initialApplication.setAccessible(true);
                app = initialApplication.get(thread);
                if (app instanceof Application) {
                    hostContext = ((Application) app).getApplicationContext();
                    log(Log.INFO, TAG, "host context recovered from ActivityThread");
                    flushPending();
                    return hostContext;
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "recover host context failed: " + throwable.getClass().getSimpleName());
        }
        return null;
    }

    private void flushPending() {
        ArrayList<DeadlineItem> copy;
        synchronized (PENDING) {
            copy = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (DeadlineItem item : copy) {
            emit(item);
        }
    }

    private static final class CourseRef {
        final String courseId;
        final String classId;
        final String uid;
        final String cpi;
        final String courseName;
        final String raw;

        CourseRef(String courseId, String classId, String uid, String cpi, String courseName) {
            this(courseId, classId, uid, cpi, courseName, "");
        }

        CourseRef(String courseId, String classId, String uid, String cpi, String courseName, String raw) {
            this.courseId = courseId == null ? "" : courseId;
            this.classId = classId == null ? "" : classId;
            this.uid = uid == null ? "" : uid;
            this.cpi = cpi == null ? "" : cpi;
            this.courseName = courseName == null ? "" : courseName;
            this.raw = raw == null ? "" : raw;
        }
    }

}
