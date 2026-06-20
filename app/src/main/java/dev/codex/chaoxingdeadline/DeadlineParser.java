package dev.codex.chaoxingdeadline;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

public final class DeadlineParser {
    private static final String[] TIME_KEYS = {
            "deadline", "deadLine", "dueTime", "duetime", "endTime", "endtime", "end_time",
            "endDate", "enddate", "closeTime", "finishTime", "expireTime", "stopTime",
            "submitEndTime", "submitendtime", "workEndTime", "examEndTime", "lastSubmitTime",
            "endtimeStr", "endTimeStr", "taskEndTime", "limitTime", "limittime",
            "nameFour", "namefive", "nameFive"
    };
    private static final String[] TITLE_KEYS = {
            "title", "name", "workName", "examName", "courseName", "chapterName",
            "taskName", "jobName", "knowledgeName", "activityName", "paperName",
            "nameOne", "nameTwo", "nameThree"
    };
    private static final String[] COURSE_KEYS = {
            "courseName", "clazzName", "className", "folderName", "cpiName", "coursename"
    };
    private static final String[] PARENT_COURSE_KEYS = {
            "courseName", "clazzName", "className", "folderName", "cpiName", "coursename",
            "cname", "course", "name"
    };
    private static final String[] ID_KEYS = {
            "id", "workId", "workid", "examId", "examid", "courseId", "courseid",
            "chapterId", "chapterid", "knowledgeId", "jobId", "jobid", "aid", "paperId"
    };
    private static final String[] DATE_PATTERNS = {
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm", "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy年MM月dd日 HH:mm:ss", "yyyy年MM月dd日 HH:mm", "yyyy-MM-dd"
    };

    private DeadlineParser() {
    }

    public static boolean looksRelevant(String text) {
        if (text == null || text.length() < 8 || text.length() > 2_000_000) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasScope = lower.contains("course") || lower.contains("homework") || lower.contains("work")
                || lower.contains("exam") || lower.contains("chapter") || lower.contains("job")
                || lower.contains("mooc") || lower.contains("fanya") || lower.contains("clazz")
                || lower.contains("课程") || lower.contains("作业") || lower.contains("考试")
                || lower.contains("章节") || lower.contains("任务");
        boolean hasTime = lower.contains("endtime") || lower.contains("deadline") || lower.contains("duetime")
                || lower.contains("expiretime") || lower.contains("submitendtime") || lower.contains("截止")
                || lower.contains("结束") || lower.contains("到期");
        return hasScope && hasTime;
    }

    public static List<DeadlineItem> parsePayload(String text, String source) {
        ArrayList<DeadlineItem> result = new ArrayList<>();
        if (text != null && source != null && source.startsWith("active.")) {
            result.addAll(parseHtmlList(text, source));
        }
        if (!looksRelevant(text)) {
            return result;
        }
        try {
            String trimmed = text.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            collect(root, "", source, "", result, 0);
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static List<DeadlineItem> parseHtmlList(String html, String source) {
        ArrayList<DeadlineItem> result = new ArrayList<>();
        if (html == null || html.length() < 20 || html.indexOf('<') < 0) {
            return result;
        }
        if (source.contains("workPage") || source.contains("workList") || html.contains("stu-work")) {
            parseListItems(html, source, "作业", result);
        }
        if (source.contains("examPage") || source.contains("examList")
                || html.contains("ks_list") || html.contains("examcode")) {
            parseListItems(html, source, "考试", result);
        }
        if (source.contains("chapterList") || html.contains("knowledge") || html.contains("chapter")) {
            parseListItems(html, source, "章节", result);
        }
        return result;
    }

    private static void parseListItems(String html, String source, String type, List<DeadlineItem> out) {
        Matcher matcher = Pattern.compile("(?is)<li\\b([^>]*)>(.*?)</li>").matcher(html);
        while (matcher.find()) {
            String attrs = matcher.group(1);
            String li = matcher.group(2);
            String plain = stripTags(li);
            if (plain.contains("已完成") || plain.contains("待批阅") || plain.contains("已提交")
                    || (plain.contains("已交") && !plain.contains("未交"))
                    || plain.contains("已结束") || plain.contains("已过期")) {
                continue;
            }
            long dueAt = parseDueFromText(plain);
            if (dueAt <= System.currentTimeMillis()) {
                continue;
            }
            String title = firstMatch(li, "(?is)<p[^>]*>(.*?)</p>");
            if (title.isEmpty()) {
                title = firstMatch(li, "(?is)<dt[^>]*>(.*?)</dt>");
            }
            if (title.isEmpty()) {
                title = firstMatch(li, "(?is)<h[1-6][^>]*>(.*?)</h[1-6]>");
            }
            if (title.isEmpty()) {
                title = firstMatch(li, "(?is)<a[^>]*>(.*?)</a>");
            }
            if (title.isEmpty()) {
                title = firstNonEmptyLine(plain);
            }
            String course = secondSpanText(li);
            if (badCourseText(course)) {
                course = courseNameFromSource(source);
            }
            DeadlineItem item = new DeadlineItem();
            item.type = type;
            item.title = clean(stripTags(title));
            item.course = clean(stripTags(course));
            item.dueAt = dueAt;
            item.submitted = false;
            item.source = source;
            item.raw = matcher.group(0);
            item.id = htmlStableId(type, attrs + " " + li, item.title, item.course);
            if (!item.title.isEmpty()) {
                out.add(item);
            }
        }
    }

    private static boolean badCourseText(String course) {
        String value = clean(stripTags(course));
        return value.isEmpty()
                || value.contains("剩余")
                || value.contains("还剩")
                || value.contains("未交")
                || value.contains("未提交")
                || value.contains("已交")
                || value.contains("已提交")
                || value.contains("截止");
    }

    private static String htmlStableId(String type, String raw, String title, String course) {
        String taskId = firstParam(raw, "taskrefId", "taskId", "workId", "workid", "examId", "examid", "id");
        String courseId = firstParam(raw, "courseId", "courseid", "moocId");
        String classId = firstParam(raw, "classId", "classid", "clazzId", "clazzid");
        String key = !taskId.isEmpty() ? taskId + "|" + courseId + "|" + classId
                : title + "|" + course + "|" + firstMatch(raw, "(?is)data=[\"']([^\"']+)[\"']");
        return type + "_" + Integer.toHexString(key.hashCode());
    }

    private static String firstParam(String text, String... keys) {
        for (String key : keys) {
            Matcher matcher = Pattern.compile("(?i)(?:[?&]|&amp;)" + Pattern.quote(key) + "=([^&\"'\\s>]+)").matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static long parseDueFromText(String text) {
        long absolute = parseTimeFromFreeText(text);
        if (absolute > 0) {
            return absolute;
        }
        long now = System.currentTimeMillis();
        Matcher dayMatcher = Pattern.compile("(?:(?:还剩|剩余|距截止|离截止)\\s*)?(\\d+)\\s*天").matcher(text);
        if (dayMatcher.find()) {
            long days = Long.parseLong(dayMatcher.group(1));
            long hours = firstLong(text, "(\\d+)\\s*小时");
            return now + TimeUnit.DAYS.toMillis(days) + TimeUnit.HOURS.toMillis(hours);
        }
        long hours = firstLong(text, "(?:还剩|剩余|距截止|离截止)?\\s*(\\d+)\\s*小时");
        if (hours > 0) {
            return now + TimeUnit.HOURS.toMillis(hours);
        }
        return 0L;
    }

    private static long parseTimeFromFreeText(String text) {
        Matcher matcher = Pattern.compile("(20\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}(?:日)?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)").matcher(text);
        while (matcher.find()) {
            long parsed = parseTime(matcher.group(1).replace("年", "-").replace("月", "-").replace("日", ""));
            if (parsed > System.currentTimeMillis()) {
                return parsed;
            }
        }
        return 0L;
    }

    private static long firstLong(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private static String firstMatch(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String secondSpanText(String html) {
        Matcher matcher = Pattern.compile("(?is)<span[^>]*>(.*?)</span>").matcher(html);
        int index = 0;
        while (matcher.find()) {
            index++;
            if (index == 2) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static String courseNameFromSource(String source) {
        if (source == null) {
            return "";
        }
        int bar = source.indexOf('|');
        if (bar < 0 || bar + 1 >= source.length()) {
            return "";
        }
        int second = source.indexOf('|', bar + 1);
        return (second > bar ? source.substring(bar + 1, second) : source.substring(bar + 1)).trim();
    }

    private static String firstNonEmptyLine(String text) {
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String cleaned = clean(line);
            if (!cleaned.isEmpty() && !cleaned.contains("未提交") && !cleaned.contains("进行中")) {
                return cleaned;
            }
        }
        return "";
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", "\n")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static void collect(Object node, String path, String source, String parentCourse, List<DeadlineItem> out, int depth) throws JSONException {
        if (node == null || depth > 14) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            DeadlineItem item = readItem(object, path, source);
            String currentCourse = firstString(object, PARENT_COURSE_KEYS);
            if (currentCourse.isEmpty()) currentCourse = parentCourse;
            if (item != null) {
                if ((item.course == null || item.course.isEmpty()) && currentCourse != null && !currentCourse.isEmpty()) {
                    item.course = currentCourse;
                }
                out.add(item);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collect(child, path + "/" + key, source, currentCourse, out, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collect(child, path + "[" + i + "]", source, parentCourse, out, depth + 1);
                }
            }
        }
    }

    private static DeadlineItem readItem(JSONObject object, String path, String source) {
        long dueAt = firstTime(object);
        if (dueAt <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean activeChapter = isActiveChapterSource(source);
        if (isActiveChapterListSource(source) && !isUnfinishedChapterNode(object, path)) {
            return null;
        }
        boolean submitted = isSubmitted(object);
        if (submitted || dueAt <= now) {
            return null;
        }
        String type = inferType(object, path, source);
        if (!isUsefulContext(type, path, object, source)) {
            return null;
        }
        String title = firstString(object, TITLE_KEYS);
        String course = firstString(object, COURSE_KEYS);
        if ((course == null || course.isEmpty()) && activeChapter) {
            course = courseNameFromSource(source);
        }
        if (title == null || title.isEmpty()) {
            title = course == null || course.isEmpty() ? type + "事项" : course;
        }
        DeadlineItem item = new DeadlineItem();
        item.type = type;
        item.title = clean(title);
        item.course = clean(course);
        item.dueAt = dueAt;
        item.submitted = false;
        item.source = source;
        item.raw = object.toString();
        String id = firstString(object, ID_KEYS);
        item.id = id == null || id.isEmpty() ? item.stableId() : type + "_" + id + "_" + dueAt;
        return item;
    }

    private static boolean isUsefulContext(String type, String path, JSONObject object, String source) {
        if (isActiveChapterSource(source)) {
            return !isActiveChapterListSource(source) || isChapterNodeCandidate(object, path);
        }
        if (!"事项".equals(type)) {
            return true;
        }
        String scope = (path + " " + object.toString()).toLowerCase(Locale.ROOT);
        return scope.contains("course") || scope.contains("homework") || scope.contains("exam")
                || scope.contains("chapter") || scope.contains("work") || scope.contains("job")
                || scope.contains("课程") || scope.contains("作业") || scope.contains("考试")
                || scope.contains("章节") || scope.contains("任务");
    }

    private static boolean isActiveChapterSource(String source) {
        return source != null && source.startsWith("active.chapter");
    }

    private static boolean isActiveChapterListSource(String source) {
        return source != null && source.startsWith("active.chapterList");
    }

    private static boolean isUnfinishedChapterNode(JSONObject object, String path) {
        if (!isChapterNodeCandidate(object, path)) {
            return false;
        }
        if (object.has("jobUnfinishedCount")) {
            return object.optInt("jobUnfinishedCount", 0) > 0;
        }
        if (object.has("jobcount")) {
            return object.optInt("jobcount", 0) > 0;
        }
        return false;
    }

    private static boolean isChapterNodeCandidate(JSONObject object, String path) {
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("knowledge")) {
            return true;
        }
        if (object.has("jobUnfinishedCount")) {
            return true;
        }
        return object.has("jobcount") && (object.has("parentnodeid")
                || object.has("layer") || object.has("label") || object.has("indexOrder"));
    }

    private static long firstTime(JSONObject object) {
        for (String key : TIME_KEYS) {
            if (object.has(key)) {
                long value = parseTime(object.opt(key));
                if (value > 0) {
                    return value;
                }
            }
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("time") && (lower.contains("end") || lower.contains("deadline")
                    || lower.contains("expire") || lower.contains("limit"))) {
                long value = parseTime(object.opt(key));
                if (value > 0) {
                    return value;
                }
            }
        }
        return 0L;
    }

    private static long parseTime(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return 0L;
        }
        if (value instanceof Number) {
            long number = ((Number) value).longValue();
            if (number > 0 && number < 10_000_000_000L) {
                number *= 1000L;
            }
            return plausible(number) ? number : 0L;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "0".equals(text)) {
            return 0L;
        }
        if (text.matches("\\d{10,13}")) {
            long number = Long.parseLong(text);
            if (text.length() == 10) {
                number *= 1000L;
            }
            return plausible(number) ? number : 0L;
        }
        for (String pattern : DATE_PATTERNS) {
            try {
                long parsed = new SimpleDateFormat(pattern, Locale.CHINA).parse(text).getTime();
                if (plausible(parsed)) {
                    return parsed;
                }
            } catch (ParseException ignored) {
            }
        }
        return 0L;
    }

    private static boolean plausible(long millis) {
        return millis > 1_577_836_800_000L && millis < 2_208_988_800_000L;
    }

    private static boolean isSubmitted(JSONObject object) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String lower = key.toLowerCase(Locale.ROOT);
            Object value = object.opt(key);
            boolean submitKey = lower.contains("submit") || lower.contains("finish")
                    || lower.contains("complete") || lower.contains("done") || lower.contains("status")
                    || lower.contains("state");
            if (!submitKey) {
                continue;
            }
            if (value instanceof Boolean && (Boolean) value) {
                return true;
            }
            if (value instanceof Number && ((Number) value).intValue() > 0
                    && (lower.contains("submit") || lower.contains("finish") || lower.contains("complete"))) {
                return true;
            }
            String text = String.valueOf(value).toLowerCase(Locale.ROOT);
            if ("true".equals(text) || "yes".equals(text) || text.contains("已提交")
                    || text.contains("已完成") || text.contains("submitted") || text.contains("finished")) {
                return true;
            }
        }
        String all = object.toString();
        return all.contains("已提交") || all.contains("已完成");
    }

    private static String inferType(JSONObject object, String path, String source) {
        if (isActiveChapterSource(source)) {
            return "\u7ae0\u8282";
        }
        String scope = (path + " " + object.toString()).toLowerCase(Locale.ROOT);
        if (scope.contains("homework") || scope.contains("work") || scope.contains("作业")) {
            return "作业";
        }
        if (scope.contains("exam") || scope.contains("考试") || scope.contains("测验")) {
            return "考试";
        }
        if (scope.contains("chapter") || scope.contains("knowledge") || scope.contains("章节")) {
            return "章节";
        }
        if (scope.contains("course") || scope.contains("clazz") || scope.contains("课程")) {
            return "课程";
        }
        return "事项";
    }

    private static String firstString(JSONObject object, String[] keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }
}
