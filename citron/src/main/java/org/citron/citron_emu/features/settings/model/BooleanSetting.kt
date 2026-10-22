// Minimal stand-in for the upstream settings enum. Only the entries used by the
// input overlay are declared; each returns a default value (optionally persisted
// via a private SharedPreferences) instead of going through NativeConfig.

package org.citron.citron_emu.features.settings.model

import android.content.Context
import org.citron.citron_emu.NativeLibrary

enum class BooleanSetting(val key: String, private val default: Boolean) {
    SHOW_INPUT_OVERLAY("show_input_overlay", true),
    TOUCHSCREEN("touchscreen", true),
    DPAD_SLIDE("dpad_slide", true),
    HAPTIC_FEEDBACK("haptic_feedback", false),
    JOYSTICK_REL_CENTER("joystick_rel_center", false);

    fun getBoolean(): Boolean {
        val prefs = NativeLibrary.appContext!!.getSharedPreferences("wn_overlay", Context.MODE_PRIVATE)
        return prefs.getBoolean(key, default)
    }

    fun setBoolean(value: Boolean) {
        val prefs = NativeLibrary.appContext!!.getSharedPreferences("wn_overlay", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
    }
}
