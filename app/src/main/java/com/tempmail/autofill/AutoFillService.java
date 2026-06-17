package com.tempmail.autofill;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoFillService extends AccessibilityService {
    private static final int EMAIL_SCORE_THRESHOLD = 140;
    private static final int CODE_SCORE_THRESHOLD = 140;

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
        String windowSignature = buildWindowSignature(root);

        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditableNodes(root, fields);

        AccessibilityNodeInfo focusedNode = findBestTargetNode(root, event.getSource());
        if (tryFillSegmentedCodeFields(fields, focusedNode, code)) {
            return;
        }

        tryFillBestCandidate(focusedNode, fields, windowSignature, email, code);
    }

    @Override
    public void onInterrupt() {}

    private void collectEditableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields) {
        if (node == null) {
            return;
        }

        if (canAcceptText(node)) {
            fields.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectEditableNodes(node.getChild(i), fields);
        }
    }

    private AccessibilityNodeInfo findBestTargetNode(AccessibilityNodeInfo root, AccessibilityNodeInfo source) {
        AccessibilityNodeInfo editableAncestor = findEditableAncestor(source);
        if (canAcceptText(editableAncestor)) {
            return editableAncestor;
        }
        if (canAcceptText(source)) {
            return source;
        }

        AccessibilityNodeInfo focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (canAcceptText(focusedInput)) {
            return focusedInput;
        }

        AccessibilityNodeInfo focusedAccessibility = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (canAcceptText(focusedAccessibility)) {
            return focusedAccessibility;
        }

        return null;
    }

    private AccessibilityNodeInfo findEditableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 6; depth++) {
            if (canAcceptText(current)) {
                return current;
            }
            if (current == null || current.getParent() == null) {
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean canAcceptText(AccessibilityNodeInfo node) {
        return node != null
                && node.isEnabled()
                && (node.isEditable() || supportsSetText(node));
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

    private String buildFieldSignature(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, node.getViewIdResourceName());
        appendIfPresent(builder, node.getText());
        appendIfPresent(builder, node.getContentDescription());
        appendIfPresent(builder, node.getPackageName());
        appendIfPresent(builder, node.getClassName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendIfPresent(builder, node.getPaneTitle());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendIfPresent(builder, node.getHintText());
        }
        return builder.toString().toLowerCase(Locale.US);
    }

    private String buildNodeContext(AccessibilityNodeInfo node) {
        StringBuilder builder = new StringBuilder(buildFieldSignature(node));
        AccessibilityNodeInfo parent = node != null ? node.getParent() : null;
        if (parent != null) {
            appendNearbyNodeText(builder, parent, node, 1);
            AccessibilityNodeInfo grandParent = parent.getParent();
            if (grandParent != null) {
                appendNearbyNodeText(builder, grandParent, parent, 1);
            }
        }
        return builder.toString().toLowerCase(Locale.US);
    }

    private void appendNearbyNodeText(
            StringBuilder builder,
            AccessibilityNodeInfo container,
            AccessibilityNodeInfo skipNode,
            int depth
    ) {
        if (container == null || depth < 0) {
            return;
        }
        appendIfPresent(builder, container.getText());
        appendIfPresent(builder, container.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendIfPresent(builder, container.getHintText());
        }
        int childCount = Math.min(container.getChildCount(), 8);
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = container.getChild(i);
            if (child == null || child == skipNode) {
                continue;
            }
            appendIfPresent(builder, child.getText());
            appendIfPresent(builder, child.getContentDescription());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appendIfPresent(builder, child.getHintText());
            }
            if (depth > 0) {
                appendNearbyNodeText(builder, child, null, depth - 1);
            }
        }
    }

    private String buildWindowSignature(AccessibilityNodeInfo root) {
        StringBuilder builder = new StringBuilder();
        appendWindowText(root, builder, 0, 80);
        return builder.toString().toLowerCase(Locale.US);
    }

    private int appendWindowText(AccessibilityNodeInfo node, StringBuilder builder, int count, int limit) {
        if (node == null || count >= limit) {
            return count;
        }
        if (!canAcceptText(node)) {
            int before = builder.length();
            appendIfPresent(builder, node.getText());
            appendIfPresent(builder, node.getContentDescription());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appendIfPresent(builder, node.getHintText());
            }
            if (builder.length() > before) {
                count++;
            }
        }
        for (int i = 0; i < node.getChildCount() && count < limit; i++) {
            count = appendWindowText(node.getChild(i), builder, count, limit);
        }
        return count;
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
                || signature.contains("mail address")
                || signature.contains("correo");
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
                || signature.contains("join")
                || signature.contains("get started")
                || signature.contains("continue with email");
    }

    private boolean tryFillSegmentedCodeFields(
            List<AccessibilityNodeInfo> fields,
            AccessibilityNodeInfo focusedNode,
            String code
    ) {
        if (TextUtils.isEmpty(code)) {
            return false;
        }

        String normalizedCode = code.trim();
        if (normalizedCode.length() < 4 || normalizedCode.length() > 8) {
            return false;
        }

        List<AccessibilityNodeInfo> labeledCodeFields = new ArrayList<>();
        List<AccessibilityNodeInfo> compactEditableFields = new ArrayList<>();
        for (AccessibilityNodeInfo node : fields) {
            if (!canAcceptText(node)) {
                continue;
            }

            String currentValue = getNodeValue(node);
            if (currentValue.length() > 1) {
                continue;
            }

            compactEditableFields.add(node);
            String signature = buildNodeContext(node);
            if (!signature.isEmpty() && shouldFillCode(signature)) {
                labeledCodeFields.add(node);
            }
        }

        if (normalizedCode.length() == labeledCodeFields.size()) {
            return fillSegmentedFields(labeledCodeFields, normalizedCode);
        }

        String focusedSignature = buildNodeContext(focusedNode);
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
            if (digit.equals(getNodeValue(node))) {
                continue;
            }
            filledAny |= fillNode(node, digit);
        }
        return filledAny;
    }

    private boolean tryFillBestCandidate(
            AccessibilityNodeInfo focusedNode,
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            String email,
            String code
    ) {
        if (tryFillFocusedNode(focusedNode, windowSignature, email, code)) {
            return true;
        }

        Candidate bestEmailCandidate = findBestEmailCandidate(fields, windowSignature);
        if (bestEmailCandidate != null && fillNode(bestEmailCandidate.node, email)) {
            return true;
        }

        Candidate bestCodeCandidate = findBestCodeCandidate(fields, windowSignature);
        return bestCodeCandidate != null && fillNode(bestCodeCandidate.node, code);
    }

    private boolean tryFillFocusedNode(
            AccessibilityNodeInfo focusedNode,
            String windowSignature,
            String email,
            String code
    ) {
        if (!canAcceptText(focusedNode)) {
            return false;
        }

        String nodeContext = buildNodeContext(focusedNode);
        int emailScore = scoreEmailCandidate(focusedNode, nodeContext, windowSignature, true);
        int codeScore = scoreCodeCandidate(focusedNode, nodeContext, windowSignature, true);

        if (!TextUtils.isEmpty(email)
                && emailScore >= EMAIL_SCORE_THRESHOLD
                && emailScore >= codeScore
                && fillNode(focusedNode, email)) {
            return true;
        }

        return !TextUtils.isEmpty(code)
                && codeScore >= CODE_SCORE_THRESHOLD
                && fillNode(focusedNode, code);
    }

    private Candidate findBestEmailCandidate(List<AccessibilityNodeInfo> fields, String windowSignature) {
        Candidate best = null;
        Candidate secondBest = null;
        for (AccessibilityNodeInfo node : fields) {
            String nodeContext = buildNodeContext(node);
            int score = scoreEmailCandidate(node, nodeContext, windowSignature, false);
            if (score < EMAIL_SCORE_THRESHOLD) {
                continue;
            }
            Candidate candidate = new Candidate(node, score);
            if (best == null || candidate.score > best.score) {
                secondBest = best;
                best = candidate;
            } else if (secondBest == null || candidate.score > secondBest.score) {
                secondBest = candidate;
            }
        }

        if (best == null) {
            return null;
        }
        if (secondBest != null && best.score - secondBest.score < 25) {
            return null;
        }
        return best;
    }

    private Candidate findBestCodeCandidate(List<AccessibilityNodeInfo> fields, String windowSignature) {
        Candidate best = null;
        for (AccessibilityNodeInfo node : fields) {
            String nodeContext = buildNodeContext(node);
            int score = scoreCodeCandidate(node, nodeContext, windowSignature, false);
            if (score < CODE_SCORE_THRESHOLD) {
                continue;
            }
            Candidate candidate = new Candidate(node, score);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private int scoreEmailCandidate(
            AccessibilityNodeInfo node,
            String nodeContext,
            String windowSignature,
            boolean focused
    ) {
        if (!canAcceptText(node)) {
            return Integer.MIN_VALUE;
        }
        if (!getNodeValue(node).isEmpty()) {
            return Integer.MIN_VALUE;
        }

        int maxLength = node.getMaxTextLength();
        if (maxLength > 0 && maxLength < 8) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        if (shouldFillEmail(nodeContext)) {
            score += 180;
        }
        if (containsAny(nodeContext, "username", "user name", "account", "login", "identifier")) {
            score += isSignupContext(windowSignature) ? 60 : 20;
        }
        if (containsAny(nodeContext, "password", "passcode", "otp", "verification", "code", "search")) {
            score -= 220;
        }
        if (isEmailInputType(node.getInputType())) {
            score += 220;
        }
        if (isNumericInputType(node.getInputType()) || isPasswordInputType(node.getInputType())) {
            score -= 220;
        }
        CharSequence className = node.getClassName();
        if (className != null && className.toString().toLowerCase(Locale.US).contains("edittext")) {
            score += 10;
        }
        if (focused) {
            score += 35;
        }
        if (isSignupContext(windowSignature) && containsAny(windowSignature, "email", "sign up", "register", "create account")) {
            score += 25;
        }
        return score;
    }

    private int scoreCodeCandidate(
            AccessibilityNodeInfo node,
            String nodeContext,
            String windowSignature,
            boolean focused
    ) {
        if (!canAcceptText(node)) {
            return Integer.MIN_VALUE;
        }
        if (getNodeValue(node).length() > 1) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        if (shouldFillCode(nodeContext)) {
            score += 190;
        }
        if (containsAny(nodeContext, "password", "email", "mail", "search")) {
            score -= 180;
        }
        if (isNumericInputType(node.getInputType())) {
            score += 90;
        }
        if (isEmailInputType(node.getInputType()) || isPasswordInputType(node.getInputType())) {
            score -= 160;
        }

        int maxLength = node.getMaxTextLength();
        if (maxLength > 0 && maxLength <= 2) {
            score += 100;
        } else if (maxLength >= 4 && maxLength <= 8) {
            score += 60;
        }

        if (focused) {
            score += 35;
        }
        if (containsAny(windowSignature, "verification", "otp", "code")) {
            score += 20;
        }
        return score;
    }

    private String getNodeValue(AccessibilityNodeInfo node) {
        if (node == null || node.getText() == null) {
            return "";
        }
        return node.getText().toString().trim();
    }

    private boolean containsAny(String haystack, String... needles) {
        if (TextUtils.isEmpty(haystack)) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEmailInputType(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }

    private boolean isPasswordInputType(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private boolean isNumericInputType(int inputType) {
        int typeClass = inputType & InputType.TYPE_MASK_CLASS;
        return typeClass == InputType.TYPE_CLASS_NUMBER || typeClass == InputType.TYPE_CLASS_PHONE;
    }

    private boolean fillNode(AccessibilityNodeInfo node, String value) {
        if (!canAcceptText(node) || TextUtils.isEmpty(value)) {
            return false;
        }
        if (value.equals(getNodeValue(node))) {
            return false;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static final class Candidate {
        private final AccessibilityNodeInfo node;
        private final int score;

        private Candidate(AccessibilityNodeInfo node, int score) {
            this.node = node;
            this.score = score;
        }
    }
}
