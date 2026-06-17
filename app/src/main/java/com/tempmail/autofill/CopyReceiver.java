package com.tempmail.autofill;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * Handles the notification quick-copy action for OTP codes.
 */
public class CopyReceiver extends BroadcastReceiver {
    public static final String ACTION_COPY_OTP = "com.tempmail.autofill.action.COPY_OTP";
    public static final String EXTRA_OTP = "otp";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_COPY_OTP.equals(intent.getAction())) {
            return;
        }

        String otp = intent.getStringExtra(EXTRA_OTP);
        if (TextUtils.isEmpty(otp)) {
            return;
        }

        ClipboardManager clipboardManager =
                ContextCompat.getSystemService(context, ClipboardManager.class);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.app_name), otp)
            );
            Toast.makeText(
                    context,
                    context.getString(R.string.toast_copied_code_prefix, otp),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }
}
