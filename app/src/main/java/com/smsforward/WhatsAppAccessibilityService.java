package com.smsforward;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class WhatsAppAccessibilityService extends AccessibilityService {

    private static final String TAG = "WAService";
    private static final String WA_PKG = "com.whatsapp";
    private Handler handler;
    private long lastActionTime = 0;
    private boolean sentSuccessfully = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service created");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service connected OK");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        try {
            CharSequence pkg = event.getPackageName();
            if (pkg == null || !WA_PKG.equals(pkg.toString())) return;

            // Check if we have a pending message
            SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
            String pending = prefs.getString("pending_message", "");
            long pendingTime = prefs.getLong("pending_time", 0);
            long now = System.currentTimeMillis();

            // Expire after 120 seconds (was 60, give more time)
            if (pending.isEmpty() || now - pendingTime > 120000) return;

            // Throttle to 800ms (was 1500ms - faster now)
            if (now - lastActionTime < 800) return;
            lastActionTime = now;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // Log the current window state for debugging
            logNodeTree(root, 0);

            // Step 1: Try to click "Continue to chat" on the wa.me/api.whatsapp.com confirmation screen
            boolean clickedContinue = clickContinueButton(root);
            if (clickedContinue) {
                Log.d(TAG, "Clicked continue/send-to-chat button");
                root.recycle();
                return;
            }

            // Step 2: Try to find and click the send button in the chat screen
            boolean clickedSend = clickSendButton(root);
            if (clickedSend) {
                Log.d(TAG, "Clicked send button!");
                sentSuccessfully = true;
                // Clear pending message
                prefs.edit()
                    .putString("pending_message", "")
                    .putLong("pending_time", 0)
                    .apply();

                // Go home after a short delay
                handler.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    sentSuccessfully = false;
                }, 1500);
            }

            root.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error in event", e);
        }
    }

    private void logNodeTree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) return; // Only log first 3 levels
        try {
            String indent = "";
            for (int i = 0; i < depth; i++) indent += "  ";
            String cls = node.getClassName() != null ? node.getClassName().toString() : "null";
            String text = node.getText() != null ? node.getText().toString() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
            String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            boolean clickable = node.isClickable();
            if (!text.isEmpty() || !desc.isEmpty() || clickable || !viewId.isEmpty()) {
                Log.d(TAG, indent + cls + " click=" + clickable + " id=" + viewId +
                    " text='" + text + "' desc='" + desc + "'");
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) logNodeTree(child, depth + 1);
            }
        } catch (Exception e) {}
    }

    private boolean clickContinueButton(AccessibilityNodeInfo root) {
        // WhatsApp api.whatsapp.com/send confirmation screen buttons
        // Try by known view IDs first
        String[] continueIds = {
            "com.whatsapp:id/action_button",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/ok_btn",
            "com.whatsapp:id/btn_ok",
            "com.whatsapp:id/primary_button"
        };
        for (String id : continueIds) {
            if (clickById(root, id)) return true;
        }

        // Try by text content (Hebrew, English, various forms)
        String[] continueTexts = {
            "continue to chat", "continue", "המשך לצ'אט", "המשך",
            "send to", "שלח ל", "open chat", "פתח צ'אט",
            "message", "שלח הודעה", "chat"
        };
        if (clickByTexts(root, continueTexts)) return true;

        // Try by content description
        String[] continueDescs = {"continue", "המשך", "send", "שלח", "open"};
        if (clickByDescriptions(root, continueDescs)) return true;

        // Try to find any green/colored button (WhatsApp's continue button is green)
        if (clickFirstButton(root)) return true;

        return false;
    }

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        // The send button in WhatsApp chat
        if (clickById(root, "com.whatsapp:id/send")) return true;

        // Try by content description for the send button
        String[] sendDescs = {"send", "שלח", "שליחה", "إرسال"};
        for (String desc : sendDescs) {
            AccessibilityNodeInfo btn = findByDescription(root, desc);
            if (btn != null && btn.isClickable()) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }

        // Try finding ImageButton with send-related description at bottom of screen
        AccessibilityNodeInfo sendImg = findSendImageButton(root);
        if (sendImg != null) {
            sendImg.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }

        return false;
    }

    private boolean clickById(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.get(0);
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
                // Try parent
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null && parent.isClickable()) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean clickByTexts(AccessibilityNodeInfo node, String[] keywords) {
        if (node == null) return false;
        try {
            CharSequence text = node.getText();
            if (text != null) {
                String t = text.toString().toLowerCase();
                for (String kw : keywords) {
                    if (t.contains(kw.toLowerCase())) {
                        if (node.isClickable()) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                        // Try clicking parent (button wrapping text)
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null && parent.isClickable()) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                        // Try grandparent
                        if (parent != null) {
                            AccessibilityNodeInfo gp = parent.getParent();
                            if (gp != null && gp.isClickable()) {
                                gp.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                return true;
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (clickByTexts(child, keywords)) return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean clickByDescriptions(AccessibilityNodeInfo node, String[] keywords) {
        if (node == null) return false;
        try {
            CharSequence desc = node.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                for (String kw : keywords) {
                    if (d.contains(kw.toLowerCase())) {
                        if (node.isClickable()) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null && parent.isClickable()) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return true;
                        }
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (clickByDescriptions(child, keywords)) return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean clickFirstButton(AccessibilityNodeInfo node) {
        // Find the first clickable Button node (for the continue screen)
        if (node == null) return false;
        try {
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String cn = cls.toString();
                if ((cn.contains("Button") || cn.contains("button")) && node.isClickable()) {
                    // Don't click back/navigation buttons - only content area buttons
                    CharSequence desc = node.getContentDescription();
                    if (desc != null) {
                        String d = desc.toString().toLowerCase();
                        if (d.contains("back") || d.contains("navigate") || d.contains("חזור")) {
                            return false;
                        }
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (clickFirstButton(child)) return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo node, String keyword) {
        if (node == null) return null;
        try {
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().toLowerCase().contains(keyword.toLowerCase())) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    AccessibilityNodeInfo result = findByDescription(child, keyword);
                    if (result != null) return result;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private AccessibilityNodeInfo findSendImageButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().contains("ImageButton") && node.isClickable()) {
                CharSequence desc = node.getContentDescription();
                if (desc != null) {
                    String d = desc.toString().toLowerCase();
                    if (d.contains("send") || d.contains("שלח") || d.contains("שליחה") || d.contains("إرسال")) {
                        return node;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    AccessibilityNodeInfo result = findSendImageButton(child);
                    if (result != null) return result;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }
}
