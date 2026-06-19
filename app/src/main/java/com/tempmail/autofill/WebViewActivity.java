package com.tempmail.autofill;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class WebViewActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    public static final String MODE_DEMO = "demo";
    public static final String MODE_PROVIDER_HUB = "provider_hub";
    public static final String MODE_EXTERNAL_URL = "external_url";

    private WebView webView;

    public static Intent createDemoIntent(Context context) {
        return new Intent(context, WebViewActivity.class).putExtra(EXTRA_MODE, MODE_DEMO);
    }

    public static Intent createProviderHubIntent(Context context) {
        return new Intent(context, WebViewActivity.class).putExtra(EXTRA_MODE, MODE_PROVIDER_HUB);
    }

    public static Intent createBrowserIntent(Context context, String url, String title) {
        return new Intent(context, WebViewActivity.class)
                .putExtra(EXTRA_MODE, MODE_EXTERNAL_URL)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        configureWebView(webView);
        setContentView(webView);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        loadInitialContent();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void configureWebView(WebView browser) {
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBuiltInZoomControls(false);
    }

    private void loadInitialContent() {
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (TextUtils.isEmpty(mode) || MODE_DEMO.equals(mode)) {
            setTitle(R.string.webview_title);
            webView.loadDataWithBaseURL(
                    "https://local.tempmail.autofill/",
                    buildDemoPage(),
                    "text/html",
                    "UTF-8",
                    null
            );
            return;
        }
        if (MODE_PROVIDER_HUB.equals(mode)) {
            setTitle(R.string.provider_hub_title);
            webView.loadDataWithBaseURL(
                    "https://local.tempmail.autofill/providers/",
                    buildProviderHubPage(),
                    "text/html",
                    "UTF-8",
                    null
            );
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String url = getIntent().getStringExtra(EXTRA_URL);
        setTitle(TextUtils.isEmpty(title) ? getString(R.string.browser_title_default) : title);
        if (TextUtils.isEmpty(url)) {
            webView.loadData("<html><body></body></html>", "text/html", "UTF-8");
            return;
        }
        webView.loadUrl(url);
    }

    private String buildProviderHubPage() {
        StringBuilder cards = new StringBuilder();
        List<ProviderPreset> presets = ProviderPreset.getBrowserPresets();
        for (ProviderPreset preset : presets) {
            cards.append("<a class=\"provider\" href=\"")
                    .append(preset.url)
                    .append("\">")
                    .append("<div class=\"provider-name\">")
                    .append(preset.name)
                    .append("</div>")
                    .append("<div class=\"provider-url\">")
                    .append(preset.url)
                    .append("</div>")
                    .append("</a>");
        }

        return "<!DOCTYPE html>"
                + "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />"
                + "<style>"
                + "body{font-family:sans-serif;background:#0b1220;color:#e2e8f0;padding:18px;}"
                + ".shell{max-width:860px;margin:0 auto;}"
                + ".hero,.section{background:#111827;border:1px solid #1f2937;border-radius:22px;padding:20px;"
                + "box-shadow:0 14px 38px rgba(2,6,23,0.3);}"
                + ".section{margin-top:16px;}"
                + "h1{font-size:28px;margin:0 0 10px;}"
                + "p{color:#94a3b8;line-height:1.55;margin:0;}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px;margin-top:18px;}"
                + ".provider{display:block;text-decoration:none;background:#0f172a;border:1px solid #23304a;"
                + "border-radius:18px;padding:16px;color:#e2e8f0;}"
                + ".provider-name{font-size:16px;font-weight:700;}"
                + ".provider-url{margin-top:8px;font-size:12px;color:#60a5fa;word-break:break-word;}"
                + ".tag{display:inline-block;margin-top:14px;padding:8px 12px;border-radius:999px;background:#172554;"
                + "color:#bfdbfe;font-size:12px;font-weight:700;}"
                + ".meta{margin-top:12px;color:#cbd5e1;font-size:13px;}"
                + "</style></head><body>"
                + "<div class=\"shell\">"
                + "<div class=\"hero\">"
                + "<h1>Provider Hub</h1>"
                + "<p>Switch between temp mail providers inside the app so verification browsing stays in one place. "
                + "Use this hub for website-based providers, and keep the API provider setting for automated mailbox polling.</p>"
                + "<div class=\"tag\">"
                + presets.size()
                + " browser-ready providers"
                + "</div>"
                + "<div class=\"meta\">Tap any provider below to open it in the in-app browser.</div>"
                + "</div>"
                + "<div class=\"section\">"
                + "<div class=\"grid\">"
                + cards
                + "</div>"
                + "</div>"
                + "</div></body></html>";
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
