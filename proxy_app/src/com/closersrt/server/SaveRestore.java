package com.closersrt.server;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;

/**
 * A FIXED default account save, restored so every launch starts from the same
 * known-good state.
 *
 * Why this exists
 * ---------------
 * The client writes its progress back into
 * /sdcard/Android/data/com.naddicgames.projectm/files/SaveGames/CMAccountSlot.sav,
 * so once a session dies part way through -- for example in the middle of the
 * tutorial -- the next launch reads that half-finished state and dies in the
 * same place.  The failure becomes self-perpetuating and it becomes impossible
 * to tell a real regression from the debris of the previous crash.
 *
 * All three reference server emulators solve this the same way: the server owns
 * the player's state and hands the client a fixed starting point, instead of
 * letting the client accumulate whatever happened last time.
 *
 * What the payload is
 * -------------------
 * The 4000-byte save that was in place for the one session that reached
 * NewWorld : Lobby_Default with zero aborts.  It is reproduced byte for byte,
 * deliberately, rather than regenerated -- the fields are an opaque hex chain
 * and there is no way to synthesise a "correct" one.
 */
final class SaveRestore {

    /** Where the game keeps its account slot. */
    static final String SAVE_DIR =
            "/sdcard/Android/data/com.naddicgames.projectm/files/SaveGames";
    static final String SAVE_PATH = SAVE_DIR + "/CMAccountSlot.sav";

    /** Restore automatically on proxy start; switchable from /api/save. */
    static volatile boolean autoRestore = true;

    private SaveRestore() { }

    private static final String B64 =
            "RjA5NTJDMEYzRDEzODM5RDAxOTIwNzk0Q0ZENzdDRThCODc4M0MyMEExMUM5MDNCNDVERTU4ODQxRTAzMUYwOUI4MkFDODg2" +
            "NDUyOTdGMjBEQ0U2Q0ZGOEEzMzhBN0Q1MTg0QzcyNjJDMzU0NEI1MEZCNzVBMUMxNUJDNTI4MTlFM0ZGRUU1MDg1QjI2MDdE" +
            "NUVDNDhBOEQ5MzdENDFENDFFM0FERUFENjQwNzVFOTAzQzYwNjZGQTgzRjNEOUQ4QzUzRjA1MzU4Mjg0NTdFNEVDOTk2RjBB" +
            "RURBQUUwNDYwQzgzNzkxRUNCREZFREQ5NjFBNEFGNEM1MDYyREQyOEYwNTAxOENDN0FGOTdBN0JFRjE1MzdCQ0U3QzcwOTU2" +
            "M0EzN0YxNzIxM0Y5NEE2Qjg0NTk1ODVEQ0NEOUFDODFEQkVENURDQ0Y0OUNGMkQ3MDFBNzc3OEFFREUzNjBCQkQxMkQxMDRD" +
            "QzMwRUY1NjlEMjk3QTlFMUE2RUUyOUYxOURFMzhCMUVBMTMxM0Q0NzI2N0JEMUZGMTA5RDY0OTA4MUY3MUY1M0QzNUU3M0Q1" +
            "QzMwRjQxNEY4RDg3MDBDRkM0OTc4NDdGNzkxMjAyRjhDRjUzN0EwQkFBNjEzMTc1NzBDODA2OTVFNTdFMEQ0RkM4OUYzMTU0" +
            "RkZCOERGQUU5RTRGMDQ3OTJCQ0I1NzI2NjlFN0Y5OUMxMEExQTNDNTdDOTk0NUQwNTU1RTU3NjE2OUVDNjlCNDBBMTc5OTYx" +
            "NTE5NjM2NDIyN0I2QjAwOTg2N0Q0N0ZCN0I2Njc5MTA5NDhGQjFCQjg5RjM4MEQ2RjcwMzI3MjgwNTY4RTUwMjdDNzcxRThC" +
            "QTEwNjMyNDVFNkI4N0NDQzgxNEU4NTNEQjFDNENCOTA1MjMzMjRDQzFGMzI5NjIwNDcxOTVBN0E2N0RFQjM5ODgxOTNGRTM3" +
            "M0QwNzU3MzQ1OERGRkY5RUMzQjFFMURDRjQxQjNFRkVGMzAzNzY4RjkyMjNCMkI1REIwRDI0RkZGRTI0NTE2MUZCQkM4MUUy" +
            "RjJCMDRBRkVFOTdBODhERDg1QjJFMDVGMjQ4OUJFQUVGQTQ5NEEzNEUxRTRERjIyOTJGRDVBQjY1Q0I0NDZCNTJDRDE4QkRE" +
            "RjU0Mzc0RTk1NjUwMUE2RTk3MzJGRTQ1MjU0QTk0Njk1RDRGMjE0NTk5MkZBQkQ1Qjk4OTE4Q0RGNTU1Mjk2ODdDMTY4MTk1" +
            "NzFDODFBMTZDQ0Y4OUZFRDhDOTMxMTg2NzlGMkFBQjEyMURCMkEzMDFERjg0OUZGQzAwMEFFREI4Q0JGRDkyM0RBMEVEQjA2" +
            "Nzc2QzZBMzREOEZBN0NFOUFEMUIzNjM4MkQyOTYxODY0Qjg5QkM0RkE0MjQ0MjNCQ0NBMTZGMkQ2NjM2N0EyNEMyM0ZGOTgx" +
            "MkEzODM4RUI4N0FFQjQ4QTc4M0I1NzJDRTEyOTMyRjhGNTA4NERBQTA2Q0RCNTgzMTE0RjVCRDc0NTJENDhGOUE0MDI0MUQz" +
            "NTM0QjRBOEJEOUM5QzRERjA2NTVCQzZFOTRBOURDMjdBRDYwNTk2MENBMDUzOERGMTZBNzUzNkNBRTg1QjBBNDY4MkQyNUE1" +
            "Q0E1ODlBOUFFNTFBQTM2NjFDQzE4RDQ0RDgyNjRGM0I5Q0IwMTA1MjREMjY4QUUyNDJFNzQyMzZCNzU4NEY1NEE5MjMyMzND" +
            "ODhBQ0ZEQTJENUMyQzhDOThGQjVCMkQ4MzNFN0I3OTZEMDY3ODlGNzU3Qzg4N0NERjRDNURGNEY4Q0I3QjFEQ0FGQjJFNTY0" +
            "NzIxMTM3Q0E5Q0NBOTBGRUMyNkQ2QzlDNDBDQzQ2QzM0MDVCMTExOUZGMzE1QzVGMjRDQzFGQTdDQjcxQTlBRTQ0ODUwQkE5" +
            "MzAwMDQ1N0M3NDhCQjY4NEFDMzRCQzIyQzQ4MjZBRTJBMkY4MDUxNDA4MDJCQjY5OTY1NTc4RjI5MkUyMDA2NDkzRDQzNTBG" +
            "NUVGOUY5OTE3MURFOEE3MUI5NDZGNTlEQkQ0RUY5RDI3MUQyMjY0M0JGM0ZBQ0Q0QkM2NUJCQzY2OTI3OEQwOTFFNzJEOTM4" +
            "NTE3REVDMTA1REQ3RkI2MzJEOEIxMDlFMzNCNTZBMDg3RkFCOUNBOUNCQ0E5ODc1OUFFMkI1OTdBMUM3QUI5QTE5RDNERUUw" +
            "MTIyQkJCOUZDMTE5NTBCRDUxMDI2OUM0RDY2RUVFNDZENDg5MEJBREVDODIwNzI4OTFBQjgwQTNEN0NERjA5OUNDMEY5MURD" +
            "QTRFNTM2Q0VENURBMjVDRjdCNzA0NTRFQTFFMjk1OEZDNDdCMDA2M0Q4NEJBMThCNjZBNkVBNjIwOTE1MDRFRDM2MzcwRjUw" +
            "QUQxMjgwNEMzMzIwMjVDRDQ4MTA3OTI5ODNDNDg5RjE0NkJCQTU3RjQ4N0QzNUEyQ0ZFNjQ3RTVBMzY1MzhBMTczMjA5RUFD" +
            "NjYwQ0Q2M0Y0MUJDM0ZBMTg3NUU5RDFBRDY1RjZGNTE4MkYxMkZDMjZBRjBFREExMTQ2NUY1MjJFQ0UxMkRFNDMxOUJBQTI5" +
            "MzNDMjUwQ0Y1ODFEM0Y0MjRCMjI0RUU0ODg1REZDQjYwRkU5RjNGNDMzNDUzOEVERjcxRDMwRDMzODVGRkU0NkFGRDczNTEx" +
            "REU0RkU4OUFDNjdDMTdEMUFDQTUzNDQ4NjEyQjE5MUQ3M0FDNDA5QzY5QTk0QjVFNUVCMUExNTE0NTRDMEJBMkIyRTJCQzAw" +
            "MzE5QTI4QkUwMjk2M0E5RkI1RjEyREI4MjcyN0NDMjE0MDUzRjZDNkZGRTNDNUI0MDg0MkE4M0NBOUMxMzM5RDhERjMzRkNE" +
            "MTUwNTZCNkYyNzE5QUQ0N0NFQjQ0ODQ5NEZEQUREQzkzOEU4NEI5MDlFNzcyQTRFQ0RGODA2NzFFRDAwQTg1MDJGQ0VEQTE4" +
            "QzU3NDA3MTNDREY4MDY3MUVEMDBBODUwMkZDRURBMThDNTc0MDcxM0NERjgwNjcxRUQwMEE4NTAyRkNFREExOEM1NzQwNzEz" +
            "Q0RGODA2NzFFRDAwQTg1MDJGQ0VEQTE4QzU3NDA3MTNDREY4MDY3MUVEMDBBODUwMkZDRURBMThDNTc0MDcxM0U5MTRBRjM2" +
            "MEQ3MDg1RTkyRTQ0NjM2RkMxQjRDRDJFQzNCMDVCQkEwOUVDNTRGQTQ0OTlDMzBGQzI3RkEwQjY5RjE2N0VDMTRDQ0Q1MEI0" +
            "NEM2RTEzMUFFMkQ3MUI0QjIxOEMzOTJFODQ1RTBFOUQzMUIzQTk3MkI5ODhEREMzMkFDOTVGNjlFRTQ5M0UzQTcxRDEwRDM4" +
            "QzZDMkFGOTI1NThCMUIxRDNDRkRGMEUxRjFFMkM5MENBNDAxOEFDM0QwNkJBMjU2RDBBN0E4MTkwQzQwQ0FGREU0OUVENThD" +
            "QjhENzdGQzM3NUE5NEQyRjBERTAyQ0VGQ0ExQzY2RUUzQzAzQzk3QTkwRkQ0MzFBRDFCMjA3NDlENDQ5QTlCNUNCM0RGNzg1" +
            "MTM0MkQxMzhCRDA5NjdFRTg5QkMyNUQ5MTFFQTIyOTNCOTEyRTc1RDA2ODNEQzgwMEZFOEIwNzM5QjY5QUFGRTVBQzlGQTFE" +
            "NzVERDI0MDhEMkQwMDA0ODYyN0ZGQ0NFOEY3MTE4NUVGMkY4NEI2QUQ0MDVGMTg4RkY0NjU1NThCRUU4NTA5MTM2QUVEQjAz" +
            "QUNCMTE1MUE3QTRFMDkxNzhCRjZFQzZCMTVGM0U0MTVDNzFFMEE3NTEyRjAxMDI0NkZDQkYyNEM4RjQ3MEU5RDczM0QxMERD" +
            "NDY3RUEwQjUzN0ZFQjkzMjFGN0VBQTFGRjM4QzM1Qjc4OUE3REZCRjgyQTdFMjY2QTA2QTQ2RDQ1NDUyNTMyN0Y4ODMyRjIx" +
            "RDg1RTMwQzdGNzA3MjgyNzVDMUVDMjc1QTY4QzAyQ0JDRjk1QUZERjMyMjVDNjBDODlCRjFGNTdFQTM3MzNDRkVDQzcyQzUy" +
            "M0UzODEyRUQxODVBMENEODg1MzZDNDdEOUFBNTk5MDc2MDM2NDQyRjRENEQ2NTI4NTY5NDQyQjgyOUNCNTg2NjBEODY2MDBC" +
            "NTQ1OUZDRjQ5NjBCMzVCNTMwNzM2RjAyMURDODBDMEQ1MDc2NzMyOERBNDY2ODYzQkMxRDNBNEYyNjg1QzExMkZBOTIzM0U5" +
            "NEUyMzM0RUQwOUQ2RDQ5NDI1RjYyM0YwNkRENjUzMUY5QkQ0QkIzOTAyMUNCOTJBNjE2Mzk2QTg3QjhDNDcyQkY5M0QyQzFF" +
            "RkRGRDBDNzA3RDUwOEZCRDc1OThBMjE4QjdCRjJEOTFGMTEyMkFEN0IzQTcyRDdFN0E0NEJBRjg1OUYwMzU4OTBBQjRBMkUw" +
            "RTgzQzc5MUU2MjY4NDZCMTlFMDQ5N0Q3MDMyQjdEQjdEMjgzRDUzMTQwQ0MxRUNBQjMxQzZEN0E1NDAyMENFMTdGMTNEMUEy" +
            "QjEwNTY5QTk3NkE4RjdBRjI1REFDMDMyNjVDNTNDMUU3OUI1OEJEMUFDMkMzQ0E0NTVBODdERERDQ0MwRTQ5REExOTQwRkRE" +
            "RTk5QjE3NzkzOUE5OTNBRjg3QjU3RTQ3M0RFRUYxQTI0MjI0RDc0OTUyOTNBRDZBMzZBMzhCQ0QyRTdDOEZEMzNDMDNDOTdB" +
            "OTBGRDQzMUFEMUIyMDc0OUQ0NDlBOUI1OTU0RjkxNzcxQUExMTQ2NDZCNkFCMTY5NEY4OUIyNzlBQzEyMzEzOTlBRkUyQjY0" +
            "MThDNzY3REY2N0M3QkMxMjVCMUI5QjVCQTRFOTVEMTU3MUU4QzQwQjI4QjZCQzJGRDBEM0VFREY5QzlGMzg4MDM0MUM3MDUx" +
            "QTI3QzVEQkVFMUNEQkFBMjVEMkMwMzU3NTAzRThBQ0NDRjJFQUM1MkE0MkE4ODZGNUQxRDUyQ0ZBQUM4QTNDMzU2OUQzMkJB" +
            "QkJDRjFFQTcyOUU3NzM5N0ZFNzA2ODZBMjhCMTBENkU4M0U1MDVDNjNDREE2RkEyMEVCQjlGMThBMzkwNTIzMzhGOTIzN0Qy" +
            "QTkwOUU3MDcxRkNDRDE0RjQ4NTdCOUIxQjk4M0Y5QUQ3OUNEMkUwMTI2MUM5NEU3Q0IwQjk3QzM1MjZCODIwNDQ3N0E1QzlF" +
            "Qzg0Q0U5RjE1M0QyRDE2QTJFMEE1MEM2MzBEN0Q1Rjk0QkVGRjdDNUE1RjcyOTMxODAyQ0Y3NTI1QzBDOTc3ODRFMkQ3MEFF" +
            "OENDODRCQTMxMjM5NUVBNzgwODI1Q0NCM0EzNEIzQzZFMkI3RjI4OA==";

    /** Write the fixed save into place, keeping one backup of what was there. */
    static synchronized String restore() {
        try {
            // The literal above is concatenated with no separators, so it is
            // already a clean base64 string and needs no whitespace stripping.
            byte[] data = Base64.getDecoder().decode(B64);
            File dir = new File(SAVE_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "cannot create " + SAVE_DIR;
            }
            File cur = new File(SAVE_PATH);
            if (cur.isFile() && cur.length() > 0) {
                // Keep a copy of whatever was there, so a restore is never
                // destructive.  Plain streams rather than java.nio.file.Files,
                // which is unavailable below API 26.
                try {
                    File bak = new File(SAVE_PATH + ".pre-restore");
                    java.io.InputStream in = new java.io.FileInputStream(cur);
                    try {
                        FileOutputStream bo = new FileOutputStream(bak);
                        try {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                            bo.flush();
                        } finally { bo.close(); }
                    } finally { in.close(); }
                } catch (Throwable ignored) { }
            }
            FileOutputStream out = new FileOutputStream(cur);
            try {
                out.write(data);
                out.flush();
            } finally {
                out.close();
            }
            return "ok " + data.length + "B";
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }
}
