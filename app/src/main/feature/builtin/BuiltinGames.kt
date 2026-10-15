package com.winlator.cmod.feature.builtin

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.Locale
import com.winlator.cmod.app.shell.LIBRARY_NAME_SANITIZE_REGEX
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.AppType

/**
 * Built-in games bundled into the app (not installed from a store).
 * Currently just Brotato, a Godot-exported Android game launched as a plain Activity.
 *
 * Play-time tracking mirrors the store games: stats live in the shared
 * `playtime_stats` SharedPreferences under the same key scheme used by
 * [com.winlator.cmod.app.shell.UnifiedActivityGameDialogs] and [com.winlator.cmod.app.shell.LibraryDefaultLayout]
 * (i.e. `${name sanitized}_play_count` / `_last_played` / `_playtime`).
 */
object BuiltinGames {
    const val BROTATO_ID = 1_900_000_001
    const val BROTATO_ACTIVITY = "org.godotengine.godot.WinNativeGodotApp"
    const val BROTATO_NAME = "Brotato"

    private const val PLAYTIME_PREFS = "playtime_stats"
    private const val SESSION_START_KEY = "__brotato_session_start"

    /**
     * Broadcast sent by the ":brotato" process when its activity is destroyed, so the library
     * process (which never sees that activity's lifecycle now that it runs separately) can
     * finalize the play session. Must match the literal in WinNativeGodotApp.
     */
    const val SESSION_END_ACTION = "com.winlator.cmod.builtin.BROTATO_SESSION_END"

    /**
     * Display name for Brotato in the current locale: Chinese shows "土豆兄弟",
     * every other language shows "Brotato". [BROTATO_NAME] stays the stable
     * internal identifier (used for playtime keys) so stats survive locale changes.
     */
    fun brotatoDisplayName(): String =
        if (Locale.getDefault().language.startsWith("zh", ignoreCase = true)) "土豆兄弟" else BROTATO_NAME

    /** The library entry for Brotato, injected into the merged library list. */
    val brotato: SteamApp
        get() = SteamApp(id = BROTATO_ID, name = brotatoDisplayName(), type = AppType.game)

    fun isBrotato(appId: Int): Boolean = appId == BROTATO_ID

    /** True for bundled built-in games (e.g. Brotato) that aren't from a store. */
    fun isBuiltin(appId: Int): Boolean = appId == BROTATO_ID

    /** Playtime key, derived identically to the rest of the library UI. */
    fun playtimeKey(): String = BROTATO_NAME.replace(LIBRARY_NAME_SANITIZE_REGEX, "")

    /** Launch the bundled Godot activity. */
    fun launch(context: Context) {
        runCatching {
            val intent = Intent().setClassName(context, BROTATO_ACTIVITY)
            // Launch in the same task as the caller (the library) so finishing the game
            // returns to the library with a smooth in-app pop instead of a cross-task
            // switch. NEW_TASK is only required when starting from a non-Activity context.
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Launch and record play stats: increment play count + last-played immediately
     * (matching XServerDisplayActivity.incrementPlayCount), and mark the session start
     * so elapsed time can be accumulated when the game exits.
     */
    fun launchWithTracking(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PLAYTIME_PREFS, Context.MODE_PRIVATE)
            val key = playtimeKey()
            val now = System.currentTimeMillis()
            prefs.edit().apply {
                putInt("${key}_play_count", prefs.getInt("${key}_play_count", 0) + 1)
                putLong("${key}_last_played", now)
                putLong(SESSION_START_KEY, now)
                apply()
            }
            launch(context)
        }
    }

    /**
     * Finalize an active play session: add the elapsed duration to the cumulative
     * playtime. No-op if no session is active (e.g. app restarted mid-session).
     */
    fun finalizeSession(context: Context) {
        val prefs = context.getSharedPreferences(PLAYTIME_PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong(SESSION_START_KEY, 0L)
        if (start <= 0L) return
        val elapsed = System.currentTimeMillis() - start
        val key = playtimeKey()
        val total = prefs.getLong("${key}_playtime", 0L) + elapsed.coerceAtLeast(0L)
        prefs.edit().apply {
            putLong("${key}_playtime", total)
            remove(SESSION_START_KEY)
            apply()
        }
    }

    /**
     * Register the play-session finalizer. Because Brotato runs in the separate ":brotato"
     * process, the library process can't observe its activity lifecycle directly, so we listen
     * for the [SESSION_END_ACTION] broadcast it sends on destroy and finalize from here. A
     * same-process lifecycle callback is kept as a harmless fallback. Call once from the
     * app/activity entry point.
     */
    private val registeredApps = mutableSetOf<Application>()

    fun initTracking(application: Application) {
        if (!registeredApps.add(application)) return

        // Brotato now runs in the separate ":brotato" process, so the library process can't
        // observe its activity lifecycle directly. Listen for the explicit session-end broadcast
        // it sends on destroy and finalize playtime from here.
        val sessionEndReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == SESSION_END_ACTION) {
                        finalizeSession(application.applicationContext)
                    }
                }
            }
        ContextCompat.registerReceiver(
            application,
            sessionEndReceiver,
            IntentFilter(SESSION_END_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Keep the lifecycle callback as a same-process fallback (harmless when Brotato is in
        // its own process; idempotent with the broadcast path).
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity.javaClass.name == BROTATO_ACTIVITY) {
                        finalizeSession(activity.applicationContext)
                    }
                }
            },
        )
    }

    /**
     * Copy the bundled cover art (assets/builtin) into a real file path so the library
     * artwork maps (which expect file paths) can reference them.
     * Returns (heroPath, shortcutPath) or null on failure.
     */
    fun artPaths(context: Context): Pair<String, String>? =
        runCatching {
            val dir = java.io.File(context.filesDir, "builtin_art").also { it.mkdirs() }
            val hero = java.io.File(dir, "brotato_hero.png")
            val shortcut = java.io.File(dir, "brotato_shortcut.png")
            if (!hero.exists()) {
                context.assets.open("builtin/cover.png").use { it.copyTo(hero.outputStream()) }
            }
            if (!shortcut.exists()) {
                context.assets.open("builtin/shortcut.jpeg").use { it.copyTo(shortcut.outputStream()) }
            }
            hero.absolutePath to shortcut.absolutePath
        }.getOrNull()
}
