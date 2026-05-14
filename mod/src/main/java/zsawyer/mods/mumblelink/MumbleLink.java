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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.logging.Logger;

/**
 * Opens Mumble's shared-memory segment and writes positional-audio data
 * directly using JNA – no intermediate native helper library (DLL/SO) needed.
 *
 * <p>The <a href="https://wiki.mumble.info/wiki/Link">Mumble Link protocol</a>
 * (v2) requires the game to:
 * <ol>
 *   <li>Open the "MumbleLink" shared-memory object (Windows) or
 *       "/MumbleLink.{uid}" (Linux/macOS).</li>
 *   <li>Write {@code uiVersion = 2}, an application name, and a description
 *       <em>once</em> to identify the game.</li>
 *   <li>Write avatar/camera position and orientation vectors and increment
 *       {@code uiTick} every frame (~20 Hz).</li>
 * </ol>
 *
 * <h3>Shared-memory layout</h3>
 * <pre>
 * struct LinkedMem {
 *   uint32_t uiVersion;            // +0  (4)
 *   uint32_t uiTick;               // +4  (4)
 *   float    fAvatarPosition[3];   // +8  (12)
 *   float    fAvatarFront[3];      // +20 (12)
 *   float    fAvatarTop[3];        // +32 (12)
 *   wchar_t  name[256];            // +44 (256 * WCHAR_SIZE)
 *   float    fCameraPosition[3];   // +44+256*W (12)
 *   float    fCameraFront[3];      //          (12)
 *   float    fCameraTop[3];        //          (12)
 *   wchar_t  identity[256];        // (256 * WCHAR_SIZE)
 *   uint32_t context_len;          // (4)
 *   uint8_t  context[256];         // (256)
 *   wchar_t  description[2048];    // (2048 * WCHAR_SIZE)
 * };
 * </pre>
 * {@code WCHAR_SIZE} is 2 on Windows (UTF-16) and 4 on Linux/macOS (UTF-32).
 */
public final class MumbleLink {

    private static final Logger LOGGER = Logger.getLogger("mumblelink");

    // ---- wchar_t width: 2 bytes on Windows (UTF-16), 4 on POSIX (UTF-32) ---
    private static final int WCHAR = Platform.isWindows() ? 2 : 4;

    // ---- Shared-memory layout byte offsets ----------------------------------
    // These are constant at class-init time; note that WCHAR drives the layout.
    private static final int OFF_VERSION      = 0;
    private static final int OFF_TICK         = 4;
    private static final int OFF_AVATAR_POS   = 8;
    private static final int OFF_AVATAR_FRONT = 20;
    private static final int OFF_AVATAR_TOP   = 32;
    private static final int OFF_NAME         = 44;
    private static final int OFF_CAMERA_POS   = OFF_NAME         + 256 * WCHAR;
    private static final int OFF_CAMERA_FRONT = OFF_CAMERA_POS   + 12;
    private static final int OFF_CAMERA_TOP   = OFF_CAMERA_FRONT + 12;
    private static final int OFF_IDENTITY     = OFF_CAMERA_TOP   + 12;
    private static final int OFF_CONTEXT_LEN  = OFF_IDENTITY     + 256 * WCHAR;
    private static final int OFF_CONTEXT      = OFF_CONTEXT_LEN  + 4;
    private static final int OFF_DESCRIPTION  = OFF_CONTEXT      + 256;
    static final int         MEM_SIZE         = OFF_DESCRIPTION  + 2048 * WCHAR;

    // ---- Windows constants --------------------------------------------------
    private static final int FILE_MAP_ALL_ACCESS = 0x000F001F;

    // ---- POSIX constants (Linux / macOS) ------------------------------------
    private static final int  O_RDWR    = 2;
    private static final int  PROT_READ = 1, PROT_WRITE = 2;
    private static final int  MAP_SHARED = 1;

    // ---- Windows kernel32 JNA interface ------------------------------------
    private interface Kernel32 extends StdCallLibrary {
        /**
         * Opens an existing named file-mapping object.
         * Returns {@code null} (NULL handle) if not found (Mumble not running).
         */
        Pointer OpenFileMappingW(int dwDesiredAccess, boolean bInheritHandle,
                WString lpName);

        /** Maps a view of the named mapping. Returns {@code null} on failure. */
        Pointer MapViewOfFile(Pointer hFileMappingObject, int dwDesiredAccess,
                int dwFileOffsetHigh, int dwFileOffsetLow, int dwNumberOfBytesToMap);
    }

    // ---- Linux / macOS libc JNA interface ----------------------------------
    private interface LibC extends Library {
        int shm_open(String name, int oflag, int mode);
        Pointer mmap(Pointer addr, NativeLong length, int prot, int flags,
                int fd, NativeLong offset);
        int getuid();
    }

    // ---- Instance ----------------------------------------------------------

    private final Pointer mem;
    private int tick = 0;

    private MumbleLink(Pointer mem) {
        this.mem = mem;
    }

    // ---- Factory -----------------------------------------------------------

    /**
     * Tries to open Mumble's shared-memory segment.
     *
     * @return a new {@code MumbleLink} on success, or {@code null} if Mumble
     *         is not running or the Link plugin is disabled.
     */
    public static MumbleLink tryOpen() {
        try {
            if (Platform.isWindows())                    return openWindows();
            if (Platform.isLinux() || Platform.isMac()) return openUnix();
        } catch (Throwable t) {
            LOGGER.fine("[MumbleLink] Cannot open Mumble shared memory: " + t);
        }
        return null;
    }

    private static MumbleLink openWindows() {
        Kernel32 k32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
        Pointer hMap = k32.OpenFileMappingW(FILE_MAP_ALL_ACCESS, false,
                new WString("MumbleLink"));
        if (hMap == null) {
            return null;          // Mumble not running / Link plugin disabled
        }
        Pointer view = k32.MapViewOfFile(hMap, FILE_MAP_ALL_ACCESS, 0, 0, MEM_SIZE);
        return view != null ? new MumbleLink(view) : null;
    }

    private static MumbleLink openUnix() {
        LibC libc = loadLibC();
        if (libc == null) return null;

        int uid = libc.getuid();
        // uid_t is unsigned 32-bit; guard against JNA returning a negative int
        // for UIDs > Integer.MAX_VALUE (extremely rare, but correct to handle).
        String uidStr = Long.toString(uid & 0xFFFFFFFFL);
        int fd = libc.shm_open("/MumbleLink." + uidStr, O_RDWR, 0);
        if (fd < 0) return null;    // Mumble not running

        NativeLong size   = new NativeLong(MEM_SIZE);
        NativeLong offset = new NativeLong(0);
        Pointer view = libc.mmap(null, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, offset);

        // mmap returns MAP_FAILED (void*)-1 on error
        if (view == null || Pointer.nativeValue(view) == -1L) return null;
        return new MumbleLink(view);
    }

    /** Tries to find a libc that has {@code shm_open} (glibc 2.2+ has it in libc). */
    private static LibC loadLibC() {
        for (String name : new String[]{"c", "rt"}) {
            try {
                return (LibC) Native.loadLibrary(name, LibC.class);
            } catch (UnsatisfiedLinkError ignored) {
                // try next candidate
            }
        }
        return null;
    }

    // ---- Public API --------------------------------------------------------

    /**
     * Writes the application name and description to the shared memory and
     * sets {@code uiVersion = 2} to activate the Mumble Link.
     *
     * <p>Name and description are written before the version so that Mumble
     * always reads a consistent snapshot the moment the link becomes active.
     *
     * @param name        display name for the application (max 255 chars)
     * @param description free-text description (max 2047 chars)
     */
    public void init(String name, String description) {
        writeWString(OFF_NAME,        name,        256);
        writeWString(OFF_DESCRIPTION, description, 2048);
        mem.setInt(OFF_TICK, 0);
        tick = 0;
        // Write version last so Mumble reads consistent name/description.
        mem.setInt(OFF_VERSION, 2);
    }

    /**
     * Writes one frame of positional audio data and increments
     * {@code uiTick} to signal Mumble that the data is fresh.
     *
     * <p>Must be called approximately every 50 ms (20 Hz) while a world is
     * loaded.  Minecraft coordinate axes map directly to Mumble's axes.
     *
     * @param x      player eye X (East = positive)
     * @param y      player eye Y (Up = positive)
     * @param z      player eye Z (South = positive)
     * @param frontX look-direction X component (unit vector)
     * @param frontY look-direction Y component (unit vector)
     * @param frontZ look-direction Z component (unit vector)
     */
    public void update(float x, float y, float z,
                       float frontX, float frontY, float frontZ) {
        // avatar == camera (first-person view)
        writeVec(OFF_AVATAR_POS,   x, y, z);
        writeVec(OFF_AVATAR_FRONT, frontX, frontY, frontZ);
        writeVec(OFF_AVATAR_TOP,   0f, 1f, 0f);
        writeVec(OFF_CAMERA_POS,   x, y, z);
        writeVec(OFF_CAMERA_FRONT, frontX, frontY, frontZ);
        writeVec(OFF_CAMERA_TOP,   0f, 1f, 0f);
        // Increment tick LAST — Mumble uses tick change to detect a live game.
        tick++;
        mem.setInt(OFF_TICK, tick);
    }

    /**
     * Sets the identity field (typically the player name).
     *
     * @param identity string unique to this player; max 255 characters
     */
    public void setIdentity(String identity) {
        writeWString(OFF_IDENTITY, identity, 256);
    }

    /**
     * Sets the context bytes (used by Mumble to decide which players can hear
     * each other positionally — usually the server address).
     *
     * @param context raw bytes; at most 256 bytes
     */
    public void setContext(byte[] context) {
        int len = Math.min(context.length, 256);
        mem.setInt(OFF_CONTEXT_LEN, len);
        mem.write(OFF_CONTEXT, context, 0, len);
    }

    /**
     * Signals Mumble that the game has stopped by writing {@code uiVersion = 0}.
     */
    public void unlink() {
        mem.setInt(OFF_VERSION, 0);
    }

    // ---- Private helpers ---------------------------------------------------

    private void writeVec(int off, float x, float y, float z) {
        mem.setFloat(off,      x);
        mem.setFloat(off + 4,  y);
        mem.setFloat(off + 8,  z);
    }

    /**
     * Writes a Java string as a null-terminated wide-character sequence.
     *
     * @param off      byte offset into {@link #mem}
     * @param s        the string value
     * @param maxChars maximum number of wide characters including the NUL terminator
     */
    private void writeWString(int off, String s, int maxChars) {
        int n = Math.min(s.length(), maxChars - 1);
        for (int i = 0; i < n; i++) {
            writeWChar(off + i * WCHAR, s.charAt(i));
        }
        writeWChar(off + n * WCHAR, '\0');
    }

    private void writeWChar(int off, char c) {
        if (WCHAR == 2) {
            mem.setShort(off, (short) c);
        } else {
            mem.setInt(off, c);
        }
    }
}
