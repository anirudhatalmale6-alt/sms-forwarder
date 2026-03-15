package com.smsforward;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.SmsMessage;
import android.util.Log;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent foreground service that monitors SMS.
 * Since this is a FOREGROUND service, it CAN start activities (unlike BroadcastReceivers).
 * This is the key fix for Samsung/Android 10+ devices that block background activity launches.
 */
public class SmsMonitorService extends Service {

    private static final String TAG = "SmsMonitor";
    private static final String CHANNEL_ID = "sms_monitor_channel";
    private BroadcastReceiver smsReceiver;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Show persistent notification
        Notification notification = buildNotification();
        startForeground(100, notification);

        // Register SMS receiver in service context (foreground!)
        registerSmsReceiver();

        // Acquire partial wake lock to keep service alive
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsforward:monitor");
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock error", e);
        }

        Log.d(TAG, "Service started - monitoring SMS");
        return START_STICKY; // Restart if killed
    }

    private void registerSmsReceiver() {
        if (smsReceiver != null) return; // Already registered

        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
                Log.d(TAG, "=== SMS RECEIVED in foreground service! ===");
                handleSms(intent);
            }
        };

        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        filter.setPriority(999);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(smsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(smsReceiver, filter);
        }
        Log.d(TAG, "SMS receiver registered in foreground service context");
    }

    private void handleSms(Intent intent) {
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

        SharedPreferences prefs = getSharedPreferences("sms_forward", Context.MODE_PRIVATE);
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

        // Launch TrampolineActivity directly from foreground service!
        // This WORKS because we're a foreground service.
        launchWhatsApp(whatsappMsg, formattedNumber);
    }

    private void launchWhatsApp(String message, String number) {
        Log.d(TAG, "Launching WhatsApp from foreground service");
        try {
            Intent trampolineIntent = new Intent(this, TrampolineActivity.class);
            trampolineIntent.putExtra("message", message);
            trampolineIntent.putExtra("number", number);
            trampolineIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            );
            startActivity(trampolineIntent);
            Log.d(TAG, "TrampolineActivity launched successfully!");
        } catch (Exception e) {
            Log.e(TAG, "Direct launch failed, trying alternative", e);
            // Fallback: try opening WhatsApp directly
            try {
                String encodedMsg = URLEncoder.encode(message, "UTF-8");
                String url = "https://api.whatsapp.com/send?phone=" + number + "&text=" + encodedMsg;
                Intent waIntent = new Intent(Intent.ACTION_VIEW);
                waIntent.setData(android.net.Uri.parse(url));
                waIntent.setPackage("com.whatsapp");
                waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(waIntent);
                Log.d(TAG, "WhatsApp opened directly from service");
            } catch (Exception e2) {
                Log.e(TAG, "All launch methods failed", e2);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SMS Monitor",
                NotificationManager.IMPORTANCE_LOW); // Low so it doesn't make sound
            channel.setDescription("מאזין ל-SMS ומעביר לוואצאפ");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("SMS Forward פעיל")
            .setContentText("ממתין להודעות SMS...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private String formatPhone(String phone) {
        phone = phone.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) phone = "972" + phone.substring(1);
        if (!phone.startsWith("972")) phone = "972" + phone;
        return phone;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (smsReceiver != null) {
            try { unregisterReceiver(smsReceiver); } catch (Exception e) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) {}
        }
        Log.d(TAG, "Service destroyed");

        // Try to restart self
        Intent restartIntent = new Intent(this, SmsMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
