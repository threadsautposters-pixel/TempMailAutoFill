package com.tempmail.autofill;
import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class AutoFillService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        SharedPreferences prefs = getSharedPreferences("auto", MODE_PRIVATE);
        String email = prefs.getString("latest_email", null);
        String code = prefs.getString("latest_code", null);
        List<AccessibilityNodeInfo> fields = root.findAccessibilityNodeInfosByViewId("android:id/edit");
        for (AccessibilityNodeInfo node : fields) {
            if (node == null || !node.isEditable()) continue;
            CharSequence hint = node.getHintText();
            if (hint != null) {
                String h = hint.toString().toLowerCase();
                if (h.contains("email") && email != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, email);
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                } else if ((h.contains("code") || h.contains("verification")) && code != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code);
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }
        }
    }
    @Override
    public void onInterrupt() {}
}
