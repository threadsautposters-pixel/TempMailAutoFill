package com.tempmail.autofill;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoFillService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences("auto", MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        String email = prefs.getString("latest_email", null);
        String code = prefs.getString("latest_code", null);
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditableNodes(root, fields);

        for (AccessibilityNodeInfo node : fields) {
            if (node == null || !node.isEditable() || !node.isEnabled()) {
                continue;
            }

            CharSequence existingText = node.getText();
            if (existingText != null && existingText.length() > 0) {
                continue;
            }

            String signature = buildFieldSignature(node);
            if (signature.isEmpty()) {
                continue;
            }

            if (shouldFillEmail(signature) && email != null && !email.isEmpty()) {
                fillNode(node, email);
            } else if (shouldFillCode(signature) && code != null && !code.isEmpty()) {
                fillNode(node, code);
            }
        }
    }

    @Override
    public void onInterrupt() {}

    private void collectEditableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields) {
        if (node == null) {
            return;
        }

        if (node.isEditable()) {
            fields.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectEditableNodes(node.getChild(i), fields);
        }
    }

    private String buildFieldSignature(AccessibilityNodeInfo node) {
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, node.getViewIdResourceName());
        appendIfPresent(builder, node.getText());
        appendIfPresent(builder, node.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendIfPresent(builder, node.getPaneTitle());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendIfPresent(builder, node.getHintText());
        }
        return builder.toString().toLowerCase(Locale.US);
    }

    private void appendIfPresent(StringBuilder builder, CharSequence value) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private boolean shouldFillEmail(String signature) {
        return signature.contains("email")
                || signature.contains("e-mail")
                || signature.contains("mail");
    }

    private boolean shouldFillCode(String signature) {
        return signature.contains("code")
                || signature.contains("otp")
                || signature.contains("verification")
                || signature.contains("verify")
                || signature.contains("pin")
                || signature.contains("passcode");
    }

    private void fillNode(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}
