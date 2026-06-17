package com.tempmail.autofill;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TempMailApi {
    private static final String BASE_URL = "https://api.mail.tm";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern KEYWORD_CODE_PATTERN = Pattern.compile(
            "(?:code|otp|verification|verify|pin|passcode)[^0-9]{0,24}([0-9]{4,8})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ALPHANUMERIC_KEYWORD_CODE_PATTERN = Pattern.compile(
            "(?:code|otp|verification|verify|pin|passcode)[^a-z0-9]{0,24}([a-z0-9-]{4,12})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMERIC_CODE_PATTERN = Pattern.compile("\\b([0-9]{4,8})\\b");

    private String currentEmail;
    private String currentProvider = "Mail.tm";
    private String currentPassword;
    private String currentToken;
    private String currentAccountId;
    private String lastMessageId;
    private final OkHttpClient client;

    public TempMailApi() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public String generateNewEmail() throws IOException {
        JSONArray domains = fetchDomains();
        if (domains.length() == 0) {
            throw new IOException("No active mailbox domains available");
        }

        String domain = null;
        for (int i = 0; i < domains.length(); i++) {
            JSONObject item = domains.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (item.optBoolean("isActive", true)) {
                domain = item.optString("domain", "");
                if (!domain.isEmpty()) {
                    break;
                }
            }
        }

        if (domain == null || domain.isEmpty()) {
            throw new IOException("No usable mailbox domain returned");
        }

        IOException lastError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            String localPart = "tm" + System.currentTimeMillis() + attempt;
            String address = localPart + "@" + domain;
            String password = "pw" + System.nanoTime() + "A!";
            try {
                createAccount(address, password);
                currentEmail = address;
                currentPassword = password;
                currentToken = fetchToken(address, password);
                currentAccountId = fetchAccountId();
                lastMessageId = null;
                currentProvider = "Mail.tm";
                return currentEmail;
            } catch (IOException e) {
                lastError = e;
            }
        }

        throw lastError != null ? lastError : new IOException("Failed to create mailbox account");
    }

    public String fetchVerificationCode() throws IOException {
        if (currentToken == null || currentToken.isEmpty()) {
            return null;
        }

        JSONObject inbox = getJson("/messages", true);
        JSONArray messages = inbox.optJSONArray("hydra:member");
        if (messages == null || messages.length() == 0) {
            return null;
        }

        String bestMessageId = null;
        String bestCode = null;
        String bestTimestamp = null;

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }

            String messageId = message.optString("id", "");
            if (messageId.isEmpty() || messageId.equals(lastMessageId)) {
                continue;
            }

            JSONObject detail = getJson("/messages/" + messageId, true);
            String code = extractVerificationCode(detail);
            if (code == null) {
                code = extractVerificationCode(fetchDownloadedMessage(messageId));
            }
            if (code == null) {
                continue;
            }

            String timestamp = message.optString("createdAt", "");
            if (timestamp.isEmpty()) {
                timestamp = message.optString("updatedAt", "");
            }

            if (bestCode == null) {
                bestMessageId = messageId;
                bestCode = code;
                bestTimestamp = timestamp;
                continue;
            }

            if (timestamp.isEmpty() || bestTimestamp == null || bestTimestamp.isEmpty()) {
                bestMessageId = messageId;
                bestCode = code;
                bestTimestamp = timestamp;
                continue;
            }

            if (timestamp.compareTo(bestTimestamp) > 0) {
                bestMessageId = messageId;
                bestCode = code;
                bestTimestamp = timestamp;
            }
        }

        if (bestCode != null && bestMessageId != null) {
            lastMessageId = bestMessageId;
            return bestCode;
        }

        return null;
    }

    private JSONArray fetchDomains() throws IOException {
        String body = getResponseBody("/domains", false);
        String trimmedBody = body == null ? "" : body.trim();
        if (trimmedBody.isEmpty()) {
            return new JSONArray();
        }

        try {
            if (trimmedBody.startsWith("[")) {
                return new JSONArray(trimmedBody);
            }

            JSONObject response = new JSONObject(trimmedBody);
            JSONArray hydraItems = response.optJSONArray("hydra:member");
            if (hydraItems != null) {
                return hydraItems;
            }

            JSONArray directItems = response.optJSONArray("domains");
            return directItems != null ? directItems : new JSONArray();
        } catch (JSONException e) {
            throw new IOException("Invalid JSON from /domains", e);
        }
    }

    private void createAccount(String address, String password) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("address", address);
            payload.put("password", password);
        } catch (JSONException e) {
            throw new IOException("Failed to build account payload", e);
        }

        postJson("/accounts", payload, false);
    }

    private String fetchToken(String address, String password) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("address", address);
            payload.put("password", password);
        } catch (JSONException e) {
            throw new IOException("Failed to build token payload", e);
        }

        JSONObject response = postJson("/token", payload, false);
        String token = response.optString("token", "");
        if (token.isEmpty()) {
            throw new IOException("Mailbox token was empty");
        }
        return token;
    }

    private String fetchAccountId() throws IOException {
        JSONObject account = getJson("/me", true);
        String id = account.optString("id", "");
        if (id.isEmpty()) {
            throw new IOException("Mailbox account id missing");
        }
        return id;
    }

    private JSONObject getJson(String path, boolean authenticated) throws IOException {
        String body = getResponseBody(path, authenticated);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Invalid JSON from " + path, e);
        }
    }

    private String getResponseBody(String path, boolean authenticated) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .get()
                .header("Accept", "application/ld+json, application/json;q=0.9, */*;q=0.8")
                .header("User-Agent", "TempMailAutoFill/1.4");
        if (authenticated) {
            builder.header("Authorization", "Bearer " + currentToken);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("GET " + path + " failed: " + response.code() + " " + body);
            }
            return body;
        }
    }

    private JSONObject postJson(String path, JSONObject payload, boolean authenticated) throws IOException {
        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .post(body)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "TempMailAutoFill/1.4");
        if (authenticated) {
            builder.header("Authorization", "Bearer " + currentToken);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("POST " + path + " failed: " + response.code() + " " + responseBody);
            }
            try {
                return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
            } catch (JSONException e) {
                throw new IOException("Invalid JSON from " + path, e);
            }
        }
    }

    private String fetchDownloadedMessage(String messageId) throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/messages/" + messageId + "/download")
                .get()
                .header("Accept", "text/plain, message/rfc822, text/html, */*")
                .header("Authorization", "Bearer " + currentToken)
                .header("User-Agent", "TempMailAutoFill/1.4")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            return response.body() != null ? response.body().string() : null;
        }
    }

    private String extractVerificationCode(String rawContent) {
        if (rawContent == null || rawContent.trim().isEmpty()) {
            return null;
        }

        String normalized = normalizeContent(rawContent);
        if (normalized.isEmpty()) {
            return null;
        }

        VerificationCodeCandidate bestCandidate = null;

        Matcher keywordMatcher = KEYWORD_CODE_PATTERN.matcher(normalized);
        while (keywordMatcher.find()) {
            bestCandidate = chooseBetterCandidate(
                    bestCandidate,
                    sanitizeCandidate(keywordMatcher.group(1)),
                    300,
                    keywordMatcher.start(1)
            );
        }

        Matcher alphanumericKeywordMatcher = ALPHANUMERIC_KEYWORD_CODE_PATTERN.matcher(normalized);
        while (alphanumericKeywordMatcher.find()) {
            bestCandidate = chooseBetterCandidate(
                    bestCandidate,
                    sanitizeCandidate(alphanumericKeywordMatcher.group(1)),
                    250,
                    alphanumericKeywordMatcher.start(1)
            );
        }

        Matcher numericMatcher = NUMERIC_CODE_PATTERN.matcher(normalized);
        while (numericMatcher.find()) {
            String candidate = sanitizeCandidate(numericMatcher.group(1));
            if (candidate == null || candidate.length() < 4 || candidate.length() > 8) {
                continue;
            }

            int score;
            if (candidate.length() == 6) {
                score = 130;
            } else if (candidate.length() == 5) {
                score = 120;
            } else {
                score = 110;
            }

            bestCandidate = chooseBetterCandidate(
                    bestCandidate,
                    candidate,
                    score,
                    numericMatcher.start(1)
            );
        }

        return bestCandidate != null ? bestCandidate.value : null;
    }

    private String extractVerificationCode(JSONObject detail) {
        StringBuilder content = new StringBuilder();
        appendIfPresent(content, detail.optString("subject", ""));
        appendIfPresent(content, detail.optString("intro", ""));
        appendIfPresent(content, detail.optString("text", ""));

        JSONArray htmlBodies = detail.optJSONArray("html");
        if (htmlBodies != null) {
            for (int i = 0; i < htmlBodies.length(); i++) {
                appendIfPresent(content, htmlBodies.optString(i, ""));
            }
        }

        appendIfPresent(content, detail.optString("htmlAsText", ""));
        return extractVerificationCode(content.toString());
    }

    private String normalizeContent(String rawContent) {
        String text = rawContent
                .replace("=\r\n", "")
                .replace("=\n", "")
                .replace("&nbsp;", " ");
        text = Jsoup.parse(text).text();
        return text.replaceAll("\\s+", " ").trim();
    }

    private String sanitizeCandidate(String candidate) {
        if (candidate == null) {
            return null;
        }
        String sanitized = candidate.trim().replaceAll("[^A-Za-z0-9]", "");
        return sanitized.isEmpty() ? null : sanitized;
    }

    private VerificationCodeCandidate chooseBetterCandidate(
            VerificationCodeCandidate current,
            String candidate,
            int score,
            int position
    ) {
        if (candidate == null) {
            return current;
        }

        int adjustedScore = score;
        if (!hasRepeatedSingleCharacter(candidate)) {
            adjustedScore += 5;
        }

        VerificationCodeCandidate next = new VerificationCodeCandidate(candidate, adjustedScore, position);
        if (current == null) {
            return next;
        }
        if (next.score > current.score) {
            return next;
        }
        if (next.score < current.score) {
            return current;
        }
        if (next.position < current.position) {
            return next;
        }
        if (next.position > current.position) {
            return current;
        }
        return next.value.length() > current.value.length() ? next : current;
    }

    private boolean hasRepeatedSingleCharacter(String candidate) {
        if (candidate == null || candidate.length() < 2) {
            return false;
        }

        char first = candidate.charAt(0);
        for (int i = 1; i < candidate.length(); i++) {
            if (candidate.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static final class VerificationCodeCandidate {
        private final String value;
        private final int score;
        private final int position;

        private VerificationCodeCandidate(String value, int score, int position) {
            this.value = value;
            this.score = score;
            this.position = position;
        }
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value.trim());
    }

    public String getCurrentEmail() {
        return currentEmail;
    }

    public String getCurrentProviderName() {
        if (currentProvider == null || currentProvider.isEmpty()) {
            return "Unknown provider";
        }
        return currentProvider;
    }
}
