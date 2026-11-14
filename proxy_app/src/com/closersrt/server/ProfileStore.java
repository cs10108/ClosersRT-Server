package com.closersrt.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Imported "data packages" — the operator's step 4.
 *
 * A package is whatever the proxy needs in order to answer the client:
 * chapter tables, stage lists, account JSON, patch manifests.  We keep them as
 * raw text keyed by name so the format can evolve without a rebuild.
 */
public final class ProfileStore {

    private static final ProfileStore INSTANCE = new ProfileStore();
    public static ProfileStore get() { return INSTANCE; }

    public static final class Profile {
        public final String name;
        public final String body;
        public final long importedAt;
        Profile(String name, String body) {
            this.name = name;
            this.body = body;
            this.importedAt = System.currentTimeMillis();
        }
    }

    private final List<Profile> profiles = new ArrayList<Profile>();
    private volatile String active;

    private ProfileStore() { }

    public synchronized String importData(String body, String name) {
        if (body == null || body.trim().isEmpty()) {
            return "{\"ok\":false,\"error\":\"empty body\"}";
        }
        String key = (name == null || name.trim().isEmpty())
                ? ("profile-" + (profiles.size() + 1)) : name.trim();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equals(key)) profiles.remove(i--);
        }
        profiles.add(new Profile(key, body));
        if (active == null) active = key;
        Status.log("imported profile '" + key + "' (" + body.length() + " chars)");
        return "{\"ok\":true,\"name\":\"" + Status.jsonEscape(key) + "\"}";
    }

    public synchronized boolean activate(String name) {
        for (Profile p : profiles) {
            if (p.name.equals(name)) {
                active = name;
                Status.log("activated profile '" + name + "'");
                return true;
            }
        }
        return false;
    }

    public synchronized String activeName() { return active; }

    public synchronized String activeBody() {
        for (Profile p : profiles) {
            if (p.name.equals(active)) return p.body;
        }
        return null;
    }

    /** Rows for the most important table, used by the chapter builder. */
    public synchronized List<String[]> activeRows() {
        List<String[]> out = new ArrayList<String[]>();
        String body = activeBody();
        if (body == null) return out;
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(line.split("[,\\t]"));
        }
        return out;
    }

    public synchronized String json() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < profiles.size(); i++) {
            Profile p = profiles.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(Status.jsonEscape(p.name)).append("\",");
            sb.append("\"chars\":").append(p.body.length()).append(',');
            sb.append("\"active\":").append(p.name.equals(active)).append('}');
        }
        return sb.append(']').toString();
    }

    public synchronized void clear() {
        profiles.clear();
        active = null;
    }
}
