// Minimal stand-in for the upstream settings enum. Only the entries used by the
// input overlay are declared; each returns a default value (optionally persisted
// via a private SharedPreferences) instead of going through NativeConfig.

package org.citron.citron_emu.features.settings.model

import android.content.Context
import org.citron.citron_emu.NativeLibrary

enum class IntSetting(val key: String, private val default: Int) {
    OVERLAY_SCALE("overlay_scale", 50),
    OVERLAY_OPACITY("overlay_opacity", 100);

    fun getInt(): Int {
        val prefs = NativeLibrary.appContext!!.getSharedPreferences("wn_overlay", Context.MODE_PRIVATE)
        return prefs.getInt(key, default)
    }

    fun setInt(value: Int) {
        val prefs = NativeLibrary.appContext!!.getSharedPreferences("wn_overlay", Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).apply()
    }
}
