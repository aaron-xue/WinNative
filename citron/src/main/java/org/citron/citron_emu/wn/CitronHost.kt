package org.citron.citron_emu.wn

import android.app.Activity
import android.view.KeyEvent

// Bridge object the host app (com.winlator.cmod) populates inside the :switch
// process, and that WnCitronActivity consumes. Mirrors com.armsx2.WinNativeHost
// so the two external-frontend integrations stay structurally identical.
//
// Lifecycle: PluviaApp.install() runs once per process, so in the :switch process
// CitronGameOverlay.install() sets `attachOverlay` (which builds the Compose
// overlay + fills the menu/input slots). WnCitronActivity then invokes those
// slots. The save/load/fast-forward slots are implemented here (in the :switch
// process) because they must reach libcitron-android.so.
object CitronHost {
    // Host calls this to attach its Compose overlay (input view + drawer menu).
    var attachOverlay: ((Activity) -> Unit)? = null

    // Host applies per-game / global boot settings before run() is called.
    var applyBootSettings: ((android.content.Context) -> Unit)? = null

    // Open/close the in-emulator menu drawer.
    var openMenu: (() -> Unit)? = null
    var closeMenu: (() -> Unit)? = null
    var isMenuOpen: (() -> Boolean)? = null

    // Open the native Switch settings dialog on top of the running emulator.
    var openSettings: ((Activity) -> Unit)? = null

    // Menu navigation, fed by the Activity's key/motion dispatch.
    var menuKeyHandler: ((KeyEvent) -> Boolean)? = null
    var menuAxisHandler: ((Float, Float) -> Boolean)? = null

    // Save-state slots — currently no native API in libcitron-android.so; the
    // Activity implements these as no-ops/toasts and they become real in P3.
    var saveState: ((Int) -> Unit)? = null
    var loadState: ((Int) -> Unit)? = null

    // Fast-forward / speed-limit toggle (wired to NativeConfig in P3).
    var setFastForward: ((Boolean) -> Unit)? = null

    // Emulation lifecycle callbacks invoked by NativeLibrary (called by C++).
    var onEmulationStarted: (() -> Unit)? = null
    var onEmulationStopped: ((Int) -> Unit)? = null
    var onProgramChanged: ((Int) -> Unit)? = null
    var onExitEmulationActivity: ((Int) -> Unit)? = null
    var onShaderCacheProgress: ((Int, Int, Int) -> Unit)? = null

    fun enabled(): Boolean = attachOverlay != null
}