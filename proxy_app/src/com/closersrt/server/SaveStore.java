package com.closersrt.server;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent account progress -- the offline equivalent of the retail server's
 * account database.
 *
 * WHY THIS EXISTS
 * ---------------
 * Until now the only thing that survived a restart was AssetStore (the
 * operator's configured defaults for funds / items / characters).  Everything
 * the player *earned* while playing -- gacha pulls, gold picked up in a stage,
 * character EXP, equipment upgrade levels -- lived in SessionState, which is
 * deliberately rebuilt from AssetStore on every login.  So every restart threw
 * the player's progress away and snapped back to the configured defaults.
 *
 * AssetStore stays the BASELINE (what a fresh account is seeded with, and what
 * the web console edits).  SaveStore is the DELTA: the player's own progress on
 * top of that baseline.  On login the server sends baseline + delta, which is
 * why the console still works as a starting-point editor while play now
 * accumulates.
 *
 * WHAT IS PERSISTED
 * -----------------
 *   characters   which character ids the account owns (gacha adds rows here)
 *   charGrowth   per-character level / class / skill levels / exp / favor
 *   items        inventory delta on top of the asset baseline
 *   funds        currency delta on top of the asset baseline
 *   story        cleared chapter / stage
 *   meta         save format version and a monotonic revision counter
 *
 * DESIGN NOTES (learned the hard way, see _memory/ and the revival playbook)
 * ------------------------------------------------------------------------
 * - Every mutation writes through to SharedPreferences immediately.  A save
 *   that only updates memory passes every in-process test and then loses the
 *   data on the next cold start; that failure is invisible until a restart.
 * - One key per concern, values are "id:field:field" lines.  Adding a field
 *   later must never change the meaning of an existing one.
 * - A save-format version is stored so a future change can migrate instead of
 *   silently misreading.  A save written by a NEWER build is refused rather
 *   than misparsed.
 * - Clamping happens on the way in, not just on the way out: a corrupted or
 *   hand-edited value must not be able to make the client compute garbage.
 */
public final class SaveStore {

    /** Bump when the on-disk shape changes in a way older builds cannot read. */
    public static final int FORMAT_VERSION = 1;

    private static final String PREFS = "closersrt_save";

    private static final SaveStore INSTANCE = new SaveStore();
    public static SaveStore get() { return INSTANCE; }

    /** Per-character progression.  Mirrors the fields the client shows. */
    public static final class CharGrowth {
        public int level = 1;
        public int charClass = 1;      // ETB_Char_Class (1=PUBLIC ... )
        public int exp = 0;
        public final int[] skills = new int[7];
        public int favorLevel = 1;
        public int favorExp = 0;
        /** Equipment ids currently worn, per slot.  -1 = empty. */
        public final int[] equipped = new int[] { -1, -1, -1, -1 };

        CharGrowth copy() {
            CharGrowth g = new CharGrowth();
            g.level = level;
            g.charClass = charClass;
            g.exp = exp;
            System.arraycopy(skills, 0, g.skills, 0, skills.length);
            g.favorLevel = favorLevel;
            g.favorExp = favorExp;
            System.arraycopy(equipped, 0, g.equipped, 0, equipped.length);
            return g;
        }
    }

    /** Per-item progression: equipment upgrade (enchant) level, etc. */
    public static final class ItemGrowth {
        public int enchant = 0;        // 강화 level for equipment
        public int count  = 0;         // owned count, persisted on top of baseline
        ItemGrowth copy() { ItemGrowth g = new ItemGrowth(); g.enchant = enchant; g.count = count; return g; }
    }

    // ---- persisted state ------------------------------------------------
    /** Character ids the account owns, in acquisition order. */
    private final List<String> characters = new ArrayList<String>();
    /** charId -> growth.  Missing entry means "use defaults". */
    /**
     * Characters that carry a bond ("호감도") track.  Only these have favour
     * rows in the client, so seeding a level for any other id does nothing.
     * Kept in sync with GameServer.NARRATIVE_CHAR_IDS.
     */
    public static final String NARRATIVE_IDS =
            "4103,4104,4105,4106,4107,4108,4203,4204,4205,4206,4207,4208,"
          + "4303,4304,4305,4306,4307,4308,4309,4310,4403,4404,4405,4406,"
          + "4503,4504,4505";
    /** Favour level every narrative character starts at on a fresh account. */
    public static final int DEFAULT_FAVOR_LEVEL = 9;


    public static boolean isNarrative(int charId) {
        for (String p : NARRATIVE_IDS.split(",")) {
            try { if (Integer.parseInt(p.trim()) == charId) return true; }
            catch (NumberFormatException ignored) { }
        }
        return false;
    }

    /**
     * A fresh CharGrowth for the roster seed.  Narrative characters start at
     * DEFAULT_FAVOR_LEVEL so the bond track shows immediately; exp stays 0
     * because that is a real level start, not a display-only value.
     */
    public static CharGrowth seededGrowth(int charId) {
        CharGrowth g = new CharGrowth();
        if (isNarrative(charId)) g.favorLevel = DEFAULT_FAVOR_LEVEL;
        return g;
    }

    /**
     * Raise every narrative character to at least DEFAULT_FAVOR_LEVEL.
     *
     * Only ever raises: a save at level 10 (or 9 with real progress) keeps its
     * own state, and non-bond characters are untouched.  This is what makes the
     * default apply to an EXISTING save as well as a fresh one.
     */
    public synchronized int applyFavorFloor() {
        int raised = 0;
        for (String id : characters) {
            try {
                int cid = Integer.parseInt(id);
                if (!isNarrative(cid)) continue;
                CharGrowth g = growthOf(cid);
                if (g.favorLevel < DEFAULT_FAVOR_LEVEL) {
                    g.favorLevel = DEFAULT_FAVOR_LEVEL;
                    g.favorExp = 0;
                    setGrowth(cid, g);
                    raised++;
                }
            } catch (NumberFormatException ignored) { }
        }
        if (raised > 0) {
            Status.log("save: favour floor applied to " + raised
                    + " character(s) (-> level " + DEFAULT_FAVOR_LEVEL + ")");
        }
        return raised;
    }

    private final Map<Integer, CharGrowth> charGrowth = new LinkedHashMap<Integer, CharGrowth>();
    /** itemId -> growth / count. */
    private final Map<Integer, ItemGrowth> itemGrowth = new LinkedHashMap<Integer, ItemGrowth>();
    /** fundId -> amount the player actually owns (not a delta; see below). */
    private final Map<Integer, Long> funds = new LinkedHashMap<Integer, Long>();
    /** itemId -> count the player actually owns. */
    private final Map<Integer, Integer> items = new LinkedHashMap<Integer, Integer>();

    private int clearedChapter = 0;
    private int clearedStage = 0;
    /** Cleared character side-story stage ids (8_CCCC_SS), persisted so a
     *  stage clear survives a proxy or game restart. */
    private final java.util.Set<Integer> narrativeCleared =
            new java.util.LinkedHashSet<Integer>();
    private long revision = 0;
    private int loadedVersion = -1;

    private SharedPreferences prefs;

    /** The application context captured by init(), for other components that
     *  need their own preferences file (facility state, etc.). */
    private static Context appContext;
    public static Context context() { return appContext; }
    private boolean ready = false;

    private SaveStore() { }

    // ---- lifecycle ------------------------------------------------------

    public synchronized void init(Context ctx) {
        if (ready) return;
        appContext = ctx.getApplicationContext();
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();

        // ---- SEED THE ROSTER ONCE -------------------------------------
        //
        // The baseline roster (AssetStore) is the "what a new account starts
        // with" list.  The save has to take a SNAPSHOT of it on first run and
        // then own the roster, because the alternative -- merging baseline
        // into save on every login -- makes the first gacha pull wipe every
        // character that was not in the pull: the save would hold one id, and
        // "save overrides baseline" would drop the other 68.
        //
        // That is the classic "seed resets the player" bug and it is invisible
        // until a restart, so it gets an explicit one-time snapshot plus a
        // stored flag rather than an implicit merge.
        if (!prefs.getBoolean("rosterSnapshot", false)) {
            characters.clear();
            for (String id : AssetStore.get().characters()) {
                characters.add(id);
                try {
                    int cid = Integer.parseInt(id);
                    CharGrowth g = new CharGrowth();
                    // Bond characters start at level 9 so a fresh account shows
                    // the full favour track instead of an empty one.
                    if (isNarrative(cid)) {
                        g.favorLevel = DEFAULT_FAVOR_LEVEL;
                        // Real level, not display-only: the client recomputes
                        // from accumulated points on interaction.
                        // exp stays 0: level 9 starts with 0 points within the level
                    }
                    charGrowth.put(cid, g);
                } catch (NumberFormatException ignored) { }
            }
            prefs.edit().putBoolean("rosterSnapshot", true).apply();
            save();
            Status.log("save: roster snapshot taken from baseline ("
                    + characters.size() + " characters)");
        }

        // Apply the favour floor even when a save already exists: the bond
        // defaults (level 9) must reach an OLD save too, not only a fresh one.
        // applyFavorFloor() only ever raises, so real progress is kept.
        try {
            applyFavorFloor();
        } catch (Throwable ignored) { }

        ready = true;
        // Build the working set immediately.  SessionState otherwise stays empty
        // until the game logs in, so anything that inspects the account before
        // the first login (the web console, a real-device check, an acceptance
        // script) sees an empty bag and reports a false failure.
        try {
            SessionState.get().reset();
        } catch (Throwable ignored) {
            // SessionState may touch AssetStore before it is ready in unit-ish
            // contexts; the login path will rebuild it anyway.
        }
        Status.log("save: loaded v" + loadedVersion + " rev" + revision
                + " chars=" + characters.size()
                + " items=" + items.size()
                + " funds=" + funds.size()
                + " growth=" + charGrowth.size());
    }

    public synchronized boolean isReady() { return ready; }

    /**
     * True when the stored save was written by a newer build than this one.
     * The caller must refuse to enter the game rather than misread the data --
     * a downgrade that silently drops fields is worse than a clear message.
     */
    public synchronized boolean isFromNewerBuild() {
        return loadedVersion > FORMAT_VERSION;
    }

    public synchronized int loadedVersion() { return loadedVersion; }

    private void load() {
        characters.clear();
        charGrowth.clear();
        itemGrowth.clear();
        funds.clear();
        items.clear();

        loadedVersion = prefs.getInt("formatVersion", -1);
        revision = prefs.getLong("revision", 0L);
        clearedChapter = prefs.getInt("storyChapter", 0);
        clearedStage = prefs.getInt("storyStage", 0);
        if (clearedChapter < 0) clearedChapter = 0;
        if (clearedStage < 0) clearedStage = 0;

        narrativeCleared.clear();
        for (String s : prefs.getString("narrativeCleared", "").split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                int id = Integer.parseInt(t);
                if (id >= 8400000 && id <= 8499999) narrativeCleared.add(id);
            } catch (NumberFormatException ignored) { }
        }

        // "4105|9:1|1:3,3,3,3,3,3,3|2500:20000"
        //   id | level:class | skills | favorLevel:favorExp
        for (String rec : prefs.getString("characters", "").split("\n")) {
            String t = rec.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split("\\|");
            String id = p[0].trim();
            if (id.isEmpty()) continue;
            if (!characters.contains(id)) characters.add(id);
            try {
                int cid = Integer.parseInt(id);
                CharGrowth g = new CharGrowth();
                if (p.length > 1) {
                    String[] lc = p[1].split(":");
                    if (lc.length > 0) g.level = Math.max(1, Integer.parseInt(lc[0]));
                    if (lc.length > 1) g.charClass = Math.max(0, Integer.parseInt(lc[1]));
                }
                if (p.length > 2 && !p[2].isEmpty()) {
                    String[] sk = p[2].split(",");
                    for (int i = 0; i < 7 && i < sk.length; i++) {
                        g.skills[i] = Math.max(0, Integer.parseInt(sk[i].trim()));
                    }
                }
                if (p.length > 3) {
                    String[] fe = p[3].split(":");
                    if (fe.length > 0) g.favorLevel = Math.max(1, Integer.parseInt(fe[0]));
                    if (fe.length > 1) g.favorExp = Math.max(0, Integer.parseInt(fe[1]));
                }
                if (p.length > 4) {
                    String[] eq = p[4].split(",");
                    for (int i = 0; i < 4 && i < eq.length; i++) {
                        g.equipped[i] = Integer.parseInt(eq[i].trim());
                    }
                }
                g.exp = prefs.getInt("exp_" + cid, 0);
                if (g.exp < 0) g.exp = 0;
                charGrowth.put(cid, g);
            } catch (NumberFormatException ignored) {
                // Keep the id in the roster even if the growth row is malformed;
                // the character is still owned, it just starts at defaults.
            }
        }

        for (String rec : prefs.getString("items", "").split("\n")) {
            String t = rec.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split(":");
            if (p.length < 2) continue;
            try {
                ItemGrowth g = new ItemGrowth();
                int id = Integer.parseInt(p[0]);
                g.count = Math.max(0, Integer.parseInt(p[1]));
                if (p.length > 2) g.enchant = Math.max(0, Integer.parseInt(p[2]));
                itemGrowth.put(id, g);
            } catch (NumberFormatException ignored) { }
        }

        for (String rec : prefs.getString("funds", "").split("\n")) {
            String t = rec.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split(":");
            if (p.length < 2) continue;
            try { funds.put(Integer.parseInt(p[0]), Long.parseLong(p[1])); }
            catch (NumberFormatException ignored) { }
        }
    }

    /** Write the whole save.  Called after every mutation. */
    private void save() {
        if (prefs == null) return;
        revision++;
        // Track the in-memory version too: after the first successful save this
        // build IS the writer of the stored format, so reporting the pre-load
        // value (-1 on a fresh install) would look like a corrupt save in the
        // console.
        loadedVersion = FORMAT_VERSION;

        StringBuilder ch = new StringBuilder();
        for (String id : characters) {
            if (ch.length() > 0) ch.append('\n');
            CharGrowth g = null;
            try { g = charGrowth.get(Integer.parseInt(id)); }
            catch (NumberFormatException ignored) { }
            if (g == null) { ch.append(id); continue; }
            StringBuilder sk = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if (i > 0) sk.append(',');
                sk.append(g.skills[i]);
            }
            StringBuilder eq = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) eq.append(',');
                eq.append(g.equipped[i]);
            }
            ch.append(id).append('|')
              .append(g.level).append(':').append(g.charClass).append('|')
              .append(sk).append('|')
              .append(g.favorLevel).append(':').append(g.favorExp).append('|')
              .append(eq);
        }

        StringBuilder it = new StringBuilder();
        for (Map.Entry<Integer, ItemGrowth> e : itemGrowth.entrySet()) {
            if (it.length() > 0) it.append('\n');
            ItemGrowth g = e.getValue();
            it.append(e.getKey()).append(':').append(g.count).append(':').append(g.enchant);
        }

        StringBuilder fd = new StringBuilder();
        for (Map.Entry<Integer, Long> e : funds.entrySet()) {
            if (fd.length() > 0) fd.append('\n');
            fd.append(e.getKey()).append(':').append(e.getValue());
        }

        StringBuilder nc = new StringBuilder();
        for (Integer id : narrativeCleared) {
            if (nc.length() > 0) nc.append(',');
            nc.append(id);
        }

        SharedPreferences.Editor ed = prefs.edit()
                .putInt("formatVersion", FORMAT_VERSION)
                .putLong("revision", revision)
                .putString("characters", ch.toString())
                .putString("items", it.toString())
                .putString("funds", fd.toString())
                .putString("narrativeCleared", nc.toString())
                .putInt("storyChapter", clearedChapter)
                .putInt("storyStage", clearedStage);
        // Per-character EXP lives in its own key so adding a character never
        // rewrites (and risks truncating) the rest of the save.
        for (Map.Entry<Integer, CharGrowth> e : charGrowth.entrySet()) {
            ed.putInt("exp_" + e.getKey(), e.getValue().exp);
        }
        ed.apply();
    }

    // ---- characters -----------------------------------------------------

    public synchronized List<String> characters() {
        return new ArrayList<String>(characters);
    }

    public synchronized boolean ownsCharacter(int charId) {
        return characters.contains(String.valueOf(charId));
    }

    /**
     * Add an owned character.  Returns true when it was newly added, false when
     * the account already had it -- a gacha duplicate has to be distinguishable
     * from a first pull so the caller can award a duplicate reward instead of
     * silently doing nothing.
     */
    public synchronized boolean addCharacter(int charId, int startLevel) {
        String id = String.valueOf(charId);
        if (characters.contains(id)) return false;
        characters.add(id);
        CharGrowth g = new CharGrowth();
        g.level = Math.max(1, startLevel);
        charGrowth.put(charId, g);
        save();
        Status.log("save: character " + charId + " acquired (levels=" + g.level + ")");
        return true;
    }

    public synchronized boolean removeCharacter(int charId) {
        String id = String.valueOf(charId);
        if (!characters.remove(id)) return false;
        charGrowth.remove(charId);
        save();
        Status.log("save: character " + charId + " released");
        return true;
    }

    /** Growth row for a character, created at defaults when absent. */
    public synchronized CharGrowth growthOf(int charId) {
        CharGrowth g = charGrowth.get(charId);
        if (g == null) {
            g = new CharGrowth();
            charGrowth.put(charId, g);
        }
        // Sanitise on read: an earlier build stored the EXP in the wrong
        // field width and left negative values behind.  EXP is now earned
        // from items only, so a negative or absurd value clamps to 0.
        if (g.exp < 0) g.exp = 0; // sanitised
        if (g.level < 1) g.level = 1;
        return g.copy();
    }

    /**
     * Rewrites level/exp for every stored character at once.
     *
     * Used to clear the negative-EXP damage done by the earlier field-width
     * bug: after this the roster is level 1 / exp 0, so 326 falls back to the
     * seed level (charLevel) and the seed exp (charExp) for every character.
     */
    public synchronized int resetAllGrowth(int level, int exp) {
        for (java.util.Map.Entry<Integer, CharGrowth> e : charGrowth.entrySet()) {
            CharGrowth g = e.getValue();
            if (g == null) continue;
            g.level = level;
            g.exp = exp;
        }
        save();
        return charGrowth.size();
    }

    public synchronized void setGrowth(int charId, CharGrowth g) {
        if (g == null) return;
        charGrowth.put(charId, g.copy());
        save();
    }

    // ---- items / equipment ----------------------------------------------

    public synchronized ItemGrowth itemOf(int itemId) {
        ItemGrowth g = itemGrowth.get(itemId);
        return g == null ? new ItemGrowth() : g.copy();
    }

    public synchronized void setItemGrowth(int itemId, ItemGrowth g) {
        if (g == null) return;
        itemGrowth.put(itemId, g.copy());
        save();
    }

    public synchronized void setItemCount(int itemId, int count) {
        ItemGrowth g = itemGrowth.get(itemId);
        if (g == null) { g = new ItemGrowth(); itemGrowth.put(itemId, g); }
        g.count = Math.max(0, count);
        save();
    }

    public synchronized int itemCount(int itemId) {
        ItemGrowth g = itemGrowth.get(itemId);
        return g == null ? 0 : g.count;
    }

    /** True when the save holds an explicit row for this fund id. */
    public synchronized boolean tracksFund(int fundId) {
        return funds.containsKey(fundId);
    }

    /** True when the save holds an explicit row for this item id. */
    public synchronized boolean tracksItem(int itemId) {
        return itemGrowth.containsKey(itemId);
    }

    /** {fundId, amount} rows, for merging with the baseline. */
    public synchronized List<long[]> fundRows() {
        List<long[]> out = new ArrayList<long[]>();
        for (Map.Entry<Integer, Long> e : funds.entrySet()) {
            out.add(new long[] { e.getKey(), e.getValue() });
        }
        return out;
    }

    /** {itemId, count} rows, for merging with the baseline. */
    public synchronized List<int[]> itemRows() {
        List<int[]> out = new ArrayList<int[]>();
        for (Map.Entry<Integer, ItemGrowth> e : itemGrowth.entrySet()) {
            out.add(new int[] { e.getKey(), e.getValue().count });
        }
        return out;
    }

    // ---- funds ----------------------------------------------------------

    public synchronized long fund(int fundId) {
        Long v = funds.get(fundId);
        return v == null ? 0L : v;
    }

    public synchronized void setFund(int fundId, long amount) {
        funds.put(fundId, Math.max(0L, amount));
        save();
    }

    /**
     * Add to a fund.  Returns the new balance.  Negative amounts clamp at 0 --
     * a purchase that would overdraw must be rejected by the caller BEFORE
     * calling this, so the clamp here is only a corruption guard.
     */
    public synchronized long addFund(int fundId, long delta) {
        long now = fund(fundId) + delta;
        if (now < 0) now = 0;
        funds.put(fundId, now);
        save();
        return now;
    }

    // ---- story ----------------------------------------------------------

    public synchronized int clearedChapter() { return clearedChapter; }
    public synchronized int clearedStage() { return clearedStage; }

    public synchronized void setStoryProgress(int chapter, int stage) {
        clearedChapter = Math.max(0, chapter);
        clearedStage = Math.max(0, stage);
        save();
        Status.log("save: story ch" + clearedChapter + " st" + clearedStage);
    }

    // ---- character side-story -------------------------------------------

    /** Ids of side-story stages the player has cleared with 3 stars. */
    public synchronized java.util.Set<Integer> narrativeCleared() {
        return new java.util.LinkedHashSet<Integer>(narrativeCleared);
    }

    /** Record a side-story clear.  Returns true when it was newly added. */
    public synchronized boolean clearNarrativeStage(int stageId) {
        if (stageId < 8400000 || stageId > 8499999) return false;
        boolean added = narrativeCleared.add(stageId);
        if (added) { save(); Status.log("save: narrative stage " + stageId + " cleared"); }
        return added;
    }

    /** Forget all live side-story clears (used when the operator lowers N). */
    public synchronized void resetNarrative() {
        narrativeCleared.clear();
        save();
        Status.log("save: narrative progress reset");
    }

    public synchronized int narrativeCount() { return narrativeCleared.size(); }

    /** True when this save has real progress (not a pristine account). */
    public synchronized boolean hasProgress() {
        return !characters.isEmpty() || clearedChapter > 0 || !charGrowth.isEmpty();
    }

    public synchronized long revision() { return revision; }

    // ---- web console / diagnostics --------------------------------------

    public synchronized String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"formatVersion\":").append(FORMAT_VERSION)
          .append(",\"storedVersion\":").append(loadedVersion)
          .append(",\"revision\":").append(revision)
          .append(",\"characters\":[");
        for (int i = 0; i < characters.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(Status.jsonEscape(characters.get(i))).append('"');
        }
        sb.append("],\"growth\":{");
        boolean first = true;
        for (Map.Entry<Integer, CharGrowth> e : charGrowth.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            CharGrowth g = e.getValue();
            sb.append('"').append(e.getKey()).append("\":{")
              .append("\"level\":").append(g.level)
              .append(",\"class\":").append(g.charClass)
              .append(",\"exp\":").append(g.exp)
              .append(",\"favorLevel\":").append(g.favorLevel)
              .append(",\"favorExp\":").append(g.favorExp)
              .append(",\"skills\":[");
            for (int i = 0; i < 7; i++) {
                if (i > 0) sb.append(',');
                sb.append(g.skills[i]);
            }
            sb.append("],\"equipped\":[");
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append(',');
                sb.append(g.equipped[i]);
            }
            sb.append("]}");
        }
        sb.append("},\"items\":{");
        first = true;
        for (Map.Entry<Integer, ItemGrowth> e : itemGrowth.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":{")
              .append("\"count\":").append(e.getValue().count)
              .append(",\"enchant\":").append(e.getValue().enchant).append('}');
        }
        sb.append("},\"funds\":{");
        first = true;
        for (Map.Entry<Integer, Long> e : funds.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("},\"story\":{\"chapter\":").append(clearedChapter)
          .append(",\"stage\":").append(clearedStage).append("}}");
        return sb.toString();
    }

    /**
     * Wipe the player's progress but keep the configured baseline.
     *
     * "Reset" means "back to a fresh account", so the roster is re-seeded from
     * the baseline immediately.  Leaving the character list empty instead made
     * the game answer "No My Characters" -- a reset must land on the same state
     * a brand-new install would, not on an account that owns nothing at all.
     */
    public synchronized void reset() {
        characters.clear();
        charGrowth.clear();
        itemGrowth.clear();
        funds.clear();
        items.clear();
        clearedChapter = 0;
        clearedStage = 0;
        narrativeCleared.clear();
        revision++;
        if (prefs != null) {
            SharedPreferences.Editor ed = prefs.edit().clear()
                    .putInt("formatVersion", FORMAT_VERSION)
                    .putLong("revision", revision)
                    .putBoolean("rosterSnapshot", true);
            ed.apply();
        }
        // Re-seed the roster from the operator's baseline.  Same progression as
        // the init seed, so a reset lands on the same state a fresh install has
        // (bond characters at level 9, the rest at level 1).
        for (String id : AssetStore.get().characters()) {
            characters.add(id);
            try {
                charGrowth.put(Integer.parseInt(id), seededGrowth(Integer.parseInt(id)));
            } catch (NumberFormatException ignored) { }
        }
        save();
        applyFavorFloor();
        Status.log("save: reset to baseline (" + characters.size() + " characters)");
    }

    /**
     * Copy the current progress into a self-contained JSON blob.
     *
     * This is what "extract the current play state from the server" means in
     * practice: a snapshot the operator can archive, inspect, or feed back in.
     */
    public synchronized String exportJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"formatVersion\":").append(FORMAT_VERSION)
          .append(",\"revision\":").append(revision)
          .append(",\"story\":{\"chapter\":").append(clearedChapter)
          .append(",\"stage\":").append(clearedStage).append("},")
          .append("\"narrative\":[");
        boolean firstNc = true;
        for (Integer id : narrativeCleared) {
            if (!firstNc) sb.append(',');
            firstNc = false;
            sb.append(id);
        }
        sb.append("],\"characters\":[");
        for (int i = 0; i < characters.size(); i++) {
            if (i > 0) sb.append(',');
            String id = characters.get(i);
            sb.append("{\"id\":").append(id);
            try {
                CharGrowth g = charGrowth.get(Integer.parseInt(id));
                if (g != null) {
                    sb.append(",\"level\":").append(g.level)
                      .append(",\"class\":").append(g.charClass)
                      .append(",\"exp\":").append(g.exp)
                      .append(",\"favorLevel\":").append(g.favorLevel)
                      .append(",\"favorExp\":").append(g.favorExp)
                      .append(",\"skills\":[");
                    for (int k = 0; k < 7; k++) {
                        if (k > 0) sb.append(',');
                        sb.append(g.skills[k]);
                    }
                    sb.append("],\"equipped\":[");
                    for (int k = 0; k < 4; k++) {
                        if (k > 0) sb.append(',');
                        sb.append(g.equipped[k]);
                    }
                    sb.append(']');
                }
            } catch (NumberFormatException ignored) { }
            sb.append('}');
        }
        sb.append("],\"items\":[");
        boolean first = true;
        for (Map.Entry<Integer, ItemGrowth> e : itemGrowth.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(e.getKey())
              .append(",\"count\":").append(e.getValue().count)
              .append(",\"enchant\":").append(e.getValue().enchant).append('}');
        }
        sb.append("],\"funds\":[");
        first = true;
        for (Map.Entry<Integer, Long> e : funds.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(e.getKey())
              .append(",\"amount\":").append(e.getValue()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Replace the whole account state from an exported JSON blob.
     *
     * The shape is exactly what exportJson() produces, so a player can keep a
     * copy and restore it later.  Anything unrecognised is ignored rather than
     * guessed at: a half-applied save is worse than a rejected one.
     *
     * The previous state is written to "characters.pre_import" (and funds/items)
     * before the swap, so a bad paste can be undone without adb.
     */
    public synchronized String importJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "{\"ok\":false,\"err\":\"empty\"}";
        }
        // Minimal JSON parsing: this class has no org.json on purpose (the proxy
        // stays dependency-free), so the few fields we need are read directly.
        java.util.List<String> ids = new java.util.ArrayList<String>();
        java.util.Map<Integer, CharGrowth> growth = new java.util.LinkedHashMap<Integer, CharGrowth>();
        java.util.Map<Integer, Long> newFunds = new java.util.LinkedHashMap<Integer, Long>();
        java.util.Map<Integer, Integer> newItems = new java.util.LinkedHashMap<Integer, Integer>();
        java.util.Set<Integer> narr = new java.util.LinkedHashSet<Integer>();
        int ch = 0, st = 0;

        try {
            int ci = json.indexOf("\"story\"");
            if (ci >= 0) {
                int b = json.indexOf('{', ci), e = json.indexOf('}', b);
                if (b > 0 && e > b) {
                    String s = json.substring(b + 1, e);
                    ch = jsonInt(s, "chapter", 0);
                    st = jsonInt(s, "stage", 0);
                }
            }
            int ni = json.indexOf("\"narrative\"");
            if (ni >= 0) {
                int b = json.indexOf('[', ni), e = json.indexOf(']', b);
                if (b > 0 && e > b) {
                    for (String t : json.substring(b + 1, e).split(",")) {
                        t = t.trim();
                        if (t.isEmpty()) continue;
                        try { narr.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
                    }
                }
            }
            int chi = json.indexOf("\"characters\"");
            if (chi >= 0) {
                int b = json.indexOf('[', chi);
                if (b > 0) {
                    int p2 = b + 1;
                    // The characters array ends at the matching ']'.  Without
                    // this bound the loop walks into items/funds and invents
                    // characters out of them.
                    int arrEnd = -1;
                    {
                        int depth0 = 0;
                        for (int k = b; k < json.length(); k++) {
                            char c0 = json.charAt(k);
                            if (c0 == '[') depth0++;
                            else if (c0 == ']') {
                                depth0--;
                                if (depth0 == 0) { arrEnd = k; break; }
                            }
                        }
                    }
                    if (arrEnd < 0) arrEnd = json.length();
                    while (true) {
                        // Find the start of the next object, then match its
                        // closing brace by depth so nested arrays (skills,
                        // equipped) do not terminate the object early.
                        int ob = json.indexOf('{', p2);
                        if (ob < 0 || ob > arrEnd) break;
                        int depth = 0, oe = -1;
                        for (int k = ob; k < json.length(); k++) {
                            char ch2 = json.charAt(k);
                            if (ch2 == '{') depth++;
                            else if (ch2 == '}') {
                                depth--;
                                if (depth == 0) { oe = k; break; }
                            }
                        }
                        if (oe < 0) break;
                        String obj = json.substring(ob + 1, oe);
                        p2 = oe + 1;
                        int id = jsonInt(obj, "id", -1);
                        if (id < 0) continue;
                        ids.add(String.valueOf(id));
                        CharGrowth g = new CharGrowth();
                        g.level = Math.max(1, jsonInt(obj, "level", 1));
                        g.charClass = Math.max(0, jsonInt(obj, "class", 1));
                        g.exp = Math.max(0, jsonInt(obj, "exp", 0));
                        g.favorLevel = Math.max(1, jsonInt(obj, "favorLevel", 1));
                        g.favorExp = Math.max(0, jsonInt(obj, "favorExp", 0));
                        int sb2 = obj.indexOf("\"skills\"");
                        if (sb2 >= 0) {
                            int x = obj.indexOf('[', sb2), y = obj.indexOf(']', x);
                            if (x > 0 && y > x) {
                                String[] q = obj.substring(x + 1, y).split(",");
                                for (int i = 0; i < 7 && i < q.length; i++) {
                                    try { g.skills[i] = Math.max(0, Integer.parseInt(q[i].trim())); }
                                    catch (NumberFormatException ignored) { }
                                }
                            }
                        }
                        int eb = obj.indexOf("\"equipped\"");
                        if (eb >= 0) {
                            int x = obj.indexOf('[', eb), y = obj.indexOf(']', x);
                            if (x > 0 && y > x) {
                                String[] q = obj.substring(x + 1, y).split(",");
                                for (int i = 0; i < 4 && i < q.length; i++) {
                                    try { g.equipped[i] = Integer.parseInt(q[i].trim()); }
                                    catch (NumberFormatException ignored) { }
                                }
                            }
                        }
                        growth.put(id, g);
                    }
                }
            }
            int fi = json.indexOf("\"funds\"");
            if (fi >= 0) {
                int b = json.indexOf('[', fi), e = json.lastIndexOf(']');
                if (b > 0 && e > b) {
                    String arr = json.substring(b + 1, e);
                    int p = 0;
                    while (true) {
                        int ob = arr.indexOf('{', p);
                        if (ob < 0) break;
                        int oe = arr.indexOf('}', ob);
                        if (oe < 0) break;
                        String obj = arr.substring(ob + 1, oe);
                        p = oe + 1;
                        int id = jsonInt(obj, "id", -1);
                        long amt = (long) jsonInt(obj, "amount", 0);
                        if (id >= 0) newFunds.put(id, amt);
                    }
                }
            }
            int ii = json.indexOf("\"items\"");
            if (ii >= 0) {
                int b = json.indexOf('[', ii), e = json.lastIndexOf(']');
                if (b > 0 && e > b) {
                    String arr = json.substring(b + 1, e);
                    int p = 0;
                    while (true) {
                        int ob = arr.indexOf('{', p);
                        if (ob < 0) break;
                        int oe = arr.indexOf('}', ob);
                        if (oe < 0) break;
                        String obj = arr.substring(ob + 1, oe);
                        p = oe + 1;
                        int id = jsonInt(obj, "id", -1);
                        int cnt = jsonInt(obj, "count", 0);
                        if (id >= 0) newItems.put(id, Math.max(0, cnt));
                    }
                }
            }
        } catch (Throwable t) {
            Status.log("save: import parse failed: " + t);
            return "{\"ok\":false,\"err\":\"" + Status.jsonEscape(String.valueOf(t)) + "\"}";
        }

        if (ids.isEmpty()) {
            return "{\"ok\":false,\"err\":\"no characters in payload\"}";
        }

        // ---- keep the previous state so a bad paste can be undone ----------
        if (prefs != null) {
            prefs.edit()
                 .putString("characters.pre_import", prefs.getString("characters", ""))
                 .putString("funds.pre_import", prefs.getString("funds", ""))
                 .putString("items.pre_import", prefs.getString("items", ""))
                 .apply();
        }

        characters.clear();
        characters.addAll(ids);
        charGrowth.clear();
        charGrowth.putAll(growth);
        items.clear();
        itemGrowth.clear();
        for (java.util.Map.Entry<Integer, Integer> e : newItems.entrySet()) {
            ItemGrowth g = new ItemGrowth();
            g.count = e.getValue();
            itemGrowth.put(e.getKey(), g);
            items.put(e.getKey(), e.getValue());
        }
        funds.clear();
        for (java.util.Map.Entry<Integer, Long> e : newFunds.entrySet()) {
            funds.put(e.getKey(), e.getValue());
        }
        clearedChapter = ch;
        clearedStage = st;
        narrativeCleared.clear();
        narrativeCleared.addAll(narr);
        revision++;
        prefs.edit().putBoolean("rosterSnapshot", true).apply();
        save();
        Status.log("save: imported " + characters.size() + " characters, "
                + items.size() + " items, " + funds.size() + " funds");
        return "{\"ok\":true,\"characters\":" + characters.size()
                + ",\"items\":" + items.size() + ",\"funds\":" + funds.size() + "}";
    }

    /** Read one integer field out of a flat JSON object body. */
    private static int jsonInt(String obj, String key, int dflt) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return dflt;
        int c = obj.indexOf(':', k);
        if (c < 0) return dflt;
        int i = c + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        int j = i;
        if (j < obj.length() && (obj.charAt(j) == '-' || obj.charAt(j) == '+')) j++;
        while (j < obj.length() && Character.isDigit(obj.charAt(j))) j++;
        if (j == i) return dflt;
        try { return Integer.parseInt(obj.substring(i, j)); }
        catch (NumberFormatException e) { return dflt; }
    }

}
