package com.closersrt.server;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal account manager — the operator's step 5.
 *
 * The retail backend authenticated against GamePot/Naver; offline we only need
 * an identity for the game server to answer with, so an account here is just a
 * name plus the fields the CM login packets carry back (nickname, account id,
 * session/PSN style token).
 *
 * Accounts and the active one are persisted to SharedPreferences.  Without
 * that the proxy fell back to its built-in default on every restart,
 * which is why the game kept showing "Player" instead of the account the
 * operator had created.
 */
public final class UserManager {

    /**
     * Name of the account created on a fresh install.
     *
     * The proxy used to hardcode "player", which made the client greet
     * "Player" no matter what.  The operator's account is Carsdin.
     */
    public static final String DEFAULT_NAME = "Carsdin";

    /** What the old default account was called; renamed in place on load. */
    private static final String LEGACY_NAME = "player";

    private static final String PREFS = "closersrt_users";
    private static final String KEY_ACCOUNTS = "accounts";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_NEXT = "next_id";

    private static final UserManager INSTANCE = new UserManager();
    public static UserManager get() { return INSTANCE; }

    public static final class User {
        public String name;
        public final long accountId;
        public String nickname;
        public boolean active;
        User(String name, long accountId) {
            this.name = name;
            this.accountId = accountId;
            this.nickname = name;
        }
    }

    private final List<User> users = new ArrayList<User>();
    private long nextId = 1000;
    private SharedPreferences prefs;

    private UserManager() { }

    /** Attach storage.  Call once from MainActivity.onCreate before any use. */
    public synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        users.clear();
        String blob = prefs.getString(KEY_ACCOUNTS, "");
        nextId = prefs.getLong(KEY_NEXT, 1000L);
        String active = prefs.getString(KEY_ACTIVE, null);
        for (String rec : blob.split("\n")) {
            if (rec.trim().isEmpty()) continue;
            String[] f = rec.split("\t");
            if (f.length < 2) continue;
            try {
                User u = new User(f[0], Long.parseLong(f[1]));
                if (f.length > 2 && !f[2].isEmpty()) u.nickname = f[2];
                u.active = f[0].equals(active);
                users.add(u);
            } catch (NumberFormatException ignored) { }
        }
        ensureDefaultAccount(active);
        if (active != null && current() == null) {
            // active name no longer exists; nothing is active
            for (User u : users) u.active = false;
        }
    }

    /**
     * Guarantee that `DEFAULT_NAME` exists and is the ACTIVE account.
     *
     * Called on every load, so the proxy always answers the client with
     * "Carsdin" no matter what an older build left behind.  Two cases are
     * handled:
     *
     *   1. A legacy "player" record and no Carsdin at all -- rename it in
     *      place, so the account id and any saved progress survive; only the
     *      display name changes.
     *   2. Both exist (what an intermediate build produced) -- keep Carsdin
     *      and drop the stale "player" entry, then make Carsdin active.
     *
     * Anything with a different name is left completely alone.
     */
    private void ensureDefaultAccount(String active) {
        User def = null;
        for (User u : users) {
            if (DEFAULT_NAME.equals(u.name)) { def = u; break; }
        }

        if (def == null) {
            // No Carsdin yet: adopt the legacy record if there is one.
            User legacy = null;
            for (User u : users) {
                if (LEGACY_NAME.equals(u.name)) { legacy = u; break; }
            }
            if (legacy != null) {
                legacy.name = DEFAULT_NAME;
                if (LEGACY_NAME.equals(legacy.nickname)) legacy.nickname = DEFAULT_NAME;
                def = legacy;
            } else {
                create(DEFAULT_NAME, null);
                for (User u : users) {
                    if (DEFAULT_NAME.equals(u.name)) { def = u; break; }
                }
            }
        } else {
            // Carsdin exists: retire any leftover "player" so the account
            // list stays clean and never becomes active again.
            for (int i = users.size() - 1; i >= 0; i--) {
                if (LEGACY_NAME.equals(users.get(i).name)) users.remove(i);
            }
        }

        if (def != null) {
            for (User u : users) u.active = (u == def);
            save();
        }
    }

    private void save() {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (User u : users) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(u.name).append('\t').append(u.accountId).append('\t').append(u.nickname);
        }
        User cur = current();
        prefs.edit()
                .putString(KEY_ACCOUNTS, sb.toString())
                .putString(KEY_ACTIVE, cur == null ? "" : cur.name)
                .putLong(KEY_NEXT, nextId)
                .apply();
    }

    public synchronized String create(String name, String body) {
        if (name == null || name.trim().isEmpty()) {
            return "{\"ok\":false,\"error\":\"missing ?name=\"}";
        }
        String key = name.trim();
        for (User u : users) {
            if (u.name.equals(key)) {
                return "{\"ok\":true,\"existed\":true,\"accountId\":" + u.accountId + "}";
            }
        }
        User u = new User(key, nextId++);
        if (body != null && !body.trim().isEmpty()) {
            String nick = extractNickname(body);
            if (nick != null) u.nickname = nick;
        }
        users.add(u);
        save();
        Status.log("created account '" + key + "' id=" + u.accountId);
        return "{\"ok\":true,\"accountId\":" + u.accountId + "}";
    }

    public synchronized boolean activate(String name) {
        for (User u : users) {
            u.active = u.name.equals(name);
            if (u.active) Status.log("active account = '" + u.name + "'");
        }
        save();
        return current() != null;
    }

    public synchronized User current() {
        for (User u : users) if (u.active) return u;
        return users.isEmpty() ? null : users.get(0);
    }

    public synchronized String json() {
        StringBuilder sb = new StringBuilder("[");
        User cur = current();
        for (int i = 0; i < users.size(); i++) {
            User u = users.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(Status.jsonEscape(u.name)).append("\",");
            sb.append("\"nickname\":\"").append(Status.jsonEscape(u.nickname)).append("\",");
            sb.append("\"accountId\":").append(u.accountId).append(',');
            sb.append("\"active\":").append(cur == u).append('}');
        }
        return sb.append(']').toString();
    }

    private static String extractNickname(String body) {
        int i = body.indexOf("\"nickname\"");
        if (i < 0) return null;
        int c = body.indexOf(':', i);
        int q1 = body.indexOf('"', c + 1);
        int q2 = body.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    public synchronized void clear() {
        users.clear();
        nextId = 1000;
        save();
    }
}
