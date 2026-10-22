package org.citron.citron_emu.model

// Minimal stub matching the JNI surface expected by libcitron-android.so
// (constructor (String;String;String;String;String;Z)V with fields
// title/path/programId/developer/version/isHomebrew).
data class Game(
    val title: String = "",
    val path: String,
    val programId: String = "",
    val developer: String = "",
    var version: String = "",
    val isHomebrew: Boolean = false
)
