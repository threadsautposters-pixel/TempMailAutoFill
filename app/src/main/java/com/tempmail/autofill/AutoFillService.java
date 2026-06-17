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
                && eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
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

        AccessibilityNodeInfo focusedNode = findBestTargetNode(root, event.getSource());
        if (tryFillNode(focusedNode, email, code)) {
            return;
        }

        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditableNodes(root, fields);

        if (tryFillSegmentedCodeFields(fields, code)) {
            return;
        }

        for (AccessibilityNodeInfo node : fields) {
            if (tryFillNode(node, email, code)) {
                return;
            }
        }
    }

    @Override
    public void onInterrupt() {}

    private void collectEditableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields) {
        if (node == null) {
            return;
        }

        if (isEditableField(node)) {
            fields.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectEditableNodes(node.getChild(i), fields);
        }
    }

    private AccessibilityNodeInfo findBestTargetNode(AccessibilityNodeInfo root, AccessibilityNodeInfo source) {
        if (isEditableField(source)) {
            return source;
        }

        AccessibilityNodeInfo focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (isEditableField(focusedInput)) {
            return focusedInput;
        }

        AccessibilityNodeInfo focusedAccessibility = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (isEditableField(focusedAccessibility)) {
            return focusedAccessibility;
        }

        return null;
    }

    private boolean isEditableField(AccessibilityNodeInfo node) {
        return node != null && node.isEnabled() && node.isEditable();
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

    private boolean tryFillSegmentedCodeFields(List<AccessibilityNodeInfo> fields, String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }

        String normalizedCode = code.trim();
        List<AccessibilityNodeInfo> codeFields = new ArrayList<>();
        for (AccessibilityNodeInfo node : fields) {
            if (!isEditableField(node)) {
                continue;
            }

            String signature = buildFieldSignature(node);
            if (signature.isEmpty() || !shouldFillCode(signature)) {
                continue;
            }

            CharSequence currentText = node.getText();
            if (currentText != null && currentText.length() > 1) {
                return false;
            }
            codeFields.add(node);
        }

        if (codeFields.size() < 4 || codeFields.size() > 8 || normalizedCode.length() != codeFields.size()) {
            return false;
        }

        boolean filledAny = false;
        for (int i = 0; i < codeFields.size(); i++) {
            AccessibilityNodeInfo node = codeFields.get(i);
            String digit = String.valueOf(normalizedCode.charAt(i));
            CharSequence existingText = node.getText();
            if (existingText != null && digit.contentEquals(existingText)) {
                continue;
            }
            filledAny |= fillNode(node, digit);
        }
        return filledAny;
    }

    private boolean tryFillNode(AccessibilityNodeInfo node, String email, String code) {
        if (!isEditableField(node)) {
            return false;
        }

        String signature = buildFieldSignature(node);
        if (signature.isEmpty()) {
            return false;
        }

        CharSequence existingText = node.getText();
        String currentValue = existingText == null ? "" : existingText.toString().trim();

        if (shouldFillEmail(signature) && email != null && !email.isEmpty()) {
            if (!currentValue.isEmpty()) {
                return false;
            }
            return fillNode(node, email);
        }

        if (shouldFillCode(signature) && code != null && !code.isEmpty()) {
            if (code.equals(currentValue)) {
                return false;
            }
            return fillNode(node, code);
        }

        return false;
    }

    private boolean fillNode(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}
