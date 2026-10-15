package org.godotengine.godot;

import android.content.Intent;
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

    /**
     * Broadcast fired (from the ":brotato" process) when the game activity is destroyed, so the
     * library process can finalize the play session. Kept as a literal (not a shared constant)
     * because this module must not depend on the :app module.
     */
    static final String SESSION_END_ACTION = "com.winlator.cmod.builtin.BROTATO_SESSION_END";

    @Override
    protected Godot initGodotInstance() {
        return new WinNativeGodot();
    }

    @Override
    public void onBackPressed() {
        Log.v(TAG, "Back/edge-swipe ignored; keeping the game alive.");
    }

    private void notifySessionEnd() {
        try {
            Intent intent = new Intent(SESSION_END_ACTION);
            intent.setPackage(getApplicationContext().getPackageName());
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast Brotato session end", e);
        }
    }

    @Override
    public void onDestroy() {
        // Tell the library process (running separately) to finalize playtime before this
        // process tears down.
        notifySessionEnd();
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
