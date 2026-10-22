// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.citron.citron_emu.features.input.model

// Must match enum in src/core/hid/hid_types.h
// NOTE: the upstream enum carries an @StringRes nameId; we drop it here to avoid a
// dependency on R.string resources that do not exist in this stripped build.
enum class NpadStyleIndex(val int: Int) {
    None(0),
    Fullkey(3),
    Handheld(4),
    HandheldNES(4),
    JoyconDual(5),
    JoyconLeft(6),
    JoyconRight(7),
    GameCube(8),
    Pokeball(9),
    NES(10),
    SNES(12),
    N64(13),
    SegaGenesis(14),
    SystemExt(32),
    System(33);

    companion object {
        fun from(int: Int): NpadStyleIndex = entries.firstOrNull { it.int == int } ?: None
    }
}
