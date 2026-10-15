package org.godotengine.godot;

import android.util.Log;

/**
 * Godot subclass used for Brotato. The in-game "quit" eventually calls forceQuit(); we
 * override it to finish the activity (returning to the WinNative hub) instead of letting
 * Godot's default forceQuit() call ProcessPhoenix and kill the whole host process.
 *
 * The remaining process-kill in FullScreenGodotApp.onDestroy() is neutralized in
 * WinNativeGodotApp.onDestroy() via reflection.
 */
public class WinNativeGodot extends Godot {
    private static final String TAG = "WinNativeGodot";

    @Override
    public void forceQuit() {
        Log.v(TAG, "forceQuit intercepted -> finishing activity instead of killing process.");
        android.app.Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }
}
