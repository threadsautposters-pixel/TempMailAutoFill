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
                && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
                && eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
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
        collectEditableNodes(root, fields);
        String pageSignature = buildPageSignature(root);

        AccessibilityNodeInfo focusedNode = findBestTargetNode(root, event.getSource());
        if (tryFillSegmentedCodeFields(fields, focusedNode, code)) {
            return;
        }

        if (tryFillNode(focusedNode, email, code, pageSignature, true)) {
            return;
        }

        AccessibilityNodeInfo bestEmailNode = findBestEmailNode(fields, focusedNode, pageSignature);
        if (bestEmailNode != null && tryFillNode(bestEmailNode, email, code, pageSignature, true)) {
            return;
        }

        for (AccessibilityNodeInfo node : fields) {
            if (tryFillNode(node, email, code, pageSignature, false)) {
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
        AccessibilityNodeInfo editableAncestor = findEditableAncestor(source);
        if (isEditableField(editableAncestor)) {
            return editableAncestor;
        }
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

    private AccessibilityNodeInfo findEditableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 6; depth++) {
            if (isEditableField(current)) {
                return current;
            }
            if (current == null || current.getParent() == null) {
                return null;
            }
            current = (AccessibilityNodeInfo) current.getParent();
        }
        return null;
    }

    private boolean isEditableField(AccessibilityNodeInfo node) {
        return node != null
                && node.isEnabled()
                && node.isVisibleToUser()
                && (node.isEditable() || supportsSetText(node) || isTextInputClass(node));
    }

    private String buildFieldSignature(AccessibilityNodeInfo node) {
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, node.getViewIdResourceName());
        appendIfPresent(builder, node.getClassName());
        appendIfPresent(builder, node.getText());
        appendIfPresent(builder, node.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendIfPresent(builder, node.getPaneTitle());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendIfPresent(builder, node.getHintText());
            appendIfPresent(builder, node.getTooltipText());
        }

        AccessibilityNodeInfo parent = node.getParent();
        for (int depth = 0; depth < 2 && parent != null; depth++) {
            appendIfPresent(builder, parent.getViewIdResourceName());
            appendIfPresent(builder, parent.getText());
            appendIfPresent(builder, parent.getContentDescription());
            parent = parent.getParent();
        }
        return builder.toString().toLowerCase(Locale.US);
    }

    private String buildPageSignature(AccessibilityNodeInfo root) {
        StringBuilder builder = new StringBuilder();
        collectPageText(root, builder, 0, 250);
        return builder.toString().toLowerCase(Locale.US);
    }

    private int collectPageText(
            AccessibilityNodeInfo node,
            StringBuilder builder,
            int visited,
            int limit
    ) {
        if (node == null || visited >= limit) {
            return visited;
        }

        appendIfPresent(builder, node.getViewIdResourceName());
        appendIfPresent(builder, node.getClassName());
        appendIfPresent(builder, node.getText());
        appendIfPresent(builder, node.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendIfPresent(builder, node.getHintText());
            appendIfPresent(builder, node.getTooltipText());
        }

        int currentVisited = visited + 1;
        for (int i = 0; i < node.getChildCount() && currentVisited < limit; i++) {
            currentVisited = collectPageText(node.getChild(i), builder, currentVisited, limit);
        }
        return currentVisited;
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
                || signature.contains("email address")
                || signature.contains("@")
                || signature.contains("account id");
    }

    private boolean shouldFillCode(String signature) {
        return signature.contains("code")
                || signature.contains("otp")
                || signature.contains("verification")
                || signature.contains("verify")
                || signature.contains("pin")
                || signature.contains("passcode");
    }

    private boolean isSignupContext(String signature) {
        return signature.contains("sign up")
                || signature.contains("signup")
                || signature.contains("register")
                || signature.contains("create account")
                || signature.contains("create your account")
                || signature.contains("join now")
                || signature.contains("get started")
                || signature.contains("start free")
                || signature.contains("continue with email");
    }

    private boolean looksLikePasswordField(String signature) {
        return signature.contains("password")
                || signature.contains("pass word")
                || signature.contains("confirm password")
                || signature.contains("new password");
    }

    private boolean looksLikePhoneField(String signature) {
        return signature.contains("phone")
                || signature.contains("mobile")
                || signature.contains("telephone")
                || signature.contains("tel");
    }

    private boolean looksLikeNameField(String signature) {
        return signature.contains("full name")
                || signature.contains("first name")
                || signature.contains("last name")
                || signature.contains("your name");
    }

    private boolean looksLikeSearchField(String signature) {
        return signature.contains("search")
                || signature.contains("query");
    }

    private boolean supportsSetText(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action != null && action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                return true;
            }
        }
        return false;
    }

    private boolean isTextInputClass(AccessibilityNodeInfo node) {
        if (node == null || node.getClassName() == null) {
            return false;
        }
        String className = node.getClassName().toString().toLowerCase(Locale.US);
        return className.contains("edittext")
                || className.contains("textfield")
                || className.contains("autocomplete");
    }

    private AccessibilityNodeInfo findBestEmailNode(
            List<AccessibilityNodeInfo> fields,
            AccessibilityNodeInfo focusedNode,
            String pageSignature
    ) {
        int bestScore = Integer.MIN_VALUE;
        AccessibilityNodeInfo bestNode = null;
        boolean signupContext = isSignupContext(pageSignature);

        for (AccessibilityNodeInfo node : fields) {
            if (!isEditableField(node)) {
                continue;
            }

            String signature = buildFieldSignature(node);
            int score = scoreEmailField(node, signature, focusedNode, signupContext);
            if (score > bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }

        return bestScore >= 40 ? bestNode : null;
    }

    private int scoreEmailField(
            AccessibilityNodeInfo node,
            String signature,
            AccessibilityNodeInfo focusedNode,
            boolean signupContext
    ) {
        int score = 0;
        if (shouldFillEmail(signature)) {
            score += 120;
        }
        if (signupContext) {
            score += 35;
        }
        if (node.equals(focusedNode)) {
            score += 45;
        }
        if (isTextInputClass(node)) {
            score += 20;
        }
        if (supportsSetText(node)) {
            score += 15;
        }
        if (node.isFocused()) {
            score += 20;
        }

        CharSequence text = node.getText();
        String currentValue = text == null ? "" : text.toString().trim();
        if (!currentValue.isEmpty()) {
            if (currentValue.contains("@")) {
                score -= 100;
            } else {
                score -= 30;
            }
        }

        if (shouldFillCode(signature)) {
            score -= 90;
        }
        if (looksLikePasswordField(signature)) {
            score -= 120;
        }
        if (looksLikePhoneField(signature)) {
            score -= 80;
        }
        if (looksLikeNameField(signature)) {
            score -= 50;
        }
        if (looksLikeSearchField(signature)) {
            score -= 80;
        }

        int maxLen = node.getMaxTextLength();
        if (maxLen > 0 && maxLen <= 2) {
            score -= 120;
        }
        return score;
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

    private boolean tryFillNode(
            AccessibilityNodeInfo node,
            String email,
            String code,
            String pageSignature,
            boolean allowAggressiveEmail
    ) {
        if (!isEditableField(node)) {
            return false;
        }

        String signature = buildFieldSignature(node);
        if (signature.isEmpty() && !allowAggressiveEmail) {
            return false;
        }

        CharSequence existingText = node.getText();
        String currentValue = existingText == null ? "" : existingText.toString().trim();
        boolean signupContext = isSignupContext(pageSignature);

        if (shouldFillEmail(signature) && email != null && !email.isEmpty()) {
            if (!currentValue.isEmpty()) {
                return false;
            }
            return fillNode(node, email);
        }

        if (allowAggressiveEmail
                && email != null
                && !email.isEmpty()
                && currentValue.isEmpty()
                && signupContext
                && !shouldFillCode(signature)
                && !looksLikePasswordField(signature)
                && !looksLikePhoneField(signature)
                && !looksLikeNameField(signature)
                && !looksLikeSearchField(signature)) {
            return fillNode(node, email);
        }

        if (shouldFillCode(signature) && code != null && !code.isEmpty()) {
            int maxLen = node.getMaxTextLength();
            if (maxLen > 0 && maxLen <= 2 && code.length() > maxLen && currentValue.length() <= 1) {
                return false;
            }
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
