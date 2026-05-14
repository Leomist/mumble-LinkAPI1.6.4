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
import net.minecraft.util.ChatComponentText;
import zsawyer.mods.mumblelink.MumbleLink;
import zsawyer.mods.mumblelink.MumbleLinkMod;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * <p>State transitions trigger formatted in-game chat messages so the player
 * always knows the current Mumble link status:
 * <ul>
 *   <li>World join   – header banner + current status (searching or active)</li>
 *   <li>Link established  – green ACTIVE message with server/player details</li>
 *   <li>Link lost    – red INACTIVE message with retry hint</li>
 *   <li>Link restored     – green RESTORED message</li>
 *   <li>Context change    – gold UPDATE message showing new server context</li>
 * </ul>
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

    /** Chat format codes (§ sequences). */
    private static final String C_RESET  = "\u00a7r";
    private static final String C_GREEN  = "\u00a7a";
    private static final String C_RED    = "\u00a7c";
    private static final String C_GOLD   = "\u00a76";
    private static final String C_GRAY   = "\u00a77";
    private static final String C_WHITE  = "\u00a7f";
    private static final String C_BOLD   = "\u00a7l";
    private static final String C_YELLOW = "\u00a7e";

    /** Prefix used on every chat message this mod sends. */
    private static final String PREFIX =
            C_GOLD + C_BOLD + "[MumbleLink]" + C_RESET + " ";

    /** Live link to Mumble shared memory; null when not yet opened. */
    private MumbleLink link = null;

    /** Cached context string to avoid writing on every tick. */
    private String lastContext = "";

    /** Whether the player was in a world on the previous tick. */
    private boolean wasInWorld = false;

    /**
     * Whether the link was active when it was last opened this session.
     * Used to distinguish "established for the first time" vs "restored
     * after a drop" so each gets the right message.
     */
    private boolean wasPreviouslyLinked = false;

    /**
     * Thread-safe queue for chat messages to be delivered to the player.
     * Messages are enqueued by state-transition logic and dequeued each
     * tick when mc.thePlayer is available.
     */
    private final ConcurrentLinkedQueue<String> pendingMessages =
            new ConcurrentLinkedQueue<String>();

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

        boolean inWorld = mc != null && mc.thePlayer != null && mc.theWorld != null;

        // ---- Transition: player left world (main menu / disconnect) --------
        if (!inWorld) {
            if (wasInWorld) {
                if (link != null) {
                    link.unlink();
                    link        = null;
                    lastContext = "";
                    LOGGER.info("[MumbleLink] Unlinked (left world)");
                }
                wasPreviouslyLinked = false;
            }
            wasInWorld = false;
            return;
        }

        EntityClientPlayerMP player = mc.thePlayer;

        // ---- Transition: player just entered a world -----------------------
        if (!wasInWorld) {
            wasInWorld = true;
            sendWorldJoinBanner(mc);
        }

        // ---- Drain any queued chat messages --------------------------------
        drainMessages(player);

        // ---- Open shared memory (retry every tick until Mumble is running) -
        if (link == null) {
            link = MumbleLink.tryOpen();
            if (link == null) {
                LOGGER.fine("[MumbleLink] Waiting for Mumble to start...");
                return;
            }
            // Link just opened.
            link.init(APP_NAME, APP_DESCRIPTION);
            lastContext = "";
            String ctx = buildContext(mc);
            if (wasPreviouslyLinked) {
                // Re-established after a drop.
                enqueue(buildRestoredMessage(ctx, player.getCommandSenderName()));
                LOGGER.info("[MumbleLink] Link restored – positional audio active");
            } else {
                // First time linked since world join.
                enqueue(buildActiveMessage(ctx, player.getCommandSenderName()));
                LOGGER.info("[MumbleLink] Linked to Mumble – positional audio active");
            }
            wasPreviouslyLinked = true;
        }

        // ---- Positional data (catch write errors as link-loss) ---------------
        try {
            double yawRad   = Math.toRadians(player.rotationYaw);
            double pitchRad = Math.toRadians(player.rotationPitch);

            float frontX = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
            float frontY = (float) (-Math.sin(pitchRad));
            float frontZ = (float) ( Math.cos(yawRad) * Math.cos(pitchRad));

            link.update(
                    (float)  player.posX,
                    (float) (player.posY + player.getEyeHeight()),
                    (float)  player.posZ,
                    frontX, frontY, frontZ);

            // ---- Identity (player name) ------------------------------------
            link.setIdentity(player.getCommandSenderName());

            // ---- Context (server address; only write and notify on change) -
            String newCtx = buildContext(mc);
            if (!newCtx.equals(lastContext)) {
                byte[] ctxBytes;
                try {
                    ctxBytes = newCtx.getBytes("UTF-8");
                } catch (UnsupportedEncodingException e) {
                    ctxBytes = newCtx.getBytes();
                }
                link.setContext(ctxBytes);

                // Notify the player about the context change (but suppress the
                // very first context write that happens right after linking).
                if (!lastContext.isEmpty()) {
                    enqueue(buildContextChangedMessage(newCtx));
                    LOGGER.info("[MumbleLink] Context updated: " + newCtx);
                }
                lastContext = newCtx;
            }
        } catch (Exception e) {
            // Write failure means the shared memory is no longer accessible
            // (e.g. Mumble was closed while the world was loaded).
            LOGGER.warning("[MumbleLink] Link error – shared memory lost: " + e);
            enqueue(buildInactiveMessage());
            link        = null;
            lastContext = "";
        }
    }

    // -----------------------------------------------------------------------
    // Chat delivery
    // -----------------------------------------------------------------------

    /** Queues a message for delivery on the next tick. */
    private void enqueue(String message) {
        pendingMessages.add(message);
    }

    /**
     * Drains all pending chat messages to the player's chat GUI.
     * Called each tick when mc.thePlayer is confirmed non-null.
     */
    private void drainMessages(EntityClientPlayerMP player) {
        String msg;
        while ((msg = pendingMessages.poll()) != null) {
            try {
                player.addChatMessage(new ChatComponentText(msg));
            } catch (Exception e) {
                LOGGER.fine("[MumbleLink] Could not send chat message: " + e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Message builders
    // -----------------------------------------------------------------------

    /**
     * Sends the initial status banner immediately after the player enters a
     * world, telling them whether Mumble is already linked or still being
     * searched for.
     */
    private void sendWorldJoinBanner(Minecraft mc) {
        String os    = System.getProperty("os.name", "unknown");
        String ver   = MumbleLinkMod.VERSION;

        // Line 1: header
        enqueue(C_GOLD + C_BOLD + "--- MumbleLink v" + ver
                + " Positional Audio ---" + C_RESET);

        // Line 2: attempt status
        if (link != null) {
            // Already linked from a previous world in the same session
            // (unusual, but handle gracefully).
            String ctx = buildContext(mc);
            enqueue(buildActiveMessage(ctx,
                    mc.thePlayer.getCommandSenderName()));
        } else {
            enqueue(PREFIX
                    + C_YELLOW + "Searching for Mumble..." + C_RESET
                    + C_GRAY + "  (OS: " + os + ")" + C_RESET);
            enqueue(C_GRAY
                    + "  Ensure Mumble is running with the "
                    + C_WHITE + "Link" + C_GRAY
                    + " plugin enabled for positional audio." + C_RESET);
        }
    }

    /** Builds the "link ACTIVE" message shown when Mumble is first found. */
    private static String buildActiveMessage(String context, String playerName) {
        return PREFIX
                + C_GREEN + C_BOLD + "Positional audio: ACTIVE" + C_RESET + "\n"
                + C_GRAY  + "  Server: " + C_WHITE + context    + C_RESET
                + C_GRAY  + "  |  Player: " + C_WHITE + playerName + C_RESET
                + C_GRAY  + "  |  Protocol: Mumble Link v2" + C_RESET;
    }

    /** Builds the "link RESTORED" message after Mumble reconnects mid-session. */
    private static String buildRestoredMessage(String context, String playerName) {
        return PREFIX
                + C_GREEN + "Positional audio: RESTORED" + C_RESET + "\n"
                + C_GRAY  + "  Server: " + C_WHITE + context    + C_RESET
                + C_GRAY  + "  |  Player: " + C_WHITE + playerName + C_RESET;
    }

    /** Builds the "link INACTIVE" message when Mumble closes mid-session. */
    private static String buildInactiveMessage() {
        return PREFIX
                + C_RED + "Positional audio: INACTIVE" + C_RESET + "\n"
                + C_GRAY + "  Mumble closed or Link plugin disabled."
                + "  Will reconnect automatically." + C_RESET;
    }

    /** Builds the context-changed update message. */
    private static String buildContextChangedMessage(String newContext) {
        return PREFIX
                + C_GOLD + "Context updated: " + C_WHITE + newContext + C_RESET;
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

