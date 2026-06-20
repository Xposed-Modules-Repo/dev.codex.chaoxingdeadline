package dev.codex.chaoxingdeadline;

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
    private static final int DB_VERSION = 2;
    private static final String PREFS = "blocked_courses";
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
                + "due_at INTEGER NOT NULL,"
                + "submitted INTEGER NOT NULL DEFAULT 0,"
                + "source TEXT,"
                + "updated_at INTEGER NOT NULL,"
                + "raw TEXT)");
        db.execSQL("CREATE INDEX idx_deadlines_due ON deadlines(due_at)");
        createCourseTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createCourseTable(db);
        }
    }

    private void createCourseTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS courses ("
                + "name TEXT PRIMARY KEY,"
                + "updated_at INTEGER NOT NULL)");
    }

    public void upsert(DeadlineItem item) {
        if (item == null || item.dueAt <= 0 || item.title == null || item.title.isEmpty()) {
            return;
        }
        if (item.id == null || item.id.isEmpty()) {
            item.id = item.stableId();
        }
        SQLiteDatabase db = getWritableDatabase();
        db.replace("deadlines", null, item.toValues());
        deleteLikelyDuplicates(db, item);
        if (AppSettings.autoDeleteExpired(context)) {
            prune();
        } else {
            getWritableDatabase().delete("deadlines", "submitted != 0", null);
        }
    }

    public void rememberCourse(String course) {
        if (course == null || course.trim().isEmpty()) {
            return;
        }
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("name", course.trim());
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().replace("courses", null, values);
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

    private void deleteLikelyDuplicates(SQLiteDatabase db, DeadlineItem item) {
        if (item.type == null || item.title == null || item.id == null) {
            return;
        }
        long window = "考试".equals(item.type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
        db.delete(
                "deadlines",
                "id <> ? AND type = ? AND title = ? AND ABS(due_at - ?) <= ?",
                new String[]{
                        item.id,
                        item.type,
                        item.title,
                        String.valueOf(item.dueAt),
                        String.valueOf(window)
                });
    }

    public List<DeadlineItem> activeItems() {
        ArrayList<DeadlineItem> items = new ArrayList<>();
        if (AppSettings.autoDeleteExpired(context)) {
            prune();
        }
        String where = AppSettings.autoDeleteExpired(context) ? "due_at > ? AND submitted = 0" : "submitted = 0";
        String[] args = AppSettings.autoDeleteExpired(context)
                ? new String[]{String.valueOf(System.currentTimeMillis())}
                : null;
        try (Cursor cursor = getReadableDatabase().query(
                "deadlines",
                null,
                where,
                args,
                null,
                null,
                "due_at ASC")) {
            while (cursor.moveToNext()) {
                DeadlineItem item = DeadlineItem.fromCursor(cursor);
                if (!"作业".equals(item.type) && !"考试".equals(item.type)) {
                    continue;
                }
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

    public void deleteItem(String id) {
        if (id == null || id.isEmpty()) return;
        getWritableDatabase().delete("deadlines", "id = ?", new String[]{id});
    }

    public void clear() {
        getWritableDatabase().delete("deadlines", null, null);
    }


    public void blockCourseType(String course, String type, boolean blocked) {
        if (course == null || course.trim().isEmpty()) {
            return;
        }
        String key = blockKey(course, type);
        Set<String> blockedRules = new HashSet<>(blockedRules());
        if (blocked) {
            blockedRules.add(key);
        } else {
            blockedRules.remove(key);
        }
        prefs().edit().putStringSet("rules", blockedRules).apply();
    }

    public void clearBlockedCourses() {
        prefs().edit().remove("courses").remove("rules").apply();
    }

    public Set<String> blockedCourses() {
        Set<String> result = new HashSet<>(prefs().getStringSet("courses", new HashSet<>()));
        for (String rule : blockedRules()) {
            int split = rule.indexOf('|');
            if (split > 0) {
                result.add(rule.substring(0, split));
            }
        }
        return result;
    }

    public boolean isBlocked(String course) {
        return isBlocked(course, "全部");
    }

    public boolean isBlocked(String course, String type) {
        if (course == null || course.trim().isEmpty()) {
            return false;
        }
        Set<String> oldCourses = prefs().getStringSet("courses", new HashSet<>());
        if (oldCourses.contains(course.trim())) {
            return true;
        }
        Set<String> rules = blockedRules();
        return rules.contains(blockKey(course, "全部")) || rules.contains(blockKey(course, type));
    }

    public Set<String> blockedRules() {
        return new HashSet<>(prefs().getStringSet("rules", new HashSet<>()));
    }

    public List<String> knownCourses() {
        HashSet<String> courses = new HashSet<>(blockedCourses());
        try (Cursor cursor = getReadableDatabase().query(
                true,
                "courses",
                new String[]{"name"},
                "name IS NOT NULL AND name <> ''",
                null,
                null,
                null,
                "name ASC",
                null)) {
            while (cursor.moveToNext()) {
                courses.add(cursor.getString(0));
            }
        }
        try (Cursor cursor = getReadableDatabase().query(
                true,
                "deadlines",
                new String[]{"course"},
                "course IS NOT NULL AND course <> ''",
                null,
                null,
                null,
                "course ASC",
                null)) {
            while (cursor.moveToNext()) {
                courses.add(cursor.getString(0));
            }
        }
        ArrayList<String> result = new ArrayList<>(courses);
        Collections.sort(result);
        return result;
    }

    private String blockKey(String course, String type) {
        String normalizedType = type == null || type.trim().isEmpty() ? "全部" : type.trim();
        return course.trim() + "|" + normalizedType;
    }

    public void prune() {
        long now = System.currentTimeMillis();
        getWritableDatabase().delete("deadlines", "due_at <= ? OR submitted != 0", new String[]{String.valueOf(now)});
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
