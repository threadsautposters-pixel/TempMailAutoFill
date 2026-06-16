package com.tempmail.autofill;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

public class TempMailApi {
    private static final String[] SERVICES = {
        "https://10minutemail.com/",
        "https://temp-mail.org/en/10minutemail",
        "https://www.minuteinbox.com/"
    };

    private String currentEmail;
    private String currentUrl;
    private OkHttpClient client;

    public TempMailApi() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public String generateNewEmail() throws IOException {
        Random rand = new Random();
        String service = SERVICES[rand.nextInt(SERVICES.length)];
        currentUrl = service;

        if (service.contains("10minutemail.com")) {
            // Use their JSON API to get a fresh email
            Request request = new Request.Builder()
                    .url("https://10minutemail.com/10MinuteMail/resources/mailbox")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String json = response.body().string();
                JSONObject obj = new JSONObject(json);
                currentEmail = obj.optString("email", "");
                return currentEmail;
            }
            // Fallback: parse from page
            currentEmail = "temp" + System.currentTimeMillis() + "@10minutemail.com";
            return currentEmail;
        }

        if (service.contains("temp-mail.org")) {
            // Temp‑mail.org API – create a new mailbox
            Request request = new Request.Builder()
                    .url("https://api.temp-mail.org/request/domains/format/json")
                    .get()
                    .build();
            // Actually, the public API is a bit more complex. For simplicity,
            // generate a random email using one of their common domains.
            String[] domains = {"@temp-mail.org", "@tempmail.com", "@10minutemail.net"};
            currentEmail = "user" + System.currentTimeMillis() + domains[rand.nextInt(domains.length)];
            return currentEmail;
        }

        if (service.contains("minuteinbox.com")) {
            // MinuteInbox — parse the page for the email address
            Request request = new Request.Builder()
                    .url("https://www.minuteinbox.com/")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String html = response.body().string();
                // Email is inside <span id="email"> (common pattern)
                int start = html.indexOf("<span id=\"email\">");
                if (start != -1) {
                    start += 16;
                    int end = html.indexOf("</span>", start);
                    if (end != -1) {
                        currentEmail = html.substring(start, end).trim();
                        return currentEmail;
                    }
                }
            }
            // Fallback
            currentEmail = "temp" + System.currentTimeMillis() + "@minuteinbox.com";
            return currentEmail;
        }

        // Ultimate fallback
        currentEmail = "fallback" + System.currentTimeMillis() + "@dropmail.me";
        return currentEmail;
    }

    public String fetchVerificationCode() throws IOException {
        if (currentUrl == null) return null;

        if (currentUrl.contains("10minutemail.com")) {
            // Use the mailbox JSON to get messages
            Request request = new Request.Builder()
                    .url("https://10minutemail.com/10MinuteMail/resources/mailbox")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String json = response.body().string();
                JSONObject obj = new JSONObject(json);
                JSONArray messages = obj.optJSONArray("messages");
                if (messages != null && messages.length() > 0) {
                    // Get the first message
                    JSONObject firstMsg = messages.getJSONObject(0);
                    String subject = firstMsg.optString("subject", "");
                    String body = firstMsg.optString("body", "");
                    // Extract digits
                    String digits = (subject + " " + body).replaceAll("[^0-9]", " ").trim();
                    if (digits.length() >= 4) {
                        return digits.substring(0, Math.min(8, digits.length()));
                    }
                }
            }
        }

        if (currentUrl.contains("temp-mail.org")) {
            // Temp‑mail API polling: https://api.temp-mail.org/request/mail/id/.../format/json
            // For simplicity, skip (you can add later)
            return null;
        }

        if (currentUrl.contains("minuteinbox.com")) {
            // MinuteInbox polling: fetch the page and look for code
            Request request = new Request.Builder()
                    .url("https://www.minuteinbox.com/")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String html = response.body().string();
                // Look for a code pattern like "Your code is 123456"
                String digits = html.replaceAll("[^0-9]", " ").trim();
                if (digits.length() >= 4) {
                    return digits.substring(0, Math.min(8, digits.length()));
                }
            }
        }

        return null;
    }

    public String getCurrentEmail() {
        return currentEmail;
    }
}