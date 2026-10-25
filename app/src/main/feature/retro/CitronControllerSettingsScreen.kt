package com.winlator.cmod.feature.retro

import android.widget.Toast
import android.hardware.input.InputManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.fragment.app.FragmentActivity
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.features.input.model.ButtonName
import org.citron.citron_emu.features.input.model.NativeAnalog
import org.citron.citron_emu.features.input.model.NativeButton
import org.citron.citron_emu.features.input.model.NpadStyleIndex
import org.citron.citron_emu.wn.CitronBootstrap
import org.citron.citron_emu.wn.InputDialogFragment
import org.citron.citron_emu.utils.InputHandler
import org.citron.citron_emu.utils.NativeConfig
import org.citron.citron_emu.utils.ParamPackage

/**
 * Controller settings, rebuilt in Compose but with the exact same data flow as the
 * upstream citron SettingsFragment (addInputSettings / addInputPlayer): a player list,
 * then per-player button/stick mapping rows whose current value is read from
 * NativeInput.getButtonParam / getStickParam and edited through the shared
 * wn.InputDialogFragment mapping popup.
 */
@Composable
internal fun CitronControllerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var selectedPlayer by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        CitronBootstrap.ensure(context)
        runCatching { InputHandler.updateControllerData() }
        refresh++
    }

    Box(Modifier.fillMaxSize()) {
        if (selectedPlayer == null) {
            PlayerListScreen(
                onBack = onBack,
                onSelect = { selectedPlayer = it },
                refresh = refresh,
            )
        } else {
            PlayerDetailScreen(
                playerIndex = selectedPlayer!!,
                onBack = { selectedPlayer = null },
                refresh = refresh,
                onRefresh = { refresh++ },
            )
        }
    }
}

private data class PlayerSummary(
    val index: Int,
    val connected: Boolean,
    val profile: String,
)

@Composable
private fun PlayerListScreen(
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
    refresh: Int,
) {
    val listState = rememberLazyListState()
    val players = remember(refresh) {
        runCatching {
            val settings = NativeConfig.getInputSettings(true)
            (0..7).map { i ->
                PlayerSummary(
                    index = i,
                    connected = NativeInput.getIsConnected(i),
                    profile = settings.getOrNull(i)?.profileName ?: "",
                )
            }
        }.getOrDefault((0..7).map { PlayerSummary(it, false, "") })
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { RetroBackRow(onBack) }
            item { RetroGroupTitle("控制器玩家绑定") }
            items(players) { p ->
                RetroSettingGroup {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(p.index) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (p.connected) Color(0xFF4CAF50) else Color.Gray,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "玩家 ${p.index + 1}",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (p.connected) "已连接" else "未连接",
                                color = PageSub,
                                fontSize = 12.sp,
                            )
                        }
                        if (p.profile.isNotEmpty()) {
                            Text(p.profile, color = PageSub, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private data class InputItem(
    val title: String,
    val value: String,
    val button: NativeButton? = null,
    val analog: NativeAnalog? = null,
    val dir: String? = null,
)

private data class InputGroup(val header: String, val items: List<InputItem>)

@Composable
private fun PlayerDetailScreen(
    playerIndex: Int,
    onBack: () -> Unit,
    refresh: Int,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        runCatching { InputHandler.updateControllerData() }
        onRefresh()
    }

    // Listen for physical controller plug / unplug events so the auto-map
    // dropdown and connection status stay in sync in real time.
    fun rescan() {
        runCatching { InputHandler.updateControllerData() }
        onRefresh()
    }

    DisposableEffect(context) {
        val inputManager =
            context.getSystemService(android.content.Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { rescan() }
            override fun onInputDeviceChanged(deviceId: Int) { rescan() }
            override fun onInputDeviceRemoved(deviceId: Int) { rescan() }
        }
        inputManager?.registerInputDeviceListener(listener, null)
        onDispose { inputManager?.unregisterInputDeviceListener(listener) }
    }

    val connected = remember(refresh) {
        runCatching { NativeInput.getIsConnected(playerIndex) }.getOrDefault(false)
    }
    val style = remember(refresh) {
        runCatching { NativeInput.getStyleIndex(playerIndex) }.getOrDefault(NpadStyleIndex.Fullkey)
    }
    val supportedStyles = remember {
        runCatching { NativeInput.getSupportedStyleTags(playerIndex) }
            .getOrDefault(listOf(NpadStyleIndex.Fullkey))
    }
    val devices = remember(refresh) { InputHandler.androidControllers.values.toList() }
    val profileNames = remember {
        runCatching {
            NativeInput.loadInputProfiles()
            NativeInput.getInputProfileNames().toList()
        }.getOrDefault(emptyList())
    }
    val currentProfile = remember(refresh) {
        runCatching { NativeConfig.getInputSettings(true).getOrNull(playerIndex)?.profileName ?: "" }
            .getOrDefault("")
    }
    val vibrationEnabled = remember(refresh) {
        runCatching { NativeConfig.getInputSettings(true)[playerIndex].vibrationEnabled }
            .getOrDefault(true)
    }
    val useSystemVibrator = remember(refresh) {
        runCatching { NativeConfig.getInputSettings(true)[playerIndex].useSystemVibrator }
            .getOrDefault(playerIndex == 0)
    }

    val groups = remember(style, refresh) { buildInputRows(playerIndex, style) }

    fun showMapping(item: InputItem) {
        val act = activity ?: return
        InputDialogFragment.newInstance(
            playerIndex,
            item.button,
            item.analog,
            item.dir,
            item.title,
            onRefresh,
        ).show(act.supportFragmentManager, "map_${item.title}")
    }

    fun toggleConnected() {
        val connecting = !connected
        NativeInput.connectControllers(playerIndex, connecting)

        // When connecting, auto-bind the first available physical controller
        // so the button/stick list immediately shows real mappings instead of
        // "未设置". Skip if the player already has a manual mapping.
        if (connecting) {
            val physical = InputHandler.androidControllers.values.firstOrNull()
            if (physical != null) {
                val alreadyBound = runCatching {
                    NativeInput.getButtonParam(playerIndex, NativeButton.A).has("engine")
                }.getOrDefault(false)
                if (!alreadyBound) {
                    val port = physical.getPort()
                    val params = InputHandler.registeredControllers.firstOrNull {
                        it.get("port", -1) == port
                    }
                    if (params != null) {
                        NativeInput.updateMappingsWithDefault(
                            playerIndex, params,
                            params.get("display", physical.getName())
                        )
                    }
                }
            }
        }

        NativeConfig.saveGlobalConfig()
        NativeInput.reloadInputDevices()
        onRefresh()
    }

    fun toggleVibration() {
        runCatching {
            val s = NativeConfig.getInputSettings(true)
            s[playerIndex].vibrationEnabled = !vibrationEnabled
            NativeConfig.setInputSettings(s, true)
            NativeConfig.saveGlobalConfig()
        }
        onRefresh()
    }

    fun toggleSystemVibrator() {
        runCatching {
            val s = NativeConfig.getInputSettings(true)
            s[playerIndex].useSystemVibrator = !useSystemVibrator
            NativeConfig.setInputSettings(s, true)
            NativeConfig.saveGlobalConfig()
        }
        onRefresh()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { RetroBackRow(onBack) }

            // --- Player-level settings (mirrors addInputPlayer top section) ---
            item {
                RetroSettingGroup {
                    RetroGroupTitle("玩家 ${playerIndex + 1} 设置")

                    RetroSettingSwitch(
                        label = "连接",
                        checked = connected,
                        subtitle = if (connected) "已连接" else "未连接",
                        onCheckedChange = { toggleConnected() },
                    )

                    val styleLabels = supportedStyles.map { styleLabel(it) }
                    RetroSettingDropdown(
                        "控制器类型",
                        styleLabels,
                        supportedStyles.indexOf(style).coerceAtLeast(0),
                    ) { idx ->
                        NativeInput.setStyleIndex(playerIndex, supportedStyles[idx])
                        NativeConfig.saveGlobalConfig()
                        NativeInput.reloadInputDevices()
                        onRefresh()
                    }

                    val physicalDevices = devices
                    if (physicalDevices.isNotEmpty()) {
                        val devLabels =
                            physicalDevices.map { dev -> "手柄 ${dev.getName()} (端口 ${dev.getPort()})" }
                        RetroSettingDropdown("自动映射", devLabels, 0) { idx ->
                            val dev = physicalDevices[idx]
                            val port = dev.getPort()
                            // Use the full ParamPackage from native-side registeredControllers
                            // (populated by getInputDevices() during updateControllerData).
                            // A manually constructed ParamPackage with just guid+port misses
                            // critical keys (axis declarations, button bitmasks, etc.) that
                            // GetButtonMappingForDevice needs to compute the default mapping,
                            // causing all buttons to stay "未设置".
                            val params = InputHandler.registeredControllers.firstOrNull {
                                it.get("port", -1) == port
                            }
                            if (params != null) {
                                NativeInput.updateMappingsWithDefault(
                                    playerIndex, params,
                                    params.get("display", dev.getName())
                                )
                                NativeConfig.saveGlobalConfig()
                                NativeInput.reloadInputDevices()
                                onRefresh()
                            }
                        }
                    }

                    val profEntries = listOf("(无)") + profileNames
                    RetroSettingDropdown(
                        "配置方案",
                        profEntries,
                        profEntries.indexOf(currentProfile).coerceAtLeast(0),
                    ) { idx ->
                        if (idx > 0) {
                            NativeInput.loadProfile(profileNames[idx - 1], playerIndex)
                            NativeConfig.saveGlobalConfig()
                            NativeInput.reloadInputDevices()
                            onRefresh()
                        }
                    }

                    Button(
                        onClick = {
                            NativeInput.resetControllerMappings(playerIndex)
                            NativeConfig.saveGlobalConfig()
                            NativeInput.reloadInputDevices()
                            onRefresh()
                            Toast.makeText(
                                context,
                                "已恢复默认映射",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text("恢复默认映射")
                    }
                }
            }

            // --- Vibration (mirrors addInputPlayer vibration block) ---
            item {
                RetroSettingGroup {
                    RetroGroupTitle("振动")
                    RetroSettingSwitch(
                        label = "启用振动",
                        checked = vibrationEnabled,
                        subtitle = if (vibrationEnabled) "开" else "关",
                        onCheckedChange = { toggleVibration() },
                    )
                    RetroSettingSwitch(
                        label = "使用系统振动器",
                        checked = useSystemVibrator,
                        subtitle = if (useSystemVibrator) "开" else "关",
                        onCheckedChange = { toggleSystemVibrator() },
                    )
                }
            }

            // --- Button / stick mapping groups (mirrors addInputPlayer body) ---
            items(groups) { group ->
                RetroSettingGroup {
                    RetroGroupTitle(group.header)
                    group.items.forEach { item ->
                        val clickable = item.button != null || item.analog != null
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (clickable) Modifier.clickable { showMapping(item) } else Modifier)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontSize = 14.sp)
                                Text(item.value, color = PageSub, fontSize = 12.sp)
                            }
                            if (clickable) {
                                Text(">", color = PageSub, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Mapping-row builder — a faithful port of SettingsFragmentPresenter.addInputPlayer
// (the button/stick list is built from the player's NpadStyleIndex exactly like
// upstream, only the rendering is Compose instead of RecyclerView).
// ----------------------------------------------------------------------------

private fun buildInputRows(playerIndex: Int, style: NpadStyleIndex): List<InputGroup> {
    val groups = mutableListOf<InputGroup>()
    fun buttonItem(button: NativeButton, title: String) =
        InputItem(title, buttonDisplay(playerIndex, button), button = button)
    fun analogItem(analog: NativeAnalog, dir: String, title: String) =
        InputItem(title, analogDisplay(playerIndex, analog, dir), analog = analog, dir = dir)

    // Buttons
    val buttons = when (style) {
        NpadStyleIndex.Fullkey, NpadStyleIndex.Handheld, NpadStyleIndex.JoyconDual -> listOf(
            buttonItem(NativeButton.A, "A"),
            buttonItem(NativeButton.B, "B"),
            buttonItem(NativeButton.X, "X"),
            buttonItem(NativeButton.Y, "Y"),
            buttonItem(NativeButton.Plus, "+"),
            buttonItem(NativeButton.Minus, "-"),
            buttonItem(NativeButton.Home, "Home"),
            buttonItem(NativeButton.Capture, "Capture"),
        )
        NpadStyleIndex.JoyconLeft -> listOf(
            buttonItem(NativeButton.Minus, "-"),
            buttonItem(NativeButton.Capture, "Capture"),
        )
        NpadStyleIndex.JoyconRight -> listOf(
            buttonItem(NativeButton.A, "A"),
            buttonItem(NativeButton.B, "B"),
            buttonItem(NativeButton.X, "X"),
            buttonItem(NativeButton.Y, "Y"),
            buttonItem(NativeButton.Plus, "+"),
            buttonItem(NativeButton.Home, "Home"),
        )
        NpadStyleIndex.GameCube -> listOf(
            buttonItem(NativeButton.A, "A"),
            buttonItem(NativeButton.B, "B"),
            buttonItem(NativeButton.X, "X"),
            buttonItem(NativeButton.Y, "Y"),
            buttonItem(NativeButton.Plus, "Start"),
        )
        else -> emptyList()
    }
    if (buttons.isNotEmpty()) groups.add(InputGroup("按键", buttons))

    // DPad
    val dpad = when (style) {
        NpadStyleIndex.Fullkey, NpadStyleIndex.Handheld, NpadStyleIndex.JoyconDual, NpadStyleIndex.JoyconLeft -> listOf(
            buttonItem(NativeButton.DUp, "↑"),
            buttonItem(NativeButton.DDown, "↓"),
            buttonItem(NativeButton.DLeft, "←"),
            buttonItem(NativeButton.DRight, "→"),
        )
        else -> emptyList()
    }
    if (dpad.isNotEmpty()) groups.add(InputGroup("方向键", dpad))

    // Left stick
    val leftStick = style in listOf(
        NpadStyleIndex.Fullkey, NpadStyleIndex.Handheld, NpadStyleIndex.JoyconDual, NpadStyleIndex.JoyconLeft,
    )
    val leftGameCube = style == NpadStyleIndex.GameCube
    if (leftStick || leftGameCube) {
        val items = mutableListOf<InputItem>().apply {
            add(analogItem(NativeAnalog.LStick, "up", "↑"))
            add(analogItem(NativeAnalog.LStick, "down", "↓"))
            add(analogItem(NativeAnalog.LStick, "left", "←"))
            add(analogItem(NativeAnalog.LStick, "right", "→"))
            if (leftStick) add(buttonItem(NativeButton.LStick, "按下"))
            addAll(getExtraStickItems(playerIndex, NativeAnalog.LStick))
        }
        groups.add(InputGroup(if (leftGameCube) "控制杆" else "左摇杆", items))
    }

    // Right stick
    val rightStick = style in listOf(
        NpadStyleIndex.Fullkey, NpadStyleIndex.Handheld, NpadStyleIndex.JoyconDual, NpadStyleIndex.JoyconRight,
    )
    if (rightStick || leftGameCube) {
        val items = mutableListOf<InputItem>().apply {
            add(analogItem(NativeAnalog.RStick, "up", "↑"))
            add(analogItem(NativeAnalog.RStick, "down", "↓"))
            add(analogItem(NativeAnalog.RStick, "left", "←"))
            add(analogItem(NativeAnalog.RStick, "right", "→"))
            if (rightStick) add(buttonItem(NativeButton.RStick, "按下"))
            addAll(getExtraStickItems(playerIndex, NativeAnalog.RStick))
        }
        groups.add(InputGroup(if (leftGameCube) "C 摇杆" else "右摇杆", items))
    }

    // Triggers
    val triggers = when (style) {
        NpadStyleIndex.Fullkey, NpadStyleIndex.Handheld -> listOf(
            buttonItem(NativeButton.L, "L"),
            buttonItem(NativeButton.R, "R"),
            buttonItem(NativeButton.ZL, "ZL"),
            buttonItem(NativeButton.ZR, "ZR"),
        )
        NpadStyleIndex.JoyconDual -> listOf(
            buttonItem(NativeButton.L, "L"),
            buttonItem(NativeButton.R, "R"),
            buttonItem(NativeButton.ZL, "ZL"),
            buttonItem(NativeButton.ZR, "ZR"),
            buttonItem(NativeButton.SLLeft, "SL(左)"),
            buttonItem(NativeButton.SRLeft, "SR(左)"),
            buttonItem(NativeButton.SLRight, "SL(右)"),
            buttonItem(NativeButton.SRRight, "SR(右)"),
        )
        NpadStyleIndex.JoyconLeft -> listOf(
            buttonItem(NativeButton.L, "L"),
            buttonItem(NativeButton.ZL, "ZL"),
            buttonItem(NativeButton.SLLeft, "SL(左)"),
            buttonItem(NativeButton.SRLeft, "SR(左)"),
        )
        NpadStyleIndex.JoyconRight -> listOf(
            buttonItem(NativeButton.R, "R"),
            buttonItem(NativeButton.ZR, "ZR"),
            buttonItem(NativeButton.SLRight, "SL(右)"),
            buttonItem(NativeButton.SRRight, "SR(右)"),
        )
        NpadStyleIndex.GameCube -> listOf(
            buttonItem(NativeButton.R, "Z"),
            buttonItem(NativeButton.ZL, "L"),
            buttonItem(NativeButton.ZR, "R"),
        )
        else -> emptyList()
    }
    if (triggers.isNotEmpty()) groups.add(InputGroup("扳机", triggers))

    return groups
}

private fun getExtraStickItems(playerIndex: Int, analog: NativeAnalog): List<InputItem> {
    val params = NativeInput.getStickParam(playerIndex, analog)
    val isController = NativeInput.isController(params)
    return if (isController) {
        listOf(
            InputItem("范围", stickIntDisplay(playerIndex, analog, "range", 0.95f)),
            InputItem("死区", stickIntDisplay(playerIndex, analog, "deadzone", 0.15f)),
        )
    } else {
        listOf(
            InputItem("修饰键", analogDisplay(playerIndex, analog, "modifier")),
            InputItem("修饰范围", stickIntDisplay(playerIndex, analog, "modifier_scale", 0.5f)),
        )
    }
}

// ----------------------------------------------------------------------------
// Mapping-value formatting — identical logic to emulator's InputSetting
// buttonToText / analogToText / getDisplayString, only the strings are localized.
// ----------------------------------------------------------------------------

private const val NOT_SET = "未设置"
private const val INVALID = "无效"
private const val UNKNOWN = "未知"
private const val UNUSED = "未使用"

private fun buttonDisplay(playerIndex: Int, button: NativeButton): String {
    val params = NativeInput.getButtonParam(playerIndex, button)
    return formatInput(params, buttonToText(params))
}

private fun analogDisplay(playerIndex: Int, analog: NativeAnalog, dir: String): String {
    val params = NativeInput.getStickParam(playerIndex, analog)
    return formatInput(params, analogToText(params, dir))
}

private fun formatInput(params: ParamPackage, control: String): String {
    val device = params.get("display", "")
    if (device.isEmpty()) return NOT_SET
    return "$device: $control"
}

private fun buttonToText(param: ParamPackage): String {
    if (!param.has("engine")) return NOT_SET
    val toggle = if (param.get("toggle", false)) "~" else ""
    val inverted = if (param.get("inverted", false)) "!" else ""
    val invert = if (param.get("invert", "+") == "-") "-" else ""
    val turbo = if (param.get("turbo", false)) "$" else ""
    val name = NativeInput.getButtonName(param)
    if (name == ButtonName.Invalid) return INVALID
    if (name == ButtonName.Engine) return param.get("engine", "")
    if (name == ButtonName.Value) {
        if (param.has("hat")) {
            return "方向 ${dirName(param.get("direction", ""))}"
        }
        if (param.has("axis")) {
            val axis = param.get("axis", "")
            return "轴 $axis"
        }
        if (param.has("button")) {
            val button = param.get("button", "")
            return "按键 $button"
        }
    }
    return UNKNOWN
}

private fun analogToText(param: ParamPackage, dir: String): String {
    if (!param.has("engine")) return NOT_SET
    if (param.get("engine", "") == "analog_from_button") {
        return buttonToText(ParamPackage(param.get(dir, "")))
    }
    if (!param.has("axis_x") || !param.has("axis_y")) return UNKNOWN
    val xAxis = param.get("axis_x", "")
    val yAxis = param.get("axis_y", "")
    val xInvert = param.get("invert_x", "+") == "-"
    val yInvert = param.get("invert_y", "+") == "-"
    if (dir == "modifier") return UNUSED
    return when (dir) {
        "up" -> "轴 $yAxis ${if (yInvert) "+" else "-"}"
        "down" -> "轴 $yAxis ${if (yInvert) "-" else "+"}"
        "left" -> "轴 $xAxis ${if (xInvert) "+" else "-"}"
        "right" -> "轴 $xAxis ${if (xInvert) "-" else "+"}"
        else -> UNKNOWN
    }
}

private fun stickIntDisplay(playerIndex: Int, analog: NativeAnalog, name: String, def: Float): String {
    val params = NativeInput.getStickParam(playerIndex, analog)
    val v = (params.get(name, def) * 100).toInt()
    return "$v%"
}

private fun dirName(d: String): String = when (d) {
    "up" -> "上"
    "down" -> "下"
    "left" -> "左"
    "right" -> "右"
    else -> d
}

private fun styleLabel(style: NpadStyleIndex): String = when (style) {
    NpadStyleIndex.Fullkey -> "Pro 手柄"
    NpadStyleIndex.Handheld -> "掌机"
    NpadStyleIndex.JoyconDual -> "Joy-Con 双持"
    NpadStyleIndex.JoyconLeft -> "左 Joy-Con"
    NpadStyleIndex.JoyconRight -> "右 Joy-Con"
    NpadStyleIndex.GameCube -> "GC 手柄"
    else -> style.name
}