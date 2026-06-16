package com.tempmail.autofill;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class EmailFetcherService extends Service {
    private Handler handler = new Handler(Looper.getMainLooper());
    private TempMailApi api = new TempMailApi();
    private boolean polling;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                String email = api.generateNewEmail();
                polling = true;
                while (polling) {
                    String code = api.fetchVerificationCode();
                    if (code != null) {
                        getSharedPreferences("auto", MODE_PRIVATE).edit()
                            .putString("latest_code", code)
                            .putString("latest_email", email)
                            .apply();
                    }
                    Thread.sleep(3000);
                }
            } catch (Exception e) {
                Log.e("Fetcher", "Error", e);
            }
        }).start();
        return START_STICKY;
    }
    @Override
    public void onDestroy() {
        polling = false;
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
