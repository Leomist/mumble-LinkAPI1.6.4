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
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import com.sun.jna.Native;
import zsawyer.mods.mumblelink.handler.TickHandler;
import zsawyer.mods.mumblelink.util.NativeLoader;
import zsawyer.mumble.jna.LinkAPILibrary;

import java.util.logging.Logger;

/**
 * Main Forge mod class for MumbleLink.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #preInit} – extracts the native LinkAPI library from the JAR and
 *       sets {@code jna.library.path} so JNA can find it.</li>
 *   <li>{@link #init}    – loads the JNA wrapper and registers the per-tick
 *       handler that pipes player position to Mumble.</li>
 * </ol>
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
    public static final String VERSION = "1.1.2";

    @Mod.Instance(MODID)
    public static MumbleLinkMod INSTANCE;

    private static final Logger LOGGER = Logger.getLogger(MODID);

    /** Set during {@link #init} after the native library is loaded. */
    private LinkAPILibrary api;

    // -----------------------------------------------------------------------
    // FML lifecycle
    // -----------------------------------------------------------------------

    /**
     * Pre-initialisation: extract the native library from the mod JAR so it
     * is available on the file system before JNA tries to load it.
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("[MumbleLink] Pre-initialising " + NAME + " " + VERSION);
        try {
            NativeLoader.extractAndLoad();
        } catch (Exception e) {
            LOGGER.severe("[MumbleLink] Failed to extract native library: "
                    + e.getMessage());
        }
    }

    /**
     * Initialisation: load the JNA wrapper and start the background link thread.
     *
     * <p>Using a background daemon thread instead of the FML event bus ensures
     * compatibility across all 1.6.4 Forge builds regardless of which EventBus
     * API variant (FML's own EventBus vs Guava EventBus) is present at runtime.
     * The thread polls Minecraft state at ~20 Hz, which is the rate Mumble's
     * Link plugin expects.
     */
    @Mod.EventHandler
    @SuppressWarnings("unchecked")
    public void init(FMLInitializationEvent event) {
        LOGGER.info("[MumbleLink] Initialising " + NAME + " " + VERSION);
        try {
            api = (LinkAPILibrary) Native.loadLibrary(
                    "LinkAPI", LinkAPILibrary.class);
            Thread t = new Thread(new TickHandler(api), "MumbleLink");
            t.setDaemon(true);
            t.start();
            LOGGER.info("[MumbleLink] Initialised successfully – "
                    + "link thread started");
        } catch (Exception e) {
            LOGGER.severe("[MumbleLink] Initialisation failed: "
                    + e.getMessage());
        }
    }
}
