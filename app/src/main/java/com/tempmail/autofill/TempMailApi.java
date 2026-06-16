package com.tempmail.autofill;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.IOException;
import java.util.Random;

public class TempMailApi {
    private static final String[] SERVICES = {
        "https://10minutemail.com/",
        "https://temp-mail.org/en/10minutemail",
        "https://www.minuteinbox.com/"
    };
    private String currentEmail;
    private String currentUrl;

    public String generateNewEmail() throws IOException {
        String service = SERVICES[new Random().nextInt(SERVICES.length)];
        currentUrl = service;
        if (service.equals("https://10minutemail.com/")) {
            Document doc = Jsoup.connect(service).get();
            Element e = doc.selectFirst("#mailAddress");
            if (e != null) {
                currentEmail = e.text();
                return currentEmail;
            }
        }
        currentEmail = "temp" + System.currentTimeMillis() + "@dropmail.me";
        return currentEmail;
    }

    public String fetchVerificationCode() throws IOException {
        if (currentUrl == null) return null;
        if (currentUrl.equals("https://10minutemail.com/")) {
            Document doc = Jsoup.connect(currentUrl).get();
            Element msg = doc.selectFirst(".message a");
            if (msg != null) {
                String msgUrl = msg.absUrl("href");
                Document msgDoc = Jsoup.connect(msgUrl).get();
                String body = msgDoc.body().text();
                String digits = body.replaceAll("[^0-9]", " ").trim();
                if (digits.length() >= 4)
                    return digits.substring(0, Math.min(8, digits.length()));
            }
        }
        return null;
    }
    public String getCurrentEmail() { return currentEmail; }
}
