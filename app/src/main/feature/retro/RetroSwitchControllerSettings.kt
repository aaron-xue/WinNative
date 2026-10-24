package com.winlator.cmod.feature.retro

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.wn.CitronBootstrap
import org.citron.citron_emu.wn.InputDialogFragment
import org.citron.citron_emu.features.input.model.NativeAnalog
import org.citron.citron_emu.features.input.model.NativeButton
import org.citron.citron_emu.utils.InputHandler
import org.citron.citron_emu.utils.NativeConfig
import org.citron.citron_emu.utils.ParamPackage

private const val TAG = "SwitchControllerSettings"

// The on-screen Input Overlay registers a pseudo-device with this all-zero GUID; it
// must not appear as a bindable physical controller in the player dropdowns.
private const val OVERLAY_GUID = "00000000000000000000000000000000"

private fun realDevices(): List<ParamPackage> =
    NativeInput.getInputDevices().map { ParamPackage(it) }
        .filter { it.get("guid", "") != OVERLAY_GUID }

// Color constants (PageBg/PageText/PageSub) are defined in RetroDefaultsScreen.kt as
// internal vals shared across the retro package.
private val CardBg = Color(0xFF1A2030)
private val Accent = Color(0xFF4F8CFF)

// All emulatable Switch buttons, in display order, matching NativeButton.
private val BINDABLE_BUTTONS = listOf(
    NativeButton.A to "A",
    NativeButton.B to "B",
    NativeButton.X to "X",
    NativeButton.Y to "Y",
    NativeButton.LStick to "Left Stick Press",
    NativeButton.RStick to "Right Stick Press",
    NativeButton.L to "L",
    NativeButton.R to "R",
    NativeButton.ZL to "ZL",
    NativeButton.ZR to "ZR",
    NativeButton.Plus to "Plus",
    NativeButton.Minus to "Minus",
    NativeButton.DUp to "D-Pad Up",
    NativeButton.DDown to "D-Pad Down",
    NativeButton.DLeft to "D-Pad Left",
    NativeButton.DRight to "D-Pad Right",
    NativeButton.SLLeft to "SL (Left Joy-Con)",
    NativeButton.SRLeft to "SR (Left Joy-Con)",
    NativeButton.Home to "Home",
    NativeButton.Capture to "Capture",
    NativeButton.SLRight to "SL (Right Joy-Con)",
    NativeButton.SRRight to "SR (Right Joy-Con)",
)

// Each analog stick is bound per-direction (mirrors upstream citron).
private val STICK_DIRECTIONS = listOf("up", "down", "left", "right")

@Composable
fun SwitchControllerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // When non-null, the whole screen swaps to the per-player binding sub-screen.
    var configPlayer by remember { mutableStateOf<Int?>(null) }

    // The citron native input subsystem is normally only loaded in the :switch
    // emulation process. This settings screen runs in the main (UI) process, so we
    // initialize it on demand here. Same bundle/so set, separate process memory.
    LaunchedEffect(Unit) {
        runCatching {
            CitronBootstrap.ensure(context)
            InputHandler.updateControllerData()
            NativeInput.getInputDevices() // smoke test that the input bridge works
            ready = true
        }.onFailure {
            Log.e(TAG, "citron native init failed for controller settings", it)
            errorMsg = "无法初始化 Switch 输入子系统：${it.message}（请确认已安装 Retro-Consoles 资源包）"
        }
    }

    var players by remember(ready) { mutableStateOf(if (ready) buildPlayerList() else emptyList()) }

    Column(modifier = Modifier.fillMaxSize().background(PageBg).padding(16.dp)) {
        BackHandler(enabled = true) {
            if (configPlayer != null) configPlayer = null else onBack()
        }
        when {
            configPlayer != null -> {
                PlayerBindScreen(playerIndex = configPlayer!!, onBack = { configPlayer = null })
            }
            !ready && errorMsg == null -> {
                Text("正在初始化输入子系统…", color = PageSub, fontSize = 14.sp)
            }
            errorMsg != null -> {
                Text(errorMsg ?: "", color = Color(0xFFFF6B6B), fontSize = 14.sp)
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "控制器设置",
                        color = PageText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onBack) { Text("返回", color = PageText) }
                }

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(players.size) { index ->
                        PlayerCard(
                            playerIndex = players[index].index,
                            initialDeviceIndex = players[index].deviceIndex,
                            initiallyConnected = players[index].connected,
                            onConfigure = { configPlayer = players[index].index },
                        )
                    }
                }
            }
        }
    }
}

private data class PlayerEntry(val index: Int, val deviceIndex: Int, val connected: Boolean)

private fun buildPlayerList(): List<PlayerEntry> {
    val devices = realDevices()
    return (0 until 8).map { p ->
        val connected = runCatching { NativeInput.getIsConnected(p) }.getOrDefault(false)
        val guid = runCatching { NativeInput.getButtonParam(p, NativeButton.A) }
            .getOrNull()?.get("guid", "") ?: ""
        val deviceIndex = devices.indexOfFirst { it.get("guid", "") == guid }.let { if (it < 0) 0 else it + 1 }
        PlayerEntry(p, deviceIndex, connected)
    }
}

@Composable
private fun PlayerCard(
    playerIndex: Int,
    initialDeviceIndex: Int,
    initiallyConnected: Boolean,
    onConfigure: () -> Unit,
) {
    var deviceIndex by remember { mutableStateOf(initialDeviceIndex) }
    var connected by remember { mutableStateOf(initiallyConnected) }

    val devices = remember { realDevices() }
    val deviceEntries = listOf("未分配") + devices.map {
        it.get("display", "手柄") + " (" + it.get("guid", "????").take(8) + ")"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(CardBg, RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("玩家 ${playerIndex + 1}", color = PageText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                if (connected) "已连接" else "未连接",
                color = if (connected) Accent else PageSub,
                fontSize = 13.sp,
            )
        }

        Spacer(modifier = Modifier.padding(6.dp))
        RetroSettingDropdown(
            label = "绑定设备",
            entries = deviceEntries,
            selectedIndex = deviceIndex,
            onSelected = { idx ->
                deviceIndex = idx
                val dev = if (idx > 0) devices[idx - 1] else null
                runCatching {
                    if (dev != null) {
                        NativeInput.updateMappingsWithDefault(playerIndex, dev, dev.get("display", "手柄"))
                    }
                    NativeInput.connectControllers(playerIndex, true)
                    connected = true
                    NativeConfig.saveGlobalConfig()
                    NativeInput.reloadInputDevices()
                }.onFailure { Log.e(TAG, "assign device to player $playerIndex failed", it) }
            },
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    connected = !connected
                    runCatching {
                        NativeInput.connectControllers(playerIndex, connected)
                        NativeConfig.saveGlobalConfig()
                        NativeInput.reloadInputDevices()
                    }.onFailure { Log.e(TAG, "connect player $playerIndex failed", it) }
                },
            ) { Text(if (connected) "断开" else "连接", color = PageText) }

            Button(
                modifier = Modifier.weight(1f),
                onClick = onConfigure,
            ) { Text("配置按键") }
        }
    }
}

@Composable
private fun PlayerBindScreen(playerIndex: Int, onBack: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    val activity = LocalContext.current as? FragmentActivity

    Box(modifier = Modifier.fillMaxSize().background(PageBg)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("玩家 ${playerIndex + 1} 按键绑定", color = PageText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onBack) { Text("完成", color = PageText) }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(BINDABLE_BUTTONS.size) { i ->
                    val (button, label) = BINDABLE_BUTTONS[i]
                    val param = remember(refresh, playerIndex) {
                        runCatching { NativeInput.getButtonParam(playerIndex, button) }.getOrNull()
                            ?: ParamPackage()
                    }
                    BindRow(
                        label = label,
                        summary = describeParam(param),
                        onClick = {
                            activity?.let { act ->
                                InputDialogFragment.newInstance(
                                    playerIndex = playerIndex,
                                    button = button,
                                    title = "映射按键 · $label",
                                    onSaved = { refresh++ },
                                ).show(act.supportFragmentManager, "input_map")
                            }
                        },
                    )
                }

                item {
                    RetroGroupTitle("摇杆")
                    STICK_DIRECTIONS.forEach { dir ->
                        val analog = NativeAnalog.LStick
                        val param = remember(refresh, playerIndex) {
                            runCatching { NativeInput.getStickParam(playerIndex, analog) }.getOrNull()
                                ?: ParamPackage()
                        }
                        BindRow(
                            label = "左摇杆 - ${dir.uppercase()}",
                            summary = describeParam(param),
                            onClick = {
                                activity?.let { act ->
                                    InputDialogFragment.newInstance(
                                        playerIndex = playerIndex,
                                        analog = analog,
                                        analogDirection = dir,
                                        title = "映射左摇杆 · ${dir.uppercase()}",
                                        onSaved = { refresh++ },
                                    ).show(act.supportFragmentManager, "input_map")
                                }
                            },
                        )
                    }
                    STICK_DIRECTIONS.forEach { dir ->
                        val analog = NativeAnalog.RStick
                        val param = remember(refresh, playerIndex) {
                            runCatching { NativeInput.getStickParam(playerIndex, analog) }.getOrNull()
                                ?: ParamPackage()
                        }
                        BindRow(
                            label = "右摇杆 - ${dir.uppercase()}",
                            summary = describeParam(param),
                            onClick = {
                                activity?.let { act ->
                                    InputDialogFragment.newInstance(
                                        playerIndex = playerIndex,
                                        analog = analog,
                                        analogDirection = dir,
                                        title = "映射右摇杆 · ${dir.uppercase()}",
                                        onSaved = { refresh++ },
                                    ).show(act.supportFragmentManager, "input_map")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BindRow(label: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(CardBg, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = PageText, fontSize = 15.sp)
            Text(summary, color = PageSub, fontSize = 12.sp)
        }
        Text("点击绑定", color = Accent, fontSize = 13.sp)
    }
}

/**
 * Builds a human-readable summary for a button/stick ParamPackage (no string resources
 * needed). Shows the engine + the bound key/axis, or "未绑定" when empty.
 */
private fun describeParam(param: ParamPackage): String {
    if (param.data.isEmpty()) return "未绑定"
    val engine = param.get("engine", "")
    val button = param.get("button", "")
    val axis = param.get("axis", "")
    val axisX = param.get("axis_x", "")
    val axisY = param.get("axis_y", "")
    return when {
        axisX.isNotEmpty() || axisY.isNotEmpty() -> "摇杆轴 ($axisX,$axisY)"
        axis.isNotEmpty() -> "轴 $axis"
        button.isNotEmpty() -> "按键 $button"
        engine.isNotEmpty() -> engine
        else -> "已绑定"
    }
}