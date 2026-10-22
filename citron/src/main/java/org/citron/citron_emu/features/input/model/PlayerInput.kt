package org.citron.citron_emu.features.input.model

// Minimal stub matching the JNI surface expected by libcitron-android.so
// (constructor (Z[String;[String;[String;ZIJJJJString;Z)V with the
// connected/buttons/analogs/motions/vibrationEnabled/vibrationStrength/
// bodyColorLeft/bodyColorRight/buttonColorLeft/buttonColorRight/
// profileName/useSystemVibrator fields).
data class PlayerInput(
    var connected: Boolean,
    var buttons: Array<String>,
    var analogs: Array<String>,
    var motions: Array<String>,
    var vibrationEnabled: Boolean,
    var vibrationStrength: Int,
    var bodyColorLeft: Long,
    var bodyColorRight: Long,
    var buttonColorLeft: Long,
    var buttonColorRight: Long,
    var profileName: String,
    var useSystemVibrator: Boolean
)
