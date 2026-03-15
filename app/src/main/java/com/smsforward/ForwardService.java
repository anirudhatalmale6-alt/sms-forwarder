package com.smsforward;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class ForwardService extends Service {

    private static final String TAG = "ForwardService";
    private static final String CHANNEL_ID = "sms_forward_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification("מעביר SMS...");
        startForeground(1, notification);

        String message = intent != null ? intent.getStringExtra("message") : null;
        String number = intent != null ? intent.getStringExtra("number") : null;

        if (message != null && !message.isEmpty() && number != null && !number.isEmpty()) {
            // Launch TrampolineActivity from the foreground service context
            try {
                Intent trampolineIntent = new Intent(this, TrampolineActivity.class);
                trampolineIntent.putExtra("message", message);
                trampolineIntent.putExtra("number", number);
                trampolineIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(trampolineIntent);
                Log.d(TAG, "Launched TrampolineActivity from service");
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch trampoline from service", e);
            }
        }

        // Stop after 10 seconds
        final int sid = startId;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopForeground(true);
            stopSelf(sid);
        }, 10000);

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "SMS Forward", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("העברת SMS לוואצאפ");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setContentTitle("SMS Forward")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
