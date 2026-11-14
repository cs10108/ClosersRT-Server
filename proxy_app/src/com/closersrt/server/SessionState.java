package com.closersrt.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-login working copy of the account.
 *
 * COMPOSITION: SaveStore (the player's own progress) OVER AssetStore (the
 * operator's configured baseline).  A field is taken from the save when the
 * player has one, otherwise from the baseline.  That is what makes play
 * accumulate while the web console still works as a "starting point" editor --
 * the console writes the baseline, the player writes the save, and the save
 * wins wherever it has something.
 *
 * HISTORY: before SaveStore existed this class was rebuilt from AssetStore on
 * every login, which meant every restart snapped gold, items, characters and
 * levels back to the configured defaults.  That is correct for a fresh account
 * and wrong for one that has been played; the split above fixes it without
 * removing the console's ability to reset an account.
 */
public final class SessionState {

    private static final SessionState INSTANCE = new SessionState();
    public static SessionState get() { return INSTANCE; }

    private final List<AssetStore.Item> items = new ArrayList<AssetStore.Item>();
    private final List<AssetStore.Fund> funds = new ArrayList<AssetStore.Fund>();
    private final List<String> characters = new ArrayList<String>();
    private int clearedChapter = 0;
    private int clearedStage = 0;
    private long loginAt = 0;

    private SessionState() { }

    /**
     * Rebuild the working copy from baseline + save.  Called once per login.
     *
     * Ordering matters: the save is read first so that its own character list
     * defines ownership, and the baseline only fills in what the save does not
     * mention.  A character removed in the console therefore stays removed
     * (it is absent from both), while a character pulled from the gacha stays
     * owned even though the baseline never knew about it.
     */
    public synchronized void reset() {
        items.clear();
        funds.clear();
        characters.clear();

        AssetStore base = AssetStore.get();
        SaveStore save = SaveStore.get();
        boolean haveSave = save.isReady() && save.hasProgress();

        // ---- characters ------------------------------------------------
        // SaveStore took a one-time snapshot of the baseline roster on first
        // run, so from then on the SAVE owns the roster outright.  Do not merge
        // the baseline in here: merging would re-add characters the player
        // deliberately dropped, and (worse) the first gacha pull would look
        // like it wiped the other 68 because the save only held the pull.
        if (save.isReady() && !save.characters().isEmpty()) {
            characters.addAll(save.characters());
        } else {
            characters.addAll(base.characters());
        }

        // ---- funds -----------------------------------------------------
        // Baseline rows first, then the save overrides the ones it tracks.
        for (AssetStore.Fund f : base.funds()) {
            long amount = f.amount;
            if (haveSave && save.tracksFund(f.id)) {
                // Trust the save even when it says 0 -- but ONLY when the save
                // has a row at all.  The earlier bug was the opposite: a row
                // holding 0 (because a sale had just written it) was treated as
                // "no opinion" and the baseline was used, so gold appeared to
                // jump back up.  Presence of the row is the signal, not its
                // value.
                amount = save.fund(f.id);
            }
            funds.add(new AssetStore.Fund(f.id, amount));
        }
        if (haveSave) {
            for (long[] row : save.fundRows()) {
                int id = (int) row[0];
                boolean present = false;
                for (AssetStore.Fund f : funds) {
                    if (f.id == id) { present = true; break; }
                }
                if (!present) funds.add(new AssetStore.Fund(id, row[1]));
            }
        }

        // ---- items -----------------------------------------------------
        for (AssetStore.Item it : base.items()) {
            int count = it.count;
            if (haveSave && save.tracksItem(it.id)) {
                count = save.itemCount(it.id);   // 0 is a real answer here
            }
            items.add(new AssetStore.Item(it.id, count));
        }
        if (haveSave) {
            for (int[] row : save.itemRows()) {
                int id = row[0];
                boolean present = false;
                for (AssetStore.Item it : items) {
                    if (it.id == id) { present = true; break; }
                }
                if (!present) items.add(new AssetStore.Item(id, row[1]));
            }
        }

        // ---- story -----------------------------------------------------
        // Progress only ever moves forward; take whichever is further.
        clearedChapter = Math.max(base.clearedChapter(), haveSave ? save.clearedChapter() : 0);
        clearedStage   = Math.max(base.clearedStage(),   haveSave ? save.clearedStage()   : 0);

        loginAt = System.currentTimeMillis() / 1000L;
        Status.log("session: reset from " + (haveSave ? "save+baseline" : "baseline")
                + " (" + items.size() + " items, " + funds.size() + " funds, "
                + characters.size() + " chars, ch" + clearedChapter + ")");
    }

    public synchronized long loginAt() { return loginAt; }

    public synchronized List<AssetStore.Item> items() {
        return new ArrayList<AssetStore.Item>(items);
    }

    public synchronized List<AssetStore.Fund> funds() {
        return new ArrayList<AssetStore.Fund>(funds);
    }

    public synchronized List<String> characters() {
        return new ArrayList<String>(characters);
    }

    public synchronized int clearedChapter() { return clearedChapter; }
    public synchronized int clearedStage() { return clearedStage; }

    /**
     * Add to the stack the player owns.
     *
     * Writes through to SaveStore so the change survives a restart; updating
     * only the in-memory list is exactly the bug this class used to have.
     */
    public synchronized int addItem(int id, int count) {
        int now = 0;
        boolean found = false;
        for (AssetStore.Item it : items) {
            if (it.id == id) {
                now = Math.max(0, it.count + count);
                it.count = now;
                found = true;
                break;
            }
        }
        if (!found) {
            now = Math.max(0, count);
            if (now > 0) items.add(new AssetStore.Item(id, now));
        }
        SaveStore.get().setItemCount(id, now);
        return now;
    }

    /** Absolute set, persisted. */
    public synchronized void setItemCount(int id, int count) {
        boolean found = false;
        for (AssetStore.Item it : items) {
            if (it.id == id) { it.count = Math.max(0, count); found = true; break; }
        }
        // An item the baseline never mentioned still has to become part of the
        // working set, otherwise the next read of this id goes looking in a
        // list that does not contain it and reports 0.
        if (!found && count > 0) {
            items.add(new AssetStore.Item(id, Math.max(0, count)));
        }
        SaveStore.get().setItemCount(id, count);
    }

    /**
     * Units of an item the player holds.
     *
     * Falls back to the operator's baseline when the working set predates this
     * id: the console can add a stack at any time, and a handler that only
     * consulted the in-memory list would then answer "you own none" for
     * something the bag visibly contains.
     */
    public synchronized int itemCount(int id) {
        for (AssetStore.Item it : items) {
            if (it.id == id) return it.count;
        }
        for (AssetStore.Item it : AssetStore.get().items()) {
            if (it.id == id) return it.count;
        }
        return 0;
    }

    /**
     * Add to a fund and persist.
     *
     * Returns the new balance.  Callers that spend must check the balance first:
     * the clamp at zero here is a corruption guard, not an overdraft facility.
     */
    public synchronized long addFund(int id, long amount) {
        long now = 0;
        boolean found = false;
        for (AssetStore.Fund f : funds) {
            if (f.id == id) {
                now = Math.max(0L, f.amount + amount);
                f.amount = now;
                found = true;
                break;
            }
        }
        if (!found) {
            now = Math.max(0L, amount);
            if (now > 0) funds.add(new AssetStore.Fund(id, now));
        }
        SaveStore.get().setFund(id, now);
        return now;
    }

    /** Absolute set, persisted. */
    public synchronized void setFund(int id, long amount) {
        for (AssetStore.Fund f : funds) {
            if (f.id == id) { f.amount = Math.max(0L, amount); break; }
        }
        SaveStore.get().setFund(id, amount);
    }

    public synchronized long fund(int id) {
        for (AssetStore.Fund f : funds) {
            if (f.id == id) return f.amount;
        }
        for (AssetStore.Fund f : AssetStore.get().funds()) {
            if (f.id == id) return f.amount;
        }
        return 0L;
    }

    public synchronized void setStoryProgress(int chapter, int stage) {
        clearedChapter = chapter;
        clearedStage = stage;
        SaveStore.get().setStoryProgress(chapter, stage);
    }
}
