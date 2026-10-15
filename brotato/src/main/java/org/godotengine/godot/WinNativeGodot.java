package org.godotengine.godot;

import android.util.Log;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.godotengine.godot.utils.PermissionsUtil;

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

    // RECORD_AUDIO is declared in the host app manifest for the Winlator
    // DirectAudio mic-capture feature, which Brotato (a self-contained native
    // Godot game) never uses. Godot's default requestPermissions() asks for EVERY
    // dangerous permission in the merged manifest, so launching Brotato would spam
    // the microphone prompt on every start. Exclude it here; the Winlator mic path
    // requests RECORD_AUDIO itself, dynamically, only when the user opts in.
    private static final Set<String> EXCLUDED_PERMISSIONS =
            Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(
                    "android.permission.RECORD_AUDIO")));

    @Override
    public void forceQuit() {
        Log.v(TAG, "forceQuit intercepted -> finishing activity instead of killing process.");
        android.app.Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    @Override
    public boolean requestPermissions() {
        return PermissionsUtil.requestManifestPermissions(getActivity(), EXCLUDED_PERMISSIONS);
    }

    @Override
    public boolean requestPermission(String str) {
        if ("RECORD_AUDIO".equals(str) || "android.permission.RECORD_AUDIO".equals(str)) {
            Log.v(TAG, "Skipping RECORD_AUDIO request; Brotato has no mic use.");
            return false;
        }
        return super.requestPermission(str);
    }
}
