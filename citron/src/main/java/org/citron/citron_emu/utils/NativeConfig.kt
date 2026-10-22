// Thin JNI bridge for NativeConfig (global + per-game ini settings).
// Package/class must stay `org.citron.citron_emu.utils.NativeConfig` to match
// the `Java_org_citron_citron_1emu_utils_NativeConfig_*` symbols in
// libcitron-android.so. Signatures copied from
// D:/workspace/emulator/src/android/app/src/main/java/org/citron/citron_emu/utils/NativeConfig.kt
package org.citron.citron_emu.utils

import org.citron.citron_emu.model.GameDir
import org.citron.citron_emu.overlay.model.OverlayControlData
import org.citron.citron_emu.features.input.model.PlayerInput

object NativeConfig {
    // ---- external (resolved from the so by name) ----
    @Synchronized
    external fun initializeGlobalConfig()

    @Synchronized
    external fun unloadGlobalConfig()

    @Synchronized
    external fun reloadGlobalConfig()

    @Synchronized
    external fun saveGlobalConfig()

    @Synchronized
    external fun initializePerGameConfig(programId: String, fileName: String)

    @Synchronized
    external fun isPerGameConfigLoaded(): Boolean

    @Synchronized
    external fun savePerGameConfig()

    @Synchronized
    external fun unloadPerGameConfig()

    @Synchronized
    external fun getBoolean(key: String, needsGlobal: Boolean): Boolean

    @Synchronized
    external fun setBoolean(key: String, value: Boolean)

    @Synchronized
    external fun getByte(key: String, needsGlobal: Boolean): Byte

    @Synchronized
    external fun setByte(key: String, value: Byte)

    @Synchronized
    external fun getShort(key: String, needsGlobal: Boolean): Short

    @Synchronized
    external fun setShort(key: String, value: Short)

    @Synchronized
    external fun getInt(key: String, needsGlobal: Boolean): Int

    @Synchronized
    external fun setInt(key: String, value: Int)

    @Synchronized
    external fun getFloat(key: String, needsGlobal: Boolean): Float

    @Synchronized
    external fun setFloat(key: String, value: Float)

    @Synchronized
    external fun getLong(key: String, needsGlobal: Boolean): Long

    @Synchronized
    external fun setLong(key: String, value: Long)

    @Synchronized
    external fun getString(key: String, needsGlobal: Boolean): String

    @Synchronized
    external fun setString(key: String, value: String)

    @Synchronized
    external fun usingGlobal(key: String): Boolean

    @Synchronized
    external fun setGlobal(key: String, global: Boolean)

    @Synchronized
    external fun getGameDirs(): Array<GameDir>

    @Synchronized
    external fun setGameDirs(dirs: Array<GameDir>)

    @Synchronized
    external fun addGameDir(dir: GameDir)

    @Synchronized
    external fun getDisabledAddons(programId: String): Array<String>

    @Synchronized
    external fun setDisabledAddons(programId: String, disabledAddons: Array<String>)

    @Synchronized
    external fun getOverlayControlData(): Array<OverlayControlData>

    @Synchronized
    external fun setOverlayControlData(overlayControlData: Array<OverlayControlData>)

    @Synchronized
    external fun getInputSettings(global: Boolean): Array<PlayerInput>

    @Synchronized
    external fun setInputSettings(value: Array<PlayerInput>, global: Boolean)

    @Synchronized
    external fun saveControlPlayerValues()

    // ---- non-external helpers (queried by UI, not resolved from the so) ----
    fun getIsRuntimeModifiable(key: String): Boolean = false
    fun getPairedSettingKey(key: String): String = ""
    fun getIsSwitchable(key: String): Boolean = false
    fun getIsSaveable(key: String): Boolean = true
    fun getDefaultToString(key: String): String = ""
}
