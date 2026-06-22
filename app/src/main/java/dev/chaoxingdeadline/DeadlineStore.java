package dev.chaoxingdeadline;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DeadlineStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "deadlines.db";
    private static final int DB_VERSION = 4;
    private static final String PREFS = "blocked_courses";
    private static final String KEY_LEGACY_COURSES = "courses";
    private static final String KEY_BLOCKED_RULES = "rules";
    private final Context context;

    public DeadlineStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deadlines ("
                + "id TEXT PRIMARY KEY,"
                + "type TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "course TEXT,"
                + "course_id TEXT,"
                + "class_id TEXT,"
                + "cpi TEXT,"
                + "uid TEXT,"
                + "task_id TEXT,"
                + "course_confidence INTEGER NOT NULL DEFAULT 0,"
                + "due_at INTEGER NOT NULL,"
                + "submitted INTEGER NOT NULL DEFAULT 0,"
                + "submit_state INTEGER NOT NULL DEFAULT -1,"
                + "source TEXT,"
                + "url TEXT,"
                + "updated_at INTEGER NOT NULL,"
                + "raw TEXT)");
        db.execSQL("CREATE INDEX idx_deadlines_due ON deadlines(due_at)");
        db.execSQL("CREATE INDEX idx_deadlines_course_ref ON deadlines(course_id, class_id)");
        db.execSQL("CREATE INDEX idx_deadlines_task_ref ON deadlines(type, course_id, class_id, task_id)");
        createCourseTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createLegacyCourseTable(db);
        }
        if (oldVersion < 3) {
            addColumn(db, "deadlines", "course_id", "TEXT");
            addColumn(db, "deadlines", "class_id", "TEXT");
            addColumn(db, "deadlines", "cpi", "TEXT");
            addColumn(db, "deadlines", "uid", "TEXT");
            addColumn(db, "deadlines", "task_id", "TEXT");
            addColumn(db, "deadlines", "course_confidence", "INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "deadlines", "url", "TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_deadlines_course_ref ON deadlines(course_id, class_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_deadlines_task_ref ON deadlines(type, course_id, class_id, task_id)");
            migrateCourseTable(db);
        }
        if (oldVersion < 4) {
            addColumn(db, "deadlines", "submit_state", "INTEGER NOT NULL DEFAULT -1");
            try {
                db.execSQL("UPDATE deadlines SET submit_state = 1 WHERE submitted != 0");
            } catch (Throwable ignored) {
            }
        }
    }

    private void addColumn(SQLiteDatabase db, String table, String column, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Throwable ignored) {
        }
    }

    private void createLegacyCourseTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS courses ("
                + "name TEXT PRIMARY KEY,"
                + "updated_at INTEGER NOT NULL)");
    }

    private void createCourseTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS courses ("
                + "course_id TEXT NOT NULL DEFAULT '',"
                + "class_id TEXT NOT NULL DEFAULT '',"
                + "cpi TEXT,"
                + "uid TEXT,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "raw TEXT,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(course_id, class_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_courses_name ON courses(name)");
    }

    private void migrateCourseTable(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE courses RENAME TO courses_legacy");
        } catch (Throwable ignored) {
        }
        createCourseTable(db);
        try (Cursor cursor = db.query("courses_legacy", new String[]{"name", "updated_at"},
                "name IS NOT NULL AND name <> ''", null, null, null, null)) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                String name = cursor.getString(0);
                values.put("course_id", "legacy:" + Integer.toHexString(name.hashCode()));
                values.put("class_id", "");
                values.put("name", name);
                values.put("updated_at", cursor.getLong(1));
                db.replace("courses", null, values);
            }
        } catch (Throwable ignored) {
        }
        try {
            db.execSQL("DROP TABLE IF EXISTS courses_legacy");
        } catch (Throwable ignored) {
        }
    }

    public void upsert(DeadlineItem item) {
        if (item == null || item.dueAt <= 0 || item.title == null || item.title.isEmpty()) {
            return;
        }
        resolveCourse(item);
        if (item.id == null || item.id.isEmpty()) {
            item.id = item.stableId();
        }
        SQLiteDatabase db = getWritableDatabase();
        preserveSubmissionState(db, item);
        db.replace("deadlines", null, item.toValues());
        deleteLikelyDuplicates(db, item);
        if (AppSettings.autoDeleteExpired(context)) {
            prune();
        }
    }

    public void rememberCourse(String course) {
        if (course == null || course.trim().isEmpty()) {
            return;
        }
        rememberCourse("legacy:" + Integer.toHexString(course.trim().hashCode()), "", "", "", course, null);
    }

    public void rememberCourse(String courseId, String classId, String cpi, String uid, String name, String raw) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String cleanName = name.trim();
        String cleanCourseId = empty(courseId) ? "legacy:" + Integer.toHexString(cleanName.hashCode()) : courseId.trim();
        String cleanClassId = classId == null ? "" : classId.trim();
        ContentValues values = new ContentValues();
        values.put("course_id", cleanCourseId);
        values.put("class_id", cleanClassId);
        values.put("cpi", cpi == null ? "" : cpi.trim());
        values.put("uid", uid == null ? "" : uid.trim());
        values.put("name", cleanName);
        values.put("raw", raw);
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.replace("courses", null, values);
        backfillCourseName(db, cleanCourseId, cleanClassId, cleanName);
    }

    public String resolveCourse(DeadlineItem item) {
        if (item == null) {
            return "";
        }
        String name = "";
        if (!empty(item.courseId)) {
            name = findCourseName(item.courseId, item.classId);
            if (name.isEmpty()) {
                name = findCourseName(item.courseId, "");
            }
        }
        if (!name.isEmpty() && (empty(item.course) || item.courseConfidence < 90)) {
            item.course = name;
            item.courseConfidence = 90;
        }
        if (empty(item.course)) {
            String inferred = inferCourse((item.title == null ? "" : item.title) + "\n"
                    + (item.raw == null ? "" : item.raw));
            if (!inferred.isEmpty()) {
                item.course = inferred;
                item.courseConfidence = Math.max(item.courseConfidence, 10);
            }
        }
        return item.course == null ? "" : item.course;
    }

    public String findCourseName(String courseId, String classId) {
        if (empty(courseId) && empty(classId)) {
            return "";
        }
        String selection;
        String[] args;
        if (!empty(classId)) {
            selection = "course_id = ? AND class_id = ?";
            args = new String[]{courseId, classId};
        } else {
            selection = "course_id = ?";
            args = new String[]{courseId};
        }
        try (Cursor cursor = getReadableDatabase().query("courses", new String[]{"name"}, selection, args,
                null, null, "updated_at DESC", "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public String inferCourse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String best = "";
        for (String course : knownCourses()) {
            if (course == null || course.isEmpty()) {
                continue;
            }
            if (text.contains(course) && course.length() > best.length()) {
                best = course;
            }
        }
        return best;
    }

    private void backfillCourseName(SQLiteDatabase db, String courseId, String classId, String name) {
        if (empty(courseId) || empty(name)) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("course", name);
        values.put("course_confidence", 90);
        String where;
        String[] args;
        if (!empty(classId)) {
            where = "course_id = ? AND class_id = ? AND (course IS NULL OR course = '' OR course_confidence < 90)";
            args = new String[]{courseId, classId};
        } else {
            where = "course_id = ? AND (course IS NULL OR course = '' OR course_confidence < 90)";
            args = new String[]{courseId};
        }
        db.update("deadlines", values, where, args);
    }

    private void preserveSubmissionState(SQLiteDatabase db, DeadlineItem item) {
        if (item == null || item.submissionState != DeadlineItem.SUBMISSION_UNKNOWN
                || item.id == null || item.id.isEmpty()) {
            return;
        }
        if (hasSubmittedMatch(db, "id = ?", new String[]{item.id})) {
            item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
            return;
        }
        if (!empty(item.taskId) && !empty(item.courseId)) {
            if (hasSubmittedMatch(db,
                    "type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{item.type, item.courseId, item.classId == null ? "" : item.classId, item.taskId})) {
                item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
                return;
            }
        }
        long window = "考试".equals(item.type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
        if (!empty(item.title) && hasSubmittedMatch(db,
                "type = ? AND title = ? AND ABS(due_at - ?) <= ?",
                new String[]{item.type, item.title, String.valueOf(item.dueAt), String.valueOf(window)})) {
            item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
        }
    }

    private boolean hasSubmittedMatch(SQLiteDatabase db, String where, String[] args) {
        try (Cursor cursor = db.query("deadlines", new String[]{"id"},
                "submitted != 0 AND " + where, args, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    private void deleteLikelyDuplicates(SQLiteDatabase db, DeadlineItem item) {
        if (item.type == null || item.title == null || item.id == null) {
            return;
        }
        if (!empty(item.taskId) && !empty(item.courseId)) {
            db.delete("deadlines",
                    "id <> ? AND type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{item.id, item.type, item.courseId, item.classId == null ? "" : item.classId, item.taskId});
            return;
        }
        long window = "考试".equals(item.type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
        if (!empty(item.courseId) || !empty(item.classId)) {
            db.delete(
                    "deadlines",
                    "id <> ? AND type = ? AND course_id = ? AND class_id = ? AND title = ? AND ABS(due_at - ?) <= ?",
                    new String[]{item.id, item.type, item.courseId == null ? "" : item.courseId,
                            item.classId == null ? "" : item.classId, item.title,
                            String.valueOf(item.dueAt), String.valueOf(window)});
            return;
        }
        db.delete(
                "deadlines",
                "id <> ? AND type = ? AND title = ? AND ABS(due_at - ?) <= ?",
                new String[]{item.id, item.type, item.title, String.valueOf(item.dueAt), String.valueOf(window)});
    }

    public List<DeadlineItem> activeItems() {
        ArrayList<DeadlineItem> items = new ArrayList<>();
        if (AppSettings.autoDeleteExpired(context)) {
            prune();
        }
        String where = AppSettings.autoDeleteExpired(context) ? "due_at > ?" : null;
        String[] args = AppSettings.autoDeleteExpired(context)
                ? new String[]{String.valueOf(System.currentTimeMillis())}
                : null;
        try (Cursor cursor = getReadableDatabase().query(
                "deadlines", null, where, args, null, null, "due_at ASC")) {
            while (cursor.moveToNext()) {
                DeadlineItem item = DeadlineItem.fromCursor(cursor);
                if (!"作业".equals(item.type) && !"考试".equals(item.type)) {
                    continue;
                }
                resolveCourse(item);
                if (!isBlocked(item.course, item.type)) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    public int countAll() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM deadlines", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public DeadlineItem itemById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query("deadlines", null, "id = ?",
                new String[]{id}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            DeadlineItem item = DeadlineItem.fromCursor(cursor);
            resolveCourse(item);
            if (item.submitted || isBlocked(item.course, item.type)) {
                return null;
            }
            return item;
        }
    }

    public void deleteItem(String id) {
        if (id == null || id.isEmpty()) return;
        getWritableDatabase().delete("deadlines", "id = ?", new String[]{id});
    }

    public void clear() {
        getWritableDatabase().delete("deadlines", null, null);
    }

    public void blockCourseType(String course, String type, boolean blocked) {
        setCourseTypeEnabled(course, type, !blocked);
    }

    public void setCourseTypeEnabled(String course, String type, boolean enabled) {
        if (course == null || course.trim().isEmpty()) {
            return;
        }
        String key = blockKey(course, type);
        Set<String> blockedRules = new HashSet<>(blockedRules());
        if (enabled) {
            blockedRules.remove(key);
        } else {
            blockedRules.add(key);
        }
        prefs().edit()
                .putStringSet(KEY_BLOCKED_RULES, blockedRules)
                .apply();
    }


    public void clearBlockedCourses() {
        prefs().edit()
                .remove(KEY_LEGACY_COURSES)
                .remove(KEY_BLOCKED_RULES)
                .apply();
    }

    public Set<String> blockedCourses() {
        Set<String> result = new HashSet<>(prefs().getStringSet(KEY_LEGACY_COURSES, new HashSet<>()));
        for (String rule : blockedRules()) {
            int bar = rule.lastIndexOf('|');
            if (bar > 0) {
                result.add(rule.substring(0, bar));
            }
        }
        return result;
    }

    public boolean isBlocked(String course) {
        return isBlocked(course, "全部");
    }

    public boolean isBlocked(String course, String type) {
        return !isCourseTypeEnabled(course, type);
    }

    public boolean isCourseTypeEnabled(String course, String type) {
        if (course == null || course.trim().isEmpty()) {
            return true;
        }
        String cleanCourse = course.trim();
        Set<String> legacyCourses = prefs().getStringSet(KEY_LEGACY_COURSES, new HashSet<>());
        if (legacyCourses.contains(cleanCourse)) {
            return false;
        }
        Set<String> blocked = blockedRules();
        if (blocked.contains(blockKey(cleanCourse, "全部")) || blocked.contains(blockKey(cleanCourse, type))) {
            return false;
        }
        return true;
    }

    public List<String> knownCourses() {
        HashSet<String> courses = new HashSet<>(blockedCourses());
        courses.addAll(knownCoursesFromDb());
        ArrayList<String> result = new ArrayList<>(courses);
        Collections.sort(result);
        return result;
    }

    private Set<String> knownCoursesFromDb() {
        HashSet<String> courses = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query("courses", new String[]{"name"},
                "name IS NOT NULL AND name <> ''", null, "name", null, "name ASC")) {
            while (cursor.moveToNext()) {
                courses.add(cursor.getString(0));
            }
        }
        try (Cursor cursor = getReadableDatabase().query("deadlines", new String[]{"course"},
                "course IS NOT NULL AND course <> ''", null, "course", null, "course ASC")) {
            while (cursor.moveToNext()) {
                courses.add(cursor.getString(0));
            }
        }
        return courses;
    }

    private String blockKey(String course, String type) {
        String normalizedType = type == null || type.trim().isEmpty() ? "全部" : type.trim();
        return course.trim() + "|" + normalizedType;
    }

    private Set<String> blockedRules() {
        return new HashSet<>(prefs().getStringSet(KEY_BLOCKED_RULES, new HashSet<>()));
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void prune() {
        long now = System.currentTimeMillis();
        getWritableDatabase().delete("deadlines", "due_at <= ?", new String[]{String.valueOf(now)});
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
