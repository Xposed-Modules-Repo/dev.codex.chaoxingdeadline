package dev.codex.chaoxingdeadline;

import android.app.Application;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
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
    private static final String BUILD_TAG = "build-20260618-2045";
    // Experimental chapter scanner: scans chapter-level task structures for extra deadlines.
    // Intentionally disabled (kept, not deleted). While false, the chapter helpers
    // (collectAndFetchChapterCards / collectChapterRefs / ChapterRef and the chapter-source
    // branches in DeadlineParser) stay dormant on purpose so the feature can be re-enabled
    // without rewriting it. When false, only the standard work list / exam list APIs are used.
    private static final boolean CHAPTER_TASKS_ENABLED = false;
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";
    private static final String MODULE_PACKAGE = "dev.codex.chaoxingdeadline";
    private static final ThreadLocal<Boolean> PARSING = ThreadLocal.withInitial(() -> false);
    private static final ArrayList<DeadlineItem> PENDING = new ArrayList<>();
    private static final Set<String> FETCHED_URLS = new HashSet<>();
    private static final Map<String, String> COURSE_NAMES = new HashMap<>();
    private static final ArrayList<DeadlineItem> RECENT_ITEMS = new ArrayList<>();
    private static volatile Context hostContext;
    private static volatile Activity currentActivity;
    private static volatile boolean commandListenerInstalled;
    private static volatile long lastRefreshSeq;
    private static volatile long antiSpiderUntil;
    private static volatile int scanCursor;
    private static volatile long lastOverlayAt;
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener commandListener;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, BUILD_TAG + " loaded in " + param.getProcessName() + ", framework "
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
        installCommandListener();
        emitStatus("hooks installed", "onPackageReady");
        activeRefresh("onPackageReady");
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
            Class<?> responseBody = Class.forName("okhttp3.ResponseBody", false, loader);
            hook(responseBody.getDeclaredMethod("string"))
                    .setId("okhttp_response_body_string")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof String) {
                            inspect((String) result, "OkHttp.string");
                        }
                        return result;
                    });
            hook(responseBody.getDeclaredMethod("bytes"))
                    .setId("okhttp_response_body_bytes")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof byte[]) {
                            inspectBytes((byte[]) result, "OkHttp.bytes");
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "OkHttp hooks installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "OkHttp hook skipped: " + throwable);
        }
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
                            currentActivity = (Activity) receiver;
                            maybeShowOverlay((Activity) receiver);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "Activity overlay hook installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Activity overlay hook skipped: " + throwable);
        }
    }

    private void maybeShowOverlay(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOverlayAt < 45L * 1000L) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!overlayEnabled()) {
                    log(Log.INFO, TAG, "overlay skipped: disabled");
                    return;
                }
                String text = recentSummary();
                log(Log.INFO, TAG, "overlay summary length=" + text.length());
                if (text.isEmpty()) {
                    return;
                }
                lastOverlayAt = System.currentTimeMillis();
                new AlertDialog.Builder(activity)
                        .setTitle("\u672a\u5b8c\u6210\u5f85\u529e")
                        .setMessage(text)
                        .setPositiveButton("\u77e5\u9053\u4e86", null)
                        .show();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "show overlay failed: " + throwable);
            }
        }, 1200L);
    }

    private boolean overlayEnabled() {
        try {
            return getRemotePreferences("app_settings").getBoolean("overlay_enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private String recentSummary() {
        StringBuilder builder = new StringBuilder();
        long now = System.currentTimeMillis();
        int count = 0;
        synchronized (RECENT_ITEMS) {
            for (DeadlineItem item : RECENT_ITEMS) {
                if (item == null || item.dueAt <= now) {
                    continue;
                }
                if (!"\u4f5c\u4e1a".equals(item.type) && !"\u8003\u8bd5".equals(item.type)) {
                    continue;
                }
                if (count > 0) {
                    builder.append("\n\n");
                }
                builder.append(item.type).append("  ").append(item.title).append("\n");
                if (item.course != null && !item.course.isEmpty()) {
                    builder.append(item.course).append("\n");
                }
                builder.append(DateText.dueLine(item.dueAt));
                count++;
                if (count >= 6) {
                    break;
                }
            }
        }
        return builder.toString();
    }

    private void rememberRecentItem(DeadlineItem item) {
        if (item == null || item.id == null || item.dueAt <= System.currentTimeMillis()) {
            return;
        }
        if (!"\u4f5c\u4e1a".equals(item.type) && !"\u8003\u8bd5".equals(item.type)) {
            return;
        }
        synchronized (RECENT_ITEMS) {
            for (int i = RECENT_ITEMS.size() - 1; i >= 0; i--) {
                DeadlineItem old = RECENT_ITEMS.get(i);
                if (old == null || item.id.equals(old.id) || old.dueAt <= System.currentTimeMillis()) {
                    RECENT_ITEMS.remove(i);
                }
            }
            RECENT_ITEMS.add(0, item);
            while (RECENT_ITEMS.size() > 12) {
                RECENT_ITEMS.remove(RECENT_ITEMS.size() - 1);
            }
        }
        Activity activity = currentActivity;
        if (activity != null) {
            maybeShowOverlay(activity);
        }
    }

    private void inspect(String text, String source) {
        if (Boolean.TRUE.equals(PARSING.get())) {
            return;
        }
        collectAndFetchCourseTasks(text, source);
        if (CHAPTER_TASKS_ENABLED) {
            collectAndFetchChapterCards(text, source);
        }
        PARSING.set(true);
        try {
        List<DeadlineItem> items = DeadlineParser.parsePayload(text, source);
        if (items.isEmpty()) {
            return;
        }
        log(Log.INFO, TAG, String.format(Locale.ROOT, BUILD_TAG + " found %d deadline items from %s", items.size(), source));
        for (DeadlineItem item : items) {
                enrichCourseName(item);
                rememberRecentItem(item);
                log(Log.INFO, TAG, BUILD_TAG + " about to emit item from " + source);
                emit(item);
            }
            emitStatus("captured " + items.size(), source);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "parse failed: " + throwable);
        } finally {
            PARSING.set(false);
        }
    }

    private void enrichCourseName(DeadlineItem item) {
        if (item == null || (item.course != null && !item.course.trim().isEmpty())) {
            return;
        }
        String sourceCourse = courseNameFromSource(item.source);
        if (!sourceCourse.isEmpty()) {
            item.course = sourceCourse;
            return;
        }
        String raw = item.raw == null ? "" : item.raw;
        String courseId = firstRegex(raw, "(?:courseId|courseid)[\"']?\\s*[:=]\\s*[\"']?([0-9]+)[\"']?");
        String classId = firstRegex(raw, "(?:classId|classid|clazzId|clazzid)[\"']?\\s*[:=]\\s*[\"']?([0-9]+)[\"']?");
        synchronized (COURSE_NAMES) {
            String name = "";
            if (!courseId.isEmpty() && !classId.isEmpty()) {
                name = COURSE_NAMES.get(courseId + "|" + classId);
            }
            if ((name == null || name.isEmpty()) && !courseId.isEmpty()) {
                name = COURSE_NAMES.get(courseId);
            }
            if (name != null && !name.isEmpty()) {
                item.course = name;
            }
        }
    }

    private String courseNameFromSource(String source) {
        if (source == null) {
            return "";
        }
        int bar = source.indexOf('|');
        return bar >= 0 && bar + 1 < source.length() ? source.substring(bar + 1).trim() : "";
    }

    private String firstRegex(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void inspectBytes(byte[] bytes, String source) {
        if (bytes == null || bytes.length < 8 || bytes.length > 2_000_000) {
            return;
        }
        inspect(new String(bytes, StandardCharsets.UTF_8), source);
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
        inspect(url, source);
    }

    private void installCommandListener() {
        if (commandListenerInstalled) {
            return;
        }
        try {
            SharedPreferences prefs = getRemotePreferences("commands");
            lastRefreshSeq = prefs.getLong("refresh_seq", 0L);
            commandListener = (sharedPreferences, key) -> {
                if (!"refresh_seq".equals(key)) {
                    return;
                }
                long seq = sharedPreferences.getLong("refresh_seq", 0L);
                if (seq > 0L && seq != lastRefreshSeq) {
                    lastRefreshSeq = seq;
                    activeRefresh("command");
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(commandListener);
            commandListenerInstalled = true;
            log(Log.INFO, TAG, "command listener installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "command listener failed: " + throwable);
        }
    }

    private void activeRefresh(String reason) {
        emitStatus("主动刷新中", reason);
        Thread worker = new Thread(() -> {
            try {
                synchronized (FETCHED_URLS) {
                    FETCHED_URLS.clear();
                }
                fetchUrl("https://mooc1-api.chaoxing.com/work/stu-work", "active.workPage");
                fetchUrl("https://mooc1-api.chaoxing.com/exam-ans/exam/phone/examcode", "active.examPage");
                fetchUrl("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1", "active.courseList");
                emitStatus("主动刷新完成", reason);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "active refresh failed: " + throwable);
                emitStatus("主动刷新失败：" + throwable.getClass().getSimpleName(), reason);
            }
        }, "ChaoxingDeadlineRefresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void fetchUrl(String url, String source) {
        boolean guardedUrl = source != null && (source.startsWith("active.chapter") || source.startsWith("active.courseList"));
        if (!CHAPTER_TASKS_ENABLED && source != null && source.startsWith("active.chapter")) {
            return;
        }
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
            inspect(body, source);
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

    private void collectAndFetchCourseTasks(String text, String source) {
        if (!source.startsWith("active.courseList")) {
            return;
        }
        try {
            String trimmed = text.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            ArrayList<CourseRef> refs = new ArrayList<>();
            collectCourseRefs(root, refs, 0);
            log(Log.INFO, TAG, "course refs from " + source + ": " + refs.size());
            String cookieUid = uidFromCookies();
            int total = refs.size();
            if (total == 0) {
                return;
            }
            int limit = Math.min(8, total);
            int start = Math.floorMod(scanCursor, total);
            scanCursor = (start + limit) % total;
            for (int offset = 0; offset < limit; offset++) {
                CourseRef ref = refs.get((start + offset) % total);
                if (ref.courseId.isEmpty() || ref.classId.isEmpty()) {
                    continue;
                }
                if (offset > 0) {
                    Thread.sleep(1200L);
                }
                rememberCourse(ref);
                String refUid = ref.uid.isEmpty() ? cookieUid : ref.uid;
                String uid = refUid.isEmpty() ? "" : "&uid=" + refUid;
                String cpi = ref.cpi.isEmpty() ? "" : "&cpi=" + ref.cpi;
                String courseSource = sourceForCourse("active.workList", ref);
                fetchUrl("https://mooc1-api.chaoxing.com/work/task-list?courseId="
                        + ref.courseId + "&classId=" + ref.classId + cpi, courseSource);
                fetchUrl("https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId="
                        + ref.courseId + "&classId=" + ref.classId + uid, sourceForCourse("active.taskList", ref));
                fetchUrl("https://mooc1-api.chaoxing.com/mooc-ans/exam/phone/task-list?courseId="
                        + ref.courseId + "&classId=" + ref.classId + cpi, sourceForCourse("active.examList", ref));
                if (CHAPTER_TASKS_ENABLED) {
                    String personId = ref.cpi.isEmpty() ? refUid : ref.cpi;
                    String fields = "id,name,course.fields(id,name,knowledge.fields(id,name,indexOrder,parentnodeid,status,isReview,layer,label,jobcount,begintime,endtime,jobUnfinishedCount))";
                    fetchUrl("https://mooc1-api.chaoxing.com/gas/clazz?id=" + ref.classId
                            + "&personid=" + personId + "&fields=" + urlEncode(fields) + "&view=json",
                            sourceForCourse("active.chapterList", ref));
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "collect course refs failed: " + throwable);
        }
    }

    private void collectAndFetchChapterCards(String text, String source) {
        if (source == null || !source.startsWith("active.chapterList") || source.startsWith("active.chapterCard")) {
            return;
        }
        try {
            String trimmed = text.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            ArrayList<ChapterRef> refs = new ArrayList<>();
            collectChapterRefs(root, refs, "", "", 0);
            log(Log.INFO, TAG, "chapter card refs from " + source + ": " + refs.size());
            int fetched = 0;
            for (ChapterRef ref : refs) {
                if (ref.courseId.isEmpty() || ref.knowledgeId.isEmpty()) {
                    continue;
                }
                if (fetched >= 5) {
                    break;
                }
                fetched++;
                String fields = "id,parentnodeid,indexorder,label,layer,name,begintime,createtime,lastmodifytime,status,jobUnfinishedCount,clickcount,openlock,card.fields(id,knowledgeid,title,knowledgeTitile,description,cardorder).contentcard(all)";
                fetchUrl("https://mooc1-api.chaoxing.com/gas/knowledge?id=" + ref.knowledgeId
                        + "&courseid=" + ref.courseId
                        + "&fields=" + urlEncode(fields)
                        + "&view=json&token=4faa8662c59590c6f43ae9fe5b002b42&_time=" + System.currentTimeMillis(),
                        sourceForChapterCard(source, ref));
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "collect chapter card refs failed: " + throwable);
        }
    }

    private void collectChapterRefs(Object node, List<ChapterRef> out, String courseId, String courseName, int depth) throws Exception {
        if (node == null || depth > 14 || out.size() > 120) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String nextCourseId = courseId;
            String nextCourseName = courseName;
            if (object.has("course") && object.opt("course") instanceof JSONObject) {
                JSONObject course = object.optJSONObject("course");
                JSONObject courseData = firstDataObject(course);
                if (courseData != null) {
                    String id = firstString(courseData, "id", "courseid", "courseId");
                    String name = firstString(courseData, "name", "courseName", "coursename");
                    if (!id.isEmpty()) {
                        nextCourseId = id;
                    }
                    if (!name.isEmpty()) {
                        nextCourseName = name;
                    }
                }
            }
            if (isKnowledgeTaskNode(object)) {
                String knowledgeId = firstString(object, "id", "knowledgeId", "chapterId");
                if (!knowledgeId.isEmpty() && !nextCourseId.isEmpty()) {
                    out.add(new ChapterRef(nextCourseId, knowledgeId, nextCourseName, firstString(object, "name", "title")));
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object child = object.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectChapterRefs(child, out, nextCourseId, nextCourseName, depth + 1);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectChapterRefs(child, out, courseId, courseName, depth + 1);
                }
            }
        }
    }

    private boolean isKnowledgeTaskNode(JSONObject object) {
        int jobCount = object.optInt("jobcount", 0);
        int unfinished = object.optInt("jobUnfinishedCount", 0);
        if (jobCount <= 0 && unfinished <= 0) {
            return false;
        }
        return object.has("label") || object.has("parentnodeid") || object.has("layer") || object.has("indexorder");
    }

    private JSONObject firstDataObject(JSONObject wrapper) {
        if (wrapper == null) {
            return null;
        }
        JSONArray data = wrapper.optJSONArray("data");
        if (data != null && data.length() > 0) {
            return data.optJSONObject(0);
        }
        return wrapper;
    }

    private String sourceForChapterCard(String source, ChapterRef ref) {
        String course = ref.courseName.isEmpty() ? courseNameFromSource(source) : ref.courseName;
        String title = ref.chapterTitle.isEmpty() ? ref.knowledgeId : ref.chapterTitle;
        return "active.chapterCard|" + course + "|" + title;
    }

    private void rememberCourse(CourseRef ref) {
        if (ref.courseName.isEmpty()) {
            return;
        }
        synchronized (COURSE_NAMES) {
            COURSE_NAMES.put(ref.courseId + "|" + ref.classId, ref.courseName);
            COURSE_NAMES.put(ref.courseId, ref.courseName);
        }
        emitCourse(ref.courseName);
    }

    private void emitCourse(String courseName) {
        Context context = ensureHostContext();
        if (context == null || courseName == null || courseName.trim().isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("course", courseName.trim());
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "emit course failed: " + throwable.getClass().getSimpleName());
        }
    }

    private String sourceForCourse(String prefix, CourseRef ref) {
        if (ref.courseName.isEmpty()) {
            return prefix;
        }
        return prefix + "|" + ref.courseName;
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

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable ignored) {
            return value;
        }
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
                out.add(new CourseRef(courseId, classId, uid, cpi, courseName));
            }
            java.util.Iterator<String> keys = object.keys();
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
        return new CourseRef(courseId, classId, uid, cpi, courseName);
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
            log(Log.WARN, TAG, "bridge token unavailable: " + throwable);
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
                log(Log.INFO, TAG, BUILD_TAG + " host context recovered from currentApplication");
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
                    log(Log.INFO, TAG, BUILD_TAG + " host context recovered from ActivityThread");
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

        CourseRef(String courseId, String classId, String uid, String cpi, String courseName) {
            this.courseId = courseId;
            this.classId = classId;
            this.uid = uid;
            this.cpi = cpi;
            this.courseName = courseName == null ? "" : courseName;
        }
    }

    private static final class ChapterRef {
        final String courseId;
        final String knowledgeId;
        final String courseName;
        final String chapterTitle;

        ChapterRef(String courseId, String knowledgeId, String courseName, String chapterTitle) {
            this.courseId = courseId == null ? "" : courseId;
            this.knowledgeId = knowledgeId == null ? "" : knowledgeId;
            this.courseName = courseName == null ? "" : courseName;
            this.chapterTitle = chapterTitle == null ? "" : chapterTitle;
        }
    }
}
