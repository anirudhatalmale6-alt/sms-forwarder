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
    private boolean isPolling = false;
    private int pollCount = 0;
    private static final int MAX_POLLS = 30; // Try for 15 seconds (30 * 500ms)

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
            if (!hasPendingMessage()) return;

            Log.d(TAG, "WhatsApp event: " + event.getEventType() + " class=" + event.getClassName());

            // Try to click buttons immediately
            tryClickButtons();

            // Start polling if not already polling
            if (!isPolling) {
                startPolling();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in event", e);
        }
    }

    private void startPolling() {
        isPolling = true;
        pollCount = 0;
        Log.d(TAG, "Starting poll loop");
        handler.postDelayed(pollRunnable, 500);
    }

    private void stopPolling() {
        isPolling = false;
        pollCount = 0;
        handler.removeCallbacks(pollRunnable);
        Log.d(TAG, "Stopped polling");
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollCount++;
            if (pollCount > MAX_POLLS || !hasPendingMessage()) {
                stopPolling();
                return;
            }

            Log.d(TAG, "Poll #" + pollCount);
            tryClickButtons();

            // Continue polling
            if (isPolling && hasPendingMessage()) {
                handler.postDelayed(this, 500);
            }
        }
    };

    private boolean hasPendingMessage() {
        try {
            SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
            String pending = prefs.getString("pending_message", "");
            long pendingTime = prefs.getLong("pending_time", 0);
            long now = System.currentTimeMillis();
            return !pending.isEmpty() && now - pendingTime < 120000;
        } catch (Exception e) {
            return false;
        }
    }

    private void tryClickButtons() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.d(TAG, "Root is null");
                return;
            }

            // Log all clickable elements for debugging
            logClickables(root, 0);

            // STEP 1: Try "Continue to chat" / confirmation buttons
            if (clickContinueButton(root)) {
                Log.d(TAG, ">>> Clicked CONTINUE button");
                root.recycle();
                return;
            }

            // STEP 2: Try send button
            if (clickSendButton(root)) {
                Log.d(TAG, ">>> Clicked SEND button!");
                clearPendingAndGoHome();
                root.recycle();
                return;
            }

            root.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error trying buttons", e);
        }
    }

    private void clearPendingAndGoHome() {
        SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
        prefs.edit()
            .putString("pending_message", "")
            .putLong("pending_time", 0)
            .apply();
        stopPolling();

        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }, 1500);
    }

    private boolean clickContinueButton(AccessibilityNodeInfo root) {
        // Try by known WhatsApp view IDs for the confirmation screen
        String[] ids = {
            "com.whatsapp:id/action_button",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/ok_btn",
            "com.whatsapp:id/btn_ok",
            "com.whatsapp:id/primary_button",
            "com.whatsapp:id/positive_btn"
        };
        for (String id : ids) {
            if (clickNodeById(root, id)) return true;
        }

        // Try by text (Hebrew + English)
        String[] texts = {
            "continue to chat", "continue", "המשך לצ'אט", "המשך",
            "send to", "שלח ל", "open chat", "פתח צ'אט",
            "שלח הודעה", "message"
        };
        if (clickNodeByText(root, texts)) return true;

        return false;
    }

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        // Method 1: By exact WhatsApp send button ID
        if (clickNodeById(root, "com.whatsapp:id/send")) return true;

        // Method 2: By content description containing "send" / "שלח"
        AccessibilityNodeInfo sendNode = findNodeByDesc(root, new String[]{"send", "שלח", "שליחה"});
        if (sendNode != null) {
            if (performClick(sendNode)) return true;
        }

        // Method 3: Find any ImageButton that looks like a send button
        // In WhatsApp, the send button is an ImageButton with send-related description
        AccessibilityNodeInfo imgBtn = findSendImageButton(root);
        if (imgBtn != null) {
            if (performClick(imgBtn)) return true;
        }

        // Method 4: Find by view ID pattern - WhatsApp sometimes uses different IDs
        String[] altIds = {
            "com.whatsapp:id/fab_send",
            "com.whatsapp:id/send_container",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/conversation_send",
            "com.whatsapp:id/entry_container_send"
        };
        for (String id : altIds) {
            if (clickNodeById(root, id)) return true;
        }

        return false;
    }

    // ---- Helper methods ----

    private boolean clickNodeById(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performClick(node)) return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean clickNodeByText(AccessibilityNodeInfo node, String[] keywords) {
        if (node == null) return false;
        try {
            // Check this node's text
            CharSequence text = node.getText();
            if (text != null) {
                String t = text.toString().toLowerCase();
                for (String kw : keywords) {
                    if (t.contains(kw.toLowerCase())) {
                        if (performClick(node)) return true;
                    }
                }
            }

            // Check this node's content description
            CharSequence desc = node.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                for (String kw : keywords) {
                    if (d.contains(kw.toLowerCase())) {
                        if (performClick(node)) return true;
                    }
                }
            }

            // Recurse into children
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (clickNodeByText(child, keywords)) return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private AccessibilityNodeInfo findNodeByDesc(AccessibilityNodeInfo node, String[] keywords) {
        if (node == null) return null;
        try {
            CharSequence desc = node.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                for (String kw : keywords) {
                    if (d.contains(kw.toLowerCase())) return node;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    AccessibilityNodeInfo found = findNodeByDesc(child, keywords);
                    if (found != null) return found;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private AccessibilityNodeInfo findSendImageButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String cn = cls.toString();
                if ((cn.contains("ImageButton") || cn.contains("ImageView")) && node.isClickable()) {
                    CharSequence desc = node.getContentDescription();
                    if (desc != null) {
                        String d = desc.toString().toLowerCase();
                        if (d.contains("send") || d.contains("שלח") || d.contains("שליחה")) {
                            return node;
                        }
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    AccessibilityNodeInfo found = findSendImageButton(child);
                    if (found != null) return found;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    /**
     * Try to click a node. If node itself isn't clickable, try parent and grandparent.
     */
    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            // Try clicking the node itself
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
            // Try grandparent
            if (parent != null) {
                AccessibilityNodeInfo gp = parent.getParent();
                if (gp != null && gp.isClickable()) {
                    gp.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private void logClickables(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 4) return;
        try {
            String indent = "";
            for (int i = 0; i < depth; i++) indent += "  ";

            boolean clickable = node.isClickable();
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            String text = node.getText() != null ? node.getText().toString() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
            String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";

            if (clickable || !viewId.isEmpty() || !desc.isEmpty()) {
                Log.d(TAG, indent + "[" + cls + "] click=" + clickable +
                    " id=" + viewId + " text='" + text + "' desc='" + desc + "'");
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) logClickables(child, depth + 1);
            }
        } catch (Exception e) {}
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
        stopPolling();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPolling();
    }
}
