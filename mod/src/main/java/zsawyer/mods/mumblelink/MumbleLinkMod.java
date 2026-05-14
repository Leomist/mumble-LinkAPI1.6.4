/*
 * Copyright (C) 2013, zsawyer <zsawyer@users.sourceforge.net>
 * Modifications Copyright (C) 2014, Leomist
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.
 */
package zsawyer.mods.mumblelink;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import zsawyer.mods.mumblelink.handler.TickHandler;

import java.util.logging.Logger;

/**
 * Main Forge mod class for MumbleLink.
 *
 * <p>On {@link #init}, starts a background daemon thread that polls Minecraft
 * state at 20 Hz and writes positional audio data directly to Mumble's
 * shared-memory segment (via {@link MumbleLink}).  No intermediate native
 * helper library is required.
 *
 * <p>Drop {@code mumblelink-1.6.4.jar} into the {@code mods/} folder of a
 * Minecraft 1.6.4 + Forge installation.  Mumble must be running with the
 * "Link" plugin enabled.
 */
@Mod(
    modid   = MumbleLinkMod.MODID,
    name    = MumbleLinkMod.NAME,
    version = MumbleLinkMod.VERSION,
    acceptedMinecraftVersions = "1.6.4"
)
public final class MumbleLinkMod {

    public static final String MODID   = "mumblelink";
    public static final String NAME    = "MumbleLink";
    public static final String VERSION = "1.1.4";

    @Mod.Instance(MODID)
    public static MumbleLinkMod INSTANCE;

    private static final Logger LOGGER = Logger.getLogger(MODID);

    /**
     * Initialisation: start the background Mumble link thread.
     *
     * <p>The thread opens Mumble's shared memory directly via JNA (no
     * external DLL needed) and retries every 50 ms until Mumble is running.
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("[MumbleLink] Initialising " + NAME + " " + VERSION);
        Thread t = new Thread(new TickHandler(), "MumbleLink");
        t.setDaemon(true);
        t.start();
        LOGGER.info("[MumbleLink] Link thread started – waiting for Mumble");
    }
}

