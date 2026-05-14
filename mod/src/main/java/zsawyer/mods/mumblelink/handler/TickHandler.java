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
package zsawyer.mods.mumblelink.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import zsawyer.mods.mumblelink.MumbleLink;

import java.io.UnsupportedEncodingException;
import java.util.logging.Logger;

/**
 * Background daemon thread that keeps Mumble's shared-memory link alive while
 * Minecraft is running a world.
 *
 * <p>This implementation polls Minecraft state every {@value #POLL_MS} ms and
 * writes positional audio data directly to Mumble's shared-memory segment via
 * {@link MumbleLink}.  It does not depend on any intermediate native helper
 * library (DLL/SO).
 *
 * <p>Coordinate mapping:
 * <ul>
 *   <li>Minecraft X (East +)   → Mumble X</li>
 *   <li>Minecraft Y (Up +)     → Mumble Y</li>
 *   <li>Minecraft Z (South +)  → Mumble Z</li>
 * </ul>
 */
public final class TickHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger("mumblelink");

    private static final String APP_NAME        = "Minecraft";
    private static final String APP_DESCRIPTION = "Minecraft 1.6.4 MumbleLink positional audio";

    /** Poll interval in milliseconds – 50 ms = 20 Hz, the rate Mumble expects. */
    private static final long POLL_MS = 50L;

    /** Live link to Mumble shared memory; null when not yet opened. */
    private MumbleLink link = null;

    /** Cached context string to avoid writing on every tick. */
    private String lastContext = "";

    // -----------------------------------------------------------------------
    // Runnable
    // -----------------------------------------------------------------------

    @Override
    public void run() {
        LOGGER.info("[MumbleLink] Link thread started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                tick();
            } catch (Exception e) {
                LOGGER.warning("[MumbleLink] Error in link thread: " + e);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOGGER.info("[MumbleLink] Link thread stopped");
    }

    // -----------------------------------------------------------------------

    private void tick() {
        Minecraft mc = Minecraft.getMinecraft();

        // Unlink if no world is loaded (main menu, loading screen, etc.)
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            if (link != null) {
                link.unlink();
                link        = null;
                lastContext = "";
                LOGGER.info("[MumbleLink] Unlinked from Mumble (no active world)");
            }
            return;
        }

        // Open shared memory if not yet connected (retried every poll until
        // Mumble starts and creates the segment).
        if (link == null) {
            link = MumbleLink.tryOpen();
            if (link == null) {
                // Mumble not running yet; suppress spam by logging at FINE.
                LOGGER.fine("[MumbleLink] Waiting for Mumble to start...");
                return;
            }
            link.init(APP_NAME, APP_DESCRIPTION);
            LOGGER.info("[MumbleLink] Linked to Mumble – positional audio active");
            lastContext = "";
        }

        // ---- Positional data -----------------------------------------------
        EntityClientPlayerMP player = mc.thePlayer;

        double yawRad   = Math.toRadians(player.rotationYaw);
        double pitchRad = Math.toRadians(player.rotationPitch);

        // MC yaw 0 = South (+Z), increases clockwise when viewed from above.
        // MC pitch > 0 = looking down; < 0 = looking up.
        float frontX = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
        float frontY = (float) (-Math.sin(pitchRad));
        float frontZ = (float) ( Math.cos(yawRad) * Math.cos(pitchRad));

        link.update(
                (float)  player.posX,
                (float) (player.posY + player.getEyeHeight()),
                (float)  player.posZ,
                frontX, frontY, frontZ);

        // ---- Identity (player name) ----------------------------------------
        link.setIdentity(player.getCommandSenderName());

        // ---- Context (server address; only write on change) ----------------
        String newCtx = buildContext(mc);
        if (!newCtx.equals(lastContext)) {
            byte[] ctxBytes;
            try {
                ctxBytes = newCtx.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                ctxBytes = newCtx.getBytes();
            }
            link.setContext(ctxBytes);
            lastContext = newCtx;
        }
    }

    // -----------------------------------------------------------------------

    private static String buildContext(Minecraft mc) {
        try {
            if (mc.getCurrentServerData() != null) {
                return "mumblelink|" + mc.getCurrentServerData().serverIP;
            }
            if (mc.isIntegratedServerRunning()) {
                return "mumblelink|singleplayer";
            }
        } catch (Exception e) {
            LOGGER.fine("[MumbleLink] Could not determine context: " + e);
        }
        return "mumblelink|unknown";
    }
}

