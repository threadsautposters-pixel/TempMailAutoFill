package com.tempmail.autofill;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class EmailFetcherService extends Service {
    public static final String ACTION_FORCE_REFRESH = "com.tempmail.autofill.action.FORCE_REFRESH";

    private static final String CHANNEL_ID = "mailbox_monitoring";
    private static final String OTP_CHANNEL_ID = "otp_codes";
    private static final int NOTIFICATION_ID = 1001;
    private static final int OTP_NOTIFICATION_ID = 1002;
    private static final int LINK_NOTIFICATION_ID = 1003;
    private static final long POLL_INTERVAL_MS = 3000L;
    private static final long ERROR_RETRY_MS = 5000L;
    private static final int DEFAULT_MAILBOX_LIFETIME_MINUTES = 10;

    private TempMailApi api;
    private volatile boolean serviceActive;
    private volatile long workerGeneration;
    private Thread workerThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean forceRefresh = intent != null
                && (intent.getBooleanExtra("force_refresh", false)
                || ACTION_FORCE_REFRESH.equals(intent.getAction()));

        if (!ensureForegroundNotification()) {
            return START_NOT_STICKY;
        }

        if (forceRefresh) {
            getSharedPreferences("auto", MODE_PRIVATE)
                    .edit()
                    .putBoolean("pending_force_refresh", true)
                    .remove("latest_code")
                    .remove("latest_link")
                    .remove("last_auto_copied_code")
                    .remove("last_notified_code")
                    .remove("last_notified_link")
                    .apply();
            InboxStorage.clear(getSharedPreferences("auto", MODE_PRIVATE));
            dismissOtpNotification();
            dismissLinkNotification();
            workerGeneration++;
            if (workerThread != null) {
                workerThread.interrupt();
            }
            workerThread = null;
        }

        if (workerThread != null && workerThread.isAlive()) {
            saveServiceState(true, null);
            return START_STICKY;
        }

        serviceActive = true;
        long generation = ++workerGeneration;
        workerThread = new Thread(() -> runPollingLoop(generation), "TempMailFetcher");
        workerThread.start();
        return START_STICKY;
    }

    private void runPollingLoop(long generation) {
        SharedPreferences prefs = getSharedPreferences("auto", MODE_PRIVATE);
        ensureApi(prefs, true);
        String lastError = null;
        boolean needsMailboxRefresh = prefs.getBoolean("pending_force_refresh", false) || shouldCreateMailbox(prefs);
        saveServiceState(true, null);

        try {
            while (serviceActive
                    && generation == workerGeneration
                    && !Thread.currentThread().isInterrupted()) {
                try {
                    if (needsMailboxRefresh || isMailboxExpired(prefs)) {
                        long now = System.currentTimeMillis();
                        ensureApi(prefs, false);
                        String email = api.generateNewEmail();
                        String provider = api.getCurrentProviderName();
                        long mailboxLifetimeMs = getMailboxLifetimeMs(prefs);
                        prefs.edit()
                                .putString("latest_email", email)
                                .putString("latest_provider", provider)
                                .remove("latest_code")
                                .remove("latest_link")
                                .remove("last_auto_copied_code")
                                .remove("last_notified_code")
                                .remove("last_notified_link")
                                .remove("pending_force_refresh")
                                .putLong("last_sync", now)
                                .putLong("mailbox_created_at", now)
                                .putLong("mailbox_expires_at", now + mailboxLifetimeMs)
                                .remove("last_error")
                                .apply();
                        HistoryStorage.recordEmail(prefs, email, provider, now);
                        InboxStorage.clear(prefs);
                        updateNotification(email, null, null);
                        dismissOtpNotification();
                        dismissLinkNotification();
                        needsMailboxRefresh = false;
                    }

                    ensureApi(prefs, false);
                    TempMailApi.MailboxSnapshot mailboxSnapshot = api.fetchMailboxSnapshot();
                    SharedPreferences.Editor editor = prefs.edit()
                            .putBoolean("polling_active", true)
                            .putLong("last_sync", System.currentTimeMillis())
                            .remove("last_error");

                    InboxStorage.saveInbox(prefs, mailboxSnapshot.messages);

                    if (mailboxSnapshot.latestCode != null && !mailboxSnapshot.latestCode.trim().isEmpty()) {
                        String trimmedCode = mailboxSnapshot.latestCode.trim();
                        editor.putString("latest_code", trimmedCode);
                        maybeNotifyOtp(prefs, trimmedCode);
                        maybeAutoCopyCode(prefs, trimmedCode);
                    }
                    if (mailboxSnapshot.latestLink != null && !mailboxSnapshot.latestLink.trim().isEmpty()) {
                        String trimmedLink = mailboxSnapshot.latestLink.trim();
                        editor.putString("latest_link", trimmedLink);
                        maybeNotifyLink(prefs, trimmedLink);
                    }

                    updateNotification(
                            prefs.getString("latest_email", ""),
                            mailboxSnapshot.latestCode,
                            mailboxSnapshot.latestLink
                    );

                    editor.apply();
                    lastError = null;
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw interruptedException;
                } catch (Exception loopError) {
                    Log.e("Fetcher", "Mailbox loop error", loopError);
                    lastError = loopError.getMessage();
                    saveServiceState(true, lastError);
                    updateNotification(prefs.getString("latest_email", ""), null, null);
                    needsMailboxRefresh = true;
                    Thread.sleep(ERROR_RETRY_MS);
                }
            }
        } catch (Exception e) {
            Log.e("Fetcher", "Error", e);
            if (!(e instanceof InterruptedException)) {
                lastError = e.getMessage();
            }
        } finally {
            boolean currentWorkerOwnsState =
                    workerThread == Thread.currentThread() && generation == workerGeneration;
            if (currentWorkerOwnsState) {
                workerThread = null;
                saveServiceState(false, lastError);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private boolean shouldCreateMailbox(SharedPreferences prefs) {
        String currentEmail = prefs.getString("latest_email", "");
        return currentEmail == null || currentEmail.trim().isEmpty() || isMailboxExpired(prefs);
    }

    private boolean isMailboxExpired(SharedPreferences prefs) {
        long expiresAt = prefs.getLong("mailbox_expires_at", 0L);
        return expiresAt <= 0L || System.currentTimeMillis() >= expiresAt;
    }

    private long getMailboxLifetimeMs(SharedPreferences prefs) {
        int minutes = prefs.getInt("mailbox_lifetime_minutes", DEFAULT_MAILBOX_LIFETIME_MINUTES);
        minutes = Math.max(5, Math.min(minutes, 20));
        return minutes * 60L * 1000L;
    }

    private void maybeAutoCopyCode(SharedPreferences prefs, String code) {
        if (!prefs.getBoolean("auto_copy_code", false) || TextUtils.isEmpty(code)) {
            return;
        }
        String lastCopiedCode = prefs.getString("last_auto_copied_code", "");
        if (code.equals(lastCopiedCode)) {
            return;
        }

        ClipboardManager clipboardManager = ContextCompat.getSystemService(this, ClipboardManager.class);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), code));
            prefs.edit().putString("last_auto_copied_code", code).apply();
        }
    }

    private void maybeNotifyOtp(SharedPreferences prefs, String code) {
        if (!prefs.getBoolean("otp_detect_enabled", true) || TextUtils.isEmpty(code) || !canPostNotifications()) {
            return;
        }

        String lastNotifiedCode = prefs.getString("last_notified_code", "");
        if (code.equals(lastNotifiedCode)) {
            return;
        }

        prefs.edit().putString("last_notified_code", code).apply();
        postOtpNotification(code);
    }

    private void maybeNotifyLink(SharedPreferences prefs, String link) {
        if (!prefs.getBoolean("otp_detect_enabled", true) || TextUtils.isEmpty(link) || !canPostNotifications()) {
            return;
        }

        String lastNotifiedLink = prefs.getString("last_notified_link", "");
        if (link.equals(lastNotifiedLink)) {
            return;
        }

        prefs.edit().putString("last_notified_link", link).apply();
        postLinkNotification(link);
    }

    @Override
    public void onDestroy() {
        serviceActive = false;
        workerGeneration++;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        saveServiceState(false, null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void saveServiceState(boolean active, String errorMessage) {
        SharedPreferences.Editor editor = getSharedPreferences("auto", MODE_PRIVATE)
                .edit()
                .putBoolean("polling_active", active);
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            editor.remove("last_error");
        } else {
            editor.putString("last_error", errorMessage);
        }
        editor.apply();
    }

    private boolean ensureForegroundNotification() {
        createNotificationChannel();
        if (!canPostNotifications()) {
            saveServiceState(false, getString(R.string.error_notifications_required));
            stopSelf();
            return false;
        }
        try {
            Notification notification = buildNotification(null, null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (SecurityException securityException) {
            saveServiceState(false, getString(R.string.error_notifications_required));
            stopSelf();
            return false;
        }
    }

    private void updateNotification(String email, String code, String link) {
        if (!canPostNotifications()) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(email, code, link));
        } catch (SecurityException ignored) {
        }
    }

    private Notification buildNotification(String email, String code, String link) {
        String contentText;
        if (!TextUtils.isEmpty(code)) {
            contentText = getString(R.string.notification_text_code_ready, code);
        } else if (!TextUtils.isEmpty(link)) {
            contentText = getString(R.string.notification_text_link_ready);
        } else if (!TextUtils.isEmpty(email)) {
            contentText = getString(R.string.notification_text_email_ready, email);
        } else {
            contentText = getString(R.string.notification_text);
        }

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent refreshIntent = new Intent(this, EmailFetcherService.class);
        refreshIntent.setAction(ACTION_FORCE_REFRESH);
        refreshIntent.putExtra("force_refresh", true);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                pendingIntentFlags
        );
        PendingIntent refreshPendingIntent = PendingIntent.getService(
                this,
                1,
                refreshIntent,
                pendingIntentFlags
        );

        PendingIntent linkPendingIntent = null;
        if (!TextUtils.isEmpty(link)) {
            Intent openLinkIntent = buildOpenLinkIntent(link);
            linkPendingIntent = PendingIntent.getActivity(
                    this,
                    4,
                    openLinkIntent,
                    pendingIntentFlags
            );
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .addAction(0, getString(R.string.notification_action_refresh), refreshPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(false);

        if (linkPendingIntent != null) {
            builder.addAction(
                    R.drawable.ic_bolt_24,
                    getString(R.string.notification_action_open_link),
                    linkPendingIntent
            );
        }
        return builder.build();
    }

    private void postOtpNotification(String code) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        Intent copyIntent = new Intent(this, CopyReceiver.class);
        copyIntent.setAction(CopyReceiver.ACTION_COPY_OTP);
        copyIntent.putExtra(CopyReceiver.EXTRA_OTP, code);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                2,
                openAppIntent,
                pendingIntentFlags
        );
        PendingIntent copyPendingIntent = PendingIntent.getBroadcast(
                this,
                3,
                copyIntent,
                pendingIntentFlags
        );

        Notification notification = new NotificationCompat.Builder(this, OTP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getString(R.string.otp_notification_title, code))
                .setContentText(getString(R.string.otp_notification_text))
                .setContentIntent(openPendingIntent)
                .addAction(
                        R.drawable.ic_bolt_24,
                        getString(R.string.otp_notification_action_copy, code),
                        copyPendingIntent
                )
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        try {
            manager.notify(OTP_NOTIFICATION_ID, notification);
        } catch (SecurityException ignored) {
        }
    }

    private void postLinkNotification(String link) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent openLinkIntent = buildOpenLinkIntent(link);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                5,
                openAppIntent,
                pendingIntentFlags
        );
        PendingIntent linkPendingIntent = PendingIntent.getActivity(
                this,
                6,
                openLinkIntent,
                pendingIntentFlags
        );

        Notification notification = new NotificationCompat.Builder(this, OTP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getString(R.string.link_notification_title))
                .setContentText(getString(R.string.link_notification_text))
                .setContentIntent(openPendingIntent)
                .addAction(
                        R.drawable.ic_bolt_24,
                        getString(R.string.link_notification_action_open),
                        linkPendingIntent
                )
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        try {
            manager.notify(LINK_NOTIFICATION_ID, notification);
        } catch (SecurityException ignored) {
        }
    }

    private void dismissOtpNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(OTP_NOTIFICATION_ID);
        }
    }

    private void dismissLinkNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(LINK_NOTIFICATION_ID);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_description));
            manager.createNotificationChannel(channel);
        }

        if (manager.getNotificationChannel(OTP_CHANNEL_ID) == null) {
            NotificationChannel otpChannel = new NotificationChannel(
                    OTP_CHANNEL_ID,
                    getString(R.string.otp_notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            otpChannel.setDescription(getString(R.string.otp_notification_channel_description));
            manager.createNotificationChannel(otpChannel);
        }
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Intent buildOpenLinkIntent(String link) {
        Intent openLinkIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        openLinkIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return openLinkIntent;
    }

    private void ensureApi(SharedPreferences prefs, boolean resetIfMissing) {
        String baseUrl = prefs.getString("provider_base_url", TempMailApi.DEFAULT_BASE_URL);
        String providerName = prefs.getString("provider_name", TempMailApi.DEFAULT_PROVIDER_NAME);
        if (baseUrl == null) {
            baseUrl = TempMailApi.DEFAULT_BASE_URL;
        }
        if (providerName == null) {
            providerName = TempMailApi.DEFAULT_PROVIDER_NAME;
        }
        if (api == null) {
            api = new TempMailApi(baseUrl, providerName);
            return;
        }
        if (resetIfMissing) {
            api = new TempMailApi(baseUrl, providerName);
        }
    }
}
