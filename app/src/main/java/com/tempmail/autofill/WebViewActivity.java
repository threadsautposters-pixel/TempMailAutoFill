package com.tempmail.autofill;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class WebViewActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        setTitle(R.string.webview_title);

        webView.loadDataWithBaseURL(
                "https://local.tempmail.autofill/",
                buildDemoPage(),
                "text/html",
                "UTF-8",
                null
        );
        setContentView(webView);
    }

    private String buildDemoPage() {
        return "<!DOCTYPE html>"
                + "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />"
                + "<style>"
                + "body{font-family:sans-serif;background:#f4f7fb;color:#0f172a;padding:24px;}"
                + ".card{max-width:520px;margin:0 auto;background:#ffffff;border-radius:24px;padding:24px;"
                + "box-shadow:0 10px 30px rgba(15,23,42,0.08);}"
                + "h1{font-size:28px;margin:0 0 8px;}"
                + "p{color:#475569;line-height:1.5;}"
                + "label{display:block;margin:16px 0 8px;font-weight:700;}"
                + "input{width:100%;padding:14px 16px;border:1px solid #cbd5e1;border-radius:14px;"
                + "font-size:16px;box-sizing:border-box;}"
                + ".otp-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;margin-top:8px;}"
                + ".otp-grid input{text-align:center;padding:14px 0;}"
                + "button{margin-top:20px;width:100%;padding:14px 16px;border:none;border-radius:14px;"
                + "background:#4f46e5;color:white;font-size:16px;font-weight:700;}"
                + ".hint{margin-top:14px;font-size:14px;color:#64748b;}"
                + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1>Create your account</h1>"
                + "<p>This local demo page helps you test whether the accessibility autofill service can detect and fill email and verification code fields.</p>"
                + "<label for=\"email\">Email address</label>"
                + "<input id=\"email\" name=\"email\" type=\"email\" placeholder=\"Enter your email\" />"
                + "<label for=\"verification_code\">Verification code</label>"
                + "<input id=\"verification_code\" name=\"verification_code\" inputmode=\"numeric\" placeholder=\"Enter verification code\" />"
                + "<label for=\"password\">Create password</label>"
                + "<input id=\"password\" name=\"password\" type=\"password\" placeholder=\"Create password\" />"
                + "<label for=\"confirm_password\">Confirm password</label>"
                + "<input id=\"confirm_password\" name=\"confirm_password\" type=\"password\" placeholder=\"Repeat password\" />"
                + "<label>Segmented verification code</label>"
                + "<div class=\"otp-grid\">"
                + "<input id=\"otp_1\" name=\"otp_1\" inputmode=\"numeric\" maxlength=\"1\" placeholder=\"0\" />"
                + "<input id=\"otp_2\" name=\"otp_2\" inputmode=\"numeric\" maxlength=\"1\" placeholder=\"0\" />"
                + "<input id=\"otp_3\" name=\"otp_3\" inputmode=\"numeric\" maxlength=\"1\" placeholder=\"0\" />"
                + "<input id=\"otp_4\" name=\"otp_4\" inputmode=\"numeric\" maxlength=\"1\" placeholder=\"0\" />"
                + "<input id=\"otp_5\" name=\"otp_5\" inputmode=\"numeric\" maxlength=\"1\" placeholder=\"0\" />"
                + "<input id=\"otp_6\" name=\"otp_6\" inputmode=\"numeric\" maxlength=\"1\" placeholder=\"0\" />"
                + "</div>"
                + "<button type=\"button\">Continue</button>"
                + "<div class=\"hint\">Tip: keep the app enabled, then return to this screen after a mailbox has been generated.</div>"
                + "</div></body></html>";
    }
}
