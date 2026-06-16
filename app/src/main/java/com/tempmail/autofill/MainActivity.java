package com.tempmail.autofill;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private Switch switchAutoFill;
    private TextView statusText;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        switchAutoFill = findViewById(R.id.switch_auto);
        statusText = findViewById(R.id.status_text);
        Button btnService = findViewById(R.id.btn_start_service);
        SharedPreferences prefs = getSharedPreferences("auto", MODE_PRIVATE);
        switchAutoFill.setChecked(prefs.getBoolean("enabled", false));
        switchAutoFill.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            if (checked) startEmailFetcher();
            else stopEmailFetcher();
        });
        btnService.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
    }
    private void startEmailFetcher() {
        startService(new Intent(this, EmailFetcherService.class));
    }
    private void stopEmailFetcher() {
        stopService(new Intent(this, EmailFetcherService.class));
    }
    public void openWebView(android.view.View v) {
        startActivity(new Intent(this, WebViewActivity.class));
    }
}
