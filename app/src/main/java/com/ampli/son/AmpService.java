package com.ampli.son;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

/** Service de premier plan : garde l'amplification active écran éteint. */
public class AmpService extends Service implements AudioEngine.Callback {

    static final String ACTION_STOP = "com.ampli.son.STOP";
    private static final String CHANNEL = "ampli_son";
    private static final int NOTIF_ID = 1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AudioEngine.get().stop();
            shutdown();
            return START_NOT_STICKY;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Amplification",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Indique que l'amplification est active");
        nm.createNotificationChannel(ch);

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 30) {
            // 128 = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE (API 30)
            startForeground(NOTIF_ID, n, 128);
        } else {
            startForeground(NOTIF_ID, n);
        }
        AudioEngine engine = AudioEngine.get();
        engine.setCallback(this);
        engine.start(this);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AmpService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource("android", android.R.drawable.ic_media_pause),
                "Arrêter", stopPi).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_btn_speak_now))
                .setContentTitle("Ampli Son actif")
                .setContentText("Amplification du micro vers votre casque")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(stopAction)
                .build();
    }

    private void shutdown() {
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onEngineStopped(String error) {
        shutdown();
    }

    @Override
    public void onDestroy() {
        AudioEngine.get().stop();
        super.onDestroy();
    }
}
