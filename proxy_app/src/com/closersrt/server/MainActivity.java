package com.closersrt.server;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;

/**
 * Control panel for ClosersRT Local Server.
 *
 * Deliberately dependency-free (no AndroidX, no resources beyond a string) so
 * the whole APK builds with javac + d8 + aapt2 and needs no Gradle.
 *
 * NOTE: every listener below is a NAMED nested class rather than an anonymous
 * `new Runnable() { ... }`.  That is not style: R8/D8 8.2 (build-tools 34.0.0)
 * aborts with an internal NullPointerException when it is handed the
 * `Foo$1.class` files that javac 21 emits for anonymous classes, while named
 * nested classes (`Foo$Bar.class`) dex fine.  Verified by bisecting each class.
 */
public class MainActivity extends Activity {

    public static final int WEB_PORT = 8080;
    private static final int REQ_VPN = 1;
    private static final int REQ_ZIP = 2;
    private static final int REQ_LANGZIP = 3;

    private TextView account;
    private TextView status;
    private TextView logView;
    private Button toggle;
    private Button gmButton;
    private Button serverButton;
    private Button zipButton;
    private TextView zipState;
    private Button langKoButton;
    private Button langZhButton;
    private Button langPackButton;
    private TextView langState;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /*
     * Every nested class here is `static` on purpose.  R8/D8 (both the 8.2 in
     * build-tools 34 and the 3.3 in build-tools 33) aborts with an internal
     * NullPointerException on javac's output for NON-static inner classes,
     * while static nested classes dex fine.  The activity is passed in
     * explicitly instead of being captured.
     */

    /** Periodic status refresh. */
    private static final class Refresher implements Runnable {
        private final MainActivity act;
        Refresher(MainActivity act) { this.act = act; }
        @Override public void run() {
            act.refreshStatus();
            act.ui.postDelayed(this, 1000);
        }
    }

    /** Start/stop button. */
    private static final class ToggleClick implements View.OnClickListener {
        private final MainActivity act;
        ToggleClick(MainActivity act) { this.act = act; }
        @Override public void onClick(View v) { act.toggleProxy(); }
    }

    /** Start/stop the local servers (web console / game server / patch server). */
    private static final class ServerClick implements View.OnClickListener {
        private final MainActivity act;
        ServerClick(MainActivity act) { this.act = act; }
        @Override public void onClick(View v) { act.toggleServers(); }
    }

    /** Pick the content ZIP through SAF. */
    private static final class ChooseZipClick implements View.OnClickListener {
        private final MainActivity act;
        ChooseZipClick(MainActivity act) { this.act = act; }
        @Override public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            act.startActivityForResult(i, REQ_ZIP);
        }
    }

    /** Switch the staged language (ko / zh) and clear the game marker. */
    private static final class LangClick implements View.OnClickListener {
        private final MainActivity act;
        private final String lang;
        LangClick(MainActivity a, String l) { this.act = a; this.lang = l; }
        @Override public void onClick(View v) { act.setLanguage(lang); }
    }

    /** Pick a language-pack ZIP through SAF and stage it. */
    private static final class ChooseLangZipClick implements View.OnClickListener {
        private final MainActivity act;
        ChooseLangZipClick(MainActivity a) { this.act = a; }
        @Override public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            act.startActivityForResult(i, REQ_LANGZIP);
        }
    }

    /** Open the loopback web console in a browser. */
    private static final class OpenWebClick implements View.OnClickListener {
        private final MainActivity act;
        OpenWebClick(MainActivity act) { this.act = act; }
        @Override public void onClick(View v) {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("http://127.0.0.1:" + WEB_PORT + "/")));
            } catch (Exception e) {
                act.append("cannot open browser: " + e);
            }
        }
    }

    /** One-shot refresh after a state change. */
    private static final class RefreshOnce implements Runnable {
        private final MainActivity act;
        RefreshOnce(MainActivity act) { this.act = act; }
        @Override public void run() { act.refreshStatus(); }
    }

    /**
     * Show the "set battery saver to unrestricted" tip on first launch.
     *
     * This is the single most common cause of "login hangs on a real phone":
     * the OS freezes the proxy while the game is in the foreground, so the
     * loopback servers stop answering and libcurl times out on 8443.  The
     * dialog appears once (flag in SharedPreferences) so it does not nag.
     */
    private void maybeShowBatteryTip() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(
                    "closers_proxy", MODE_PRIVATE);
            if (sp.getBoolean("battery_tip_shown", false)) {
                return;
            }
            sp.edit().putBoolean("battery_tip_shown", true).apply();
        } catch (Throwable ignored) {
            // If prefs fail we still show it; better once too often than never.
        }
        ui.post(new ShowBatteryTip(this));
    }

    /** Named static: d8 8.2 crashes on anonymous classes (see class comment). */
    private static final class ShowBatteryTip implements Runnable {
        private final MainActivity act;
        ShowBatteryTip(MainActivity a) { this.act = a; }
        @Override public void run() {
            try {
                new android.app.AlertDialog.Builder(act)
                        .setTitle("重要：省電設定 / Battery setting")
                        .setMessage(
                                "登入卡住、一直 Loading 或顯示連線失敗時，"
                              + "請把本 App 的省電設定改成「無限制」，並允許自啟動。\n\n"
                              + "小米 / Redmi / POCO：\n"
                              + "設定 → 應用程式 → 管理應用程式 → ClosersRT Local Server\n"
                              + "→ 省電策略 → 無限制\n"
                              + "→ 自啟動 → 允許\n\n"
                              + "其他品牌：設定 → 電池 → 應用程式耗電管理 → 不限制。\n\n"
                              + "If login hangs or stays on Loading, set this app's\n"
                              + "battery setting to \"Unrestricted\" and allow auto-start.\n"
                              + "Android otherwise freezes the local servers and the\n"
                              + "game times out on 127.0.0.1:8443.")
                        .setPositiveButton("知道了 / OK", null)
                        .show();
            } catch (Throwable ignored) { }
        }
    }


    /** Shows/hides the floating GM chip and remembers the choice. */
    static final class GmToggleClick implements android.view.View.OnClickListener {
        private final MainActivity act;
        GmToggleClick(MainActivity a) { this.act = a; }
        public void onClick(android.view.View v) {
            boolean on = !GmOverlay.isEnabled(act);
            GmOverlay.setEnabled(act, on);
            act.updateGmLabel();
        }
    }

    void updateGmLabel() {
        if (gmButton != null) {
            gmButton.setText(GmOverlay.isEnabled(this) ? "GM 懸浮鍵：開" : "GM 懸浮鍵：關");
        }
    }

    private final Refresher refresher = new Refresher(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        // Load persisted accounts BEFORE the web/game servers start, so the
        // first login after a proxy restart still answers with the account the
        // operator created rather than the built-in "player" default.
        UserManager.get().init(getApplicationContext());
        // Same for the account's assets (gold, inventory, characters): the
        // game server renders them into SC_FUNDINFORES / SC_ITEMINFORES /
        // SC_CHARACTERINFO2RES, so they must be loaded before it can answer.
        AssetStore.get().init(getApplicationContext());
        // Player progress (gacha pulls, gold, character levels, equipment
        // upgrades) persists separately from the operator's configured
        // baseline in AssetStore: AssetStore is what a fresh account is seeded
        // with and what the web console edits; SaveStore is what the player
        // actually earned.  Both must be loaded before the game server answers
        // its first login packet.
        SaveStore.get().init(getApplicationContext());
        // Saved costume selections (per character), used by the GM menu.
        GameServer.initCostumes(getApplicationContext());
        // floating GM menu (proxy-side, server data only).  Off by default;
        // toggled from the button added in buildUi().
        if (GmOverlay.isEnabled(this)) GmOverlay.show(this);
        // The naval-base buildings the account has already constructed.  Must be
        // restored before the first 328 goes out, or the client renders the base
        // as empty and the player has to build the HQ again.
        GameServer.loadBuiltIds();
        // Operator knobs (skill levels, content-pack mask, ...).  Must run
        // before the first packet goes out, and before the web console starts
        // serving, so a restart keeps what was configured.
        GameServer.loadKnobs();
        if (SaveStore.get().isFromNewerBuild()) {
            // A save written by a newer build cannot be interpreted correctly.
            // Refusing is safer than misreading it and silently wiping progress.
            Status.log("save: WARNING stored save is version "
                    + SaveStore.get().loadedVersion()
                    + ", this build understands " + SaveStore.FORMAT_VERSION);
        }
        ContentZip.init(getApplicationContext());
        // Language pack selection.  The proxy only stages packs and records the
        // choice; the swap itself happens inside the game process (see
        // com.closersrt.savefix.LanguageFix).
        Language.init(getApplicationContext());
        maybeShowBatteryTip();
        if (BuildFlags.AUTO_START_SERVERS) {
            startServers();
        } else {
            Status.log("servers idle; press 'start server'");
        }

        // Put the account slot back to the fixed known-good save.
        //
        // The client writes its own progress into CMAccountSlot.sav, so a session
        // that dies part way through leaves half-finished state behind; the next
        // launch then reads that state and dies in the same place, and the
        // failure feeds itself until a real regression is indistinguishable from
        // the debris of the last crash.  Restoring a fixed save at start-up makes
        // every launch begin from the same point.  Switch it off with
        // /api/save?auto=0 once runs are stable and progress should be kept.
        //
        // The save belongs to the GAME, in another app's external directory, so
        // this needs WRITE_EXTERNAL_STORAGE and we must ask for it at runtime
        // before the first attempt.  On the Android 9 test image the file is
        // group-owned by sdcard_rw with group write, so the grant is sufficient;
        // without it the write fails with "(Permission denied)".
        if (SaveRestore.autoRestore) {
            maybeRestoreSave();
        }

        // Bring the tunnel up automatically when consent has already been
        // granted.  The release "manual start" variant compiles this flag to
        // false so opening the app never creates a VPN tunnel by itself.
        if (BuildFlags.AUTO_START_PROXY) {
            try {
                if (VpnService.prepare(this) == null && !ProxyVpnService.isRunning()) {
                    startProxyService();
                }
            } catch (Throwable t) {
                Status.log("auto-start skipped: " + t);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
        updateGmLabel();
        showLang();
        if (GmOverlay.isEnabled(this)) GmOverlay.show(this);
    }

    /**
     * Restore the fixed save, asking for WRITE_EXTERNAL_STORAGE first if needed.
     *
     * The permission is only required on the older images we test against; where
     * it is already granted, or where the platform does not police it, the
     * restore simply runs.  A denial is not fatal: the result is reported in the
     * log and /api/save?now=1 can retry later.
     */
    private void maybeRestoreSave() {
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Status.log("save: requesting WRITE_EXTERNAL_STORAGE");
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
                return;                      // restore happens in the callback
            }
        } catch (Throwable t) {
            Status.log("save: permission check skipped: " + t);
        }
        Status.log("save: auto-restore -> " + SaveRestore.restore());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        Status.log("save: permission granted=" + granted);
        if (granted) {
            Status.log("save: auto-restore -> " + SaveRestore.restore());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    /** versionName from the manifest, e.g. "261106". */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ClosersRT Local Server\nv" + appVersion());
        title.setTextSize(20);
        root.addView(title);

        // Controls live ABOVE the notice on purpose.
        //
        // The notice is a tall multi-line panel; placing it first pushed the
        // start/stop and "open web console" buttons below the fold on a 720p
        // screen, which read as though the web button had been removed.
        //
        // Requested order: 1) server, 2) web console, 3) proxy.
        serverButton = new Button(this);
        serverButton.setOnClickListener(new ServerClick(this));
        root.addView(serverButton);

        Button web = new Button(this);
        web.setText("open web console (127.0.0.1:" + WEB_PORT + ")");
        web.setOnClickListener(new OpenWebClick(this));
        root.addView(web);

        gmButton = new Button(this);
        gmButton.setOnClickListener(new GmToggleClick(this));
        root.addView(gmButton);

        toggle = new Button(this);
        toggle.setOnClickListener(new ToggleClick(this));
        root.addView(toggle);

        zipButton = new Button(this);
        zipButton.setText("select content zip (Download)");
        zipButton.setOnClickListener(new ChooseZipClick(this));
        root.addView(zipButton);

        zipState = new TextView(this);
        zipState.setTextSize(12);
        zipState.setPadding(0, 4, 0, 4);
        root.addView(zipState);

        // ---- language / 漢化 -------------------------------------------
        //
        // The language switch used to live only in the web console.  Putting it
        // here means a first-time user never has to find the console: pick the
        // pack once, then toggle 韓文 / 中文 from the app itself.  The pack stays
        // on the server, so later switches need no upload.
        TextView langTitle = new TextView(this);
        langTitle.setText("漢化 / Language");
        langTitle.setTextSize(15);
        langTitle.setPadding(0, pad / 2, 0, 4);
        root.addView(langTitle);

        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langKoButton = new Button(this);
        langKoButton.setText("韓文");
        langKoButton.setOnClickListener(new LangClick(this, Language.KO));
        langRow.addView(langKoButton);
        langZhButton = new Button(this);
        langZhButton.setText("中文");
        langZhButton.setOnClickListener(new LangClick(this, Language.ZH));
        langRow.addView(langZhButton);
        root.addView(langRow);

        langPackButton = new Button(this);
        langPackButton.setText("上傳漢化包（選 zip，只需一次）");
        langPackButton.setOnClickListener(new ChooseLangZipClick(this));
        root.addView(langPackButton);

        langState = new TextView(this);
        langState.setTextSize(12);
        langState.setPadding(0, 4, 0, pad / 2);
        root.addView(langState);

        // ---- battery / background restriction warning -------------------
        //
        // Measured on MIUI (Android 14): with the app's battery saver on
        // "restrict/optimize", the process is frozen while the game is in the
        // foreground.  The servers stay "listening" but stop answering, and the
        // game reports
        //
        //   connect to 127.0.0.1 port 8443 failed: Connection timed out
        //   libcurl error: 28 (Timeout was reached)
        //
        // so the login flow sits on the loading screen and never reaches the
        // lobby.  Setting the proxy to "no restrictions" fixed it in testing.
        // A foreground service alone is not enough on MIUI.
        TextView battery = new TextView(this);
        battery.setText(
                "⚠ 登入卡住或一直 Loading？請把本 App 的省電設定改成「無限制」\n"
              + "\n"
              + "Android / MIUI 會凍結背景 App，導致本機伺服器（8443/8500）"
              + "雖然在監聽卻無法回應，遊戲就會在登入後卡住或顯示連線失敗。\n"
              + "\n"
              + "設定方式（小米 / Redmi / POCO）：\n"
              + "  設定 → 應用程式 → 管理應用程式 → ClosersRT Local Server\n"
              + "  → 省電策略 → 選「無限制」\n"
              + "  → 自啟動 → 允許\n"
              + "\n"
              + "其他品牌：設定 → 電池 → 應用程式耗電管理 → 找到本 App → 不限制。\n"
              + "\n"
              + "If login hangs or stays on Loading, set this app's battery\n"
              + "setting to \"Unrestricted\" (and allow auto-start).  Otherwise\n"
              + "Android freezes the proxy and the game times out on 8443/8500.");
        battery.setTextSize(12);
        battery.setLineSpacing(0, 1.2f);
        battery.setBackgroundColor(0xFFFFF4CE);      // pale amber
        int bpad = (int) (10 * getResources().getDisplayMetrics().density);
        battery.setPadding(bpad, bpad, bpad, bpad);
        root.addView(battery);

        // ---- free / non-commercial notice -------------------------------
        TextView notice = new TextView(this);
        notice.setText(
                "此軟件由 cs10108 開發\n"
              + "免費公開 · 不收取任何費用\n"
              + "\n"
              + "本程式為非營利性質的個人研究項目，"
              + "僅供學習與技術交流使用。\n"
              + "任何人皆可自由取得與使用，"
              + "開發者不會以任何形式向使用者收費，"
              + "亦不會販售、出租或以此營利。\n"
              + "若你是付費取得本程式，請立即向販售者要求退款 —— "
              + "那個人無權販賣它。");
        notice.setTextSize(12);
        notice.setPadding(0, pad / 2, 0, pad / 2);
        notice.setLineSpacing(0, 1.25f);

        // A subtle panel so the notice reads as a statement, not as log noise.
        int panel = 0xFFF2F6FA;
        notice.setBackgroundColor(panel);
        int inner = (int) (10 * getResources().getDisplayMetrics().density);
        notice.setPadding(inner, inner, inner, inner);
        root.addView(notice);

        // Which account the game server will answer with.  Shown explicitly
        // so it is obvious at a glance that the proxy is not falling back to
        // the old built-in "player" default.
        account = new TextView(this);
        account.setTextSize(13);
        account.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(account);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        ScrollView scroller = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setGravity(Gravity.START);
        scroller.addView(logView);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.setScrollbarFadingEnabled(false);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (220 * getResources().getDisplayMetrics().density)));
        root.addView(scroller);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(true);
        page.setScrollbarFadingEnabled(false);
        page.addView(root);
        return page;
    }

    /** Switch language and tell the user what to do next. */
    void setLanguage(String lang) {
        if (Language.ZH.equals(lang) && !Language.hasZhPack()) {
            append("lang: 中文包尚未上傳，請先按「上傳漢化包」");
            showLang();
            return;
        }
        Language.set(lang);
        append("lang: switched to " + lang);
        showLang();
    }

    /** Read a ZIP chosen by the user and stage it as the Chinese pack. */
    private void importLangZip(Uri u) {
        try {
            InputStream in = getContentResolver().openInputStream(u);
            String r = Language.importZip(in);
            append("lang zip: " + r);
            if (r != null && r.contains("\"ok\":true")) {
                append("漢化包已儲存，之後切換中文不需再上傳");
            }
        } catch (Throwable t) {
            append("lang zip failed: " + t);
        }
        showLang();
    }

    /** Reflect the staged pack + current language in the UI. */
    private void showLang() {
        if (langState == null) return;
        File zh = Language.packFile(Language.ZH);
        boolean have = Language.hasZhPack();
        long kb = zh != null && zh.isFile() ? zh.length() / 1024 : 0;
        String cur = Language.current();
        langState.setText("目前語言：" + ("zh".equals(cur) ? "中文" : "韓文")
                + (have ? "　｜　漢化包：" + kb + " KB" : "　｜　漢化包：未上傳")
                + "\n切換後請重新啟動遊戲");
        if (langPackButton != null) {
            langPackButton.setText(have ? "更換漢化包（選 zip）"
                                        : "上傳漢化包（選 zip，只需一次）");
        }
        if (langKoButton != null) {
            langKoButton.setEnabled(!"ko".equals(cur));
            langZhButton.setEnabled(have && !"zh".equals(cur));
        }
    }

    private void showZip(ContentZip z) {
        if (zipState == null) return;
        if (z == null || z.count() == 0) {
            zipState.setText("content zip: 尚未選擇");
        } else {
            zipState.setText("content zip: " + z.displayName()
                    + "  (" + z.count() + " entries)");
            if (zipButton != null) {
                zipButton.setText("content zip: " + z.displayName());
            }
        }
    }

    private void append(String s) {
        if (logView != null) logView.append(s + "\n");
        Status.log(s);
    }

    private void startServers() {
        // A foreground service, not an in-process start: on MIUI and stock
        // Android 12+ a backgrounded activity is frozen, and a frozen process
        // stops answering on its listening sockets ("Connection timed out"
        // from the game's libcurl).
        Intent i = new Intent(this, ServerService.class);
        i.setAction(ServerService.ACTION_START);
        startForegroundService(i);
        Status.log("servers: starting (foreground service)");
    }

    private void stopServers() {
        Intent i = new Intent(this, ServerService.class);
        i.setAction(ServerService.ACTION_STOP);
        startService(i);
        Status.log("servers: stopping");
    }

    private boolean serversRunning() {
        return ServerService.isRunning()
                || LocalWebServer.get().isRunning()
                || GameServer.isRunning()
                || PatchServer.isRunning();
    }

    private void toggleServers() {
        if (serversRunning()) {
            stopServers();
        } else {
            startServers();
        }
        ui.postDelayed(new RefreshOnce(this), 300);
    }

    private void toggleProxy() {
        if (ProxyVpnService.isRunning()) {
            Intent i = new Intent(this, ProxyVpnService.class);
            i.setAction(ProxyVpnService.ACTION_STOP);
            startService(i);
        } else {
            Intent prep = VpnService.prepare(this);
            if (prep != null) {
                startActivityForResult(prep, REQ_VPN);
            } else {
                startProxyService();
            }
        }
        ui.postDelayed(new RefreshOnce(this), 500);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_VPN) {
            if (result == RESULT_OK) {
                startProxyService();
            } else {
                append("VPN permission denied");
            }
        } else if (request == REQ_LANGZIP) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) { }
                importLangZip(u);
            }
        } else if (request == REQ_ZIP) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable t) {
                    append("zip: persist failed: " + t);
                }
                ContentZip z = ContentZip.get();
                if (z != null && z.setUri(u)) {
                    append("zip selected: " + z.displayName() + " (" + z.count() + " entries)");
                    showZip(z);
                } else {
                    append("zip: could not read selected file");
                    if (zipState != null) zipState.setText("content zip: 讀取失敗");
                }
            }
        }
        super.onActivityResult(request, result, data);
    }

    private void startProxyService() {
        // The tunnel is useless without the local endpoints the client talks
        // to, so starting the proxy also makes sure the servers are up.
        if (!serversRunning()) {
            startServers();
        }
        Intent i = new Intent(this, ProxyVpnService.class);
        i.setAction(ProxyVpnService.ACTION_START);
        startForegroundService(i);
    }

    private void refreshStatus() {
        boolean on = ProxyVpnService.isRunning();
        // This is the VPN tunnel switch, not the servers (those are
        // serverButton above), so it keeps the word "proxy".
        toggle.setText(on ? "stop proxy" : "start proxy");

        boolean srv = serversRunning();
        if (serverButton != null) {
            serverButton.setText(srv ? "stop server" : "start server");
        }

        if (account != null) {
            UserManager.User cur = UserManager.get().current();
            account.setText("account    : "
                    + (cur == null ? "(none)" : cur.name)
                    + "   id " + (cur == null ? "-" : String.valueOf(cur.accountId)));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("server     : ").append(srv ? "RUNNING" : "stopped").append('\n');
        sb.append("  web      : ").append(LocalWebServer.get().isRunning() ? "RUNNING" : "stopped").append('\n');
        sb.append("  game     : ").append(GameServer.isRunning() ? "RUNNING" : "stopped").append('\n');
        sb.append("  patch    : ").append(PatchServer.isRunning() ? "RUNNING" : "stopped").append('\n');
        sb.append("proxy      : ").append(on ? "RUNNING" : "stopped").append('\n');
        sb.append("web console: http://127.0.0.1:").append(WEB_PORT).append("/\n");
        sb.append("game server: ").append(GameServer.hostPort()).append('\n');
        sb.append("conns seen : ").append(TunRelay.flowCount()).append('\n');
        sb.append("packets    : in ").append(TunRelay.packetsIn())
          .append(" / out ").append(TunRelay.packetsOut()).append('\n');
        sb.append("message    : ").append(ProxyVpnService.lastMessage()).append('\n');
        sb.append("tip        : 登入卡住請把本 App 省電設為「無限制」\n");
        ContentZip cz = ContentZip.get();
        sb.append("content zip: ").append(cz == null ? "-" :
                (cz.displayName() + "  (" + cz.count() + " entries)")).append('\n');
        showZip(cz);
        status.setText(sb.toString());
    }
}
