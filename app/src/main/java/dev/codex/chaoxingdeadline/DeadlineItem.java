package dev.codex.chaoxingdeadline;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class DeadlineItem {
    public String id;
    public String type;
    public String title;
    public String course;
    public long dueAt;
    public boolean submitted;
    public String source;
    public String raw;

    public ContentValues toValues() {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("type", type);
        values.put("title", title);
        values.put("course", course);
        values.put("due_at", dueAt);
        values.put("submitted", submitted ? 1 : 0);
        values.put("source", source);
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
        json.put("dueAt", dueAt);
        json.put("submitted", submitted);
        json.put("source", source);
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
        item.dueAt = json.optLong("dueAt", 0L);
        item.submitted = json.optBoolean("submitted", false);
        item.source = json.optString("source", "");
        item.raw = json.optString("raw", "");
        return item;
    }

    public static DeadlineItem fromCursor(Cursor cursor) {
        DeadlineItem item = new DeadlineItem();
        item.id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        item.type = cursor.getString(cursor.getColumnIndexOrThrow("type"));
        item.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        item.course = cursor.getString(cursor.getColumnIndexOrThrow("course"));
        item.dueAt = cursor.getLong(cursor.getColumnIndexOrThrow("due_at"));
        item.submitted = cursor.getInt(cursor.getColumnIndexOrThrow("submitted")) != 0;
        item.source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
        item.raw = cursor.getString(cursor.getColumnIndexOrThrow("raw"));
        return item;
    }

    public String stableId() {
        String basis = (type == null ? "" : type) + "|" + (course == null ? "" : course)
                + "|" + (title == null ? "" : title) + "|" + dueAt;
        return Integer.toHexString(basis.toLowerCase(Locale.ROOT).hashCode());
    }
}
