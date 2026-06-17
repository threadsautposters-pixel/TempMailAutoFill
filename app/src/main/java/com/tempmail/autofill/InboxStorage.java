package com.tempmail.autofill;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class InboxStorage {
    private static final String KEY_INBOX = "inbox_messages";
    private static final int MAX_INBOX_ITEMS = 8;

    private InboxStorage() {}

    public static void saveInbox(SharedPreferences prefs, List<TempMailApi.InboxMessage> messages) {
        JSONArray array = new JSONArray();
        if (messages != null) {
            int count = Math.min(messages.size(), MAX_INBOX_ITEMS);
            for (int i = 0; i < count; i++) {
                TempMailApi.InboxMessage message = messages.get(i);
                if (message == null) {
                    continue;
                }
                JSONObject object = new JSONObject();
                try {
                    object.put("id", emptyIfNull(message.id));
                    object.put("subject", emptyIfNull(message.subject));
                    object.put("from", emptyIfNull(message.from));
                    object.put("preview", emptyIfNull(message.preview));
                    object.put("timestamp", emptyIfNull(message.timestamp));
                    object.put("code", emptyIfNull(message.verificationCode));
                    object.put("link", emptyIfNull(message.verificationLink));
                    array.put(object);
                } catch (Exception ignored) {
                    // Skip malformed items and keep persisting the rest.
                }
            }
        }
        prefs.edit().putString(KEY_INBOX, array.toString()).apply();
    }

    public static List<InboxEntry> getInbox(SharedPreferences prefs) {
        List<InboxEntry> items = new ArrayList<>();
        String raw = prefs.getString(KEY_INBOX, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                items.add(new InboxEntry(
                        object.optString("id", ""),
                        object.optString("subject", ""),
                        object.optString("from", ""),
                        object.optString("preview", ""),
                        object.optString("timestamp", ""),
                        object.optString("code", ""),
                        object.optString("link", "")
                ));
            }
        } catch (Exception ignored) {
            // Ignore corrupted inbox state and return an empty list.
        }
        return items;
    }

    public static void clear(SharedPreferences prefs) {
        prefs.edit().remove(KEY_INBOX).apply();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    public static final class InboxEntry {
        public final String id;
        public final String subject;
        public final String from;
        public final String preview;
        public final String timestamp;
        public final String code;
        public final String link;

        public InboxEntry(
                String id,
                String subject,
                String from,
                String preview,
                String timestamp,
                String code,
                String link
        ) {
            this.id = id;
            this.subject = subject;
            this.from = from;
            this.preview = preview;
            this.timestamp = timestamp;
            this.code = code;
            this.link = link;
        }

        public boolean hasLink() {
            return link != null && !link.trim().isEmpty();
        }

        public boolean hasCode() {
            return code != null && !code.trim().isEmpty();
        }
    }
}
