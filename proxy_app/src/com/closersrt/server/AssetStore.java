package com.closersrt.server;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-account assets: gold / prism (funds), inventory items, and characters.
 *
 * The offline server has to answer three packets with real content or the
 * lobby shows an empty player:
 *
 *   SC_FUNDINFORES(327)        -- the currency row
 *   SC_ITEMINFORES(325)        -- the inventory
 *   SC_CHARACTERINFO2RES(326)  -- the character card list
 *
 * Wire formats recovered by disassembling libUE4.so are documented in
 * GameServer.fundPayload / itemPayload / characterPayload.
 */
public final class AssetStore {

    private static final String PREFS = "closersrt_assets";

    private static final AssetStore INSTANCE = new AssetStore();
    public static AssetStore get() { return INSTANCE; }

    /** One currency row: a fund id and how much the account owns. */
    public static final class Fund {
        public int id;
        public long amount;
        Fund(int id, long amount) { this.id = id; this.amount = amount; }
    }

    /** One inventory row: an item id and how many. */
    public static final class Item {
        public int id;
        public int count;
        Item(int id, int count) { this.id = id; this.count = count; }
    }

    private final List<Fund> funds = new ArrayList<Fund>();
    private final List<Item> items = new ArrayList<Item>();
    private final List<String> characters = new ArrayList<String>();
    private SharedPreferences prefs;

    /**
     * Story progress: how far the account has cleared, in chapter/stage terms.
     *
     * Story chapters are gated server-side: the client only lists what the
     * server says is unlocked.  These two numbers let the operator open later
     * chapters for testing without replaying the story.
     */
    private int clearedChapter = 0;
    private int clearedStage = 0;

    /**
     * Every playable character in Character_Base.tbd (69 rows).
     *
     * Seeded when the stored list is empty, so a fresh install (or a wiped
     * configuration) shows the whole roster instead of one character.  Each row
     * is sent with the same level (10) and the same 7 skill bytes, so they all
     * arrive unlocked at skill level 1.
     */
    public static final String[] DEFAULT_CHARACTERS = {
            "1201", "1301", "1401", "2101", "2102", "2201", "2202", "2203", "2301", "2302",
            "2501", "2502", "3101", "3102", "3103", "3201", "3202", "3203", "3204", "3301",
            "3302", "3303", "3304", "3305", "3401", "3402", "3403", "3404", "3501", "3502",
            "3503", "4101", "4102", "4103", "4104", "4105", "4106", "4107", "4108", "4201",
            "4202", "4203", "4204", "4205", "4206", "4207", "4208", "4209", "4301", "4302",
            "4303", "4304", "4305", "4306", "4307", "4308", "4309", "4310", "4401", "4402",
            "4403", "4404", "4405", "4406", "4501", "4502", "4503", "4504", "4505",
    };

    /**
     * Items every new account starts with.
     *
     *   4 EXP chips at 999   (topped up on every login, see CHIP_TOPUP)
     *   240 equipment pieces, 1 each  (ETB_Item_TypeMain 2, id 20001..54108)
     *   112 consumables/materials, 1 each
     *     1001-1005     box/coupon rows
     *     10001-12801   growth material + research codes
     *     101000-200021 facility / event material
     *
     * Generated from Item.tbd via ItemCatalog; regenerating is safe because
     * only ids the client actually knows are listed.
     */
    public static final int[][] DEFAULT_ITEMS = {
        // EXP chips: 999 each, topped up on every login.
        {11001, 999}, {11002, 999}, {11003, 999}, {11004, 999},
        // Equipment: 1 each (240)
        {20001,1}, {20002,1}, {20003,1}, {20004,1}, {20005,1}, {20006,1}, {20007,1}, {20008,1},
        {20105,1}, {20106,1}, {20107,1}, {20108,1}, {21001,1}, {21002,1}, {21003,1}, {21004,1},
        {21005,1}, {21006,1}, {21007,1}, {21008,1}, {21105,1}, {21106,1}, {21107,1}, {21108,1},
        {22001,1}, {22002,1}, {22003,1}, {22004,1}, {22005,1}, {22006,1}, {22007,1}, {22008,1},
        {22105,1}, {22106,1}, {22107,1}, {22108,1}, {23001,1}, {23002,1}, {23003,1}, {23004,1},
        {23005,1}, {23006,1}, {23007,1}, {23008,1}, {23105,1}, {23106,1}, {23107,1}, {23108,1},
        {24001,1}, {24002,1}, {24003,1}, {24004,1}, {24005,1}, {24006,1}, {24007,1}, {24008,1},
        {24105,1}, {24106,1}, {24107,1}, {24108,1}, {30001,1}, {30002,1}, {30003,1}, {30004,1},
        {30005,1}, {30006,1}, {30007,1}, {30008,1}, {30105,1}, {30106,1}, {30107,1}, {30108,1},
        {31001,1}, {31002,1}, {31003,1}, {31004,1}, {31005,1}, {31006,1}, {31007,1}, {31008,1},
        {31105,1}, {31106,1}, {31107,1}, {31108,1}, {32001,1}, {32002,1}, {32003,1}, {32004,1},
        {32005,1}, {32006,1}, {32007,1}, {32008,1}, {32105,1}, {32106,1}, {32107,1}, {32108,1},
        {33001,1}, {33002,1}, {33003,1}, {33004,1}, {33005,1}, {33006,1}, {33007,1}, {33008,1},
        {33105,1}, {33106,1}, {33107,1}, {33108,1}, {34001,1}, {34002,1}, {34003,1}, {34004,1},
        {34005,1}, {34006,1}, {34007,1}, {34008,1}, {34105,1}, {34106,1}, {34107,1}, {34108,1},
        {40001,1}, {40002,1}, {40003,1}, {40004,1}, {40005,1}, {40006,1}, {40007,1}, {40008,1},
        {40105,1}, {40106,1}, {40107,1}, {40108,1}, {41001,1}, {41002,1}, {41003,1}, {41004,1},
        {41005,1}, {41006,1}, {41007,1}, {41008,1}, {41105,1}, {41106,1}, {41107,1}, {41108,1},
        {42001,1}, {42002,1}, {42003,1}, {42004,1}, {42005,1}, {42006,1}, {42007,1}, {42008,1},
        {42105,1}, {42106,1}, {42107,1}, {42108,1}, {43001,1}, {43002,1}, {43003,1}, {43004,1},
        {43005,1}, {43006,1}, {43007,1}, {43008,1}, {43105,1}, {43106,1}, {43107,1}, {43108,1},
        {44001,1}, {44002,1}, {44003,1}, {44004,1}, {44005,1}, {44006,1}, {44007,1}, {44008,1},
        {44105,1}, {44106,1}, {44107,1}, {44108,1}, {50001,1}, {50002,1}, {50003,1}, {50004,1},
        {50005,1}, {50006,1}, {50007,1}, {50008,1}, {50105,1}, {50106,1}, {50107,1}, {50108,1},
        {51001,1}, {51002,1}, {51003,1}, {51004,1}, {51005,1}, {51006,1}, {51007,1}, {51008,1},
        {51105,1}, {51106,1}, {51107,1}, {51108,1}, {52001,1}, {52002,1}, {52003,1}, {52004,1},
        {52005,1}, {52006,1}, {52007,1}, {52008,1}, {52105,1}, {52106,1}, {52107,1}, {52108,1},
        {53001,1}, {53002,1}, {53003,1}, {53004,1}, {53005,1}, {53006,1}, {53007,1}, {53008,1},
        {53105,1}, {53106,1}, {53107,1}, {53108,1}, {54001,1}, {54002,1}, {54003,1}, {54004,1},
        {54005,1}, {54006,1}, {54007,1}, {54008,1}, {54105,1}, {54106,1}, {54107,1}, {54108,1},
        // Consumables / materials: 1 each (112)
        {1001,1}, {1002,1}, {1003,1}, {1004,1}, {1005,1}, {10001,1}, {11100,1}, {11201,1},
        {11202,1}, {11203,1}, {11204,1}, {11302,1}, {11303,1}, {11304,1}, {11305,1}, {11401,1},
        {11402,1}, {11403,1}, {11404,1}, {11405,1}, {11406,1}, {11407,1}, {11411,1}, {11412,1},
        {11413,1}, {11414,1}, {11415,1}, {11416,1}, {11501,1}, {11502,1}, {11503,1}, {11504,1},
        {11505,1}, {11506,1}, {11507,1}, {11508,1}, {11509,1}, {12501,1}, {12601,1}, {12701,1},
        {12801,1}, {101000,1}, {101010,1}, {101011,1}, {101012,1}, {101020,1}, {101030,1}, {101040,1},
        {101041,1}, {101042,1}, {102000,1}, {102010,1}, {102011,1}, {102012,1}, {102020,1}, {102030,1},
        {102040,1}, {102041,1}, {102042,1}, {103000,1}, {103010,1}, {103011,1}, {103012,1}, {103020,1},
        {103030,1}, {103040,1}, {103041,1}, {103042,1}, {104000,1}, {104010,1}, {104011,1}, {104012,1},
        {104020,1}, {104030,1}, {104040,1}, {104041,1}, {104042,1}, {105001,1}, {105010,1}, {105011,1},
        {105012,1}, {105020,1}, {105030,1}, {105040,1}, {105041,1}, {105042,1}, {109999,1}, {200001,1},
        {200002,1}, {200003,1}, {200004,1}, {200005,1}, {200006,1}, {200007,1}, {200008,1}, {200009,1},
        {200010,1}, {200011,1}, {200012,1}, {200013,1}, {200014,1}, {200015,1}, {200016,1}, {200017,1},
        {200018,1}, {200019,1}, {200020,1}, {200021,1},
    };
    /** Login top-up count for the EXP chips (nothing else is touched). */
    public static final int CHIP_TOPUP = 999;

    /**
     * Currency a fresh account starts with.
     *
     * These are Item.tbd IDs, not ETB_Common_Fund keys.  GameServer.fundTypeOf
     * maps 101/1001 -> gold key 1 and 102/103/54868 -> prism key 2, so the
     * prism row MUST use 102 (id 2 is not a prism id and silently lands on the
     * gold key, which is why the prism counter stayed at zero).
     */
    public static final int[][] DEFAULT_FUNDS = {
        {101, 1000000},
        {102, 100000},
        {103, 100000},
    };

    private AssetStore() { }

    public synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        funds.clear();
        items.clear();
        characters.clear();

        String f = prefs.getString("funds", "");
        for (String rec : f.split("\n")) {
            String[] p = rec.trim().split(":");
            if (p.length < 2) continue;
            try { funds.add(new Fund(Integer.parseInt(p[0]), Long.parseLong(p[1]))); }
            catch (NumberFormatException ignored) { }
        }
        String it = prefs.getString("items", "");
        for (String rec : it.split("\n")) {
            String[] p = rec.trim().split(":");
            if (p.length < 2) continue;
            try { items.add(new Item(Integer.parseInt(p[0]), Integer.parseInt(p[1]))); }
            catch (NumberFormatException ignored) { }
        }
        clearedChapter = prefs.getInt("storyChapter", 0);
        clearedStage = prefs.getInt("storyStage", 0);

        String ch = prefs.getString("characters", "");
        for (String rec : ch.split("\n")) {
            String t = rec.trim();
            if (!t.isEmpty()) characters.add(t);
        }

        // Seed the full roster once per install.  After that the list is owned
        // by the operator (web console), so removing a character sticks.
        // Ensure the configured baseline rows exist.
        //
        // This used to run only once (defaultItemsSeeded_v2), which meant a
        // build that ADDED rows to DEFAULT_ITEMS never delivered them to an
        // install that had already seeded: expanding the equipment/default
        // list silently did nothing.  Running it every start is safe because it
        // only ever adds a row whose id is absent, and never lowers a count the
        // operator or the player already has.
        //
        // The flag is still honoured for the *first* seeding message, but no
        // longer gates the loop.
        boolean firstSeed = !prefs.getBoolean("defaultItemsSeeded_v2", false);
        {
            for (int[] dflt : DEFAULT_ITEMS) {
                boolean have = false;
                for (Item x : items) {
                    if (x.id == dflt[0]) { have = true; break; }
                }
                if (!have) items.add(new Item(dflt[0], dflt[1]));
            }
            prefs.edit().putBoolean("defaultItemsSeeded_v2", true).apply();
            save();
        }

        // Currency defaults.  Separate flag from the items so an install that
        // was seeded before the EXP chips existed still receives everything.
        if (!prefs.getBoolean("defaultFundsSeeded2", false)) {
            for (int[] dflt : DEFAULT_FUNDS) {
                boolean have = false;
                for (Fund x : funds) {
                    if (x.id == dflt[0]) { have = true; break; }
                }
                if (!have) funds.add(new Fund(dflt[0], dflt[1]));
            }
            prefs.edit().putBoolean("defaultFundsSeeded2", true).apply();
            save();
            Status.log("asset: default funds seeded (gold 1,000,000 / prism 100,000)");
        }

        if (!prefs.getBoolean("rosterSeeded", false) || characters.isEmpty()) {
            characters.clear();
            for (String id : DEFAULT_CHARACTERS) {
                characters.add(id);
            }
            prefs.edit().putBoolean("rosterSeeded", true).apply();
            save();
        }

        // Currency and items used to start empty because the fund-record wire
        // shape was still being probed.  It is understood now (BUG-001 /
        // STAGE-43), so a fresh account is seeded with gold, prism, the EXP
        // chips and a little gear; the web console can still override them.
    }

    private void save() {
        if (prefs == null) return;
        StringBuilder f = new StringBuilder();
        for (Fund x : funds) {
            if (f.length() > 0) f.append('\n');
            f.append(x.id).append(':').append(x.amount);
        }
        StringBuilder it = new StringBuilder();
        for (Item x : items) {
            if (it.length() > 0) it.append('\n');
            it.append(x.id).append(':').append(x.count);
        }
        StringBuilder ch = new StringBuilder();
        for (String s : characters) {
            if (ch.length() > 0) ch.append('\n');
            ch.append(s);
        }
        prefs.edit()
                .putString("funds", f.toString())
                .putString("items", it.toString())
                .putString("characters", ch.toString())
                .putInt("storyChapter", clearedChapter)
                .putInt("storyStage", clearedStage)
                .apply();
    }

    // ---- mutation ---------------------------------------------------------

    public synchronized void setFund(int id, long amount) {
        for (Fund x : funds) {
            if (x.id == id) { x.amount = amount; save(); return; }
        }
        funds.add(new Fund(id, amount));
        save();
        Status.log("asset: fund " + id + " = " + amount);
    }

    public synchronized void removeFund(int id) {
        for (int i = 0; i < funds.size(); i++) {
            if (funds.get(i).id == id) { funds.remove(i); save(); return; }
        }
    }

    public synchronized void clearAll() {
        funds.clear();
        items.clear();
        characters.clear();
        save();
    }

    public synchronized void addItem(int id, int count) {
        for (Item x : items) {
            if (x.id == id) { x.count += count; save(); return; }
        }
        items.add(new Item(id, count));
        save();
        Status.log("asset: item " + id + " x" + count);
    }

    public synchronized void setItem(int id, int count) {
        for (Item x : items) {
            if (x.id == id) { x.count = count; save(); return; }
        }
        items.add(new Item(id, count));
        save();
    }

    public synchronized void removeItem(int id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) { items.remove(i); save(); return; }
        }
    }

    public synchronized void addCharacter(String id) {
        for (String s : characters) if (s.equals(id)) return;
        characters.add(id);
        save();
        Status.log("asset: character " + id);
    }

    public synchronized void removeCharacter(String id) {
        characters.remove(id);
        save();
    }

    // ---- read -------------------------------------------------------------

    public synchronized List<Fund> funds() { return new ArrayList<Fund>(funds); }
    public synchronized List<Item> items() { return new ArrayList<Item>(items); }
    public synchronized List<String> characters() { return new ArrayList<String>(characters); }

    public synchronized int clearedChapter() { return clearedChapter; }
    public synchronized int clearedStage() { return clearedStage; }

    public synchronized void setStoryProgress(int chapter, int stage) {
        clearedChapter = Math.max(0, chapter);
        clearedStage = Math.max(0, stage);
        save();
        Status.log("asset: story progress ch" + clearedChapter + " st" + clearedStage);
    }

    public synchronized String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"funds\":[");
        for (int i = 0; i < funds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(funds.get(i).id)
              .append(",\"amount\":").append(funds.get(i).amount).append('}');
        }
        sb.append("],\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(items.get(i).id)
              .append(",\"count\":").append(items.get(i).count).append('}');
        }
        sb.append("],\"characters\":[");
        for (int i = 0; i < characters.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(Status.jsonEscape(characters.get(i))).append('"');
        }
        sb.append("],\"story\":{\"chapter\":").append(clearedChapter)
          .append(",\"stage\":").append(clearedStage).append('}');
        return sb.append("}").toString();
    }

    public synchronized void reset() {
        funds.clear();
        items.clear();
        characters.clear();
        load();
    }
}