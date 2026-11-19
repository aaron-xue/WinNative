package com.winlator.cmod.shared.android;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Build;

/** Reports game lifecycle state to the system {@link GameManager} (API 33+) so the OS can
 *  schedule performance boosts while a game is loading or actively running. */
public final class GameStateReporter {

    private GameStateReporter() {}

    public static void reportLoading(Context context) {
        report(context, true, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE);
    }

    public static void reportGameplay(Context context) {
        report(context, false, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE);
    }

    public static void reportNone(Context context) {
        report(context, false, GameState.MODE_NONE);
    }

    private static void report(Context context, boolean isLoading, int mode) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            GameManager gameManager = context.getSystemService(GameManager.class);
            if (gameManager == null) return;
            // Some ROMs (notably domestic third-party builds) expose GameManager but never
            // act on game-state hints; skip the call when the platform doesn't support
            // game mode so we don't run it in vain.
            if (gameManager.getGameMode() == GameManager.GAME_MODE_UNSUPPORTED) return;
            gameManager.setGameState(new GameState(isLoading, mode));
        } catch (Throwable ignore) {}
    }
}