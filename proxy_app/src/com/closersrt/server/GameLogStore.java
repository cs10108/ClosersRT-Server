package com.closersrt.server;

/**
 * Ring buffer for log lines the game pushes to the local server.
 *
 * WHY
 * ---
 * The game writes CM.log into its own external files directory, which:

 *   - adb cannot read on a retail device (the file is 0600, owned by the
 *     game's uid, and Android 11+ blocks the shell from that directory), and
 *   - no File Manager the user tried could copy out either.
 *
 * The game already runs its SaveFix helper in-process at startup, so that
 * helper also tails CM.log and POSTs new bytes to 127.0.0.1:8080/api/gamelog.
 * The proxy keeps the last N lines here and the web console shows them, so a
 * real phone can be diagnosed without root, adb, or file access.
 *
 * Endpoints (see LocalWebServer):
 *   POST /api/gamelog          body = plain text chunk, appended
 *   GET  /api/gamelog?n=2000   last n lines as JSON
 *   POST /api/gamelog/clear    drop the buffer
 */
public final class GameLogStore {

    /** Keep roughly the tail of one CM.log; 20k lines is a few MB at most. */
    private static final int MAX_LINES = 20000;
    private static final int MAX_LINE_LEN = 4096;

    private static final String[] LINES = new String[MAX_LINES];
    private static int head = 0;
    private static int count = 0;
    private static long receivedBytes = 0;
    private static String lastPush = null;

    /**
     * Whether the game side should forward CM.log to us.
     *
     * DEFAULT OFF: the pump polls every 2 s, copies strings and does a TLS
     * POST whenever UE4 appends a line, which is real overhead during a
     * battle.  Diagnostics only, so it is opt-in from the web console.
     */
    private static volatile boolean enabled = false;

    public static boolean isEnabled() { return enabled; }

    public static synchronized void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
        Status.log("gamelog: forwarding " + (on ? "ON" : "OFF"));
    }

    private GameLogStore() { }

    /** Append a text chunk; it may contain many lines, and partial last line. */
    public static synchronized void append(String chunk) {
        if (!enabled) return;      // forwarding is off: discard, do not buffer
        if (chunk == null || chunk.isEmpty()) return;
        receivedBytes += chunk.length();
        lastPush = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());

        int start = 0;
        for (int i = 0; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '\n') {
                addLine(chunk.substring(start, i));
                start = i + 1;
            }
        }
        if (start < chunk.length()) {
            addLine(chunk.substring(start));
        }
    }

    private static void addLine(String s) {
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        if (s.length() > MAX_LINE_LEN) s = s.substring(0, MAX_LINE_LEN) + "…";
        LINES[head] = s;
        head = (head + 1) % MAX_LINES;
        if (count < MAX_LINES) count++;
    }

    public static synchronized void clear() {
        head = 0;
        count = 0;
        receivedBytes = 0;
        lastPush = null;
    }

    public static synchronized int lineCount() { return count; }
    public static synchronized long bytes() { return receivedBytes; }
    public static synchronized String lastPush() { return lastPush; }

    /** Last n lines, oldest first. */
    public static synchronized String[] recent(int n) {
        int take = Math.min(n, count);
        String[] out = new String[take];
        for (int i = 0; i < take; i++) {
            int idx = (head - take + i + MAX_LINES * 2) % MAX_LINES;
            out[i] = LINES[idx];
        }
        return out;
    }

    /** JSON array of the last n lines (reuses Status' escaper). */
    public static synchronized String json(int n) {
        String[] lines = recent(n);
        StringBuilder sb = new StringBuilder(64 + lines.length * 80);
        sb.append("{\"lines\":").append(lines.length)
          .append(",\"bytes\":").append(receivedBytes)
          .append(",\"last_push\":");
        if (lastPush == null) sb.append("null");
        else sb.append('"').append(Status.jsonEscape(lastPush)).append('"');
        sb.append(",\"log\":[");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(Status.jsonEscape(lines[i])).append('"');
        }
        return sb.append("]}").toString();
    }
}
