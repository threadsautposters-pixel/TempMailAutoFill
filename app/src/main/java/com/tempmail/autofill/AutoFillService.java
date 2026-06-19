package com.tempmail.autofill;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AutoFillService extends AccessibilityService {
    private static final int EMAIL_SCORE_THRESHOLD = 120;
    private static final int CODE_SCORE_THRESHOLD = 140;
    private static final int PASSWORD_SCORE_THRESHOLD = 150;
    private static final int AGGRESSIVE_SIGNUP_EMAIL_THRESHOLD = 100;
    private static final int STRONG_SIGNUP_CONTEXT_THRESHOLD = 120;
    private static final int EMAIL_NEAR_BEST_DELTA = 25;
    private static final int PASSWORD_NEAR_BEST_DELTA = 20;
    private static final long DEFERRED_SCAN_DELAY_MS = 180L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deferredScanRunnable = () -> attemptAutoFill(null);

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
                && eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleDeferredScan();
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        attemptAutoFill(event.getSource());
    }

    @Override
    public void onInterrupt() {}

    private void scheduleDeferredScan() {
        handler.removeCallbacks(deferredScanRunnable);
        handler.postDelayed(deferredScanRunnable, DEFERRED_SCAN_DELAY_MS);
    }

    private void attemptAutoFill(AccessibilityNodeInfo source) {
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
        String password = prefs.getString("saved_password", null);
        boolean aggressiveSignupAutofill = prefs.getBoolean("aggressive_signup_autofill", false);
        String windowSignature = buildWindowSignature(root);

        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditableNodes(root, fields);

        AccessibilityNodeInfo focusedNode = findBestTargetNode(root, source);
        if (tryFillSegmentedCodeFields(fields, focusedNode, code)) {
            return;
        }

        tryFillBestCandidate(
                focusedNode,
                fields,
                windowSignature,
                email,
                code,
                password,
                aggressiveSignupAutofill
        );
    }

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
                && node.isVisibleToUser()
                && (node.isEditable()
                || supportsSetText(node)
                || supportsPaste(node)
                || looksLikeTextEntry(node));
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

    private boolean supportsPaste(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action != null && action.getId() == AccessibilityNodeInfo.ACTION_PASTE) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeTextEntry(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        CharSequence className = node.getClassName();
        if (className == null) {
            return false;
        }
        String classNameValue = className.toString().toLowerCase(Locale.US);
        return classNameValue.contains("edittext")
                || classNameValue.contains("textfield")
                || classNameValue.contains("textinput")
                || classNameValue.contains("autocomplete");
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
        appendSiblingText(builder, node);
        appendAncestorText(builder, node, 3);
        return builder.toString().toLowerCase(Locale.US);
    }

    private void appendSiblingText(StringBuilder builder, AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) {
            return;
        }

        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo sibling = parent.getChild(i);
            if (sibling == null || sibling == node) {
                continue;
            }
            appendIfPresent(builder, sibling.getText());
            appendIfPresent(builder, sibling.getContentDescription());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appendIfPresent(builder, sibling.getHintText());
            }
        }
    }

    private void appendAncestorText(StringBuilder builder, AccessibilityNodeInfo node, int maxDepth) {
        AccessibilityNodeInfo current = node != null ? node.getParent() : null;
        for (int depth = 0; depth < maxDepth && current != null; depth++) {
            appendIfPresent(builder, current.getText());
            appendIfPresent(builder, current.getContentDescription());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appendIfPresent(builder, current.getHintText());
            }
            current = current.getParent();
        }
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
                || signature.contains("e mail")
                || signature.contains("mail")
                || signature.contains("email address")
                || signature.contains("your email")
                || signature.contains("mail address")
                || signature.contains("email id")
                || signature.contains("email or username")
                || signature.contains("username or email")
                || signature.contains("user id")
                || signature.contains("userid")
                || signature.contains("login id")
                || signature.contains("correo")
                || signature.contains("correo electronico")
                || signature.contains("work email")
                || signature.contains("business email")
                || signature.contains("contact email");
    }

    private boolean shouldFillCode(String signature) {
        return signature.contains("code")
                || signature.contains("otp")
                || signature.contains("verification")
                || signature.contains("verify")
                || signature.contains("pin")
                || signature.contains("passcode");
    }

    private boolean shouldFillPassword(String signature) {
        return signature.contains("password")
                || signature.contains("passphrase")
                || signature.contains("set password")
                || signature.contains("create password")
                || signature.contains("new password")
                || signature.contains("choose password");
    }

    private boolean isSignupContext(String signature) {
        return scoreSignupContext(signature) >= 90;
    }

    private int scoreSignupContext(String signature) {
        if (TextUtils.isEmpty(signature)) {
            return 0;
        }

        int score = 0;
        if (containsAny(
                signature,
                "sign up",
                "signup",
                "register",
                "create account",
                "create your account",
                "join",
                "get started",
                "continue with email",
                "start for free",
                "free trial",
                "open account",
                "new account",
                "create profile",
                "new here"
        )) {
            score += 160;
        }
        if (containsAny(
                signature,
                "already have an account",
                "terms of service",
                "privacy policy",
                "by continuing",
                "continue",
                "agree",
                "welcome"
        )) {
            score += 40;
        }
        if (containsAny(signature, "email", "mail")) {
            score += 20;
        }
        if (containsAny(signature, "password", "confirm password")) {
            score += 15;
        }
        if (containsAny(signature, "sign in", "login", "log in", "welcome back", "forgot password")) {
            score -= 80;
        }
        return score;
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
            String code,
            String password,
            boolean aggressiveSignupAutofill
    ) {
        if (tryFillFocusedNode(
                focusedNode,
                fields,
                windowSignature,
                email,
                code,
                password,
                aggressiveSignupAutofill
        )) {
            return true;
        }

        if (fillLikelyEmailCandidates(fields, windowSignature, email, aggressiveSignupAutofill)) {
            return true;
        }

        if (fillLikelyPasswordCandidates(fields, windowSignature, password)) {
            return true;
        }

        Candidate bestCodeCandidate = findBestCodeCandidate(fields, windowSignature);
        return bestCodeCandidate != null && fillNode(bestCodeCandidate.node, code);
    }

    private boolean tryFillFocusedNode(
            AccessibilityNodeInfo focusedNode,
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            String email,
            String code,
            String password,
            boolean aggressiveSignupAutofill
    ) {
        if (!canAcceptText(focusedNode)) {
            return false;
        }

        String nodeContext = buildNodeContext(focusedNode);
        int emailScore = scoreEmailCandidate(focusedNode, nodeContext, windowSignature, true);
        int codeScore = scoreCodeCandidate(focusedNode, nodeContext, windowSignature, true);
        int passwordScore = scorePasswordCandidate(focusedNode, nodeContext, windowSignature, true);

        if (!TextUtils.isEmpty(email)
                && emailScore >= EMAIL_SCORE_THRESHOLD
                && emailScore >= codeScore
                && emailScore >= passwordScore
                && fillNode(focusedNode, email)) {
            return true;
        }

        if (aggressiveSignupAutofill
                && !TextUtils.isEmpty(email)
                && emailScore >= AGGRESSIVE_SIGNUP_EMAIL_THRESHOLD
                && emailScore >= codeScore
                && emailScore >= passwordScore
                && isAggressiveSignupEmailCandidate(focusedNode, nodeContext, windowSignature, fields)
                && fillNode(focusedNode, email)) {
            return true;
        }

        if (!TextUtils.isEmpty(password)
                && passwordScore >= PASSWORD_SCORE_THRESHOLD
                && passwordScore >= codeScore
                && fillNode(focusedNode, password)) {
            return true;
        }

        return !TextUtils.isEmpty(code)
                && codeScore >= CODE_SCORE_THRESHOLD
                && fillNode(focusedNode, code);
    }

    private boolean fillLikelyEmailCandidates(
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            String email,
            boolean aggressiveSignupAutofill
    ) {
        if (TextUtils.isEmpty(email)) {
            return false;
        }

        List<Candidate> candidates = findEmailCandidates(fields, windowSignature, EMAIL_SCORE_THRESHOLD);
        if (!candidates.isEmpty()) {
            Candidate best = candidates.get(0);
            boolean bestExplicit = isExplicitEmailCandidate(best.node, best.signature);
            boolean filledAny = false;

            for (Candidate candidate : candidates) {
                boolean isBest = candidate == best;
                boolean nearBest = candidate.score >= best.score - EMAIL_NEAR_BEST_DELTA;
                boolean safeCompanion = isExplicitEmailCandidate(candidate.node, candidate.signature)
                        || isEmailConfirmationField(candidate.signature);
                if (isBest || (bestExplicit && nearBest && safeCompanion)) {
                    filledAny |= fillNode(candidate.node, email);
                }
            }

            if (filledAny) {
                return true;
            }
        }

        if (!aggressiveSignupAutofill) {
            return false;
        }

        Candidate aggressiveCandidate = findBestEmailCandidate(
                fields,
                windowSignature,
                AGGRESSIVE_SIGNUP_EMAIL_THRESHOLD
        );
        return aggressiveCandidate != null && fillNode(aggressiveCandidate.node, email);
    }

    private boolean fillLikelyPasswordCandidates(
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            String password
    ) {
        if (TextUtils.isEmpty(password)) {
            return false;
        }

        List<Candidate> candidates = findPasswordCandidates(fields, windowSignature, PASSWORD_SCORE_THRESHOLD);
        if (candidates.isEmpty()) {
            return false;
        }

        Candidate best = candidates.get(0);
        boolean bestExplicit = isExplicitPasswordCandidate(best.node, best.signature);
        boolean filledAny = false;
        for (Candidate candidate : candidates) {
            boolean isBest = candidate == best;
            boolean nearBest = candidate.score >= best.score - PASSWORD_NEAR_BEST_DELTA;
            boolean safeCompanion = isExplicitPasswordCandidate(candidate.node, candidate.signature)
                    || isPasswordConfirmationField(candidate.signature);
            if (isBest || (bestExplicit && nearBest && safeCompanion)) {
                filledAny |= fillNode(candidate.node, password);
            }
        }
        return filledAny;
    }

    private List<Candidate> findEmailCandidates(
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            int threshold
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo node : fields) {
            String nodeContext = buildNodeContext(node);
            int score = scoreEmailCandidate(node, nodeContext, windowSignature, false);
            if (score < threshold) {
                continue;
            }
            candidates.add(new Candidate(node, score, nodeContext));
        }
        Collections.sort(candidates, Comparator.comparingInt((Candidate candidate) -> candidate.score).reversed());
        return candidates;
    }

    private Candidate findBestEmailCandidate(
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            int threshold
    ) {
        Candidate best = null;
        for (AccessibilityNodeInfo node : fields) {
            String nodeContext = buildNodeContext(node);
            int score = scoreEmailCandidate(node, nodeContext, windowSignature, false);
            if (score < threshold) {
                continue;
            }
            if (score < EMAIL_SCORE_THRESHOLD
                    && !isAggressiveSignupEmailCandidate(node, nodeContext, windowSignature, fields)) {
                continue;
            }
            Candidate candidate = new Candidate(node, score, nodeContext);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
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
            Candidate candidate = new Candidate(node, score, nodeContext);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private List<Candidate> findPasswordCandidates(
            List<AccessibilityNodeInfo> fields,
            String windowSignature,
            int threshold
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo node : fields) {
            String nodeContext = buildNodeContext(node);
            int score = scorePasswordCandidate(node, nodeContext, windowSignature, false);
            if (score < threshold) {
                continue;
            }
            candidates.add(new Candidate(node, score, nodeContext));
        }
        Collections.sort(candidates, Comparator.comparingInt((Candidate candidate) -> candidate.score).reversed());
        return candidates;
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
        int signupScore = scoreSignupContext(windowSignature);
        if (shouldFillEmail(nodeContext)) {
            score += 200;
        }
        if (isEmailConfirmationField(nodeContext)) {
            score += 120;
        }
        if (containsAny(nodeContext, "username", "user name", "account", "login", "identifier")) {
            score += signupScore >= 90 ? 95 : 25;
        }
        if (containsAny(nodeContext, "first name", "last name", "full name", "display name", "nickname")) {
            score -= 240;
        }
        if (containsAny(
                nodeContext,
                "password",
                "passcode",
                "otp",
                "verification",
                "code",
                "search",
                "coupon",
                "promo",
                "referral",
                "message",
                "comment"
        )) {
            score -= 240;
        }
        if (containsAny(nodeContext, "phone", "mobile", "telephone", "tel")) {
            score -= 220;
        }
        if (isEmailInputType(node.getInputType())) {
            score += 220;
        }
        if (isPlainTextInputType(node.getInputType()) && signupScore >= 90) {
            score += 45;
        }
        if (isNumericInputType(node.getInputType()) || isPasswordInputType(node.getInputType())) {
            score -= 220;
        }
        if (isMultiLineInputType(node.getInputType())) {
            score -= 180;
        }
        CharSequence className = node.getClassName();
        if (className != null && className.toString().toLowerCase(Locale.US).contains("edittext")) {
            score += 10;
        }
        if (focused) {
            score += 35;
        }
        if (signupScore >= 90) {
            score += 30;
        }
        if (signupScore >= 90
                && containsAny(
                windowSignature,
                "email",
                "mail",
                "sign up",
                "register",
                "create account",
                "join",
                "continue with email"
        )) {
            score += 35;
        }
        if (signupScore >= 90 && looksLikeTextEntry(node)) {
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

    private int scorePasswordCandidate(
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

        int score = 0;
        if (shouldFillPassword(nodeContext)) {
            score += 210;
        }
        if (isPasswordConfirmationField(nodeContext)) {
            score += 160;
        }
        if (containsAny(nodeContext, "email", "mail", "otp", "verification", "code", "search", "phone")) {
            score -= 220;
        }
        if (isPasswordInputType(node.getInputType())) {
            score += 220;
        }
        if (isEmailInputType(node.getInputType()) || isNumericInputType(node.getInputType())) {
            score -= 180;
        }

        int maxLength = node.getMaxTextLength();
        if (maxLength > 0 && maxLength < 6) {
            score -= 120;
        }
        if (focused) {
            score += 35;
        }
        if (containsAny(windowSignature, "set password", "create password", "confirm password", "repeat password")) {
            score += 35;
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

    private boolean isEmailConfirmationField(String signature) {
        return containsAny(
                signature,
                "confirm email",
                "confirmation email",
                "re-enter email",
                "repeat email",
                "verify email"
        );
    }

    private boolean isPasswordConfirmationField(String signature) {
        return containsAny(
                signature,
                "confirm password",
                "confirmation password",
                "repeat password",
                "re-enter password",
                "verify password"
        );
    }

    private boolean isExplicitEmailCandidate(AccessibilityNodeInfo node, String signature) {
        return shouldFillEmail(signature)
                || isEmailConfirmationField(signature)
                || isEmailInputType(node.getInputType());
    }

    private boolean isExplicitPasswordCandidate(AccessibilityNodeInfo node, String signature) {
        return shouldFillPassword(signature)
                || isPasswordConfirmationField(signature)
                || isPasswordInputType(node.getInputType());
    }

    private boolean isLikelySignupTextField(
            AccessibilityNodeInfo node,
            String nodeContext,
            String windowSignature
    ) {
        if (!canAcceptText(node) || !isSignupContext(windowSignature)) {
            return false;
        }
        if (!isPlainTextInputType(node.getInputType()) && !isEmailInputType(node.getInputType())) {
            return false;
        }
        if (containsAny(
                nodeContext,
                "password",
                "passcode",
                "otp",
                "verification",
                "code",
                "search",
                "phone",
                "mobile",
                "first name",
                "last name",
                "full name"
        )) {
            return false;
        }
        return true;
    }

    private boolean isAggressiveSignupEmailCandidate(
            AccessibilityNodeInfo node,
            String nodeContext,
            String windowSignature,
            List<AccessibilityNodeInfo> fields
    ) {
        if (node == null || !canAcceptText(node) || !isStrongSignupContext(windowSignature)) {
            return false;
        }
        if (isExplicitEmailCandidate(node, nodeContext)) {
            return true;
        }
        if (!isLikelySignupTextField(node, nodeContext, windowSignature)) {
            return false;
        }
        if (containsAny(
                nodeContext,
                "username",
                "user name",
                "login",
                "handle",
                "display name",
                "nickname",
                "first name",
                "last name",
                "full name",
                "referral",
                "promo",
                "coupon",
                "message",
                "comment"
        )) {
            return false;
        }

        int eligibleFieldCount = countAggressiveEmailEligibleFields(fields, windowSignature);
        boolean screenHasExplicitEmailCue = containsAny(
                windowSignature,
                "email",
                "e-mail",
                "mail",
                "continue with email",
                "email address"
        );
        boolean screenHasPasswordField = hasPasswordField(fields, windowSignature);
        if (screenHasExplicitEmailCue) {
            return eligibleFieldCount <= 2;
        }
        return screenHasPasswordField && eligibleFieldCount == 1;
    }

    private boolean isStrongSignupContext(String signature) {
        return scoreSignupContext(signature) >= STRONG_SIGNUP_CONTEXT_THRESHOLD;
    }

    private int countAggressiveEmailEligibleFields(
            List<AccessibilityNodeInfo> fields,
            String windowSignature
    ) {
        int count = 0;
        for (AccessibilityNodeInfo field : fields) {
            if (field == null || !canAcceptText(field) || !getNodeValue(field).isEmpty()) {
                continue;
            }
            if (isNumericInputType(field.getInputType())
                    || isPasswordInputType(field.getInputType())
                    || isMultiLineInputType(field.getInputType())) {
                continue;
            }
            String context = buildNodeContext(field);
            if (!isLikelySignupTextField(field, context, windowSignature)) {
                continue;
            }
            if (containsAny(
                    context,
                    "username",
                    "user name",
                    "login",
                    "handle",
                    "first name",
                    "last name",
                    "full name",
                    "display name",
                    "nickname"
            )) {
                continue;
            }
            count++;
        }
        return count;
    }

    private boolean hasPasswordField(List<AccessibilityNodeInfo> fields, String windowSignature) {
        for (AccessibilityNodeInfo field : fields) {
            if (field == null) {
                continue;
            }
            String context = buildNodeContext(field);
            if (scorePasswordCandidate(field, context, windowSignature, false) >= PASSWORD_SCORE_THRESHOLD) {
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

    private boolean isPlainTextInputType(int inputType) {
        if (inputType == 0) {
            return true;
        }
        int typeClass = inputType & InputType.TYPE_MASK_CLASS;
        return typeClass == InputType.TYPE_CLASS_TEXT;
    }

    private boolean isMultiLineInputType(int inputType) {
        return (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
    }

    private boolean fillNode(AccessibilityNodeInfo node, String value) {
        if (!canAcceptText(node) || TextUtils.isEmpty(value)) {
            return false;
        }
        if (value.equals(getNodeValue(node))) {
            return false;
        }

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        if (supportsSetText(node)) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean setTextResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (setTextResult || value.equals(getNodeValue(node))) {
                return true;
            }
        }

        return pasteNodeValue(node, value);
    }

    private boolean pasteNodeValue(AccessibilityNodeInfo node, String value) {
        if (!supportsPaste(node)) {
            return false;
        }

        ClipboardManager clipboardManager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            return false;
        }

        final ClipData previousClip = clipboardManager.getPrimaryClip();
        clipboardManager.setPrimaryClip(ClipData.newPlainText("TempMailAutoFill", value));
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        boolean pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        handler.postDelayed(() -> restoreClipboard(clipboardManager, previousClip), 150L);
        return pasted || value.equals(getNodeValue(node));
    }

    private void restoreClipboard(ClipboardManager clipboardManager, ClipData previousClip) {
        if (clipboardManager == null) {
            return;
        }
        if (previousClip != null) {
            clipboardManager.setPrimaryClip(previousClip);
        } else {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    private static final class Candidate {
        private final AccessibilityNodeInfo node;
        private final int score;
        private final String signature;

        private Candidate(AccessibilityNodeInfo node, int score, String signature) {
            this.node = node;
            this.score = score;
            this.signature = signature;
        }
    }
}
