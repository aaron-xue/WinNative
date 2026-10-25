package org.citron.citron_emu.wn

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.R
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.features.input.model.NativeButton
import org.citron.citron_emu.overlay.InputOverlay
import org.citron.citron_emu.utils.InputHandler
import org.citron.citron_emu.utils.NativeConfig
import org.citron.citron_emu.utils.ParamPackage
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

    // Loading overlay (game title + spinner + "Loading…") shown over the SurfaceView while
    // the NSP installs and the core boots; dismissed once NativeLibrary.isRunning() is true
    // (mirrors citron's EmulationFragment loading indicator).
    private var loadingOverlay: View? = null
    private val overlaysAttached = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunning = object : Runnable {
        override fun run() {
            if (runCatching { NativeLibrary.isRunning() }.getOrDefault(false)) {
                loadingOverlay?.visibility = View.GONE
                attachGameOverlays()
            } else {
                mainHandler.postDelayed(this, 100)
            }
        }
    }

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

        // Loading overlay on top of the SurfaceView while the NSP installs and the core
        // boots. Dismissed by the isRunning() poll in maybeRun(); the host overlay and the
        // on-screen gamepad are attached then too (see attachGameOverlays) so they never
        // float above the loading screen.
        loadingOverlay = buildLoadingOverlay(
            intent.getStringExtra(CitronConsts.EXTRA_GAME_NAME),
            intent.getStringExtra(CitronConsts.EXTRA_GAME_ICON),
        )
        root.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

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
                // The SurfaceView surface may have been created before the .so finished
                // loading; apply it now that the native side is live.
                if (surface != null) runCatching { NativeLibrary.surfaceChanged(surface) }
                // Overlays (host Compose menu + on-screen gamepad) are attached in
                // attachGameOverlays() once emulation is actually running, so they don't
                // appear above the loading screen.
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
        // A device may be registered as a gamepad yet emit motion events whose
        // `source` lacks the JOYSTICK/GAMEPAD bit (OTG / keyboard-presenting pads),
        // so also intercept events that originate from a registered controller.
        val dev = event.device
        val isGamepadSource = dev != null &&
            (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)
        val fromRegisteredPad = dev != null &&
            InputHandler.androidControllers.containsKey(dev.controllerNumber)
        if (!isGamepadSource && !fromRegisteredPad) {
            return super.dispatchGenericMotionEvent(event)
        }
        if (menuShown) {
            return super.dispatchGenericMotionEvent(event)
        }
        window.decorView.requestUnbufferedDispatch(event.source)
        if (event.action == MotionEvent.ACTION_CANCEL) {
            runCatching { InputHandler.releaseAllInputs() }
            return true
        }
        return InputHandler.dispatchGenericMotionEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        // Only touch the native surface once CitronBootstrap.ensure() has loaded the .so
        // (ready==true). surfaceCreated fires on the SurfaceView's first preDraw, which
        // can beat the bootstrap thread and used to throw UnsatisfiedLinkError; if not
        // ready yet, the bootstrap completion applies the surface instead.
        if (ready.get()) runCatching { NativeLibrary.surfaceChanged(surface) }
        maybeRun()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (ready.get()) runCatching { NativeLibrary.surfaceChanged(holder.surface) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (ready.get()) runCatching { NativeLibrary.surfaceDestroyed() }
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
        // Reload global config from the .ini file so any modifications made by
        // applyBootSettings (renderer backend, resolution scale, vsync, audio volume)
        // take effect before launching the emulator. Without this the in-memory
        // settings from initializeGlobalConfig() are used instead.
        runCatching { NativeConfig.reloadGlobalConfig() }
            .onFailure { Log.e(tag, "reloadGlobalConfig failed", it) }
        // Dismiss the loading overlay once the core reports it is running.
        mainHandler.post(pollRunning)
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
                // Mirror upstream EmulationActivity: a detected physical controller
                // must be auto-mapped to Player 1 (port 0), otherwise the native
                // GamePad device exists but the emulated controller never reads
                // from it and the gamepad stays dead while the overlay still works.
                // Also handles the virtual-gamepad-only case (no physical controller
                // present) by falling back to the on-screen overlay, which upstream
                // skips. Without this, buttons do nothing until the first input
                // event "wakes" the player connection.
                autoMapFirstController()
                Log.i(tag, "starting emulation for $romPath")
                // Use Cubeb (1) audio backend instead of Oboe (4).
                // Oboe/OpenSLES produces no audio output in the :switch process,
                // while Cubeb works correctly on all tested devices.
                NativeConfig.setInt("output_engine", 1)
                NativeConfig.saveGlobalConfig()
                // frontendInitiated=true matches the upstream EmulationActivity call, ensuring
                // the audio system path is correctly initialized for the multi-process setup.
                NativeLibrary.run(romPath, 0, true)
            } catch (t: Throwable) {
                Log.e(tag, "run failed", t)
            } finally {
                runOnUiThread { finish() }
            }
        }, "WnCitronRun").start()
    }

    /**
     * Binds the emulated Player 1 to the first available input device so the gamepad is live
     * immediately. Mirrors EmulationActivity.onCreate's auto-config, but also covers the
     * virtual-gamepad-only case (no physical controller): if no port-0 device exists we fall
     * back to the on-screen overlay, which upstream skips (it only auto-maps when a physical
     * controller is present). Without this, buttons do nothing until the first input event
     * "wakes" the player connection.
     */
    private fun autoMapFirstController() {
        // Mirror upstream EmulationActivity: use native-side registeredControllers
        // ParamPackages (from getInputDevices()) which contain all the fields the
        // native input driver needs (engine, guid, port, display, etc.). Manually
        // constructing a partial ParamPackage misses critical keys like the axis
        // declarations and button bitmasks, causing GetButtonMappingForDevice to
        // return an empty map and the gamepad to be completely dead.
        val physical = InputHandler.androidControllers.values.toList()
        if (physical.isEmpty()) return
        // Don't clobber a controller the user already set up in the settings UI.
        val alreadyBound = runCatching {
            NativeInput.getButtonParam(0, NativeButton.A).has("engine")
        }.getOrDefault(false)
        if (alreadyBound) {
            Log.i(tag, "Player 1 already has a mapping; skipping auto-map")
        } else {
            runCatching {
                physical.forEach { dev ->
                    val port = dev.getPort()
                    // Find the native-side ParamPackage for this port (populated by
                    // getInputDevices() during updateControllerData).
                    val params = InputHandler.registeredControllers.firstOrNull {
                        it.get("port", -1) == port
                    }
                    if (params != null) {
                        NativeInput.updateMappingsWithDefault(
                            port, params,
                            params.get("display", dev.getName())
                        )
                    }
                }
                // connectControllers(n) connects players 0..n (mirrors upstream).
                val maxPort = physical.maxOf { it.getPort() }
                NativeInput.connectControllers(maxPort)
                NativeConfig.saveGlobalConfig()
                Log.i(tag, "auto-mapped ${physical.size} physical controller(s) ports=${physical.map { it.getPort() }}")
            }.onFailure { Log.e(tag, "auto-map first controller failed", it) }
        }
    }

    /**
     * Attaches the host Compose overlay and the on-screen gamepad exactly once, after the
     * core reports it is running. Deferring this from onCreate keeps them from floating
     * above the loading screen. The gamepad is added ABOVE the host overlay so it receives
     * touch events (otherwise the host Compose overlay intercepts every touch).
     */
    private fun attachGameOverlays() {
        if (!overlaysAttached.compareAndSet(false, true)) return
        window.decorView.post {
            CitronHost.attachOverlay?.invoke(this)
            inputOverlay = InputOverlay(this)
            window.addContentView(
                inputOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /**
     * Inflates the loading overlay (R.layout.wn_citron_loading) shown while the game
     * installs/boots: an outlined MaterialCardView centered on a dark backdrop, with the
     * cover art on the left and (title + "Loading" + indeterminate LinearProgressIndicator)
     * on the right — mirroring the emulator's fragment_emulation.xml loading_indicator.
     *
     * The card is inflated with a Material3 ContextThemeWrapper because WnCitronActivity
     * uses a framework fullscreen theme that lacks the Material attributes the widgets need.
     */
    private fun buildLoadingOverlay(gameName: String?, coverPath: String?): View {
        val themed = android.view.ContextThemeWrapper(this, R.style.Theme_WnCitronLoading)
        val view = android.view.LayoutInflater.from(themed).inflate(R.layout.wn_citron_loading, null)

        view.findViewById<TextView>(R.id.wn_loading_title)?.text =
            gameName?.takeIf { it.isNotBlank() } ?: getString(R.string.wn_loading_title_fallback)

        val cover = view.findViewById<ImageView>(R.id.wn_loading_image)
        val bmp = runCatching {
            coverPath?.takeIf { it.isNotBlank() }?.let { android.graphics.BitmapFactory.decodeFile(it) }
        }.getOrNull()
        if (bmp != null) {
            cover?.setImageBitmap(bmp)
        } else {
            cover?.visibility = View.GONE
        }
        return view
    }

    override fun onPause() {
        super.onPause()
        runCatching { InputHandler.releaseAllInputs() }
        if (started.get() && !menuShown) runCatching { NativeLibrary.pauseEmulation() }
    }

    override fun onResume() {
        super.onResume()
        if (started.get() && !menuShown) runCatching { NativeLibrary.unpauseEmulation() }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollRunning)
        runCatching { InputHandler.releaseAllInputs() }
        runCatching { NativeLibrary.stopEmulation() }
        super.onDestroy()
        // Tear down the process so the next launch starts from a clean state.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}