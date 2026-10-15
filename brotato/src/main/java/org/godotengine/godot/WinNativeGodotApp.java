package org.godotengine.godot;

import android.util.Log;
import com.godot.game.GodotApp;

/**
 * Host activity for Brotato launched from the WinNative hub.
 *
 * Back / edge-swipe is intentionally ignored. Finishing this activity would run
 * Godot's onDestroy -> forceQuit() -> ProcessPhoenix.killProcess, killing the whole
 * host app. The game is exited from inside the game instead. (If you also want the
 * in-game "quit" to return to the hub rather than kill the process, override
 * Godot.forceQuit() to finish the activity instead of calling ProcessPhoenix.)
 */
public class WinNativeGodotApp extends GodotApp {
    private static final String TAG = "WinNativeGodotApp";

    @Override
    public void onBackPressed() {
        Log.v(TAG, "Back/edge-swipe ignored; keeping the game alive.");
    }
}
