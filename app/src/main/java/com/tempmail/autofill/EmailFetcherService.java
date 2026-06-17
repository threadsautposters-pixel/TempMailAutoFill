package com.tempmail.autofill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class EmailFetcherService extends Service {
    public static final String ACTION_FORCE_REFRESH = "com.tempmail.autofill.action.FORCE_REFRESH";

    private static final String CHANNEL_ID = "mailbox_monitoring";
    private static final int NOTIFICATION_ID = 1001;
    private static final long POLL_INTERVAL_MS = 3000L;
    private static final long ERROR_RETRY_MS = 5000L;
    private static final int DEFAULT_MAILBOX_LIFETIME_MINUTES = 10;

    private final TempMailApi api = new TempMailApi();
    private volatile boolean serviceActive;
    private volatile long workerGeneration;
    private Thread workerThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean forceRefresh = intent != null
                && (intent.getBooleanExtra("force_refresh", false)
                || ACTION_FORCE_REFRESH.equals(intent.getAction()));

        ensureForegroundNotification();

        if (forceRefresh) {
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
        String lastError = null;
        boolean needsMailboxRefresh = shouldCreateMailbox(prefs);
        saveServiceState(true, null);

        try {
            while (serviceActive
                    && generation == workerGeneration
                    && !Thread.currentThread().isInterrupted()) {
                try {
                    if (needsMailboxRefresh || isMailboxExpired(prefs)) {
                        long now = System.currentTimeMillis();
                        String email = api.generateNewEmail();
                        String provider = api.getCurrentProviderName();
                        long mailboxLifetimeMs = getMailboxLifetimeMs(prefs);
                        prefs.edit()
                                .putString("latest_email", email)
                                .putString("latest_provider", provider)
                                .remove("latest_code")
                                .putLong("last_sync", now)
                                .putLong("mailbox_created_at", now)
                                .putLong("mailbox_expires_at", now + mailboxLifetimeMs)
                                .remove("last_error")
                                .apply();
                        HistoryStorage.recordEmail(prefs, email, provider, now);
                        updateNotification(email, null);
                        needsMailboxRefresh = false;
                    }

                    String code = api.fetchVerificationCode();
                    SharedPreferences.Editor editor = prefs.edit()
                            .putBoolean("polling_active", true)
                            .putLong("last_sync", System.currentTimeMillis())
                            .remove("last_error");

                    if (code != null && !code.trim().isEmpty()) {
                        String trimmedCode = code.trim();
                        editor.putString("latest_code", trimmedCode);
                        maybeAutoCopyCode(prefs, trimmedCode);
                        updateNotification(prefs.getString("latest_email", ""), trimmedCode);
                    } else {
                        updateNotification(prefs.getString("latest_email", ""), prefs.getString("latest_code", ""));
                    }

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
                    updateNotification(prefs.getString("latest_email", ""), null);
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

    private void ensureForegroundNotification() {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(null, null));
    }

    private void updateNotification(String email, String code) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.notify(NOTIFICATION_ID, buildNotification(email, code));
    }

    private Notification buildNotification(String email, String code) {
        String contentText;
        if (!TextUtils.isEmpty(code)) {
            contentText = getString(R.string.notification_text_code_ready, code);
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

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .addAction(0, getString(R.string.notification_action_refresh), refreshPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        manager.createNotificationChannel(channel);
    }
}
