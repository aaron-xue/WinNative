package com.winlator.cmod.feature.retro

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.shared.framegen.FrameGen
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.wn.CitronHost
import org.citron.citron_emu.wn.CitronConsts
import org.citron.citron_emu.utils.NativeConfig
import java.io.File

// Host-side overlay for the embedded Nintendo Switch emulator. Mirrors the role of
// Ps2GameOverlay: it builds the Compose overlay (touch controls + drawer menu) and
// wires the host-provided CitronHost slots so WnCitronActivity can drive them.
//
// This runs in the :switch process (PluviaApp.install() executes per-process), so
// NativeLibrary/NativeInput calls here resolve against libcitron-android.so.
object CitronGameOverlay {
    @Volatile
    private var attachedTo: Activity? = null

    fun install() {
        attachedTo = null
        CitronHost.attachOverlay = attach@{ activity ->
            if (attachedTo === activity) return@attach
            attachedTo = activity
            attach(activity)
        }
    }

    private fun attach(activity: Activity) {
        val menu = RetroMenuController()
        var paused by mutableStateOf(false)
        var fastForward by mutableStateOf(false)

        val listener = object : RetroInputView.Listener {
            override fun onButton(keyCode: Int, down: Boolean) {
                if (!inputUp()) return
                NativeInput.onGamePadButtonEventByPortTimed(
                    NativeInput.Player1Device,
                    keyCode,
                    if (down) NativeInput.ButtonState.PRESSED else NativeInput.ButtonState.RELEASED,
                    System.nanoTime(),
                )
            }

            override fun onDpad(x: Float, y: Float) {
                if (!inputUp()) return
                NativeInput.onGamePadAxisEventByPort(
                    NativeInput.Player1Device,
                    intArrayOf(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y),
                    floatArrayOf(x, y),
                    2,
                )
            }

            override fun onStick(x: Float, y: Float) {
                if (!inputUp()) return
                NativeInput.onGamePadAxisEventByPort(
                    NativeInput.Player1Device,
                    intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y),
                    floatArrayOf(x, y),
                    2,
                )
            }

            override fun onRightStick(x: Float, y: Float) {
                if (!inputUp()) return
                NativeInput.onGamePadAxisEventByPort(
                    NativeInput.Player1Device,
                    intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY),
                    floatArrayOf(x, y),
                    2,
                )
            }

            override fun onMenu() {
                if (!inputUp()) return
                CitronHost.openMenu?.invoke()
            }
        }

        menu.entriesProvider = {
            buildEntries(activity, menu, { paused = it }, { fastForward = it })
        }

        CitronHost.openMenu = { activity.runOnUiThread { menu.open() } }
        CitronHost.closeMenu = { activity.runOnUiThread { menu.close() } }
        CitronHost.isMenuOpen = { menu.visible }
        CitronHost.menuKeyHandler = { ev -> menu.handleKey(ev.keyCode, ev.action) }
        CitronHost.menuAxisHandler = { x, y -> menu.handleAxis(x, y) }
        CitronHost.applyBootSettings = { ctx -> applyCitronBootSettings(ctx) }
        CitronHost.openSettings = { activity ->
            openSwitchSettings(activity)
        }

        val overlay = ComposeView(activity).apply {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { ctx ->
                        RetroInputView(ctx, listener, RetroSystems.SWITCH)
                    })
                    RetroDrawerMenu(menu)
                }
            }
        }
        activity.addContentView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun buildEntries(
        activity: Activity,
        menu: RetroMenuController,
        onPausedChange: (Boolean) -> Unit,
        onFastForwardChange: (Boolean) -> Unit,
    ): List<RetroMenuEntry> {
        val entries = mutableListOf<RetroMenuEntry>()
        entries += RetroMenuEntry.Action(
            label = "Exit",
            icon = Icons.Outlined.ExitToApp,
            danger = true,
        ) {
            menu.close()
            activity.finish()
        }
        entries += RetroMenuEntry.Toggle(
            label = "Pause",
            checked = false,
        ) { checked ->
            onPausedChange(checked)
            runCatching {
                if (checked) NativeLibrary.pauseEmulation() else NativeLibrary.unpauseEmulation()
            }
        }
        entries += RetroMenuEntry.Toggle(
            label = "Fast-forward",
            checked = false,
        ) { checked ->
            onFastForwardChange(checked)
            CitronHost.setFastForward?.invoke(checked)
        }
        for (i in 1..8) {
            entries += RetroMenuEntry.Action(
                label = "Save state $i",
                icon = Icons.Outlined.Save,
            ) { CitronHost.saveState?.invoke(i) }
            entries += RetroMenuEntry.Action(
                label = "Load state $i",
                icon = Icons.Outlined.FolderOpen,
            ) { CitronHost.loadState?.invoke(i) }
        }
        return entries
    }

    private fun inputUp(): Boolean = runCatching { NativeLibrary.isRunning() }.getOrDefault(false)

    // Opens the native Switch settings dialog on top of the running game.
    // Settings saved in the dialog are immediately applied to the running emulator.
    private fun openSwitchSettings(activity: Activity) {
        val intent = activity.intent
        val shortcutPath = intent.getStringExtra(CitronConsts.EXTRA_SHORTCUT_PATH)
        val containerId = intent.getIntExtra(CitronConsts.EXTRA_CONTAINER_ID, -1)
        if (shortcutPath == null || containerId == -1) {
            Toast.makeText(activity, "Settings unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = ContainerManager(activity)
        val container = if (containerId == ContainerManager.RETRO_CONTAINER_ID) {
            cm.retroContainer
        } else {
            cm.getContainerById(containerId)
        }
        if (container == null) {
            Toast.makeText(activity, "Settings unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(shortcutPath)
        if (!file.isFile) {
            Toast.makeText(activity, "Settings unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val shortcut = Shortcut(container, file)
        val dialog = RetroSwitchSettingsDialog(activity, shortcut)
        dialog.onDismissed = {
            applyCitronBootSettings(activity)
            runCatching { NativeLibrary.applySettings() }
            applyFrameGenSettings(activity, shortcut)
            val fgEnabled = RetroFrameGen.enabled(activity, shortcut, RetroSystems.SWITCH.id)
            CitronHost.onFrameGenReservedChanged?.invoke(fgEnabled)
        }
        dialog.show()
    }

    // Applies FrameGen settings from the shortcut to the running FrameGen singleton.
    // When FrameGen is already active (surface was wrapped at boot) parameter changes
    // take effect immediately via reconfigure / setGenerating.  Enabling FrameGen from a
    // cold (non-wrapped) boot state requires a game restart.
    private fun applyFrameGenSettings(context: Context, shortcut: Shortcut) {
        val enabled = RetroFrameGen.enabled(context, shortcut, RetroSystems.SWITCH.id)
        if (FrameGen.active) {
            if (enabled) {
                FrameGen.setGenerating(true)
                FrameGen.reconfigure(
                    RetroFrameGen.multiplier(context, shortcut, RetroSystems.SWITCH.id),
                    RetroFrameGen.targetRate(context, shortcut, RetroSystems.SWITCH.id),
                    RetroFrameGen.flowScale(context, shortcut, RetroSystems.SWITCH.id),
                )
            } else {
                FrameGen.setGenerating(false)
            }
        } else if (enabled) {
            FrameGen.install(
                context,
                RetroFrameGen.liveOptions(
                    context,
                    RetroFrameGen.multiplier(context, shortcut, RetroSystems.SWITCH.id),
                    RetroFrameGen.targetRate(context, shortcut, RetroSystems.SWITCH.id),
                    RetroFrameGen.flowScale(context, shortcut, RetroSystems.SWITCH.id),
                ),
            )
        }
    }

    // Applies all host-side Switch graphics settings via NativeConfig before emulation
    // starts. Keys are read from the "CITRON" SharedPreferences and written directly to
    // Citron's native config store using NativeConfig.setInt() / setBoolean().
    private fun applyCitronBootSettings(context: Context) {
        val prefs = context.getSharedPreferences("CITRON", Context.MODE_PRIVATE)

        val intMap = mapOf(
            "gpu_accuracy" to 0,
            "resolution_setup" to 3,
            "use_vsync" to 2,
            "scaling_filter" to 1,
            "anti_aliasing" to 0,
            "max_anisotropy" to 0,
            "screen_layout" to 0,
            "aspect_ratio" to 0,
            "vertical_alignment" to 0,
            "fsr_sharpening_slider" to 25,
            "fsr2_quality_mode" to 1,
        )
        val boolMap = mapOf(
            "use_disk_shader_cache" to true,
            "force_max_clock" to false,
            "use_asynchronous_shaders" to false,
            "use_reactive_flushing" to false,
            "picture_in_picture" to false,
            "airplane_mode" to false,
        )
        runCatching {
            for ((key, default) in intMap) {
                NativeConfig.setInt(key, prefs.getInt(key, default))
            }
            for ((key, default) in boolMap) {
                NativeConfig.setBoolean(key, prefs.getBoolean(key, default))
            }
            NativeConfig.setInt("audio_volume", prefs.getInt("wn.switch.audio", 100))
        }
    }
}