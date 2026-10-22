package org.citron.citron_emu.model

// Minimal stub matching the JNI surface expected by libcitron-android.so
// (constructor (ZStringStringIStringStringZ)V with fields
// enabled/name/version/type/programId/titleId).
data class Patch(
    var enabled: Boolean,
    val name: String,
    val version: String,
    val type: Int,
    val programId: String,
    val titleId: String,
    val removable: Boolean
)
