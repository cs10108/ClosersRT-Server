package com.closersrt.server;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Floating GM menu drawn by the PROXY app: a costume picker.
 *
 * Tap GM -> pick a character -> pick one of the costumes that character
 * actually owns.  The choice is written into the roster packet (326 +0x2B) and
 * echoed by the dorm chat reply (776); the lobby model applies it on the next
 * login, because the client builds that model only at login.
 *
 * No anonymous inner classes: the build skips javac synthetic Foo$1.class.
 */
public final class GmOverlay {

    private static final String PREF = "gm_overlay";
    private static final String PREF_VISIBLE = "visible";
    private static final String PREF_INIT = "init_v1";

    private static WindowManager wm;
    private static View root;
    private static LinearLayout panel;
    private static ScrollView scroller;
    private static WindowManager.LayoutParams lp;
    private static Context app;
    private static boolean shown;

    private GmOverlay() { }

    public static boolean isEnabled(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, 0);
        if (!sp.getBoolean(PREF_INIT, false)) {
            sp.edit().putBoolean(PREF_INIT, true).putBoolean(PREF_VISIBLE, false).apply();
        }
        boolean v = sp.getBoolean(PREF_VISIBLE, false);
        Status.log("gm: enabled=" + v);
        return v;
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREF, 0).edit().putBoolean(PREF_VISIBLE, on).apply();
        if (on) show(ctx); else hide();
    }

    public static void show(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                Status.log("gm: overlay permission requested");
            } catch (Throwable t) {
                Status.log("gm: cannot open overlay settings: " + t);
            }
            return;
        }
        if (shown) return;
        try {
            addOverlay(c);
            shown = true;
            Status.log("gm: overlay shown");
        } catch (Throwable t) {
            Status.log("gm: overlay failed: " + t);
        }
    }

    private static void addOverlay(Context c) {
        app = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = 8;

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);

        TextView chip = new TextView(c);
        chip.setText("GM");
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(16);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundColor(Color.parseColor("#CC1F6FEB"));
        chip.setPadding(24, 24, 24, 24);
        chip.setOnClickListener(new Toggle());
        chip.setOnTouchListener(new Drag());
        box.addView(chip);

        ScrollView sc = new ScrollView(c);
        scroller = sc;
        sc.setBackgroundColor(Color.parseColor("#EE101418"));
        sc.setVisibility(View.GONE);
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(12, 12, 12, 12);
        sc.addView(panel);
        sc.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 220), dp(c, 420)));
        box.addView(sc);

        root = box;
        wm.addView(root, lp);
        showChars();
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    private static void header(String text) {
        TextView t = new TextView(app);
        t.setText(text);
        t.setTextColor(Color.parseColor("#FFAAAAAA"));
        t.setTextSize(12);
        t.setPadding(0, 8, 0, 4);
        panel.addView(t);
    }

    /** Step 1: the character list. */
    static void showChars() {
        if (panel == null) return;
        panel.removeAllViews();
        header("選角色 Costume");
        for (String row : DormCatalog.RAW) {
            String[] f = row.split("\\|");
            if (f.length < 3) continue;
            int cid = Integer.parseInt(f[0]);
            panel.addView(button(f[2] + "  " + cid, new Pick(cid)));
        }
        panel.addView(button("關閉", new Act(0)));
    }

    /** Step 2: the costumes that character owns. */
    static void showCostumes(int cid) {
        if (panel == null) return;
        panel.removeAllViews();
        header("角色 " + cid + " 的服裝");
        java.util.List<Integer> ids = CostumeCatalog.forChar(cid);
        if (ids.isEmpty()) {
            header("（沒有資料）");
        } else {
            for (Integer id : ids) panel.addView(button("穿 " + id, new Act(cid, id)));
            panel.addView(button("回復預設", new Act(cid, 0)));
        }
        panel.addView(button("← 返回", new Back()));
    }

    private static Button button(String label, View.OnClickListener l) {
        Button b = new Button(app);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    /**
     * Show the result to BOTH the log and the screen.
     *
     * It used to only write Status.log, so a refused costume change looked
     * identical to a successful one -- the menu closed and nothing happened.
     */
    private static void toast(String msg) {
        Status.log("gm: " + msg);
        try {
            if (app != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new ToastRunnable(app, msg));
            }
        } catch (Throwable ignored) { }
    }

    /** Named class: the build skips javac anonymous Foo$1.class output. */
    private static final class ToastRunnable implements Runnable {
        private final Context ctx;
        private final String msg;
        ToastRunnable(Context c, String m) { ctx = c; msg = m; }
        @Override public void run() {
            try {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        }
    }

    private static final class Toggle implements View.OnClickListener {
        public void onClick(View v) {
            if (root == null) return;
            if (scroller == null) return;
            scroller.setVisibility(scroller.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }

    private static final class Back implements View.OnClickListener {
        public void onClick(View v) { showChars(); }
    }

    private static final class Pick implements View.OnClickListener {
        private final int cid;
        Pick(int cid) { this.cid = cid; }
        public void onClick(View v) { showCostumes(cid); }
    }


    /** Runs pushCostume off the UI thread (socket writes are forbidden there). */
    private static final class Push implements Runnable {
        private final int cid;
        private final int costume;
        Push(int cid, int costume) { this.cid = cid; this.costume = costume; }
        @Override public void run() {
            try {
                String r = GameServer.pushCostume(cid, costume);
                toast("角色 " + cid + " -> 時裝 " + costume + "：\n" + r);
            } catch (Throwable t) {
                toast("失敗 Failed: " + t);
            }
        }
    }
    private static final class Act implements View.OnClickListener {
        private final int cid;
        private final int costume;
        Act(int cid) { this(cid, -1); }
        Act(int cid, int costume) { this.cid = cid; this.costume = costume; }
        public void onClick(View v) {
            if (cid == 0 && costume == -1) { hide(); return; }
            // pushCostume() WRITES TO A SOCKET, and this handler runs on the
            // UI thread -- Android throws NetworkOnMainThreadException and the
            // whole push is lost.  That is why the GM menu never changed a
            // costume while the web console (a background thread) did.
            Thread t = new Thread(new Push(cid, costume), "gm-costume");
            t.setDaemon(true);
            t.start();
        }
    }

    private static final class Drag implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float downX;
        private float downY;
        private boolean moved;
        public boolean onTouch(View v, MotionEvent e) {
            if (lp == null || wm == null || root == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - downX);
                    int dy = (int) (e.getRawY() - downY);
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                    if (!moved) return false;
                    lp.x = startX + dx;
                    lp.y = startY + dy;
                    try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) { }
                    return true;
                default:
                    return false;
            }
        }
    }

    public static void hide() {
        try {
            if (wm != null && root != null) wm.removeView(root);
        } catch (Throwable ignored) { }
        root = null;
        shown = false;
    }
}
