package com.winlator.cmod.feature.retro

import android.app.Activity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// Switch (Citron) settings. Persist to the "CITRON" SharedPreferences using the exact
// native config keys from Citron's IntSetting / BooleanSetting enums. The values are
// applied via NativeConfig.setInt() / setBoolean() by CitronHost.applyBootSettings
// before emulation starts.

// Full graphics settings taken from the official Citron emulator app.
// Resolution scale values correspond to the ResolutionSetup enum ordinals:
//   0=0.25X, 1=0.5X, 2=0.75X, 3=1X, 12=1.25X, 4=1.5X, 13=1.75X,
//   5=2X, 6=3X, 7=4X, 8=5X, 9=6X, 10=7X, 11=8X
@Composable
internal fun RetroCitronGraphicsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE) }
    var version by remember { mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); version++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); version++ }
    fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    fun getBool(key: String, def: Boolean) = prefs.getBoolean(key, def)

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_video))

        val accValues = listOf(0, 1, 2, 3)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_gpu_accuracy),
            listOf(
                stringResource(R.string.retro_gs_accuracy_normal),
                stringResource(R.string.retro_gs_accuracy_high),
                stringResource(R.string.retro_gs_accuracy_extreme),
                stringResource(R.string.retro_gs_accuracy_low),
            ),
            accValues.indexOf(getInt("gpu_accuracy", 0)).coerceAtLeast(0),
        ) { putInt("gpu_accuracy", accValues[it]) }

        val resValues = listOf(0, 1, 2, 3, 12, 4, 13, 5, 6, 7, 8, 9, 10, 11)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_resolution_scale),
            resValues.mapIndexed { i, _ ->
                listOf("0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "3x", "4x", "5x", "6x", "7x", "8x")[i]
            },
            resValues.indexOf(getInt("resolution_setup", 3)).coerceAtLeast(0),
        ) { putInt("resolution_setup", resValues[it]) }

        val vsValues = listOf(0, 1, 2, 3)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_vsync),
            listOf(
                stringResource(R.string.retro_gs_vsync_immediate),
                stringResource(R.string.retro_gs_vsync_mailbox),
                stringResource(R.string.retro_gs_vsync_fifo),
                stringResource(R.string.retro_gs_vsync_fifo_relaxed),
            ),
            vsValues.indexOf(getInt("use_vsync", 2)).coerceAtLeast(0),
        ) { putInt("use_vsync", vsValues[it]) }

        val filterValues = (0..10).toList()
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_scaling_filter),
            listOf(
                stringResource(R.string.retro_gs_filter_nearest),
                stringResource(R.string.retro_gs_filter_bilinear),
                stringResource(R.string.retro_gs_filter_bicubic),
                stringResource(R.string.retro_gs_filter_gaussian),
                stringResource(R.string.retro_gs_filter_scale_force),
                stringResource(R.string.retro_gs_filter_scale_fx),
                stringResource(R.string.retro_gs_filter_lanczos),
                stringResource(R.string.retro_gs_filter_fsr),
                stringResource(R.string.retro_gs_filter_fsr2),
                stringResource(R.string.retro_gs_filter_crt_easy),
                stringResource(R.string.retro_gs_filter_crt_royale),
            ),
            filterValues.indexOf(getInt("scaling_filter", 1)).coerceAtLeast(0),
        ) { putInt("scaling_filter", filterValues[it]) }

        val aaValues = listOf(0, 1, 2, 3)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_anti_aliasing),
            listOf(
                stringResource(R.string.retro_gs_aa_none),
                "FXAA",
                "SMAA",
                "TAA",
            ),
            aaValues.indexOf(getInt("anti_aliasing", 0)).coerceAtLeast(0),
        ) { putInt("anti_aliasing", aaValues[it]) }

        val anisoValues = listOf(0, 1, 2, 3, 4, 5)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_anisotropic_filtering),
            listOf(
                stringResource(R.string.retro_gs_auto),
                stringResource(R.string.retro_gs_default),
                "2x",
                "4x",
                "8x",
                "16x",
            ),
            anisoValues.indexOf(getInt("max_anisotropy", 0)).coerceAtLeast(0),
        ) { putInt("max_anisotropy", anisoValues[it]) }

        val fsrValues = (0..100 step 25).toList()
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_fsr_sharpness),
            fsrValues.map { "$it%" },
            fsrValues.indexOf(
                getInt("fsr_sharpening_slider", 25).let { v ->
                    fsrValues.minByOrNull { kotlin.math.abs(it - v) } ?: 25
                }
            ).coerceAtLeast(0),
        ) { putInt("fsr_sharpening_slider", fsrValues[it]) }

        Text(
            text = stringResource(R.string.retro_gs_fsr_sharpness_desc),
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 8.dp),
        )

        val fsr2Values = listOf(0, 1, 2, 3)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_fsr2_quality),
            listOf(
                stringResource(R.string.retro_gs_fsr2_quality_q),
                stringResource(R.string.retro_gs_fsr2_quality_balanced),
                stringResource(R.string.retro_gs_fsr2_quality_perf),
                stringResource(R.string.retro_gs_fsr2_quality_ultra),
            ),
            fsr2Values.indexOf(getInt("fsr2_quality_mode", 1)).coerceAtLeast(0),
        ) { putInt("fsr2_quality_mode", fsr2Values[it]) }

        Text(
            text = stringResource(R.string.retro_gs_fsr2_quality_desc),
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 8.dp),
        )
    }

    Spacer(Modifier.height(8.dp))

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_display))

        val layoutValues = listOf(0, 5, 1, 2, 6, 4, 3)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_screen_layout),
            listOf(
                stringResource(R.string.retro_gs_layout_auto),
                stringResource(R.string.retro_gs_layout_sensor_landscape),
                stringResource(R.string.retro_gs_layout_landscape),
                stringResource(R.string.retro_gs_layout_reverse_landscape),
                stringResource(R.string.retro_gs_layout_sensor_portrait),
                stringResource(R.string.retro_gs_layout_portrait),
                stringResource(R.string.retro_gs_layout_reverse_portrait),
            ),
            layoutValues.indexOf(getInt("screen_layout", 0)).coerceAtLeast(0),
        ) { putInt("screen_layout", layoutValues[it]) }

        val aspectValues = listOf(0, 1, 2, 3, 4, 5)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_aspect_ratio),
            listOf(
                stringResource(R.string.retro_gs_aspect_default),
                "4:3",
                "21:9",
                "16:10",
                "32:9",
                stringResource(R.string.retro_gs_aspect_stretch),
            ),
            aspectValues.indexOf(getInt("aspect_ratio", 0)).coerceAtLeast(0),
        ) { putInt("aspect_ratio", aspectValues[it]) }

        val valignValues = listOf(1, 0, 2)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_vertical_alignment),
            listOf(
                stringResource(R.string.retro_gs_valign_top),
                stringResource(R.string.retro_gs_valign_center),
                stringResource(R.string.retro_gs_valign_bottom),
            ),
            valignValues.indexOf(getInt("vertical_alignment", 0)).coerceAtLeast(0),
        ) { putInt("vertical_alignment", valignValues[it]) }
    }

    Spacer(Modifier.height(8.dp))

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_advanced))

        RetroSettingSwitch(
            stringResource(R.string.retro_gs_disk_shader_cache),
            getBool("use_disk_shader_cache", true),
            subtitle = stringResource(R.string.retro_gs_disk_shader_cache_desc),
        ) { putBool("use_disk_shader_cache", it) }

        RetroSettingSwitch(
            stringResource(R.string.retro_gs_force_max_clock),
            getBool("force_max_clock", false),
            subtitle = stringResource(R.string.retro_gs_force_max_clock_desc),
        ) { putBool("force_max_clock", it) }

        RetroSettingSwitch(
            stringResource(R.string.retro_gs_async_shaders),
            getBool("use_asynchronous_shaders", false),
            subtitle = stringResource(R.string.retro_gs_async_shaders_desc),
        ) { putBool("use_asynchronous_shaders", it) }

        RetroSettingSwitch(
            stringResource(R.string.retro_gs_reactive_flushing),
            getBool("use_reactive_flushing", false),
            subtitle = stringResource(R.string.retro_gs_reactive_flushing_desc),
        ) { putBool("use_reactive_flushing", it) }

        RetroSettingSwitch(
            stringResource(R.string.retro_gs_pip),
            getBool("picture_in_picture", false),
            subtitle = stringResource(R.string.retro_gs_pip_desc),
        ) { putBool("picture_in_picture", it) }
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