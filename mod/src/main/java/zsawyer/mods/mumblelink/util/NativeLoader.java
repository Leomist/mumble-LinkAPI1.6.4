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

/**
 * @deprecated No longer used.  As of v1.1.3 the Mumble Link protocol is
 *             implemented directly in {@code zsawyer.mods.mumblelink.MumbleLink}
 *             using JNA Windows / POSIX API calls; no intermediate native
 *             helper library (DLL/SO) is needed.
 */
@Deprecated
public final class NativeLoader {
    private NativeLoader() {}
}

