package com.closersrt.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the three loopback servers alive while the app is in the background.
 *
 * WHY THIS EXISTS
 * ---------------
 * The servers used to run in MainActivity's process with no foreground
 * service.  On MIUI (and stock Android 12+) an activity that leaves the
 * screen is frozen, and a frozen process stops answering on its listening
 * sockets: the game's libcurl then reports
 *
 *   connect to 127.0.0.1 port 8443 failed: Connection timed out
 *   libcurl error: 28 (Timeout was reached)
 *
 * and the login flow dies at Download_ServiceInfo with no visible error.
 * Running as a foreground service keeps the process exempt from the freezer.
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.closersrt.server.SERVERS_START";
    public static final String ACTION_STOP = "com.closersrt.server.SERVERS_STOP";

    private static final String CHANNEL = "closers-proxy-servers";
    private static final int NOTIF_ID = 4712;

    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServers();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        startServers();
        return START_STICKY;
    }

    private void startServers() {
        LocalWebServer.get().start(MainActivity.WEB_PORT);
        GameServer.start();
        PatchServer.start(getApplicationContext());
        running = true;
        Status.log("servers(service): web=" + LocalWebServer.get().isRunning()
                + " game=" + GameServer.isRunning()
                + " patch=" + PatchServer.isRunning());
    }

    private void stopServers() {
        PatchServer.stop();
        GameServer.stop();
        LocalWebServer.get().stop();
        running = false;
        Status.log("servers(service) stopped");
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "ClosersRT servers", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("ClosersRT servers")
         .setContentText("web 8080 / game 8500 / patch 8443")
         .setSmallIcon(android.R.drawable.stat_sys_download_done)
         .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        // Keep the sockets alive if the OS rebinds; only an explicit stop
        // should tear them down.
        super.onDestroy();
    }
}
