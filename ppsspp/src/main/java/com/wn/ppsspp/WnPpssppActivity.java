package com.wn.ppsspp;

import android.content.Context;
import android.util.Log;
import org.ppsspp.ppsspp.PpssppActivity;
import org.ppsspp.ppsspp.PpssppBootstrap;

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
}