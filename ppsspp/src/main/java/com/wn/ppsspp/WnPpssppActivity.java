package com.wn.ppsspp;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import org.ppsspp.ppsspp.PpssppActivity;
import org.ppsspp.ppsspp.PpssppBootstrap;
import org.ppsspp.ppsspp.PspHost;

/**
 * Thin bootstrap Activity that inherits from the stock PPSSPP PpssppActivity.
 * This is the entry-point registered in the AndroidManifest.
 *
 * Before any PpssppActivity code touches native state we must call
 * PpssppBootstrap.ensure() to load libppsspp_jni.so from the Retro-Consoles
 * extracted bundle path.
 *
 * It must live in its own isolated :psp process so there is zero chance of
 * Vulkan / GLSurfaceView state contamination with the main app or other emulators.
 */
public class WnPpssppActivity extends PpssppActivity {
    private static final String TAG = "WnPpssppActivity";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);

        // Bootstrap must complete before PpssppActivity.onCreate() so that
        // NativeApp.* calls don't hit UnsatisfiedLinkError.
        if (!PpssppBootstrap.INSTANCE.ensure(this)) {
            Log.e(TAG, "PPSSPP native lib failed to load from Retro bundle.");
            finish();
            return;
        }

        // The stock PpssppActivity guards several native entry points behind its
        // static `libraryLoaded` flag. Flip it on now that the .so is actually loaded.
        PpssppActivity.CheckABIAndLoadLibrary();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Install frame generation from the launch intent (written by
        // RetroFrameGen.writeInto) before any surface is created so the first
        // surfaceChanged can wrap the output surface.
        com.winlator.cmod.shared.framegen.FrameGen.installFromIntent(this, getIntent());

        super.onCreate(savedInstanceState);

        com.winlator.cmod.shared.framegen.FrameGen.applyDisplayMode(this);

        // The host app (PspGameOverlay.install()) registered the HUD overlay in this
        // process; attach it above PPSSPP's surface now that the content view exists.
        kotlin.jvm.functions.Function1<android.app.Activity, kotlin.Unit> attach =
            PspHost.INSTANCE.getAttachOverlay();
        if (attach != null) {
            attach.invoke(this);
        }
    }
}