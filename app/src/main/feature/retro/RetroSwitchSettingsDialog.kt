package com.winlator.cmod.feature.retro

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Shortcut

/**
 * Switch-specific per-game settings dialog.
 *
 * Shares the exact shell and visual style with [RetroSettingsDialog] (same ContentDialog
 * window, WinNativeTheme, sidebar/portrait-chip layout, save/cancel actions, artwork/BIOS
 * pickers and D-pad pane navigation) — it only restricts the section list to the tabs that
 * make sense for the citron core:
 *
 *   常规 (General) · 图形 (Graphics) · 帧生成 (Frame generation) · 性能 (Performance) · HUD
 *
 * Everything else (state load/save, artwork, BIOS import, frame-gen, HUD) is inherited, so
 * per-game values keep being stored on the shortcut exactly like the other retro systems.
 */
class RetroSwitchSettingsDialog(
    activity: Activity,
    shortcut: Shortcut,
) : RetroSettingsDialog(
    activity,
    shortcut,
    onlySections = SWITCH_SECTIONS,
) {
    companion object {
        val SWITCH_SECTIONS: Set<RetroSectionId> =
            setOf(
                RetroSectionId.GENERAL,
                RetroSectionId.GRAPHICS,
                RetroSectionId.FRAMEGEN,
                RetroSectionId.PERFORMANCE,
                RetroSectionId.HUD,
            )
    }
}

// Switch (Citron) settings. Persist to the "CITRON" SharedPreferences; the values
// are applied to Citron's config.ini by CitronHost.applyBootSettings before run().

// "Graphics" tab: resolution scale + VSync. citron on Android is Vulkan-only (the OpenGL
// backend was removed upstream), so there is no renderer choice to expose.
@Composable
internal fun RetroCitronGraphicsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE) }
    var version by remember { mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); version++ }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_video))
        val scales = listOf(1, 2, 3, 4, 5, 6)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_resolution_scale),
            scales.map { "${it}x" },
            scales.indexOf(prefs.getInt("wn.switch.resolution", 1)).coerceAtLeast(0),
        ) { putInt("wn.switch.resolution", scales[it]) }
        val vsync = listOf(0, 1, 2)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_vsync),
            listOf(
                stringResource(R.string.retro_gs_vsync_off),
                stringResource(R.string.retro_gs_vsync_on),
                stringResource(R.string.retro_gs_vsync_relaxed),
            ),
            vsync.indexOf(prefs.getInt("wn.switch.vsync", 1)).coerceAtLeast(0),
        ) { putInt("wn.switch.vsync", vsync[it]) }
    }
}

// "Performance" tab: audio output level.
@Composable
internal fun RetroCitronPerformanceSection() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE) }
    var version by remember { mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); version++ }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_audio))
        val vols = listOf(0, 25, 50, 75, 100)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_audio_volume),
            vols.map { "$it%" },
            vols.indexOf(prefs.getInt("wn.switch.audio", 100)).coerceAtLeast(0),
        ) { putInt("wn.switch.audio", vols[it]) }
    }
}

// Legacy combined view (video + audio), kept for the shared dialog's switch branches.
@Composable
internal fun RetroCitronSettingsSection() {
    RetroCitronGraphicsSection()
    RetroCitronPerformanceSection()
}
