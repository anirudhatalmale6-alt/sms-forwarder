package com.smsforward;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.SmsMessage;
import android.util.Log;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String CHANNEL_ID = "sms_forward_urgent";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        String format = bundle.getString("format", "3gpp");
        StringBuilder fullMessage = new StringBuilder();
        String sender = "";

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            sender = sms.getDisplayOriginatingAddress();
            fullMessage.append(sms.getMessageBody());
        }

        String messageText = fullMessage.toString();
        Log.d(TAG, "SMS from " + sender + ": " + messageText);

        SharedPreferences prefs = context.getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
        String targetNumber = prefs.getString("whatsapp_number", "0587891654");
        String whatsappMsg = "SMS מ: " + sender + "\n" + messageText;
        String formattedNumber = formatPhone(targetNumber);

        // Save to log
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = time + " | " + sender + " | " + messageText;
        String existingLog = prefs.getString("forward_log", "");
        String newLog = logEntry + "\n" + existingLog;
        String[] lines = newLog.split("\n");
        if (lines.length > 20) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) sb.append(lines[i]).append("\n");
            newLog = sb.toString().trim();
        }

        prefs.edit()
            .putString("forward_log", newLog)
            .putString("pending_message", whatsappMsg)
            .putString("pending_number", formattedNumber)
            .putLong("pending_time", System.currentTimeMillis())
            .apply();

        // Create notification channel (must be IMPORTANCE_HIGH for full-screen intent)
        createNotificationChannel(context);

        // METHOD: Full-Screen Intent (most reliable on Android 10+)
        // This is the same mechanism phone call and alarm apps use.
        // The system itself launches the activity, bypassing background restrictions.
        launchViaFullScreenIntent(context, whatsappMsg, formattedNumber);
    }

    private void launchViaFullScreenIntent(Context context, String message, String number) {
        try {
            // Create intent for TrampolineActivity
            Intent trampolineIntent = new Intent(context, TrampolineActivity.class);
            trampolineIntent.putExtra("message", message);
            trampolineIntent.putExtra("number", number);
            trampolineIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, (int) System.currentTimeMillis(), trampolineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Also create a content intent (when user taps the notification)
            PendingIntent contentIntent = PendingIntent.getActivity(
                context, (int) (System.currentTimeMillis() + 1), trampolineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            Notification notification = builder
                .setContentTitle("SMS → WhatsApp")
                .setContentText("מעביר הודעה...")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_CALL) // Highest priority - like a phone call
                .setContentIntent(contentIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true) // KEY: launches activity immediately
                .setAutoCancel(true)
                .build();

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(42, notification);
                Log.d(TAG, "Full-screen intent notification posted");
            }

            // ALSO try direct start as backup (works if overlay permission is granted)
            if (Build.VERSION.SDK_INT < 29 ||
                (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(context))) {
                try {
                    context.startActivity(trampolineIntent);
                    Log.d(TAG, "Also launched trampoline directly");
                } catch (Exception e) {
                    Log.e(TAG, "Direct launch failed (expected on some devices)", e);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Full-screen intent failed", e);
            // Last resort: try foreground service
            try {
                Intent serviceIntent = new Intent(context, ForwardService.class);
                serviceIntent.putExtra("message", message);
                serviceIntent.putExtra("number", number);
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e2) {
                Log.e(TAG, "Service fallback also failed", e2);
            }
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SMS Forward Urgent",
                NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("העברת SMS לוואצאפ");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private String formatPhone(String phone) {
        phone = phone.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) phone = "972" + phone.substring(1);
        if (!phone.startsWith("972")) phone = "972" + phone;
        return phone;
    }
}
