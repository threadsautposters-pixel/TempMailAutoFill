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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
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
    private TextView mailboxCountdownValue;
    private TextView lastSyncValue;
    private TextView historyEmpty;
    private LinearLayout historyContainer;
    private SharedPreferences prefs;
    private SwitchMaterial switchAutoCopy;
    private final Handler dashboardHandler = new Handler(Looper.getMainLooper());
    private NestedScrollView mainScroll;
    private View historyAnchor;
    private View automationAnchor;
    private FloatingActionButton fabStartAutomation;
    private AlertDialog accessibilityDialog;
    private final Runnable dashboardTicker = new Runnable() {
        @Override
        public void run() {
            updateDashboard();
            dashboardHandler.postDelayed(this, 1000L);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> updateDashboard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainScroll = findViewById(R.id.main_scroll);
        historyAnchor = findViewById(R.id.anchor_history);
        automationAnchor = findViewById(R.id.anchor_automation);
        BottomAppBar bottomAppBar = findViewById(R.id.bottom_app_bar);
        fabStartAutomation = findViewById(R.id.fab_start_automation);

        switchAutoFill = findViewById(R.id.switch_auto);
        statusBadge = findViewById(R.id.status_badge);
        statusText = findViewById(R.id.status_text);
        accessibilityStatus = findViewById(R.id.accessibility_status);
        serviceStatus = findViewById(R.id.service_status);
        stabilityStatus = findViewById(R.id.stability_status);
        emailValue = findViewById(R.id.email_value);
        codeValue = findViewById(R.id.code_value);
        providerValue = findViewById(R.id.provider_value);
        mailboxCountdownValue = findViewById(R.id.mailbox_countdown_value);
        lastSyncValue = findViewById(R.id.last_sync_value);
        historyEmpty = findViewById(R.id.history_empty);
        historyContainer = findViewById(R.id.history_container);
        MaterialButton btnService = findViewById(R.id.btn_start_service);
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        MaterialButton btnToggleAutomation = findViewById(R.id.btn_toggle_automation);
        MaterialButton btnTest = findViewById(R.id.btn_test_webview);
        MaterialButton btnCopyEmail = findViewById(R.id.btn_copy_email);
        MaterialButton btnCopyCode = findViewById(R.id.btn_copy_code);
        MaterialButton btnClearCode = findViewById(R.id.btn_clear_code);
        MaterialButton btnClearHistory = findViewById(R.id.btn_clear_history);
        MaterialButton btnLifetimePreset = findViewById(R.id.btn_lifetime_preset);
        switchAutoCopy = findViewById(R.id.switch_auto_copy);

        prefs = getSharedPreferences("auto", MODE_PRIVATE);
        switchAutoFill.setChecked(prefs.getBoolean("enabled", false));
        switchAutoCopy.setChecked(prefs.getBoolean("auto_copy_code", false));
        switchAutoFill.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            if (checked) startEmailFetcher(false);
            else stopEmailFetcher();
            if (checked) {
                maybePromptAccessibilityRequired();
            }
            updateDashboard();
        });
        switchAutoCopy.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("auto_copy_code", checked).apply();
            updateDashboard();
        });

        btnService.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnToggleAutomation.setOnClickListener(v -> {
            if (switchAutoFill.isChecked()) {
                switchAutoFill.setChecked(false);
            } else {
                switchAutoFill.setChecked(true);
            }
            updateDashboard();
        });

        if (fabStartAutomation != null) {
            fabStartAutomation.setOnClickListener(v -> {
                if (switchAutoFill.isChecked()) {
                    switchAutoFill.setChecked(false);
                } else {
                    switchAutoFill.setChecked(true);
                }
                updateDashboard();
            });
        }

        if (bottomAppBar != null) {
            bottomAppBar.setOnMenuItemClickListener(item -> handleBottomNav(item.getItemId()));
        }

        btnRefresh.setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean("pending_force_refresh", true)
                    .remove("latest_code")
                    .remove("last_auto_copied_code")
                    .apply();
            if (!switchAutoFill.isChecked()) {
                switchAutoFill.setChecked(true);
            }
            startEmailFetcher(true);
            updateDashboard();
            Toast.makeText(this, R.string.toast_refresh_started, Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> {
            startActivity(new Intent(this, WebViewActivity.class));
        });
        btnLifetimePreset.setOnClickListener(v -> {
            int nextLifetime = getNextLifetimeMinutes();
            prefs.edit().putInt("mailbox_lifetime_minutes", nextLifetime).apply();
            btnLifetimePreset.setText(getLifetimeButtonText(nextLifetime));
            startEmailFetcher(true);
            Toast.makeText(this, R.string.toast_lifetime_updated, Toast.LENGTH_SHORT).show();
        });

        btnCopyEmail.setOnClickListener(v -> copyValue(
                prefs.getString("latest_email", ""),
                R.string.toast_copied_email
        ));

        btnCopyCode.setOnClickListener(v -> copyValue(
                prefs.getString("latest_code", ""),
                R.string.toast_copied_code
        ));

        btnClearCode.setOnClickListener(v -> {
            prefs.edit()
                    .remove("latest_code")
                    .remove("last_auto_copied_code")
                    .apply();
            updateDashboard();
            Toast.makeText(this, R.string.toast_code_cleared, Toast.LENGTH_SHORT).show();
        });

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
        dashboardHandler.post(dashboardTicker);
    }

    @Override
    protected void onStop() {
        dashboardHandler.removeCallbacks(dashboardTicker);
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybePromptAccessibilityRequired();
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
        long mailboxExpiresAt = prefs.getLong("mailbox_expires_at", 0L);
        int mailboxLifetimeMinutes = prefs.getInt("mailbox_lifetime_minutes", 10);

        if (switchAutoFill.isChecked() != enabled) {
            switchAutoFill.setChecked(enabled);
        }
        if (switchAutoCopy.isChecked() != prefs.getBoolean("auto_copy_code", false)) {
            switchAutoCopy.setChecked(prefs.getBoolean("auto_copy_code", false));
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
        mailboxCountdownValue.setText(formatMailboxCountdown(enabled, serviceActive, mailboxExpiresAt));
        MaterialButton btnToggleAutomation = findViewById(R.id.btn_toggle_automation);
        btnToggleAutomation.setText(enabled ? R.string.button_stop_automation : R.string.button_start_automation);
        if (fabStartAutomation != null) {
            fabStartAutomation.setImageResource(enabled ? R.drawable.ic_stop_24 : R.drawable.ic_play_24);
            fabStartAutomation.setContentDescription(getString(
                    enabled ? R.string.button_stop_automation : R.string.nav_start_automation
            ));
        }
        MaterialButton btnLifetimePreset = findViewById(R.id.btn_lifetime_preset);
        btnLifetimePreset.setText(getLifetimeButtonText(mailboxLifetimeMinutes));
        lastSyncValue.setText(lastSync <= 0
                ? getString(R.string.placeholder_sync)
                : DateFormat.getMediumDateFormat(this).format(new Date(lastSync))
                + " "
                + DateFormat.getTimeFormat(this).format(new Date(lastSync)));
        renderHistory();
    }

    private boolean handleBottomNav(int itemId) {
        if (itemId == R.id.nav_home) {
            if (mainScroll != null) {
                mainScroll.smoothScrollTo(0, 0);
            }
            return true;
        }
        if (itemId == R.id.nav_automation) {
            scrollToAnchor(automationAnchor);
            return true;
        }
        if (itemId == R.id.nav_history) {
            scrollToAnchor(historyAnchor);
            return true;
        }
        if (itemId == R.id.nav_settings) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
            return true;
        }
        return false;
    }

    private void scrollToAnchor(View anchor) {
        if (mainScroll == null || anchor == null) {
            return;
        }
        mainScroll.post(() -> mainScroll.smoothScrollTo(0, anchor.getTop()));
    }

    private void maybePromptAccessibilityRequired() {
        boolean enabled = prefs != null && prefs.getBoolean("enabled", false);
        if (!enabled) {
            if (accessibilityDialog != null) {
                accessibilityDialog.dismiss();
                accessibilityDialog = null;
            }
            return;
        }
        if (isAccessibilityServiceEnabled()) {
            if (accessibilityDialog != null) {
                accessibilityDialog.dismiss();
                accessibilityDialog = null;
            }
            return;
        }
        if (accessibilityDialog != null && accessibilityDialog.isShowing()) {
            return;
        }
        accessibilityDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_required_title)
                .setMessage(R.string.accessibility_required_message)
                .setCancelable(false)
                .setPositiveButton(R.string.accessibility_required_open, (dialog, which) -> {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                })
                .setNegativeButton(R.string.accessibility_required_disable, (dialog, which) -> {
                    prefs.edit().putBoolean("enabled", false).apply();
                    switchAutoFill.setChecked(false);
                    stopEmailFetcher();
                    updateDashboard();
                })
                .create();
        accessibilityDialog.show();
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

    private String formatMailboxCountdown(boolean enabled, boolean serviceActive, long mailboxExpiresAt) {
        if (!enabled) {
            return getString(R.string.placeholder_countdown_off);
        }
        if (!serviceActive && mailboxExpiresAt <= 0L) {
            return getString(R.string.placeholder_countdown_waiting);
        }
        if (mailboxExpiresAt <= 0L) {
            return getString(R.string.placeholder_countdown_waiting);
        }

        long remainingMs = mailboxExpiresAt - System.currentTimeMillis();
        if (remainingMs <= 0L) {
            return getString(R.string.placeholder_countdown_refreshing);
        }

        long totalSeconds = remainingMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private int getNextLifetimeMinutes() {
        int current = prefs.getInt("mailbox_lifetime_minutes", 10);
        if (current <= 5) {
            return 10;
        }
        if (current <= 10) {
            return 15;
        }
        return 5;
    }

    private String getLifetimeButtonText(int minutes) {
        return getString(R.string.button_lifetime_format, minutes);
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
