package org.citron.citron_emu.wn

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.PixelFormat
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.winlator.cmod.shared.framegen.FrameGen
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.R
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.features.input.model.NativeButton
import org.citron.citron_emu.overlay.InputOverlay
import org.citron.citron_emu.utils.GameMetadata
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
    private var surfaceW = 0
    private var surfaceH = 0
    private var inputOverlay: InputOverlay? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val ready = java.util.concurrent.atomic.AtomicBoolean(false)
    private var menuShown = false
    private var lifecyclePaused = false

    private var pauseDimOverlay: View? = null

    // Loading overlay (game title + spinner + "Loading…") shown over the SurfaceView while
    // the NSP installs and the core boots; dismissed once NativeLibrary.isRunning() is true
    // (mirrors citron's EmulationFragment loading indicator).
    private var loadingOverlay: View? = null
    private var perfHud: SwitchPerfHudController? = null
    private var switchMenu: SwitchMenuView? = null
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

        // Frame generation (LSFG) — read per-game settings from the launch Intent so
        // surfaceCreated/surfaceChanged can wrap the output Surface with interpolation.
        FrameGen.installFromIntent(this, intent)

        // Let content run edge-to-edge so the side menu (and the game surface) sit flush against
        // the screen edges; we then manually avoid the camera/notch via the menu's safe inset.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Tapping the system / gamepad BACK now toggles the in-emulator side menu (mirrors PS2's
        // RetroActivity) instead of closing the game. This covers gesture navigation; KEYCODE_BACK
        // from a hardware/3-button back is also consumed in dispatchKeyEvent below.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (lifecyclePaused) {
                        resumeFromLifecyclePause()
                    } else if (menuShown) {
                        closeMenu()
                    } else {
                        openMenu()
                    }
                }
            },
        )

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

        val pauseOverlay = object : SurfaceView(this), SurfaceHolder.Callback {
            private var surfaceReady = false

            init {
                holder.addCallback(this)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setZOrderOnTop(true)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        if (menuShown) closeMenu()
                        if (lifecyclePaused) resumeFromLifecyclePause()
                    }
                    true
                }
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                redraw()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = redraw()

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }

            fun redraw() {
                if (!surfaceReady) return
                val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
                try {
                    canvas.drawColor(0x80000000.toInt())

                    val playIcon = resources.getDrawable(R.drawable.ic_play, theme)
                    val circleBg = resources.getDrawable(R.drawable.circle_white, theme)
                    val cx = width / 2f
                    val cy = height / 2f
                    val iconSize = 64f * resources.displayMetrics.density
                    val half = iconSize / 2f

                    circleBg.setBounds(
                        (cx - half).toInt(), (cy - half).toInt(),
                        (cx + half).toInt(), (cy + half).toInt()
                    )
                    circleBg.draw(canvas)

                    val pad = 14f * resources.displayMetrics.density
                    playIcon.setTint(android.graphics.Color.BLACK)
                    playIcon.setBounds(
                        (cx - half + pad).toInt(), (cy - half + pad).toInt(),
                        (cx + half - pad).toInt(), (cy + half - pad).toInt()
                    )
                    playIcon.draw(canvas)
                } finally {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }
        (window.decorView as ViewGroup).addView(
            pauseOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        pauseDimOverlay = pauseOverlay

        // In-game performance HUD (FPS / GPU / CPU / RAM / battery). Driven entirely by
        // device-level metrics + NativeLibrary.getPerfStats(); the toggle lives in the Switch
        // settings (writes the "retro_hud" extra into the shortcut's .desktop file via saveData()).
        //
        // We read that flag straight from the .desktop file (path supplied as EXTRA_SHORTCUT_PATH)
        // rather than trusting the intent extra or a cross-process "CITRON" pref, both of which
        // proved unreliable. The other two sources are kept as belt-and-suspenders fallbacks.
        val hudFromShortcut = isHudEnabledFromShortcut(intent)
        val hudFromExtra = intent.getBooleanExtra(CitronConsts.EXTRA_HUD, false)
        val hudFromCitron = runCatching {
            getSharedPreferences("CITRON", android.content.Context.MODE_PRIVATE)
                .getBoolean("wn.switch.hud", false)
        }.getOrDefault(false)
        val hudEnabled = hudFromShortcut || hudFromExtra || hudFromCitron
        Log.i(
            tag,
            "HUD enable: total=$hudEnabled | shortcutFile=$hudFromShortcut extra=$hudFromExtra " +
                "citronPref=$hudFromCitron | shortcutPath=" +
                intent.getStringExtra(CitronConsts.EXTRA_SHORTCUT_PATH),
        )
        if (hudEnabled) {
            perfHud = SwitchPerfHudController(this, root).also { it.start() }
        }

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
                // loading; apply it now that the native side is live. applySurface()
                // wraps it through FrameGen if the user enabled frame generation.
                if (surface != null) applySurface()
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
        // System / gamepad BACK toggles the in-emulator side menu (mirrors PS2 RetroActivity)
        // instead of finishing the activity. Consumed on ACTION_UP so a single press toggles once.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (lifecyclePaused) {
                    resumeFromLifecyclePause()
                } else if (menuShown) {
                    closeMenu()
                } else {
                    openMenu()
                }
            }
            return true
        }
        if (menuShown) {
            // While the menu is open, route navigation keys (dpad/stick) into it.
            return CitronHost.menuKeyHandler?.invoke(event) ?: false
        }
        // BUTTON_MODE (the controller "guide" button) opens the in-emulator menu.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.action == KeyEvent.ACTION_DOWN) {
            if (lifecyclePaused) resumeFromLifecyclePause() else openMenu()
            return true
        }
        // Route physical gamepad keys into the emulated controller.
        if (InputHandler.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Open the in-emulator side menu (same RetroDrawerMenu PS2 uses) and pause emulation.
    private fun openMenu() {
        if (menuShown || lifecyclePaused) return
        if (!runCatching { NativeLibrary.isRunning() }.getOrDefault(false)) return
        menuShown = true
        pauseDimOverlay?.visibility = View.VISIBLE
        CitronHost.openMenu?.invoke()
        runCatching { NativeLibrary.pauseEmulation() }
    }

    private fun closeMenu() {
        if (!menuShown) return
        menuShown = false
        if (!lifecyclePaused) {
            pauseDimOverlay?.visibility = View.GONE
            runCatching { NativeLibrary.unpauseEmulation() }
        }
        CitronHost.closeMenu?.invoke()
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
            // Feed analog sticks into the open menu for navigation.
            CitronHost.menuAxisHandler?.invoke(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
            )
            return true
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
        val frame = holder.surfaceFrame
        surfaceW = frame.width()
        surfaceH = frame.height()
        // Only touch the native surface once CitronBootstrap.ensure() has loaded the .so
        // (ready==true). surfaceCreated fires on the SurfaceView's first preDraw, which
        // can beat the bootstrap thread and used to throw UnsatisfiedLinkError; if not
        // ready yet, the bootstrap completion applies the surface instead.
        if (ready.get()) applySurface()
        maybeRun()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        surfaceW = width
        surfaceH = height
        if (ready.get()) applySurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (ready.get()) runCatching { NativeLibrary.surfaceDestroyed() }
        surface = null
        FrameGen.release()
    }

    private fun applySurface() {
        val raw = surface ?: return
        val wrapped = FrameGen.wrap(raw, surfaceW, surfaceH)
        val final = wrapped ?: raw
        Log.i(
            tag,
            "applySurface ${surfaceW}x${surfaceH} requested=${FrameGen.requested} mult=${FrameGen.multiplier} " +
                "target=${FrameGen.targetRate} active=${FrameGen.active} generating=${FrameGen.generating}",
        )
        runCatching { NativeLibrary.surfaceChanged(final) }
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
                // Persist this game's titleId -> name so the save manager can label saves
                // by game name, and keep that mapping across app reinstalls.
                persistGameName(romPath)
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
     * Resolves the running game's title + titleId via the native GameMetadata (works here
     * because CitronBootstrap.ensure() already initialized the core) and merges
     * { titleId : name } into WinNative/saves/switch/names.json. The main-process save
     * manager reads that file to label saves by game name; since the file lives in public
     * storage and the titleId is intrinsic to the game, the mapping survives reinstalls.
     */
    private fun persistGameName(romPath: String) {
        runCatching {
            val name = GameMetadata.getTitle(romPath)
            if (name.isBlank()) return
            val programId = GameMetadata.getProgramId(romPath).toULongOrNull() ?: return
            val titleId = programId.toString(16).uppercase().padStart(16, '0')
            val dir = File(
                android.os.Environment.getExternalStorageDirectory(),
                "WinNative/saves/switch",
            ).apply { mkdirs() }
            val namesFile = File(dir, "names.json")
            val obj = if (namesFile.isFile) {
                runCatching { org.json.JSONObject(namesFile.readText()) }.getOrNull() ?: org.json.JSONObject()
            } else {
                org.json.JSONObject()
            }
            if (obj.optString(titleId) != name) {
                obj.put(titleId, name)
                namesFile.writeText(obj.toString())
                Log.i(tag, "persisted game name for $titleId: $name")
            }
        }.onFailure { Log.w(tag, "persist game name failed", it) }
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
            // Switch-specific SurfaceView side menu. The Compose RetroDrawerMenu does not
            // reliably appear over the emulated game's SurfaceView, so we render our own menu
            // here (same reliable-visibility approach as SwitchPerfHud). This overrides the
            // (inert) Compose menu slots wired by CitronGameOverlay.
            val menu = SwitchMenuView(this, this)
            (window.decorView as ViewGroup).addView(
                menu,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            switchMenu = menu
            menu.setGameTitle(intent.getStringExtra(CitronConsts.EXTRA_GAME_NAME))
            menu.onRequestClose = { closeMenu() }
            menu.onToggleInputOverlay = {
                val ov = inputOverlay
                if (ov != null) {
                    ov.visibility = if (ov.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    Toast.makeText(
                        this,
                        if (ov.visibility == View.VISIBLE) "已显示触屏按键" else "已隐藏触屏按键",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            CitronHost.openMenu = { menu.open() }
            CitronHost.closeMenu = { menu.close() }
            CitronHost.isMenuOpen = { menu.visible }
            CitronHost.menuKeyHandler = { ev -> menu.handleKey(ev.keyCode, ev.action) }
            CitronHost.menuAxisHandler = { x, y -> menu.handleAxis(x, y) }
        }
    }

    /**
     * Reads the "retro_hud" flag straight from the shortcut's .desktop file. This is the
     * authoritative, process-safe source: the host app writes it there (via Shortcut.saveData())
     * when the user toggles the Switch performance-HUD switch, and the :switch process reads the
     * same file — no intent extra or cross-process SharedPreferences involved.
     */
    private fun isHudEnabledFromShortcut(intent: android.content.Intent): Boolean {
        val path = intent.getStringExtra(CitronConsts.EXTRA_SHORTCUT_PATH) ?: return false
        val file = java.io.File(path)
        if (!file.isFile) return false
        return try {
            var inExtraData = false
            for (raw in file.readLines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[")) {
                    inExtraData = line.equals("[Extra Data]", ignoreCase = true)
                    continue
                }
                if (inExtraData && line.startsWith("retro_hud=")) {
                    return line.substringAfter("=").trim() == "1"
                }
            }
            false
        } catch (_: Exception) {
            false
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
        if (started.get()) {
            runCatching { NativeLibrary.pauseEmulation() }
            if (menuShown) {
                CitronHost.closeMenu?.invoke()
                menuShown = false
            }
            lifecyclePaused = true
            pauseDimOverlay?.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun resumeFromLifecyclePause() {
        if (!lifecyclePaused) return
        lifecyclePaused = false
        pauseDimOverlay?.visibility = View.GONE
        runCatching { NativeLibrary.unpauseEmulation() }
    }

    override fun onDestroy() {
        perfHud?.stop()
        perfHud = null
        mainHandler.removeCallbacks(pollRunning)
        runCatching { InputHandler.releaseAllInputs() }
        runCatching { NativeLibrary.stopEmulation() }
        super.onDestroy()
        // Tear down the process so the next launch starts from a clean state.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}