package com.closersrt.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Socket;

/**
 * The VPN that captures Closers RT's traffic.
 *
 * Why a VPN at all: the retail client dials hard-coded endpoints
 * (`download.closersrt.com` over HTTPS, and the game server IP it reads from
 * `service-live.txt`, on port 8500).  On a non-rooted device the only supported
 * way to redirect another app's traffic is `VpnService`, so we establish a tun
 * that routes everything and terminate the interesting flows in userspace.
 *
 * The device's own traffic is unaffected because every socket this app opens is
 * passed through {@link VpnService#protect}.
 *
 * Nested classes are named rather than anonymous: R8/D8 8.2 in build-tools
 * 34.0.0 crashes on `Foo$1.class` files (see MainActivity's note).
 */
public class ProxyVpnService extends VpnService implements Runnable {

    public static final String ACTION_START = "com.closersrt.server.START";
    public static final String ACTION_STOP = "com.closersrt.server.STOP";

    private static final String CHANNEL = "closers-proxy";
    private static final int NOTIF_ID = 4711;

    /** The tun address handed to the system. */
    static final String TUN_ADDRESS = "10.9.9.1";
    static final int TUN_PREFIX = 32;

    /** The DNS server we advertise; our relay answers it. */
    static final String DNS_ADDRESS = "10.9.9.9";

    /** Where we send the (dead) patch host. */
    static final String PATCH_IP = "10.9.9.52";

    /** Hostnames we answer ourselves instead of forwarding. */
    static final String[] PATCH_HOSTS = {"download.closersrt.com", "closersrt.com"};

    /**
     * The dead game server.  Its address comes from the retail
     * `service-live.txt`, so it is fixed and we can route exactly it.
     */
    static final String GAME_SERVER_IP = "175.106.101.33";

    /**
     * Catch-all mode.
     *
     * WHY IT DEFAULTS TO TRUE NOW
     *
     * The narrow mode (three /32 routes) was meant to leave every other app
     * alone.  It has a fatal flaw for this game though: `addRoute(ip, 32)`
     * steers only the *destination* addresses we named, while DNS resolution
     * still goes through the system resolver, which keeps asking the REAL
     * upstream servers.  `download.closersrt.com` is gone (NXDOMAIN), so the
     * client gets
     *
     *   libcurl error: 6 (Couldn't resolve host name)
     *   Download_ServiceInfo_Start -> Download_ServiceInfo_Fail
     *
     * and never reaches our patch server at all - the "stuck on the login
     * screen" the player reported.  Our Dns responder was never even called:
     * the query did not enter the tunnel.
     *
     * Catch-all fixes it by making the VPN the default route and the DNS
     * server, so every lookup arrives in TunRelay and `isOurs()` can answer
     * the dead patch host with our own address.  Queries for live hosts are
     * still forwarded upstream, so real services keep working.
     */
    static volatile boolean CATCH_ALL = true;

    private static volatile boolean running = false;
    private static volatile String lastMessage = "idle";

    private ParcelFileDescriptor tun;
    private Thread worker;

    public static boolean isRunning() { return running; }
    public static String lastMessage() { return lastMessage; }

    static void setMessage(String s) {
        lastMessage = s;
        Status.log(s);
    }

    /**
     * Exempts our own sockets from the tunnel.
     *
     * `static` on purpose: non-static inner classes make R8/D8 abort with an
     * internal NPE (both build-tools 33 and 34), while static nested classes
     * dex fine.  See the note in LocalWebServer.
     */
    private static final class Protector implements TunRelay.SocketProtector {
        private final ProxyVpnService svc;
        Protector(ProxyVpnService svc) { this.svc = svc; }
        @Override public void protect(Socket s) { svc.protect(s); }
        @Override public void protectDatagram(java.net.DatagramSocket s) { svc.protect(s); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopProxy();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        startProxy();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "ClosersRT Local Server", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("ClosersRT Local Server")
         .setContentText("capturing game traffic")
         .setSmallIcon(android.R.drawable.stat_sys_download_done)
         .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    private void startProxy() {
        if (running) return;
        try {
            Builder builder = new Builder();
            builder.setSession("ClosersRT Local Server");
            builder.addAddress(TUN_ADDRESS, TUN_PREFIX);
            if (CATCH_ALL) {
                // Original behaviour: capture everything and answer DNS ourselves.
                builder.addDnsServer(DNS_ADDRESS);
                builder.addRoute("0.0.0.0", 0);
            } else {
                // Route ONLY the three things we actually handle.  Everything
                // else -- GamePot's login API above all -- keeps using the real
                // network, so live services work and no certificate trust
                // question arises for them.
                //
                //   10.9.9.9    our DNS; every query arrives here and we either
                //               answer it (the dead patch host) or forward it
                //   10.9.9.52   the address we hand back for that patch host
                //   175.106.101.33  the dead game server
                builder.addDnsServer(DNS_ADDRESS);
                builder.addRoute(DNS_ADDRESS, 32);
                builder.addRoute(PATCH_IP, 32);
                builder.addRoute(GAME_SERVER_IP, 32);
            }
            builder.setBlocking(true);
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
                // older APIs: our sockets are protected explicitly instead
            }
            tun = builder.establish();
            if (tun == null) {
                setMessage("VPN establish failed (not authorised?)");
                stopSelf();
                return;
            }
            running = true;
            setMessage("VPN up, capturing");
            worker = new Thread(this, "tun-relay");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            setMessage("start failed: " + e);
            stopSelf();
        }
    }

    private void stopProxy() {
        running = false;
        setMessage("VPN stopped");
        try { if (tun != null) tun.close(); } catch (Exception ignored) { }
        tun = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void run() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        TunRelay relay = new TunRelay(in, out, new Protector(this));
        relay.loop();
        setMessage("relay ended");
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
