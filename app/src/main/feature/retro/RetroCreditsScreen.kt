package com.winlator.cmod.feature.retro

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.runtime.system.GPUInformation
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val CreditsBg = Color(0xFF101018)
private val CreditsText = Color(0xFFF0F4FF)
private val CreditsSub = Color(0xFF93A6BC)

private const val TAG = "RetroCreditsScreen"

@Composable
fun RetroCreditsScreen(bridge: SettingsNavBridge? = null) {
    val context = LocalContext.current
    val contentNav = rememberSettingsContentNav(bridge)

    // 系统信息含 /proc/cpuinfo 读取与 Vulkan native 调用，只构建一次避免重组时重复执行
    val systemInfo = remember(context) { buildSystemInfo(context) }

    // 进入界面时打印一次到 logcat（LaunchedEffect 保证只执行一次）
    LaunchedEffect(Unit) {
        Log.d(TAG, "System info:\n$systemInfo")
    }

    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ),
            )
        }
    }

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(CreditsBg)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.system_info),
                color = CreditsSub,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                systemInfo,
                color = CreditsSub,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun getGpuInfo(context: Context): String {
    val gpuInfo = StringBuilder()
    try {
        // Get GPU model via Vulkan API
        val gpuModel = GPUInformation.getRenderer(null, context)
        if (gpuModel != null && !gpuModel.isEmpty()) {
            gpuInfo.append(context.getString(R.string.system_info_gpu_model)).append("：").append(gpuModel).append(System.lineSeparator())
        }

        // Get Vulkan version via Vulkan API
        val apiVersionStr = GPUInformation.getSystemApiVersion()
        if (apiVersionStr != null && apiVersionStr.isNotEmpty()) {
            // Native side may return either a packed int ("1157627904") or an
            // already formatted string ("1.3.231"); handle both.
            val versionStr =
                apiVersionStr.toIntOrNull()?.let { version ->
                    String.format(
                        Locale.ENGLISH,
                        "%d.%d.%d",
                        vkVersionMajor(version),
                        vkVersionMinor(version),
                        vkVersionPatch(version),
                    )
                } ?: apiVersionStr
            gpuInfo.append(context.getString(R.string.system_info_vulkan_version)).append("：").append(versionStr).append(System.lineSeparator())
        }

        // Get Vulkan driver version via Vulkan API
        val driverVersion = GPUInformation.getSystemDriverVersion()
        if (driverVersion != null && !driverVersion.isEmpty()) {
            gpuInfo.append(context.getString(R.string.system_info_vulkan_driver_version)).append("：").append(driverVersion).append(System.lineSeparator())
        }
    } catch (e: Exception) {
        gpuInfo.append("Unable to get GPU info: ").append(e.message)
    }
    return gpuInfo.toString()
}

private fun vkVersionMajor(version: Int): Int = version shr 22

private fun vkVersionMinor(version: Int): Int = (version shr 12) and 0x3FF

private fun vkVersionPatch(version: Int): Int = version and 0xFFF

private fun getCpuInfoFromProc(context: Context): String {
    val cpuInfo = StringBuilder()
    val variant = StringBuilder()
    var processorCount = 0
    var cpuFeatures: String? = null

    try {
        File("/proc/cpuinfo").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("processor") -> processorCount++

                    line.startsWith("CPU variant") -> {
                        val parts = line.split(":")
                        if (parts.size > 1) {
                            variant.append("CPU").append(processorCount - 1)
                                .append("：").append(parts[1].trim())
                                .append(System.lineSeparator())
                        }
                    }

                    (line.startsWith("Features") || line.startsWith("flags")) && cpuFeatures == null -> {
                        val parts = line.split(":")
                        if (parts.size > 1) {
                            cpuFeatures = parts[1].trim()
                        }
                    }
                }
            }
        }

        cpuInfo.append(context.getString(R.string.system_info_cpu_cores)).append("：").append(processorCount).append(System.lineSeparator())
        cpuInfo.append(variant)
        // 局部变量承接，避免 var 被 lambda 捕获后 smart cast 失效
        val features = cpuFeatures
        if (features != null) {
            cpuInfo.append(context.getString(R.string.system_info_cpu_features)).append("：").append(features)
        }
    } catch (e: IOException) {
        cpuInfo.append("Unable to read /proc/cpuinfo: ").append(e.message)
    }
    return cpuInfo.toString()
}

/** 汇总设备 / CPU / GPU / 内存信息，供系统信息界面展示。 */
private fun buildSystemInfo(context: Context): String = buildString {
    val sep = System.lineSeparator()
    val str: (Int) -> String = { resId -> context.getString(resId) }

    // === 通用信息 ===
    append("=== ${str(R.string.system_info_general_information)} ===").append(sep)
    append("${str(R.string.system_info_device_manufacturer)}：${Build.MANUFACTURER}").append(sep)
    append("${str(R.string.system_info_device_model)}：${Build.MODEL}").append(sep)
    append("${str(R.string.system_info_device_name)}：${Build.DEVICE}").append(sep)
    append("${str(R.string.system_info_product)}：${Build.PRODUCT}").append(sep)
    append("${str(R.string.system_info_hardware)}：${Build.HARDWARE}").append(sep)
    // joinToString 直接以 ", " 连接，避免 Arrays.toString 产生的方括号包裹
    append("${str(R.string.system_info_supported_abis)}：${Build.SUPPORTED_ABIS.joinToString(", ")}").append(sep)
    append("${str(R.string.system_info_android_version)}：${Build.VERSION.RELEASE} API（${Build.VERSION.SDK_INT}）").append(sep)
    append("${str(R.string.system_info_android_security_patch)}：${Build.VERSION.SECURITY_PATCH}").append(sep)
    append("${str(R.string.system_info_build_id)}：${Build.ID}").append(sep).append(sep)

    // === CPU 信息 ===
    append("=== ${str(R.string.system_info_cpu_info)} ===").append(sep)
    // SOC_MODEL 为 API 31+（Android 12）新增，旧系统下为空字符串
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.orEmpty().isNotBlank()) {
        // system_info_soc 资源值自带冒号（"SOC:"），故以空格连接
        append("${str(R.string.system_info_soc)} ${Build.SOC_MODEL}").append(sep)
    }
    append(getCpuInfoFromProc(context)).append(sep).append(sep)

    // === GPU 信息 ===
    append("=== ${str(R.string.system_info_gpu_information)} ===").append(sep)
    append(getGpuInfo(context)).append(sep)

    // === 内存信息 ===
    append("=== ${str(R.string.system_info_memory_info)} ===").append(sep)
    // getSystemService 可能返回 null，用 as? 安全转换并判空；totalMem 为 0 时显示 "?"
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val totalRamMb = activityManager?.let { am ->
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        memInfo.totalMem.takeIf { it > 0 }?.div(1024 * 1024)
    }
    append("${str(R.string.system_info_total_memory)}： ${totalRamMb ?: "?"} MB")
}
