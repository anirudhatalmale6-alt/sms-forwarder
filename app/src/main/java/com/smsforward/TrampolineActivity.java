package com.smsforward;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.net.URLEncoder;

/**
 * Transparent trampoline activity that immediately opens WhatsApp and finishes.
 * Launched via Full-Screen Intent from notification — this bypasses Android 10+
 * background activity launch restrictions (same mechanism as phone call apps).
 */
public class TrampolineActivity extends Activity {

    private static final String TAG = "Trampoline";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make this activity work even on lock screen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        // Dismiss the notification
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(42);
        } catch (Exception e) {}

        // Get data from intent or SharedPreferences
        String message = getIntent().getStringExtra("message");
        String number = getIntent().getStringExtra("number");

        if (message == null || number == null) {
            SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
            message = prefs.getString("pending_message", "");
            number = prefs.getString("pending_number", "");
        }

        if (message != null && !message.isEmpty() && number != null && !number.isEmpty()) {
            openWhatsApp(message, number);
        } else {
            Log.e(TAG, "No message/number to forward");
        }

        finish();
    }

    private void openWhatsApp(String message, String number) {
        try {
            String encodedMsg = URLEncoder.encode(message, "UTF-8");
            // Use api.whatsapp.com/send which opens directly into chat
            String url = "https://api.whatsapp.com/send?phone=" + number + "&text=" + encodedMsg;

            Intent waIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            waIntent.setPackage("com.whatsapp");
            waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(waIntent);
            Log.d(TAG, "WhatsApp opened via trampoline");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open WhatsApp", e);
            // Try without package restriction (opens browser/chooser)
            try {
                String encodedMsg = URLEncoder.encode(message, "UTF-8");
                String url = "https://wa.me/" + number + "?text=" + encodedMsg;
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Exception e2) {
                Log.e(TAG, "Fallback also failed", e2);
            }
        }
    }
}
