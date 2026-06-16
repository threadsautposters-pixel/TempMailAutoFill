package com.tempmail.autofill;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

public class EmailFetcherService extends Service {
    private final TempMailApi api = new TempMailApi();
    private volatile boolean polling;
    private Thread workerThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (workerThread != null && workerThread.isAlive()) {
            saveServiceState(true, null);
            return START_STICKY;
        }

        polling = true;
        workerThread = new Thread(this::runPollingLoop, "TempMailFetcher");
        workerThread.start();
        return START_STICKY;
    }

    private void runPollingLoop() {
        SharedPreferences prefs = getSharedPreferences("auto", MODE_PRIVATE);
        String lastError = null;
        saveServiceState(true, null);

        try {
            String email = api.generateNewEmail();
            prefs.edit()
                    .putString("latest_email", email)
                    .remove("latest_code")
                    .putLong("last_sync", System.currentTimeMillis())
                    .apply();

            while (polling && !Thread.currentThread().isInterrupted()) {
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
            polling = false;
            if (workerThread == Thread.currentThread()) {
                workerThread = null;
            }
            saveServiceState(false, lastError);
        }
    }

    @Override
    public void onDestroy() {
        polling = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        saveServiceState(false, null);
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
}
