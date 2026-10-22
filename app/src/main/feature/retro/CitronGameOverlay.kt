package com.winlator.cmod.feature.retro

import android.content.Context
import java.io.File
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
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.wn.CitronHost

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

    // Applies the host's Switch settings (renderer/resolution/vsync/audio) to Citron's
    // config.ini before emulation starts. CitronBootstrap sets the app directory to
    // <extFiles>/citron, so config.ini lives at <extFiles>/citron/config/config.ini.
    // The ini keys mirror yuzu/Citron's config schema (backend is the RendererBackend
    // enum: 0=OpenGL, 1=Vulkan, 2=Null). Values absent from the file are appended.
    private fun applyCitronBootSettings(context: Context) {
        val prefs = context.getSharedPreferences("CITRON", Context.MODE_PRIVATE)
        val ini = File(context.getExternalFilesDir(null), "citron/config/config.ini")
        if (!ini.exists()) return
        val backend = if ((prefs.getString("wn.switch.renderer", "Vulkan") ?: "Vulkan") == "Vulkan") 1 else 0
        val resolution = prefs.getInt("wn.switch.resolution", 1).coerceAtLeast(1)
        val vsync = prefs.getInt("wn.switch.vsync", 1).coerceIn(0, 2)
        val audio = prefs.getInt("wn.switch.audio", 100).coerceIn(0, 100)
        runCatching {
            updateIni(
                ini,
                mapOf(
                    "Renderer" to mapOf(
                        "backend" to backend.toString(),
                        "resolution_scale" to resolution.toString(),
                        "vsync_mode" to vsync.toString(),
                    ),
                    "Audio" to mapOf("output_volume" to audio.toString()),
                ),
            )
        }
    }

    private fun updateIni(file: File, sections: Map<String, Map<String, String>>) {
        val lines = file.readLines().toMutableList()
        val presentSections = mutableSetOf<String>()
        val handled = mutableSetOf<String>()
        var curSection: String? = null
        for (i in lines.indices) {
            val line = lines[i]
            val sect = Regex("^\\[(.+)]$").find(line)?.groupValues?.get(1)
            if (sect != null) {
                curSection = sect
                presentSections.add(sect)
            }
            val m = Regex("^\\s*([\\w.]+)\\s*=\\s*(.*)$").find(line)
            if (curSection != null && m != null) {
                val key = m.groupValues[1]
                val target = sections[curSection]?.get(key)
                if (target != null) {
                    lines[i] = "$key = $target"
                    handled.add("${curSection}/$key")
                }
            }
        }
        val sb = StringBuilder()
        sb.append(lines.joinToString("\n"))
        for ((sect, kv) in sections) {
            val missing = kv.filter { (k, _) -> !handled.contains("$sect/$k") }
            if (missing.isEmpty()) continue
            sb.append(if (!presentSections.contains(sect)) "\n\n[$sect]\n" else "\n")
            for ((k, v) in missing) sb.append("$k = $v\n")
        }
        file.writeText(sb.toString())
    }
}
