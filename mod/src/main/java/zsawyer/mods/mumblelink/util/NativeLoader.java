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
package zsawyer.mods.mumblelink.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Extracts the appropriate native LinkAPI library from the mod JAR at runtime
 * and configures JNA to find it.  Supports Windows 32/64-bit and Linux 64-bit.
 */
public final class NativeLoader {

    private static final Logger LOGGER = Logger.getLogger("mumblelink");

    /** Set once the library has been extracted and the JNA path configured. */
    private static File extractDir;

    private NativeLoader() {}

    /**
     * Extracts the native library once (idempotent) and sets
     * {@code jna.library.path} so that JNA can locate it by name.
     *
     * @throws IOException if the resource is not found or extraction fails
     */
    public static synchronized void extractAndLoad() throws IOException {
        if (extractDir != null) {
            return; // already extracted in this JVM session
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64bit = osArch.contains("64") || osArch.contains("amd64")
                || osArch.contains("x86_64");

        String resourcePath;
        String libFileName;

        if (osName.contains("windows")) {
            libFileName = "LinkAPI.dll";
            resourcePath = is64bit
                    ? "/native/windows/amd64/" + libFileName
                    : "/native/windows/x86/" + libFileName;
        } else if (osName.contains("linux")) {
            libFileName = "libLinkAPI.so";
            resourcePath = "/native/linux/amd64/" + libFileName;
        } else if (osName.contains("mac")) {
            libFileName = "libLinkAPI.dylib";
            resourcePath = "/native/osx/amd64/" + libFileName;
        } else {
            throw new UnsupportedOperationException(
                    "[MumbleLink] Unsupported operating system: " + osName);
        }

        InputStream in = NativeLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException(
                    "[MumbleLink] Native library resource not found in JAR: "
                    + resourcePath);
        }

        File tmpDir = new File(System.getProperty("java.io.tmpdir"),
                "mumblelink-native");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw new IOException(
                    "[MumbleLink] Could not create temp directory: " + tmpDir);
        }

        File libFile = new File(tmpDir, libFileName);
        try {
            FileOutputStream out = new FileOutputStream(libFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }

        // Prepend the temp dir to jna.library.path so JNA finds the library
        // by its short name when Native.loadLibrary("LinkAPI", ...) is called.
        String existing = System.getProperty("jna.library.path", "");
        String newPath  = libFile.getParent();
        if (!existing.isEmpty()) {
            newPath = newPath + File.pathSeparator + existing;
        }
        System.setProperty("jna.library.path", newPath);

        extractDir = tmpDir;
        LOGGER.info("[MumbleLink] Native library extracted to: "
                + libFile.getAbsolutePath());
    }
}
