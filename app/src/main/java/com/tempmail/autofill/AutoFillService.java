package com.tempmail.autofill;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class AutoFillService extends AccessibilityService {
    private static final int MAX_EDITABLE_NODES = 80;
    private static final Pattern OTP_INDEX_PATTERN = Pattern.compile(".*(otp|code|pin|digit)[^0-9]*[0-9].*", Pattern.CASE_INSENSITIVE);

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

        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditableNodes(root, fields, 0);

        AccessibilityNodeInfo focusedNode = findBestTargetNode(root, event.getSource());
        if (tryFillSegmentedCodeFields(fields, focusedNode, code)) {
            return;
        }

        if (tryFillNode(focusedNode, email, code)) {
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

    private int collectEditableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields, int collected) {
        if (node == null) {
            return collected;
        }

        if (collected >= MAX_EDITABLE_NODES) {
            return collected;
        }

        if (isEditableField(node)) {
            fields.add(node);
            collected++;
            if (collected >= MAX_EDITABLE_NODES) {
                return collected;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collected = collectEditableNodes(node.getChild(i), fields, collected);
            if (collected >= MAX_EDITABLE_NODES) {
                return collected;
            }
        }
        return collected;
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
        appendIfPresent(builder, node.getClassName());
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
                || signature.contains("mail")
                || signature.contains("username")
                || signature.contains("user name")
                || signature.contains("login");
    }

    private boolean shouldFillCode(String signature) {
        return signature.contains("code")
                || signature.contains("otp")
                || signature.contains("verification")
                || signature.contains("verify")
                || signature.contains("pin")
                || signature.contains("passcode");
    }

    private boolean tryFillSegmentedCodeFields(
            List<AccessibilityNodeInfo> fields,
            AccessibilityNodeInfo focusedNode,
            String code
    ) {
        if (code == null || code.isEmpty()) {
            return false;
        }

        String normalizedCode = code.trim();
        if (normalizedCode.length() < 4 || normalizedCode.length() > 8) {
            return false;
        }

        List<AccessibilityNodeInfo> labeledCodeFields = new ArrayList<>();
        List<AccessibilityNodeInfo> compactEditableFields = new ArrayList<>();
        for (AccessibilityNodeInfo node : fields) {
            if (!isEditableField(node)) {
                continue;
            }

            CharSequence currentText = node.getText();
            if (currentText != null && currentText.length() > 1) {
                continue;
            }

            compactEditableFields.add(node);
            String signature = buildFieldSignature(node);
            if (!signature.isEmpty() && shouldFillCode(signature)) {
                labeledCodeFields.add(node);
            }
        }

        sortNodesByScreenPosition(labeledCodeFields);
        sortNodesByScreenPosition(compactEditableFields);

        if (normalizedCode.length() == labeledCodeFields.size()) {
            return fillSegmentedFields(labeledCodeFields, normalizedCode);
        }

        String focusedSignature = buildFieldSignature(focusedNode);
        boolean focusedLooksLikeCode = !focusedSignature.isEmpty() && shouldFillCode(focusedSignature);
        if (focusedLooksLikeCode && normalizedCode.length() == compactEditableFields.size()) {
            return fillSegmentedFields(compactEditableFields, normalizedCode);
        }

        return false;
    }

    private void sortNodesByScreenPosition(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null || nodes.size() < 2) {
            return;
        }
        Collections.sort(nodes, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo left, AccessibilityNodeInfo right) {
                Rect leftRect = new Rect();
                Rect rightRect = new Rect();
                left.getBoundsInScreen(leftRect);
                right.getBoundsInScreen(rightRect);
                if (leftRect.top != rightRect.top) {
                    return Integer.compare(leftRect.top, rightRect.top);
                }
                return Integer.compare(leftRect.left, rightRect.left);
            }
        });
    }

    private boolean fillSegmentedFields(List<AccessibilityNodeInfo> fields, String code) {
        boolean filledAny = false;
        for (int i = 0; i < fields.size(); i++) {
            AccessibilityNodeInfo node = fields.get(i);
            String digit = String.valueOf(code.charAt(i));
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
            boolean focused = node.isFocused();
            if (!focused && !currentValue.isEmpty()) {
                return false;
            }
            if (focused && !currentValue.isEmpty() && currentValue.contains("@")) {
                return false;
            }
            return fillNode(node, email);
        }

        if (shouldFillCode(signature) && code != null && !code.isEmpty()) {
            if (code.equals(currentValue)) {
                return false;
            }
            if (code.length() > 1 && currentValue.length() <= 1 && looksLikeSingleCharOtpField(signature)) {
                return false;
            }
            return fillNode(node, code);
        }

        return false;
    }

    private boolean looksLikeSingleCharOtpField(String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        return OTP_INDEX_PATTERN.matcher(signature).matches();
    }

    private boolean fillNode(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}
