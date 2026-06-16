package com.tempmail.autofill;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class EmailFetcherService extends Service {
    private static final String CHANNEL_ID = "mailbox_monitoring";
    private static final int NOTIFICATION_ID = 1001;

    private final TempMailApi api = new TempMailApi();
    private volatile boolean serviceActive;
    private volatile long workerGeneration;
    private Thread workerThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean forceRefresh = intent != null && intent.getBooleanExtra("force_refresh", false);

        ensureForegroundNotification();

        if (forceRefresh && workerThread != null) {
            workerThread.interrupt();
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
        saveServiceState(true, null);

        try {
            long now = System.currentTimeMillis();
            String email = api.generateNewEmail();
            String provider = api.getCurrentProviderName();
            prefs.edit()
                    .putString("latest_email", email)
                    .putString("latest_provider", provider)
                    .remove("latest_code")
                    .putLong("last_sync", now)
                    .apply();
            HistoryStorage.recordEmail(prefs, email, provider, now);

            while (serviceActive
                    && generation == workerGeneration
                    && !Thread.currentThread().isInterrupted()) {
                String code = api.fetchVerificationCode();
                SharedPreferences.Editor editor = prefs.edit()
                        .putBoolean("polling_active", true)
                        .putLong("last_sync", System.currentTimeMillis())
                        .remove("last_error");

                if (code != null && !code.trim().isEmpty()) {
                    editor.putString("latest_code", code.trim());
                }

                editor.apply();
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            Log.e("Fetcher", "Error", e);
            lastError = e.getMessage();
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

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        startForeground(NOTIFICATION_ID, builder.build());
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
