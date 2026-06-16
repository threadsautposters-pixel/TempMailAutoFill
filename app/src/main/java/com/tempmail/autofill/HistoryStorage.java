package com.tempmail.autofill;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStorage {
    private static final String KEY_HISTORY = "email_history";
    private static final int MAX_HISTORY_ITEMS = 12;

    private HistoryStorage() {}

    public static void recordEmail(SharedPreferences prefs, String email, String provider, long timestamp) {
        if (email == null || email.trim().isEmpty()) {
            return;
        }

        List<HistoryEntry> existing = getHistory(prefs);
        List<HistoryEntry> updated = new ArrayList<>();
        updated.add(new HistoryEntry(email.trim(), provider, timestamp));

        for (HistoryEntry entry : existing) {
            if (entry.email.equalsIgnoreCase(email.trim())) {
                continue;
            }
            updated.add(entry);
            if (updated.size() >= MAX_HISTORY_ITEMS) {
                break;
            }
        }

        JSONArray array = new JSONArray();
        for (HistoryEntry entry : updated) {
            JSONObject object = new JSONObject();
            try {
                object.put("email", entry.email);
                object.put("provider", entry.provider == null ? "" : entry.provider);
                object.put("timestamp", entry.timestamp);
                array.put(object);
            } catch (Exception ignored) {
                // Ignore malformed items and continue saving what we can.
            }
        }

        prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    public static List<HistoryEntry> getHistory(SharedPreferences prefs) {
        List<HistoryEntry> items = new ArrayList<>();
        String raw = prefs.getString(KEY_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }

                String email = object.optString("email", "");
                if (email.trim().isEmpty()) {
                    continue;
                }

                items.add(new HistoryEntry(
                        email,
                        object.optString("provider", ""),
                        object.optLong("timestamp", 0L)
                ));
            }
        } catch (Exception ignored) {
            // Ignore corrupted history and return an empty list.
        }
        return items;
    }

    public static void clear(SharedPreferences prefs) {
        prefs.edit().remove(KEY_HISTORY).apply();
    }

    public static final class HistoryEntry {
        public final String email;
        public final String provider;
        public final long timestamp;

        public HistoryEntry(String email, String provider, long timestamp) {
            this.email = email;
            this.provider = provider;
            this.timestamp = timestamp;
        }
    }
}
