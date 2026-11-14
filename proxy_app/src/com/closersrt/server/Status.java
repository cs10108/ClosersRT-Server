package com.closersrt.server;

/**
 * Tiny logging + in-memory ring buffer.
 *
 * The web console shows this buffer, which is how the operator debugs without
 * needing adb.
 */
public final class Status {

    private static final int MAX = 400;
    private static final String[] LINES = new String[MAX];
    private static int head = 0;
    private static int count = 0;

    private Status() { }

    public static synchronized void log(String s) {
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        LINES[head] = stamp + "  " + s;
        head = (head + 1) % MAX;
        if (count < MAX) count++;
        // Guarded so this class also runs on a desktop JDK during packet tests,
        // where android.util.Log does not exist.
        try {
            android.util.Log.i("ClosersProxy", s);
        } catch (Throwable ignored) {
            // not running on Android
        }
    }

    public static synchronized String[] recent(int n) {
        int take = Math.min(n, count);
        String[] out = new String[take];
        for (int i = 0; i < take; i++) {
            int idx = (head - take + i + MAX * 2) % MAX;
            out[i] = LINES[idx];
        }
        return out;
    }

    static String logJson() {
        String[] lines = recent(150);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(lines[i])).append('"');
        }
        return sb.append(']').toString();
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
