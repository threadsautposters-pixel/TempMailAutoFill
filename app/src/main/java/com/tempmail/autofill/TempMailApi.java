package com.tempmail.autofill;

import org.json.JSONArray;
import org.json.JSONException;
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
            Request request = new Request.Builder()
                    .url("https://10minutemail.com/10MinuteMail/resources/mailbox")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                try {
                    String json = response.body().string();
                    JSONObject obj = new JSONObject(json);
                    currentEmail = obj.optString("email", "");
                    if (!currentEmail.isEmpty()) return currentEmail;
                } catch (JSONException e) {
                    // ignore, fallback
                }
            }
            currentEmail = "temp" + System.currentTimeMillis() + "@10minutemail.com";
            return currentEmail;
        }

        if (service.contains("temp-mail.org")) {
            String[] domains = {"@temp-mail.org", "@tempmail.com", "@10minutemail.net"};
            currentEmail = "user" + System.currentTimeMillis() + domains[rand.nextInt(domains.length)];
            return currentEmail;
        }

        if (service.contains("minuteinbox.com")) {
            Request request = new Request.Builder()
                    .url("https://www.minuteinbox.com/")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String html = response.body().string();
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
            currentEmail = "temp" + System.currentTimeMillis() + "@minuteinbox.com";
            return currentEmail;
        }

        currentEmail = "fallback" + System.currentTimeMillis() + "@dropmail.me";
        return currentEmail;
    }

    public String fetchVerificationCode() throws IOException {
        if (currentUrl == null) return null;

        if (currentUrl.contains("10minutemail.com")) {
            Request request = new Request.Builder()
                    .url("https://10minutemail.com/10MinuteMail/resources/mailbox")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                try {
                    String json = response.body().string();
                    JSONObject obj = new JSONObject(json);
                    JSONArray messages = obj.optJSONArray("messages");
                    if (messages != null && messages.length() > 0) {
                        JSONObject firstMsg = messages.getJSONObject(0);
                        String subject = firstMsg.optString("subject", "");
                        String body = firstMsg.optString("body", "");
                        String digits = (subject + " " + body).replaceAll("[^0-9]", " ").trim();
                        if (digits.length() >= 4) {
                            return digits.substring(0, Math.min(8, digits.length()));
                        }
                    }
                } catch (JSONException e) {
                    // ignore
                }
            }
        }

        if (currentUrl.contains("temp-mail.org")) {
            // TODO: implement real API when needed
            return null;
        }

        if (currentUrl.contains("minuteinbox.com")) {
            Request request = new Request.Builder()
                    .url("https://www.minuteinbox.com/")
                    .get()
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String html = response.body().string();
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

    public String getCurrentProviderName() {
        if (currentUrl == null || currentUrl.isEmpty()) {
            return "Unknown provider";
        }
        if (currentUrl.contains("10minutemail.com")) {
            return "10 Minute Mail";
        }
        if (currentUrl.contains("temp-mail.org")) {
            return "Temp-Mail";
        }
        if (currentUrl.contains("minuteinbox.com")) {
            return "MinuteInbox";
        }
        return currentUrl;
    }
}
