// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.citron.citron_emu.utils

/**
 * JNI bridge onto libcitron-android.so's game_metadata.cpp
 * (Java_org_citron_citron_1emu_utils_GameMetadata_*). The package + object name
 * MUST match the upstream emulator exactly so the native symbols resolve.
 *
 * Used by the host app's "add game" flow to read a Switch NSP/XCI/NCA/NRO's title
 * and cover art without launching it: [getTitle] returns the NACP application name
 * (control.nacp) and [getIcon] returns the raw icon_*.dat bytes (JPEG), decoded by
 * the caller.
 *
 * The core library must already be resident — call
 * org.citron.citron_emu.wn.CitronBootstrap.ensureNativeForMetadata(context) (or the
 * full ensure()) first; invoking these before the .so is loaded throws
 * UnsatisfiedLinkError.
 */
object GameMetadata {
    external fun getIsValid(path: String): Boolean

    external fun getTitle(path: String): String

    external fun getProgramId(path: String): String

    external fun getDeveloper(path: String): String

    external fun getVersion(path: String, reload: Boolean): String

    external fun getIcon(path: String): ByteArray

    external fun getIsHomebrew(path: String): Boolean

    external fun resetMetadata()
}
