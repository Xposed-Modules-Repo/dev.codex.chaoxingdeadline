package dev.chaoxingdeadline;

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
            "activityEndTime", "answerEndTime", "answerEndtime", "deadlineTime", "endDateTime",
            "endtimeStr", "endTimeStr", "taskEndTime", "limitTime", "limittime",
            "nameFour", "namefour", "nameFive", "namefive"
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
        return parsePayload(text, ParseContext.fromSource(source, ""));
    }

    public static List<DeadlineItem> parsePayload(String text, ParseContext context) {
        ParseContext ctx = context == null ? ParseContext.simple("") : context;
        ArrayList<DeadlineItem> result = new ArrayList<>();
        if (text != null && ctx.isActive()) {
            result.addAll(parseHtmlList(text, ctx));
        }
        if (!looksRelevant(text) && !sourceLooksLikeDeadline(ctx)) {
            return result;
        }
        try {
            String trimmed = text.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            collect(root, "", ctx, "", result, 0);
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static boolean sourceLooksLikeDeadline(ParseContext context) {
        if (context == null) {
            return false;
        }
        String scope = ((context.source == null ? "" : context.source) + " "
                + (context.url == null ? "" : context.url)).toLowerCase(Locale.ROOT);
        return scope.contains("work") || scope.contains("homework") || scope.contains("exam")
                || scope.contains("task-list") || scope.contains("taskactivelist")
                || scope.contains("stu-work") || scope.contains("examcode")
                || scope.contains("作业") || scope.contains("考试") || scope.contains("任务");
    }

    private static List<DeadlineItem> parseHtmlList(String html, ParseContext context) {
        ArrayList<DeadlineItem> result = new ArrayList<>();
        String source = context == null ? "" : context.source;
        if (html == null || html.length() < 20 || html.indexOf('<') < 0) {
            return result;
        }
        if (source.contains("workPage") || source.contains("workList") || html.contains("stu-work")) {
            parseListItems(html, context, "作业", result);
        }
        if (source.contains("examPage") || source.contains("examList")
                || html.contains("ks_list") || html.contains("examcode")) {
            parseListItems(html, context, "考试", result);
        }
        if (source.contains("chapterList") || html.contains("knowledge") || html.contains("chapter")) {
            parseListItems(html, context, "章节", result);
        }
        return result;
    }

    private static void parseListItems(String html, ParseContext context, String type, List<DeadlineItem> out) {
        Matcher matcher = Pattern.compile("(?is)<li\\b([^>]*)>(.*?)</li>").matcher(html);
        while (matcher.find()) {
            String attrs = matcher.group(1);
            String li = matcher.group(2);
            String plain = stripTags(li);
            int submissionState = htmlSubmissionState(plain);
            long dueAt = parseDueFromText(plain, context);
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
            int confidence = 30;
            if (badCourseText(course)) {
                course = context == null ? "" : context.courseName;
                confidence = context == null ? 0 : context.courseConfidence;
            }
            DeadlineItem item = new DeadlineItem();
            item.type = type;
            item.title = clean(stripTags(title));
            item.course = clean(stripTags(course));
            item.courseConfidence = confidence;
            item.dueAt = dueAt;
            item.setSubmissionState(submissionState);
            item.source = context == null ? "" : context.source;
            item.url = context == null ? "" : context.url;
            item.raw = matcher.group(0);
            fillIdentityFromRaw(item, attrs + " " + li);
            if (context != null) {
                item.applyContext(context);
            }
            item.id = htmlStableId(type, attrs + " " + li, item.title, item.course);
            if (!item.taskId.isEmpty() || !item.courseId.isEmpty()) {
                item.id = item.stableId();
            }
            if (!item.title.isEmpty()) {
                out.add(item);
            }
        }
    }

    private static int htmlSubmissionState(String plain) {
        if (plain == null) {
            return DeadlineItem.SUBMISSION_UNKNOWN;
        }
        boolean explicitlyUnfinished = plain.contains("未完成") || plain.contains("未提交")
                || plain.contains("未交") || plain.contains("待完成") || plain.contains("待提交");
        if (explicitlyUnfinished) {
            return DeadlineItem.SUBMISSION_UNSUBMITTED;
        }
        boolean explicitlySubmitted = plain.contains("待批阅") || plain.contains("已提交")
                || plain.contains("已完成") || (plain.contains("已交") && !plain.contains("未交"));
        return explicitlySubmitted ? DeadlineItem.SUBMISSION_SUBMITTED : DeadlineItem.SUBMISSION_UNKNOWN;
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
            matcher = Pattern.compile("(?i)" + Pattern.quote(key) + "[\\\"']?\\s*[:=]\\s*[\\\"']?([^,}&\\\"'\\s>]+)").matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static void fillIdentityFromRaw(DeadlineItem item, String raw) {
        if (item == null || raw == null) {
            return;
        }
        item.taskId = firstParam(raw, "taskrefId", "taskId", "workId", "workid", "examId", "examid", "id", "aid", "paperId");
        item.courseId = firstParam(raw, "courseId", "courseid", "courseIdStr", "courseidStr", "moocId");
        item.classId = firstParam(raw, "classId", "classid", "clazzId", "clazzid", "clazzIdStr", "classIdStr");
        item.cpi = firstParam(raw, "cpi", "cpiId");
        item.uid = firstParam(raw, "uid", "puid", "personid", "personId");
    }

    private static long parseDueFromText(String text, ParseContext context) {
        long absolute = parseTimeFromFreeText(text);
        if (absolute > 0) {
            return absolute;
        }
        if (!allowRelativeDueFallback(context)) {
            return 0L;
        }
        return parseRelativeDueFromText(text);
    }

    private static boolean allowRelativeDueFallback(ParseContext context) {
        String source = context == null || context.source == null ? "" : context.source;
        // The all-work page is currently the only active endpoint that reliably exposes
        // unfinished homework plus remaining time. Keep examPage disabled because it often
        // lacks a usable remaining/deadline signal and would create noisy guesses.
        if (source.contains("examPage")) {
            return false;
        }
        return source.contains("workPage") || source.contains("workList") || source.contains("examList")
                || source.contains("taskList") || source.contains("chapter");
    }

    private static long parseRelativeDueFromText(String text) {
        long now = System.currentTimeMillis();
        long days = 0L;
        Matcher dayMatcher = Pattern.compile("(?:(?:还剩|剩余|距截止|离截止)\\s*)?(\\d+)\\s*天").matcher(text);
        if (dayMatcher.find()) {
            days = Long.parseLong(dayMatcher.group(1));
        }
        long hours = firstLong(text, "(\\d+)\\s*小时");
        long minutes = firstLong(text, "(\\d+)\\s*分钟");
        if (days <= 0 && hours <= 0 && minutes <= 0) {
            return 0L;
        }
        if (minutes > 0) {
            // Chaoxing list pages show remaining time rounded down, for example
            // "剩余54小时47分钟" while the real deadline is 54h47m46s away.
            // Using now + displayed duration directly produced systematic :59 / one-minute-early
            // deadlines such as 06-22 23:59 for a real 06-23 00:00 deadline.
            long lowerBound = now + TimeUnit.DAYS.toMillis(days)
                    + TimeUnit.HOURS.toMillis(hours)
                    + TimeUnit.MINUTES.toMillis(minutes);
            return ceilToMinute(lowerBound);
        }
        long hourEndBase = now - (now % TimeUnit.HOURS.toMillis(1))
                + TimeUnit.MINUTES.toMillis(59)
                + TimeUnit.SECONDS.toMillis(59);
        return ceilToMinute(hourEndBase + TimeUnit.DAYS.toMillis(days) + TimeUnit.HOURS.toMillis(hours));
    }

    private static long ceilToMinute(long millis) {
        long minute = TimeUnit.MINUTES.toMillis(1);
        return ((millis + minute - 1L) / minute) * minute;
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

    private static void collect(Object node, String path, ParseContext context, String parentCourse, List<DeadlineItem> out, int depth) throws JSONException {
        if (node == null || depth > 14) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            DeadlineItem item = readItem(object, path, context);
            String currentCourse = firstString(object, PARENT_COURSE_KEYS);
            if (currentCourse.isEmpty()) currentCourse = parentCourse;
            if (item != null) {
                if ((item.course == null || item.course.isEmpty()) && currentCourse != null && !currentCourse.isEmpty()) {
                    item.course = currentCourse;
                    item.courseConfidence = Math.max(item.courseConfidence, 50);
                }
                out.add(item);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collect(child, path + "/" + key, context, currentCourse, out, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collect(child, path + "[" + i + "]", context, parentCourse, out, depth + 1);
                }
            }
        }
    }

    private static DeadlineItem readItem(JSONObject object, String path, ParseContext context) {
        String source = context == null ? "" : context.source;
        long dueAt = firstTime(object);
        if (dueAt <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean activeChapter = isActiveChapterSource(source);
        if (isActiveChapterListSource(source) && !isUnfinishedChapterNode(object, path)) {
            return null;
        }
        int submissionState = submissionState(object);
        if (dueAt <= now) {
            return null;
        }
        String type = inferType(object, path, source);
        if (!isUsefulContext(type, path, object, source)) {
            return null;
        }
        String title = firstString(object, TITLE_KEYS);
        String course = firstString(object, COURSE_KEYS);
        int confidence = course == null || course.isEmpty() ? 0 : 100;
        if ((course == null || course.isEmpty()) && activeChapter) {
            course = courseNameFromSource(source);
            confidence = course == null || course.isEmpty() ? 0 : 80;
        }
        if (title == null || title.isEmpty()) {
            title = course == null || course.isEmpty() ? type + "事项" : course;
        }
        DeadlineItem item = new DeadlineItem();
        item.type = type;
        item.title = clean(title);
        item.course = clean(course);
        item.courseConfidence = confidence;
        item.dueAt = dueAt;
        item.setSubmissionState(submissionState);
        item.source = source;
        item.url = context == null ? "" : context.url;
        item.raw = object.toString();
        item.taskId = firstString(object, ID_KEYS);
        item.courseId = firstString(object, "courseId", "courseid", "courseIdStr", "courseidStr", "moocId");
        item.classId = firstString(object, "classId", "classid", "clazzId", "clazzid", "clazzIdStr", "classIdStr");
        item.cpi = firstString(object, "cpi", "cpiId");
        item.uid = firstString(object, "uid", "puid", "personid", "personId");
        if (context != null) {
            item.applyContext(context);
        }
        item.id = item.stableId();
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
        boolean dateOnly = isDateOnly(text);
        for (String pattern : DATE_PATTERNS) {
            try {
                long parsed = new SimpleDateFormat(pattern, Locale.CHINA).parse(text).getTime();
                if (dateOnly) {
                    parsed += TimeUnit.DAYS.toMillis(1) - 1000L;
                }
                if (plausible(parsed)) {
                    return parsed;
                }
            } catch (ParseException ignored) {
            }
        }
        long embedded = parseTimeFromFreeText(text);
        return embedded > 0 ? embedded : parseMonthDayTime(text);
    }

    private static boolean isDateOnly(String text) {
        return text != null && text.matches("20\\d{2}(?:[-/.]\\d{1,2}[-/.]\\d{1,2}|年\\d{1,2}月\\d{1,2}日?)");
    }

    private static long parseMonthDayTime(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        Matcher matcher = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日(?:\\s+|[^0-9]+)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(text);
        if (!matcher.find()) {
            return 0L;
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance(Locale.CHINA);
        calendar.set(java.util.Calendar.MONTH, Integer.parseInt(matcher.group(1)) - 1);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, Integer.parseInt(matcher.group(2)));
        calendar.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(matcher.group(3)));
        calendar.set(java.util.Calendar.MINUTE, Integer.parseInt(matcher.group(4)));
        calendar.set(java.util.Calendar.SECOND, matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5)));
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        long parsed = calendar.getTimeInMillis();
        if (parsed <= System.currentTimeMillis()) {
            calendar.add(java.util.Calendar.YEAR, 1);
            parsed = calendar.getTimeInMillis();
        }
        return plausible(parsed) ? parsed : 0L;
    }

    private static boolean plausible(long millis) {
        return millis > 1_577_836_800_000L && millis < 2_208_988_800_000L;
    }

    private static int submissionState(JSONObject object) {
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
            if (value instanceof Boolean) {
                if (lower.contains("submit") || lower.contains("finish") || lower.contains("complete")) {
                    return (Boolean) value ? DeadlineItem.SUBMISSION_SUBMITTED
                            : DeadlineItem.SUBMISSION_UNSUBMITTED;
                }
                continue;
            }
            if (value instanceof Number && (lower.contains("submit") || lower.contains("finish") || lower.contains("complete"))) {
                return ((Number) value).intValue() > 0
                        ? DeadlineItem.SUBMISSION_SUBMITTED : DeadlineItem.SUBMISSION_UNSUBMITTED;
            }
            String text = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (text.contains("未提交") || text.contains("未完成") || text.contains("未交")
                    || text.contains("unsubmitted") || text.contains("unfinished")
                    || "false".equals(text) || "no".equals(text)) {
                return DeadlineItem.SUBMISSION_UNSUBMITTED;
            }
            if ("true".equals(text) || "yes".equals(text) || text.contains("已提交")
                    || text.contains("已完成") || text.contains("待批阅")
                    || text.contains("submitted") || text.contains("finished")) {
                return DeadlineItem.SUBMISSION_SUBMITTED;
            }
        }
        return DeadlineItem.SUBMISSION_UNKNOWN;
    }

    private static String inferType(JSONObject object, String path, String source) {
        String lowerSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (lowerSource.contains("exam")) {
            return "考试";
        }
        if (lowerSource.contains("work") || lowerSource.contains("homework") || lowerSource.contains("tasklist")) {
            return "作业";
        }
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

    private static String firstString(JSONObject object, String... keys) {
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
