package com.winlator.cmod.feature.retro

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.winlator.cmod.runtime.display.ui.FrameRating
import com.winlator.cmod.shared.framegen.FrameGen
import org.ppsspp.ppsspp.NativeApp
import org.ppsspp.ppsspp.PpssppActivity
import org.ppsspp.ppsspp.PspHost

// Host-side overlay for the embedded PSP emulator. Minimal compared to the PS2/Switch
// overlays: it only mounts the performance HUD (FrameRating) above PPSSPP's surface.
// Frame generation itself is handled by FrameGen.wrap() in NativeSurfaceManager plus the
// FrameGen.installFromIntent() call in WnPpssppActivity.
//
// This runs in the :psp process (PluviaApp.onCreate() executes per-process).
object PspGameOverlay {
    const val EXTRA_PSP_HUD = "wn_psp_hud"

    // Poll interval for the native flip counter (queryConfig("flipCount")).
    private const val TICK_MS = 16L

    fun install() {
        PspHost.attachOverlay = { activity -> attach(activity) }
    }

    private fun attach(activity: Activity) {
        if (activity !is PpssppActivity) return
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (!activity.intent.getBooleanExtra(EXTRA_PSP_HUD, false)) return

        val rating = RetroHudSupport.createFrameRating(activity, "Vulkan")
        RetroHudSupport.applyStyle(
            rating,
            RetroHudSupport.loadGlobalHudStyle(activity),
            RetroHudSupport.loadGlobalHudElements(activity),
        )
        RetroHudSupport.attachFrameRating(root, rating)
        rating.visibility = View.VISIBLE
        rating.reset()
        RetroHudSupport.bindFrameGeneration(rating)

        // PPSSPP has no Java-visible frame callback, so poll the native flip counter
        // (queryConfig("flipCount"), exposed in app-android.cpp) and drive the HUD
        // source frame counter off its delta — same pattern as Ps2FrameSource.
        val handler = Handler(Looper.getMainLooper())
        var lastCount = -1
        val ticker =
            object : Runnable {
                override fun run() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    if (!rating.isAttachedToWindow) {
                        handler.postDelayed(this, TICK_MS)
                        return
                    }
                    val count =
                        runCatching { NativeApp.queryConfig("flipCount").toIntOrNull() }.getOrNull()
                    if (count != null) {
                        if (lastCount >= 0 && count >= lastCount) {
                            val delta = (count - lastCount).coerceAtMost(8)
                            FrameGen.noteSourceFrames(delta)
                            repeat(delta) { rating.recordGameFrame() }
                        }
                        lastCount = count
                    }
                    handler.postDelayed(this, TICK_MS)
                }
            }
        handler.post(ticker)
    }
}