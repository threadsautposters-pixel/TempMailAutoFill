package com.tempmail.autofill;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private SwitchMaterial switchAutoFill;
    private TextView statusText;
    private TextView statusBadge;
    private TextView accessibilityStatus;
    private TextView serviceStatus;
    private TextView stabilityStatus;
    private TextView emailValue;
    private TextView codeValue;
    private TextView providerValue;
    private TextView lastSyncValue;
    private TextView historyEmpty;
    private LinearLayout historyContainer;
    private SharedPreferences prefs;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> updateDashboard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        switchAutoFill = findViewById(R.id.switch_auto);
        statusBadge = findViewById(R.id.status_badge);
        statusText = findViewById(R.id.status_text);
        accessibilityStatus = findViewById(R.id.accessibility_status);
        serviceStatus = findViewById(R.id.service_status);
        stabilityStatus = findViewById(R.id.stability_status);
        emailValue = findViewById(R.id.email_value);
        codeValue = findViewById(R.id.code_value);
        providerValue = findViewById(R.id.provider_value);
        lastSyncValue = findViewById(R.id.last_sync_value);
        historyEmpty = findViewById(R.id.history_empty);
        historyContainer = findViewById(R.id.history_container);
        MaterialButton btnService = findViewById(R.id.btn_start_service);
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        MaterialButton btnTest = findViewById(R.id.btn_test_webview);
        MaterialButton btnCopyEmail = findViewById(R.id.btn_copy_email);
        MaterialButton btnCopyCode = findViewById(R.id.btn_copy_code);
        MaterialButton btnClearHistory = findViewById(R.id.btn_clear_history);

        prefs = getSharedPreferences("auto", MODE_PRIVATE);
        switchAutoFill.setChecked(prefs.getBoolean("enabled", false));
        switchAutoFill.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            if (checked) startEmailFetcher(false);
            else stopEmailFetcher();
            if (checked && !isAccessibilityServiceEnabled()) {
                Toast.makeText(this, R.string.toast_accessibility_hint, Toast.LENGTH_SHORT).show();
            }
            updateDashboard();
        });

        btnService.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnRefresh.setOnClickListener(v -> {
            if (!switchAutoFill.isChecked()) {
                switchAutoFill.setChecked(true);
            } else {
                startEmailFetcher(true);
            }
            Toast.makeText(this, R.string.toast_refresh_started, Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> {
            startActivity(new Intent(this, WebViewActivity.class));
        });

        btnCopyEmail.setOnClickListener(v -> copyValue(
                prefs.getString("latest_email", ""),
                R.string.toast_copied_email
        ));

        btnCopyCode.setOnClickListener(v -> copyValue(
                prefs.getString("latest_code", ""),
                R.string.toast_copied_code
        ));

        btnClearHistory.setOnClickListener(v -> {
            HistoryStorage.clear(prefs);
            updateDashboard();
            Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show();
        });

        requestNotificationPermissionIfNeeded();
        updateDashboard();
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        updateDashboard();
    }

    @Override
    protected void onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDashboard();
    }

    private void startEmailFetcher(boolean forceRefresh) {
        Intent serviceIntent = new Intent(this, EmailFetcherService.class);
        serviceIntent.putExtra("force_refresh", forceRefresh);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopEmailFetcher() {
        stopService(new Intent(this, EmailFetcherService.class));
    }

    private void updateDashboard() {
        boolean enabled = prefs.getBoolean("enabled", false);
        boolean serviceActive = prefs.getBoolean("polling_active", false);
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        String email = prefs.getString("latest_email", "");
        String code = prefs.getString("latest_code", "");
        String provider = prefs.getString("latest_provider", "");
        String lastError = prefs.getString("last_error", "");
        long lastSync = prefs.getLong("last_sync", 0L);

        if (switchAutoFill.isChecked() != enabled) {
            switchAutoFill.setChecked(enabled);
        }

        if (!enabled) {
            setBadgeState(
                    getString(R.string.status_badge_off),
                    R.color.color_muted_bg,
                    R.color.color_text_secondary
            );
            statusText.setText(R.string.status_summary_off);
        } else if (accessibilityEnabled) {
            setBadgeState(
                    getString(R.string.status_badge_ready),
                    R.color.color_success_bg,
                    R.color.color_success
            );
            statusText.setText(R.string.status_summary_ready);
        } else {
            setBadgeState(
                    getString(R.string.status_badge_setup),
                    R.color.color_warning_bg,
                    R.color.color_warning
            );
            statusText.setText(R.string.status_summary_setup);
        }

        if (!TextUtils.isEmpty(lastError)) {
            statusText.setText(getString(R.string.status_error_prefix) + lastError);
        }

        accessibilityStatus.setText(
                accessibilityEnabled
                        ? R.string.value_accessibility_on
                        : R.string.value_accessibility_off
        );
        accessibilityStatus.setTextColor(ContextCompat.getColor(
                this,
                accessibilityEnabled ? R.color.color_success : R.color.color_warning
        ));

        serviceStatus.setText(serviceActive ? R.string.value_service_on : R.string.value_service_off);
        serviceStatus.setTextColor(ContextCompat.getColor(
                this,
                serviceActive ? R.color.color_success : R.color.color_text_primary
        ));

        stabilityStatus.setText(R.string.value_stability);
        emailValue.setText(TextUtils.isEmpty(email) ? getString(R.string.placeholder_email) : email);
        codeValue.setText(TextUtils.isEmpty(code) ? getString(R.string.placeholder_code) : code);
        providerValue.setText(TextUtils.isEmpty(provider) ? getString(R.string.placeholder_provider) : provider);
        lastSyncValue.setText(lastSync <= 0
                ? getString(R.string.placeholder_sync)
                : DateFormat.getMediumDateFormat(this).format(new Date(lastSync))
                + " "
                + DateFormat.getTimeFormat(this).format(new Date(lastSync)));
        renderHistory();
    }

    private void setBadgeState(String text, int backgroundColorRes, int textColorRes) {
        statusBadge.setText(text);
        statusBadge.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, backgroundColorRes)
        ));
        statusBadge.setTextColor(ContextCompat.getColor(this, textColorRes));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }

        String expected = new ComponentName(this, AutoFillService.class)
                .flattenToString()
                .toLowerCase(Locale.US);
        return enabledServices.toLowerCase(Locale.US).contains(expected);
    }

    private void copyValue(String value, int successMessageRes) {
        if (TextUtils.isEmpty(value)) {
            Toast.makeText(this, R.string.toast_copy_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboardManager = ContextCompat.getSystemService(this, ClipboardManager.class);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), value));
        }
        Toast.makeText(this, successMessageRes, Toast.LENGTH_SHORT).show();
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        java.util.List<HistoryStorage.HistoryEntry> historyEntries = HistoryStorage.getHistory(prefs);
        boolean hasItems = !historyEntries.isEmpty();
        historyEmpty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
        historyContainer.setVisibility(hasItems ? View.VISIBLE : View.GONE);

        for (HistoryStorage.HistoryEntry entry : historyEntries) {
            historyContainer.addView(createHistoryItemView(entry));
        }
    }

    private View createHistoryItemView(HistoryStorage.HistoryEntry entry) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackgroundResource(R.drawable.bg_history_item);
        int padding = dpToPx(16);
        item.setPadding(padding, padding, padding, padding);

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        layoutParams.bottomMargin = dpToPx(10);
        item.setLayoutParams(layoutParams);

        TextView title = new TextView(this);
        title.setText(entry.email);
        title.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(
                R.string.history_provider_format,
                TextUtils.isEmpty(entry.provider) ? getString(R.string.placeholder_provider) : entry.provider,
                entry.timestamp <= 0 ? getString(R.string.placeholder_sync) : formatTimestamp(entry.timestamp)
        ));
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dpToPx(6), 0, 0);

        item.addView(title);
        item.addView(subtitle);
        item.setOnClickListener(v -> copyValue(entry.email, R.string.toast_copied_email));
        return item;
    }

    private String formatTimestamp(long timestamp) {
        Date date = new Date(timestamp);
        return DateFormat.getMediumDateFormat(this).format(date)
                + " "
                + DateFormat.getTimeFormat(this).format(date);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }
}
