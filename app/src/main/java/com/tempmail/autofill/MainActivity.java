package com.tempmail.autofill;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
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
    private TextView emailValue;
    private TextView codeValue;
    private TextView lastSyncValue;
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
        emailValue = findViewById(R.id.email_value);
        codeValue = findViewById(R.id.code_value);
        lastSyncValue = findViewById(R.id.last_sync_value);
        MaterialButton btnService = findViewById(R.id.btn_start_service);
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        MaterialButton btnTest = findViewById(R.id.btn_test_webview);

        prefs = getSharedPreferences("auto", MODE_PRIVATE);
        switchAutoFill.setChecked(prefs.getBoolean("enabled", false));
        switchAutoFill.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            if (checked) startEmailFetcher();
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
                startEmailFetcher();
            }
            Toast.makeText(this, R.string.toast_refresh_started, Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> {
            startActivity(new Intent(this, WebViewActivity.class));
        });

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

    private void startEmailFetcher() {
        startService(new Intent(this, EmailFetcherService.class));
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

        emailValue.setText(TextUtils.isEmpty(email) ? getString(R.string.placeholder_email) : email);
        codeValue.setText(TextUtils.isEmpty(code) ? getString(R.string.placeholder_code) : code);
        lastSyncValue.setText(lastSync <= 0
                ? getString(R.string.placeholder_sync)
                : DateFormat.getMediumDateFormat(this).format(new Date(lastSync))
                + " "
                + DateFormat.getTimeFormat(this).format(new Date(lastSync)));
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
}
