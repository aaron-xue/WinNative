package com.winlator.cmod.feature.settings.support

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav

private val HelpBg = Color(0xFF101018)
private val HelpText = Color(0xFFF0F4FF)
private val HelpSub = Color(0xFF93A6BC)
private val HelpCard = Color(0xFF181822)
private val HelpAccent = Color(0xFF4FC3F7)
private val HelpChipBg = Color(0xFF1E2A3A)
private val HelpOptionBg = Color(0xFF14141F)

private data class EnvVarOption(
    val value: String,
    @StringRes val descRes: Int,
)

private enum class EnvVarType(val label: String) {
    CHECKBOX("Checkbox"),
    SELECT("Select"),
    SELECT_CUSTOM("Select"),
    SELECT_MULTIPLE("Multi-Select"),
    TEXT("Text"),
    NUMBER("Number"),
    DECIMAL("Decimal"),
}

private data class EnvVarInfo(
    val name: String,
    val type: EnvVarType,
    @StringRes val descriptionRes: Int,
    val default: String = "",
    val options: List<EnvVarOption> = emptyList(),
)

private data class EnvVarCategory(
    @StringRes val titleRes: Int,
    val envVars: List<EnvVarInfo>,
)

private val envVarCategories = listOf(
    EnvVarCategory(
        titleRes = R.string.help_env_cat_graphics,
        envVars = listOf(
            EnvVarInfo(
                name = "ZINK_DESCRIPTORS",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_zink_descriptors,
                default = "auto",
                options = listOf(
                    EnvVarOption("auto", R.string.help_opt_zink_auto),
                    EnvVarOption("lazy", R.string.help_opt_zink_lazy),
                    EnvVarOption("cached", R.string.help_opt_zink_cached),
                    EnvVarOption("notemplates", R.string.help_opt_zink_notemplates),
                ),
            ),
            EnvVarInfo(
                name = "ZINK_DEBUG",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_zink_debug,
                options = listOf(
                    EnvVarOption("nir", R.string.help_opt_zink_debug_nir),
                    EnvVarOption("spirv", R.string.help_opt_zink_debug_spirv),
                    EnvVarOption("tgsi", R.string.help_opt_zink_debug_tgsi),
                    EnvVarOption("validation", R.string.help_opt_zink_debug_validation),
                    EnvVarOption("sync", R.string.help_opt_zink_debug_sync),
                    EnvVarOption("compact", R.string.help_opt_zink_debug_compact),
                    EnvVarOption("noreorder", R.string.help_opt_zink_debug_noreorder),
                ),
            ),
            EnvVarInfo(
                name = "MESA_SHADER_CACHE_DISABLE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_mesa_shader_cache_disable,
                default = "false",
                options = listOf(
                    EnvVarOption("false", R.string.help_opt_mesa_cache_true),
                    EnvVarOption("true", R.string.help_opt_mesa_cache_false),
                ),
            ),
            EnvVarInfo(
                name = "mesa_glthread",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_mesa_glthread,
                default = "false",
                options = listOf(
                    EnvVarOption("false", R.string.help_opt_disable),
                    EnvVarOption("true", R.string.help_opt_enable),
                ),
            ),
            EnvVarInfo(
                name = "MESA_EXTENSION_MAX_YEAR",
                type = EnvVarType.TEXT,
                descriptionRes = R.string.help_env_mesa_extension_max_year,
            ),
            EnvVarInfo(
                name = "MESA_GL_VERSION_OVERRIDE",
                type = EnvVarType.TEXT,
                descriptionRes = R.string.help_env_mesa_gl_version_override,
            ),
            EnvVarInfo(
                name = "GALLIUM_HUD",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_gallium_hud,
                options = listOf(
                    EnvVarOption("simple", R.string.help_opt_gallium_hud_simple),
                    EnvVarOption("fps", R.string.help_opt_gallium_hud_fps),
                    EnvVarOption("frametime", R.string.help_opt_gallium_hud_frametime),
                ),
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_cat_vulkan,
        envVars = listOf(
            EnvVarInfo(
                name = "DXVK_HUD",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_dxvk_hud,
                options = listOf(
                    EnvVarOption("scale=0.5", R.string.help_opt_dxvk_hud_scale50),
                    EnvVarOption("scale=0.7", R.string.help_opt_dxvk_hud_scale70),
                    EnvVarOption("opacity=0.5", R.string.help_opt_dxvk_hud_opacity50),
                    EnvVarOption("opacity=0.7", R.string.help_opt_dxvk_hud_opacity70),
                    EnvVarOption("devinfo", R.string.help_opt_dxvk_hud_devinfo),
                    EnvVarOption("fps", R.string.help_opt_dxvk_hud_fps),
                    EnvVarOption("frametimes", R.string.help_opt_dxvk_hud_frametimes),
                    EnvVarOption("submissions", R.string.help_opt_dxvk_hud_submissions),
                    EnvVarOption("drawcalls", R.string.help_opt_dxvk_hud_drawcalls),
                    EnvVarOption("pipelines", R.string.help_opt_dxvk_hud_pipelines),
                    EnvVarOption("descriptors", R.string.help_opt_dxvk_hud_descriptors),
                    EnvVarOption("memory", R.string.help_opt_dxvk_hud_memory),
                    EnvVarOption("gpuload", R.string.help_opt_dxvk_hud_gpuload),
                    EnvVarOption("version", R.string.help_opt_dxvk_hud_version),
                    EnvVarOption("api", R.string.help_opt_dxvk_hud_api),
                    EnvVarOption("cs", R.string.help_opt_dxvk_hud_cs),
                    EnvVarOption("compiler", R.string.help_opt_dxvk_hud_compiler),
                    EnvVarOption("samplers", R.string.help_opt_dxvk_hud_samplers),
                ),
            ),
            EnvVarInfo(
                name = "DXVK_DISABLE_TIMELINE_SEMAPHORES",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_dxvk_disable_timeline_semaphores,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_dxvk_timeline_enable),
                    EnvVarOption("1", R.string.help_opt_dxvk_timeline_disable),
                ),
            ),
            EnvVarInfo(
                name = "VKD3D_SHADER_MODEL",
                type = EnvVarType.SELECT_CUSTOM,
                descriptionRes = R.string.help_env_vkd3d_shader_model,
                options = listOf(
                    EnvVarOption("6_9", R.string.help_opt_vkd3d_sm_6_9),
                    EnvVarOption("6_6", R.string.help_opt_vkd3d_sm_6_6),
                    EnvVarOption("6_0", R.string.help_opt_vkd3d_sm_6_0),
                    EnvVarOption("5_0", R.string.help_opt_vkd3d_sm_5_0),
                ),
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_cat_sync,
        envVars = listOf(
            EnvVarInfo(
                name = "WINEESYNC",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wineesync,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_disable),
                    EnvVarOption("1", R.string.help_opt_enable),
                ),
            ),
            EnvVarInfo(
                name = "WINENTSYNC",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_winentsync,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_disable),
                    EnvVarOption("1", R.string.help_opt_enable),
                ),
            ),
            EnvVarInfo(
                name = "WINE_FAST_YIELD",
                type = EnvVarType.TEXT,
                descriptionRes = R.string.help_env_wine_fast_yield,
                default = "1",
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_cat_adreno,
        envVars = listOf(
            EnvVarInfo(
                name = "TU_DEBUG",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_tu_debug,
                options = listOf(
                    EnvVarOption("forcecb", R.string.help_opt_tu_debug_forcecb),
                    EnvVarOption("nocb", R.string.help_opt_tu_debug_nocb),
                    EnvVarOption("startup", R.string.help_opt_tu_debug_startup),
                    EnvVarOption("deck_emu", R.string.help_opt_tu_debug_deck_emu),
                    EnvVarOption("nir", R.string.help_opt_tu_debug_nir),
                    EnvVarOption("nobin", R.string.help_opt_tu_debug_nobin),
                    EnvVarOption("sysmem", R.string.help_opt_tu_debug_sysmem),
                    EnvVarOption("gmem", R.string.help_opt_tu_debug_gmem),
                    EnvVarOption("forcebin", R.string.help_opt_tu_debug_forcebin),
                    EnvVarOption("layout", R.string.help_opt_tu_debug_layout),
                    EnvVarOption("noubwc", R.string.help_opt_tu_debug_noubwc),
                    EnvVarOption("nomultipos", R.string.help_opt_tu_debug_nomultipos),
                    EnvVarOption("nolrz", R.string.help_opt_tu_debug_nolrz),
                    EnvVarOption("nolrzfc", R.string.help_opt_tu_debug_nolrzfc),
                    EnvVarOption("perf", R.string.help_opt_tu_debug_perf),
                    EnvVarOption("perfc", R.string.help_opt_tu_debug_perfc),
                    EnvVarOption("flushall", R.string.help_opt_tu_debug_flushall),
                    EnvVarOption("syncdraw", R.string.help_opt_tu_debug_syncdraw),
                    EnvVarOption("push_consts_per_stage", R.string.help_opt_tu_debug_push_consts),
                    EnvVarOption("rast_order", R.string.help_opt_tu_debug_rast_order),
                    EnvVarOption("unaligned_store", R.string.help_opt_tu_debug_unaligned),
                    EnvVarOption("log_skip_gmem_ops", R.string.help_opt_tu_debug_log_skip_gmem),
                    EnvVarOption("dynamic", R.string.help_opt_tu_debug_dynamic),
                    EnvVarOption("bos", R.string.help_opt_tu_debug_bos),
                    EnvVarOption("3d_load", R.string.help_opt_tu_debug_3d_load),
                    EnvVarOption("fdm", R.string.help_opt_tu_debug_fdm),
                    EnvVarOption("noconform", R.string.help_opt_tu_debug_noconform),
                    EnvVarOption("rd", R.string.help_opt_tu_debug_rd),
                ),
            ),
            EnvVarInfo(
                name = "FD_DEV_FEATURES",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_fd_dev_features,
                options = listOf(
                    EnvVarOption("enable_tp_ubwc_flag_hint=1", R.string.help_opt_fd_features_ubwc),
                    EnvVarOption("storage_8bit=1", R.string.help_opt_fd_features_8bit),
                ),
            ),
            EnvVarInfo(
                name = "IR3_SHADER_DEBUG",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_ir3_shader_debug,
                options = listOf(
                    EnvVarOption("nouboopt", R.string.help_opt_ir3_nouboopt),
                    EnvVarOption("nopreamble", R.string.help_opt_ir3_nopreamble),
                    EnvVarOption("noearlypreamble", R.string.help_opt_ir3_noearlypreamble),
                ),
            ),
            EnvVarInfo(
                name = "WRAPPER_MAX_IMAGE_COUNT",
                type = EnvVarType.TEXT,
                descriptionRes = R.string.help_env_wrapper_max_image_count,
            ),
            EnvVarInfo(
                name = "WRAPPER_DMAHEAP_CACHED",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wrapper_dmaheap_cached,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_disable),
                    EnvVarOption("1", R.string.help_opt_enable),
                ),
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_cat_audio,
        envVars = listOf(
            EnvVarInfo(
                name = "PULSE_LATENCY_MSEC",
                type = EnvVarType.NUMBER,
                descriptionRes = R.string.help_env_pulse_latency_msec,
            ),
            EnvVarInfo(
                name = "ALSA_LATENCY_MS",
                type = EnvVarType.NUMBER,
                descriptionRes = R.string.help_env_alsa_latency_ms,
            ),
            EnvVarInfo(
                name = "ALSA_VOLUME",
                type = EnvVarType.DECIMAL,
                descriptionRes = R.string.help_env_alsa_volume,
            ),
            EnvVarInfo(
                name = "ALSA_BASS_BOOST",
                type = EnvVarType.DECIMAL,
                descriptionRes = R.string.help_env_alsa_bass_boost,
            ),
            EnvVarInfo(
                name = "ALSA_PERFORMANCE_MODE",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_alsa_performance_mode,
                options = listOf(
                    EnvVarOption("low_latency", R.string.help_opt_alsa_low_latency),
                    EnvVarOption("none", R.string.help_opt_alsa_none),
                    EnvVarOption("power_saving", R.string.help_opt_alsa_power_saving),
                ),
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_cat_compat,
        envVars = listOf(
            EnvVarInfo(
                name = "WINE_DESKTOP_CAPTURE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_desktop_capture,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_disable),
                    EnvVarOption("1", R.string.help_opt_enable),
                ),
            ),
            EnvVarInfo(
                name = "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_do_not_create_dxgi_device_manager,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_allow_create),
                    EnvVarOption("1", R.string.help_opt_prohibit_create),
                ),
            ),
            EnvVarInfo(
                name = "WINE_NEW_MEDIASOURCE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_new_mediasource,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_disable),
                    EnvVarOption("1", R.string.help_opt_enable),
                ),
            ),
            EnvVarInfo(
                name = "WINE_LARGE_ADDRESS_AWARE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_large_address_aware,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_disable),
                    EnvVarOption("1", R.string.help_opt_enable),
                ),
            ),
            EnvVarInfo(
                name = "WINEDLLOVERRIDES",
                type = EnvVarType.TEXT,
                descriptionRes = R.string.help_env_winedlloverrides,
            ),
        ),
    ),
)

private val box64OnOffOptions = listOf(
    EnvVarOption("1", R.string.help_opt_enable),
    EnvVarOption("0", R.string.help_opt_disable),
)

private val box64EnvVarCategories = listOf(
    EnvVarCategory(
        titleRes = R.string.help_env_box64_cat_dynarec,
        envVars = listOf(
            EnvVarInfo(
                name = "BOX64_DYNAREC",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_SAFEFLAGS",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_safeflags,
                default = "1",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_safeflags_2),
                    EnvVarOption("1", R.string.help_opt_box64_safeflags_1),
                    EnvVarOption("0", R.string.help_opt_box64_safeflags_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_FASTNAN",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec_fastnan,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_FASTROUND",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_fastround,
                default = "1",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_fastround_2),
                    EnvVarOption("1", R.string.help_opt_box64_fastround_1),
                    EnvVarOption("0", R.string.help_opt_box64_fastround_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_X87DOUBLE",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_x87double,
                default = "0",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_x87double_2),
                    EnvVarOption("1", R.string.help_opt_box64_x87double_1),
                    EnvVarOption("0", R.string.help_opt_box64_x87double_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_BIGBLOCK",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_bigblock,
                default = "2",
                options = listOf(
                    EnvVarOption("3", R.string.help_opt_box64_bigblock_3),
                    EnvVarOption("2", R.string.help_opt_box64_bigblock_2),
                    EnvVarOption("1", R.string.help_opt_box64_bigblock_1),
                    EnvVarOption("0", R.string.help_opt_box64_bigblock_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_STRONGMEM",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_strongmem,
                default = "0",
                options = listOf(
                    EnvVarOption("4", R.string.help_opt_box64_strongmem_4),
                    EnvVarOption("3", R.string.help_opt_box64_strongmem_3),
                    EnvVarOption("2", R.string.help_opt_box64_strongmem_2),
                    EnvVarOption("1", R.string.help_opt_box64_strongmem_1),
                    EnvVarOption("0", R.string.help_opt_box64_strongmem_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_FORWARD",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_forward,
                default = "128",
                options = listOf(
                    EnvVarOption("1024", R.string.help_opt_box64_forward_1024),
                    EnvVarOption("512", R.string.help_opt_box64_forward_512),
                    EnvVarOption("256", R.string.help_opt_box64_forward_256),
                    EnvVarOption("128", R.string.help_opt_box64_forward_128),
                    EnvVarOption("0", R.string.help_opt_box64_forward_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_CALLRET",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_callret,
                default = "0",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_callret_2),
                    EnvVarOption("1", R.string.help_opt_box64_callret_1),
                    EnvVarOption("0", R.string.help_opt_box64_callret_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_SEP",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_sep,
                default = "1",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_sep_2),
                    EnvVarOption("1", R.string.help_opt_box64_sep_1),
                    EnvVarOption("0", R.string.help_opt_box64_sep_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_WAIT",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec_wait,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_WEAKBARRIER",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_weakbarrier,
                default = "1",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_weakbarrier_2),
                    EnvVarOption("1", R.string.help_opt_box64_weakbarrier_1),
                    EnvVarOption("0", R.string.help_opt_box64_weakbarrier_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_ALIGNED_ATOMICS",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec_aligned_atomics,
                default = "0",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_DF",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec_df,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_DIRTY",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_dirty,
                default = "0",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_dirty_2),
                    EnvVarOption("1", R.string.help_opt_box64_dirty_1),
                    EnvVarOption("0", R.string.help_opt_box64_dirty_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_NATIVEFLAGS",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec_nativeflags,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_PAUSE",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_pause,
                default = "0",
                options = listOf(
                    EnvVarOption("3", R.string.help_opt_box64_pause_3),
                    EnvVarOption("2", R.string.help_opt_box64_pause_2),
                    EnvVarOption("1", R.string.help_opt_box64_pause_1),
                    EnvVarOption("0", R.string.help_opt_box64_pause_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_NOARCH",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynarec_noarch,
                default = "0",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_noarch_2),
                    EnvVarOption("1", R.string.help_opt_box64_noarch_1),
                    EnvVarOption("0", R.string.help_opt_box64_noarch_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_DYNAREC_VOLATILE_METADATA",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_dynarec_volatile_metadata,
                default = "1",
                options = box64OnOffOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_box64_cat_cache,
        envVars = listOf(
            EnvVarInfo(
                name = "BOX64_DYNACACHE",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_dynacache,
                default = "0",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_dynacache_2),
                    EnvVarOption("1", R.string.help_opt_box64_dynacache_1),
                    EnvVarOption("0", R.string.help_opt_box64_dynacache_0),
                ),
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_box64_cat_isa,
        envVars = listOf(
            EnvVarInfo(
                name = "BOX64_AVX",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_avx,
                default = "0",
                options = listOf(
                    EnvVarOption("2", R.string.help_opt_box64_avx_2),
                    EnvVarOption("1", R.string.help_opt_box64_avx_1),
                    EnvVarOption("0", R.string.help_opt_box64_avx_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_AES",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_aes,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_PCLMULQDQ",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_pclmulqdq,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_SHAEXT",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_shaext,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_SSE42",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_sse42,
                default = "1",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_SSE_FLUSHTO0",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_sse_flushto0,
                default = "0",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_X87_NO80BITS",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_x87_no80bits,
                default = "0",
                options = box64OnOffOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_box64_cat_cpu,
        envVars = listOf(
            EnvVarInfo(
                name = "BOX64_CPUTYPE",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_cputype,
                default = "0",
                options = listOf(
                    EnvVarOption("1", R.string.help_opt_box64_cputype_1),
                    EnvVarOption("0", R.string.help_opt_box64_cputype_0),
                ),
            ),
            EnvVarInfo(
                name = "BOX64_MAXCPU",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_box64_maxcpu,
                default = "0",
                options = listOf(
                    EnvVarOption("0", R.string.help_opt_box64_maxcpu_0),
                    EnvVarOption("4", R.string.help_opt_box64_maxcpu_4),
                    EnvVarOption("8", R.string.help_opt_box64_maxcpu_8),
                    EnvVarOption("16", R.string.help_opt_box64_maxcpu_16),
                    EnvVarOption("32", R.string.help_opt_box64_maxcpu_32),
                    EnvVarOption("64", R.string.help_opt_box64_maxcpu_64),
                ),
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_box64_cat_engine,
        envVars = listOf(
            EnvVarInfo(
                name = "BOX64_UNITYPLAYER",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_unityplayer,
                default = "0",
                options = box64OnOffOptions,
            ),
            EnvVarInfo(
                name = "BOX64_UNITY",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_unity,
                default = "0",
                options = box64OnOffOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_box64_cat_mem,
        envVars = listOf(
            EnvVarInfo(
                name = "BOX64_MMAP32",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_box64_mmap32,
                default = "1",
                options = box64OnOffOptions,
            ),
        ),
    ),
)

private val fexcoreOnOffOptions = listOf(
    EnvVarOption("1", R.string.help_opt_enable),
    EnvVarOption("0", R.string.help_opt_disable),
)

private val fexcoreHostFeaturesOptions = listOf(
    EnvVarOption("off", R.string.help_opt_fexcore_hostfeat_off),
    EnvVarOption("enablesve", R.string.help_opt_fexcore_hostfeat_enablesve),
    EnvVarOption("disablesve", R.string.help_opt_fexcore_hostfeat_disablesve),
    EnvVarOption("enableavx", R.string.help_opt_fexcore_hostfeat_enableavx),
    EnvVarOption("disableavx", R.string.help_opt_fexcore_hostfeat_disableavx),
    EnvVarOption("enableafp", R.string.help_opt_fexcore_hostfeat_enableafp),
    EnvVarOption("disableafp", R.string.help_opt_fexcore_hostfeat_disableafp),
    EnvVarOption("enablelrcpc", R.string.help_opt_fexcore_hostfeat_enablelrcpc),
    EnvVarOption("disablelrcpc", R.string.help_opt_fexcore_hostfeat_disablelrcpc),
    EnvVarOption("enablelrcpc2", R.string.help_opt_fexcore_hostfeat_enablelrcpc2),
    EnvVarOption("disablelrcpc2", R.string.help_opt_fexcore_hostfeat_disablelrcpc2),
    EnvVarOption("enablecssc", R.string.help_opt_fexcore_hostfeat_enablecssc),
    EnvVarOption("disablecssc", R.string.help_opt_fexcore_hostfeat_disablecssc),
    EnvVarOption("enablepmull128", R.string.help_opt_fexcore_hostfeat_enablepmull128),
    EnvVarOption("disablepmull128", R.string.help_opt_fexcore_hostfeat_disablepmull128),
    EnvVarOption("enablerng", R.string.help_opt_fexcore_hostfeat_enablerng),
    EnvVarOption("disablerng", R.string.help_opt_fexcore_hostfeat_disablerng),
    EnvVarOption("enableclzero", R.string.help_opt_fexcore_hostfeat_enableclzero),
    EnvVarOption("disableclzero", R.string.help_opt_fexcore_hostfeat_disableclzero),
    EnvVarOption("enableatomics", R.string.help_opt_fexcore_hostfeat_enableatomics),
    EnvVarOption("disableatomics", R.string.help_opt_fexcore_hostfeat_disableatomics),
    EnvVarOption("enablefcma", R.string.help_opt_fexcore_hostfeat_enablefcma),
    EnvVarOption("disablefcma", R.string.help_opt_fexcore_hostfeat_disablefcma),
    EnvVarOption("enableflagm", R.string.help_opt_fexcore_hostfeat_enableflagm),
    EnvVarOption("disableflagm", R.string.help_opt_fexcore_hostfeat_disableflagm),
    EnvVarOption("enableflagm2", R.string.help_opt_fexcore_hostfeat_enableflagm2),
    EnvVarOption("disableflagm2", R.string.help_opt_fexcore_hostfeat_disableflagm2),
    EnvVarOption("enablefrintts", R.string.help_opt_fexcore_hostfeat_enablefrintts),
    EnvVarOption("disablefrintts", R.string.help_opt_fexcore_hostfeat_disablefrintts),
    EnvVarOption("enablecrypto", R.string.help_opt_fexcore_hostfeat_enablecrypto),
    EnvVarOption("disablecrypto", R.string.help_opt_fexcore_hostfeat_disablecrypto),
    EnvVarOption("enablerpres", R.string.help_opt_fexcore_hostfeat_enablerpres),
    EnvVarOption("disablerpres", R.string.help_opt_fexcore_hostfeat_disablerpres),
    EnvVarOption("enablesvebitperm", R.string.help_opt_fexcore_hostfeat_enablesvebitperm),
    EnvVarOption("disablesvebitperm", R.string.help_opt_fexcore_hostfeat_disablesvebitperm),
    EnvVarOption("enablepreserveallabi", R.string.help_opt_fexcore_hostfeat_enablepreserveallabi),
    EnvVarOption("disablepreserveallabi", R.string.help_opt_fexcore_hostfeat_disablepreserveallabi),
    EnvVarOption("enablewfxt", R.string.help_opt_fexcore_hostfeat_enablewfxt),
    EnvVarOption("disablewfxt", R.string.help_opt_fexcore_hostfeat_disablewfxt),
    EnvVarOption("enable3dnow", R.string.help_opt_fexcore_hostfeat_enable3dnow),
    EnvVarOption("disable3dnow", R.string.help_opt_fexcore_hostfeat_disable3dnow),
    EnvVarOption("enablesse4a", R.string.help_opt_fexcore_hostfeat_enablesse4a),
    EnvVarOption("disablesse4a", R.string.help_opt_fexcore_hostfeat_disablesse4a),
    EnvVarOption("enablemops", R.string.help_opt_fexcore_hostfeat_enablemops),
    EnvVarOption("disablemops", R.string.help_opt_fexcore_hostfeat_disablemops),
)

private val fexcoreSmcChecksOptions = listOf(
    EnvVarOption("none", R.string.help_opt_fexcore_smc_none),
    EnvVarOption("mtrack", R.string.help_opt_fexcore_smc_mtrack),
    EnvVarOption("full", R.string.help_opt_fexcore_smc_full),
)

private val fexcoreEnvVarCategories = listOf(
    EnvVarCategory(
        titleRes = R.string.help_env_fexcore_cat_tso,
        envVars = listOf(
            EnvVarInfo(
                name = "FEX_TSOENABLED",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_tsoenabled,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_VECTORTSOENABLED",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_vectortsoenabled,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_HALFBARRIERTSOENABLED",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_halfbarriertsoenabled,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_MEMCPYSETTSOENABLED",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_memcpysetsoenabled,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_STRICTINPROCESSSPLITLOCKS",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_strictinprocesssplitlocks,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_fexcore_cat_cpu,
        envVars = listOf(
            EnvVarInfo(
                name = "FEX_KERNELUNALIGNEDATOMICBACKPATCHING",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_kernelunalignedatomicbackpatching,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_X87REDUCEDPRECISION",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_x87reducedprecision,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_MULTIBLOCK",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_multiblock,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_MAXINST",
                type = EnvVarType.NUMBER,
                descriptionRes = R.string.help_env_fexcore_maxinst,
                default = "5000",
            ),
            EnvVarInfo(
                name = "FEX_HOSTFEATURES",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_fexcore_hostfeatures,
                default = "off",
                options = fexcoreHostFeaturesOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_fexcore_cat_compat,
        envVars = listOf(
            EnvVarInfo(
                name = "FEX_SMALLTSCSCALE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_smalltscscale,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_HIDEHYBRID",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_hidehybrid,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_SMCCHECKS",
                type = EnvVarType.SELECT,
                descriptionRes = R.string.help_env_fexcore_smcchecks,
                default = "mtrack",
                options = fexcoreSmcChecksOptions,
            ),
            EnvVarInfo(
                name = "FEX_MONOHACKS",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_monohacks,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_HIDEHYPERVISORBIT",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_hidehypervisorbit,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_fexcore_cat_cachemeta,
        envVars = listOf(
            EnvVarInfo(
                name = "FEX_VOLATILEMETADATA",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_volatilemetadata,
                default = "1",
                options = fexcoreOnOffOptions,
            ),
        ),
    ),
    EnvVarCategory(
        titleRes = R.string.help_env_fexcore_cat_cpucache,
        envVars = listOf(
            EnvVarInfo(
                name = "FEX_DISABLEL2CACHE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_disablel2cache,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_DYNAMICL1CACHE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_fexcore_dynamicl1cache,
                default = "0",
                options = fexcoreOnOffOptions,
            ),
            EnvVarInfo(
                name = "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC",
                type = EnvVarType.NUMBER,
                descriptionRes = R.string.help_env_fexcore_dynamicl1cacheincreasecountheuristic,
                default = "250",
            ),
            EnvVarInfo(
                name = "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC",
                type = EnvVarType.NUMBER,
                descriptionRes = R.string.help_env_fexcore_dynamicl1cachedecreasecountheuristic,
                default = "50",
            ),
        ),
    ),
)

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        stringResource(titleRes),
        color = HelpAccent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun TypeChip(type: EnvVarType) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(HelpChipBg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            type.label,
            color = HelpAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EnvVarCard(info: EnvVarInfo) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(HelpCard)
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.name,
                    color = HelpText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(info.descriptionRes),
                    color = HelpSub,
                    fontSize = 12.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            TypeChip(info.type)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = HelpSub,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (info.default.isNotEmpty()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                    ) {
                        Text(
                            "${stringResource(R.string.settings_help_env_vars_default)}: ",
                            color = HelpSub,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            info.default,
                            color = HelpAccent,
                            fontSize = 11.sp,
                        )
                    }
                }

                if (info.options.isNotEmpty()) {
                    Text(
                        "${stringResource(R.string.settings_help_env_vars_options)}:",
                        color = HelpSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    info.options.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(HelpOptionBg)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                option.value,
                                color = HelpAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(140.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(option.descRes),
                                color = HelpSub,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }

        if (!expanded && (info.default.isNotEmpty() || info.options.isNotEmpty())) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
            ) {
                if (info.default.isNotEmpty()) {
                    Text(
                        "${stringResource(R.string.settings_help_env_vars_default)}: ${info.default}",
                        color = HelpSub,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SupportScreen(bridge: SettingsNavBridge? = null) {
    val contentNav = rememberSettingsContentNav(bridge)

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(HelpBg)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = HelpAccent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_help_env_vars_title),
                    color = HelpText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            envVarCategories.forEach { category ->
                SectionHeader(category.titleRes)
                category.envVars.forEach { envVar ->
                    EnvVarCard(envVar)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = HelpAccent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_help_box64_env_vars_title),
                    color = HelpText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            box64EnvVarCategories.forEach { category ->
                SectionHeader(category.titleRes)
                category.envVars.forEach { envVar ->
                    EnvVarCard(envVar)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = HelpAccent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_help_fexcore_env_vars_title),
                    color = HelpText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            fexcoreEnvVarCategories.forEach { category ->
                SectionHeader(category.titleRes)
                category.envVars.forEach { envVar ->
                    EnvVarCard(envVar)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}