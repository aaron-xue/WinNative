package org.godotengine.godot;

import android.util.Log;
import com.godot.game.GodotApp;

/**
 * Host activity for Brotato launched from the WinNative hub.
 *
 * - Back / edge-swipe is ignored (onBackPressed no-op + enableOnBackInvokedCallback=false),
 *   so the game isn't dismissed by a gesture.
 * - The in-game "quit" runs through WinNativeGodot.forceQuit() -> activity.finish().
 * - FullScreenGodotApp.onDestroy() unconditionally calls ProcessPhoenix.forceQuit(this),
 *   killing the whole host process. We skip that by invoking the base FragmentActivity
 *   onDestroy directly (same cleanup, without the kill). Java has no legal
 *   "skip-parent-method-body" syntax for a normal subclass, hence the reflection.
 */
public class WinNativeGodotApp extends GodotApp {
    private static final String TAG = "WinNativeGodotApp";

    @Override
    protected Godot initGodotInstance() {
        return new WinNativeGodot();
    }

    @Override
    public void onBackPressed() {
        Log.v(TAG, "Back/edge-swipe ignored; keeping the game alive.");
    }

    @Override
    public void onDestroy() {
        try {
            java.lang.reflect.Method m =
                    androidx.fragment.app.FragmentActivity.class.getDeclaredMethod("onDestroy");
            m.setAccessible(true);
            m.invoke(this);
            return;
        } catch (Exception e) {
            Log.w(TAG, "Reflection onDestroy failed; falling back to super (may force-quit).", e);
        }
        super.onDestroy();
    }
}
