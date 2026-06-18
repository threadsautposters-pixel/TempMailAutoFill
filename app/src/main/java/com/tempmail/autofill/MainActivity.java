package com.tempmail.autofill;

import android.Manifest;
import android.content.ActivityNotFoundException;
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
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
    private SwitchMaterial switchAggressiveSignup;
    private TextView statusText;
    private TextView statusBadge;
    private TextView accessibilityStatus;
    private TextView serviceStatus;
    private TextView stabilityStatus;
    private TextView emailValue;
    private TextView codeValue;
    private TextView linkValue;
    private TextView passwordValue;
    private TextView providerValue;
    private TextView mailboxCountdownValue;
    private TextView lastSyncValue;
    private TextView historyEmpty;
    private TextView inboxEmpty;
    private LinearLayout historyContainer;
    private LinearLayout inboxContainer;
    private SharedPreferences prefs;
    private SwitchMaterial switchAutoCopy;
    private SwitchMaterial switchOtpAlerts;
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
        linkValue = findViewById(R.id.link_value);
        passwordValue = findViewById(R.id.password_value);
        providerValue = findViewById(R.id.provider_value);
        mailboxCountdownValue = findViewById(R.id.mailbox_countdown_value);
        lastSyncValue = findViewById(R.id.last_sync_value);
        historyEmpty = findViewById(R.id.history_empty);
        inboxEmpty = findViewById(R.id.inbox_empty);
        historyContainer = findViewById(R.id.history_container);
        inboxContainer = findViewById(R.id.inbox_container);
        MaterialButton btnService = findViewById(R.id.btn_start_service);
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        MaterialButton btnToggleAutomation = findViewById(R.id.btn_toggle_automation);
        MaterialButton btnTest = findViewById(R.id.btn_test_webview);
        MaterialButton btnCopyEmail = findViewById(R.id.btn_copy_email);
        MaterialButton btnCopyCode = findViewById(R.id.btn_copy_code);
        MaterialButton btnCopyLink = findViewById(R.id.btn_copy_link);
        MaterialButton btnOpenLink = findViewById(R.id.btn_open_link);
        MaterialButton btnClearCode = findViewById(R.id.btn_clear_code);
        MaterialButton btnClearHistory = findViewById(R.id.btn_clear_history);
        MaterialButton btnLifetimePreset = findViewById(R.id.btn_lifetime_preset);
        MaterialButton btnSetPassword = findViewById(R.id.btn_set_password);
        MaterialButton btnVerificationToolkit = findViewById(R.id.btn_verification_toolkit);
        switchAutoCopy = findViewById(R.id.switch_auto_copy);
        switchOtpAlerts = findViewById(R.id.switch_otp_alerts);
        switchAggressiveSignup = findViewById(R.id.switch_aggressive_signup);

        prefs = getSharedPreferences("auto", MODE_PRIVATE);
        switchAutoFill.setChecked(prefs.getBoolean("enabled", false));
        switchAutoCopy.setChecked(prefs.getBoolean("auto_copy_code", false));
        switchOtpAlerts.setChecked(prefs.getBoolean("otp_detect_enabled", true));
        switchAggressiveSignup.setChecked(prefs.getBoolean("aggressive_signup_autofill", false));
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
        switchOtpAlerts.setOnCheckedChangeListener((buttonView, checked) -> {
            SharedPreferences.Editor editor = prefs.edit().putBoolean("otp_detect_enabled", checked);
            if (!checked) {
                editor.remove("last_notified_code");
                editor.remove("last_notified_link");
            }
            editor.apply();
            updateDashboard();
        });
        switchAggressiveSignup.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("aggressive_signup_autofill", checked).apply();
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
                    .remove("latest_link")
                    .remove("last_auto_copied_code")
                    .remove("last_notified_code")
                    .remove("last_notified_link")
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
        emailValue.setOnClickListener(v -> copyValue(
                prefs.getString("latest_email", ""),
                R.string.toast_copied_email
        ));

        btnCopyCode.setOnClickListener(v -> copyValue(
                prefs.getString("latest_code", ""),
                R.string.toast_copied_code
        ));
        codeValue.setOnClickListener(v -> copyValue(
                prefs.getString("latest_code", ""),
                R.string.toast_copied_code
        ));
        btnCopyLink.setOnClickListener(v -> copyValue(
                prefs.getString("latest_link", ""),
                R.string.toast_copied_link
        ));
        btnOpenLink.setOnClickListener(v -> openLatestLink());
        linkValue.setOnClickListener(v -> openLatestLink());
        btnSetPassword.setOnClickListener(v -> showPasswordDialog());
        btnVerificationToolkit.setOnClickListener(v -> showVerificationToolkitDialog());

        btnClearCode.setOnClickListener(v -> {
            prefs.edit()
                    .remove("latest_code")
                    .remove("latest_link")
                    .remove("last_auto_copied_code")
                    .remove("last_notified_code")
                    .remove("last_notified_link")
                    .apply();
            updateDashboard();
            Toast.makeText(this, R.string.toast_code_cleared, Toast.LENGTH_SHORT).show();
        });

        btnClearHistory.setOnClickListener(v -> {
            HistoryStorage.clear(prefs);
            updateDashboard();
            Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show();
        });

        providerValue.setOnClickListener(v -> showProviderDialog());

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
        String link = prefs.getString("latest_link", "");
        String provider = prefs.getString("latest_provider", "");
        String savedPassword = prefs.getString("saved_password", "");
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
        if (switchOtpAlerts.isChecked() != prefs.getBoolean("otp_detect_enabled", true)) {
            switchOtpAlerts.setChecked(prefs.getBoolean("otp_detect_enabled", true));
        }
        if (switchAggressiveSignup.isChecked() != prefs.getBoolean("aggressive_signup_autofill", false)) {
            switchAggressiveSignup.setChecked(prefs.getBoolean("aggressive_signup_autofill", false));
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
        codeValue.setText(TextUtils.isEmpty(code) ? getString(R.string.placeholder_code) : formatCodeForDisplay(code));
        linkValue.setText(TextUtils.isEmpty(link) ? getString(R.string.placeholder_link) : link);
        passwordValue.setText(formatSavedPasswordSummary(savedPassword));
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
        renderInbox();
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

    private void renderInbox() {
        inboxContainer.removeAllViews();
        java.util.List<InboxStorage.InboxEntry> inboxEntries = InboxStorage.getInbox(prefs);
        boolean hasItems = !inboxEntries.isEmpty();
        inboxEmpty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
        inboxContainer.setVisibility(hasItems ? View.VISIBLE : View.GONE);

        for (InboxStorage.InboxEntry entry : inboxEntries) {
            inboxContainer.addView(createInboxItemView(entry));
        }
    }

    private View createInboxItemView(InboxStorage.InboxEntry entry) {
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
        title.setText(TextUtils.isEmpty(entry.subject) ? getString(R.string.card_inbox_title) : entry.subject);
        title.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText(buildInboxMetadata(entry));
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dpToPx(6), 0, 0);

        TextView preview = new TextView(this);
        preview.setText(buildInboxPreview(entry));
        preview.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary));
        preview.setTextSize(13);
        preview.setPadding(0, dpToPx(6), 0, 0);

        item.addView(title);
        item.addView(subtitle);
        item.addView(preview);
        if (entry.hasActionableValue()) {
            TextView helper = new TextView(this);
            helper.setText(buildInboxActionHint(entry));
            helper.setTextColor(ContextCompat.getColor(this, R.color.color_primary));
            helper.setTextSize(12);
            helper.setPadding(0, dpToPx(10), 0, 0);
            item.addView(helper);
            item.addView(createInboxActionRow(entry));
            item.setOnClickListener(v -> applyInboxEntry(entry, false));
        }
        return item;
    }

    private String buildInboxPreview(InboxStorage.InboxEntry entry) {
        if (entry.hasLink() && entry.hasCode()) {
            return getString(R.string.inbox_code_format, entry.code) + " • " + getString(R.string.inbox_link_available);
        }
        if (entry.hasLink()) {
            if (!TextUtils.isEmpty(entry.preview)) {
                return entry.preview + "\n" + getString(R.string.inbox_link_available);
            }
            return getString(R.string.inbox_link_available);
        }
        if (entry.hasCode()) {
            if (!TextUtils.isEmpty(entry.preview)) {
                return entry.preview + "\n" + getString(R.string.inbox_code_format, entry.code);
            }
            return getString(R.string.inbox_code_format, entry.code);
        }
        if (!TextUtils.isEmpty(entry.preview)) {
            return entry.preview;
        }
        return TextUtils.isEmpty(entry.timestamp) ? getString(R.string.inbox_timestamp_unknown) : entry.timestamp;
    }

    private String buildInboxMetadata(InboxStorage.InboxEntry entry) {
        String fromText = TextUtils.isEmpty(entry.from) ? "" : getString(R.string.inbox_from_format, entry.from);
        String timeText = formatInboxTimestamp(entry.timestamp);
        if (!TextUtils.isEmpty(fromText) && !TextUtils.isEmpty(timeText)) {
            return fromText + " • " + timeText;
        }
        if (!TextUtils.isEmpty(fromText)) {
            return fromText;
        }
        if (!TextUtils.isEmpty(timeText)) {
            return timeText;
        }
        return getString(R.string.inbox_timestamp_unknown);
    }

    private String buildInboxActionHint(InboxStorage.InboxEntry entry) {
        if (entry.hasLink() && entry.hasCode()) {
            return getString(R.string.inbox_action_hint_link_code);
        }
        if (entry.hasLink()) {
            return getString(R.string.inbox_action_hint_link);
        }
        return getString(R.string.inbox_action_hint_code);
    }

    private View createInboxActionRow(InboxStorage.InboxEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dpToPx(12), 0, 0);

        row.addView(createInboxActionButton(
                R.string.button_use_now,
                v -> applyInboxEntry(entry, false)
        ));

        if (entry.hasLink()) {
            row.addView(createInboxActionButton(
                    R.string.button_open_link,
                    v -> openInboxLink(entry)
            ));
        }

        if (entry.hasCode()) {
            row.addView(createInboxActionButton(
                    R.string.button_copy_code,
                    v -> copyValue(entry.code, R.string.toast_copied_code)
            ));
        }

        return row;
    }

    private MaterialButton createInboxActionButton(int textRes, View.OnClickListener onClickListener) {
        MaterialButton button = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.rightMargin = dpToPx(8);
        button.setLayoutParams(params);
        button.setText(textRes);
        button.setOnClickListener(onClickListener);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private void applyInboxEntry(InboxStorage.InboxEntry entry, boolean silent) {
        if (prefs == null || entry == null || !entry.hasActionableValue()) {
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        boolean updated = false;
        if (entry.hasCode()) {
            editor.putString("latest_code", entry.code.trim());
            updated = true;
        }
        if (entry.hasLink()) {
            editor.putString("latest_link", entry.link.trim());
            updated = true;
        }
        if (!updated) {
            return;
        }
        editor.apply();
        updateDashboard();
        if (!silent) {
            int messageRes = entry.hasLink() && entry.hasCode()
                    ? R.string.toast_inbox_applied
                    : entry.hasLink()
                    ? R.string.toast_inbox_link_applied
                    : R.string.toast_inbox_code_applied;
            Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
        }
    }

    private void openInboxLink(InboxStorage.InboxEntry entry) {
        if (entry == null || !entry.hasLink()) {
            openLink(null);
            return;
        }
        applyInboxEntry(entry, true);
        openLink(entry.link);
    }

    private String formatInboxTimestamp(String rawTimestamp) {
        if (TextUtils.isEmpty(rawTimestamp)) {
            return "";
        }
        String value = rawTimestamp.trim().replace('T', ' ');
        int dotIndex = value.indexOf('.');
        if (dotIndex >= 0) {
            value = value.substring(0, dotIndex);
        }
        if (value.endsWith("Z")) {
            value = value.substring(0, value.length() - 1).trim() + " UTC";
        }
        if (value.length() > 19 && !value.endsWith("UTC")) {
            value = value.substring(0, 19);
        }
        return value;
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

    private String formatCodeForDisplay(String code) {
        if (TextUtils.isEmpty(code)) {
            return getString(R.string.placeholder_code);
        }

        String trimmed = code.trim();
        if (!trimmed.matches("\\d{6,8}")) {
            return trimmed;
        }

        StringBuilder builder = new StringBuilder(trimmed.length() + 2);
        for (int i = 0; i < trimmed.length(); i++) {
            if (i > 0 && i % 3 == 0) {
                builder.append(' ');
            }
            builder.append(trimmed.charAt(i));
        }
        return builder.toString();
    }

    private String getLifetimeButtonText(int minutes) {
        return getString(R.string.button_lifetime_format, minutes);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void showVerificationToolkitDialog() {
        if (prefs == null) {
            return;
        }

        java.util.List<InboxStorage.InboxEntry> inboxEntries = InboxStorage.getInbox(prefs);
        InboxStorage.InboxEntry bestEntry = findBestToolkitEntry(inboxEntries);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(20);
        layout.setPadding(padding, padding, padding, padding);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.toolkit_subtitle);
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary));
        subtitle.setTextSize(14);

        TextView status = new TextView(this);
        status.setText(hasCurrentMailbox()
                ? R.string.toolkit_status_ready
                : R.string.toolkit_status_empty);
        status.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary));
        status.setTextSize(14);
        status.setPadding(0, dpToPx(14), 0, 0);

        TextView stats = new TextView(this);
        stats.setText(buildToolkitStatusBlock(inboxEntries, bestEntry));
        stats.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary));
        stats.setTextSize(14);
        stats.setPadding(0, dpToPx(16), 0, 0);

        layout.addView(subtitle);
        layout.addView(status);
        layout.addView(stats);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dpToPx(18), 0, 0);
        final AlertDialog[] dialogHolder = new AlertDialog[1];

        actions.addView(createToolkitButton(R.string.button_apply_best, v -> {
            if (!applyBestToolkitEntry(inboxEntries)) {
                Toast.makeText(this, R.string.toast_toolkit_no_action, Toast.LENGTH_SHORT).show();
            }
        }));
        actions.addView(createToolkitButton(R.string.button_open_best_link, v -> {
            if (!openBestToolkitLink(inboxEntries)) {
                Toast.makeText(this, R.string.toast_toolkit_no_action, Toast.LENGTH_SHORT).show();
            }
        }));
        actions.addView(createToolkitButton(R.string.button_copy_pack, v -> {
            copyValue(buildVerificationPack(inboxEntries), R.string.toast_pack_copied);
        }));
        actions.addView(createToolkitButton(R.string.button_share_pack, v -> {
            shareVerificationPack(inboxEntries);
        }));
        actions.addView(createToolkitButton(R.string.button_clear_inbox, v -> {
            InboxStorage.clear(prefs);
            updateDashboard();
            Toast.makeText(this, R.string.toast_inbox_cleared, Toast.LENGTH_SHORT).show();
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
        }));

        layout.addView(actions);
        dialogHolder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.toolkit_title)
                .setView(layout)
                .setPositiveButton(R.string.provider_dialog_cancel, null)
                .create();
        dialogHolder[0].show();
    }

    private MaterialButton createToolkitButton(int textRes, View.OnClickListener onClickListener) {
        MaterialButton button = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dpToPx(10);
        button.setLayoutParams(params);
        button.setText(textRes);
        button.setOnClickListener(onClickListener);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private String buildToolkitStatusBlock(
            java.util.List<InboxStorage.InboxEntry> inboxEntries,
            InboxStorage.InboxEntry bestEntry
    ) {
        int linkCount = 0;
        int codeCount = 0;
        int actionableCount = 0;
        for (InboxStorage.InboxEntry entry : inboxEntries) {
            if (entry == null) {
                continue;
            }
            if (entry.hasActionableValue()) {
                actionableCount++;
            }
            if (entry.hasLink()) {
                linkCount++;
            }
            if (entry.hasCode()) {
                codeCount++;
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.toolkit_messages_format, inboxEntries.size(), actionableCount));
        builder.append('\n');
        builder.append(getString(R.string.toolkit_links_format, linkCount));
        builder.append('\n');
        builder.append(getString(R.string.toolkit_codes_format, codeCount));
        builder.append('\n');
        builder.append(resolveToolkitBestAction(bestEntry));
        builder.append('\n');
        if (bestEntry == null || TextUtils.isEmpty(bestEntry.subject)) {
            builder.append(getString(R.string.toolkit_selected_message_fallback));
        } else {
            builder.append(getString(R.string.toolkit_selected_message, bestEntry.subject));
        }
        return builder.toString();
    }

    private String resolveToolkitBestAction(InboxStorage.InboxEntry bestEntry) {
        if (bestEntry == null) {
            return getString(R.string.toolkit_best_action_none);
        }
        if (bestEntry.hasLink()) {
            return getString(R.string.toolkit_best_action_link);
        }
        if (bestEntry.hasCode()) {
            return getString(R.string.toolkit_best_action_code);
        }
        return getString(R.string.toolkit_best_action_none);
    }

    private InboxStorage.InboxEntry findBestToolkitEntry(java.util.List<InboxStorage.InboxEntry> inboxEntries) {
        if (inboxEntries == null || inboxEntries.isEmpty()) {
            return null;
        }

        InboxStorage.InboxEntry bestCodeEntry = null;
        for (InboxStorage.InboxEntry entry : inboxEntries) {
            if (entry == null || !entry.hasActionableValue()) {
                continue;
            }
            if (entry.hasLink()) {
                return entry;
            }
            if (bestCodeEntry == null && entry.hasCode()) {
                bestCodeEntry = entry;
            }
        }
        return bestCodeEntry;
    }

    private boolean applyBestToolkitEntry(java.util.List<InboxStorage.InboxEntry> inboxEntries) {
        InboxStorage.InboxEntry bestEntry = findBestToolkitEntry(inboxEntries);
        if (bestEntry == null) {
            return false;
        }
        applyInboxEntry(bestEntry, false);
        return true;
    }

    private boolean openBestToolkitLink(java.util.List<InboxStorage.InboxEntry> inboxEntries) {
        InboxStorage.InboxEntry bestEntry = findBestToolkitEntry(inboxEntries);
        if (bestEntry == null || !bestEntry.hasLink()) {
            return false;
        }
        openInboxLink(bestEntry);
        return true;
    }

    private boolean hasCurrentMailbox() {
        return prefs != null && !TextUtils.isEmpty(prefs.getString("latest_email", ""));
    }

    private String buildVerificationPack(java.util.List<InboxStorage.InboxEntry> inboxEntries) {
        String email = prefs.getString("latest_email", "");
        String code = prefs.getString("latest_code", "");
        String link = prefs.getString("latest_link", "");
        String provider = prefs.getString("latest_provider", "");
        String password = prefs.getString("saved_password", "");
        long lastSync = prefs.getLong("last_sync", 0L);

        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.app_name)).append('\n');
        builder.append("Email: ").append(TextUtils.isEmpty(email) ? "-" : email).append('\n');
        builder.append("Provider: ").append(TextUtils.isEmpty(provider) ? "-" : provider).append('\n');
        builder.append("Latest code: ").append(TextUtils.isEmpty(code) ? "-" : code).append('\n');
        builder.append("Latest link: ").append(TextUtils.isEmpty(link) ? "-" : link).append('\n');
        builder.append("Saved password: ").append(TextUtils.isEmpty(password) ? "-" : formatSavedPasswordSummary(password)).append('\n');
        builder.append("Last sync: ").append(lastSync <= 0 ? getString(R.string.placeholder_sync) : formatTimestamp(lastSync)).append('\n');

        InboxStorage.InboxEntry bestEntry = findBestToolkitEntry(inboxEntries);
        builder.append(resolveToolkitBestAction(bestEntry)).append('\n');
        builder.append(getString(R.string.toolkit_messages_format, inboxEntries.size(), countActionableEntries(inboxEntries))).append('\n');

        int limit = Math.min(inboxEntries.size(), 3);
        for (int i = 0; i < limit; i++) {
            InboxStorage.InboxEntry entry = inboxEntries.get(i);
            builder.append('\n').append("Inbox ").append(i + 1).append(": ");
            builder.append(TextUtils.isEmpty(entry.subject) ? getString(R.string.card_inbox_title) : entry.subject);
            if (entry.hasCode()) {
                builder.append('\n').append("Code: ").append(entry.code);
            }
            if (entry.hasLink()) {
                builder.append('\n').append("Link: ").append(entry.link);
            }
            if (!TextUtils.isEmpty(entry.preview)) {
                builder.append('\n').append("Preview: ").append(entry.preview);
            }
        }
        return builder.toString().trim();
    }

    private int countActionableEntries(java.util.List<InboxStorage.InboxEntry> inboxEntries) {
        int count = 0;
        for (InboxStorage.InboxEntry entry : inboxEntries) {
            if (entry != null && entry.hasActionableValue()) {
                count++;
            }
        }
        return count;
    }

    private void shareVerificationPack(java.util.List<InboxStorage.InboxEntry> inboxEntries) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.toolkit_title));
            shareIntent.putExtra(Intent.EXTRA_TEXT, buildVerificationPack(inboxEntries));
            startActivity(Intent.createChooser(shareIntent, getString(R.string.toolkit_title)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.toast_share_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void showProviderDialog() {
        if (prefs == null) {
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(16);
        layout.setPadding(padding, padding, padding, padding);

        EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.provider_dialog_name_hint);
        nameInput.setSingleLine(true);
        nameInput.setText(prefs.getString("provider_name", TempMailApi.DEFAULT_PROVIDER_NAME));

        EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.provider_dialog_url_hint);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(prefs.getString("provider_base_url", TempMailApi.DEFAULT_BASE_URL));

        layout.addView(nameInput);
        layout.addView(urlInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.provider_dialog_title)
                .setView(layout)
                .setPositiveButton(R.string.provider_dialog_save, (dialog, which) -> {
                    String providerName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    String baseUrl = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();

                    SharedPreferences.Editor editor = prefs.edit()
                            .putBoolean("pending_force_refresh", true)
                            .remove("latest_code")
                            .remove("latest_link")
                            .remove("last_auto_copied_code")
                            .remove("last_notified_link");

                    if (TextUtils.isEmpty(baseUrl)) {
                        editor.remove("provider_base_url");
                        editor.remove("provider_name");
                    } else {
                        editor.putString("provider_base_url", normalizeBaseUrl(baseUrl));
                        if (TextUtils.isEmpty(providerName)) {
                            editor.remove("provider_name");
                        } else {
                            editor.putString("provider_name", providerName);
                        }
                    }

                    editor.apply();
                    if (prefs.getBoolean("enabled", false)) {
                        startEmailFetcher(true);
                    }
                    updateDashboard();
                    Toast.makeText(this, R.string.toast_provider_updated, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.provider_dialog_cancel, null)
                .show();
    }

    private void showPasswordDialog() {
        if (prefs == null) {
            return;
        }

        EditText passwordInput = new EditText(this);
        int padding = dpToPx(16);
        passwordInput.setPadding(padding, padding, padding, padding);
        passwordInput.setHint(R.string.password_dialog_hint);
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setText(prefs.getString("saved_password", ""));

        new AlertDialog.Builder(this)
                .setTitle(R.string.password_dialog_title)
                .setView(passwordInput)
                .setPositiveButton(R.string.password_dialog_save, (dialog, which) -> {
                    String password = passwordInput.getText() == null
                            ? ""
                            : passwordInput.getText().toString();
                    prefs.edit().putString("saved_password", password.trim()).apply();
                    updateDashboard();
                    Toast.makeText(this, R.string.toast_password_saved, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.password_dialog_clear, (dialog, which) -> {
                    prefs.edit().remove("saved_password").apply();
                    updateDashboard();
                })
                .setNegativeButton(R.string.provider_dialog_cancel, null)
                .show();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void openLatestLink() {
        openLink(prefs.getString("latest_link", ""));
    }

    private void openLink(String link) {
        if (TextUtils.isEmpty(link)) {
            Toast.makeText(this, R.string.toast_open_link_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String formatSavedPasswordSummary(String password) {
        if (TextUtils.isEmpty(password)) {
            return getString(R.string.placeholder_password);
        }
        return getString(R.string.password_mask_format, password.length());
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
