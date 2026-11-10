package org.ppsspp.ppsspp

import android.app.Activity

// Bridge object the host app (com.winlator.cmod) populates inside the :psp
// process, and that WnPpssppActivity consumes. Mirrors org.citron.citron_emu.wn.CitronHost
// so the external-frontend integrations stay structurally identical.
//
// Lifecycle: PluviaApp.onCreate() runs once per process, so in the :psp process
// PspGameOverlay.install() sets `attachOverlay` (which builds the HUD overlay).
// WnPpssppActivity then invokes that slot after its content view is inflated.
object PspHost {
    // Host calls this to attach its HUD overlay (FrameRating) above the PPSSPP surface.
    @Volatile
    var attachOverlay: ((Activity) -> Unit)? = null

    fun enabled(): Boolean = attachOverlay != null
}