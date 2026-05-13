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
import zsawyer.mumble.jna.LinkAPILibrary;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Background daemon thread that keeps Mumble's shared-memory link alive while
 * Minecraft is running a world.
 *
 * <p>This implementation polls Minecraft state every {@value #POLL_MS} ms so
 * that it works regardless of which Forge / FML event-bus API variant is
 * present at runtime.  The Mumble Link protocol requires position updates at
 * roughly 20 Hz (50 ms), so polling at 50 ms is sufficient.
 *
 * <p>Coordinate mapping:
 * <ul>
 *   <li>Minecraft X (East +)  → Mumble X</li>
 *   <li>Minecraft Y (Up +)    → Mumble Y</li>
 *   <li>Minecraft Z (South +) → Mumble Z</li>
 * </ul>
 */
public final class TickHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger("mumblelink");

    private static final String APP_NAME        = "Minecraft";
    private static final String APP_DESCRIPTION = "Minecraft 1.6.4 MumbleLink positional audio";

    /** Poll interval in milliseconds – 50 ms ≈ 20 Hz, the rate Mumble expects. */
    private static final long POLL_MS = 50L;

    private final LinkAPILibrary api;

    private boolean linked      = false;
    private String  lastContext = "";

    public TickHandler(LinkAPILibrary api) {
        this.api = api;
    }

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
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            if (linked) {
                try {
                    api.unlinkMumble();
                } catch (Exception e) {
                    LOGGER.fine("[MumbleLink] unlinkMumble error: " + e);
                }
                linked      = false;
                lastContext = "";
                LOGGER.info("[MumbleLink] Unlinked from Mumble (no active world)");
            }
            return;
        }

        if (!linked) {
            char[] name = toCharArray(APP_NAME, LinkAPILibrary.LINKAPI_MAX_NAME_LENGTH);
            char[] desc = toCharArray(APP_DESCRIPTION,
                    LinkAPILibrary.LINKAPI_MAX_DESCRIPTION_LENGTH);
            int err = api.initialize(name, desc, 2);
            if (err == LinkAPILibrary.LINKAPI_ERROR_CODE.LINKAPI_ERROR_CODE_NO_ERROR) {
                linked = true;
                LOGGER.info("[MumbleLink] Linked to Mumble – positional audio active");
            } else {
                // Mumble not running yet – log at FINE to avoid log spam.
                LOGGER.fine("[MumbleLink] Waiting for Mumble Link"
                        + " (initialize returned " + err + ")");
            }
            return;
        }

        EntityClientPlayerMP player = mc.thePlayer;

        // ---- Position (eye position in metres / blocks) ------------------
        float[] pos = {
            (float)  player.posX,
            (float) (player.posY + player.getEyeHeight()),
            (float)  player.posZ
        };

        // ---- Look vector from yaw / pitch --------------------------------
        // MC yaw:   0 = South (+Z), 90 = West (-X), clockwise looking down.
        // MC pitch: positive = looking down, negative = looking up.
        double yawRad   = Math.toRadians(player.rotationYaw);
        double pitchRad = Math.toRadians(player.rotationPitch);

        float[] front = {
            (float) (-Math.sin(yawRad) * Math.cos(pitchRad)),
            (float) (-Math.sin(pitchRad)),
            (float) ( Math.cos(yawRad) * Math.cos(pitchRad))
        };
        float[] top = { 0f, 1f, 0f };

        api.commitVectorsAvatarAsCamera(pos, front, top);

        // ---- Identity (player name) --------------------------------------
        api.commitIdentity(
                toCharArray(player.getCommandSenderName(),
                        LinkAPILibrary.LINKAPI_MAX_IDENTITY_LENGTH));

        // ---- Context (server address – controls who hears positional audio)
        String newCtx = buildContext(mc);
        if (!newCtx.equals(lastContext)) {
            byte[] ctxBytes;
            try {
                ctxBytes = newCtx.getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                ctxBytes = newCtx.getBytes();
            }
            int len = Math.min(ctxBytes.length, LinkAPILibrary.LINKAPI_MAX_CONTEXT_LENGTH);
            api.commitContext(Arrays.copyOf(ctxBytes, len), len);
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

    /**
     * Converts a String to a null-padded {@code char[]} of length {@code maxLen}.
     * The result is guaranteed to be null-terminated.
     */
    private static char[] toCharArray(String s, int maxLen) {
        char[] arr = new char[maxLen];
        int copy = Math.min(s.length(), maxLen - 1);
        s.getChars(0, copy, arr, 0);
        return arr;
    }
}
