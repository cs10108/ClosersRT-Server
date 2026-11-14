package com.closersrt.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The offline CM game server, in Java.
 *
 * Framing was recovered by disassembling libUE4.so:
 *
 *   UCMNetSender_GameBase::CS_VersionCheck (@0x07cd7a94)
 *     0x07cd7b14  mov w9, #0x20          ; size = 32
 *     0x07cd7b20  str w9, [x8, #4]       ; opcode   @+0x04
 *     0x07cd7b30  str x8, [x9, #8]       ; session  @+0x08  (u64)
 *     0x07cd7b38  mov w9, #0x1000
 *     0x07cd7b3c  str w9, [x8, #20]      ; flags    @+0x14
 *
 *   UCMNetReceiver_GameBase::SC_VersionCheckRes (@0x07c21c8c)
 *     0x07c21cbc  ldr w8, [x8, #16]      ; count    @+0x10
 *     0x07c21cc0  cmp w8, #1
 *
 * so a frame is:
 *
 *     +0x00 u32 size (= 24 + payload)
 *     +0x04 u32 opcode
 *     +0x08 u64 session
 *     +0x10 u32 count
 *     +0x14 u32 flags = 0x1000
 *     +0x18 payload
 *
 * The server speaks first (SC_LOGINOPENRES), exactly as the retail server did.
 */
public final class GameServer {

    public static final int PORT = 8500;

    /**
     * Pause before the first server packet, in milliseconds.
     *
     * Overridable from the web console so the window in which the client
     * accepts SC_LOGINOPENRES can be swept without rebuilding the APK each
     * time.  See the comment at the send site for why the timing matters.
     */
    public static volatile int openingDelayMs = 30;

    /**
     * Extra kilobytes to send right after the opening packet, for diagnosing
     * whether the tun relay actually delivers bytes to the client's socket.
     * 0 disables it.  See the send site for details.
     */
    public static volatile int blobKb = 0;

    /**
     * Extra identical 24-byte opening frames to send, payload-free.
     *
     * Isolates whether the client needs more BYTES or merely more FRAMES before
     * it will read: a lone 24-byte frame is never read, but the same frame
     * followed by a 256 KB payload is read on the next frame.
     */
    public static volatile int burst = 0;

    /**
     * Send one frame whose header holds a distinct value in every 4-byte slot,
     * then stop.  The client's own `Net SC : ...` log then reveals which wire
     * offset each parsed field came from.  See the send site.
     */
    public static volatile boolean fingerprint = false;

    public static final int SC_LOGINOPENRES = 13;
    public static final int SC_KEEPALIVERES = 31;
    public static final int SC_VERSIONCHECKRES = 33;
    public static final int SC_AUTHRES = 41;
    public static final int SC_REQUESTSTARTRES = 45;
    public static final int SC_ENTERGAMESERVERRES = 47;
    public static final int SC_GETNICKNAMERES = 201;
    public static final int SC_ACCOUNTINFORES = 209;
    /**
     * SC_ACCOUNTMISCINFORES — carries FGB_AccountMiscInfo, which is where the
     * tutorial progress lives.  Without it the lobby stays locked in the
     * new-player flow, which is exactly what the player hit: the lobby opens,
     * but every control is disabled until the tutorial stage is played.
     *
     * The progress is NOT in the client's save.  Every field of UCMSaveAccount
     * is a UI or audio preference (volumes, MSAA, sort order,
     * FirstShowAgreeDialog, ...) and none of them is a progress field, so this
     * packet is the only way the client can learn the tutorial is finished.
     *
     * Opcode 320, read from the client's own eCmd name table at 0x4741f78.
     * That table holds one 24-byte record per entry and its entries sit at
     * their wire opcodes, which is established by eight consecutive anchors
     * that we have all seen arrive on the wire:
     *
     *   0x4741fa8  322  SC_STAGEINFORES
     *   0x4741fc0  323  SC_STAGEDECKINFO2RES
     *   0x4741fd8  324  SC_CHAPTERINFORES
     *   0x4741ff0  325  SC_ITEMINFORES
     *   0x4742008  326  SC_CHARACTERINFO2RES
     *   0x4742020  327  SC_FUNDINFORES
     *   0x4742038  328  SC_FACILITYBUILDINGINFORES
     *
     * Counting back two entries from SC_STAGEINFORES gives
     * SC_ACCOUNTMISCINFORES at 320.
     *
     * Two earlier guesses were wrong and produced a blank name in the client
     * log -- `[Net SC : (223), OK(1)]` and `[Net SC : (311), OK(1)]` -- which is
     * what an opcode the client cannot name looks like, and it then died in
     * CS_ACCOUNTSECTION02.
     */
    public static final int SC_ACCOUNTMISCINFORES = 320;

    /** eCmd table index 532 = SC_CHEATNARRATIVECOUNTRESETRES.  Its handler
     *  copies a 9-byte FGB_NarrativeInfo tail into the account manager. */
    public static final int SC_CHEATNARRATIVECOUNTRESETRES = 532;
    /** eCmd 256: the side-story battle result (44-byte StageInfo + 9-byte
     *  FGB_NarrativeInfo).  Main story uses 255 instead. */
    public static final int SC_NARRATIVERESULTRES = 256;
    /** eCmd 255: the main-story battle result (44-byte StageInfo only). */
    public static final int SC_MAINSTORYRESULTRES = 255;

    /**
     * Whether the account reports the tutorial as ALREADY FINISHED.
     *
     * true  (default) - accountMiscPayload() writes MainScene = 7, so the
     *        client unlocks the lobby immediately and never shows the
     *        tutorial prompts.
     * false - the field keeps its 0x04 fill, MainScene reads as 4, and the
     *        tutorial plays from the beginning like a brand new account.
     *
     * Runtime: POST /api/tutorial?skip=0|1
     */
    public static volatile boolean skipTutorial = true;

    /** Send the misc record (tutorial progress); /api/misc?on=0 disables it. */
    public static volatile boolean sendAccountMisc = true;

    /** Fill byte for FGB_AccountMiscInfo (default 0x04 as the client pre-fills). */
    public static volatile int accountMiscFill = 4;

    /**
     * Send SC_CHEATNARRATIVECOUNTRESETRES(532) with a 9-byte FGB_NarrativeInfo.
     * Default bytes are  FF 03 00 00 00 00 00 00 00  -> "remaining -1 / max 3",
     * which is the state the UI showed before the stage records were added.
     */
    public static volatile boolean sendNarrativeReset = true;
    /** Legacy hex probe kept for /api/narreset compatibility; the live 532
     *  payload is now built by narrativeResetPayload() from the daily knobs. */
    public static volatile String narrativeResetHex = "030300000000000000";

    /**
     * Bisection switch for the lobby-load crash.
     *
     * The client dies somewhere between the account section burst and the
     * Lobby_Default level load, at a different place every run.  Each of these
     * content packets has its own reader with hand-verified sizes, so the
     * fastest way to tell "our packet is malformed" from "the client corrupts
     * its own heap" is to stop sending them entirely:
     *
     * It is now a 5-bit mask over the content burst at the end of
     * ACCOUNTSECTION01:
     *
     *   bit 0  SC_STAGEINFORES(322)      bit 1  SC_CHAPTERINFORES(324)
     *   bit 2  SC_STAGEDECKINFO2RES(323) bit 3  SC_ITEMINFORES(325)
     *   bit 4  SC_FUNDINFORES(327)
     *
     * DEFAULT 0x1D = everything except SC_CHAPTERINFORES.  That is the
     * measured-stable set: with 0x1D the lobby loaded 4/4 times, with 0x1F
     * (including 324) only 2/4, and with ONLY 324 just 1/4.
     * Runtime: POST /api/content?on=<hex>
     *
     * If the crash persists with 0, the proxy payloads are not the cause.
     * Runtime: POST /api/content?on=0
     */
    public static volatile int contentPackets = 0x1D;   // all but 324

    public static final int SC_ACCOUNTSECTION01RES = 211;
    public static final int SC_STAGEINFORES = 322;
    public static final int SC_STAGEDECKINFO2RES = 323;
    public static final int SC_CHAPTERINFORES = 324;
    public static final int SC_ITEMINFORES = 325;
    public static final int SC_CHARACTERINFO2RES = 326;
    public static final int SC_FUNDINFORES = 327;

    /**
     * SC_ACCOUNTINFOFINISHRES.
     *
     * The last packet of the account-loading handshake.  The retail server
     * sends it unprompted right after SC_ACCOUNTSECTION06RES(221), and the
     * client will not leave the loading screen without it.
     */
    public static final int SC_ACCOUNTINFOFINISHRES = 222;

    /*
     * Inventory packets the retail server pushes inside each account section.
     *
     * The 2023 retail log shows these arriving between a CS_ACCOUNTSECTION0N and
     * its RES, so the client's section handler expects the managers behind them
     * to have been fed.  Replying with only the RES left the client to
     * dereference something those packets would have set up, and it died with
     * SIGSEGV right after CS_ACCOUNTSECTION06.
     *
     * All of them are list-shaped, so an empty payload means "no records" --
     * the safe shape that fills the manager with an empty list.
     */
    public static final int SC_SUMMONINFORES = 337;
    /**
     * SC_REWARDDISPLAYRES(20) -- the packet that carries a reward list, and the
     * only thing that can arm the gacha result page.
     *
     * The opcode is 20, and the client says so itself in its own net log:
     *
     *   [Net SC : SC_REWARDDISPLAYRES(20), OK(1)]
     *   [Net SC : (261), OK(1)]            <- unnamed: no handler at all
     *
     * An earlier attempt used 261, derived from the 24-byte reader table
     * (325 -> 0x055bfa48, 337 -> 0x055bfb68, so 261 -> 0x055bf448 ->
     * SC_RewardDisplayRes @0x07c21280).  That reader exists and 261 does reach
     * it, but the client's *dispatch* for the summon flow is keyed on 20, which
     * is also what BlockTouch waits for (see CS 652).  Sending 261 therefore
     * answered nothing the UI was waiting on, which is why the pull completed
     * with no result animation.
     */
    public static final int SC_REWARDDISPLAYRES = 20;
    /**
     * SC_CHARACTERSUMMONRES(653) -- the reply to a gacha pull (CS 652).
     *
     * Reader: UCMNetReceiver_GameBase::SC_CharacterSummonRes @0x07c30648.
     * With result==1 (header +0x10) it reads:
     *   u16 len + len bytes  -> a 32-byte FGB_SummonInfo  (0x7c30770 w3=0x20)
     *   u32 count            -> that many 14-byte FGB_FundInfo rows
     * so the OK-with-no-rows shape is "u16 0" + "u32 0".  An empty payload
     * makes it read the length out of whatever follows the frame, which is
     * why the plain default handler stalled the client here.
     */
    public static final int SC_CHARACTERSUMMONRES = 653;
    public static final int SC_SHOPPURCHASINGRES = 484;
    public static final int SC_FACILITYBUILDINGINFORES = 328;

    // ---- facility wire ids -------------------------------------------------
    // The eCmd name table @0x4741fa8 is indexed by an INTERNAL enum, not by the
    // number that actually travels in the packet header.  Each row's message
    // descriptor (0xc9ec328 + (enum-326)*16) holds  wire_id + 1  at +0x08:
    //
    //   enum 328 SC_FacilityBuildingInfoRes   wire 328
    //   enum 392 SC_FacilityBuildingUpdateRes wire 701
    //   enum 393 CS_FacilityBuildingBuild     wire 702   <- the request
    //   enum 394 SC_FacilityBuildingBuildRes  wire 703
    //   enum 441 SC_FacilityFavorRefreshRes   wire 772   (enum+309)
    //   enum 443 SC_FacilityFavorSetSlotRes   wire 774
    //   enum 445 SC_FacilityFavorCommuRes     wire 776
    //   enum 447 SC_FacilityFavorGiftRes      wire 778
    //   enum 449 SC_FacilityFavorDateRes      wire 780
    //   enum 451 SC_FacilityFavorRewardRes    wire 789
    //   enum 454 SC_FacilityOrganizeSetSlotRes wire 800
    //
    // Sanity anchors that prove the rule: the client's own build sender writes
    // 702 (= 703-1), and 213/326/328/329/330/334/335/337 all map to themselves.
    // The whole dorm block used to be answered with its ENUM id (441/443/445/
    // 447/449/451/454), i.e. the client got some unrelated command back and its
    // own responses were never recognised -- that is why every dorm button
    // looked dead.
    public static final int SC_FACILITYBUILDINGUPDATERES = 701;
    public static final int SC_FACILITYBUILDINGBUILDRES = 703;
    public static final int SC_FACILITYFAVORREFRESHRES = 772;
    public static final int SC_FACILITYFAVORSETSLOTRES = 774;
    public static final int SC_FACILITYFAVORCOMMURES = 776;
    public static final int SC_FACILITYFAVORGIFTRES = 778;
    public static final int SC_FACILITYFAVORDATERES = 780;
    public static final int SC_FACILITYFAVORREWARDRES = 789;
    public static final int SC_FACILITYORGANIZESETSLOTRES = 800;
    public static final int SC_FACILITYCRAFTINFORES = 329;
    public static final int SC_FACILITYMISSIONINFORES = 330;
    public static final int SC_FACILITYFAVORINFORES = 334;
    public static final int SC_FACILITYORGANIZEINFORES = 335;
    public static final int SC_COLLECTTEAMINFORES = 338;
    public static final int SC_ACHIEVEINFORES = 340;
    public static final int SC_GLOBALEFFECTINFORES = 341;
    public static final int SC_ACCOUNTPROFILEINFORES = 343;
    public static final int SC_ACCOUNTPROFILEFRAMEINFORES = 344;
    public static final int SC_ACCOUNTATTENDINFORES = 353;
    public static final int SC_ACCOUNTEVENTINFORES = 354;
    public static final int SC_ACCOUNTEVENTREWARDINFORES = 355;

    /**
     * Gap between SC_ACCOUNTSECTION06RES(221) and SC_ACCOUNTINFOFINISHRES(222).
     *
     * Retail has 157 ms between them.  Sending 222 too soon after 221 makes the
     * client SIGSEGV while it is still processing the section result -- the log
     * stops right after "Tick(306) [Net Read SC 3]" and 222 is never seen, even
     * though the server did write it.  Defaulting this to 0 was the reason good
     * handshakes still died at the end; 800 ms is measured to be enough.
     */
    /**
     * Gap between SC_ACCOUNTSECTION06RES(221) and SC_ACCOUNTINFOFINISHRES(222).
     *
     * Retail uses 157 ms.  The client starts its black OpenLevel transition on
     * 221, so 222 has to land in the right part of that window; back-to-back
     * delivery is what we have been doing and the transition then faults inside
     * libhoudini.  200 ms gives a little margin over retail for the slower
     * emulator.  Set it to 0 with /api/gap?ms=0 to send both at once and compare.
     */
    public static volatile int SECTION_GAP_MS = 200;

    /**
     * Calibration marker for the server clock in SC_ACCOUNTINFORES.
     *
     * The client turns the stamp at struct+0xA0 into an FDateTime and aborts
     * when the result is out of range ("Invalid Date values. Y:14296").
     *
     * The unit is now pinned down.  GetNextResetTime_ReferenceTimeStamp
     * @0x079f2318 does, in order:
     *
     *   0x79f2338  mov  w23, #0x9680 / movk #0x98 lsl16  ; 10,000,000 = ticks/sec
     *   0x79f23dc  madd x8, x20, x23, x8   ; d.Ticks = stamp * 1e7 + ticks(1970)
     *
     * so the field is SECONDS.  A Windows FILETIME (1.34e17) overflows on that
     * multiply, which is where the absurd years came from.
     *
     * 0 means "use the real clock" (Unix seconds).  -1 sends a literal zero.
     */
    public static volatile long STAMP_PROBE = 0;

    /**
     * How the clock is delivered to the client.
     *   0 = legacy "cancel": +0x00/+0x08 keep their 0x04 fill and struct+0xA0
     *       carries now - 0x0404040404040404.  Only correct when the client
     *       actually performs the offset add; when it does not, the raw value is
     *       a huge negative and the date explodes ("Invalid Date values.
     *       Y:-12930") -- that is the intermittent lobby crash, both on the way
     *       in and when coming back from a sub-UI.
     *   1 = "direct": +0x00 = serverTime, +0x08 = 0, +0xA0 = now.  Dies in
     *       OpenLevel, so +0x00 must not be written.
     *   2 = zero the UTC offset only: +0x08 = 0 and +0xA0 = now.  Fixes the
     *       lobby crash (entering and coming back).
     *   3 (DEFAULT) = zero BOTH serverTime (+0x00) and utcOffset (+0x08), and
     *       +0xA0 = now.  Also fixes SummonMainPage: GetLastResetTime_UTC does
     *       serverTime - utcOffset, and a 0x04-filled serverTime (2.89e17)
     *       overflowed into "Invalid Date values. Y:-12930".  Zero minus zero
     *       is 1970-01-01 -- valid, so the page opens instead of crashing.
     * Runtime: POST /api/clock?mode=N
     */
    public static volatile int CLOCK_MODE = 3;

    /**
     * Whether this account already has a stored name.
     *
     * SC_GETNICKNAMERES(201) reports this as mIsAccount.  It starts false so a
     * brand-new account is asked for a name, then becomes true once the client
     * has sent one.  Reporting 1 before a name exists sends the client down a
     * path that dereferences account-manager entries created only after the name
     * is stored, and it faults right after SC_GETNICKNAMERES.
     */
    public static volatile boolean nameIsSet = false;

    /**
     * Deferred SC_ACCOUNTINFOFINISHRES(222) bookkeeping.
     *
     * Set when SC_ACCOUNTSECTION06RES(221) is written; the receive loop emits 222
     * once the deadline passes.  Kept on the owning thread so the socket is only
     * ever written from one place.
     */
    static volatile long pending222At = 0;
    static volatile OutputStream pending222Out = null;
    static volatile long pending222Session = 0;

    // The client's wire header is THIRTY-TWO bytes, not twenty-four.
    //
    // Recovered from UNetAgent::Tick and its header helper (@0x07cf86b0):
    //
    //   cmp  x8, #0x1f            ; needs MORE than 31 bytes before parsing
    //   mov  w0, #0x20            ; allocates exactly 32
    //   07cf8888  cmp  x21, #0x20  ; copies exactly 32 bytes into the struct
    //   07cf88a8  mov  w9, #0x20  ; and records the header length as 32
    //
    // That also explains the odd size gate seen at 07cf6794 (`cmp w10, #0x20`)
    // and why a lone 24-byte frame was NEVER read while two of them were: the
    // client wants 32 bytes in the buffer before it will touch a frame.  With
    // 24-byte headers it read our first frame plus the next frame's first 8
    // bytes, which is why it logged `Net SC : (-19082118), (-2138207015)`
    // instead of `SC_LOGINOPENRES(13), OK(1)`.
    private static final int HEADER = 32;

    private static final int FLAGS = 0x1000;

    private static ServerSocket server;
    private static volatile boolean running;
    private static volatile int connections;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private GameServer() { }

    public static String hostPort() { return "127.0.0.1:" + PORT; }
    public static int connectionCount() { return connections; }

    public static synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            running = true;
            Thread t = new Thread(new Acceptor(), "game-accept");
            t.setDaemon(true);
            t.start();
            Status.log("game server listening on " + hostPort());
        } catch (Exception e) {
            Status.log("game server failed: " + e);
            running = false;
        }
    }

    /** Named, not anonymous: d8 8.2 crashes on Foo$1.class.
     *  Package-private (not private) on purpose: a private nested class makes
     *  javac emit a synthetic access class Foo$1, and this build has to drop
     *  those files, so anything referring to one dies at runtime with
     *  NoClassDefFoundError. */
    static final class Acceptor implements Runnable {
        @Override public void run() { acceptLoop(); }
    }

    /** One client session. */
    static final class SessionTask implements Runnable {
        private final Socket socket;
        SessionTask(Socket s) { this.socket = s; }
        @Override public void run() { session(socket); }
    }

    public static synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    public static boolean isRunning() { return running; }

    private static void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                connections++;
                POOL.execute(new SessionTask(s));
            } catch (Exception e) {
                if (running) Status.log("game accept: " + e);
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    private static void session(Socket s) {
        long sessionId = System.currentTimeMillis() & 0xFFFFFFFFL;
        Status.log("game: client connected from " + s.getRemoteSocketAddress()
                + " session=0x" + Long.toHexString(sessionId));
        try {
            s.setTcpNoDelay(true);
            // Short read timeout so the loop wakes often enough to emit the
            // deferred 222 close to its deadline; a SocketTimeoutException is
            // normal here and is handled in receiveLoop.
            s.setSoTimeout(50);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            UserManager.User me = UserManager.get().current();

            // The retail server speaks first -- but NOT instantly.
            //
            // The client's first Tick after connecting logs
            //
            //   Tick(169) [Net Connected HasPendingConnectionResult(1),
            //              HasPendingConnection(?), PendingDataSize(?)]
            //
            // and it only reaches `Net Read SC` on a LATER tick.  Against the
            // retail server that first tick always saw an empty socket:
            //
            //   retail : HasPendingConnection(0), PendingDataSize(0)   then Read
            //   us     : HasPendingConnection(1), PendingDataSize(24)  then stuck
            //
            // Over the internet the server's SC_LOGINOPENRES could not arrive
            // before the client's first tick; on loopback it always did, because
            // the client's kernel answers the SYN+ACK immediately -- far sooner
            // than its game tick.  A brief pause reproduces the retail ordering
            // and lets the client settle into its read path.
            // Send the opening packet after a short, tunable pause.
            //
            // The client only reads the server's first packet from inside
            // UNetAgent::Tick, and that read is gated twice (both verified by
            // disassembly):
            //
            //   cmp  w8, #0xa        ; [this+0x70] must be the "Connected" state
            //   blr  [x8,#0x40]      ; FSocketBSD::HasPendingData() -> ioctl(FIONREAD)
            //
            // The packet therefore has to be in the socket when the client is
            // both Connected and ticking, but not so early that it is pending
            // while the connection result is still being processed -- that path
            // ends in Detect_Disconnect1_PendingData + a reconnect popup.
            //
            // Measured so far: 0 ms leaves PendingDataSize(24) at Tick(169) and
            // the state machine never advances; 200 ms lands in
            // Detect_Disconnect1_PendingData 192 ms later.  This value is
            // overridable at runtime through the web console so the window can
            // be swept without rebuilding the APK.
            try { Thread.sleep(GameServer.openingDelayMs); }
            catch (InterruptedException ignored) { }
            // Session 0 for the opening packet: the session is negotiated as part
            // of the handshake, so the first server->client frame must not claim
            // one.
            send(out, SC_LOGINOPENRES, 0L, new byte[0]);
            Status.log("game -> SC_LOGINOPENRES(13) session=0 delay="
                    + GameServer.openingDelayMs + "ms");

            // --- DIAGNOSTIC: fingerprint frame -------------------------
            //
            // The client reads the frame, parses it, then logs
            //     Tick(242) [Net SC : %s(%d), %s(%d)]
            // with the two numbers taken from the parsed struct at +0x04 and
            // +0x10 (verified at 0x07cf6b90/0x07cf6b98 and 0x07cf6ddc/0x07cf6de4).
            //
            // Our 32-byte header has 13 at +0x04 and 1 at +0x10, yet the client
            // logs (-19082118) and (-2138207015) -- values that appear nowhere
            // in the bytes we send.  So there is a transformation between the
            // wire and that struct which our layout is not matching.
            //
            // This writes a distinct, recognisable value into every 4-byte slot
            // of the header.  Whatever the client logs then names the exact wire
            // offset each parsed field comes from, with no further guessing.
            if (GameServer.fingerprint) {
                byte[] fp = new byte[HEADER];
                java.nio.ByteBuffer fb = java.nio.ByteBuffer.wrap(fp)
                        .order(ByteOrder.LITTLE_ENDIAN);
                // Offset 0 must stay a VALID size or the client rejects the
                // frame before parsing anything -- an earlier revision put a
                // marker there too, the size became 0x11000000 (285 MB), and
                // every run produced the same meaningless parse result.
                fb.putInt(0, HEADER);
                for (int i = 1; i < HEADER / 4; i++) {
                    fb.putInt(i * 4, 0x11000000 + i);   // 0x11000001 .. 0x11000007
                }
                out.write(fp);
                out.flush();
                Status.log("game -> fingerprint frame: size=" + HEADER
                        + " then 0x11000001..0x11000007");
                return;
            }

            // --- DIAGNOSTIC: one big blob, to prove the delivery path ---
            //
            // The client's kernel queues whatever the relay sends -- with a
            // 256 KB payload the socket's rx_queue in /proc/net/tcp jumps to
            // 0x3BFF8 and STAYS there.  With the 24-byte opening packet alone it
            // reads 0 on every poll and the client never logs `Net Read SC`.
            //
            // Sending the blob makes the client read immediately:
            //
            //   Tick(169) [Net Connected ...]
            //   Tick(225) [Net Read SC 1]        <- 18 ms later, next frame
            //
            // so the read path itself is healthy and something about a lone
            // 24-byte frame is what it does not like.  `burst` isolates that:
            // it sends N more identical 24-byte opening frames and NO payload,
            // which distinguishes "needs more bytes" from "needs more frames".
            // Enabled with /api/blob?kb=N&burst=M (0 disables each).
            if (GameServer.blobKb > 0) {
                int n = GameServer.blobKb * 1024;
                send(out, SC_LOGINOPENRES, 0L, new byte[n]);
                Status.log("game -> blob test " + n + " bytes");
            }
            for (int i = 0; i < GameServer.burst; i++) {
                send(out, SC_LOGINOPENRES, 0L, new byte[0]);
            }
            if (GameServer.burst > 0) {
                Status.log("game -> burst " + GameServer.burst + " extra frames");
            }

            receiveLoop(in, out, me, sessionId);
        } catch (Exception e) {
            Status.log("game session: " + e);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
            connections--;
            Status.log("game: client disconnected");
        }
    }

    /**
     * Read the client's stream, decrypt whole frames and hand them to handle().
     *
     * TCP does not preserve message boundaries.  A 4709-byte frame arrives as
     * 536 + 1072 + ... and the original code treated each read() as a complete
     * frame, so it tried to decrypt and parse a truncated buffer and logged
     * "cannot frame at +0 of 536B: size=4709".  Bytes are accumulated here and
     * a frame is only decrypted once all of it is in hand.
     *
     * Only a frame's first four bytes (its size) are plaintext; the rest is
     * Blowfish-encrypted.  Decrypting the whole read in one pass would also
     * scramble every frame after the first, because each frame carries its own
     * plaintext size prefix.
     */
    private static void receiveLoop(InputStream in, OutputStream out,
                                    UserManager.User me, long sessionId)
            throws Exception {
        byte[] buf = new byte[65536];
        byte[] acc = new byte[1 << 20];
        int accLen = 0;

        while (true) {
            int n;
            try {
                n = in.read(buf);
            } catch (java.net.SocketTimeoutException te) {
                // Expected: the short timeout exists so the pending 222 can be
                // emitted on this same thread.  Nothing to read yet.
                n = 0;
            }
            // Emit the deferred SC_ACCOUNTINFOFINISHRES(222) here, on the thread
            // that owns this socket, once its deadline has passed.
            long due = pending222At;
            if (due != 0 && System.currentTimeMillis() >= due
                    && pending222Out == out) {
                pending222At = 0;
                try {
                    send(out, SC_ACCOUNTINFOFINISHRES, pending222Session,
                            new byte[0]);
                } catch (Exception e) {
                    Status.log("game: deferred 222 failed: " + e);
                }
            }
            if (n <= 0) continue;

            if (accLen + n > acc.length) {
                int need = accLen + n;
                int cap = acc.length;
                while (cap < need) cap <<= 1;
                if (cap > (16 << 20)) {
                    Status.log("game <- reassembly overflow, dropping");
                    break;
                }
                byte[] bigger = new byte[cap];
                System.arraycopy(acc, 0, bigger, 0, accLen);
                acc = bigger;
            }
            System.arraycopy(buf, 0, acc, accLen, n);
            accLen += n;

            int d = 0;
            boolean bad = false;
            while (d + 8 <= accLen) {
                int fsz = rd32(acc, d);
                if (fsz < 8 || fsz > (16 << 20)) {
                    Status.log("game <- bad frame size " + fsz + " at +" + d
                            + ", dropping " + (accLen - d) + "B");
                    accLen = 0;
                    d = 0;
                    bad = true;
                    break;
                }
                if (d + fsz > accLen) break;      // need more bytes
                if (decryptFrames) {
                    CRYPTO.decrypt(acc, d + 4, fsz - 4);
                }
                pushFrame(acc, d, fsz, out, me, sessionId);
                d += fsz;
            }
            if (!bad && d > 0) {
                System.arraycopy(acc, d, acc, 0, accLen - d);
                accLen -= d;
            }
        }
    }

    /** Log and dispatch one whole, already-decrypted frame. */
    private static void pushFrame(byte[] acc, int off, int size,
                                  OutputStream out, UserManager.User me,
                                  long sessionId) throws Exception {
        ByteBuffer hb = ByteBuffer.wrap(acc, off, HEADER)
                .order(ByteOrder.LITTLE_ENDIAN);
        int opcode = hb.getInt(4);
        long sess = hb.getLong(8);
        int count = hb.getInt(16);
        if (rawLog) {
            byte[] one = new byte[size];
            System.arraycopy(acc, off, one, 0, size);
            Status.log("game <- frame(" + size + "B): " + hex(one));
        }
        byte[] payload = new byte[size - HEADER];
        System.arraycopy(acc, off + HEADER, payload, 0, payload.length);
        Status.log("game <- opcode " + opcode + " (" + name(opcode)
                + ") count=" + count + " payload=" + payload.length + "B");
        if (payload.length > 0) {
            Status.log("       hex: " + hex(payload));
            for (String found : strings(payload)) {
                Status.log("       str: \"" + found + "\"");
            }
        }
        handle(out, opcode, sess == 0 ? sessionId : sess, payload, me);
    }

    // Live session capture, so the web console can PUSH a packet without a
    // re-login.  Needed while reverse-engineering: the facility list only
    // arrives during CS_ACCOUNTSECTION02, so testing one field layout used to
    // cost a full logout/login cycle.
    public static volatile java.io.OutputStream liveOut = null;

    /** Set at the end of the login burst; the next client packet runs one
     *  SC_FACILITYBUILDINGUPDATERES(701) pass so every built building is
     *  appended to manager+0x78 (Lv.1) without touching the burst itself. */
    public static volatile boolean pendingFacilityComplete = false;
    public static volatile long liveSession = 0;

    /**
     * Push the full 328 into the live session, no restart needed.
     *
     * Used while bisecting the record: change flags/ids via /api/facility, then
     * hit /api/facility/repush and look at the base screen.
     */
    public static synchronized String repushFacilityFull() {
        java.io.OutputStream o = liveOut;
        long s0 = liveSession;
        if (o == null || s0 == 0) return "no live session";
        try {
            // Completion first: it is the packet that actually moves a building
            // into manager+0x78.  328 alone only (re)creates the entries.
            send(o, SC_FACILITYBUILDINGUPDATERES, s0, facilityCompletePayload());
            byte[] p = facilityBuildingPayload();
            send(o, SC_FACILITYBUILDINGINFORES, s0, p);
            Status.log("facility: repush " + p.length + "B flags=0x"
                    + Integer.toHexString(facilityFlags) + " ids="
                    + java.util.Arrays.toString(facilityBuildingIds));
            return "pushed " + p.length + "B";
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    /** Push the facility building list to the currently connected client. */
    public static synchronized String pushFacilityNow() {
        java.io.OutputStream o = liveOut;
        long s0 = liveSession;
        if (o == null || s0 == 0) return "no live session";
        try {
            send(o, SC_FACILITYBUILDINGINFORES, s0, facilityBuildingPayload());
            return "sent";
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    private static void handle(OutputStream out, int opcode, long session,
                               byte[] payload, UserManager.User me) throws Exception {
        liveOut = out;
        liveSession = session;
        // Deferred character re-push after a level-up (see
        // resendCharacterUpdateLater).  Done here, on the packet thread, so no
        // helper thread and no anonymous Runnable are involved: this build
        // deliberately excludes javac's $1 synthetic classes, and loading one
        // at runtime is a NoClassDefFoundError that kills the proxy.
        if (pendingCharResendLeft > 0 && pendingCharResend > 0) {
            pendingCharResendLeft--;
            try {
                sendCharacterUpdate(out, session, pendingCharResend, charUpdateFlags);
                Status.log("game: deferred 369 re-push char " + pendingCharResend
                        + " flags=0x" + Integer.toHexString(charUpdateFlags)
                        + " (" + pendingCharResendLeft + " left)");
            } catch (Exception e) {
                Status.log("game: deferred 369 re-push failed " + e);
                pendingCharResendLeft = 0;
            }
        }
        if (pendingFacilityComplete) {
            pendingFacilityComplete = false;
            Status.log("facility: post-login complete -> " + pushFacilityComplete());
        }
        // The account the operator created in the web console.  The retail
        // login screen only offers Apple / Google / Guest, so "Guest" is the
        // path that has to resolve to this identity.
        long accountId = me != null ? me.accountId : 1000;
        String nick = me != null ? me.nickname : "player";
        switch (opcode) {
            case 30: {
                send(out, SC_KEEPALIVERES, session, new byte[0]);
                // Late re-send of the inventory.
                //
                // UCMItemInfoManager::Init creates the five category objects,
                // and SetItemInfo SKIPS a row when its category index is out of
                // range for that array.  If the 325 we send during
                // ACCOUNTSECTION01 arrives before Init has run, every row is
                // dropped and the bag stays empty forever.  Push the same
                // payload again on the second keepalive (i.e. ~30 s after the
                // lobby is up) to tell the two cases apart.
                if (lateItemResend && ++keepaliveCount == 2) {
                    for (byte[] frame : itemPayloads()) {
                        send(out, SC_ITEMINFORES, session, frame);
                    }
                    Status.log("game: late SC_ITEMINFORES re-send");
                }
                return;
            }
            case 32:
                send(out, SC_VERSIONCHECKRES, session, le32(1));
                return;
            case 40: {
                // A new client session starts here: rebuild the working copy
                // from the persisted defaults so anything gained during the
                // last run disappears when the game is restarted.
                SessionState.get().reset();
                // SC_AUTHRES(41) -- bare fields, the form used by the runs that
                // reached Lobby_Default with LobbyCamera Possess and zero fatal
                // errors (i2.log 09:31, k1.log 09:44).
                //
                // The deserializer at 0x07c22044 does want a u16-length-prefixed
                // 97-byte record, and this leaves that length at 0 so the client
                // keeps its 0x04 fill.  That is why the lobby shows
                // "ID: 67372036" (0x04040404).  Converting it to the structured
                // form was tried on top of the current build and made things
                // worse -- the client died right after
                // CS_ACCOUNTSECTION02(212) -- so it is reverted here and will be
                // retried on its own once the crash has been isolated.
                byte[] s2 = utf16z(nick);
                byte[] body = new byte[15 + s2.length];
                putLe32(body, 8, (int) accountId);
                body[12] = 1;
                putLe16(body, 13, nick.length());
                System.arraycopy(s2, 0, body, 15, s2.length);
                Status.log("game: guest/auth as '" + nick + "' id=" + accountId
                        + " (SC_AUTHRES " + body.length + "B)");
                send(out, SC_AUTHRES, session, body);
                return;
            }
            case 44:
                send(out, SC_REQUESTSTARTRES, session, new byte[0]);
                return;
            case 46:
                send(out, SC_ENTERGAMESERVERRES, session, new byte[0]);
                return;
            case 200: {
                // SC_GETNICKNAMERES(201) -- the "does this account have a name"
                // answer.  Layout from the client's own reader at 0x07c20250:
                //
                //   0x7c20274  ldrb w8, [x2]          ; mIsAccount, 1 byte at +0
                //   0x7c20278  cbz  w8, #0x7c20368    ; 0 -> finish, nothing more
                //   0x7c202bc  ldrh w8, [x20, #1]!    ; u16 length at +1
                //   0x7c202c8  mov  w24, #3           ; text starts at +3
                //
                // mIsAccount is ALWAYS 0.  It is the "the server already holds
                // your name, use that" flag, and the client only takes the branch
                // it guards when it is non-zero:
                //
                //   0x7c20278  cbz w8, #0x7c20368     ; 0 -> done, return
                //
                // That branch walks into the account manager and dereferences
                // records that SC_AUTHRES is supposed to have populated; with our
                // minimal offline data it faults instead.  Measured, same client,
                // same save:
                //
                //   mIsAccount = 0  (13-byte payload) -> reaches NewWorld :
                //                                        Lobby_Default, alive
                //   mIsAccount = 1  (25-byte payload) -> SIGSEGV / FORTIFY abort
                //                                        right after this frame
                //
                // Reporting 0 does NOT make the game ask for a name every launch:
                // the client decides that from its own save file, and it settled
                // straight into the lobby with no nickname prompt.  An earlier
                // version of this code derived the flag from a nameIsSet field set
                // when CS_ACCOUNTINFO arrived, which made it stick at 1 for the
                // rest of the proxy process and broke every later session.
                //
                // +0x00 u8 mIsAccount, +0x01 u16 length, +0x03 UTF-16 text.
                //
                // The payload is ALL ZEROS apart from mIsAccount, and that is
                // deliberate: TWO different handlers run on this one packet, and
                // they disagree about where the string lives.
                //
                //   UCMNetReceiver_Game::SC_GetNicknameRes_Process  @0x07c20250
                //     0x7c20274  ldrb w8, [x2]        ; mIsAccount at +0
                //     0x7c20278  cbz  w8, #0x7c20368  ; 0 -> return at once
                //     0x7c202bc  ldrh w8, [x20, #1]!  ; length at +1
                //     0x7c202c8  mov  w24, #3         ; text at +3
                //
                //   UCMNetReceiver_GameBase::SC_GetNicknameRes_Process @0x07c2583c
                //     0x7c2589c  cmp  w8, #0x21       ; only runs if frame >= 33B
                //     0x7c258c4  ldrh w21, [x9, x8]   ; length at +4 (!)
                //
                // Sending a name at +3 keeps the first handler happy but feeds the
                // second one whatever bytes sit at +4.  For "player" that is the
                // UTF-16 unit at +4, 0x006c ('l'), i.e. a length of 108, and it
                // then scans and memcpys far past the payload:
                //
                //   Abort message: 'FORTIFY: memcpy: prevented 1536-byte write
                //                   into 83-byte buffer'
                //
                // A 13-byte payload of zeros is the shape that works: the first
                // handler returns on mIsAccount == 0 and the second reads length 0
                // at +4 and skips.  That is exactly the payload the one successful
                // lobby entry used, so it is reproduced here byte for byte rather
                // than "improved".  Nothing is lost: with mIsAccount = 0 the client
                // never consumes this string anyway -- it keeps its own name in the
                // save and does not prompt for one.
                byte[] body = new byte[13];
                body[0] = 0;                         // mIsAccount
                Status.log("game: SC_GETNICKNAMERES mIsAccount=0 ("
                        + body.length + "B, zero-filled)");
                send(out, SC_GETNICKNAMERES, session, body);
                return;
            }
            case 208:
                // Every login asks for the account record first, so this is the
                // reliable place to rebuild the in-memory working copy: whatever
                // was earned during the previous run is dropped here.
                SessionState.get().reset();
                // CS_ACCOUNTINFO -> SC_ACCOUNTINFORES(209), which carries both
                // the account record and the SERVER CLOCK.
                //
                // Deserializer: UCMNetReceiver_GameBase::SC_AuthRes-style
                // u16-length framing, here at 0x07c25c3c for 209:
                //
                //   0x7c25c74  cmp  w8, #1          ; header result must be 1
                //   0x7c25c80  movi v0.16b, #4      ; 296-byte struct, pre-filled 4
                //   0x7c25d04  ldrh w21, [x9, x8]   ; u16 LENGTH at payload + 0
                //   0x7c25d14  mov  w3, #0x128      ;   then 296 bytes
                //   0x7c25d40  bl   #0x795eca4      ; SetAccountInfo(struct + 0x10)
                //   0x7c25d50  bl   #0x795ee48      ; SetAccountMiscInfo(struct + 0xCD)
                //
                // SetAccountInfo @0x0795eca4, x19 = struct + 0x10:
                //   0x795ed48  ldr x9, [x19, #0x90] ; the refresh stamp
                //   0x795edcc  stp x28, x9, [x25, #0x180]
                //
                // and UCMServerTimeManager reads it back via
                // UCMAccountManager::GetRefreshStamp @0x0796107c:
                //   0x79610c4  ldr x0, [x8, #0x188] ; manager+0xD0+0x188
                //
                // ACMLobbyOutGameMode::RequestContentRefresh @0x07bf1054 feeds
                // that into GetNextResetTime_ReferenceTimeStamp, which aborts on
                // a nonsense date:
                //
                //   Fatal error: Invalid Date values. Y:-9609, M:2, D:24, ...
                //
                // So the stamp is at struct + 0x10 + 0x90 = struct + 0xA0, i.e.
                // payload + 0x02 + 0xA0 = payload + 0xA2.  Fill it with a real
                // FILETIME and the lobby finishes.
                long nowUnixSeconds = System.currentTimeMillis() / 1000L;
                // 100 ns ticks since 1970-01-01, which is the base the client's
                // reset-time functions add to FDateTime(1970, 1, 1).
                long unixTicks = (System.currentTimeMillis() - 0L) * 10000L;
                nameIsSet = true;
                // The stamp at struct+0xA0.  STAMP_PROBE exists for
                // calibration only; leave it at 0 for normal runs so the value
                // is the real clock (FILETIME).  See
                // _memory/STAGE-09-HANDOFF.md before changing this again --
                // six different guesses at this field were measured and none
                // moved the "Invalid Date values" failure, so the field is
                // probably not the one the client consumes.
                byte[] nm = utf16z(nick);
                byte[] rec = new byte[0x128];      // 296 bytes
                java.util.Arrays.fill(rec, (byte) accountMiscFill);

                // ---- the server clock -------------------------------------
                //
                // This is the slot the summon page needs.  UUISummonMainPage::
                // Init calls UCMServerTimeManager::GetLastResetTime_UTC, and
                // that reads manager+0x28 / manager+0x30 -- the pair that
                // SetServerTime @0x079f18bc fills:
                //
                //   0x79f18ec  ldr x8, [x1]          ; arg1
                //   0x79f18f4  str x8, [x0, #0x28]   ; serverTime
                //   0x79f18f8  ldr x8, [x2]          ; arg2
                //   0x79f1900  str x8, [x19, #0x30]  ; utcOffset
                //
                //   GetLastResetTime_Local @0x079f2100
                //     0x79f217c  ldr  x8, [x0, #0x38]
                //     0x79f2188  ldr  w19, [x8, #0x28]   ; <- the same slot
                //   GetLastResetTime_UTC @0x079f22f0
                //     0x79f2304  ldr  x8, [x19, #0x30]   ; utcOffset
                //     0x79f230c  sub  x0, x0, x8
                //
                // It aborts through FDateTime::FDateTime when the stamp is
                // nonsense: "Fatal error: Invalid Date values."  That is what
                // took the summon page down.
                //
                // The arg1/arg2 pointers come from SC_AccountInfoRes_Process
                // (x19 = its x2), which receives the DECODED
                // NPGameS2C_AccountInfoRes, not the raw payload -- so the clock
                // belongs at the head of the 296-byte record, which is what
                // payload+0x02 decodes to.
                // ---- the server clock, at the head of the record --------
                //
                // UUISummonMainPage::Init and the lobby's content refresh both
                // go through UCMServerTimeManager, and that aborts on a stamp it
                // cannot turn into a date:
                //
                //   Fatal error: Invalid Date values. Y:23054, M:2, D:17, ...
                //
                // The base is 1970, not 1601.  GetLastResetTime_Local
                // @0x079f2100 spells it out:
                //
                //   0x79f2198  ldp  x10, x11, [x20, #0x28]  ; the stamp
                //   0x79f21a0  mov  w1, #0x7b2              ; 1970  <- Unix epoch
                //   0x79f21a4  sub  x8, x8, x9
                //   0x79f21a8  add  x8, x10, x8
                //   ...        FDateTime(1970, 1, 1, ...) + delta
                //
                // so the value is 100 ns ticks counted from 1970-01-01, the same
                // shape as .NET's UnixEpoch ticks.  A Windows FILETIME (1601
                // base) in this slot is what produced the absurd years.
                //
                // The `ldr w19, [x8, #0x28]` a few lines above is a separate
                // 32-bit read used as a flag, not the stamp itself.
                // Left at the 0x04 fill.  Writing the clock at the head of the
                // record -- in either base, FILETIME or Unix ticks -- makes the
                // client fault during the OpenLevel transition instead of after
                // it, so that slot is not the clock.  Manager-level time comes
                // from SC_ACCOUNTINFORES through SetAccountInfo's
                // [struct+0x10]+0x90 path (written below at struct+0xA0), which
                // is what keeps the lobby's content refresh quiet.

                // The account name is length-prefixed UTF-16.  SetAccountInfo is
                // called with struct + 0x10 and reads it relative to that:
                //   0x795ece4  ldrh w8, [x21, #4]!   ; x21 = struct + 0x10
                //   0x795ecf0  mov  w20, #6          ; text from +6 on that base
                // ---- server clock, at the head of the record -----------
                // SetServerTime is handed (x19, x19+8) where x19 is the record
                // base (Process: 0x7c203b8 mov x19,x2; 0x7c204d4 mov x1,x19;
                // 0x7c204d0 add x2,x19,#8).  SetServerTime then stores
                // [x1] -> manager+0x28 and [x2] -> manager+0x30, which is what
                // GetLastResetTime_Local reads at
                //   0x79f2198  ldp x10, x11, [x20, #0x28]
                // before building FDateTime(1970,1,1) + delta.  SetAccountInfo
                // reads the name from struct+0x14 onward, so the two do not
                // overlap.
                // struct+0x00 / +0x08 are NOT the clock.
                //
                // SetNPGameS2C_AccountInfoRes @0x0795f0fc settles where the
                // stamp really lives:
                //   0x795f13c  ldrh w8, [x21, #0x14]!  ; name length @0x14
                //   0x795f148  mov  w19, #0x16         ; name text  @0x16
                //   0x795f1a0  ldr  x9, [x20, #0xa0]   ; ★ the stamp @0xA0
                // and SetAccountInfo reads the same slot as [struct+0x10]+0x90.
                //
                // Writing a clock at +0x00 corrupted whatever field lives there
                // and the next frame died with SIGSEGV / SI_KERNEL on
                // GameThread, so the head of the record is left at its 0x04 fill.
                putLe16(rec, 0x14, nick.length()); // name length at struct + 0x14
                System.arraycopy(nm, 0, rec, 0x16,
                        Math.min(nm.length, 0xCD - 0x16));

                rec[0x123] = 7;                    // MainScene -> tutorial finished
                // SubSceneFlag rides one byte later in this 296-byte struct
                // (0x58-byte FGB_AccountMiscInfo offset inside
                //  FGB_AccountInfo = 0x123 is +0x56, so +0x57 -> 0x124).
                // All-ones marks every per-feature tutorial finished, matching
                // accountMiscPayload().
                if (skipTutorial) putLe32(rec, 0x124, 0xFFFFFFFF);

                // ---- account progress at struct+0x64 -----------------------
                //
                // UUILobbyStoryModeMenuList::IsGameModeOpenRule @0x082d3368 has
                // a type-1 rule that compares the value a chapter requires
                // against the player's progress:
                //
                //   0x082d342c  ldr  x8, [x20, #0xd0]
                //   0x082d3430  ldr  w9, [x25]        ; required
                //   0x082d3434  ldrh w8, [x8, #0x130] ; player progress (u16)
                //   0x082d3438  cmp  w9, w8
                //   0x082d343c  csinc w22, w22, wzr, ls   ; required <= ours
                //
                // manager+0x130 is filled by SetNPGameS2C_AccountInfoRes
                // @0x0795f0fc:  0x795f194  ldrh w19,[x20,#0x64] /
                //               0x795f218  strh w19,[x25,#0x130]
                //
                // i.e. struct+0x64 (u16) is the account progress.  Left at the
                // 0x04 fill it reads 0x0404 = 1028; ACCOUNT_PROGRESS lets it be
                // raised to see whether that unlocks story entries.
                if (ACCOUNT_PROGRESS >= 0) {
                    putLe16(rec, 0x64, ACCOUNT_PROGRESS);
                }
                if (ACCOUNT_EXP >= 0 && ACCOUNT_EXP_OFFSET >= 0
                        && ACCOUNT_EXP_OFFSET + 8 <= rec.length) {
                    // Written as an 8-byte little-endian value: the reader at
                    // 0x795f198 is "ldur x26, [x20, #102]" (an i64 at +0x66),
                    // so a 4-byte poke leaves the upper half at the 0x04 fill
                    // and the displayed number explodes.
                    putLe32(rec, ACCOUNT_EXP_OFFSET, ACCOUNT_EXP);
                // Keep the two account numbers consistent: whenever the level changes,
                // the exp is the level x 1000 unless ACCOUNT_EXP was pinned negative.
                if (ACCOUNT_EXP >= 0) {
                    putLe32(rec, ACCOUNT_EXP_OFFSET, ACCOUNT_PROGRESS * 1000);
                }
                    putLe32(rec, ACCOUNT_EXP_OFFSET + 4, 0);
                }

                // ---- UTC offset at struct+0x00 / +0x08: LEAVE ALONE -------
                //
                // SetServerTime is handed (x1 = struct+0x00, x2 = struct+0x08):
                //
                //   0x7c204d4  mov  x1, x19
                //   0x7c204d0  add  x2, x19, #8
                //   0x7c204d8  bl   #0x79f18bc
                //
                // and stores [x1] -> manager+0x28, [x2] -> manager+0x30.
                // GetNextResetTime_ReferenceTimeStamp then adds manager+0x30 to
                // the refresh stamp (RequestContentRefresh passes w2 = 1):
                //
                //   0x79f23a4  tbz  w22, #0, +0xc
                //   0x79f23a8  ldr  x8, [x21, #0x30]
                //   0x79f23ac  add  x20, x8, x20
                //
                // So the stamp we send is NOT the value used -- it is
                //     effective = stamp + 0x0404040404040404
                //
                // Writing either +0x00 or +0x08 has now been tried (v007) and it
                // regressed the client from "reaches 222" all the way back to
                // "dies right after CS_ACCOUNTSECTION02(212)" -- the same
                // SIGSEGV / SI_KERNEL that the head-of-record writes caused
                // before.  Both fields are something else entirely, so they stay
                // at their 0x04 fill and the offset is cancelled in the stamp.
                //
                // Cancelling is exact: we pre-subtract the fill value so the sum
                // the client computes is the real Unix time.
                // Two ways to hand the client a usable clock:
                //
                //  "cancel" (legacy, the default): +0x00/+0x08 stay at their
                //  0x04 fill and struct+0xA0 carries now - 0x0404040404040404
                //  so the sum the client computes is the real time.  This is
                //  arithmetically exact but has been observed to fall apart on
                //  some launches -- the client reported "Invalid Date values.
                //  Y:-12930", which inverts to a stamp of ~0, i.e. it did not
                //  see our value at all.
                //
                //  "direct" (CLOCK_MODE=1): write +0x00 = serverTime,
                //  +0x08 = utcOffset 0, and struct+0xA0 = now, with no
                //  cancelling.  Every consumer then reads a sane value no
                //  matter which path it takes.
                long stamp;
                if (CLOCK_MODE == 1) {
                    putLe64(rec, 0x00, nowUnixSeconds);   // serverTime
                    putLe64(rec, 0x08, 0L);               // utcOffset = UTC
                    stamp = STAMP_PROBE == -1L ? 0L
                            : (STAMP_PROBE != 0L ? STAMP_PROBE : nowUnixSeconds);
                } else if (CLOCK_MODE >= 3) {
                    // SummonMainPage crash: UUISummonMainPage::Init calls
                    // GetLastResetTime_UTC, which computes
                    //     serverTime (manager+0x28) - utcOffset (manager+0x30)
                    // and turns that into a date.  With serverTime still at its
                    // 0x04 fill that is 2.89e17, and *1e7 overflows into
                    // "Invalid Date values. Y:-12930" -- the summon page died
                    // the moment it opened.
                    //
                    // Zero both: the subtraction is then 0, which FDateTime
                    // renders as 1970-01-01 -- a valid date, so no crash.
                    // struct+0xA0 still carries the real time for the paths
                    // that read the refresh stamp.
                    putLe64(rec, 0x00, 0L);
                    putLe64(rec, 0x08, 0L);
                    stamp = STAMP_PROBE == -1L ? 0L
                            : (STAMP_PROBE != 0L ? STAMP_PROBE : nowUnixSeconds);
                } else if (CLOCK_MODE >= 2) {
                    // Best fix: zero the UTC offset instead of pre-cancelling it.
                    //
                    // RequestContentRefresh passes w2=1, and
                    // GetNextResetTime_ReferenceTimeStamp does
                    //     0x79f23a4  tbz w22,#0,+0xc   ; skip when the flag is 0
                    //     0x79f23a8  ldr x8,[x21,#0x30]
                    //     0x79f23ac  add x20,x8,x20
                    // so whether the offset is actually ADDED depends on that
                    // flag.  Pre-cancelling (mode 0) only works when the add
                    // happens; when it does not, our now-0x0404... (a huge
                    // negative) is used raw and the date explodes -- that is
                    // the intermittent "Invalid Date values. Y:-12930" that
                    // killed the lobby both on the way in and on the way back.
                    //
                    // Writing struct+0x08 = 0 makes the add a no-op, so the
                    // stamp is correct either way.
                    putLe64(rec, 0x08, 0L);           // utcOffset = 0
                    stamp = STAMP_PROBE == -1L ? 0L
                            : (STAMP_PROBE != 0L ? STAMP_PROBE : nowUnixSeconds);
                } else {
                    stamp = STAMP_PROBE == -1L ? 0L
                            : (STAMP_PROBE != 0L ? STAMP_PROBE : nowUnixSeconds);
                    if (STAMP_PROBE != -2L) {
                        stamp -= 0x0404040404040404L;
                    }
                }
                // Refresh stamp.  GetRefreshStamp @0x0796107c reads
                // (manager+0xD0)+0x188, which SetAccountInfo fills from
                // [struct+0x10]+0x90 = struct+0xA0.
                // ---- calibration probe ---------------------------------
                // Send a known marker so the reported year can be mapped back
                // to a tick count.  STAMP_PROBE_* is one variable at a time;
                // set STAMP_PROBE to 0 to restore the real clock.
                // -1 means "send a literal zero", which separates "the field
                // is read and I am writing the wrong unit" from "the field is
                // not read at all": a literal 0 must give 1970 if it is read.
                putLe64(rec, 0xA0, stamp);
                // LastFavorResetTime.
                //
                // UUIFacilityCommunity::OnClickSlotCommunity (the "add character"
                // + button in the dorm) first does:
                //   0x81c27b4  ServerTimeManager::GetLastResetTime_UTC()
                //   0x81c27c8  AccountManager::GetLastFavorResetTime()   -> [account+0x150]
                //   0x81c27d8  b.le 0x81c28a0        ; reset <= lastFavorReset -> normal path
                // and on the other branch it only tries to build a notice text and
                // returns silently when the text lookup misses (0x81c2850 tbz -> return).
                // With the field left at 0 the server reset is always "later", so the
                // + button did nothing at all: no popup, no packet.
                //
                // The account object is filled from this record; object+0x188 comes
                // from record+0xA0 (refresh stamp, verified earlier), i.e. a +0xE8
                // delta, so object+0x150 is record+0x68.
                // FavorResetStamp: account object +0x150, and
                // UCMAccountManager::SetAccountInfo @0x0795eca4 maps it from
                // record +0x78:
                //   0x795ed4c  ldur x10, [x19, #0x62]
                //   0x795ed50  ldp  x11, x28, [x19, #0x78]
                //   0x795edd0  stp  x10, x11, [x25, #0x148]   ; +0x148=rec+0x62, +0x150=rec+0x78
                // The old write went to +0x68, which no field consumes, so
                // GetLastFavorResetTime() stayed 0 and the dorm kept treating
                // the daily favour reset as pending (greyed 외출).
                putLe64(rec, 0x78, stamp);
                ByteBuffer ai = ByteBuffer.allocate(2 + rec.length + 16)
                        .order(ByteOrder.LITTLE_ENDIAN);
                ai.putShort((short) rec.length);
                ai.put(rec);
                ai.put(new byte[16]);
                byte[] aiBody = ai.array();
                Status.log("game: SC_ACCOUNTINFORES name='" + nick + "' MainScene=7"
                        + " stamp=" + stamp
                        + " @0xA0=" + Long.toHexString(rd64(rec, 0xA0))
                        + " (" + aiBody.length + "B)");
                send(out, SC_ACCOUNTINFORES, session, aiBody);
                return;
            case 212:
                // CS_ACCOUNTSECTION02 -> SC_CHARACTERINFO2RES(326) -> 337 328 329
                // 330 334 335 -> SC_ACCOUNTSECTION02RES(213).
                //
                // The retail log puts 326 exactly here, between the 212 request
                // and the 213 result:
                //
                //   [11.12.30:966] Send(23)  [Net CS : CS_ACCOUNTSECTION02(212)]
                //   [11.12.31:092] Tick(242) [Net SC : SC_CHARACTERINFO2RES(326)]
                //   [11.12.31:198] Tick(242) [Net SC : SC_ACCOUNTSECTION02RES(213)]
                //
                // This is the character inventory, and leaving it out is what
                // produced "No My Characters!".  The client then still asked the
                // LobbyBG table for a character (CheckBGSelectChar showed
                // LobbyBG Type:2 CharID:4105) and had nothing to spawn, which is
                // the SIGSEGV in the OpenLevel transition.
                //
                // Payload layout, read from the client's own reader at
                // 0x07c288dc:
                //
                //   +0x00 u32 A          (0x7c289e4)
                //   +0x04    skipped 4B
                //   +0x08 u32 B          (loaded in the same copy as A)
                //   +0x0C u32 count      ; records, each: u16 byte-length + bytes
                //                        memcpy'd to the head of a 0x30-byte
                //                        FGB_CharacterInfo
                //
                // A and B are compared at 0x7c28b7c (`cmp w23, w24 / b.ne`) and
                // the extra processing only runs when they are equal, so both are
                // the character count here.
                //
                // The record is a bare length-prefixed string, and the client
                // resolves the rest -- including CharID -- from it.  "4105" is
                // JinSomi, the starter character the tutorial expects, confirmed
                // in Character_Base.tbd where 0x1009 is followed by the
                // /Game/Game/Animation/Character/Player/JinSomi/ resource path.
                send(out, SC_CHARACTERINFO2RES, session, characterPayload());
                // The retail client got a burst of inventory packets in each
                // section, not just the RES.  Reading the 2023 retail log:
                //
                //   CS_ACCOUNTSECTION02 -> 326 337 328 329 330 334 335 -> 213
                //   CS_ACCOUNTSECTION03 -> 338 340 341                 -> 215
                //   CS_ACCOUNTSECTION04 -> 343 344 ...                 -> 217
                //
                // Answering with the RES alone left the client to dereference
                // managers those packets would have populated, and it died with
                // SIGSEGV immediately after sending CS_ACCOUNTSECTION06 -- the
                // log simply stops mid-line at "Send(23) [Net CS : CS_A".
                //
                // Every one of these is a list-shaped packet, so the empty
                // payload from send() means "no records", which is the safe
                // shape: it populates the manager with an empty list instead of
                // leaving it null.
                send(out, SC_SUMMONINFORES, session, le32(0));
                send(out, SC_FACILITYBUILDINGINFORES, session, facilityBuildingPayload());
                send(out, SC_FACILITYCRAFTINFORES, session, le32(0));
                send(out, SC_FACILITYMISSIONINFORES, session, le32(0));
                try {
                    if (sendFavorPackets) send(out, SC_FACILITYFAVORINFORES, session, favorPayload());
                } catch (Exception e) {
                    // Surface the failure instead of silently dropping the
                    // packet: a swallowed exception here looked exactly like
                    // "the client disconnected for no reason".
                    Status.log("game: FAVOR PAYLOAD FAILED: " + e);
                }
                if (sendOrganizePackets) send(out, SC_FACILITYORGANIZEINFORES, session, organizePayload());
                // The login 328 above only CREATES the buildings.  The append to
                // manager+0x78 (what the base screen draws "Lv.1" from) needs a
                // second pass with flags 0x08 -- but the burst itself is fragile
                // (adding one packet to it once produced the IP-mismatch popup),
                // so flag it here and send the completion on the next packet the
                // client itself sends, which is by definition outside the burst.
                // no login-time completion pass; 701 queues the phantom work-
                // complete popup that then re-appears on every battleship entry
                // (retail shows no such popup after building).
                send(out, 213, session, new byte[0]);
                return;
            case 210:
                // After ACCOUNTSECTION01 the retail server pushed the stage and
                // chapter tables -- the chapter list is where 14/15 live.
                //
                // SC_CHARACTERINFO2RES(326) must NOT be sent here.
                //
                // This used to be `send(..., le32(0))`, i.e. a four-byte
                // payload.  @0x07c288dc reads that packet as
                //
                //     +0x00 u32, +0x04 skipped, +0x08 u32, +0x0C u32 count
                //
                // and then `count` records of 0x30 bytes each, so a four-byte
                // payload is read far past its end.  The damage showed up as
                // heap corruption (`FMallocBinned2 Attempt to realloc an
                // unrecognized block ... canary == 0x0 != 0xe3`), as a crash in
                // OpenLevel, or as a GameThread stuck at 100% CPU on
                // Set_EUI_PanelTransitionStateType(247) Start -- which is why
                // reaching the lobby looked intermittent.
                //
                // Leaving 326 to the default handler (empty payload) is what
                // makes the lobby load reliably.
                // Bitmask of the content packets to emit at the end of
                // ACCOUNTSECTION01, for bisecting the lobby-load crash:
                //   bit 0 = SC_STAGEINFORES(322)      bit 1 = SC_CHAPTERINFORES(324)
                //   bit 2 = SC_STAGEDECKINFO2RES(323) bit 3 = SC_ITEMINFORES(325)
                //   bit 4 = SC_FUNDINFORES(327)
                // Default 0x1F (all).  Runtime: /api/content?mask=N
                int mask = contentPackets == 0 ? (Integer) 0 : contentPackets;
                if ((mask & 0x01) != 0) {
                    // Split across several frames: a single 322 larger than
                    // 16 KiB hangs the client (see STAGE_CHUNK).
                    for (byte[] chunk : stagePayloads()) {
                        send(out, SC_STAGEINFORES, session, chunk);
                    }
                }
                if ((mask & 0x02) != 0) {
                    send(out, SC_CHAPTERINFORES, session, chapterPayload());
                }
                if ((mask & 0x04) != 0) {
                    send(out, SC_STAGEDECKINFO2RES, session, le32(0));
                }
            topUpDefaults();
                if ((mask & 0x08) != 0) {
                    for (byte[] frame : itemPayloads()) {
                        send(out, SC_ITEMINFORES, session, frame);
                    }
                }
                if ((mask & 0x10) != 0) {
                    send(out, SC_FUNDINFORES, session, fundPayload());
                }
                send(out, SC_ACCOUNTSECTION01RES, session, le32(1));
                return;
            case 214:
                // CS_ACCOUNTSECTION03 -> 338 340 341 -> 215.
                //
                // These three carry the player's collection teams, achievements
                // and global effects.  They are list-shaped, so they start with a
                // record count -- and a completely EMPTY payload has no count at
                // all, which makes the client read one from beyond the payload and
                // then walk however many records that garbage number implies.
                //
                // The proxy trace shows the client dying immediately after these
                // four frames arrive (it sends CS_ACCOUNTSECTION03, receives
                // 338/340/341/215, then RSTs instead of asking for section 04),
                // and the tombstone shows the heap already corrupt by then: the
                // crash lands in the error-logging path with rdi = 0x6e35 and
                // rcx = 0x15e65, i.e. two "pointers" that are really small
                // integers, which is the signature of earlier heap damage rather
                // than a fault in logging itself.
                //
                // le32(0) is the same shape already used for 322/323/325/327 --
                // packets in this very handshake that the client accepts without
                // complaint -- so "count = 0, no records" is the safe form here.
                send(out, SC_COLLECTTEAMINFORES, session, le32(0));
                send(out, SC_ACHIEVEINFORES, session, le32(0));
                send(out, SC_GLOBALEFFECTINFORES, session, le32(0));
                send(out, 215, session, new byte[0]);
                return;
            case 216:
                // CS_ACCOUNTSECTION04 -> 343 344 353 -> 217.
                //
                // Same shape as case 214: these are list-shaped records, so they
                // need a leading count.  An empty payload leaves the client to
                // read one from beyond the frame and then walk that many records,
                // which is where it died once the tutorial record was accepted --
                // it had been getting as far as 216 and then failed on the frame
                // after these arrived.
                send(out, SC_ACCOUNTPROFILEINFORES, session, le32(0));
                send(out, SC_ACCOUNTPROFILEFRAMEINFORES, session, le32(0));
                send(out, SC_ACCOUNTATTENDINFORES, session, le32(0));
                send(out, 217, session, new byte[0]);
                return;
            case 218:
                // CS_ACCOUNTSECTION05 -> 354 355 -> 219.
                send(out, SC_ACCOUNTEVENTINFORES, session, le32(0));
                send(out, SC_ACCOUNTEVENTREWARDINFORES, session, le32(0));
                send(out, 219, session, new byte[0]);
                return;
            case 220:
                // CS_ACCOUNTSECTION06 -> SC_ACCOUNTSECTION06RES(221), and then
                // the packet the client actually waits for.
                //
                // The retail log shows the account-loading sequence ending with
                // a separate push that nothing in our handshake produced:
                //
                //   [11.12.32:039] Send(23)   [Net CS : CS_ACCOUNTSECTION06(220), OK(1)]
                //   [11.12.32:152] Tick(242)  [Net SC : SC_ACCOUNTSECTION06RES(221), OK(1)]
                //   [11.12.32:309] Tick(242)  [Net SC : SC_ACCOUNTINFOFINISHRES(222), OK(1)]
                //
                // Without 222 the client finished every section and then sat on
                // "서버와 연결 중입니다" (connecting to server) forever, because
                // the default handler only echoed opcode+1.
                // 222 is deferred by SECTION_GAP_MS, matching retail's spacing.
                //
                // Retail puts 157 ms between the two:
                //   [11.12.32:152] SC_ACCOUNTSECTION06RES(221)
                //   [11.12.32:309] SC_ACCOUNTINFOFINISHRES(222)
                // and the lobby opens at :586.
                //
                // The deadline is recorded rather than slept on, and the receive
                // loop emits 222 when it passes.  Two earlier attempts at the gap
                // were abandoned: a plain Thread.sleep here blocked the loop so
                // the client's keepalives went unanswered, and a helper thread
                // writing to the same socket wedged the connection.  Deferring on
                // the loop keeps every write on the socket's owning thread.
                //
                // SECTION_GAP_MS is settable at runtime with /api/gap?ms=N, and 0
                // restores the back-to-back behaviour, so the two can be compared
                // without rebuilding.
                // The tutorial record goes out with the end of the account
                // handshake.  Sending it earlier -- with SC_ACCOUNTINFORES(209)
                // -- got it accepted (the client then reported MainScene 7
                // instead of 4) but the lobby load afterwards faulted at
                // OpenLevel, before the transition animation finished.  Here it
                // travels with the section-06 result that the client is already
                // waiting on, so the account managers are all populated first.
                if (sendAccountMisc) {
                    send(out, SC_ACCOUNTMISCINFORES, session, accountMiscPayload());
                }
                if (sendNarrativeReset) {
                    send(out, SC_CHEATNARRATIVECOUNTRESETRES, session,
                         narrativeResetPayload());
                }
                send(out, 221, session, new byte[0]);
                if (SECTION_GAP_MS > 0) {
                    pending222Out = out;
                    pending222Session = session;
                    pending222At = System.currentTimeMillis() + SECTION_GAP_MS;
                } else {
                    send(out, SC_ACCOUNTINFOFINISHRES, session, new byte[0]);
                }
                return;
            // CS_CONTENTREFRESH -> SC_CONTENTREFRESHRES(22).
            //
            // RequestContentRefresh sends 21 and then registers a delegate for
            // the reply:
            //   0x7bf10c8  bl   #0x79f2318   ; next reset time
            //   0x7bf1108  bl   #0x7cd77a0   ; CS_ContentRefresh
            //   0x7bf1178  bl   #0x7c46140   ; SC_ContentRefreshRes_BindWeakLambda
            //
            // The reply is an 8-byte record, not an empty frame:
            //   0x7c21a44  mov x8, #0x404040404040404  ; pre-filled 0x04
            //   0x7c21a54  cmp w8, #0x21               ; frame >= 33 bytes
            //   0x7c21a7c  ldrh w21, [x9, x8]          ; u16 length at payload+0
            //   0x7c21a8c  mov w3, #8                  ; copy at most 8 bytes
            //
            // Sending an empty payload left the length at 0, so nothing was
            // copied and the callback saw 0x0404040404040404 -- four 1028
            // byte-values it then acted on.  That is the most likely trigger for
            //   "Internal Error: RemoveFromOuterMap NumRemoved = 0 for
            //    MovieScene2DTransformPropertySystem"
            // which is the UMG animation system tearing down an object twice.
            //
            // Answer with a length-prefixed, all-zero 8-byte record: "nothing
            // changed", which every reader can accept without side effects.
            case 21: {
                byte[] rec22 = new byte[8];
                ByteBuffer cb = ByteBuffer.allocate(2 + rec22.length + 8)
                        .order(ByteOrder.LITTLE_ENDIAN);
                cb.putShort((short) rec22.length);
                cb.put(rec22);
                cb.put(new byte[8]);
                send(out, 22, session, cb.array());
                return;
            }
            // ---- UI page data requests ----------------------------------
            //
            // Opening a page makes the client ask for that page's data.  Every
            // one of these was falling through to the default handler, which
            // answers with an empty payload -- fine for a list-shaped record
            // (it means "no rows") but fatal for pages that then build a date,
            // a reward row or a gacha table from what they found.
            //
            // Measured on 2026-10-17 while the player drove the UI by hand:
            //   CollectionTeamDetailPage     -> SEGV_MAPERR 0x76456c0a3c88
            //   CollectionRelationDetailPage -> "SetData_By_TBReward_Record
            //        IsValid false: 'In_TBReward_Record'" x12, then SEGV 0x20
            // and the next launch died in TaskGraphHP (async load) at
            // 0x765031f90020, again with no in-engine Fatal.
            //
            // These answer each request with a well-formed EMPTY result so the
            // page shows an empty list instead of dereferencing nothing.
            case 388:   // CS_CHARACTERSUMMONREFRESH
                send(out, 389, session, le32(0));
                return;
            case 390:   // CS_CHARACTERSUMMON
                send(out, 391, session, le32(0));
                return;

            // ---- character growth --------------------------------------
            //
            // Retail computes these server-side: the client sends "level up
            // this character, consuming these materials" and the server
            // validates, deducts, applies and answers.  Offline we are the
            // server, so the same responsibilities apply -- anything that
            // only flips a number in memory is a bug, because the player pays
            // materials for it and then loses the result on the next restart.
            //
            // Opcodes recovered by disassembling the client's own senders
            // (not from the eCmd reflection table, which is a different
            // numbering -- see _scratch/cs_opcodes.txt):
            //   CS_Character2LevelUp @0x7cdb13c  mov w9,#0x25c -> 604
            //   CS_Character2ClassUp @0x7cdb320  mov w9,#0x25e -> 606
            //   CS_Character2SkillUp @0x7cdb460  mov w9,#0x260 -> 608
            //   CS_ItemEnchant       @0x7cdac20  mov w9,#0x1fe -> 510
            //   CS_ItemUse           @0x7cda5f8  mov w9,#0x1f6 -> 502
            //
            // Payload shape for LevelUp (484-byte function, 0x7cdb13c):
            //   u16 len + u32 charId      (0x7cdb1cc: ldr w8,[x23] / bl Write(4))
            //   u32 consumeCount          (0x7cdb1d8: ldr w23,[x21,#8])
            //   consumeCount * 8 bytes    (0x7cdb254: mov w2,#8 in the loop)
            // Each consume entry is FGB_ConsumeID { u32 type, u32 id }.
            case 604: { // CS_CHARACTER2LEVELUP
                int lvChar = handleCharacterLevelUp(payload);
                // Restock the chips FIRST: the client refreshes the popup when it
                // receives 605/369, and if the bag still shows 0 at that moment the
                // four squares stay dark until the window is reopened.
                if (lvChar > 0) {
                    for (int chip : new int[] { 11001, 11002, 11003, 11004 }) {
                        try { sendItemUpdate(out, session, chip); } catch (Exception ignored) { }
                    }
                }
                {
                    SaveStore.CharGrowth gg = SaveStore.get().growthOf(lvChar);
                    send(out, 605, session, levelUpResPayload(lvChar, gg.level, gg.exp));
                }
                if (lvChar > 0) sendCharacterUpdate(out, session, lvChar);
                return;
            }
            case 612: { // CS_CostumeInfoOpen  (sender writes mov w9,#0x264)
                int cosChar = commuCid(payload);
                Status.log("costume: open for char " + cosChar);
                int[] cids = { 100201,102401,102501,102601,102701,102801,102901,103001,103101,200101,200103,200301,200304,200504,200602,200704,200802,201101,201103,300101,300201,300204,300302,300604,300702,400601,401004 };
                ByteBuffer cb = ByteBuffer.allocate(4 + cids.length * 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                cb.putInt(cids.length);
                for (int c : cids) cb.putInt(c);
                send(out, 613, session, bodyArray(cb));
                return;
            }
            case 614: { // CS_WearingCostume  (sender writes mov w9,#0x266)
                int wearChar = commuCid(payload);
                int costumeId = rd32(payload, payload != null && payload.length >= 10 ? 6 : 4);
                Status.log("costume: wear char " + wearChar + " costume " + costumeId);
                ByteBuffer wb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                wb.putInt(wearChar);
                wb.putInt(costumeId);
                wb.putInt(0);
                send(out, 615, session, bodyArray(wb));
                return;
            }
            case 606: { // CS_CHARACTER2CLASSUP
                int clChar = handleCharacterClassUp(payload);
                Status.log("growth: classup raw=" + hexPreview(payload, 24));
                send(out, 607, session, le32(0));
                if (clChar > 0) {
                    // roster re-push marker: single record first, then the whole list so
                    // the client recomputes the tier caption and the level cap.
                    send(out, SC_CHARACTERINFO2RES, session, characterPayload());
                    sendCharacterUpdate(out, session, clChar);
                }
                return;
            }
            case 608: { // CS_CHARACTER2SKILLUP
                int skChar = handleCharacterSkillUp(payload);
                send(out, 609, session, le32(0));
                if (skChar > 0) sendCharacterUpdate(out, session, skChar);
                return;
            }
            case 502: { // CS_ITEMUSE
                int useItem = handleItemUse(payload);
                send(out, 503, session, le32(0));
                if (useItem > 0) sendItemUpdate(out, session, useItem);
                if (lastFocusedCharacter > 0) sendCharacterUpdate(out, session, lastFocusedCharacter);
                return;
            }
            case 506: { // CS_ITEMEQUIP
                int eqChar = handleItemEquip(payload);
                send(out, 507, session, le32(0));
                if (eqChar > 0) sendCharacterUpdate(out, session, eqChar);
                return;
            }
            case 508: { // CS_ITEMUNEQUIP
                int ueqChar = handleItemUnequip(payload);
                send(out, 509, session, le32(0));
                if (ueqChar > 0) sendCharacterUpdate(out, session, ueqChar);
                return;
            }
            case 512: { // CS_ITEMSELL
                int[] sold = handleItemSell(payload);
                send(out, 513, session, le32(0));
                if (sold != null && sold.length >= 2) sendItemUpdate(out, session, sold[0]);
                send(out, SC_FUNDINFORES, session, fundPayload());
                try { sendItemUpdate(out, session, 101); } catch (Exception ignored) { }
                send(out, SC_FUNDINFORES, session, fundPayload());
                return;
            }
            case 510: { // CS_ITEMENCHANT -- equipment upgrade
                int[] ench = handleItemEnchant(payload);
                send(out, 511, session, le32(0));
                if (ench != null && ench.length >= 2) {
                    sendItemUpdate(out, session, ench[0]);
                    if (lastFocusedCharacter > 0) sendCharacterUpdate(out, session, lastFocusedCharacter);
                }
                return;
            }
            // ---- facility construction ------------------------------
            //
            // CS_FacilityBuildingBuild(702) wire payload (captured live):
            //   06 00 | 65 00 00 00 | 01 00
            //   ^u16   ^u32 building   ^u16
            //        len=6    id=101
            //
            // The client sends this when the player confirms 건설 in the
            // build dialog and then WAITS.  Answering with a bare OK left the
            // dialog open forever (and the building unbuilt), which is exactly
            // the "點下去沒反應" report.
            case 702: { // CS_FACILITYBUILDINGBUILD
                // Sender: CS_FacilityBuildingBuild @0x07cdbf00
                //   0x7cdbf7c  mov  w2, #6          ; payload is 6 bytes
                //   0x7cdbfa0  str  w8, [sp, #0x10] ; u32 building id
                //   0x7cdbfa4  strb w9, [sp, #0x14] ; u8 ETB_Facility_Field
                //   0x7cdbfa8  strb w10,[sp, #0x15] ; u8
                int bid = 0;
                if (payload != null && payload.length >= 6) {
                    bid = rd32(payload, 2);
                }
                if (bid > 0) {
                    facilityBuilt.add(bid);
                    // ACCUMULATE.  The old code did
                    //     facilityBuildingIds = new int[]{ bid };
                    // i.e. it REPLACED the whole list with the building that
                    // was just constructed.  Building the dorm (102) therefore
                    // erased the HQ (101) from every later packet, which is
                    // exactly the "headquarters reverted to unbuilt" report.
                    facilityBuildingIds = mergeBuiltIds(facilityBuildingIds, bid);
                    persistBuiltIds();
                    Status.log("facility: built " + bid + " -> list "
                            + java.util.Arrays.toString(facilityBuildingIds));
                }
                // SC_FacilityBuildingBuildRes(703) @0x07c30c54 reads:
                //   0x7c30d8c  ldrh w21, [x9, x8]   ; u16 length
                //   0x7c30d9c  mov  w3,  #0x59      ; 89-byte FGB_FacilityBuildingInfo
                //   0x7c30dc4  bl   SetFacilityBuildingInfo   ; <- writes the row
                //   0x7c30dec  ldr  w10, [x10, x9]  ; u32 fund count
                //   0x7c30e0c  mov  w24, #0xe       ; 14-byte FGB_FundInfo
                //   0x7c30ed0  bl   SetFundInfos
                //   then a u32 item count and its records.
                //
                // So 703 carries the built building ITSELF (one record, no count
                // prefix), then the fund delta, then the item delta.  The
                // building row must be present or SetFacilityBuildingInfo is
                // never reached and the construction silently rolls back.
                // 703 also feeds SetFacilityBuildingInfo @0x079cf854 directly
                // (0x7c30dc4), so it needs the same create+complete pair or the
                // building is created but never marked built.
                // COMPLETE flags (0x08), not the create flags: the login 328
                // already created key 101 in the manager TSet, and only the
                // 0x08 branch looks the entry up and then calls
                // InitCompleteBuildInfo -> append to manager+0x78, which is
                // what turns the label into Lv.N.
                // STEP 1 - create.  The login 328 only carries the buildings in
                // facilityBuildingIds, so for anything that was not built yet
                // (the dorm on a fresh account) the manager TSet has no entry and
                // the 0x08 branch below would bail at 0x079cf8a8 -- that is
                // exactly why the dorm click gave "small pause, nothing" while
                // the HQ (which IS in the login 328) worked.
                byte[] createRec = facilityRecord(bid, facilityCreateFlags);
                ByteBuffer cbody = ByteBuffer.allocate(2 + createRec.length + 4 + 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                cbody.putShort((short) createRec.length);
                cbody.put(createRec);
                cbody.putInt(0);        // fund rows
                cbody.putInt(0);        // item rows
                send(out, SC_FACILITYBUILDINGBUILDRES, session, cbody.array());

                // STEP 2 - complete.
                byte[] builtRec = facilityRecord(bid, facilityCompleteFlags);
                ByteBuffer body = ByteBuffer.allocate(2 + builtRec.length + 4 + 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                body.putShort((short) builtRec.length);
                body.put(builtRec);
                body.putInt(0);         // fund rows
                body.putInt(0);         // item rows
                send(out, SC_FACILITYBUILDINGBUILDRES, session, body.array());
                // Completion.  SetFacilityBuildingInfo @0x079cf854 has two
                // mutually exclusive branches and only ONE of them marks the
                // building as built:
                //   flags bit1 (0x0A) -> 0x79cf914 TSet::Add, creates the entry
                //   (flags & 0xC) == 8 (0x08) -> looks the entry up, then calls
                //                        InitCompleteBuildInfo @0x079cfa30,
                //                        the ONLY code that appends to
                //                        manager+0x78 -- the array the base
                //                        screen renders "Lv.1" from.
                // A 0x08 row with no prior 0x0A row bails at 0x079cf8a8, so the
                // create has to happen in the same session; 701 carries a list
                // (u32 count, then count * (u16 len + 89B)) while 703 carries a
                // single bare record.
                // 701 is NOT the construction reply: in this build it drives
                // the main page work-complete popup (the client creates
                // UUIFacilityBuildComplete for it) and retail has no such popup
                // after building.  It fabricated a phantom LV.-1 -> LV.0
                // dialog on every battleship entry.
                // The 328 already put the entry in the manager TSet, so the
                // completion is a second 703 carrying the complete flags --
                // that is the branch that reaches InitCompleteBuildInfo and
                // appends to manager+0x78.
                // Do NOT re-push 328 here.
                // 328 runs SetFacilityBuildingInfo with the CREATE flags for every
                // row, so sending it right after the completion re-created the
                // same key and threw away the level-1 object that
                // InitCompleteBuildInfo had just appended to manager+0x78 --
                // the next level load then rebuilt the base from a level-0
                // record and the HQ went back to "건설 가능".
                return;
            }
            // ---- boss raid ------------------------------------------
            //
            // CS_BossInfoOpen(140) had NO handler, so the proxy answered
            // "no handler -> empty payload" and the boss-raid page rendered
            // three identical placeholder slots with the start button dead.
            //
            // Reader: UCMNetReceiver_GameBase::SC_BossInfoOpenRes @0x07c240b0
            //   0x7c241d8  mov  w3, #0x17        ; 23-byte fixed struct
            //   0x7c241e0  str  w8, [x23, #8]
            //   0x7c241e4  bl   memcpy          ; u16-length-prefixed
            //   0x7c24204  bl   0x7962b34       ; setter on manager+0x330
            //   0x7c24214  bl   0x7962b4c       ; second field at +9 of it
            //   then  ldr w10,[x10,x9]          ; a u32 list count
            //         entries of FGB_BossRankInfo
            case 140: { // CS_BOSSINFOOPEN
                send(out, 271, session, bossInfoOpenResPayload());
                if (sendNarrativeReset) {
                    send(out, SC_CHEATNARRATIVECOUNTRESETRES, session,
                         narrativeResetPayload());
                }
                return;
            }
            case 143: { // CS_BOSSRANKINGINFO
                send(out, 274, session, bossRankResPayload());
                return;
            }
            case 162: { // CS_BOSSTAKEWEEKREWARD
                send(out, 283, session, le32(0));
                return;
            }
            case 652: { // CS_CHARACTERSUMMON -- the actual gacha pull
                // The pull itself is answered by SC_CHARACTERSUMMONRES(653),
                // and the *animation* is driven by a second, separate packet.
                //
                // Who consumes what:
                //   SC_CharacterSummonRes @0x07c30648  reads the summon info
                //     and the fund rows, then SetSummonInfo / SetFundInfos.
                //   UCMNetReceiver_Game::SC_RewardDisplayRes_Process
                //     @0x07c1d53c calls SetSummonResultRewardDisplayInfo when
                //     the 1-byte display type is 20 (0x14) or 21 (0x15):
                //       0x7c1d764  ldrb w9, [x19]
                //       0x7c1d768  sub  w10, w9, #0x14
                //       0x7c1d76c  cmp  w10, #2
                //       0x7c1d770  b.hs  <skip>
                //       0x7c1d7b0  bl   SetSummonResultRewardDisplayInfo
                //   ACMGachaGameMode::OnReceivedSummonResult @0x07bda26c then
                //     needs PopSummonResultRewardDisplayInfo() to return
                //     non-NULL before it reaches OpenResultPage:
                //       0x7bda318  bl  PopSummonResultRewardDisplayInfo
                //       0x7bda31c  cbz x0, #0x7bda710   ; NULL -> skip the page
                //       0x7bda70c  bl  OpenResultPage
                //
                // OpenResultPage is guarded by a one-shot flag that its own
                // early exit sets (0x7bd9584 cbz -> 0x7bd9838 strb #1), so it
                // only really opens on the SECOND call.  Both call sites fire
                // on the summon result -- the UI callback @0x07c92718 and the
                // game mode @0x07bda70c -- which is why the reward display has
                // to be in place before 653 arrives, not after.
                //
                // Order therefore matters: the reward display (opcode 20)
                // must be in the manager BEFORE the summon result (653), because
                // OnReceivedSummonResult pops it and skips the page entirely
                // when it is absent.
                //
                // Sent ONCE.  OpenResultPage is two-phase (it arms a one-shot
                // flag at +0x4d0 on its first entry and opens on the next), but
                // that second entry comes from the two call sites inside the
                // SAME result packet -- the UI callback @0x07c92718 and the game
                // mode @0x07bda70c.  Sending the pair twice drove the result page
                // through its animation sequence twice, which left the leftover
                // "touch the screen to continue" overlay on top of the finished
                // settlement UI (the second pass had nothing left to pop).
                // The summon id has to come from the request, not a constant.
                //
                // The summon id must come from the request, not a constant:
                // pool 1 uses 10001 while the limited pools use other ids
                // (observed 10005 and 20001 in the proxy log), so a hard-coded
                // id registers the summon info under the wrong key and those
                // pools' result flow finds nothing.
                //
                // CS_CharacterSummon's sender memcpy's the caller's 28-byte
                // struct straight into the stream:
                //   0x7cdbe40  ldur q0, [x21, #0xc]
                //   0x7cdbe44  ldr  q1, [x21]
                //   0x7cdbe48  stur q0, [sp, #0x1c]
                //   0x7cdbe4c  str  q1, [sp, #0x10]
                //   0x7cdbe50  bl   WriteBuffer(sp+0x10, 0x1c)
                //
                // The payload is "u16 length + 28-byte struct", so the summon
                // id is at +2, NOT +0.  Reading +0 picks up the length prefix
                // instead (0x1c001127 -> 0x2711001c) and registers the summon
                // info under a garbage key, which is what broke all three
                // pools in the build that introduced this.  The length prefix
                // is verified before trusting the +2 offset.
                int summonId = 10001;
                if (payload != null && payload.length >= 6) {
                    int len = payload[0] & 0xff | ((payload[1] & 0xff) << 8);
                    if (len == payload.length - 2) {
                        summonId = rd32(payload, 2);
                    } else if (payload.length >= 4) {
                        // No length prefix after all: fall back to +0.
                        summonId = rd32(payload, 0);
                    }
                }
                send(out, SC_REWARDDISPLAYRES, session,
                        summonRewardDisplayPayload());
                send(out, SC_CHARACTERSUMMONRES, session,
                        characterSummonResPayload(summonId));
                return;
            }
            case 424:   // CS_FACILITYMISSIONREFRESH
            case 425:   // CS_FACILITYMISSIONMANUALREFRESH
                send(out, 416, session, le32(0));
                return;
            // ---- dorm (숙소) interaction ----------------------------------
            //
            // Everything a player does inside the dorm -- dragging a character
            // into a slot, talking, giving a gift, the daily date -- used to
            // fall through to the default handler, which answers with an OK and
            // an EMPTY payload.  Every one of these responses is read as
            // "u16 length + fixed struct", so an empty body desyncs the reader
            // and the panel stays on its previous state (dead buttons).
            //
            // The four responses below all share that shape; the struct sizes
            // come straight from the memcpy guards in the readers:
            //   443 SC_FacilityFavorSetSlotRes  @0x7c35e3c  mov w3, #0xc  (12)
            //   445 SC_FacilityFavorCommuRes    @0x7c35fd8  mov w3, #0x3c (60)
            //   447 SC_FacilityFavorGiftRes     @0x7c3622c  (funds + items)
            //   449 SC_FacilityFavorDateRes     @0x7c36440  (same as 445)
            //
            // 453 CS_FacilityOrganizeSetSlot is the "put this character in that
            // dorm slot" request; 454 echoes the resulting slot table back so
            // the grid redraws without waiting for a relog.
            case 773: { // CS_FACILITYFAVORSETSLOT  (wire opcode; sender writes mov w9,#0x305)
                // Sender: CS_FacilityFavorSetSlot @0x07cdda50
                //   0x7cddac8  mov  w2, #5           ; payload is 5 bytes
                //   0x7cddae0  ldrb w8, [x22]        ; u8 slot  -> sp+0x10
                //   0x7cddae4  ldr  w9, [x21]        ; u32 cid  -> sp+0x11 (unaligned)
                // Wire layout (live capture 22:16:39): 05 00 | 00 | 6d 10 00 00
                //   u16 len(5) | u8 slot | u32 charId  (the length prefix used
                //   to be read as the slot, giving char 275578880 and no slot).
                int slot = payload != null && payload.length >= 3 ? (payload[2] & 0xff) : 0;
                int cid = payload != null && payload.length >= 7 ? rd32(payload, 3) : 0;
                if (cid > 0) {
                    // The client always sends slot 0: it expects the SERVER to
                    // place the character.  Keeping the first slot we handed out
                    // stops it drifting to a later square on every refresh.
                    Integer cur = favorSlotOf.get(cid);
                    boolean alreadyPlaced = cur != null;
                    if (!alreadyPlaced) {
                        boolean[] used = new boolean[6];
                        for (Integer v : favorSlotOf.values()) {
                            if (v != null && v >= 0 && v < used.length) used[v] = true;
                        }
                        int free = 0;
                        while (free < used.length && used[free]) free++;
                        slot = free < used.length ? free : 0;
                    } else {
                        slot = cur;
                    }
                    if (alreadyPlaced) {
                        Status.log("favor: char " + cid + " already in slot " + slot + " (ignoring duplicate add)");
                    } else {
                        favorSlotOf.put(cid, slot);
                    }
                    Status.log("favor: set slot " + slot + " -> char " + cid);
                }
                send(out, SC_FACILITYFAVORSETSLOTRES, session, favorSetSlotRes(cid, slot));
                // Repaint the dorm immediately: without these two pushes the
                // grid kept showing the old layout until some other screen
                // forced a refresh (and the player could add the same
                // character twice because slot 1 still looked empty).
                if (cid > 0) {
                    try {
                        if (sendFavorPackets) send(out, SC_FACILITYFAVORINFORES, session, favorPayload());
                        if (sendOrganizePackets) send(out, SC_FACILITYORGANIZEINFORES, session, organizePayload());
                    } catch (Exception e) {
                        Status.log("favor: refresh after set-slot failed: " + e);
                    }
                }
                return;
            }
            case 775: { // CS_FACILITYFAVORCOMMU  (talk; sender writes mov w9,#0x307)
                int cid = commuCid(payload);
                Status.log("favor: commu with char " + cid + " raw=" +
                        hexPreview(payload, 16));
                if (cid > 0) addFavor(cid, favorGainCommu);
                send(out, SC_FACILITYFAVORCOMMURES, session, favorCommuRes(cid));
                // The 776 reply re-applies the character record; on the next
                // dorm entry the client then showed that character one square
                // further along.  Push the two dorm tables straight after the
                // reply so its own copy is corrected immediately.
                try {
                    if (sendFavorPackets) send(out, SC_FACILITYFAVORINFORES, session, favorPayload());
                    if (sendOrganizePackets) send(out, SC_FACILITYORGANIZEINFORES, session, organizePayload());
                } catch (Exception e) {
                    Status.log("favor: refresh after commu failed: " + e);
                }
                return;
            }
            case 777: { // CS_FACILITYFAVORGIFT  (sender writes mov w9,#0x309)
                int cid = commuCid(payload);
                Status.log("favor: gift to char " + cid);
                // A gift is a favour interaction just like 775/779.  The old
                // reply was an all-zero 0x50 struct, so the client re-applied
                // an empty character record and the level snapped back to 1.
                // Apply the gain, reply with the FULL 326 record (same as 776)
                // and refresh the dorm tables.
                if (cid > 0) addFavor(cid, favorGainCommu);
                send(out, SC_FACILITYFAVORCOMMURES, session, favorCommuRes(cid));
                try {
                    if (sendFavorPackets) send(out, SC_FACILITYFAVORINFORES, session, favorPayload());
                    if (sendOrganizePackets) send(out, SC_FACILITYORGANIZEINFORES, session, organizePayload());
                } catch (Exception e) {
                    Status.log("favor: refresh after gift failed: " + e);
                }
                return;
            }
            case 779: { // CS_FACILITYFAVORDATE  (sender writes mov w9,#0x30b)
                int cid = commuCid(payload);
                Status.log("favor: date with char " + cid);
                if (cid > 0) addFavor(cid, favorGainDate);
                send(out, SC_FACILITYFAVORDATERES, session, favorCommuRes(cid));
                // Same reason as 776: the character record makes the client
                // re-apply the character, and it then showed up one square
                // further along.  Re-push the two dorm tables immediately.
                try {
                    if (sendFavorPackets) send(out, SC_FACILITYFAVORINFORES, session, favorPayload());
                    if (sendOrganizePackets) send(out, SC_FACILITYORGANIZEINFORES, session, organizePayload());
                } catch (Exception e) {
                    Status.log("favor: refresh after date failed: " + e);
                }
                return;
            }
            case 781: { // CS_FACILITYFAVORREWARD  (sender writes mov w9,#0x30d)
                int cid = commuCid(payload);
                Status.log("favor: reward for char " + cid);
                send(out, SC_FACILITYFAVORREWARDRES, session, le32(0));
                return;
            }
            case 771: { // CS_FACILITYFAVORREFRESH  (sender writes mov w9,#0x303)
                send(out, SC_FACILITYFAVORREFRESHRES, session, sendFavorPackets ? favorPayload() : le32(0));
                return;
            }
            case 791: { // CS_FACILITYORGANIZESETSLOT
                // Sender: CS_FacilityOrganizeSetSlot @0x07cde148
                //   0x7cde1c0  ldr w24,[x21,#8]  ; TArray count
                //   0x7cde210  str w24,...       ; u32 count
                //   0x7cde238  mov w2,#0xd       ; 13-byte element
                //   0x7cde244  bl  0x7cd7690     ; writes u16 len + bytes
                // so each row is u16 len(13) + 13 bytes (15-byte stride),
                // not the bare 13 the old parser assumed.
                send(out, SC_FACILITYORGANIZESETSLOTRES, session,
                        organizeSetSlotRes(payload));
                return;
            }
            case 481:   // CS_SHOPREFRESH
                send(out, 482, session, le32(0));
                send(out, 480, session, le32(0));
                return;
            case 1203:  // CS_SHOPPURCHASING
                // The shop hangs for the same reason the summon did, but on
                // DIFFERENT opcode numbers than I first assumed.
                //
                // The client sends 1203, not 483.  CS_ShopPurchasing's sender
                // writes its opcode literally:
                //   UCMNetSender_GameBase::CS_ShopPurchasing @0x07cdf11c
                //     0x7cdf18c  mov  w9, #0x4b3       ; 0x4b3 = 1203
                //     0x7cdf198  str  w9, [x8, #4]     ; header +0x04
                // and the proxy log confirms it: "game <- opcode 1203".
                //
                // Case 483 was simply wrong -- 483 is SC_CircleSecessionRes in
                // the reader table -- so the old branch never ran and every
                // purchase fell through to the default handler.
                //
                // The button also blocks touch input until TWO opcodes return:
                //   UUIPurchaseItemPopup::OnClickedBuyButton @0x082ec6a0
                //     0x82ed4c8  adrp x8, #0x582b000
                //     0x82ed4cc  ldr  d0, [x8, #0x3f0]   ; 8 bytes
                //     0x82ed4d0  mov  w8, #2             ; count = 2
                //     0x82ed50c  bl   BlockTouch_Until_ReceiveCmds
                //     0x82ed538  bl   CS_ShopPurchasing
                // and 0x582b3f0 holds  b4 04 00 00 | 14 00 00 00  = {1204, 20}.
                //
                // The button additionally binds a reward-display callback
                // (0x82ed564 SC_RewardDisplayRes_BindWeakLambda), so a
                // successful purchase also wants the reward list on opcode 20.
                // Record what was bought BEFORE answering: the reward list has
                // to describe the item that actually entered the bag, and the
                // bag has to agree after a restart.  Buying used to answer
                // 1204 with an empty reward list, which is why the result page
                // showed a row of blanks.
                int[] bought = recordShopPurchase(payload);
                int boughtId = (bought != null && bought.length > 0) ? bought[0] : 0;
                int boughtQty = (bought != null && bought.length > 1) ? bought[1] : 0;
                Status.log("game: shop purchase " + (boughtId == 0 ? "(unparsed)"
                        : "item " + boughtId + " x" + boughtQty) + " -> 1204 + 20");
                send(out, 1204, session, le32(0));
                send(out, SC_FUNDINFORES, session, fundPayload());
                send(out, SC_REWARDDISPLAYRES, session,
                        shopRewardDisplayPayload(bought));
                // the pre-damage build also pushed an empty 20 right after; the
                // purchase popup waits for that second packet before it closes
                send(out, SC_REWARDDISPLAYRES, session, new byte[0]);
                if (boughtId > 0) {
                    sendItemUpdate(out, session, boughtId);
                }
                return;
            case 456:   // CS_POSTARRIVAL
                send(out, 457, session, le32(0));
                return;
            case 458:   // CS_POSTRECEIVE
            case 459:   // CS_POSTRECEIVEALL
                send(out, 460, session, le32(0));
                return;
            case 461:   // CS_POSTDELETE
            case 462:   // CS_POSTDELETEALL
                send(out, 463, session, le32(0));
                return;
            case 464:   // CS_POSTTIMEOUT
                send(out, 465, session, le32(0));
                return;
            case 473:   // CS_ACHIEVESUMMARYREWARD
                send(out, 474, session, le32(0));
                return;
            case 475:   // CS_ACHIEVEDETAILREWARD
                send(out, 476, session, le32(0));
                return;
            case 477:   // CS_ACHIEVEDETAILREWARDALL
                send(out, 478, session, le32(0));
                return;
            case 100: { // CS_STAGESTART
                // CS_StageStart sender @0x7cd8a28 writes a 70-byte body; the
                // stage id is the u32 at body+6.  Scan the whole payload for a
                // legal 8_CCCC_SS id so an alignment change cannot lose it.
                int sid = findNarrativeStage(payload);
                narrativeActiveStage = sid;   // 0 for a main-story stage
                if (sid > 0) {
                    Status.log("narrative: entering stage " + sid);
                } else {
                    Status.log("game: stage start");
                }
                send(out, 101, session, le32(0));
                return;
            }
            case 102: { // CS_GAMEFINISH -- the client just cleared a stage
                int gold = battleGoldDrop;
                int exp = battleExpDrop;
                if (gold > 0) {
                    SessionState.get().addFund(101, gold);
                }
                if (exp > 0) {
                    SessionState.get().addItem(battleExpItem, exp);
                }
                // CS_GameFinish sender @0x7cd8be8 puts the stage id in the
                // first u32 of its 100-byte body; scan for a legal side-story
                // id as well so a main-story clear is unaffected.
                int stageNo = 0;
                if (payload != null && payload.length >= 4) {
                    stageNo = rd32(payload, 0);
                }
                if (stageNo > 0) {
                    advanceStoryByStage(stageNo);
                }
                int sid = findNarrativeStage(payload);
                if (sid <= 0) sid = narrativeActiveStage;
                // Always acknowledge a side-story battle, even on a REPLAY:
                // the client waits for 256 to leave its result screen.  The
                // save only records the first clear of each stage.
                boolean sideStory = sid > 0;
                if (sideStory) markNarrativeCleared(sid);
                Status.log("game: stage clear -> +" + gold + " gold, +"
                        + exp + " item " + battleExpItem
                        + (stageNo > 0 ? ", stage " + stageNo : "")
                        + (sideStory ? ", side-story " + sid : "") + " (saved)");
                send(out, 103, session, le32(0));
                if (sideStory) {
                    // The client registers EITHER SC_MainStoryResultRes(255) or
                    // SC_NarrativeResultRes(256) right after sending 102,
                    // depending on its game mode (0x7c03fc8 cmp w21,#3).  We do
                    // not know that mode reliably, so send both shapes: each is
                    // a well-formed 44-byte / 53-byte record and the client only
                    // processes the one it registered.  This is what commits the
                    // 3 stars and unlocks the next stage.
                    send(out, SC_MAINSTORYRESULTRES, session,
                         narrativeStageRecord(sid));
                    send(out, SC_NARRATIVERESULTRES, session,
                         narrativeResultPayload(sid));
                    // Keep the daily counter pinned at max (no consumption).
                    send(out, SC_CHEATNARRATIVECOUNTRESETRES, session,
                         narrativeResetPayload());
                    narrativeActiveStage = 0;
                }
                return;
            }
            case 417:   // CS_FACILITYMISSIONSTART
                send(out, 418, session, le32(0));
                return;
            case 420:   // CS_FACILITYMISSIONREWARD
                send(out, 421, session, le32(0));
                return;
            case 422:   // CS_FACILITYMISSIONREWARDALL
                send(out, 423, session, le32(0));
                return;
            case 146: { // CS_VirtualInfo (opens the side-story / virtual battle page)
                // Re-push the daily counter when that page opens.  The
                // login-time 532 arrives before UCMAccountManager+0x330 exists,
                // so the client drops it and the counter keeps showing the
                // unset -1/3.  Sending it here lands after the manager is
                // built, which is what actually updates the UI.
                if (sendNarrativeReset) {
                    send(out, SC_CHEATNARRATIVECOUNTRESETRES, session,
                         narrativeResetPayload());
                }
                send(out, 147, session, new byte[0]);
                return;
            }
            default:
                // Reply with the OK result and an EMPTY payload.
                //
                // This used to send le32(1).  For the many list-shaped
                // SC_*RES packets that value is a record COUNT, so the client
                // saw "1 record" and then spun forever trying to read a record
                // that was not there.  The GameThread showed it plainly:
                // state=R with utime climbing ~100 ticks/second, i.e. a busy
                // loop rather than a wait, and the client froze right after
                // opening the lobby level.
                //
                // The OK comes from the header's result field (+0x10), which
                // hdrField16 already sets, so the payload must stay empty.
                Status.log("game: no handler for opcode " + opcode
                        + " -> result=1, empty payload");
                send(out, opcode + 1, session, new byte[0]);
        }
    }

    /**
     * Payload for SC_FACILITYFAVORINFORES(334) -- character affinity.
     *
     * Consumer: UCMFacilityInfoManager::SetFacilityFavorInfo @0x079d4cbc
     *
     * FGB_FacilityFavorInfo is 12 bytes
     * (FGB_FacilityFavorInfo::StaticStruct @0x08679928 -> GetStaticStruct size 0xc):
     *
     *   0x79d4cdc  ldr  w8, [x1, #0]    ; +0x00 flags (bit6 drop, bit1 add)
     *   0x79d4d14  ldur w9, [x19, #5]   ; +0x05 = key = character id (u32)
     *   0x79d4da0  ldr  w8, [x19, #8]   ; +0x08
     *   0x79d4da4  ldr  x9, [x19, #0]   ; +0x00..+0x07
     *   0x79d4db0  str  w8, [x0, #48]   ; UCMFacilityFavorInfo+0x30
     *   0x79d4db4  str  x9, [x0, #40]   ; UCMFacilityFavorInfo+0x28
     *
     * so a record is flags(4) | pad(1) | charId(4, unaligned) | points(4).
     */
    // ---- tunables that lived in the block lost to the editing accident ----------
static final int SC_ITEMUPDATERES = 357;
static final int SC_BOSSINFORES = 0;

static int itemCategoryOf(int itemId) {
    if (isExpChip(itemId) && chipCat >= 0) return chipCat;
    Integer ov = itemCatOverride.get(itemId);
    if (ov != null) return ov;
    return ItemCatalog.typeOf(itemId);
}

static boolean isExpChip(int id) {
    return id == 11001 || id == 11002 || id == 11003 || id == 11004;
}

/** Sell price for one unit (offline rule, chips are the valuable ones). */
static int sellPriceOf(int itemId) {
    switch (itemId) {
        case 11001: case 11002: case 11003: case 11004: return 500;
        case 11401: return 1000;
        default: return 100;
    }
}

/**
 * CS_ItemSell(512).  _memory/STAGE-63: the row is three u32 (itemId, held,
 * sellQty) but the header length varies, so scan every offset for a triple
 * whose first word is an item we actually hold.
 */
static int[] handleItemSell(byte[] payload) {
    if (payload == null || payload.length < 12) {
        Status.log("item: sell with short payload");
        return new int[0];
    }
    SessionState st = SessionState.get();
    for (int off = 0; off + 12 <= payload.length; off++) {
        int itemId = rd32(payload, off);
        int held = rd32(payload, off + 4);
        int qty = rd32(payload, off + 8);
        if (itemId <= 0 || held <= 0 || qty <= 0 || qty > held) continue;
        int have = st.itemCount(itemId);
        if (isExpChip(itemId)) {
            Status.log("sell: refusing to sell an EXP chip (kept at 99)");
            return new int[0];
        }
        if (have <= 0) continue;
        int sold = Math.min(qty, have);
        st.addItem(itemId, -sold);
        int gold = sold * sellPriceOf(itemId);
        st.addFund(101, gold);
        st.addItem(101, gold);   // keep the currency item in step
        Status.log("sell: item " + itemId + " x" + sold + " (held " + have
                + ") -> +" + gold + " gold (saved)");
        return new int[] { itemId, sold };
    }
    Status.log("item: sell payload had no readable triple");
    return new int[0];
}
static int[] handleItemEnchant(byte[] payload) { Status.log("item: enchant ignored"); return new int[0]; }
static int handleItemUnequip(byte[] payload) { return growthCharOf(payload); }
/** SC_ITEMINFORES(325): four-u32 header + rows of (u16 63 + FGB_ItemInfo). */
/** Top every baseline item back up to 999 (runs once per login). */
/**
 * Guarantee the baseline equipment / consumable rows exist, once per login.
 *
 * The four EXP chips were already topped up every login, which is why an old
 * save still showed them.  The equipment and consumable defaults behaved the
 * opposite way: they were only seeded when the save was empty, so an account
 * that already had a save never received them.
 *
 * Operators expect baseline items to work like the chips -- present on every
 * account regardless of save age -- so the rule here is:
 *
 *   - baseline row with count 1 (equipment / consumables): ensure at least 1
 *   - baseline row with a larger count (EXP chips = 999): top up to that count
 *
 * Only ever adds, never removes, so it cannot eat a stack the player bought.
 * Runs before SC_ITEMINFORES is built, so the login packet already contains it.
 */
static void topUpDefaults() {
    SessionState st = SessionState.get();
    int added = 0;
    for (AssetStore.Item base : AssetStore.get().items()) {
        int floor = base.count;
        if (floor <= 0) continue;
        int have = st.itemCount(base.id);
        if (have < floor) {
            st.addItem(base.id, floor - have);
            added++;
        }
    }
    if (added > 0) {
        Status.log("items: baseline top-up " + added + " row(s), now "
                + st.items().size() + " total");
    }
}

/**
 * SC_ITEMINFORES(325) split into sub-16-KiB frames.
 *
 * The client hangs when ONE frame exceeds 16 KiB (measured on SC_STAGEINFORES:
 * 322 is already chunked at 320 rows of 46 bytes).  itemPayload() used to put
 * every row in a single frame, so 8 items were fine but 352 default rows made
 * a 23.5-KB frame and the lobby never finished loading.
 *
 * Each frame is a complete 325 payload with its own four-u32 header, so the
 * client accumulates rows rather than replacing them.  200 rows x 66 bytes
 * + 32 = 13,232 bytes, comfortably under the limit.
 */
static byte[][] itemPayloads() {
    java.util.List<AssetStore.Item> rows =
            new java.util.ArrayList<AssetStore.Item>(SessionState.get().items());
    for (AssetStore.Fund f : SessionState.get().funds()) {
        if (!isCurrency(f.id) || f.amount <= 0 || f.amount > Integer.MAX_VALUE) continue;
        boolean have = false;
        for (AssetStore.Item it : rows) {
            if (it.id == f.id) { have = true; break; }
        }
        if (!have) rows.add(new AssetStore.Item(f.id, (int) f.amount));
    }
    int total = rows.size();
    if (total == 0) return new byte[][] { le32(0) };
    final int CHUNK = 200;
    java.util.List<byte[]> out = new java.util.ArrayList<byte[]>();
    for (int off = 0; off < total; off += CHUNK) {
        int n = Math.min(CHUNK, total - off);
        ByteBuffer body = ByteBuffer.allocate(16 + n * 66 + 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(n);
        body.putInt(0);
        body.putInt(n);
        body.putInt(n);
        for (int i = 0; i < n; i++) {
            AssetStore.Item it = rows.get(off + i);
            byte[] rec = itemInfoRecord(it.id, it.count);
            body.putShort((short) rec.length);
            body.put(rec);
        }
        out.add(bodyArray(body));
    }
    Status.log("game: SC_ITEMINFORES " + total + " item row(s) in "
            + out.size() + " frame(s)");
    return out.toArray(new byte[0][]);
}

/** Single-frame form kept for diagnostic probes; login uses itemPayloads(). */
static byte[] itemPayload() {
    byte[][] all = itemPayloads();
    if (all.length == 1) return all[0];
    int len = 0;
    for (byte[] b : all) len += b.length;
    ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
    for (byte[] x : all) b.put(x);
    return b.array();
}



/** Some screens read prism from ETB_Common_Fund 3, others from 2. */
public static volatile boolean fundPrismKey3 = true;

/** SC_FUNDINFORES(327): four-u32 header + rows of (u16 12 + id + amount). */
/** SC_FUNDINFORES(327): gold/prism rows derived from the currency ITEMS.
 *
 * The working build did not read AssetStore.funds() for this packet: it walked
 * the inventory, took every isCurrency(item.id) and turned it into a fund row
 * (id = fund key, amount = item count).  Building it from the funds list made
 * both the gold and prism counters show 0.
 */
/** SC_FUNDINFORES(327) -- format per _memory/BUG-001-CURRENCY-PACKET.md
 * and STAGE-43-FUND-KEYS.md (both measured on the device):
 *
 *   u32 count                      <- ONE u32 only (unlike 322/325)
 *   per row: u16 len(14) + 14 bytes
 *     +0x00 flags  = 0x02          <- bit1 = create
 *     +0x04 key    (u16)           <- ETB_Common_Fund: 1 = gold, 2 = prism
 *     +0x06 amount (u64, unaligned)
 *
 * The key is the FUND key, not the item id: the binary scan filled key k with
 * 1<<k and the gold counter read back 2 (= key 1) while the prism counter read
 * 12 (= key 2 + key 3).  Gold and prism are summed from every currency item.
 */
static byte[] fundPayload() {
    long gold = 0L;
    long prism = 0L;
    for (AssetStore.Item it : SessionState.get().items()) {
        if (!isCurrency(it.id)) continue;
        if (fundTypeOf(it.id) == 1) gold += it.count; else prism += it.count;
    }
    for (AssetStore.Fund f : SessionState.get().funds()) {
        if (fundTypeOf(f.id) == 1) gold += f.amount; else prism += f.amount;
    }
    int n = 0;
    if (gold > 0) n++;
    if (prism > 0) n++;
    if (prism > 0 && fundPrismKey3) n++;
    if (n == 0) return le32(0);
    ByteBuffer body = ByteBuffer.allocate(4 + n * 16).order(ByteOrder.LITTLE_ENDIAN);
    body.putInt(n);
    if (gold > 0) {
        byte[] rec = new byte[14];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.putShort(0, (short) fundRecordFlags);
        rb.putShort(4, (short) 1);
        rb.putLong(6, gold);
        body.putShort((short) 14);
        body.put(rec);
    }
    if (prism > 0) {
        byte[] rec = new byte[14];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.putShort(0, (short) fundRecordFlags);
        rb.putShort(4, (short) 2);
        rb.putLong(6, prism);
        body.putShort((short) 14);
        body.put(rec);
    }
    if (prism > 0 && fundPrismKey3) {
        // the shop panel reads ETB_Common_Fund 3, the top bar reads 2
        byte[] rec = new byte[14];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.putShort(0, (short) fundRecordFlags);
        rb.putShort(4, (short) 3);
        rb.putLong(6, prism);
        body.putShort((short) 14);
        body.put(rec);
    }
    Status.log("game: SC_FUNDINFORES " + n + " row(s) gold=" + gold + " prism=" + prism);
    return bodyArray(body);
}

/** Currency item ids that feed the gold / prism counters. */
static boolean isCurrency(int itemId) {
    return itemId == 101 || itemId == 102 || itemId == 103
            || itemId == 1001 || itemId == 54868;
}

/** Item id -> ETB_Common_Fund key (1 = gold, 2 = prism). */
static int fundTypeOf(int itemId) {
    // ETB_Common_Fund key: 101/1001 are gold, 102/103/54868 are prism.
    if (itemId == 101 || itemId == 1001) return 1;
    return 2;
}


/** Account misc info: kept as an empty, well-formed payload. */
/** SC_ACCOUNTMISCINFORES(320): 296-byte FGB_AccountMiscInfo.
 *
 * This is where the tutorial progress lives: byte 0x123 is "MainScene",
 * and 7 means "all tutorials finished".  Without it the client replays the
 * newbie tutorial even though every control works.
 */
/** SC_ACCOUNTMISCINFORES(320): 91-byte FGB_AccountMiscInfo + 16 tail bytes.
 *
 * Recovered from the working build (dexdump 009c404):
 *   new byte[91] filled 0x04, rec[0x56] = 7 when skipTutorial,
 *   payload = u16 91 | rec | 16 zero bytes   (allocate 109)
 *
 * Sending 296 bytes here aborted the client with
 * "FORTIFY: memcpy: prevented 296-byte write into 91-byte buffer".
 */
static byte[] accountMiscPayload() {
    byte[] rec = new byte[0x5B];
    java.util.Arrays.fill(rec, (byte) accountMiscFill);
    if (skipTutorial) {
        rec[0x56] = 7;
        // MainScene -> tutorial finished.
        //
        // CheckTutorialMainScene @0x07a19a38 does (MainScene + 1) & 0xffff,
        // then `cmp w8, #7` / `cset w0, lo`, so any value >= 6 already skips
        // the main-scene tutorial; 7 is the clamped maximum.
        //
        // The per-feature tutorials (Team, Fate, Collection, Circle, ...) are a
        // SEPARATE field: SubSceneFlag, the u32 at struct +0x57.  It is a bit
        // mask, one bit per ETB_Tutorial_SubScene:
        //
        //   GetTutorialProgressInAccountMiscInfo @0x07a1e90c
        //     0x7a1e970  add  x8, x8, #0x28f   ; SubSceneFlag
        //     0x7a1e980  ldr  w8, [x8]         ; u32
        //     0x7a1e984  str  w8, [x19, #0x30] ; -> progress+0x30
        //
        //   IsFinishedTutorialSubScene @0x07a1e370
        //     0x7a1e3a4  and  w8, w20, #0xff   ; scene index
        //     0x7a1e3a8  cmp  w8, #0x1f        ; only 0..31 supported
        //     0x7a1e3c0  ldr  w9, [x19, #0x30] ; the mask
        //     0x7a1e3c8  lsl  w8, w10, w8     ; 1 << index
        //     0x7a1e3cc  tst  w9, w8
        //     0x7a1e3d0  cset w0, ne          ; bit set -> already finished
        //
        // So all-ones means every sub-scene tutorial counts as finished, which
        // is what stops Team/Fate popping the first-time tutorial on every
        // restart.  It used to keep the 0x04 fill (0x04040404), whose low bits
        // only mark scenes 2 and 10 as done.
        putLe32(rec, 0x57, 0xFFFFFFFF);
    }
    ByteBuffer b = ByteBuffer.allocate(2 + rec.length + 16).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) rec.length);
    b.put(rec);
    b.put(new byte[16]);
    return bodyArray(b);
}


/** CS_Character2LevelUp(604): EXP chips in, cumulative EXP out. */
static int handleCharacterLevelUp(byte[] payload) {
    int charId = growthCharOf(payload);
    if (charId <= 0) { Status.log("growth: levelup with unreadable char id"); return 0; }
    SaveStore save = SaveStore.get();
    if (!save.ownsCharacter(charId)) { Status.log("growth: levelup for unowned " + charId); return 0; }
    SaveStore.CharGrowth g = save.growthOf(charId);
    // costs: u16 len(8) + u32 itemId + u32 count, repeated `consumeCount` times
    int consumeCount = payload.length >= 10 ? rd32(payload, 6) : 0;
    int p = 10;
    int gain = 0;
    for (int i = 0; i < consumeCount && p + 2 <= payload.length; i++) {
        int entLen = (payload[p] & 0xFF) | ((payload[p + 1] & 0xFF) << 8);
        p += 2;
        if (entLen >= 8 && p + 8 <= payload.length) {
            int itemId = rd32(payload, p);
            int cnt = rd32(payload, p + 4);
            gain += expOfItem(itemId) * Math.max(0, cnt);
            // chips stay pinned at 99: never remove them from the session
            SessionState.get().addItem(itemId, -Math.max(0, cnt));
        }
        p += entLen;
    }
    if (charLevel > g.level) g.level = charLevel;
    int before = g.level;
    g.exp += gain;
    int column = LevelCurve.columnFor(g.charClass > 1 ? g.charClass : charClassId);
    int levels = 0;
    while (g.level < 90) {
        int need = LevelCurve.need(column, g.level);
        if (need <= 0 || g.exp < need) break;
        g.exp -= need;
        g.level++;
        levels++;
    }
    save.setGrowth(charId, g);
    Status.log("growth: character " + charId + " exp +" + gain + " -> " + g.exp
            + ", level " + before + " -> " + g.level + " (+" + levels + ")");
    return charId;
}

// ---- post-level-up character re-push ----------------------------------
//
// Measured behaviour: CS_Character2LevelUp(604) -> 605 + 369 both carry the
// right level/exp and the 369 record carries the seven skill levels at +0x24,
// yet the skill panel reads "not opened / no level" right after the popup.
// The client recalculates skill state on a level change and drops anything it
// cannot resolve, and the skill table it would resolve against is one of the
// .tbd files that ships unparsed.  The record replacement is idempotent here,
// so sending it again after the client has finished its pass restores the
// levels; a few attempts cover the window without touching the client.
public static volatile int pendingCharResend = 0;
public static volatile int pendingCharResendLeft = 0;

static void resendCharacterUpdateLater(int charId) {
    if (charId <= 0) return;
    pendingCharResend = charId;
    // The client sends several packets right after a level-up (popup confirm,
    // focus changes, keepalives), so a handful of re-pushes spread across those
    // replies wins the race without a second thread and without an anonymous
    // class -- javac's $1 synthetics are excluded from the dex on purpose.
    pendingCharResendLeft = 6;
}

static int expOfItem(int itemId) {
    switch (itemId) {
        case 11001: return 50;
        case 11002: return 200;
        case 11003: return 800;
        case 11004: return 3200;
        default: return 0;
    }
}
public static volatile boolean lateItemResend = false;
public static volatile int keepaliveCount = 0;

/** SC_STAGEINFORES(322) chunks.  Empty until the story table is rebuilt. */
/** SC_CHAPTERINFORES(324).  Zero rows: the client crashes on this one. */
static byte[] chapterPayload() { return le32(0); }

/**
 * SC_Character2LevelUpRes(605): 32-byte info + item list.
 * Reader @0x07c2eb3c ignores anything shorter than 0x21 bytes, then copies the
 * 32-byte struct and calls SetCharacterLevelUpInfo (level +0x0F, exp +0x11 u64).
 */
static byte[] levelUpResPayload(int charId, int level, int exp) {
    byte[] st = new byte[32];
    st[1] = (byte) (charId & 0xFF);
    st[2] = (byte) ((charId >> 8) & 0xFF);
    st[3] = (byte) ((charId >> 16) & 0xFF);
    st[4] = (byte) ((charId >> 24) & 0xFF);
    st[0x0F] = (byte) (level & 0xFF);
    st[0x10] = (byte) ((level >> 8) & 0xFF);
    long total = (long) LevelCurve.threshold(LevelCurve.columnFor(charClassId), level) + exp;
    for (int k = 0; k < 8; k++) st[0x11 + k] = (byte) ((total >> (8 * k)) & 0xFF);
    // The seven skill levels ride INSIDE this struct.  Disassembly of
    // UCMCharInfoManager::SetCharacterLevelUpInfo @0x079762c0:
    //   ldrb w8,[x1,#0x19] ; strb w8,[x0,#0x4c]   ... through
    //   ldrb w8,[x1,#0x1f] ; strb w8,[x0,#0x52]
    // i.e. struct+0x19..0x1F are copied straight over the character's skill
    // slots.  Leaving them zero -- which this payload used to do -- zeroed
    // every skill on each level-up, which is exactly the "no unlock / no
    // level" report.
    for (int k = 0; k < 7 && k < charSkills.length; k++) {
        st[0x19 + k] = (byte) charSkills[k];
    }
    ByteBuffer b = ByteBuffer.allocate(2 + 32 + 4).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) 32);
    b.put(st);
    b.putInt(0);
    return bodyArray(b);
}

/** SC_STAGEINFORES(322): chunks of 44-byte FGB_StageInfo records.
 *
 * _memory/STAGE-34-CHAPTERS-UNLOCKED.md + STAGE-35-STARS-CLEARED.md:
 *   +0x00 flags = 0x02 (add)   +0x04 stage id   +0x08..+0x2B clear/star data
 * Sending the id as a string made every row parse as "discard" (the first
 * byte of "S..." sets bit6), which is why chapters stayed locked.
 * A single packet over 16 KiB hangs the client, so the list is chunked.
 */
static byte[][] stagePayloads() {
    java.util.List<String> list = new java.util.ArrayList<String>();
    for (String id : STAGE_IDS) list.add(id);
    if (exStages) for (String id : EX_STAGE_IDS) list.add(id);
    // Character side-stories are DIFFERENT from the main story.  A 322 row
    // for a side-story stage means "already cleared" and the client closes
    // the entry (진입 불가).  So only stages the player actually cleared are
    // registered; the next stage is left out, which is exactly what unlocks
    // it (the client checks "previous stage has a clear record").  The
    // baseline comes from characterClearCount (web console) and thereafter
    // from the live NarrativeProgress saved when CS_GameFinish(102) arrives.
    if (characterStages) {
        int limit = characterStageLimit < 0 ? CHARACTER_STAGE_IDS.length
                : Math.min(characterStageLimit, CHARACTER_STAGE_IDS.length);
        for (int i = 0; i < limit; i++) {
            if (i % 8 < narrativeClearedCount(i / 8)) {
                list.add(CHARACTER_STAGE_IDS[i]);
            }
        }
    }
    if (narrativeStages) for (String id : NARRATIVE_STAGE_IDS) list.add(id);
    int total = stageLimit < 0 ? list.size() : Math.min(stageLimit, list.size());
    java.util.List<byte[]> out = new java.util.ArrayList<byte[]>();
    for (int off = 0; off < total; off += 320) {
        int n = Math.min(320, total - off);
        ByteBuffer b = ByteBuffer.allocate(16 + n * 46).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(n);
        b.putInt(0);
        b.putInt(n);
        b.putInt(n);
        for (int i = 0; i < n; i++) {
            byte[] rec = new byte[44];
            ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            rb.putInt(0, stageRecordFlags);
            rb.putInt(4, Integer.parseInt(list.get(off + i)));
            for (int k = 8; k < rec.length; k++) rec[k] = (byte) stageClearProbe;
            b.putShort((short) rec.length);
            b.put(rec);
        }
        out.add(bodyArray(b));
    }
    Status.log("game: SC_STAGEINFORES " + total + " stage row(s) in " + out.size() + " chunk(s)");
    return out.toArray(new byte[0][]);
}

/** The single narrative-mode stage id (801002), kept for completeness. */
public static final String[] NARRATIVE_STAGE_IDS = "801002".split(",");
static final int SC_CHARACTER2UPDATERES = 369;

/** Character id out of a length-prefixed growth request (u16 len + u32 id). */
static int growthCharOf(byte[] payload) {
    if (payload == null || payload.length < 6) return 0;
    int id = rd32(payload, 2);
    if (id > 0) lastFocusedCharacter = id;
    return id;
}

/** CS_Character2ClassUp(606): 진급. */
static int handleCharacterClassUp(byte[] payload) {
    int charId = growthCharOf(payload);
    if (charId <= 0) { Status.log("growth: classup with unreadable char id"); return 0; }
    SaveStore save = SaveStore.get();
    if (!save.ownsCharacter(charId)) { Status.log("growth: classup for unowned " + charId); return 0; }
    SaveStore.CharGrowth g = save.growthOf(charId);
    if (g.charClass >= 11) { Status.log("growth: classup " + charId + " already at max class"); return 0; }
    int before = g.charClass;
    g.charClass = before + 1;
    save.setGrowth(charId, g);
    Status.log("growth: character " + charId + " class " + before + " -> " + g.charClass + " (saved)");
    return charId;
}

/** CS_Character2SkillUp(608): one skill level. */
static int handleCharacterSkillUp(byte[] payload) {
    int charId = growthCharOf(payload);
    if (charId <= 0) return 0;
    SaveStore save = SaveStore.get();
    SaveStore.CharGrowth g = save.growthOf(charId);
    int idx = payload != null && payload.length >= 10 ? rd32(payload, 6) : 0;
    if (idx >= 0 && idx < 7 && g.skills[idx] < 20) {
        g.skills[idx] = g.skills[idx] + 1;
        save.setGrowth(charId, g);
        Status.log("growth: skill " + idx + " -> " + g.skills[idx] + " for " + charId);
    }
    return charId;
}

/** CS_ItemUse(502): consume one of the item. */
static int handleItemUse(byte[] payload) {
    if (payload == null || payload.length < 6) return 0;
    int itemId = rd32(payload, 2);
    SessionState.get().addItem(itemId, -1);
    Status.log("item: used " + itemId);
    return itemId;
}

/** CS_ItemEquip(506) / CS_ItemUnequip(508): remember the character. */
static int handleItemEquip(byte[] payload) { return growthCharOf(payload); }


public static volatile int lastFocusedCharacter = 0;
/** SC_Character2UpdateRes(369): one character record, four-u32 header. */
/** Record flags for a post-level-up refresh.  Measured from the client:
 *  SetCharacterInfo branches on record[0] -- bit1 (0x02) is the INSERT path
 *  (it creates the entry and then maps it), bit3 (0x08) is the in-place
 *  update.  Re-inserting after a level-up is what dropped the skill unlock
 *  state, so refreshes use the update form. */
public static volatile int charUpdateFlags = 0x08;

static void sendCharacterUpdate(OutputStream out, long session, int charId) throws Exception {
    sendCharacterUpdate(out, session, charId, charRecordFlags);
}

static void sendCharacterUpdate(OutputStream out, long session, int charId,
                                int flags) throws Exception {
    sendCharacterUpdate(out, session, charId, flags, -1, -1, -1);
}

/** Full-control variant used by the /api/charpush diagnostic.  -1 keeps the
 *  stored/default value for class, skill level and level. */
static void sendCharacterUpdate(OutputStream out, long session, int charId,
                                int flags, int classOverride, int skillOverride,
                                int levelOverride) throws Exception {
    SaveStore.CharGrowth g = SaveStore.get().growthOf(charId);
    byte[] rec = new byte[48];
    ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
    rb.putInt(0, flags);
    rb.putInt(4, charId);
    int recClassU = classOverride >= 0 ? classOverride
            : (g.charClass > 1 ? g.charClass : charClassId);
    int recLevelU = levelOverride >= 0 ? levelOverride
            : (g.level > 1 ? g.level : charLevel);
    rb.put(8, (byte) recClassU);
    rb.putShort(0x0D, (short) recLevelU);
    rb.putLong(0x0F, (long) (LevelCurve.threshold(LevelCurve.columnFor(g.charClass > 1 ? g.charClass : charClassId),
            g.level > 1 ? g.level : charLevel) + (g.level > 1 ? g.exp : charExp)));
    for (int i = 0; i < 7 && i < charSkills.length; i++) {
        rb.put(0x24 + i, (byte) (skillOverride >= 0 ? skillOverride : charSkills[i]));
    }
    rb.putShort(0x17, (short) (g.favorLevel > 1 ? g.favorLevel
            : (SaveStore.isNarrative(charId) ? charFavorLevel : 1)));
    rb.putInt(0x19, g.favorExp);   // 0 is a valid level-start value
    // Same rule as 326 / 776: this record REPLACES the character, so the
    // costume field has to ride along or the client resets it to outfit 1.
    Integer cos = costumeOf.get(charId);
    if (cos != null && CostumeCatalog.owns(charId, cos)) rb.putInt(0x2B, cos);
    ByteBuffer body = ByteBuffer.allocate(16 + 2 + rec.length).order(ByteOrder.LITTLE_ENDIAN);
    body.putInt(1);
    body.putInt(0);
    body.putInt(1);
    body.putInt(1);
    body.putShort((short) rec.length);
    body.put(rec);
    send(out, SC_CHARACTER2UPDATERES, session, bodyArray(body));
    Status.log("game -> SC_CHARACTER2UPDATERES(369) char " + charId + " lv" + g.level + " exp" + g.exp);
}
/** EXP chips are pinned at 99 so levelling never consumes them. */
public static final int CHIP_KEEP = 99;

static byte[] itemInfoRecord(int itemId, int count) {
    byte[] rec = new byte[63];
    ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
    rb.putInt(0, isExpChip(itemId) ? chipLoginFlags : itemRecordFlags);
    rb.putInt(4, itemId);
    rb.putInt(8, count);
    rb.put(12, (byte) itemCategoryOf(itemId));
    // id @0x0E is a 4-byte field: writing the count at 0x10 clobbered its
    // high bytes and every item came up as a blank icon.
    rb.putInt(0x0E, itemId);
    if (isCurrency(itemId)) {
        // Currency is read as a u64 starting at +0x12: keep the low half =
        // amount and the high half zero, otherwise the counter overflows.
        rb.putInt(0x12, count);
    } else {
        rb.putInt(0x12, count);
        rb.putInt(0x16, count);
        rb.putInt(0x1A, count);
        rb.putInt(0x1E, count);
    }
    return rec;
}

static byte[] bossInfoOpenResPayload() {
    ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0);
    return bodyArray(b);
}

static byte[] bossRankResPayload() {
    ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0);
    return bodyArray(b);
}

/** Weighted random character id from the summon pool. */
public static final java.util.Random RND = new java.util.Random();
public static volatile int duplicateChipCount = 10;

static int drawSummonCharacter() {
    String[] pool = SUMMON_POOL.split(",");
    int idx = RND.nextInt(pool.length);
    if (idx >= pool.length) idx = pool.length - 1;
    try {
        return Integer.parseInt(pool[idx].trim());
    } catch (NumberFormatException e) {
        return 4105;
    }
}

/** A duplicate pull becomes a universal fragment. */
static void awardDuplicateCharacter(int cid) {
    SessionState.get().addItem(11401, duplicateChipCount);
    Status.log("gacha: duplicate " + cid + " -> chip 11401 x" + duplicateChipCount);
}

/**
 * SC_RewardDisplayRes(20) for the summon page, restored from the pre-damage
 * build (dex @0x00e860):
 *   u16 1 | u8 20 | u32 rowCount |
 *   rowCount * (u16 13 + {u8 2, u32 charId, u64 0}) | four trailing u32 zeros
 * This packet is what the summon result page animates; 653 alone shows nothing.
 */
static byte[] summonRewardDisplayPayload() {
    int slots = 10;
    try {
        String env = System.getenv("CLOSERS_SUMMON_SLOTS");
        if (env != null) {
            int v = Integer.parseInt(env.trim());
            if (v > 0 && v <= 50) slots = v;
        }
    } catch (Exception ignored) { }
    int[] chars = new int[slots];
    for (int k = 0; k < slots; k++) chars[k] = drawSummonCharacter();

    SaveStore store = SaveStore.get();
    java.util.List<int[]> rows = new java.util.ArrayList<int[]>();
    int dup = 0;
    for (int k = 0; k < slots; k++) {
        int cid = chars[k];
        boolean added = store.addCharacter(cid, charLevel);
        rows.add(new int[] { cid, 1 });
        if (!added) {
            dup++;
            awardDuplicateCharacter(cid);
        }
    }
    if (dup > 0) {
        Status.log("gacha: " + dup + "/" + slots + " duplicate pull(s) -> 11401 x" + duplicateChipCount);
    }

    ByteBuffer b = ByteBuffer.allocate(rows.size() * 15 + 7 + 16).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) 1);
    b.put((byte) 20);
    b.putInt(rows.size());
    for (int[] r : rows) {
        byte[] rec = new byte[13];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.put(0, (byte) 2);
        rb.putInt(1, r[0]);
        rb.putLong(5, 0L);
        b.putShort((short) rec.length);
        b.put(rec);
    }
    b.putInt(0);
    b.putInt(0);
    b.putInt(0);
    b.putInt(0);
    Status.log("gacha: reward display " + rows.size() + " row(s)");
    return b.array();
}

public static volatile String charList = "";
public static volatile int ACCOUNT_PROGRESS = 100;
public static volatile int ACCOUNT_EXP = 100000;
public static volatile int ACCOUNT_EXP_OFFSET = 0x66;
public static volatile int battleGoldDrop = 0;
public static volatile int battleExpDrop = 0;
public static volatile int battleExpItem = 0;
public static volatile int organizeSlotBase = 0;
static int advanceStoryByStage(int stage) { SaveStore.get().setStoryProgress(1, stage); return stage; }
public static volatile int charRecordFlags = 0x02;
public static volatile int charClassId = 1;
public static volatile int charLevel = 30;
public static volatile int charExp = 0;
public static volatile int charFragCount = 500;
public static volatile boolean charSendSkills = true;
// Every character's seven skill slots.  The operator's default is "all skills
// at level 3", which used to be set by hand through /api/charskills and was
// lost on every proxy restart because nothing persisted it.  Persisted in
// PREF_KNOBS (see loadKnobs/saveKnobs) so a restart keeps the choice.
public static volatile int[] charSkills = { 3, 3, 3, 3, 3, 3, 3 };
public static volatile boolean charSendFavor = true;
public static volatile int charFavorLevel = SaveStore.DEFAULT_FAVOR_LEVEL;
public static volatile int charFavorExp = 100;
public static volatile int levelOffset = 13;
public static volatile boolean charSentinels = false;
public static volatile boolean charExpProbe = false;
public static volatile int itemRecordFlags = 0x02;
public static volatile int expChipCount = 99;

// ---- knob persistence -------------------------------------------------
// The web console writes these through static fields only, so every restart
// silently reverted them to the compiled defaults.  That is what made "all
// characters have skill level 3" appear to stop working.  Persist the ones an
// operator actually sets.
static final String PREF_KNOBS = "closersrt_knobs";

static String joinInts(int[] a) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < a.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(a[i]);
    }
    return sb.toString();
}

static int[] parseInts(String raw, int[] fallback) {
    if (raw == null || raw.trim().isEmpty()) return fallback;
    String[] parts = raw.trim().split(",");
    int[] out = new int[fallback.length];
    for (int i = 0; i < out.length; i++) {
        out[i] = fallback[i];
        if (i < parts.length) {
            try { out[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException ignored) { }
        }
    }
    return out;
}

/** Persist the operator-facing knobs. Safe to call often; never throws. */
public static void saveKnobs() {
    try {
        android.content.Context ctx = SaveStore.context();
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF_KNOBS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("charSkills", joinInts(charSkills))
                .putInt("charLevel", charLevel)
                .putInt("charClassId", charClassId)
                .putInt("charFavorLevel", charFavorLevel)
                .putInt("levelOffset", levelOffset)
                .putInt("expChipCount", expChipCount)
                .putBoolean("charSendSkills", charSendSkills)
                .putBoolean("charSendFavor", charSendFavor)
                .putInt("contentPackets", contentPackets)
                .apply();
    } catch (Throwable t) {
        Status.log("knobs: save failed " + t);
    }
}

/** Restore persisted knobs. Call once during startup, before the server runs. */
public static void loadKnobs() {
    try {
        android.content.Context ctx = SaveStore.context();
        if (ctx == null) return;
        android.content.SharedPreferences p =
                ctx.getSharedPreferences(PREF_KNOBS, android.content.Context.MODE_PRIVATE);
        charSkills = parseInts(p.getString("charSkills", null), charSkills);
        charLevel = p.getInt("charLevel", charLevel);
        charClassId = p.getInt("charClassId", charClassId);
        charFavorLevel = p.getInt("charFavorLevel", charFavorLevel);
        levelOffset = p.getInt("levelOffset", levelOffset);
        expChipCount = p.getInt("expChipCount", expChipCount);
        charSendSkills = p.getBoolean("charSendSkills", charSendSkills);
        charSendFavor = p.getBoolean("charSendFavor", charSendFavor);
        contentPackets = p.getInt("contentPackets", contentPackets);
        Status.log("knobs: restored charSkills=" + java.util.Arrays.toString(charSkills)
                + " contentPackets=0x" + Integer.toHexString(contentPackets));
    } catch (Throwable t) {
        Status.log("knobs: load failed " + t);
    }
}
// ---- remaining knobs referenced by the web console -------------------
public static volatile int fundRecordFlags = 0x02;
public static volatile int fundTypeId = 1;
public static volatile boolean FUND_RECORDS_DISABLED = false;
public static volatile boolean fundSendRecords = true;
public static volatile int STAGE_KEY_MODE = 0;
// 3 = cleared + all three battle conditions met (STAGE-35 measured this on the device).
public static volatile int stageClearProbe = 3;
public static volatile int stageRecordFlags = 0x02;
/** The 352 SC_STAGEINFORES ids, in the order the client expects (taken
 *  from the working build's static initialiser). */
/** The hard-mode (困難) stages 4-13, extracted from the working build. */
public static final String[] EX_STAGE_IDS = "10104501,10104502,10104503,10104504,10104505,10104506,10104507,10104508,10104509,10104510,10104511,10104512,10104513,10104514,10104515,10104516,10104517,10104518,10104519,10104520,10105501,10105502,10105503,10105504,10105505,10105506,10105507,10105508,10105509,10105510,10105511,10105512,10105513,10105514,10105515,10105516,10105517,10105518,10105519,10105520,10105521,10105522,10105523,10105524,10105525,10106501,10106502,10106503,10106504,10106505,10106506,10106507,10106508,10106509,10106510,10106511,10106512,10106513,10106514,10106515,10106516,10106517,10106518,10106519,10106520,10106521,10106522,10106523,10106524,10106525,10107501,10107502,10107503,10107504,10107505,10107506,10107507,10107508,10107509,10107510,10107511,10107512,10107513,10107514,10107515,10107516,10107517,10107518,10107519,10107520,10107521,10107522,10107523,10107524,10107525,10108501,10108502,10108503,10108504,10108505,10108506,10108507,10108508,10108509,10108510,10108511,10108512,10108513,10108514,10108515,10108516,10108517,10108518,10108519,10108520,10108521,10108522,10108523,10108524,10108525,10109501,10109502,10109503,10109504,10109505,10109506,10109507,10109508,10109509,10109510,10109511,10109512,10109513,10109514,10109515,10109516,10109517,10109518,10109519,10109520,10109521,10109522,10109523,10109524,10109525,10110501,10110502,10110503,10110504,10110505,10110506,10110507,10110508,10110509,10110510,10110511,10110512,10110513,10110514,10110515,10110516,10110517,10110518,10110519,10110520,10110521,10110522,10110523,10110524,10110525,10111501,10111502,10111503,10111504,10111505,10111506,10111507,10111508,10111509,10111510,10111511,10111512,10111513,10111514,10111515,10111516,10111517,10111518,10111519,10111520,10111521,10111522,10111523,10111524,10111525,10112501,10112502,10112503,10112504,10112505,10112506,10112507,10112508,10112509,10112510,10112511,10112512,10112513,10112514,10112515,10112516,10112517,10112518,10112519,10112520,10112521,10112522,10112523,10112524,10112525,10113501,10113502,10113503,10113504,10113505,10113506,10113507,10113508,10113509,10113510,10113511,10113512,10113513,10113514,10113515,10113516,10113517,10113518,10113519,10113520,10113521,10113522,10113523,10113524,10113525".split(",");
public static final String[] STAGE_IDS = "10101001,10101002,10101003,10101004,10102001,10102002,10102003,10102004,10102005,10102006,10102007,10102008,10102009,10102010,10102901,10102902,10102903,10102904,10103001,10103002,10103003,10103004,10103005,10103006,10103007,10103008,10103009,10103010,10103011,10103012,10103013,10103014,10103015,10103901,10103902,10103903,10103904,10104001,10104002,10104003,10104004,10104005,10104006,10104007,10104008,10104009,10104010,10104011,10104012,10104013,10104014,10104015,10104016,10104017,10104018,10104019,10104020,10104901,10104902,10104903,10104904,10105001,10105002,10105003,10105004,10105005,10105006,10105007,10105008,10105009,10105010,10105011,10105012,10105013,10105014,10105015,10105016,10105017,10105018,10105019,10105020,10105021,10105022,10105023,10105024,10105025,10105901,10105902,10105903,10105904,10106001,10106002,10106003,10106004,10106005,10106006,10106007,10106008,10106009,10106010,10106011,10106012,10106013,10106014,10106015,10106016,10106017,10106018,10106019,10106020,10106021,10106022,10106023,10106024,10106025,10106901,10106902,10106903,10106904,10107001,10107002,10107003,10107004,10107005,10107006,10107007,10107008,10107009,10107010,10107011,10107012,10107013,10107014,10107015,10107016,10107017,10107018,10107019,10107020,10107021,10107022,10107023,10107024,10107025,10107901,10107902,10107903,10107904,10108001,10108002,10108003,10108004,10108005,10108006,10108007,10108008,10108009,10108010,10108011,10108012,10108013,10108014,10108015,10108016,10108017,10108018,10108019,10108020,10108021,10108022,10108023,10108024,10108025,10108901,10108902,10108903,10108904,10109001,10109002,10109003,10109004,10109005,10109006,10109007,10109008,10109009,10109010,10109011,10109012,10109013,10109014,10109015,10109016,10109017,10109018,10109019,10109020,10109021,10109022,10109023,10109024,10109025,10109901,10109902,10109903,10109904,10110001,10110002,10110003,10110004,10110005,10110006,10110007,10110008,10110009,10110010,10110011,10110012,10110013,10110014,10110015,10110016,10110017,10110018,10110019,10110020,10110021,10110022,10110023,10110024,10110025,10110901,10110902,10110903,10110904,10111001,10111002,10111003,10111004,10111005,10111006,10111007,10111008,10111009,10111010,10111011,10111012,10111013,10111014,10111015,10111016,10111017,10111018,10111019,10111020,10111021,10111022,10111023,10111024,10111025,10111901,10111902,10111903,10111904,10112001,10112002,10112003,10112004,10112005,10112006,10112007,10112008,10112009,10112010,10112011,10112012,10112013,10112014,10112015,10112016,10112017,10112018,10112019,10112020,10112021,10112022,10112023,10112024,10112025,10112901,10112902,10112903,10112904,10113001,10113002,10113003,10113004,10113005,10113006,10113007,10113008,10113009,10113010,10113011,10113012,10113013,10113014,10113015,10113016,10113017,10113018,10113019,10113020,10113021,10113022,10113023,10113024,10113025,10113901,10113902,10113903,10113904,10115001,10115002,10115003,10115004,10115005,10115006,10115007,10115008,10115009,10115010,10115011,10115012,10115013,10115014,10115015,10115016,10115017,10115018,10115019,10115020,10115021,10115022,10115023,10115024,10115025,40101001,40101002,40101003,40101004,40101005".split(",");
/** The 216 character side-story stage ids (8_CCCC_SS), 8 per character.
 *  Recovered from the working proxy's static initialiser on 2026-10-21 and
 *  re-checked against NarrativeMode_Info.tbd.  The ids are NOT contiguous, so
 *  they are stored literally instead of generated from a range. */
public static final String[] CHARACTER_STAGE_IDS = (
    "8410701,8410702,8410703,8410704,8410705,8410706,8410707,8410708,"
  + "8410401,8410402,8410403,8410404,8410405,8410406,8410407,8410408,"
  + "8430301,8430302,8430303,8430304,8430305,8430306,8430307,8430308,"
  + "8430401,8430402,8430403,8430404,8430405,8430406,8430407,8430408,"
  + "8420401,8420402,8420403,8420404,8420405,8420406,8420407,8420408,"
  + "8440301,8440302,8440303,8440304,8440305,8440306,8440307,8440308,"
  + "8420501,8420502,8420503,8420504,8420505,8420506,8420507,8420508,"
  + "8410301,8410302,8410303,8410304,8410305,8410306,8410307,8410308,"
  + "8450301,8450302,8450303,8450304,8450305,8450306,8450307,8450308,"
  + "8430601,8430602,8430603,8430604,8430605,8430606,8430607,8430608,"
  + "8420301,8420302,8420303,8420304,8420305,8420306,8420307,8420308,"
  + "8440501,8440502,8440503,8440504,8440505,8440506,8440507,8440508,"
  + "8430901,8430902,8430903,8430904,8430905,8430906,8430907,8430908,"
  + "8450401,8450402,8450403,8450404,8450405,8450406,8450407,8450408,"
  + "8431001,8431002,8431003,8431004,8431005,8431006,8431007,8431008,"
  + "8410801,8410802,8410803,8410804,8410805,8410806,8410807,8410808,"
  + "8420701,8420702,8420703,8420704,8420705,8420706,8420707,8420708,"
  + "8410501,8410502,8410503,8410504,8410505,8410506,8410507,8410508,"
  + "8420801,8420802,8420803,8420804,8420805,8420806,8420807,8420808,"
  + "8410601,8410602,8410603,8410604,8410605,8410606,8410607,8410608,"
  + "8430801,8430802,8430803,8430804,8430805,8430806,8430807,8430808,"
  + "8430701,8430702,8430703,8430704,8430705,8430706,8430707,8430708,"
  + "8440401,8440402,8440403,8440404,8440405,8440406,8440407,8440408,"
  + "8430501,8430502,8430503,8430504,8430505,8430506,8430507,8430508,"
  + "8420601,8420602,8420603,8420604,8420605,8420606,8420607,8420608,"
  + "8440601,8440602,8440603,8440604,8440605,8440606,8440607,8440608,"
  + "8450501,8450502,8450503,8450504,8450505,8450506,8450507,8450508")
    .replace("\n", "").split(",");

public static volatile int stageLimit = -1;
public static volatile boolean narrativeStages = false;
public static volatile boolean exStages = true;
public static volatile boolean characterStages = true;
public static volatile int characterStageLimit = -1;
public static volatile boolean characterStarOnly = false;
public static volatile boolean characterZero = false;
/** Baseline: how many stages of every character group are pre-cleared with
 *  3 stars.  8 = all 216 side-story stages are cleared and unlocked.  This is
 *  safe because the libUE4 REPLAY patch (0x082E0F94) keeps a cleared stage
 *  enterable, so "all cleared" no longer means "진입 불가".
 *  Runtime: POST /api/stage?clear=N  (0..8) */
public static volatile int characterClearCount = 8;
public static volatile int characterEntryRemain = -1;
public static volatile int characterEntryMax = -1;
public static volatile long characterStamp = 0L;
public static volatile int characterPlayCount = 0;
public static volatile int characterClearUsed = 0;
public static volatile int hostPort = 8500;
public static volatile int connectionCount = 0;
public static volatile int characterChipCount = 500;

public static java.util.Map<Integer, Integer> characterChips() { return costumeOf; }
public static int setCharacterChip(int charId, int count) { charFragCount = count; return count; }
public 
/** SC_FacilityFavorGiftRes(447): fixed 0x50 struct + empty fund/item lists. */
static byte[] favorGiftRes(int cid) {
    ByteBuffer body = ByteBuffer.allocate(2 + 0x50 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
    body.putShort((short) 0x50);
    body.put(new byte[0x50]);
    body.putInt(0);
    body.putInt(0);
    return bodyArray(body);
}

/** SC_FacilityOrganizeSetSlotRes: echo a zeroed 12-byte info block. */
static byte[] organizeSetSlotRes(byte[] payload) {
    ByteBuffer body = ByteBuffer.allocate(2 + 12).order(ByteOrder.LITTLE_ENDIAN);
    body.putShort((short) 12);
    body.put(new byte[12]);
    return bodyArray(body);
}

/** Shop purchase: the account already owns everything, so nothing to do. */
public static volatile int shopPurchaseCount = 1;

/** True when the id exists in Item.tbd (the catalogue the client knows). */
static boolean isKnownItem(int id) {
    if (id <= 0) return false;
    String key = String.valueOf(id) + "|";
    for (String row : ItemCatalog.RAW) {
        if (row.startsWith(key)) return true;
    }
    return false;
}

/**
 * CS_ShopPurchasing(1203), restored from the pre-damage build:
 *   payload = u16 len | u32 shopId | u32 goodsId | ...
 * The goods id is whichever of +4 / +6 is a real Item.tbd id.
 */
/** shopId -> {rewardItemId, count}.  The shop sells "Bits" packages whose
 *  amounts come from Shop_Base.tbd names (Bits5000 / Bits24200 / Bits53240);
 *  the exact shopId mapping can be corrected at runtime with /api/shopmap. */
public static final java.util.Map<Integer, int[]> SHOP_MAP =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, int[]>());
/** shopId -> {costItem, cost, rewardItem, reward}; gold packages cost prism. */
static {
    // observed live (2026-11-05), cheapest first: prism cost -> gold granted
    SHOP_MAP.put(3424, new int[] { 102, 100, 101, 10000 });
    SHOP_MAP.put(3425, new int[] { 102, 300, 101, 30000 });
    SHOP_MAP.put(3426, new int[] { 102, 500, 101, 52500 });
    SHOP_MAP.put(3427, new int[] { 102, 1000, 101, 107000 });
    SHOP_MAP.put(3428, new int[] { 102, 2000, 101, 220000 });
}

/**
 * CS_ShopPurchasing(1203).  Wire layout from the live capture:
 *   08 00 | 61 0d | 03 00 | 01 00 00 00
 *   u16 len | u16 shopId | u16 goodsId | u32 count
 * The old code guessed u32 fields and ended up awarding item 1 (a character
 * piece), which is why every purchase showed "1".
 */
/**
 * CS_ShopPurchasing(1203): 08 00 | 61 0d | 03 00 | 01 00 00 00
 *   u16 len | u16 shopId | u16 goodsId | u32 count
 * SHOP_MAP holds {costItem, cost, rewardItem, reward} per shop id.
 * Unknown ids award nothing -- the earlier fallback handed out item 1, which
 * is why every purchase looked like "1 item".
 */
static int[] recordShopPurchase(byte[] payload) {
    if (payload == null || payload.length < 10) {
        Status.log("shop: payload too short (" + (payload == null ? 0 : payload.length) + "B)");
        return new int[0];
    }
    int shopId = (payload[2] & 0xff) | ((payload[3] & 0xff) << 8);
    int goodsId = (payload[4] & 0xff) | ((payload[5] & 0xff) << 8);
    int count = rd32(payload, 6);
    if (count <= 0) count = 1;
    int[] map = SHOP_MAP.get(shopId);
    if (map != null && map.length >= 4) {
        int costItem = map[0], cost = map[1] * count;
        int rewardItem = map[2], amount = map[3] * count;
        SessionState st = SessionState.get();
        st.addItem(costItem, -cost);
        st.addFund(costItem, -cost);
        st.addItem(rewardItem, amount);
        st.addFund(rewardItem, amount);
        Status.log("shop: shop " + shopId + " -> -" + cost + " (item " + costItem
                + "), +" + amount + " (item " + rewardItem + ") (saved)");
        return new int[] { rewardItem, amount };
    }
    Status.log("shop: UNMAPPED shop=" + shopId + " goods=" + goodsId + " count=" + count);
    return new int[0];
}

/** Reward display rows for the shop/summon reply. */
/**
 * SC_RewardDisplayRes(20) for a shop purchase, restored from the pre-damage
 * build (dex @0x00e4b8):
 *   u16 1 | u8 20 | u32 rowCount |
 *   rowCount * (u16 13 + {u8 3, u32 id, u64 count}) | four trailing u32 zeros
 * The record type byte is 3 for a shop item (summons use 2).
 */
static byte[] shopRewardDisplayPayload(int[] bought) {
    java.util.List<int[]> rows = new java.util.ArrayList<int[]>();
    if (bought != null && bought.length > 0 && bought[0] > 0) {
        int qty = bought.length > 1 ? Math.max(1, bought[1]) : 1;
        rows.add(new int[] { bought[0], qty });
    } else {
        rows.add(new int[] { 101, 0 });
    }
    ByteBuffer b = ByteBuffer.allocate(rows.size() * 15 + 7 + 16).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) 1);
    b.put((byte) 20);
    b.putInt(rows.size());
    for (int[] r : rows) {
        byte[] rec = new byte[13];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        boolean currency = r[0] == 101 || r[0] == 102 || r[0] == 103;
        rb.put(0, (byte) (currency ? 1 : 3));
        rb.putInt(1, r[0]);
        rb.putLong(5, Math.max(0L, (long) r[1]));
        b.putShort((short) rec.length);
        b.put(rec);
    }
    b.putInt(0);
    b.putInt(0);
    b.putInt(0);
    b.putInt(0);
    Status.log("game: SC_REWARDDISPLAYRES type=20 (shop purchase), " + rows.size() + " row(s)");
    return b.array();
}

/** SC_ITEMUPDATERES(357): push one item row (id + current count). */
/** Re-send the four EXP chips as a 325 list with update flags. */
static void pushChipList(OutputStream out, long session) throws Exception {
    int[] chips = { 11001, 11002, 11003, 11004 };
    ByteBuffer body = ByteBuffer.allocate(16 + chips.length * 66).order(ByteOrder.LITTLE_ENDIAN);
    body.putInt(chips.length);
    body.putInt(0);
    body.putInt(chips.length);
    body.putInt(chips.length);
    for (int chip : chips) {
        byte[] rec = itemInfoRecord(chip, CHIP_KEEP);
        int f = chipUpdateFlags;
        rec[0] = (byte) (f & 0xFF);
        rec[1] = (byte) ((f >> 8) & 0xFF);
        rec[2] = (byte) ((f >> 16) & 0xFF);
        rec[3] = (byte) ((f >> 24) & 0xFF);
        body.putShort((short) rec.length);
        body.put(rec);
    }
    send(out, SC_ITEMINFORES, session, bodyArray(body));
    Status.log("game: SC_ITEMINFORES chip refresh (" + chips.length + " rows)");
}

/** Push one item row with an explicit count and flags. */
static void sendItemUpdateCount(OutputStream out, long session, int itemId,
                                int count, int flags) throws Exception {
    byte[] rec = itemInfoRecord(itemId, count);
    rec[0] = (byte) (flags & 0xFF);
    rec[1] = (byte) ((flags >> 8) & 0xFF);
    rec[2] = (byte) ((flags >> 16) & 0xFF);
    rec[3] = (byte) ((flags >> 24) & 0xFF);
    ByteBuffer body = ByteBuffer.allocate(16 + 2 + rec.length).order(ByteOrder.LITTLE_ENDIAN);
    body.putInt(1);
    body.putInt(0);
    body.putInt(1);
    body.putInt(1);
    body.putShort((short) rec.length);
    body.put(rec);
    send(out, SC_ITEMUPDATERES, session, bodyArray(body));
}

static void sendItemUpdate(OutputStream out, long session, int itemId) throws Exception {
    int count = SessionState.get().itemCount(itemId);
    byte[] rec = itemInfoRecord(itemId, count);
    if (isExpChip(itemId) && rec.length >= 4) {
        // an EXP-chip row the client already owns must be an UPDATE (bit3);
        // a create row is dropped as a duplicate and the slot stays at 0
        int f = chipUpdateFlags;
        rec[0] = (byte) (f & 0xFF);
        rec[1] = (byte) ((f >> 8) & 0xFF);
        rec[2] = (byte) ((f >> 16) & 0xFF);
        rec[3] = (byte) ((f >> 24) & 0xFF);
    }
    ByteBuffer body = ByteBuffer.allocate(16 + 2 + rec.length).order(ByteOrder.LITTLE_ENDIAN);
    body.putInt(1);
    body.putInt(0);
    body.putInt(1);
    body.putInt(1);
    body.putShort((short) rec.length);
    body.put(rec);
    send(out, SC_ITEMUPDATERES, session, bodyArray(body));
}

/** Flags used when pushing an EXP chip row after a level-up (0x08 = update). */
public static volatile int chipUpdateFlags = 0x02;
/** flags for the chip rows in the login inventory list (create|update) */
public static volatile int chipLoginFlags = 0x02;

/** Summon (gacha) reply: the pulled character id. */
/** Summon pool from Summon_List.tbd (all three banners list these). */
public static final String SUMMON_POOL = "1201,1301,1401,2101,2102,2201,2202,2203,2301,2302,2501,2502,3102,3103,3202,3203,3302,3303,3304,3305,3401,3402,3403,3404,3502,3503,4103,4104,4105,4106,4107,4108,4203,4204,4205,4206,4207,4208,4303,4304,4305,4306,4307,4308,4309,4310,4403,4404,4405,4406,4503,4504,4505";

static int randomSummonChar() {
    String[] ids = SUMMON_POOL.split(",");
    int idx = (int) (Math.random() * ids.length);
    if (idx >= ids.length) idx = ids.length - 1;
    try {
        return Integer.parseInt(ids[idx].trim());
    } catch (NumberFormatException e) {
        return 4105;
    }
}

/**
 * SC_CharacterSummonRes(653). Reader @0x07c30648:
 *   cmp  w8, #0x21            -> payload must be >= 33 bytes
 *   u16 32 + 32-byte FGB_SummonInfo
 *   u32 rowCount, then rowCount * (u16 14 + FGB_FundInfo)
 * The 14-byte rows are consumed by UCMAccountManager::SetFundInfos.
 */
/**
 * SC_CharacterSummonRes(653), restored byte-for-byte from the pre-damage build
 * (dex @0x00cc44):
 *   u16 32 | 32-byte info {+0 = 10, +4 = summonId}
 *   u32 fundRowCount | fundRowCount * (u16 14 + {u32 flags, u16 key, u64 amount})
 */
static byte[] characterSummonResPayload(int summonId) {
    List<AssetStore.Fund> funds = SessionState.get().funds();
    int n = (fundSendRecords && !FUND_RECORDS_DISABLED) ? funds.size() : 0;
    ByteBuffer b = ByteBuffer.allocate(n * 16 + 38).order(ByteOrder.LITTLE_ENDIAN);
    byte[] info = new byte[32];
    ByteBuffer ib = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN);
    ib.putInt(0, 10);
    ib.putInt(4, summonId);
    b.putShort((short) 32);
    b.put(info);
    b.putInt(n);
    for (AssetStore.Fund f : funds) {
        byte[] rec = new byte[14];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.putInt(0, fundRecordFlags);
        rb.putShort(4, (short) fundTypeOf(f.id));
        rb.putLong(6, Math.max(0L, f.amount));
        b.putShort((short) rec.length);
        b.put(rec);
    }
    Status.log("game: SC_CHARACTERSUMMONRES ok, " + n + " fund row(s)");
    return b.array();
}

public static volatile int favorTailBytes = 12;
public static volatile int favorSlotSpan = 1;
public static volatile int favorKeyScan = 0;
public static volatile int fundKeyScan = 0;
public static volatile int fundTypeGoldKey = 1;
public static volatile boolean favorPointMirror = false;
public static volatile int favorLimit = 6;
public static volatile int chipCat = -1;
public static volatile int chipCatProbe = -1;
public static volatile int itemCategory = 0;
public static final java.util.Map<Integer, Integer> itemCatOverride =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, Integer>());

// ---- helpers restored after an editing accident (see docs) ----------------

/** Characters currently on the account, in roster order. */
static java.util.List<String> characterIds() {
    return SessionState.get().characters();
}

/** Serialise only the bytes written into a ByteBuffer. */
static byte[] bodyArray(ByteBuffer b) {
    byte[] out = new byte[b.position()];
    b.rewind();
    b.get(out);
    return out;
}

/** Dorm slot order: the six ids the operator picked. */
public static volatile String favorProbeIds = "4105,4205,4406,4505,4503,4504";

static java.util.List<String> favorCharIds() {
    java.util.List<String> out = new java.util.ArrayList<String>();
    for (String part : favorProbeIds.split(",")) {
        String t = part.trim();
        if (!t.isEmpty()) out.add(t);
    }
    return out;
}

public static volatile int favorRecordFlags = 0x02;
/** Flags for the 776/449 character record.  0x02 makes the client CREATE the
 *  entry, which re-registered the character one square further along in the
 *  dorm; 0x08 is the lookup/update form and should be a no-op. */
public static volatile int commuRecordFlags = 0x08;
public static volatile int favorSlotBase = 0;
public static volatile int favorPoints = 29952;
public static volatile int organizeRecordFlags = 0x02;
public static volatile int organizeLimit = -1;
/** Emit a row for every empty square so the client draws an add button there. */
public static volatile boolean organizeEmptySlots = false;
/** Switches for bisecting a client crash: turn the dorm tables off. */
public static volatile boolean sendFavorPackets = true;
public static volatile boolean sendOrganizePackets = true;
static final java.util.concurrent.atomic.AtomicBoolean firstCharLogged =
        new java.util.concurrent.atomic.AtomicBoolean(false);

static byte[] characterPayload() {
        List<String> items = characterIds();
        ByteBuffer body = ByteBuffer.allocate(16 + items.size() * 64 + 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(items.size());   // +0x00 A
        body.putInt(0);              // +0x04 skipped
        body.putInt(items.size());   // +0x08 B
        body.putInt(items.size());   // +0x0C count
        for (String it : items) {
            byte[] rec = new byte[48];
            int cid;
            try { cid = Integer.parseInt(it.trim()); } catch (NumberFormatException e) { cid = 0; }
            ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            rb.putInt(0, charRecordFlags);   // +0x00 flags (0x02 = owned/insert)
            rb.putInt(4, cid);               // +0x04 key
            SaveStore.CharGrowth cg = SaveStore.get().growthOf(cid);
            int recClass = cg.charClass > 1 ? cg.charClass : charClassId;
            rb.put(8, (byte) recClass);      // +0x08 ETB_Char_Class
            Integer cos = costumeOf.get(cid);
            if (cos != null && CostumeCatalog.owns(cid, cos)) rb.putInt(0x2B, cos);
            rb.putInt(0x09, charFragCount);  // +0x09 fragment count
            int recLevel = cg.level > 1 ? cg.level : charLevel;
            rb.putShort(0x0D, (short) recLevel);
            // The client keeps cumulative EXP and subtracts the level threshold
            // (that is why a bare "0" showed -14458 at level 30), so send
            // threshold(level) + exp-within-level as a u64 at +0x0F.
            long expTotal = (long) LevelCurve.threshold(LevelCurve.columnFor(recClass), recLevel)
                    + (cg.level > 1 ? cg.exp : charExp);
            rb.putLong(0x0F, expTotal);
            if (charSendSkills) {
                for (int k = 0; k < 7 && k < charSkills.length; k++) {
                    rb.put(0x24 + k, (byte) charSkills[k]);
                }
            }
            if (charSendFavor) {
                rb.putShort(0x17, (short) (cg.favorLevel > 1 ? cg.favorLevel
                        : (SaveStore.isNarrative(cid) ? charFavorLevel : 1)));
                rb.putInt(0x19, cg.favorExp);  // 0 is a valid level-start value
            }
            body.putShort((short) rec.length);
            body.put(rec);
        }
        return bodyArray(body);
    }

/** The six presets must occupy squares 0..5 until the operator changes them. */
static void seedDormSlots() {
    if (!favorSlotOf.isEmpty()) return;
    int slot = 0;
    for (String part : favorProbeIds.split(",")) {
        String t = part.trim();
        if (t.isEmpty()) continue;
        try {
            int cid = Integer.parseInt(t);
            if (cid > 0 && slot < 6) favorSlotOf.put(cid, slot++);
        } catch (NumberFormatException ignored) { }
    }
}

/** Push the dorm tables (334 + 335) to the live session. */
/** Diagnostics: push one character record to the live session with explicit
 *  flags, so the record layout can be probed from the web console instead of
 *  rebuilding the APK for every attempt. */
public static synchronized String pushCharNow(int charId, int flags) {
    return pushCharNow(charId, flags, -1, -1, -1);
}

public static synchronized String pushCharNow(int charId, int flags, int cls,
                                              int skill, int level) {
    java.io.OutputStream o = liveOut;
    long s0 = liveSession;
    if (o == null || s0 == 0) return "no live session";
    if (charId <= 0) return "bad char id";
    try {
        sendCharacterUpdate(o, s0, charId, flags, cls, skill, level);
        return "sent char " + charId + " flags=0x" + Integer.toHexString(flags)
                + " cls=" + cls + " skill=" + skill + " lv=" + level;
    } catch (Exception e) {
        return "failed: " + e;
    }
}

public static synchronized String pushDormNow() {
    seedDormSlots();
    java.io.OutputStream o = liveOut;
    long s0 = liveSession;
    if (o == null || s0 == 0) return "no live session";
    try {
        send(o, SC_FACILITYFAVORINFORES, s0, favorPayload());
        send(o, SC_FACILITYORGANIZEINFORES, s0, organizePayload());
        return "sent";
    } catch (Exception e) {
        return "failed: " + e;
    }
}

static byte[] favorPayload() {
    seedDormSlots();
        List<String> ids = favorCharIds();
        // Only send the characters the player actually placed in the dorm.
        // favourLimit/favourCharIds used to hand the first six roster entries
        // to the client, which filled all six squares and left no "+" to add
        // anyone.  An empty square is simply absent from this table, and the
        // client draws its own add button there.
        {
            List<String> placed = new ArrayList<String>();
            for (String s : ids) {
                try {
                    if (favorSlotOf.containsKey(Integer.parseInt(s.trim()))) placed.add(s);
                } catch (NumberFormatException ignored) { }
            }
            ids = placed;
        }
        if (favorLimit >= 0 && ids.size() > favorLimit) {
            ids = new ArrayList<String>(ids.subList(0, favorLimit));
        }
        int n = ids.size();
        java.util.Map<Integer, Integer> dormSlotsOf = dormSlots(ids, 6);
        ids = orderBySlot(ids, dormSlotsOf);
        if (n == 0) {
            return le32(0);
        }
        // SC_FacilityFavorInfoRes @0x07c29f58: "add x12, x9, #4" means the
        // header is a SINGLE u32 count, exactly like 327 -- not the four-u32
        // form used by 322/325.  Sending four u32s made the client read the
        // extra header words as record data.
        // favorSlotSpan > 1 writes that many records per character, so the
        // buffer must be sized for the worst case (it used to overflow here).
        int recs = n * Math.max(1, favorSlotSpan);
        ByteBuffer body = ByteBuffer.allocate(4 + recs * (2 + 12) + favorTailBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(n);      // +0x00 count
        // Probe: write the SAME character into keys 0..N-1 with binary-coded
        // point values, so whatever number the affinity bar shows identifies
        // which key it reads.
        if (favorKeyScan > 0) {
            int reps = Math.min(favorKeyScan, 8);
            ByteBuffer sb = ByteBuffer.allocate(4 + reps * (2 + 12))
                    .order(ByteOrder.LITTLE_ENDIAN);
            sb.putInt(reps);
            for (int k = 0; k < reps; k++) {
                byte[] rec = new byte[12];
                ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
                rb.putInt(0, favorRecordFlags);
                rb.putInt(5, k);
                rb.putInt(8, 1 << k);
                sb.putShort((short) rec.length);
                sb.put(rec);
            }
            Status.log("game: FAVOR key scan 0.." + (reps - 1) + " values 1,2,4,..");
            return sb.array();
        }
        int slotIdx = 0;
        for (String s : ids) {
            byte[] rec = new byte[12];
            ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            rb.putInt(0, favorRecordFlags);        // +0x00 flags
            int cid = 0;
            try {
                cid = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) { }
            // +0x04 is the facility slot.  GetSlotFavorInfo(unsigned char) reads
            // the same container by slot, so a row with slot 0 only ever lands
            // in slot 0 whatever character it names.
            // Probe: optionally fan one character out across several slots.
            if (favorSlotSpan > 1) {
                for (int sIdx = 0; sIdx < favorSlotSpan; sIdx++) {
                    byte[] rec2 = new byte[12];
                    ByteBuffer rb2 = ByteBuffer.wrap(rec2).order(ByteOrder.LITTLE_ENDIAN);
                    rb2.putInt(0, favorRecordFlags);
                    rb2.put(4, (byte) (favorSlotBase + sIdx));
                    rb2.putInt(5, cid);
                    rb2.putInt(8, favorPoints);
                    body.putShort((short) rec2.length);
                    body.put(rec2);
                }
                slotIdx++;
                continue;
            }
            Integer fslot = dormSlotsOf.get(cid);
            rb.put(4, (byte) (favorSlotBase + (fslot == null ? slotIdx : fslot)));
            slotIdx++;
            rb.putInt(5, cid);                     // +0x05 key (unaligned)
            // Points live at +0x08, but the UI reads 000/000 which suggests it
            // looks somewhere else.  Mirror the value into every remaining
            // slot of the 12-byte record while probing.
            rb.putInt(8, favorPoints);             // +0x08 points
            if (favorPointMirror) {
                rec[4] = (byte) (favorPoints & 0xff);
                rec[9] = (byte) (favorPoints & 0xff);
                rec[10] = (byte) ((favorPoints >> 8) & 0xff);
                rec[11] = (byte) ((favorPoints >> 16) & 0xff);
            }
            body.putShort((short) rec.length);
            body.put(rec);
        }
        if (favorTailBytes > 0) {
            body.put(new byte[favorTailBytes]);
        }
        if (n > 0) {
            StringBuilder sb = new StringBuilder("game: FAVOR rec0=[");
            byte[] all = body.array();
            // first record starts after the 16-byte header + 2-byte length
            for (int i = 18; i < Math.min(18 + 12, all.length); i++) {
                sb.append(String.format("%02x ", all[i] & 0xff));
            }
            sb.append(']');
            Status.log(sb.toString());
        }
        { StringBuilder dbg = new StringBuilder("dorm rows (334):");
          for (String id : ids) {
              Integer sl = dormSlotsOf.get(Integer.valueOf(id.trim()));
              dbg.append(" ").append(id).append("@").append(sl);
          }
          Status.log(dbg.toString()); }
        Status.log("game: SC_FACILITYFAVORINFORES " + n + " favor row(s)");
        return body.array();
    }

    // ==================================================================
    // Facility (naval base) buildings
    // ==================================================================
    //
    // CS_ACCOUNTSECTION02 makes the client expect a burst:
    //   326 337 328 329 330 334 335 -> 213
    // The proxy used to answer 328 with le32(0) -- an EMPTY building list --
    // which is why the naval-base screen showed only placeholder slots, why
    // every "requires HQ level 1" line was red, and why the organization page
    // printed 67372032 (0x04040400: uninitialised memory read as a level/exp).
    //
    // Reader: UCMNetReceiver_GameBase::SC_FacilityBuildingInfoRes @0x07c28ed4
    //   0x7c28fd8  ldr  w10,[x10,x9]   ; u32 record count
    //   0x7c28ff8  mov  w24, #0x59     ; 89-byte stride
    //   0x7c290a0  ldrh w21,[x9,x8]    ; u16 length prefix per record
    // so the body is: u32 count, then count * (u16 len + 89 bytes).
    //
    // Setter: UCMFacilityInfoManager::SetFacilityBuildingInfo @0x079cf854
    // reads +0x00 (id) and +0x0C (kind) out of that 89-byte record.
    static byte[] facilityBuildingPayload() {
        int[] ids = facilityEffectiveIds();
        ByteBuffer body = ByteBuffer.allocate(4 + ids.length * (2 + 89))
                .order(ByteOrder.LITTLE_ENDIAN);
        // Each building goes out TWICE, and the order matters.
        //
        // SetFacilityBuildingInfo @0x079cf854 branches on the flags word:
        //   0x079cf884  tbnz w8,#6 -> drop            ; bit6
        //   0x079cf88c  tbnz w8,#1 -> 0x79cf914       ; bit1 = CREATE only
        //   0x079cf890  and  w8,w8,#0xc
        //   0x079cf894  cmp  w8,#8
        //   0x079cf898  b.ne -> drop                  ; (flags & 0xC) must be 8
        //   0x079cf9ec  bl   InitCompleteBuildInfo    ; <- marks it BUILT
        //
        // The create branch just emplaces the object into the TSet keyed by the
        // u64 at record+0x04.  It never reaches InitCompleteBuildInfo, so the
        // record never enters manager+0x78 -- the "complete building" array the
        // base screen renders from.  That is why the HQ stayed white
        // (= constructible) no matter how many rows we sent with 0x0A.
        //
        // The 0x08 branch looks the key up in the TSet first and only then calls
        // InitCompleteBuildInfo, so it needs the create to have happened.  Hence
        // create-then-complete, in that order, in the same packet.
        body.putInt(ids.length);
        for (int id : ids) {
            // ALWAYS the create flag here.  The manager TSet lives in the game
            // process and is empty at login, so a 0x08 row would hit the
            // "set is empty" bail at 0x079cf8a8 and do nothing -- the building
            // would simply not exist.  Creation is the only safe thing to send
            // inside the login burst, and it must stay ONE row per building so
            // the burst payload does not grow (doubling it broke login).
            byte[] rec = facilityRecord(id, facilityFlags);
            body.putShort((short) rec.length);
            body.put(rec);
        }
        Status.log("game -> SC_FACILITYBUILDINGINFORES(328) " + ids.length
                + " building(s), level " + facilityLevel);
        return bodyArray(body);
    }

    /** Buildings whose create row has been sent this process (diagnostic only). */
    public static final java.util.Set<Integer> facilityCreated =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<Integer>());

    /** Append id to the built list unless it is already there. */
    static int[] mergeBuiltIds(int[] current, int id) {
        if (current != null) {
            for (int v : current) {
                if (v == id) return current;
            }
        }
        int n = current == null ? 0 : current.length;
        int[] out = new int[n + 1];
        if (n > 0) System.arraycopy(current, 0, out, 0, n);
        out[n] = id;
        return out;
    }

    /**
     * Persist the built list so a proxy restart does not "un-build" the base.
     *
     * Without this the list reset to { 101 } on every launch, so a dorm built in
     * the previous session vanished from 328 and the panel showed it as
     * constructible again.  Stored in the proxy's own prefs, not the game save.
     */
    static void persistBuiltIds() {
        try {
            android.content.Context ctx = SaveStore.context();
            if (ctx == null) return;
            StringBuilder sb = new StringBuilder();
            for (int v : facilityBuildingIds) {
                if (sb.length() > 0) sb.append(',');
                sb.append(v);
            }
            StringBuilder cr = new StringBuilder();
            synchronized (facilityCreated) {
                for (int v : facilityCreated) {
                    if (cr.length() > 0) cr.append(',');
                    cr.append(v);
                }
            }
            ctx.getSharedPreferences("closersrt_facility", android.content.Context.MODE_PRIVATE)
                    .edit().putString("built", sb.toString())
                    .putString("created", cr.toString()).apply();
        } catch (Throwable t) {
            Status.log("facility: persist failed " + t);
        }
    }

    /** Load the persisted built list (called once at startup). */
    public static void loadBuiltIds() {
        try {
            android.content.Context ctx = SaveStore.context();
            if (ctx == null) return;
            String raw = ctx.getSharedPreferences("closersrt_facility",
                    android.content.Context.MODE_PRIVATE).getString("built", "");
            if (raw == null || raw.isEmpty()) return;
            java.util.List<Integer> out = new java.util.ArrayList<Integer>();
            for (String one : raw.split(",")) {
                try { out.add(Integer.parseInt(one.trim())); }
                catch (NumberFormatException ignored) { }
            }
            if (out.isEmpty()) return;
            int[] arr = new int[out.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
            facilityBuildingIds = arr;
            Status.log("facility: restored built list "
                    + java.util.Arrays.toString(arr));
            String createdRaw = ctx.getSharedPreferences("closersrt_facility",
                    android.content.Context.MODE_PRIVATE).getString("created", "");
            if (createdRaw != null && !createdRaw.isEmpty()) {
                for (String one : createdRaw.split(",")) {
                    try { facilityCreated.add(Integer.parseInt(one.trim())); }
                    catch (NumberFormatException ignored) { }
                }
                Status.log("facility: restored created set " + facilityCreated);
            }
        } catch (Throwable t) {
            Status.log("facility: restore failed " + t);
        }
    }

    /**
     * 328 with BOTH rows per building: create (0x0A) then complete (0x08).
     *
     * Only safe OUTSIDE the login burst.  Inside it the extra bytes make the
     * client reject the connection (IP-mismatch popup), which is why the
     * login-time 328 keeps one row per building.
     *
     * This is what actually flips a building to "built": SetFacilityBuildingInfo
     * @0x079cf854 needs the TSet entry (create) before the 0x08 branch will
     * reach InitCompleteBuildInfo @0x079cfa30 and append to manager+0x78.
     */
    static byte[] facilityBuildingPayloadDual() {
        int[] ids = facilityEffectiveIds();
        ByteBuffer body = ByteBuffer.allocate(4 + ids.length * 2 * (2 + 89))
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(ids.length * 2);
        for (int id : ids) {
            byte[] create = facilityRecord(id, facilityCreateFlags);
            body.putShort((short) create.length);
            body.put(create);
            byte[] done = facilityRecord(id, facilityCompleteFlags);
            body.putShort((short) done.length);
            body.put(done);
        }
        Status.log("game -> 328(dual) " + (ids.length * 2) + " row(s): "
                + java.util.Arrays.toString(ids) + " create+complete");
        return bodyArray(body);
    }

    /**
     * Payload for SC_FACILITYBUILDINGUPDATERES(701): u32 count, then
     * count * (u16 length + 89-byte record), all with the COMPLETE flags.
     *
     * Reader: 0x07c30978
     *   0x7c30a7c  ldr  w10,[x10,x9]   ; u32 record count
     *   0x7c30a9c  mov  w24, #0x59     ; 89-byte stride
     *   then per record: u16 len + memcpy into a 0x59-byte stack record that
     *   is pre-filled with 0x04, and finally SetFacilityBuildingInfos
     *   @0x79cfd9c which forks every record into SetFacilityBuildingInfo
     *   @0x079cf854.
     */
    static byte[] facilityCompletePayload() {
        int[] ids = facilityEffectiveIds();
        ByteBuffer body = ByteBuffer.allocate(4 + ids.length * (2 + 89))
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(ids.length);
        for (int id : ids) {
            byte[] rec = facilityRecord(id, facilityCompleteFlags);
            body.putShort((short) rec.length);
            body.put(rec);
        }
        return bodyArray(body);
    }

    /** One-shot "mark every listed building complete" pass for the live session. */
    /** Costume id per character: recorded in FGB_CharacterInfo+0x0B.. wait, +0x2B.
     *  UCMCharInfo::SetCostumeID @0x07974a50 is `stur w1,[x0,#0x53]` and
     *  UCMCharInfo::Set copies the record to +0x28, so the wire field is
     *  record + (0x53 - 0x28) = record + 0x2B (unaligned u32). */
    public static final java.util.Map<Integer, Integer> costumeOf =
            new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();

    /** Costume selections are persisted so they survive a proxy restart. */
    private static android.content.Context costumeCtx;

    public static synchronized void initCostumes(android.content.Context c) {
        costumeCtx = c.getApplicationContext();
        android.content.SharedPreferences sp = costumeCtx.getSharedPreferences("costumes", 0);
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            try {
                Object v = e.getValue();
                if (v instanceof Integer) costumeOf.put(Integer.parseInt(e.getKey()), (Integer) v);
            } catch (Throwable ignored) { }
        }
        Status.log("costume: " + costumeOf.size() + " saved selection(s) loaded");
    }

    private static void persistCostume(int charId, int costumeId) {
        if (costumeCtx == null) return;
        android.content.SharedPreferences sp = costumeCtx.getSharedPreferences("costumes", 0);
        if (costumeId == 0) sp.edit().remove(String.valueOf(charId)).apply();
        else sp.edit().putInt(String.valueOf(charId), costumeId).apply();
    }


    /** Re-sends SC_CHARACTERINFO2RES(326) after a short delay.
     *
     * Named static class on purpose: d8 8.2 aborts on javac's anonymous
     * Foo$1.class output (see the note in MainActivity). */
    static final class DelayedRosterPush implements Runnable {
        private final java.io.OutputStream out;
        private final long session;
        DelayedRosterPush(java.io.OutputStream out, long session) {
            this.out = out;
            this.session = session;
        }
        @Override public void run() {
            try {
                Thread.sleep(350);
                send(out, SC_CHARACTERINFO2RES, session, characterPayload());
                Status.log("costume: roster re-push (326) delayed");
            } catch (Throwable ignored) { }
        }
    }
    /** Applies a costume to a character and pushes 615 to the live session. */
    public static synchronized String pushCostume(int charId, int costumeId) {
        if (costumeId != 0 && !CostumeCatalog.owns(charId, costumeId)) {
            Status.log("costume: char " + charId + " does not own " + costumeId + ", refused");
            return "refused: not owned";
        }
        costumeOf.put(charId, costumeId);
        persistCostume(charId, costumeId);
        java.io.OutputStream o = liveOut;
        long s0 = liveSession;
        if (o == null || s0 == 0) return "no live session (relog to apply)";
        try {
            // SC_WearingCostumeRes @0x07c2fd4c: the reader rejects payloads
            // shorter than 0x21 bytes, then reads a u16 length (<= 8) and
            // memcpy_chk's that many bytes into an 8-byte struct.  So the body
            // is: u16 len(8) + u32 charId + u32 costumeId + zero padding.
            ByteBuffer b = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) 8);
            b.putInt(charId);
            b.putInt(costumeId);
            b.put(new byte[23]);
            send(o, 615, s0, bodyArray(b));
            // NOTE: 369 is NOT sent here.  sendCharacterUpdate() builds its record
            // from SaveStore (level/class/exp), and for a character whose growth
            // was never saved that yields lv1/cls2 -- pushing it after a costume
            // change overwrote the live character.  Costume only rides on 326.
            //
            // Re-push the WHOLE roster (326) so the change is visible without a
            // relog.  615 alone only updates the wearing-costume popup in place;
            // the character list (character card, lobby model, dorm) is only
            // rebuilt when 326 arrives.  This is exactly what a class-up (606)
            // already did -- which is why players discovered that ranking up any
            // one character refreshed every costume at once.  Doing it here makes
            // that a side effect of the costume change itself instead of a trick.
            // Send 326 twice: once immediately, once after a short delay.
            //
            // 615 makes the client re-create the character model in place, and
            // while it is doing that it can drop a 326 that arrives in the same
            // frame -- which is why the GM overlay (unlike the web console) used
            // to need a rank-up before the new outfit showed.  The delayed copy
            // lands after that rebuild finished.
            try {
                send(o, SC_CHARACTERINFO2RES, s0, characterPayload());
                Status.log("costume: roster re-push (326) after costume change");
            } catch (Exception e) {
                Status.log("costume: roster re-push failed " + e);
            }
            Thread t = new Thread(new DelayedRosterPush(o, s0), "costume-repush");
            t.setDaemon(true);
            t.start();
            Status.log("costume: push 615 char " + charId + " -> " + costumeId);
            return "pushed";
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    public static synchronized String pushFacilityComplete() {
        java.io.OutputStream o = liveOut;
        long s0 = liveSession;
        if (o == null || s0 == 0) return "no live session";
        try {
            byte[] p = facilityCompletePayload();
            send(o, SC_FACILITYBUILDINGUPDATERES, s0, p);
            Status.log("facility: complete pass " + p.length + "B ids="
                    + java.util.Arrays.toString(facilityEffectiveIds())
                    + " flags=0x" + Integer.toHexString(facilityCompleteFlags)
                    + " gate[0x" + Integer.toHexString(facilityGateWireOffset)
                    + "]=0x" + Integer.toHexString(facilityGateValue));
            return "pushed " + p.length + "B";
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    /**
     * EMainFacilityBuilding for a Facility_Info key.
     * 101 본부=HeadQuater(1), 102 숙소=Dormitory(2), 104 연구소=Lab(3),
     * 103 팩토리=Hangar(4).  The record stores it 1-based (+1) because the
     * reader does  sub w22, w8, #1  before GetFaclitySubBuilding().
     */
    static int facilityMainType(int id) {
        switch (id) {
            case 101: return 1;
            case 102: return 2;
            case 104: return 3;
            case 103: return 4;
            default:  return 1;
        }
    }

    /** ETB_Facility_Type for a Facility_Info key (101 본부 = HEADQUATERS). */
    static int facilityFacilityType(int id) {
        switch (id) {
            case 101: return 1;   // HEADQUATERS
            case 102: return 2;   // INN (숙소)
            case 103: return 3;   // PLANT (팩토리)
            case 104: return 4;   // LABORATORY (연구소)
            default:  return 0;   // NONE
        }
    }

    /** +0x57: resource-slot index + 1.  0 = no slot, and the build
     *  sequence in RecvBeginBuildToServer stays skipped. */
    public static volatile int facilitySlotIndex = 0;

    /** +0x56: sub-building type.  0 = none. */
    public static volatile int facilitySubType = 0;

    /** One 89-byte FGB_FacilityBuildingInfo record for a building id. */
    static byte[] facilityRecord(int id) { return facilityRecord(id, facilityFlags); }

    /**
     * One 89-byte FGB_FacilityBuildingInfo record.
     *
     * MINIMAL by design.  The project log records the one configuration that
     * actually produced "본부 Lv.1" on screen:
     *
     *     push 328 with flags=0x0A, ids=101, level=1, NO canary
     *
     * Everything else in this record was my own guess and has been removed:
     *   - +0x10 / +0x14 : never confirmed to be read; SetFacilityBuildingInfo
     *                     only touches +0x00 (flags), +0x04 (u64 key),
     *                     +0x0C (FName) and one gate byte.
     *   - the gate byte  : writing it at wire +0x19 was an inference from
     *                     "object +0x28 == wire +0x00".  It was never proven,
     *                     and forcing it to 1 is a candidate for why rows that
     *                     used to render got dropped.
     *
     * Keeping the record to the four fields the setter actually reads is the
     * only way to tell whether a regression came from the flags or from my
     * extra bytes.
     */
    static byte[] facilityRecord(int id, int flags) {
        byte[] rec = new byte[89];
        ByteBuffer r = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        r.putInt(0, flags);                         // +0x00 flags
        r.putInt(4, id);                            // +0x04 key (u64 = id)
        // +0x0C is ETB_Facility_Type (a small enum), NOT a name.
        // ACMSSShipResourceActor::InitBuildingSetting does:
        //   0x7afbbac  ldr  w1, [obj, #0x34]   ; obj+0x34 = record+0x0C
        //   0x7afbbb8  bl   CreateNewResourceSlot(ETB_Facility_Type, u8)
        // and that call does AActor::AttachToComponent() on a component it
        // looks up by type.  Writing 90157 there (my old "FName" guess)
        // handed it an invalid type and the lookup returned null ->
        // SIGSEGV at AActor::AttachToComponent+4 the moment the ship level
        // started (2026-10-31 20:52 crash).
        r.putInt(12, facilityFacilityType(id));      // +0x0C ETB_Facility_Type
        // +0x14  u32 building id.
        //
        // This is the field the UI actually resolves the building through.
        // ACMFacilityOutGameMode::RecvBeginBuildToServer @0x0811b634 (the 703
        // handler on the base page) does:
        //   0x811b6b0  ldrb w8,  [info, #0x18]
        //   0x811b6bc  ldr  w1,  [info, #0x14]   ; <- building id
        //   0x811b6c8  bl   GetFacilityInfo(u32, u8)  @0x07af3f14
        //   0x811b6cc  cbz  x0, <log "IsValid false: FacilityInfo">
        // and GetFacilityInfo hashes w1 straight into the TMap at
        // gamemode+0x328 (0x7af3f3c `and w9,w9,w1`).  With the id left at 0
        // the lookup missed, the client logged
        //   CM: Error: RecvBeginBuildToServer(166) IsValid false: 'FacilityInfo'
        // and dropped the build -- the dialog stayed blank and the building
        // never gained a level.  Observed live in the game log 2026-10-31.
        r.putInt(0x14, id);                         // +0x14 building id
        r.putInt(0x18, facilityLevel);               // +0x18 level (u8; GetFacilityInfo)
        // +0x56 / +0x57 are what the 703 handler reads AFTER resolving the
        // FacilityInfo (UCMFacilityMainPage::RecvBeginBuildToServer):
        //   0x811b708  ldrb w8, [info, #0x56]
        //   0x811b710  sub  w22, w8, #1        ; -> GetFaclitySubBuilding()
        //   0x811b6b8  ldrb w25, [info, #0x57]
        //   0x811b76c  cbz  w25, 0x811bc64     ; 0 -> skip the whole sequence
        // With both left at 0 the client resolves the info but then bails out of
        // the build sequence: the camera only re-centres (no zoom-in), pinch
        // zoom stays locked because the build mode never ends, and nothing is
        // constructed.  Observed live 2026-10-31.
        // +0x57 is the resource-slot index + 1 read at obj+0x7F; it also
        // decides whether RecvBeginBuildToServer runs the build sequence.
        // Left at 0 until the ETB_Facility_Type numbers are confirmed -- a
        // wrong pair (type, slot) crashes the level load.
        rec[0x57] = (byte) facilitySlotIndex;
        rec[0x56] = (byte) facilitySubType;
        // The "is this building finished" gate.
        //
        // InitCompleteBuildInfo @0x079cfa30 checks one byte before it appends to
        // manager+0x78:
        //   0x79cfa9c  ldrb w8, [x19, #0x41]
        //   0x79cfaa0  cbz  w8, 0x79cfd14     ; zero -> return, nothing appended
        // x19 is the TSet value object and its embedded record starts at +0x28
        // (copy at 0x079cf950: q1 from [rec] lands at [obj+0x28]), so the gate
        // byte is wire record offset 0x41-0x28 = 0x19.  It has to be NON-zero or
        // the building is created but never shows a level.
        if (facilityGateWireOffset >= 0 && facilityGateWireOffset < rec.length) {
            rec[facilityGateWireOffset] = (byte) facilityGateValue;
        }
        for (int i = 0; i + 1 < facilityCanaryPairs.length; i += 2) {
            int o = facilityCanaryPairs[i];
            if (o >= 0 && o + 4 <= rec.length) r.putInt(o, facilityCanaryPairs[i + 1]);
        }
        if (facilityCanaryOffset >= 0 && facilityCanaryOffset + 4 <= rec.length) {
            r.putInt(facilityCanaryOffset, facilityCanaryValue);
        }
        return rec;
    }

    /** Building ids the account has actually built (702 requests). */
    public static final java.util.Set<Integer> facilityBuilt =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<Integer>());

    /**
     * Ids to send in 328.
     *
     * Deliberately ONLY the explicit list.  An earlier version also unioned in
     * every id seen in a 702 build request, and that polluted the client: rows
     * it rejected left the panel half-updated (built entries greyed out, the
     * next one white but not built).  Keep the wire content predictable and let
     * the operator opt in to extra ids explicitly.
     */
    static int[] facilityEffectiveIds() {
        return facilityBuildingIds;
    }

    /** Record flags for every facility row (runtime-tunable). */
    public static volatile int facilityFlags = 0x0A;

    /**
     * Pass 1: create the object in the manager TSet.
     *   bit1 (0x02) selects the create branch at 0x079cf88c.
     */
    public static volatile int facilityCreateFlags = 0x0A;

    /**
     * Pass 2: mark it complete.
     *   bit1 clear, bit6 clear, (flags & 0xC) == 8 -> reaches
     *   InitCompleteBuildInfo @0x079cfa30, which is what appends the row to
     *   manager+0x78 and makes the UI show Lv.1 instead of "constructible".
     */
    public static volatile int facilityCompleteFlags = 0x08;

    /**
     * Wire-record offset of the "append" gate read at object +0x41.
     *
     * object +0x28 maps to wire +0x00 (see the copy at 0x079cf950), so the gate
     * lives at wire +0x19.  Tunable so the two candidates can be A/B'd without
     * a rebuild: /api/facility?gate=<hexOffset>:<value>
     */
    public static volatile int facilityGateWireOffset = 0x19;

    /** Value written at {@link #facilityGateWireOffset}.  0 reproduces the old
     *  "every record is silently dropped" behaviour. */
    public static volatile int facilityGateValue = 1;

    /** Probe: offset (bytes) and value to drop into one field. -1 = off. */
    public static volatile int facilityCanaryOffset = -1;
    public static volatile int facilityCanaryValue = 0;

    /** offset,value,offset,value,... applied to every record (A/B probes). */
    public static volatile int[] facilityCanaryPairs = new int[0];

    /** The four naval-base buildings.  101..104 are the Facility_Info keys. */
    // Baseline: every naval-base building starts at Lv.1 so the ship is
    // already fully built on a fresh account -- this is the state the
    // operator asked to keep as the save baseline.
    public static volatile int[] facilityBuildingIds = { 101, 102, 103, 104 };

    /** Level every building starts at (1 = built, not yet upgraded). */
    // Requested baseline: every building starts at Lv.10 (Facility_Info has
    // levels 1..10 per building, keyed by the same building id).
    public static volatile int facilityLevel = 10;

    /** FName written to +0x0C.  The client resolves the building row through
     *  this, so a made-up value (1) leaves the record orphaned: it lands in
     *  the manager map but never gets a build slot.  Observed live values are
     *  90157 for 101 and 90158 for 102.  0 keeps the old level-based behaviour. */
    public static volatile int facilityFname = 0;

    /**
     * Payload for SC_FACILITYORGANIZEINFORES(335) -- which characters are
     * placed in the facility.  The affinity UI reads GetSlotFavorInfo(slot),
     * and a slot only exists once the character has been organised, which is
     * very likely why a plain 334 never showed up on the character card.
     *
     * FGB_FacilityOrganizeInfo :: StaticStruct @0x086797e4 -> size 0xd (13)
     *   SetFacilityOrganizeInfo @0x079d55c4:
     *     0x79d55e4  ldr  w8, [x1, #0]   ; +0x00 flags (bit6 drop, bit1 add)
     *     0x79d5618  ldr  w9, [x19, #4]  ; +0x04 key (u32, aligned)
     */
    static byte[] organizePayload() {
        List<String> ids = favorCharIds();
        // Only send the characters the player actually placed in the dorm.
        // favourLimit/favourCharIds used to hand the first six roster entries
        // to the client, which filled all six squares and left no "+" to add
        // anyone.  An empty square is simply absent from this table, and the
        // client draws its own add button there.
        {
            List<String> placed = new ArrayList<String>();
            for (String s : ids) {
                try {
                    if (favorSlotOf.containsKey(Integer.parseInt(s.trim()))) placed.add(s);
                } catch (NumberFormatException ignored) { }
            }
            ids = placed;
        }
        if (organizeLimit >= 0 && ids.size() > organizeLimit) {
            ids = new ArrayList<String>(ids.subList(0, organizeLimit));
        }
        int n = ids.size();
        java.util.Map<Integer, Integer> dormSlotsOf = dormSlots(ids, 6);
        ids = orderBySlot(ids, dormSlotsOf);
        if (n == 0) {
            // Still needs the power block: the reader copies it BEFORE it ever
            // looks at a count, so an empty body desyncs the stream.
            ByteBuffer empty = ByteBuffer.allocate(2 + 12 + 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            empty.putShort((short) 12);
            empty.put(new byte[12]);
            empty.putInt(0);
            return empty.array();
        }
        // ------------------------------------------------------------------
        // Wire shape (SC_FacilityOrganizeInfoRes @0x07c2a20c):
        //
        //   0x7c2a26c  ldr  w8,  [x23, #8]     ; stream pos
        //   0x7c2a274  add  x11, x8, #2
        //   0x7c2a27c  cmp  x11, x10
        //   0x7c2a324  ldrh w21, [x9, x8]      ; u16 length
        //   0x7c2a334  mov  w3,  #0xc          ; copies 12 bytes to sp+0x18
        //   0x7c2a35c  bl   SetFacilityOrganizePower
        //   0x7c2a384  ldr  w10, [x10, x9]     ; THEN the u32 record count
        //   0x7c2a3a4  mov  w24, #0xd          ; 13-byte records
        //
        // so the body is
        //     u16(12) + 12-byte FGB_FacilityOrganizePower
        //     u32 count + count * (u16(13) + 13-byte FGB_FacilityOrganizeInfo)
        //
        // The previous build emitted four u32s (n, 0, n, n) as the header.  The
        // reader took the first two bytes of that as a length prefix and copied
        // 12 bytes out of the count word -- every subsequent record was read
        // from the wrong offset, so no character was ever organised and the
        // dorm's second button stayed dead.
        ByteBuffer body = ByteBuffer.allocate(2 + 12 + 4 + 6 * (2 + 13) + 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) 12);      // length prefix of the power block
        body.put(new byte[12]);         // SetFacilityOrganizePower just stores it
        body.putInt(organizeEmptySlots ? 6 : n);   // record count (6 = every square)
        int slotIdx = 0;
        for (String s : ids) {
            byte[] rec = new byte[13];
            ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            // 13-byte FGB_FacilityOrganizeInfo, as read by
            //   UCMFacilityInfoManager::SetFacilityOrganizeInfo @0x079d55c4
            //       0x79d55e4  ldr  w8, [x1, #0]    ; +0x00 flags
            //       0x79d5618  ldr  w9, [x19, #4]   ; +0x04 key (u32)
            //   UCMFacilityOrganizeInfo::Set @0x079d5920
            //       0x79d594c  ldrb w8, [x19, #8]   ; +0x08 per-slot key (u8)
            //       0x79d59a8  ldrb w8, [x19, #6]
            //       0x79d59ac  ldrh w9, [x19, #4]
            //       0x79d59b4  ldurh w10,[x19, #0xb]
            //       0x79d59b8  ldur  w11,[x19, #7]
            rb.putInt(0, organizeRecordFlags);          // +0x00 flags
            int cid = 0;
            try {
                cid = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) { }
            rb.putInt(4, cid);                          // +0x04 character id
            // +0x08 is the slot the character occupies inside the dorm.  The
            // old build left it 0 for every row, so every character collided
            // on slot 0 and only one of them could ever show up.
            Integer oslot = dormSlotsOf.get(cid);
            rb.put(8, (byte) (organizeSlotBase + (oslot == null ? slotIdx : oslot)));
            slotIdx++;
            body.putShort((short) rec.length);
            body.put(rec);
        }
        // Empty squares: an entry with character 0 makes the client draw its
        // own add button there, which is what lets the player fill slots 2-6.
        if (organizeEmptySlots) {
            boolean[] taken = new boolean[6];
            for (Integer v : dormSlotsOf.values()) {
                if (v != null && v >= 0 && v < taken.length) taken[v] = true;
            }
            for (int sl = 0; sl < taken.length; sl++) {
                if (taken[sl]) continue;
                byte[] erec = new byte[13];
                ByteBuffer erb = ByteBuffer.wrap(erec).order(ByteOrder.LITTLE_ENDIAN);
                erb.putInt(0, organizeRecordFlags);
                erb.putInt(4, 0);
                erb.put(8, (byte) sl);
                body.putShort((short) erec.length);
                body.put(erec);
            }
        }
        { StringBuilder dbg = new StringBuilder("dorm rows (335):");
          for (String id : ids) {
              Integer sl = dormSlotsOf.get(Integer.valueOf(id.trim()));
              dbg.append(" ").append(id).append("@").append(sl);
          }
          Status.log(dbg.toString()); }
        Status.log("game: SC_FACILITYORGANIZEINFORES " + n + " organize row(s)");
        return body.array();
    }

/** Emit the dorm rows in slot order.  The client assigns squares by the
     *  ORDER of the rows it receives, not by our +0x08 slot byte, so an
     *  unsorted list made a character slide to the next square on every
     *  re-entry (roster order differs from placement order). */
    /** Assign a stable dorm slot to every character: respect the slot the
     *  client picked with CS_FacilityFavorSetSlot(773), then fill the free
     *  slots in order. */
    static java.util.Map<Integer, Integer> dormSlots(java.util.List<String> ids, int maxSlots) {
        java.util.Map<Integer, Integer> out = new java.util.LinkedHashMap<Integer, Integer>();
        boolean[] used = new boolean[Math.max(1, maxSlots)];
        for (String s : ids) {
            int cid = 0;
            try { cid = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
            Integer slot = favorSlotOf.get(cid);
            if (cid > 0 && slot != null && slot >= 0 && slot < used.length && !used[slot]) {
                out.put(cid, slot);
                used[slot] = true;
            }
        }
        int next = 0;
        for (String s : ids) {
            int cid = 0;
            try { cid = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
            if (cid <= 0 || out.containsKey(cid)) continue;
            while (next < used.length && used[next]) next++;
            if (next >= used.length) break;
            out.put(cid, next);
            used[next] = true;
        }
        return out;
    }

    static java.util.List<String> orderBySlot(java.util.List<String> ids,
                                              java.util.Map<Integer, Integer> slots) {
        // Plain insertion sort: a Comparator (anonymous or nested) made d8
        // abort with "Cannot invoke String.length() because <parameter1> is
        // null", so no extra class is generated at all.
        java.util.List<String> out = new java.util.ArrayList<String>(ids);
        for (int i = 1; i < out.size(); i++) {
            String cur = out.get(i);
            int ci = slotOfDorm(cur, slots);
            int j = i - 1;
            while (j >= 0 && slotOfDorm(out.get(j), slots) > ci) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, cur);
        }
        return out;
    }

    static int slotOfDorm(String s, java.util.Map<Integer, Integer> slots) {
        try {
            Integer v = slots.get(Integer.parseInt(s.trim()));
            return v == null ? Integer.MAX_VALUE : v.intValue();
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** charId -> dorm slot, updated by CS_FacilityFavorSetSlot(442). */
    public static final java.util.Map<Integer, Integer> favorSlotOf =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, Integer>());

    /**
     * SC_FacilityFavorSetSlotRes(443) -- "u16 length + 12-byte FGB_FacilityFavorInfo".
     *
     * Reader: SC_FacilityFavorSetSlotRes @0x07c35d98
     *   0x7c35e3c  ldrh w21, [x9, x8]    ; u16 length
     *   0x7c35e4c  mov  w3,  #0xc        ; 12-byte struct
     *   0x7c35e74  bl   SetFacilityFavorInfo
     */
    static byte[] favorSetSlotRes(int cid, int slot) {
        byte[] rec = new byte[12];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.putInt(0, favorRecordFlags);
        rb.put(4, (byte) slot);
        rb.putInt(5, cid);
        rb.putInt(8, favorPoints);
        ByteBuffer body = ByteBuffer.allocate(2 + 12).order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) 12);
        body.put(rec);
        return body.array();
    }

    /**
     * SC_FacilityFavorCommuRes(445) / SC_FacilityFavorDateRes(449).
     *
     * Reader: SC_FacilityFavorCommuRes @0x07c35f2c
     *   0x7c35fd8  ldrh w21, [x9, x8]    ; u16 length
     *   0x7c35fe8  mov  w3,  #0x3c       ; 60-byte FGB_CharacterInfo
     *   0x7c36018  bl   SetCharacterInfo
     *
     * The struct is a full character row, so it is filled from the same record
     * the roster uses; a zeroed 60-byte block would blank the character.
     */
    /**
     * Character id out of a CS_FacilityFavor* request.
     *
     * The sender writes a u16 length prefix, then a u32 id:
     *   CS_FacilityFavorCommu @0x07cddc14   mov w2, #4      ; 4-byte body
     *                                      str w8, [sp,#0x10]
     * and the live packet captured 2026-10-31 was
     *   hex: 04 00 | b1 04 00 00        ; len=4, cid=1201
     * Reading offset 0 gave 0x04B10004 = 78708740, i.e. the length prefix
     * bled into the id, so the reply carried a character that does not exist
     * and the chat did nothing at all.
     */
    static int commuCid(byte[] payload) {
        if (payload == null) return 0;
        // Every one of 775/777/779/781 starts with a u16 length.  Depending on
        // the message the rest is (u8 slot + u32 id) or just (u32 id), so try
        // each alignment and keep the value that names a real character.
        int[] cand = new int[4];
        int n = 0;
        if (payload.length >= 7) cand[n++] = rd32(payload, 3);
        if (payload.length >= 6) cand[n++] = rd32(payload, 2);
        if (payload.length >= 6) cand[n++] = (payload[4] & 0xff) | ((payload[5] & 0xff) << 8);
        if (payload.length >= 4) cand[n++] = rd32(payload, 0);
        List<String> ids = characterIds();
        for (int i = 0; i < n; i++) {
            if (cand[i] <= 0) continue;
            if (ids.contains(String.valueOf(cand[i]))) return cand[i];
        }
        return n > 0 ? cand[0] : 0;
    }

    /** Favour gain per interaction, until the real curve is known. */
public static volatile int favorGainCommu = 50;
public static volatile int favorGainDate = 100;
/** Exp needed per favour level (placeholder curve, level 1..10). */
public static volatile int favorExpPerLevel = 100;

/** Add favour exp and level up; returns {level, exp}. */
/** The 27 characters that own a character side-story (NarrativeMode_Info). */
public static final String NARRATIVE_CHAR_IDS = "4103,4104,4105,4106,4107,4108,4203,4204,4205,4206,4207,4208,4303,4304,4305,4306,4307,4308,4309,4310,4403,4404,4405,4406,4503,4504,4505";

// ------------------------------------------------------------------
// Character side-story (Memory of Dimension) progress.
//
// Main story and side-story do NOT unlock the same way.  This block is the
// side-story half:
//   * 322 rows go out only for CLEARED side-story stages.  A row closes the
//     entry; the NEXT stage is unlocked precisely because it has no row yet.
//   * CS_StageStart(100) records which stage the player is entering.
//   * CS_GameFinish(102) marks that stage cleared (3 stars) and replies with
//     SC_NarrativeResultRes(256) = 44-byte FGB_StageInfo + 9-byte
//     FGB_NarrativeInfo, the packet the client waits for after a side-story
//     battle (gameMode == 3 at 0x7c03fc8).
//   * SC_CheatNarrativeCountResetRes(532) carries a 9-byte FGB_NarrativeInfo
//     whose first bytes are the daily entry counter.  [0]=3, [1]=3 is the
//     measured "3/3, no consumption" state.
// ------------------------------------------------------------------
/** Cleared side-story stages, keyed by the 8_CCCC_SS stage id.  This is a
 *  read-through view of SaveStore; the authoritative copy is persisted there
 *  so a clear survives a proxy or game restart. */
static java.util.Set<Integer> narrativeCleared() {
    return SaveStore.get().narrativeCleared();
}
/** Stage id most recently entered, from CS_StageStart(100). */
public static volatile int narrativeActiveStage = 0;
/** Daily entries left / max shown at the bottom-right.  3/3 by default and
 *  deliberately NOT decremented, so the counter never runs out. */
public static volatile int narrativeDailyLeft = 3;
public static volatile int narrativeDailyMax = 3;
/** When true, a cleared stage stays replayable (requires a libUE4 patch). */
public static volatile boolean narrativeReplay = false;

/** How many stages of a character group are cleared.  characterClearCount
 *  is the web-console baseline; live clears extend it contiguously. */
static int narrativeClearedCount(int group) {
    int n = characterClearCount;
    if (n < 0) n = 0;
    if (n > 8) n = 8;
    for (int s = n; s < 8; s++) {
        int idx = group * 8 + s;
        if (idx < 0 || idx >= CHARACTER_STAGE_IDS.length) break;
        int id;
        try { id = Integer.parseInt(CHARACTER_STAGE_IDS[idx]); }
        catch (NumberFormatException e) { break; }
        if (narrativeCleared().contains(id)) n = s + 1; else break;
    }
    return n;
}

/** Locate a legal character side-story id (8_CCCC_SS) inside a request.
 *  CS_GameFinish puts the stage id at body+0; CS_StageStart puts it at
 *  body+6.  Only those two alignments are checked (plus the recorded active
 *  stage as a fallback) so a random 84xxxxx value elsewhere in a main-story
 *  packet cannot be mistaken for a side-story clear. */
static int findNarrativeStage(byte[] payload) {
    if (payload == null) return 0;
    int[] offs = { 0, 6 };
    for (int off : offs) {
        if (off + 4 > payload.length) continue;
        int v = rd32(payload, off);
        if (isNarrativeStageId(v)) return v;
    }
    return 0;
}

/** True for a stage id that is in the character side-story table. */
static boolean isNarrativeStageId(int v) {
    if (v < 8400000 || v > 8499999) return false;
    for (String s : CHARACTER_STAGE_IDS) {
        try { if (Integer.parseInt(s) == v) return true; }
        catch (NumberFormatException ignored) { }
    }
    return false;
}

/** Record a side-story clear. */
static boolean markNarrativeCleared(int stageId) {
    if (stageId < 8400000 || stageId > 8499999) return false;
    boolean added = SaveStore.get().clearNarrativeStage(stageId);
    Status.log("narrative: clear stage " + stageId + (added ? " (saved)" : " (already)"));
    return added;
}

/** 44-byte FGB_StageInfo for one side-story stage (3 stars). */
static byte[] narrativeStageRecord(int stageId) {
    byte[] rec = new byte[44];
    ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
    rb.putInt(0, 0x02);
    rb.putInt(4, stageId);
    for (int k = 8; k < rec.length; k++) rec[k] = (byte) stageClearProbe;
    return rec;
}

/** 9-byte FGB_NarrativeInfo: [0]=remaining, [1]=max.  Confirmed by
 *  FGB_NarrativeInfo::StaticStruct (size 9) and SetNarrativeMiscInfo, which
 *  copies the 8-byte head to account+0x264 and byte +8 to +0x26c. */
static byte[] narrativeInfo() {
    return new byte[] { (byte) narrativeDailyLeft, (byte) narrativeDailyMax,
                        0, 0, 0, 0, 0, 0, 0 };
}

/** SC_NarrativeResultRes(256): u16 length + 53-byte record.  The reader at
 *  0x07c22ef4 copies 0x35 bytes into one stack struct, then calls
 *  SetStageInfo(sp) and SetNarrativeMiscInfo(sp+0x2c). */
static byte[] narrativeResultPayload(int stageId) {
    byte[] st = narrativeStageRecord(stageId);
    byte[] ni = narrativeInfo();
    ByteBuffer b = ByteBuffer.allocate(2 + st.length + ni.length)
            .order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) (st.length + ni.length));
    b.put(st);
    b.put(ni);
    return b.array();
}

/** SC_CheatNarrativeCountResetRes(532): u16 9 + 9-byte struct + 16-byte
 *  zero tail (the reader copies 9; slack keeps its scan inside the frame). */
static byte[] narrativeResetPayload() {
    byte[] ni = narrativeInfo();
    ByteBuffer b = ByteBuffer.allocate(2 + ni.length + 16)
            .order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short) ni.length);
    b.put(ni);
    b.put(new byte[16]);
    return b.array();
}

/** Set the 27 narrative characters to the given favour level (exp 0). */
public static synchronized String setFavorNine(int level) {
    SaveStore store = SaveStore.get();
    int n = 0;
    for (String part : NARRATIVE_CHAR_IDS.split(",")) {
        int cid;
        try { cid = Integer.parseInt(part.trim()); } catch (NumberFormatException e) { continue; }
        SaveStore.CharGrowth g = store.growthOf(cid);
        g.favorLevel = Math.max(1, level);
        g.favorExp = 0;
        store.setGrowth(cid, g);
        n++;
    }
    Status.log("favor: narrative characters -> level " + level + " (" + n + ")");
    return "level " + level + " x" + n;
}

/** Put every character back to a favour baseline. */
public static synchronized String resetFavorAll(int exp) {
    SaveStore store = SaveStore.get();
    int n = 0;
    for (String id : characterIds()) {
        int cid;
        try { cid = Integer.parseInt(id.trim()); } catch (NumberFormatException e) { continue; }
        SaveStore.CharGrowth g = store.growthOf(cid);
        g.favorLevel = 1;
        g.favorExp = exp;
        store.setGrowth(cid, g);
        n++;
    }
    Status.log("favor: reset " + n + " character(s) to level 1 / exp " + exp);
    return "reset " + n + " to exp " + exp;
}

static int[] addFavor(int cid, int gain) {
    SaveStore store = SaveStore.get();
    SaveStore.CharGrowth g = store.growthOf(cid);
    if (g.favorLevel < 1) g.favorLevel = 1;
    g.favorExp += Math.max(0, gain);
    int per = Math.max(1, favorExpPerLevel);
    while (g.favorExp >= per && g.favorLevel < 10) {
        g.favorExp -= per;
        g.favorLevel++;
    }
    store.setGrowth(cid, g);
    Status.log("favor: char " + cid + " +" + gain + " -> level " + g.favorLevel + " exp " + g.favorExp);
    return new int[] { g.favorLevel, g.favorExp };
}

static byte[] favorCommuRes(int cid) {
        // Same per-character progression as 326, so the chat reply cannot
        // revert a promotion or a level.
        SaveStore.CharGrowth cg = SaveStore.get().growthOf(cid);
        // 60-byte FGB_CharacterInfo.  The roster builder produces 48 bytes
        // (0x30); this reader copies up to 0x3c, so the record is padded to 60
        // rather than left short -- memcpy_chk is given 0x3c as the cap, and a
        // short source just leaves the tail zeroed.
        byte[] rec = new byte[60];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        rb.putInt(0, commuRecordFlags);   // 776: update, not create   // +0x00 flags
        rb.putInt(4, cid);               // +0x04 key = character id
        rb.put(8, (byte) (cg.charClass > 1 ? cg.charClass : charClassId));  // keep the promoted class   // +0x08 ETB_Char_Class (byte)
        // +0x09 is the fragment count.  SetCharacterInfo REPLACES the record,
        // so leaving this out zeroed the 500 starter fragments every time the
        // player chatted with / dated the character.  Mirror 326.
        rb.putInt(0x09, charFragCount);
        // COSTUME MUST BE RE-SENT HERE.
        //
        // SetCharacterInfo REPLACES the whole record, so any field this reply
        // leaves at 0 becomes 0 on the client.  +0x2B is the costume id
        // (UCMCharInfo::SetCostumeID @0x07974a50 does `stur w1,[x0,#0x53]`, and
        // the record lands at UCMCharInfo+0x28, hence +0x2B).  Because the
        // dorm interaction (CS_FacilityFavorCommu 775 / FavorDate 779) answers
        // with this record, skipping the field reset every costume the web
        // console had applied back to the default first outfit.  Mirror 326.
        Integer cos = costumeOf.get(cid);
        if (cos != null && CostumeCatalog.owns(cid, cos)) rb.putInt(0x2B, cos);
        // The client feeds this struct straight into
        //   UCMCharInfoManager::SetCharacterInfo(FGB_CharacterInfo const&, bool)
        //   @0x07975d10   (0x7c36018 in SC_FacilityFavorCommuRes)
        // i.e. it REPLACES that character's record.  Leaving exp/skills
        // zeroed therefore wiped them every time the player talked to the
        // character, which is why the chat interaction did nothing useful.
        // Mirror exactly what SC_CHARACTERINFO2RES(326) sends for the same
        // character so the "update" is a no-op.
        int recClassB = cg.charClass > 1 ? cg.charClass : charClassId;
        int recLevel = cg.level > 1 ? cg.level : charLevel;
        if (charExpProbe) {
            rb.putInt(0x10, 0x00001001);
            rb.putInt(0x14, 0x00002002);
            rb.putInt(0x18, 0x00003003);
            rb.putInt(0x1C, 0x00004004);
            rb.putInt(0x20, 0x00005005);
        } else if (charExp >= 0) {
            rb.putLong(0x0F, (long) (LevelCurve.threshold(
                    LevelCurve.columnFor(cg.charClass > 1 ? cg.charClass : charClassId),
                    cg.level > 1 ? cg.level : charLevel)
                    + (cg.level > 1 ? cg.exp : charExp)));
                }
            if (charSendSkills) {
                for (int i = 0; i < 7 && i < charSkills.length; i++) {
                    rb.put(36 + i, (byte) charSkills[i]);
                }
            }
            // Level probe: write charLevel at a MOVABLE offset so the field can
            // be located empirically without rebuilding.  A level showed as 00
            // at +0x0C, so the real field is somewhere else in the 48 bytes.
            if (charSentinels) {
                // Sentinel sweep: put a DISTINCT, recognisable number into every
                // 4-byte slot from +0x0C to +0x2C in a single launch.  Whatever
                // number the character card prints tells us which offset the UI
                // reads, without needing one build per offset.
                int[] vals = {11, 22, 33, 44, 55, 66, 77, 88, 99};
                for (int i = 0; i < vals.length; i++) {
                    int off = 12 + i * 4;
                    if (off + 4 <= rec.length) {
                        rb.putInt(off, vals[i]);
                    }
                }
            } else if (levelOffset >= 0 && levelOffset <= 46) {
                // Level is a u16, NOT a u32.  UCMCharInfo::execSetLv @0x0851d548
                // is "sturh w8, [x19, #53]" -- a halfword at UCMCharInfo+0x35 --
                // and UCMCharInfo::Set copies the record verbatim to +0x28, so
                // the game reads the level from record + 0x0D.  Writing a u32 at
                // +0x0C therefore stored its low byte at 0x0C and left 0x0D = 0,
                // which is exactly why the card kept printing 00.
                // Same rule for the level: charLevel is only the seed.  Sending it
                // unconditionally is why a level-up saved in SaveStore still showed
                // the old level after relogging.
                rb.putShort(levelOffset, (short) (cg.level > 1 ? cg.level : charLevel));
            }
            // Affinity (호감도).  Written LAST so no other probe can clobber
            // it.  +0x17 is a packed u16 level and +0x19 an int64 exp; the
            // upper half of the exp has to stay zero or the 64-bit compare in
            // UpdateCharacterFavor@0x7f930d0 reads a garbage value.
            if (charSendFavor) {
                // REVERTED 2026-10-31 22:2x: writing the level byte at +0x18 made
                // the dorm show Lv.2304 (= 0x0900, i.e. the client reads a u16 at
                // +0x17) and then corrupted the character card level, so the u16
                // write at +0x17 is restored.  Do not move this field again
                // without a fresh capture.
                rb.putShort(0x17, (short) (cg.favorLevel > 0 ? cg.favorLevel
                        : (SaveStore.isNarrative(cid) ? charFavorLevel : 1)));
                rb.putInt(0x19, cg.favorExp);  // 0 is a valid level-start value
                rb.putInt(0x1D, 0);
            }
            // +0x30.. is a SECOND struct, FGB_FacilityFavorInfo:
            //   SC_FacilityFavorCommuRes @0x07c36020  add x1, x21, #0x30
            //   -> UCMFacilityInfoManager::SetFacilityFavorInfo(rec+0x30)
            // Leaving it zeroed kept the PREVIOUS slot favour info, which is why
            // every slot showed the first character dialogue.
            {
                java.util.List<String> ids = favorCharIds();
                int slot = 0;
                for (int i = 0; i < ids.size(); i++) {
                    try {
                        if (Integer.parseInt(ids.get(i).trim()) == cid) { slot = i; break; }
                    } catch (NumberFormatException ignored) { }
                }
                rb.putInt(0x30, favorRecordFlags);
                rb.put(0x34, (byte) (favorSlotBase + slot));
                rb.put(0x35, (byte) (cid & 0xFF));
                rb.put(0x36, (byte) ((cid >> 8) & 0xFF));
                rb.put(0x37, (byte) ((cid >> 16) & 0xFF));
                int pts = favorPoints & ~0xFF;
                rb.putInt(0x38, pts);
            }
            ByteBuffer body = ByteBuffer.allocate(2 + rec.length).order(ByteOrder.LITTLE_ENDIAN);
            body.putShort((short) rec.length);
            body.put(rec);
            return body.array();
        }
    // ------------------------------------------------------------------
    /**
     * Header field values at +0x10 and +0x14, and the header length.
     *
     * `UCMNetSender_GameBase::CS_LoginOpen` (@0x07cd6808) shows what the client
     * itself puts in a header -- and it writes ONLY two things:
     *
     *     mov  w9, #0xc
     *     str  w9, [x8, #4]      ; opcode at +4
     *     ldr  x8, [x20, #0x28]
     *     str  x8, [x9, #8]      ; session (8 bytes) at +8
     *
     * Everything else is left as the buffer was allocated.  Our long-standing
     * count=1 at +0x10 and flags=0x1000 at +0x14 were guesses, so they are now
     * runtime-settable and can be zeroed without rebuilding.
     */
    public static volatile int hdrField16 = 1;
    public static volatile int hdrField20 = 0x1000;
    public static volatile int hdrSize = 32;

    /**
     * Opcode override for header probing; <0 means "use the real opcode".
     *
     * Changing only this field tells us whether the client's logged
     * `Net SC : %s(%d), %s(%d)` first number really is the opcode: with
     * +0x10 varied the second number moved (so that field IS read from our
     * bytes), while the first stayed pinned at -19082118 whatever we sent.
     */
    public static volatile int hdrOpcodeOverride = -1;

    /**
     * Encrypt the frame exactly as the retail server must have.
     *
     * The client's receive path calls Blowfish::Decrypt (0x07cf3c5c) over the
     * frame tail:
     *
     *     07cf6b44  ldr  w9, [x9]      ; frame size -- still PLAINTEXT
     *     07cf6b48  add  x1, x8, #4    ; region starts just past it
     *     07cf6b50  sub  w3, w9, #4    ; length = size - 4
     *     07cf6b54  bl   Blowfish::Decrypt
     *
     * which is why every earlier run logged a garbage opcode while the size
     * looked right: the size is not encrypted, everything after it is.
     */
    public static volatile boolean encryptFrames = true;

    /**
     * Whether client -> server frames are Blowfish-encrypted.
     *
     * The client does encrypt them, so this MUST default to true.  With it false
     * the server reads ciphertext as plaintext and every reply is nonsense; the
     * log shows `game <- opcode 2057056936 (OP_2057056936)` and the client never
     * gets past CS_VERSIONCHECK.
     *
     * This used to default to false, which meant every proxy restart silently
     * broke the protocol until /api/crypto was re-applied by hand -- and that is
     * exactly what kept happening between test runs.  The defaults below are now
     * the known-good configuration.
     */
    public static volatile boolean decryptFrames = true;

    /** Dump every received buffer in raw and decrypted form. */
    public static volatile boolean rawLog = false;

    // Writes MUST be serialised.  A frame is several put() calls on one
    // ByteBuffer followed by a single write; two threads interleaving that
    // produced a corrupted frame and the client dropped the connection (seen
    // as "client disconnected" ~6 ms after an out-of-band resend).  The packet
    // loop and any helper thread both go through here, so guarding this method
    // is what keeps the stream well formed.
    static synchronized void send(OutputStream out, int opcode, long session,
                                  byte[] payload) throws Exception {
        sendLocked(out, opcode, session, payload, hdrField16);
    }

    /**
     * Send with an explicit result code.
     *
     * The client reads the result from the header at +0x10 and branches on it:
     *
     *   UCMNetReceiver_GameBase::SC_ShopPurchasingRes @0x07c39998
     *     0x7c399d0  ldr  w8, [x8, #0x10]
     *     0x7c399d4  cmp  w8, #1
     *     0x7c399d8  b.ne #0x7c39a18      ; NOT 1 -> "failed" path
     *
     * so a request the server cannot honour can be answered with a well-formed
     * frame whose result is not 1.  The UI then takes its own failure branch
     * (popup closed, touch unblocked) instead of waiting forever for a reply it
     * never considers valid.  That is what stops the shop / summon hang.
     */
    static synchronized void send(OutputStream out, int opcode, long session,
                                  byte[] payload, int result) throws Exception {
        sendLocked(out, opcode, session, payload, result);
    }

    /** The unguarded writer. Callers must already hold the GameServer lock. */
    static void sendLocked(OutputStream out, int opcode, long session,
                           byte[] payload, int result) throws Exception {
        int h = hdrSize;
        ByteBuffer b = ByteBuffer.allocate(h + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, h + payload.length);
        b.putInt(4, hdrOpcodeOverride >= 0 ? hdrOpcodeOverride : opcode);
        b.putLong(8, session);
        if (h >= 24) {
            b.putInt(16, result);
            b.putInt(20, hdrField20);
        }
        // Any bytes between 20 and h stay zero, which is what the client's own
        // builder leaves them as.
        System.arraycopy(payload, 0, b.array(), h, payload.length);

        byte[] frame = b.array();
        if (encryptFrames && frame.length > 4) {
            CRYPTO.encrypt(frame, 4, frame.length - 4);
        }
        // Log what goes out.  Without this the outbound side was invisible, which
        // made it impossible to tell a packet that was never sent from one that
        // was sent with the wrong bytes.
        Status.log("game -> opcode " + opcode + " session="
                + session + " len=" + frame.length + " payload=" + payload.length
                + "B " + hexPreview(payload, 24));
        out.write(frame);
        out.flush();
    }

    /** First n payload bytes as hex, for the outbound log. */
    static String hexPreview(byte[] p, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, p.length); i++) {
            sb.append(String.format("%02x", p[i]));
        }
        if (p.length > n) {
            sb.append("..");
        }
        return sb.toString();
    }

    /** Shared Blowfish instance; the key schedule is deterministic. */
    private static final Blowfish CRYPTO = new Blowfish();

    static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    static void putLe32(byte[] dst, int off, int v) {
        dst[off] = (byte) v;
        dst[off + 1] = (byte) (v >>> 8);
        dst[off + 2] = (byte) (v >>> 16);
        dst[off + 3] = (byte) (v >>> 24);
    }

    static long rd64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    static void putLe64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    static void putLe16(byte[] dst, int off, int v) {
        dst[off] = (byte) v;
        dst[off + 1] = (byte) (v >>> 8);
    }

    /** The nickname as UTF-16LE WITHOUT any length prefix. */
    /**
     * UTF-16LE bytes WITH a NUL terminator.
     *
     * The terminator is not cosmetic.  The client's string readers locate the end
     * of a string by scanning u16 units until they hit zero:
     *
     *   UCMAccountManager::SetNPGameS2C_AuthRes @0x0795ef1c
     *     0x795ef6c  ldrh w8, [x20, w26]     ; read one UTF-16 unit
     *     0x795ef78  cbnz w8, #0x795ef6c     ; keep going until it is zero
     *     0x795efa8  sub  w2, w26, #0xd      ; memcpy size = scan end
     *     0x795efb0  bl   memcpy
     *
     * so a string without a terminator makes the scan walk past the end of our
     * payload into whatever memory follows, and the resulting memcpy length is
     * garbage.  That is exactly what killed the client:
     *
     *   Abort message: 'FORTIFY: memcpy: prevented 1793-byte write into
     *                   83-byte buffer'
     *
     * 1793 bytes is the runaway scan length, not anything we ever intended to
     * send.  Every string placed in a payload must therefore be terminated.
     */
    static byte[] utf16Raw(String s) {
        byte[] data = s.getBytes(java.nio.charset.Charset.forName("UTF-16LE"));
        byte[] out = new byte[data.length + 2];      // + trailing NUL
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    /**
     * NUL-terminated UTF-16LE plus a little slack.
     *
     * The slack matters for the same reason as the terminator: the client also
     * scans ahead a fixed amount in one of the readers, so ending a payload
     * exactly at the terminator still leaves it reading past the end.  A few
     * extra zero units cost nothing and keep every scan inside the buffer.
     */
    static byte[] utf16z(String s) {
        byte[] data = utf16Raw(s);
        byte[] out = new byte[data.length + 8];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    /** u16 byte-length + UTF-16LE + NUL, matching the reader in
     *  SC_ChapterInfoRes (ldrh then memcpy). */
    static byte[] utf16(String s) {
        byte[] data = s.getBytes(java.nio.charset.Charset.forName("UTF-16LE"));
        ByteBuffer b = ByteBuffer.allocate(2 + data.length + 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) (data.length + 2));
        b.put(data);
        b.putShort((short) 0);
        return b.array();
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    /** Copy out only the bytes written into a ByteBuffer. */
    static byte[] trim(ByteBuffer b) {
        byte[] out = new byte[b.position()];
        b.rewind();
        b.get(out);
        return out;
    }

    static String name(int op) {
        switch (op) {
            case 30: return "CS_KEEPALIVE";
            case 32: return "CS_VERSIONCHECK";
            case 40: return "CS_AUTH";
            case 44: return "CS_REQUESTSTART";
            case 46: return "CS_ENTERGAMESERVER";
            case 200: return "CS_GETNICKNAME";
            case 208: return "CS_ACCOUNTINFO";
            case 210: return "CS_ACCOUNTSECTION01";
            default: return "OP_" + op;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** Hex of the first n bytes. */
    static String hex(byte[] b, int n) {
        int lim = Math.min(n, b.length);
        byte[] c = new byte[lim];
        System.arraycopy(b, 0, c, 0, lim);
        return hex(c);
    }

    /** Little-endian u32 at an offset. */
    static int rd32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /**
     * Pull readable runs of text out of a payload.
     *
     * CM serialises strings as `u16 byte-length` followed by either UTF-16LE or
     * ASCII, so we scan for both: the game's own names and tokens show up this
     * way even before the exact field layout is known.
     */
    static java.util.List<String> strings(byte[] p) {
        java.util.List<String> out = new java.util.ArrayList<String>();

        // ASCII runs
        StringBuilder run = new StringBuilder();
        for (byte x : p) {
            int c = x & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                run.append((char) c);
            } else {
                if (run.length() >= 4) out.add(run.toString());
                run.setLength(0);
            }
        }
        if (run.length() >= 4) out.add(run.toString());

        // UTF-16LE runs (ASCII characters interleaved with NULs)
        StringBuilder w = new StringBuilder();
        for (int i = 0; i + 1 < p.length; i += 2) {
            int lo = p[i] & 0xFF, hi = p[i + 1] & 0xFF;
            if (hi == 0 && lo >= 0x20 && lo < 0x7F) {
                w.append((char) lo);
            } else {
                if (w.length() >= 4) out.add(w.toString());
                w.setLength(0);
                i -= 1;                       // resync on a misaligned byte
            }
        }
        if (w.length() >= 4) out.add(w.toString());

        // de-duplicate, keep order
        java.util.List<String> uniq = new java.util.ArrayList<String>();
        for (String s : out) {
            if (!uniq.contains(s)) uniq.add(s);
        }
        return uniq;
    }
}
