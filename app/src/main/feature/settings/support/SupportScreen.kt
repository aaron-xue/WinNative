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
    val desc: String,
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
                    EnvVarOption("auto", "自动选择最佳策略"),
                    EnvVarOption("lazy", "延迟分配描述符"),
                    EnvVarOption("cached", "缓存描述符以减少重复分配"),
                    EnvVarOption("notemplates", "禁用描述符模板"),
                ),
            ),
            EnvVarInfo(
                name = "ZINK_DEBUG",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_zink_debug,
                options = listOf(
                    EnvVarOption("nir", "调试 NIR（中间表示）代码"),
                    EnvVarOption("spirv", "调试 SPIR-V 着色器"),
                    EnvVarOption("tgsi", "调试 TGSI（旧版着色器中间表示）"),
                    EnvVarOption("validation", "启用 Vulkan 验证层"),
                    EnvVarOption("sync", "调试同步问题"),
                    EnvVarOption("compact", "紧凑模式"),
                    EnvVarOption("noreorder", "禁用指令重排序"),
                ),
            ),
            EnvVarInfo(
                name = "MESA_SHADER_CACHE_DISABLE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_mesa_shader_cache_disable,
                default = "false",
                options = listOf(
                    EnvVarOption("false", "启用着色器缓存"),
                    EnvVarOption("true", "禁用着色器缓存"),
                ),
            ),
            EnvVarInfo(
                name = "mesa_glthread",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_mesa_glthread,
                default = "false",
                options = listOf(
                    EnvVarOption("false", "禁用"),
                    EnvVarOption("true", "启用"),
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
                    EnvVarOption("simple", "显示简单的性能信息"),
                    EnvVarOption("fps", "显示帧率 (FPS)"),
                    EnvVarOption("frametime", "显示帧时间"),
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
                    EnvVarOption("scale=0.5", "HUD 缩放 50%"),
                    EnvVarOption("scale=0.7", "HUD 缩放 70%"),
                    EnvVarOption("opacity=0.5", "HUD 透明度 50%"),
                    EnvVarOption("opacity=0.7", "HUD 透明度 70%"),
                    EnvVarOption("devinfo", "显示设备信息（GPU 名称、驱动版本等）"),
                    EnvVarOption("fps", "显示帧率"),
                    EnvVarOption("frametimes", "显示帧时间图"),
                    EnvVarOption("submissions", "显示提交数量"),
                    EnvVarOption("drawcalls", "显示绘制调用次数"),
                    EnvVarOption("pipelines", "显示图形管线统计"),
                    EnvVarOption("descriptors", "显示描述符统计"),
                    EnvVarOption("memory", "显示显存使用情况"),
                    EnvVarOption("gpuload", "显示 GPU 负载"),
                    EnvVarOption("version", "显示 DXVK 版本"),
                    EnvVarOption("api", "显示使用的 API"),
                    EnvVarOption("cs", "显示计算着色器信息"),
                    EnvVarOption("compiler", "显示着色器编译信息"),
                    EnvVarOption("samplers", "显示采样器统计"),
                ),
            ),
            EnvVarInfo(
                name = "DXVK_DISABLE_TIMELINE_SEMAPHORES",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_dxvk_disable_timeline_semaphores,
                default = "0",
                options = listOf(
                    EnvVarOption("0", "启用时间线信号量"),
                    EnvVarOption("1", "禁用时间线信号量"),
                ),
            ),
            EnvVarInfo(
                name = "VKD3D_SHADER_MODEL",
                type = EnvVarType.SELECT_CUSTOM,
                descriptionRes = R.string.help_env_vkd3d_shader_model,
                options = listOf(
                    EnvVarOption("6_9", "Shader Model 6.9（最新）"),
                    EnvVarOption("6_6", "Shader Model 6.6"),
                    EnvVarOption("6_0", "Shader Model 6.0"),
                    EnvVarOption("5_0", "Shader Model 5.0（较老）"),
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
                    EnvVarOption("0", "禁用"),
                    EnvVarOption("1", "启用"),
                ),
            ),
            EnvVarInfo(
                name = "WINENTSYNC",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_winentsync,
                default = "0",
                options = listOf(
                    EnvVarOption("0", "禁用"),
                    EnvVarOption("1", "启用"),
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
                    EnvVarOption("forcecb", "强制使用常量缓冲区"),
                    EnvVarOption("nocb", "禁用常量缓冲区"),
                    EnvVarOption("startup", "显示启动调试信息"),
                    EnvVarOption("deck_emu", "Steam Deck 模拟模式"),
                    EnvVarOption("nir", "调试 NIR 代码"),
                    EnvVarOption("nobin", "禁用二进制着色器"),
                    EnvVarOption("sysmem", "使用系统内存模式"),
                    EnvVarOption("gmem", "使用 GMEM（片上内存）模式"),
                    EnvVarOption("forcebin", "强制分箱（Binning）模式"),
                    EnvVarOption("layout", "显示管线布局信息"),
                    EnvVarOption("noubwc", "禁用 UBWC 压缩"),
                    EnvVarOption("nomultipos", "禁用多位置输出"),
                    EnvVarOption("nolrz", "禁用 LRZ"),
                    EnvVarOption("nolrzfc", "禁用 LRZ 快速清除"),
                    EnvVarOption("perf", "性能调试信息"),
                    EnvVarOption("perfc", "精简性能调试信息"),
                    EnvVarOption("flushall", "强制刷新所有操作"),
                    EnvVarOption("syncdraw", "同步绘制操作"),
                    EnvVarOption("push_consts_per_stage", "每个阶段使用推送常量"),
                    EnvVarOption("rast_order", "强制光栅化顺序"),
                    EnvVarOption("unaligned_store", "允许非对齐存储"),
                    EnvVarOption("log_skip_gmem_ops", "记录跳过的 GMEM 操作"),
                    EnvVarOption("dynamic", "动态模式"),
                    EnvVarOption("bos", "调试缓冲对象"),
                    EnvVarOption("3d_load", "调试 3D 加载"),
                    EnvVarOption("fdm", "调试 FDM"),
                    EnvVarOption("noconform", "禁用一致性检查"),
                    EnvVarOption("rd", "调试渲染目标"),
                ),
            ),
            EnvVarInfo(
                name = "FD_DEV_FEATURES",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_fd_dev_features,
                options = listOf(
                    EnvVarOption("enable_tp_ubwc_flag_hint=1", "启用 UBWC 压缩标志提示，可提升带宽利用率"),
                    EnvVarOption("storage_8bit=1", "启用 8 位存储支持"),
                ),
            ),
            EnvVarInfo(
                name = "IR3_SHADER_DEBUG",
                type = EnvVarType.SELECT_MULTIPLE,
                descriptionRes = R.string.help_env_ir3_shader_debug,
                options = listOf(
                    EnvVarOption("nouboopt", "禁用 UBO（Uniform Buffer Object）优化"),
                    EnvVarOption("nopreamble", "禁用着色器前导码"),
                    EnvVarOption("noearlypreamble", "禁用提前前导码"),
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
                    EnvVarOption("0", "禁用"),
                    EnvVarOption("1", "启用"),
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
                    EnvVarOption("low_latency", "低延迟模式，适合游戏"),
                    EnvVarOption("none", "默认模式"),
                    EnvVarOption("power_saving", "省电模式，降低功耗但可能增加延迟"),
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
                    EnvVarOption("0", "禁用"),
                    EnvVarOption("1", "启用"),
                ),
            ),
            EnvVarInfo(
                name = "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_do_not_create_dxgi_device_manager,
                default = "0",
                options = listOf(
                    EnvVarOption("0", "允许创建"),
                    EnvVarOption("1", "禁止创建"),
                ),
            ),
            EnvVarInfo(
                name = "WINE_NEW_MEDIASOURCE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_new_mediasource,
                default = "0",
                options = listOf(
                    EnvVarOption("0", "禁用"),
                    EnvVarOption("1", "启用"),
                ),
            ),
            EnvVarInfo(
                name = "WINE_LARGE_ADDRESS_AWARE",
                type = EnvVarType.CHECKBOX,
                descriptionRes = R.string.help_env_wine_large_address_aware,
                default = "0",
                options = listOf(
                    EnvVarOption("0", "禁用"),
                    EnvVarOption("1", "启用"),
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
                                option.desc,
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

            Spacer(Modifier.height(24.dp))
        }
    }
}