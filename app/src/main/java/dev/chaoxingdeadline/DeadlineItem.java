package dev.chaoxingdeadline;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class DeadlineItem {
    public static final int SUBMISSION_UNKNOWN = -1;
    public static final int SUBMISSION_UNSUBMITTED = 0;
    public static final int SUBMISSION_SUBMITTED = 1;

    public String id;
    public String type;
    public String title;
    public String course;
    public String courseId;
    public String classId;
    public String cpi;
    public String uid;
    public String taskId;
    public int courseConfidence;
    public long dueAt;
    public boolean submitted;
    public int submissionState = SUBMISSION_UNKNOWN;
    public String source;
    public String url;
    public String raw;

    public ContentValues toValues() {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("type", type);
        values.put("title", title);
        values.put("course", course);
        values.put("course_id", courseId);
        values.put("class_id", classId);
        values.put("cpi", cpi);
        values.put("uid", uid);
        values.put("task_id", taskId);
        values.put("course_confidence", courseConfidence);
        values.put("due_at", dueAt);
        int storedSubmissionState = submitted && submissionState == SUBMISSION_UNKNOWN
                ? SUBMISSION_SUBMITTED : submissionState;
        values.put("submitted", submitted ? 1 : 0);
        values.put("submit_state", storedSubmissionState);
        values.put("source", source);
        values.put("url", url);
        values.put("updated_at", System.currentTimeMillis());
        values.put("raw", raw);
        return values;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type);
        json.put("title", title);
        json.put("course", course);
        json.put("courseId", courseId);
        json.put("classId", classId);
        json.put("cpi", cpi);
        json.put("uid", uid);
        json.put("taskId", taskId);
        json.put("courseConfidence", courseConfidence);
        json.put("dueAt", dueAt);
        json.put("submitted", submitted);
        json.put("submitState", submissionState);
        json.put("source", source);
        json.put("url", url);
        json.put("raw", raw);
        return json;
    }

    public static DeadlineItem fromJson(String value) throws JSONException {
        JSONObject json = new JSONObject(value);
        DeadlineItem item = new DeadlineItem();
        item.id = json.optString("id");
        item.type = json.optString("type", "事项");
        item.title = json.optString("title", "未命名");
        item.course = json.optString("course", "");
        item.courseId = json.optString("courseId", "");
        item.classId = json.optString("classId", "");
        item.cpi = json.optString("cpi", "");
        item.uid = json.optString("uid", "");
        item.taskId = json.optString("taskId", "");
        item.courseConfidence = json.optInt("courseConfidence", item.course == null || item.course.isEmpty() ? 0 : 60);
        item.dueAt = json.optLong("dueAt", 0L);
        if (json.has("submitState")) {
            item.setSubmissionState(json.optInt("submitState", SUBMISSION_UNKNOWN));
        } else if (json.optBoolean("submitted", false)) {
            item.setSubmissionState(SUBMISSION_SUBMITTED);
        }
        item.source = json.optString("source", "");
        item.url = json.optString("url", "");
        item.raw = json.optString("raw", "");
        return item;
    }

    public static DeadlineItem fromCursor(Cursor cursor) {
        DeadlineItem item = new DeadlineItem();
        item.id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        item.type = cursor.getString(cursor.getColumnIndexOrThrow("type"));
        item.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        item.course = stringOrEmpty(cursor, "course");
        item.courseId = stringOrEmpty(cursor, "course_id");
        item.classId = stringOrEmpty(cursor, "class_id");
        item.cpi = stringOrEmpty(cursor, "cpi");
        item.uid = stringOrEmpty(cursor, "uid");
        item.taskId = stringOrEmpty(cursor, "task_id");
        item.courseConfidence = intOrZero(cursor, "course_confidence");
        item.dueAt = cursor.getLong(cursor.getColumnIndexOrThrow("due_at"));
        item.submitted = cursor.getInt(cursor.getColumnIndexOrThrow("submitted")) != 0;
        item.setSubmissionState(intOrDefault(cursor, "submit_state",
                item.submitted ? SUBMISSION_SUBMITTED : SUBMISSION_UNKNOWN));
        item.source = stringOrEmpty(cursor, "source");
        item.url = stringOrEmpty(cursor, "url");
        item.raw = stringOrEmpty(cursor, "raw");
        return item;
    }

    public String stableId() {
        String basis;
        if (taskId != null && !taskId.isEmpty()) {
            basis = safe(type) + "|" + safe(courseId) + "|" + safe(classId) + "|" + taskId;
        } else {
            basis = safe(type) + "|" + safe(courseId) + "|" + safe(classId)
                    + "|" + safe(course) + "|" + safe(title) + "|" + dueAt;
        }
        return safe(type) + "_" + Integer.toHexString(basis.toLowerCase(Locale.ROOT).hashCode());
    }

    public void applyContext(ParseContext context) {
        if (context == null) {
            return;
        }
        if (empty(source)) source = context.source;
        if (empty(url)) url = context.url;
        if (empty(courseId)) courseId = context.courseId;
        if (empty(classId)) classId = context.classId;
        if (empty(cpi)) cpi = context.cpi;
        if (empty(uid)) uid = context.uid;
        if (!empty(context.courseName) && (empty(course) || context.courseConfidence >= courseConfidence)) {
            course = context.courseName;
            courseConfidence = context.courseConfidence;
        }
    }

    private static String stringOrEmpty(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return "";
        }
        return cursor.getString(index);
    }

    private static int intOrZero(Cursor cursor, String column) {
        return intOrDefault(cursor, column, 0);
    }

    private static int intOrDefault(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return fallback;
        }
        return cursor.getInt(index);
    }

    public void setSubmissionState(int state) {
        if (state < SUBMISSION_UNKNOWN || state > SUBMISSION_SUBMITTED) {
            state = SUBMISSION_UNKNOWN;
        }
        submissionState = state;
        submitted = state == SUBMISSION_SUBMITTED;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
