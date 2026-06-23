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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final String HOOK_VERSION = "1.3";
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";
    private static final String MODULE_PACKAGE = "dev.chaoxingdeadline";
    private static final long AUTO_REFRESH_MIN_GAP_MS = 3L * 60L * 1000L;
    private static final long OVERLAY_REALTIME_WAIT_MS = 2000L;
    private static final String HOME_BUTTON_TAG = "chaoxing_deadline_home_button";
    private static final int DEFAULT_OVERLAY_WINDOW_HOURS = 24;
    private static final int OVERLAY_WINDOW_ALL_HOURS = -1;
    private static final int[] OVERLAY_WINDOW_OPTIONS = {3, 8, 12, 24, 72, OVERLAY_WINDOW_ALL_HOURS};
    private static final String COURSE_SCAN_PREFS = CourseScanScores.PREFS;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final ThreadLocal<Boolean> PARSING = ThreadLocal.withInitial(() -> false);
    private static final ArrayList<DeadlineItem> PENDING = new ArrayList<>();
    private static final Set<String> FETCHED_URLS = new HashSet<>();
    private static final Set<String> COURSE_FIELD_SIGNATURES = new HashSet<>();
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
    private static volatile boolean homePanelShowing;
    private static volatile boolean overlayScheduled;
    private static volatile boolean overlayRetryAfterDialog;
    private static volatile int overlayRefreshGeneration;
    private static volatile int overlayRealtimeShownGeneration = -1;
    private static volatile long overlayRealtimeShownAt;
    private static volatile Boolean overlayEnabledOverride;
    private static volatile int overlayWindowHoursOverride = Integer.MIN_VALUE;
    private static volatile int lastCourseScanThreads;
    private static volatile int lastCourseScanRefs;
    private static volatile int lastCourseScanScanned;
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
                                ensureHomeButton(activity);
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

    private void ensureHomeButton(Activity activity) {
        if (!isActivityUsable(activity)) {
            return;
        }
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            FrameLayout content = decor instanceof FrameLayout
                    ? (FrameLayout) decor
                    : activity.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(HOME_BUTTON_TAG) != null) {
                return;
            }
            TextView button = new TextView(activity);
            button.setTag(HOME_BUTTON_TAG);
            button.setText("Deadline");
            button.setTextSize(12);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(Color.WHITE);
            button.setGravity(android.view.Gravity.CENTER);
            button.setAlpha(0.94f);
            button.setClickable(true);
            button.setFocusable(false);
            button.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
            button.setBackground(UiTheme.fillOnly(activity, UiTheme.accent(activity), dp(activity, 999)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                button.setElevation(dp(activity, 24));
            }
            button.setOnClickListener(v -> showHomePanel(activity));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.setMargins(dp(activity, 12), dp(activity, 32), 0, 0);
            content.addView(button, params);
            log(Log.INFO, TAG, "home panel button attached");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "attach home panel button failed: " + throwable);
        }
    }

    private void showHomePanel(Activity activity) {
        if (!isActivityUsable(activity) || homePanelShowing) {
            return;
        }
        boolean shown = false;
        try {
            homePanelShowing = true;
            List<OverlayTodo> todos = homePanelTodos();
            long updatedAt = overlayDataUpdatedAt();
            final AlertDialog[] holder = new AlertDialog[1];
            View content = homePanelView(activity, todos, updatedAt,
                    () -> safeDismiss(holder[0]),
                    () -> switchHomePanel(holder[0], () -> showHomeSettingsPanel(activity)));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .show();
            holder[0] = dialog;
            dialog.setOnDismissListener(d -> homePanelShowing = false);
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(width, -2);
            }
            shown = true;
            log(Log.INFO, TAG, "home panel shown: todos=" + todos.size());
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "show home panel failed: " + throwable);
        } finally {
            if (!shown) {
                homePanelShowing = false;
            }
        }
    }

    private void showHomeSettingsPanel(Activity activity) {
        if (!isActivityUsable(activity) || homePanelShowing) {
            return;
        }
        boolean shown = false;
        try {
            homePanelShowing = true;
            final AlertDialog[] holder = new AlertDialog[1];
            View content = homeSettingsView(activity,
                    () -> switchHomePanel(holder[0], () -> showHomePanel(activity)),
                    () -> safeDismiss(holder[0]),
                    () -> {
                        openModuleSettings(activity);
                        safeDismiss(holder[0]);
                    },
                    () -> {
                        setOverlayEnabledFromPanel(activity, !overlayEnabled());
                        switchHomePanel(holder[0], () -> showHomeSettingsPanel(activity));
                    },
                    hours -> {
                        setOverlayWindowFromPanel(activity, hours);
                        switchHomePanel(holder[0], () -> showHomeSettingsPanel(activity));
                    });
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .show();
            holder[0] = dialog;
            dialog.setOnDismissListener(d -> homePanelShowing = false);
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(width, -2);
            }
            shown = true;
            log(Log.INFO, TAG, "home settings panel shown");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "show home settings panel failed: " + throwable);
        } finally {
            if (!shown) {
                homePanelShowing = false;
            }
        }
    }

    private interface OverlayWindowAction {
        void set(int hours);
    }

    private List<OverlayTodo> homePanelTodos() {
        ArrayList<OverlayTodo> todos = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            SharedPreferences prefs = getRemotePreferences(OverlayBridge.PREFS);
            String payload = prefs.getString(OverlayBridge.KEY_ITEMS, "[]");
            JSONArray array = new JSONArray(payload == null ? "[]" : payload);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null || json.optBoolean("submitted", false)) {
                    continue;
                }
                addPanelTodo(todos, new OverlayTodo(
                        json.optString("type", "事项"),
                        json.optString("title", "未命名"),
                        json.optString("course", ""),
                        json.optLong("dueAt", 0L)), now);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "read home panel prefs failed: " + throwable);
        }
        synchronized (RECENT_ITEMS) {
            for (DeadlineItem item : RECENT_ITEMS) {
                if (item == null || item.submitted || item.submissionState == DeadlineItem.SUBMISSION_UNKNOWN) {
                    continue;
                }
                addPanelTodo(todos, new OverlayTodo(item.type, item.title, item.course, item.dueAt), now);
            }
        }
        todos.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
        return todos;
    }

    private void addPanelTodo(List<OverlayTodo> todos, OverlayTodo todo, long now) {
        if (todo == null || todo.dueAt <= now) {
            return;
        }
        if (!"作业".equals(todo.type) && !"考试".equals(todo.type)) {
            return;
        }
        for (int i = 0; i < todos.size(); i++) {
            OverlayTodo old = todos.get(i);
            if (isSameOverlayTodo(old, todo)) {
                todos.set(i, betterOverlayTodo(old, todo));
                return;
            }
        }
        todos.add(todo);
    }

    private long overlayDataUpdatedAt() {
        try {
            return getRemotePreferences(OverlayBridge.PREFS).getLong(OverlayBridge.KEY_UPDATED_AT, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private String homePanelScanStatus() {
        int refs = lastCourseScanRefs;
        if (refs > 0) {
            return "线程：" + Math.max(1, lastCourseScanThreads)
                    + "｜扫描：" + Math.min(Math.max(0, lastCourseScanScanned), refs)
                    + "/" + refs + " 门";
        }
        try {
            return CourseScanThreads.summary(courseScanPrefs());
        } catch (Throwable ignored) {
            return "线程：" + CourseScanThreads.DEFAULT + "｜暂无扫描记录";
        }
    }

    private View homePanelView(Activity activity, List<OverlayTodo> todos, long updatedAt,
                               Runnable closeAction, Runnable settingsAction) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        root.setBackground(UiTheme.rounded(activity, UiTheme.sheet(activity), dp(activity, 22), UiTheme.stroke(activity)));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("Deadline");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        titles.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(activity);
        String updated = updatedAt > 0L ? " · 更新于 " + DateText.deadlineTime(updatedAt) : " · 暂无刷新时间";
        subtitle.setText("共 " + todos.size() + " 个未完成待办" + updated);
        subtitle.setTextSize(12);
        subtitle.setTextColor(UiTheme.muted(activity));
        subtitle.setPadding(0, dp(activity, 3), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView scanStatus = new TextView(activity);
        scanStatus.setText(homePanelScanStatus());
        scanStatus.setTextSize(12);
        scanStatus.setTextColor(UiTheme.muted(activity));
        scanStatus.setPadding(0, dp(activity, 3), 0, 0);
        titles.addView(scanStatus, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView settings = smallPanelButton(activity, "设置", false);
        settings.setOnClickListener(v -> {
            if (settingsAction != null) {
                settingsAction.run();
            }
        });
        header.addView(settings, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(activity, 14), 0, 0);
        if (todos.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("暂无未完成待办\n打开学习通后会自动刷新");
            empty.setTextSize(14);
            empty.setTextColor(UiTheme.muted(activity));
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setLineSpacing(0f, 1.2f);
            empty.setPadding(0, dp(activity, 28), 0, dp(activity, 28));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (OverlayTodo todo : todos) {
                list.addView(overlayRow(activity, todo));
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        int maxHeight = Math.min(dp(activity, 460), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.62f));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        TextView close = smallPanelButton(activity, "关闭", true);
        close.setOnClickListener(v -> {
            if (closeAction != null) {
                closeAction.run();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.setMargins(0, dp(activity, 8), 0, 0);
        root.addView(close, closeParams);
        return root;
    }

    private View homeSettingsView(Activity activity, Runnable backAction, Runnable closeAction,
                                  Runnable fullSettingsAction, Runnable toggleOverlayAction,
                                  OverlayWindowAction windowAction) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        root.setBackground(UiTheme.rounded(activity, UiTheme.sheet(activity), dp(activity, 22), UiTheme.stroke(activity)));

        TextView title = new TextView(activity);
        title.setText("设置");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        Space topSpace = new Space(activity);
        root.addView(topSpace, new LinearLayout.LayoutParams(1, dp(activity, 12)));

        root.addView(settingsRow(activity,
                "学习通内弹窗",
                "控制进入学习通首页时是否显示待办弹窗",
                overlayEnabled() ? "已开启" : "已关闭",
                true,
                toggleOverlayAction));

        TextView section = new TextView(activity);
        section.setText("弹窗范围");
        section.setTextSize(13);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(UiTheme.muted(activity));
        section.setPadding(0, dp(activity, 12), 0, dp(activity, 6));
        root.addView(section, new LinearLayout.LayoutParams(-1, -2));

        int currentWindow = overlayWindowHours();
        for (int option : OVERLAY_WINDOW_OPTIONS) {
            String label = overlayWindowLabel(option);
            boolean selected = option == currentWindow;
            root.addView(settingsRow(activity,
                    label,
                    selected ? "当前使用" : "点击切换",
                    selected ? "✓" : "",
                    selected,
                    () -> {
                        if (windowAction != null) {
                            windowAction.set(option);
                        }
                    }));
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(activity, 14), 0, 0);
        TextView back = smallPanelButton(activity, "返回待办", false);
        back.setOnClickListener(v -> {
            if (backAction != null) {
                backAction.run();
            }
        });
        actions.addView(back, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView full = smallPanelButton(activity, "完整设置", false);
        full.setOnClickListener(v -> {
            if (fullSettingsAction != null) {
                fullSettingsAction.run();
            }
        });
        LinearLayout.LayoutParams fullParams = new LinearLayout.LayoutParams(0, -2, 1f);
        fullParams.setMargins(dp(activity, 8), 0, 0, 0);
        actions.addView(full, fullParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        TextView close = smallPanelButton(activity, "关闭", true);
        close.setOnClickListener(v -> {
            if (closeAction != null) {
                closeAction.run();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.setMargins(0, dp(activity, 8), 0, 0);
        root.addView(close, closeParams);
        return root;
    }

    private View settingsRow(Activity activity, String title, String subtitle, String value,
                             boolean highlighted, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11));
        row.setClickable(true);
        row.setBackground(UiTheme.rounded(activity,
                highlighted ? UiTheme.card(activity) : UiTheme.sheet(activity),
                dp(activity, 14), UiTheme.stroke(activity)));
        if (action != null) {
            row.setOnClickListener(v -> action.run());
        }

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(UiTheme.text(activity));
        texts.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        TextView subView = new TextView(activity);
        subView.setText(subtitle);
        subView.setTextSize(12);
        subView.setTextColor(UiTheme.muted(activity));
        subView.setPadding(0, dp(activity, 3), 0, 0);
        texts.addView(subView, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView valueView = new TextView(activity);
        valueView.setText(value);
        valueView.setTextSize(13);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setTextColor(highlighted ? UiTheme.accent(activity) : UiTheme.muted(activity));
        valueView.setPadding(dp(activity, 10), 0, 0, 0);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(activity, 8));
        row.setLayoutParams(params);
        return row;
    }

    private TextView smallPanelButton(Activity activity, String text, boolean primary) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackground(UiTheme.fillOnly(activity, UiTheme.accent(activity), dp(activity, 12)));
        } else {
            button.setTextColor(UiTheme.accent(activity));
            button.setBackground(UiTheme.rounded(activity, UiTheme.card(activity), dp(activity, 12), UiTheme.stroke(activity)));
        }
        return button;
    }

    private void setOverlayEnabledFromPanel(Context context, boolean enabled) {
        overlayEnabledOverride = enabled;
        Intent intent = new Intent(DeadlineReceiver.ACTION_SETTINGS_UPDATE);
        intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
        intent.putExtra("overlay_enabled", enabled);
        sendAuthenticatedBroadcast(context, intent, "update overlay enabled");
    }

    private void setOverlayWindowFromPanel(Context context, int hours) {
        if (!isOverlayWindowOption(hours)) {
            return;
        }
        overlayWindowHoursOverride = hours;
        Intent intent = new Intent(DeadlineReceiver.ACTION_SETTINGS_UPDATE);
        intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
        intent.putExtra("overlay_window_hours", hours);
        sendAuthenticatedBroadcast(context, intent, "update overlay window");
    }

    private void sendAuthenticatedBroadcast(Context context, Intent intent, String label) {
        try {
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
            log(Log.INFO, TAG, label + " sent");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, label + " failed: " + throwable);
        }
    }

    private void openModuleSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".MainActivity"));
            intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable first) {
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(MODULE_PACKAGE);
                if (fallback != null) {
                    fallback.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(fallback);
                }
            } catch (Throwable second) {
                log(Log.WARN, TAG, "open module settings failed: " + second);
            }
        }
    }

    private void maybeShowOverlay(Activity activity) {
        maybeShowOverlay(activity, true);
    }

    private void maybeShowOverlay(Activity activity, boolean requireHomeActivity) {
        if (!isActivityUsable(activity)) {
            return;
        }
        if (overlayDialogShowing || overlayScheduled) {
            return;
        }
        if (requireHomeActivity && !isLikelyHomeActivity(activity)) {
            log(Log.INFO, TAG, "overlay skipped: not on home activity");
            return;
        }
        long scheduledAt = System.currentTimeMillis();
        overlayScheduled = true;
        log(Log.INFO, TAG, "overlay waits for realtime data before cache fallback");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (overlayRealtimeShownAt >= scheduledAt) {
                    log(Log.INFO, TAG, "overlay cache fallback skipped: realtime shown");
                    return;
                }
                showOverlay(activity, requireHomeActivity, false);
            } finally {
                overlayScheduled = false;
            }
        }, OVERLAY_REALTIME_WAIT_MS);
    }

    private boolean showOverlay(Activity activity, boolean requireHomeActivity, boolean realtime) {
        boolean shown = false;
        try {
            if (!isActivityUsable(activity) || (requireHomeActivity && !isLikelyHomeActivity(activity))) {
                return false;
            }
            if (overlayDialogShowing) {
                if (realtime) {
                    overlayRetryAfterDialog = true;
                }
                return false;
            }
            if (!overlayEnabled()) {
                log(Log.INFO, TAG, "overlay skipped: disabled");
                return false;
            }
            List<OverlayTodo> todos = overlayTodos(realtime);
            log(Log.INFO, TAG, (realtime ? "realtime" : "cache") + " overlay todos=" + todos.size());
            if (todos.isEmpty()) {
                return false;
            }
            String fingerprint = overlayFingerprint(todos);
            long now = System.currentTimeMillis();
            if (fingerprint.equals(lastOverlayFingerprint)) {
                log(Log.INFO, TAG, "overlay skipped: unchanged");
                return false;
            }
            overlayDialogShowing = true;
            lastOverlayAt = now;
            lastOverlayFingerprint = fingerprint;
            final AlertDialog[] holder = new AlertDialog[1];
            View content = overlayView(activity, todos, realtime, () -> safeDismiss(holder[0]));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .show();
            holder[0] = dialog;
            shown = true;
            if (realtime) {
                overlayRealtimeShownGeneration = overlayRefreshGeneration;
                overlayRealtimeShownAt = System.currentTimeMillis();
            }
            dialog.setOnDismissListener(d -> {
                overlayDialogShowing = false;
                if (overlayRetryAfterDialog) {
                    overlayRetryAfterDialog = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> showOverlay(activity, false, true), 250L);
                }
            });
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(width, -2);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "show overlay failed: " + throwable);
        } finally {
            if (!shown) {
                overlayDialogShowing = false;
            }
        }
        return shown;
    }

    private static boolean isActivityUsable(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    private static void switchHomePanel(AlertDialog dialog, Runnable next) {
        try {
            if (dialog != null) {
                dialog.setOnDismissListener(null);
            }
        } catch (Throwable ignored) {
            // Some host windows disappear while Chaoxing switches pages.
        }
        safeDismiss(dialog);
        homePanelShowing = false;
        if (next != null) {
            next.run();
        }
    }

    private static void safeDismiss(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
            // Host Activity windows can disappear while Chaoxing switches pages.
        }
    }

    private void maybeShowOverlayAfterRefresh() {
        WeakReference<Activity> ref = currentActivityRef;
        Activity activity = ref == null ? null : ref.get();
        if (!isActivityUsable(activity)) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayDialogShowing) {
                overlayRetryAfterDialog = true;
                return;
            }
            showOverlay(activity, false, true);
        });
    }

    private boolean overlayEnabled() {
        Boolean override = overlayEnabledOverride;
        if (override != null) {
            return override;
        }
        try {
            return getRemotePreferences("app_settings").getBoolean("overlay_enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private List<OverlayTodo> overlayTodos(boolean includeRecentItems) {
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
        if (includeRecentItems) {
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
        if (todo == null || !isInOverlayWindow(todo.dueAt, now)) {
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

    private boolean isInOverlayWindow(long dueAt, long now) {
        if (dueAt <= now) {
            return false;
        }
        int hours = overlayWindowHours();
        if (hours == OVERLAY_WINDOW_ALL_HOURS) {
            return true;
        }
        return dueAt - now <= TimeUnit.HOURS.toMillis(hours);
    }

    private int overlayWindowHours() {
        int override = overlayWindowHoursOverride;
        if (isOverlayWindowOption(override)) {
            return override;
        }
        try {
            int value = getRemotePreferences("app_settings").getInt("overlay_window_hours", DEFAULT_OVERLAY_WINDOW_HOURS);
            return isOverlayWindowOption(value) ? value : DEFAULT_OVERLAY_WINDOW_HOURS;
        } catch (Throwable ignored) {
            return DEFAULT_OVERLAY_WINDOW_HOURS;
        }
    }

    private boolean isOverlayWindowOption(int value) {
        return value == 3 || value == 8 || value == 12 || value == 24 || value == 72
                || value == OVERLAY_WINDOW_ALL_HOURS;
    }

    private String overlayWindowLabel() {
        return overlayWindowLabel(overlayWindowHours());
    }

    private String overlayWindowLabel(int hours) {
        if (hours == OVERLAY_WINDOW_ALL_HOURS) {
            return "所有未完成";
        }
        if (hours == 72) {
            return "3天内";
        }
        return hours + "小时内";
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

    private View overlayView(Activity activity, List<OverlayTodo> todos, boolean realtime, Runnable closeAction) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        root.setBackground(UiTheme.rounded(activity, UiTheme.sheet(activity), dp(activity, 22), UiTheme.stroke(activity)));

        TextView title = new TextView(activity);
        title.setText(overlayWindowHours() == OVERLAY_WINDOW_ALL_HOURS ? "待办提醒" : "\u4e34\u8fd1\u622a\u6b62\u5f85\u529e");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView count = new TextView(activity);
        count.setText(overlayWindowLabel() + "\u5171 " + todos.size() + " \u4e2a\u5f85\u529e");
        count.setTextSize(13);
        count.setTextColor(UiTheme.muted(activity));
        count.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        root.addView(count, new LinearLayout.LayoutParams(-1, -2));

        if (!realtime) {
            TextView notice = new TextView(activity);
            notice.setText("本次结果仅依据上次刷新得出的判断，无法判断最新待办，仅供参考");
            notice.setTextSize(12);
            notice.setTextColor(UiTheme.accent(activity));
            notice.setLineSpacing(0f, 1.12f);
            notice.setPadding(0, 0, 0, dp(activity, 14));
            root.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        }

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

        TextView close = new TextView(activity);
        close.setText("知道了");
        close.setTextSize(15);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(android.view.Gravity.CENTER);
        close.setTextColor(Color.WHITE);
        close.setBackground(UiTheme.fillOnly(activity, UiTheme.accent(activity), dp(activity, 12)));
        close.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
        close.setOnClickListener(v -> {
            if (closeAction != null) {
                closeAction.run();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.setMargins(0, dp(activity, 8), 0, 0);
        root.addView(close, closeParams);
        return root;
    }

    private View overlayRow(Activity activity, OverlayTodo todo) {
        long delta = todo.dueAt - System.currentTimeMillis();
        boolean urgent = delta <= 12L * 60L * 60L * 1000L;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        int rowBg = urgent ? UiTheme.dangerBg(activity) : UiTheme.card(activity);
        int rowStroke = urgent ? overlayDangerStroke(activity) : UiTheme.stroke(activity);
        row.setBackground(UiTheme.rounded(activity, rowBg, dp(activity, 14), rowStroke));

        TextView title = new TextView(activity);
        title.setText(todo.title == null || todo.title.isEmpty() ? "\u672a\u547d\u540d" : todo.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        title.setLineSpacing(0f, 1.08f);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        if (todo.course != null && !todo.course.isEmpty()) {
            TextView course = new TextView(activity);
            course.setText(todo.course);
            course.setTextSize(13);
            course.setTextColor(UiTheme.muted(activity));
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
        boolean exam = "\u8003\u8bd5".equals(todo.type);
        badge.setTextColor(exam ? UiTheme.badgeExam(activity) : UiTheme.badgeHomework(activity));
        badge.setBackground(UiTheme.fillOnly(activity,
                exam ? UiTheme.badgeExamBg(activity) : UiTheme.badgeHomeworkBg(activity), dp(activity, 999)));
        badge.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
        meta.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView due = new TextView(activity);
        due.setText(DateText.dueLine(todo.dueAt));
        due.setTextSize(13);
        due.setTypeface(Typeface.DEFAULT_BOLD);
        due.setTextColor(urgent ? overlayDangerText(activity) : UiTheme.muted(activity));
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

    private int overlayDangerStroke(Context context) {
        return UiTheme.dark(context) ? Color.rgb(127, 55, 55) : Color.rgb(255, 190, 200);
    }

    private int overlayDangerText(Context context) {
        return UiTheme.dark(context) ? Color.rgb(248, 113, 113) : Color.rgb(194, 65, 72);
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
            if (isInOverlayWindow(item.dueAt, System.currentTimeMillis()) && (overlayDialogShowing || overlayScheduled)) {
                overlayRetryAfterDialog = true;
            }
            if (!activeRefreshRunning) {
                maybeShowOverlay(activity);
            }
        }
    }

    private int inspect(String text, String source) {
        return inspect(text, ParseContext.fromSource(source, ""));
    }

    private int inspect(String text, ParseContext context) {
        ParseContext ctx = context == null ? ParseContext.simple("") : context;
        if (Boolean.TRUE.equals(PARSING.get())) {
            return 0;
        }
        collectAndFetchCourseTasks(text, ctx);
        PARSING.set(true);
        try {
            List<DeadlineItem> items = DeadlineParser.parsePayload(text, ctx);
            if (items.isEmpty()) {
                return 0;
            }
            log(Log.INFO, TAG, String.format(Locale.ROOT, "hook v" + HOOK_VERSION + " found %d deadline items from %s", items.size(), ctx.source));
            for (DeadlineItem item : items) {
                enrichCourseName(item);
                rememberRecentItem(item);
                log(Log.INFO, TAG, "emit parsed item from " + ctx.source);
                emit(item);
            }
            emitStatus("captured " + items.size(), ctx.source);
            return items.size();
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "parse failed: " + throwable);
            return 0;
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
            log(Log.INFO, TAG, source + ": matched deadline-related url");
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
            overlayRefreshGeneration++;
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

    private int fetchUrl(String url, String source) {
        return fetchUrl(url, ParseContext.fromSource(source, url));
    }

    private int fetchUrl(String url, ParseContext context) {
        ParseContext ctx = context == null ? ParseContext.fromSource("", url) : context.withUrl(url);
        String source = ctx.source;
        boolean guardedUrl = source != null && source.startsWith("active.courseList");
        if (guardedUrl && System.currentTimeMillis() < antiSpiderUntil) {
            log(Log.WARN, TAG, "skip active fetch while antispider cooldown is active");
            return -1;
        }
        synchronized (FETCHED_URLS) {
            if (!FETCHED_URLS.add(url)) {
                return 0;
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
            log(Log.INFO, TAG, "active fetched " + code + " " + source + " len=" + body.length());
            if (body.contains("invalid_verify") || body.contains("请输入验证码")) {
                antiSpiderUntil = System.currentTimeMillis() + 30L * 60L * 1000L;
                log(Log.WARN, TAG, "chapter fetch paused because Chaoxing requested verification");
                emitStatus("章节接口触发验证码，已暂停一会儿", source);
                return -1;
            }
            return inspect(body, ctx);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "fetch failed " + source + ": " + throwable.getClass().getSimpleName());
            return -1;
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
                logCourseFieldSummary(ref);
                rememberCourse(ref);
            }
            log(Log.INFO, TAG, "course refs cached from " + source + ": " + refs.size());
            fetchCourseDeadlines(refs);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "collect course refs failed: " + throwable);
        }
    }

    private void fetchCourseDeadlines(List<CourseRef> refs) throws InterruptedException {
        List<CourseRef> plan = courseScanPlan(refs);
        if (plan.isEmpty()) {
            log(Log.INFO, TAG, "course deadline scan skipped: no eligible courses");
            return;
        }
        long startedAt = System.currentTimeMillis();
        String cookieUid = uidFromCookies();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger foundItems = new AtomicInteger();
        List<CourseScanResult> results = Collections.synchronizedList(new ArrayList<>());
        int threadCount = courseFetchThreads();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, "ChaoxingDeadlineCourseScan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (CourseRef ref : plan) {
                int index = scheduled.incrementAndGet();
                executor.execute(() -> {
                    try {
                        Thread.sleep((long) (index % threadCount) * 40L);
                        int found = fetchCourseDeadline(ref, cookieUid);
                        if (found >= 0) {
                            results.add(new CourseScanResult(ref, found > 0));
                            foundItems.addAndGet(found);
                            scanned.incrementAndGet();
                        }
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
        boolean completed = executor.awaitTermination(90L, TimeUnit.SECONDS);
        if (!completed) {
            executor.shutdownNow();
            log(Log.WARN, TAG, "course deadline scan timeout: " + scanned.get() + "/" + scheduled.get()
                    + ", score update skipped");
        } else {
            emitCourseScanBatch(results);
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        lastCourseScanThreads = threadCount;
        lastCourseScanRefs = refs.size();
        lastCourseScanScanned = scanned.get();
        emitCourseScanPerformance(threadCount, elapsed, refs.size(), scheduled.get(), scanned.get());
        log(Log.INFO, TAG, "course deadline scan complete: " + scanned.get() + "/" + scheduled.get()
                + " courses, refs=" + refs.size() + ", items=" + foundItems.get()
                + ", elapsed=" + elapsed + "ms, threads=" + threadCount);
    }

    private int fetchCourseDeadline(CourseRef ref, String cookieUid) {
        String refUid = ref.uid.isEmpty() ? cookieUid : ref.uid;
        String uid = refUid.isEmpty() ? "" : "&uid=" + urlEncode(refUid);
        String cpi = ref.cpi.isEmpty() ? "" : "&cpi=" + urlEncode(ref.cpi);
        int failures = 0;
        int found = 0;
        int result = fetchUrl("https://mooc1-api.chaoxing.com/work/task-list?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + cpi,
                parseContextForCourse("active.workList", ref, refUid));
        if (result < 0) {
            failures++;
        } else {
            found += result;
        }
        result = fetchUrl("https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + uid,
                parseContextForCourse("active.taskList", ref, refUid));
        if (result < 0) {
            failures++;
        } else {
            found += result;
        }
        result = fetchUrl("https://mooc1-api.chaoxing.com/mooc-ans/exam/phone/task-list?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + cpi,
                parseContextForCourse("active.examList", ref, refUid));
        if (result < 0) {
            failures++;
        } else {
            found += result;
        }
        if (failures > 0 && found == 0) {
            return -1;
        }
        return found;
    }

    private List<CourseRef> courseScanPlan(List<CourseRef> refs) {
        long startedAt = System.currentTimeMillis();
        LinkedHashMap<String, CourseRef> unique = new LinkedHashMap<>();
        if (refs != null) {
            for (CourseRef ref : refs) {
                if (ref == null || ref.courseId.isEmpty() || ref.classId.isEmpty()) {
                    continue;
                }
                unique.putIfAbsent(courseKey(ref), ref);
            }
        }
        ArrayList<CourseRef> candidates = new ArrayList<>(unique.values());
        SharedPreferences prefs = courseScanPrefs();
        if (prefs == null) {
            return candidates;
        }
        long now = System.currentTimeMillis();
        ArrayList<CourseRef> result = new ArrayList<>();
        int skipped = 0;
        for (CourseRef ref : candidates) {
            if (shouldScanCourse(ref, prefs, now)) {
                result.add(ref);
            } else {
                skipped++;
            }
        }
        result.sort(Comparator
                .comparingInt((CourseRef ref) -> CourseScanScores.score(prefs, courseKey(ref))).reversed()
                .thenComparingLong(ref -> CourseScanScores.lastScanAt(prefs, courseKey(ref)))
                .thenComparing(ref -> safe(ref.courseName)));
        long elapsed = System.currentTimeMillis() - startedAt;
        log(Log.INFO, TAG, "course scan plan: selected=" + result.size()
                + ", skipped=" + skipped + ", refs=" + (refs == null ? 0 : refs.size())
                + ", elapsed=" + elapsed + "ms");
        return result;
    }

    private boolean shouldScanCourse(CourseRef ref, SharedPreferences prefs, long now) {
        return CourseScanScores.shouldScan(prefs, courseKey(ref), now);
    }

    private int courseFetchThreads() {
        return CourseScanThreads.current(courseScanPrefs());
    }

    private void emitCourseScanPerformance(int threads, long elapsedMs, int refs, int scheduled, int scanned) {
        Context context = ensureHostContext();
        if (context == null || scheduled <= 0 || elapsedMs <= 0L) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE_SCAN_PERF);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("threads", threads);
            intent.putExtra("elapsed_ms", elapsedMs);
            intent.putExtra("refs", refs);
            intent.putExtra("scheduled", scheduled);
            intent.putExtra("scanned", scanned);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "course scan perf emit failed: " + throwable.getClass().getSimpleName());
        }
    }

    private void emitCourseScanBatch(List<CourseScanResult> results) {
        Context context = ensureHostContext();
        if (context == null || results == null || results.isEmpty()) {
            return;
        }
        try {
            ArrayList<String> courseIds = new ArrayList<>();
            ArrayList<String> classIds = new ArrayList<>();
            ArrayList<String> courseNames = new ArrayList<>();
            boolean[] foundDeadlines = new boolean[results.size()];
            int count = 0;
            synchronized (results) {
                for (CourseScanResult result : results) {
                    if (result == null || result.ref == null) {
                        continue;
                    }
                    courseIds.add(result.ref.courseId);
                    classIds.add(result.ref.classId);
                    courseNames.add(result.ref.courseName);
                    foundDeadlines[count] = result.foundDeadline;
                    count++;
                }
            }
            if (count <= 0) {
                return;
            }
            if (count != foundDeadlines.length) {
                boolean[] compact = new boolean[count];
                System.arraycopy(foundDeadlines, 0, compact, 0, count);
                foundDeadlines = compact;
            }
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE_SCAN_BATCH);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("course_ids", courseIds.toArray(new String[0]));
            intent.putExtra("class_ids", classIds.toArray(new String[0]));
            intent.putExtra("course_names", courseNames.toArray(new String[0]));
            intent.putExtra("found_deadlines", foundDeadlines);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
            log(Log.INFO, TAG, "course scan score batch emitted: " + count + " courses");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "course scan batch emit failed: " + throwable.getClass().getSimpleName());
        }
    }

    private SharedPreferences courseScanPrefs() {
        try {
            return getRemotePreferences(COURSE_SCAN_PREFS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String courseKey(CourseRef ref) {
        if (ref == null) {
            return "";
        }
        return safe(ref.courseId) + "|" + safe(ref.classId);
    }

    private void logCourseFieldSummary(CourseRef ref) {
        if (ref == null || ref.raw.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(ref.raw);
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext() && keys.size() < 80) {
                keys.add(iterator.next());
            }
            Collections.sort(keys);
            String signature = keys.toString();
            synchronized (COURSE_FIELD_SIGNATURES) {
                if (COURSE_FIELD_SIGNATURES.size() >= 8 || COURSE_FIELD_SIGNATURES.contains(signature)) {
                    return;
                }
                COURSE_FIELD_SIGNATURES.add(signature);
            }
            ArrayList<String> timeLike = new ArrayList<>();
            for (String key : keys) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("time") || lower.contains("date") || lower.contains("start")
                        || lower.contains("begin") || lower.contains("end") || lower.contains("create")
                        || lower.contains("update")) {
                    timeLike.add(key);
                }
            }
            log(Log.INFO, TAG, "course raw fields: keys=" + signature + ", timeLike=" + timeLike);
        } catch (Throwable ignored) {
        }
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
            log(Log.WARN, TAG, "host context missing, queued deadline item");
            return;
        }
        log(Log.INFO, TAG, "emit deadline item type=" + item.type);
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

    private void attachBridgeToken(Intent intent) {
        try {
            SharedPreferences prefs = getRemotePreferences(BridgeAuth.PREFS_NAME);
            String token = prefs.getString(BridgeAuth.KEY_TOKEN, "");
            if (token != null && !token.isEmpty()) {
                intent.putExtra(BridgeAuth.EXTRA_TOKEN, token);
            } else {
                log(Log.WARN, TAG, "bridge token missing; open module app once to initialize bridge");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "bridge token unavailable: " + throwable.getClass().getSimpleName());
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

    private static final class CourseScanResult {
        final CourseRef ref;
        final boolean foundDeadline;

        CourseScanResult(CourseRef ref, boolean foundDeadline) {
            this.ref = ref;
            this.foundDeadline = foundDeadline;
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
