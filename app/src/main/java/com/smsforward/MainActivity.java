package com.smsforward;

import android.Manifest;
import android.app.Activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText phoneInput;
    private TextView statusSms;
    private TextView statusOverlay;
    private TextView statusAccessibility;
    private TextView statusFullScreen;
    private TextView statusBattery;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        requestSmsPermission();
        startMonitorService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#1a1a2e"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(50, 80, 50, 50);

        // Title
        TextView title = new TextView(this);
        title.setText("SMS → WhatsApp");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        addSpacer(root, 40);

        // Phone input
        TextView label = new TextView(this);
        label.setText("מספר וואצאפ יעד:");
        label.setTextSize(16);
        label.setTextColor(Color.parseColor("#cccccc"));
        root.addView(label);
        addSpacer(root, 8);

        phoneInput = new EditText(this);
        phoneInput.setHint("0587891654");
        phoneInput.setTextColor(Color.WHITE);
        phoneInput.setHintTextColor(Color.parseColor("#666666"));
        phoneInput.setTextSize(18);
        phoneInput.setPadding(30, 25, 30, 25);
        phoneInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#16213e"));
        inputBg.setCornerRadius(20);
        inputBg.setStroke(2, Color.parseColor("#333333"));
        phoneInput.setBackground(inputBg);
        root.addView(phoneInput);

        SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
        phoneInput.setText(prefs.getString("whatsapp_number", "0587891654"));

        addSpacer(root, 12);
        addButton(root, "שמור מספר", "#2196F3", v -> saveNumber());
        addSpacer(root, 30);

        // Status section
        TextView statusTitle = new TextView(this);
        statusTitle.setText("סטטוס:");
        statusTitle.setTextSize(18);
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTypeface(null, Typeface.BOLD);
        root.addView(statusTitle);
        addSpacer(root, 12);

        statusSms = makeStatusLine(root, "1. הרשאת SMS");
        statusOverlay = makeStatusLine(root, "2. תצוגה מעל אפליקציות");
        statusAccessibility = makeStatusLine(root, "3. שירות נגישות");
        statusFullScreen = makeStatusLine(root, "4. התראות מסך מלא");
        statusBattery = makeStatusLine(root, "5. ללא אופטימיזציית סוללה");

        addSpacer(root, 20);

        // Action buttons
        addButton(root, "1. אשר הרשאת SMS", "#FF9800", v -> requestSmsPermission());
        addSpacer(root, 10);
        addButton(root, "2. אשר תצוגה מעל אפליקציות", "#FF9800", v -> requestOverlayPermission());
        addSpacer(root, 10);
        addButton(root, "3. הפעל שירות נגישות", "#FF9800", v -> openAccessibilitySettings());
        addSpacer(root, 10);
        addButton(root, "4. בטל אופטימיזציית סוללה", "#FF9800", v -> requestBatteryOptimization());

        addSpacer(root, 20);

        // Test button
        addButton(root, "בדוק שליחה (טסט)", "#4CAF50", v -> testSend());

        addSpacer(root, 30);

        // Log
        TextView logLabel = new TextView(this);
        logLabel.setText("יומן SMS:");
        logLabel.setTextSize(16);
        logLabel.setTextColor(Color.parseColor("#cccccc"));
        root.addView(logLabel);
        addSpacer(root, 8);

        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(Color.parseColor("#aaaaaa"));
        logText.setPadding(20, 20, 20, 20);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(Color.parseColor("#0f0f23"));
        logBg.setCornerRadius(15);
        logText.setBackground(logBg);
        logText.setText("מחכה ל-SMS...");
        root.addView(logText);

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView makeStatusLine(LinearLayout root, String label) {
        TextView tv = new TextView(this);
        tv.setText(label + ": בודק...");
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#888888"));
        tv.setPadding(20, 8, 20, 8);
        root.addView(tv);
        return tv;
    }

    private void addButton(LinearLayout root, String text, String color, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(20);
        btn.setBackground(bg);
        btn.setPadding(0, 25, 0, 25);
        btn.setOnClickListener(listener);
        root.addView(btn);
    }

    private void addSpacer(LinearLayout root, int dp) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(dp * getResources().getDisplayMetrics().density)));
        root.addView(spacer);
    }

    private void startMonitorService() {
        Intent serviceIntent = new Intent(this, SmsMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void saveNumber() {
        String num = phoneInput.getText().toString().trim();
        if (num.isEmpty()) { Toast.makeText(this, "נא להכניס מספר", Toast.LENGTH_SHORT).show(); return; }
        getSharedPreferences("sms_forward", Context.MODE_PRIVATE)
            .edit().putString("whatsapp_number", num).apply();
        Toast.makeText(this, "נשמר: " + num, Toast.LENGTH_SHORT).show();
    }

    private void testSend() {
        // Simulate an SMS forward to test the WhatsApp opening
        SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
        String targetNumber = prefs.getString("whatsapp_number", "0587891654");
        String formattedNumber = formatPhone(targetNumber);
        String testMsg = "SMS מ: +972500000000\nזוהי הודעת בדיקה - TEST";

        prefs.edit()
            .putString("pending_message", testMsg)
            .putString("pending_number", formattedNumber)
            .putLong("pending_time", System.currentTimeMillis())
            .apply();

        // Launch directly via TrampolineActivity (we're in foreground)
        Intent intent = new Intent(this, TrampolineActivity.class);
        intent.putExtra("message", testMsg);
        intent.putExtra("number", formattedNumber);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        Toast.makeText(this, "פותח וואצאפ...", Toast.LENGTH_SHORT).show();
    }

    private void requestSmsPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] perms = { Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS };
            boolean need = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need = true;
            }
            if (need) requestPermissions(perms, 100);

            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
                }
            }
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 200);
            Toast.makeText(this, "הפעל 'אפשר תצוגה מעל אפליקציות אחרות'", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "כבר מאושר!", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryOptimization() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // Fallback to battery optimization settings
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "פתח הגדרות > סוללה > אופטימיזציה ובטל עבור SMS Forward", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        Toast.makeText(this, "הפעל את 'SMS Forward' ברשימה", Toast.LENGTH_LONG).show();
    }

    private void updateStatus() {
        boolean sms = true;
        if (Build.VERSION.SDK_INT >= 23) {
            sms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        }
        boolean overlay = true;
        if (Build.VERSION.SDK_INT >= 23) {
            overlay = Settings.canDrawOverlays(this);
        }
        boolean accessibility = isAccessibilityEnabled();

        // Full-screen intent is always available for targetSdk <= 33
        boolean fullScreen = true;

        boolean battery = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        setStatus(statusSms, "1. הרשאת SMS", sms);
        setStatus(statusOverlay, "2. תצוגה מעל אפליקציות", overlay);
        setStatus(statusAccessibility, "3. שירות נגישות", accessibility);
        setStatus(statusFullScreen, "4. התראות מסך מלא", fullScreen);
        setStatus(statusBattery, "5. ללא אופטימיזציית סוללה", battery);

        SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
        String log = prefs.getString("forward_log", "");
        if (!log.isEmpty()) logText.setText(log);
    }

    private void setStatus(TextView tv, String label, boolean ok) {
        tv.setText(label + ": " + (ok ? "מאושר ✓" : "לא פעיל ✗"));
        tv.setTextColor(ok ? Color.parseColor("#4CAF50") : Color.parseColor("#f44336"));
    }

    private boolean isAccessibilityEnabled() {
        String service = getPackageName() + "/" + WhatsAppAccessibilityService.class.getName();
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String val = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (val != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(val);
                while (splitter.hasNext()) {
                    if (splitter.next().equalsIgnoreCase(service)) return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private String formatPhone(String phone) {
        phone = phone.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) phone = "972" + phone.substring(1);
        if (!phone.startsWith("972")) phone = "972" + phone;
        return phone;
    }
}
