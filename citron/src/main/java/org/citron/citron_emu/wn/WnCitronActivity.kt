package org.citron.citron_emu.wn

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.overlay.InputOverlay
import org.citron.citron_emu.utils.InputHandler
import java.io.File

// Embedded Switch emulator host. Runs in the :switch process. The host app
// launches it via an explicit Intent carrying the ROM uri (and, optionally, the
// absolute path as EXTRA_ROM_PATH). All rendering/audio/input happens in
// libcitron-android.so; this Activity only wires up the Surface, lifecycle, and
// routes menu hotkeys to the host-provided CitronHost slots.
class WnCitronActivity : ComponentActivity(), SurfaceHolder.Callback {

    private val tag = "WnCitron"
    private var surfaceView: SurfaceView? = null
    private var surface: Surface? = null
    private var inputOverlay: InputOverlay? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val ready = java.util.concurrent.atomic.AtomicBoolean(false)
    private var menuShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
            root.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)

        // Native-side hooks. Save/load/fast-forward have no native API yet, so they
        // are implemented as user-visible no-ops until P2 wires the backend.
        CitronHost.saveState = { slot -> toast("Save state slot $slot not available yet") }
        CitronHost.loadState = { slot -> toast("Load state slot $slot not available yet") }
        CitronHost.setFastForward = { /* TODO: NativeConfig speed-limit in P2 */ }

        // Native bootstrap runs off the main thread to avoid ANR during the
        // (potentially multi-second) system/HLE initialization.
        Thread({
            runCatching { CitronBootstrap.ensure(this) }.onFailure {
                Log.e(tag, "native bootstrap failed", it)
                runOnUiThread { finish() }
                return@Thread
            }
            runOnUiThread {
                ready.set(true)
                // Attach the host overlay after layout settles (mirrors PS2's postDelayed).
                window.decorView.postDelayed({
                    CitronHost.attachOverlay?.invoke(this@WnCitronActivity)
                    // Add the on-screen gamepad overlay ABOVE the host's overlay so it
                    // actually receives touch events; otherwise the host Compose overlay
                    // intercepts every touch and our controls stay dead.
                    inputOverlay = InputOverlay(this@WnCitronActivity)
                    window.addContentView(
                        inputOverlay,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }, 50)
                maybeRun()
            }
        }, "WnCitronInit").start()
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuShown) {
            val handled = CitronHost.menuKeyHandler?.invoke(event) ?: false
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                menuShown = false
                runCatching { NativeLibrary.unpauseEmulation() }
            }
            return handled || event.keyCode == KeyEvent.KEYCODE_BACK
        }
        // BUTTON_MODE (the controller "guide" button) toggles the in-emulator menu.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.action == KeyEvent.ACTION_DOWN) {
            menuShown = true
            CitronHost.openMenu?.invoke()
            runCatching { NativeLibrary.pauseEmulation() }
            return true
        }
        // Route physical gamepad keys into the emulated controller.
        if (InputHandler.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Only intercept real gamepad sticks/triggers; let the rest fall through.
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return super.dispatchGenericMotionEvent(event)
        }
        if (menuShown) {
            return super.dispatchGenericMotionEvent(event)
        }
        window.decorView.requestUnbufferedDispatch(event.source)
        if (event.action == MotionEvent.ACTION_CANCEL) {
            InputHandler.releaseAllInputs()
            return true
        }
        return InputHandler.dispatchGenericMotionEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        NativeLibrary.surfaceChanged(surface)
        maybeRun()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeLibrary.surfaceChanged(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeLibrary.surfaceDestroyed()
        surface = null
    }

    // Run exactly once, after both bootstrap and a live Surface are available.
    private fun maybeRun() {
        if (!ready.get() || surface == null) return
        if (!started.compareAndSet(false, true)) return
        // Prefer the real on-disk path (EXTRA_ROM_PATH) over the content:// URI. The
        // :switch process shares the app's UID, so it can open the real file directly,
        // and the installer cannot read a content:// URI anyway. Fall back to the
        // content:// URI only when no real path is supplied.
        val realRomPath = intent.getStringExtra(CitronConsts.EXTRA_ROM_PATH)
        val romPath = if (!realRomPath.isNullOrEmpty() && File(realRomPath).isFile) {
            realRomPath
        } else {
            intent.data?.toString() ?: realRomPath
        }
        if (romPath.isNullOrEmpty()) {
            Log.e(tag, "no rom path supplied")
            finish()
            return
        }
        // Only NSP / NSZ files are supported.
        if (!romPath.endsWith(".nsp", ignoreCase = true) && !romPath.endsWith(".nsz", ignoreCase = true)) {
            Log.e(tag, "unsupported file (only NSP/NSZ supported): $romPath")
            finish()
            return
        }
        CitronHost.applyBootSettings?.invoke(this)
        Thread({
            try {
                val siblingUris = intent.getStringArrayListExtra(CitronConsts.EXTRA_ROM_SIBLINGS)
                // Install the base game into NAND so the title is registered and Add-On
                // Content resolution can find the sibling DLC / update files below.
                Log.i(tag, "installing NSP to NAND: $romPath")
                CitronInstaller.install(listOf(romPath))
                // Register sibling DLC / update NSPs as external content so the game can
                // mount them as Add-On Content at startup.
                if (!siblingUris.isNullOrEmpty()) {
                    Log.i(tag, "registering ${siblingUris.size} sibling DLC/update file(s)")
                    CitronInstaller.registerExternal(siblingUris)
                }
                // Register physical gamepads and the on-screen overlay input device so the
                // emulated controller receives both external gamepad events (via
                // InputHandler) and overlay button/axis events (via onOverlayButtonEvent).
                // The input subsystem is already up because CitronBootstrap.ensure()
                // initialized the system beforehand.
                runCatching {
                    InputHandler.updateControllerData()
                    Log.i(tag, "registered input devices (physical + overlay)")
                }.onFailure { Log.e(tag, "InputHandler.updateControllerData failed", it) }
                Log.i(tag, "starting emulation for $romPath")
                // frontendInitiated=false mirrors the upstream EmulationFragment call.
                NativeLibrary.run(romPath, 0, false)
            } catch (t: Throwable) {
                Log.e(tag, "run failed", t)
            } finally {
                runOnUiThread { finish() }
            }
        }, "WnCitronRun").start()
    }

    override fun onPause() {
        super.onPause()
        InputHandler.releaseAllInputs()
        if (started.get() && !menuShown) runCatching { NativeLibrary.pauseEmulation() }
    }

    override fun onResume() {
        super.onResume()
        if (started.get() && !menuShown) runCatching { NativeLibrary.unpauseEmulation() }
    }

    override fun onDestroy() {
        InputHandler.releaseAllInputs()
        runCatching { NativeLibrary.stopEmulation() }
        super.onDestroy()
        // Tear down the process so the next launch starts from a clean state.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
