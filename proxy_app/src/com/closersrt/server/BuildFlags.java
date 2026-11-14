package com.closersrt.server;

/**
 * Compile-time switches.
 *
 * AUTO_START_* controls whether the app brings its own servers up on launch.
 * It is ON by default: leaving both off meant the operator had to press
 * "start server" on the phone before the game could log in, which made the
 * whole thing depend on a manual step -- and a manual step is exactly what
 * must not be part of the deliverable, because it cannot be reproduced on
 * another device.
 */
public final class BuildFlags {
    /** Bring the game / web / patch servers up when the app starts. */
    public static final boolean AUTO_START_SERVERS = false;

    /**
     * Keep the VPN tunnel up so the game's traffic is captured by us.
     *
     * OFF.  This build is fully offline: the game talks to the loopback servers
     * on 127.0.0.1 (game 8500 / patch 8443 / web 8080) that this app starts
     * itself, so no tunnel is involved and asking for VPN consent on every
     * install is a needless permission prompt.
     */
    public static final boolean AUTO_START_PROXY = false;

    private BuildFlags() { }
}
