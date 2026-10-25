package org.citron.citron_emu.wn

import android.content.Context
import android.util.Log
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.utils.NativeConfig
import java.io.File

// One-time native bootstrap for the :switch process. Replaces Citron's own
// CitronApplication.onCreate(). The JNI surface (libcitron-android.so + its 7
// FFmpeg and 4 adrenotools-hook dependencies) is NOT bundled into the APK; it
// ships inside the Retro-Consoles bundle under `data/citron/jniLibs/` and is
// extracted on install to `<filesDir>/retro/bundle/data/citron/jniLibs/`. We load
// the 12 .so directly from that extracted path (mirroring the ARMSX2 host, which
// does `System.load(bundled.getAbsolutePath())` for its PS2 core) instead of
// relying on `applicationInfo.nativeLibraryDir`.
object CitronBootstrap {
    private const val TAG = "WnCitron"
    private const val USER_DIR_NAME = "citron"

    // Full native set shipped inside the Retro-Consoles bundle under
    // data/citron/jniLibs. Loaded in dependency-topological order: FFmpeg leaves
    // first, then libcitron-android, then the GPU-driver hook libs. Loading each
    // leaf explicitly guarantees its DT_NEEDED entries resolve by soname even
    // though the extracted directory is not part of the default linker namespace.
    private val NATIVE_LIBS = listOf(
        "avutil",
        "swresample",
        "swscale",
        "avcodec",
        "avformat",
        "avfilter",
        "avdevice",
        "citron-android",
        "file_redirect_hook",
        "gsl_alloc_hook",
        "hook_impl",
        "main_hook"
    )

    // Locates the directory holding the extracted Switch native libs inside the
    // Retro-Consoles bundle (see RetroBundle.root() -> <filesDir>/retro/bundle).
    // The expected layout is data/citron/jniLibs, but we also accept a couple of
    // variants and fall back to a bounded search so the loader survives small
    // path differences in the bundle.
    private fun findNativeLibDir(context: Context): File? {
        val root = File(context.filesDir, "retro/bundle")
        val candidates = listOf(
            File(root, "data/citron/jniLibs"),
            File(root, "citron/jniLibs"),
            File(root, "data/citron/lib"),
        )
        for (c in candidates) {
            if (File(c, "libcitron-android.so").exists()) return c
        }
        if (root.isDirectory) {
            root.walkTopDown().maxDepth(5)
                .firstOrNull { it.name == "libcitron-android.so" }
                ?.parentFile
                ?.let { return it }
        }
        return null
    }

    @Volatile
    private var initialized = false

    fun ensure(context: Context) {
        val appCtx = context.applicationContext
        NativeLibrary.appContext = appCtx
        if (!initialized) {
            // Returns true once the core lib is resident; on failure we stay
            // uninitialized so a later call (after the bundle is installed) retries.
            initialized = loadNativeLibraries(appCtx)
        }
        if (!initialized) {
            Log.e(
                TAG,
                "native libs not loaded — install the Retro-Consoles bundle " +
                    "(data/citron/jniLibs) before launching Switch titles",
            )
            return
        }

        val userDir = File(appCtx.getExternalFilesDir(null), USER_DIR_NAME)
        if (!userDir.exists() && !userDir.mkdirs()) {
            Log.w(TAG, "cannot create user dir $userDir")
        }
        for (sub in listOf("keys", "nand", "config", "load", "dump", "log", "crash_dumps")) {
            File(userDir, sub).mkdirs()
        }

        // 1. point the core at its writable data root
        NativeLibrary.setAppDirectory(userDir.absolutePath)
        Log.i(TAG, "setAppDirectory -> $userDir")
        // 2. load global config (creates default config.ini if absent)
        NativeConfig.initializeGlobalConfig()
        Log.i(TAG, "initializeGlobalConfig done")
        // 2b. apply host-configured emulated region/language (chosen in the Switch
        //     settings screen, stored in the "CITRON" prefs). When unset, the default is
        //     derived from the Android system locale (SwitchLocaleDefaults). Set on the
        //     in-memory config so the SET HLE services boot with them, and persist.
        runCatching {
            val switchPrefs = appCtx.getSharedPreferences("CITRON", Context.MODE_PRIVATE)
            NativeConfig.setInt("region_index", switchPrefs.getInt("wn.switch.region_index", SwitchLocaleDefaults.defaultRegion()))
            NativeConfig.setInt("language_index", switchPrefs.getInt("wn.switch.language_index", SwitchLocaleDefaults.defaultLanguage()))
            NativeConfig.saveGlobalConfig()
            Log.i(TAG, "region=$switchPrefs.getInt(\"wn.switch.region_index\", -1) " +
                "lang=$switchPrefs.getInt(\"wn.switch.language_index\", -1)")
        }.onFailure { Log.w(TAG, "failed to apply region/language", it) }
        // 3. set log_filter BEFORE initializeSystem, because Common::Log::Initialize()
        //    (called inside initializeSystem) reads Settings::values.log_filter once
        //    and locks it forever — changing it afterwards has no effect.
        NativeConfig.setString("log_filter", "*:Info")
        NativeConfig.saveGlobalConfig()
        // 4. load prod.keys / title.keys (no-op if the user has not supplied them)
        NativeLibrary.reloadKeys()
        Log.i(TAG, "reloadKeys done – calling initializeSystem(false)...")
        // 5. bring up the HLE system / services
        NativeLibrary.initializeSystem(false)
        Log.i(TAG, "initializeSystem(false) returned")
        // 6. GPU driver. Pass the native library dir so adrenotools can find its
        //    hook .so files. A custom (Turnip) driver is selected in the host
        //    settings UI and stored as resolved paths in the "CITRON" prefs; nulls
        //    => use the system Vulkan driver.
        val nativeLibDir = appCtx.applicationInfo.nativeLibraryDir
        val driverPrefs = appCtx.getSharedPreferences("CITRON", Context.MODE_PRIVATE)
        val driverDir = driverPrefs.getString("wn.switch.driverDir", "") ?: ""
        val driverName = driverPrefs.getString("wn.switch.driverName", "") ?: ""
        val driverRedirect = driverPrefs.getString("wn.switch.driverRedirect", "") ?: ""
        if (driverDir.isNotEmpty() && driverName.isNotEmpty()) {
            NativeLibrary.initializeGpuDriver(nativeLibDir, driverDir, driverName, driverRedirect)
        } else {
            NativeLibrary.initializeGpuDriver(nativeLibDir, null, null, null)
        }
    }

    @Volatile
    private var metadataNativeLoaded = false

    /**
     * Prepares the native core in the HOST app process so read-only metadata APIs such
     * as org.citron.citron_emu.utils.GameMetadata.getTitle/getIcon can be called —
     * used to scrape a Switch NSP's name + cover art when adding it to the library.
     *
     * GameMetadata internally does EmulationSession::GetInstance().System()..., which
     * dereferences the emulated Core::System; that singleton only exists AFTER
     * NativeLibrary.initializeSystem(). Skipping it makes getTitle/getIcon segfault
     * (SIGSEGV at 0x0), which runCatching cannot catch. So we mirror ensure()'s init
     * (setAppDirectory -> global config -> keys -> initializeSystem) but intentionally
     * SKIP initializeGpuDriver (no surface here, and not needed to read control data).
     * Returns true if the core lib is resident and the system is initialized.
     */
    fun ensureNativeForMetadata(context: Context): Boolean {
        if (metadataNativeLoaded) return true
        val appCtx = context.applicationContext
        if (!loadNativeLibraries(appCtx)) {
            Log.e(
                TAG,
                "ensureNativeForMetadata: core native libs not loaded — install the " +
                    "Retro-Consoles bundle (data/citron/jniLibs)",
            )
            return false
        }
        val ok = runCatching {
            NativeLibrary.appContext = appCtx
            val userDir = File(appCtx.getExternalFilesDir(null), USER_DIR_NAME).apply { mkdirs() }
            NativeLibrary.setAppDirectory(userDir.absolutePath)
            NativeConfig.initializeGlobalConfig()
            NativeLibrary.reloadKeys()
            // Required: brings up EmulationSession's Core::System so GameMetadata's
            // loader calls have a valid System() to read the control NCA from. Skipping
            // this is what made selecting an NSP crash with SIGSEGV at 0x0.
            NativeLibrary.initializeSystem(false)
        }.onFailure {
            Log.e(TAG, "ensureNativeForMetadata: native init failed", it)
        }.isSuccess
        if (!ok) return false
        metadataNativeLoaded = true
        return true
    }

    /**
     * Loads the 12 native libs directly from the Retro-Consoles bundle's extracted
     * `data/citron/jniLibs` directory. Falls back to `applicationInfo.nativeLibraryDir`
     * for builds that still bundle the libs. Returns true if the core lib is loaded.
     */
    private fun loadNativeLibraries(context: Context): Boolean {
        val dir = findNativeLibDir(context) ?: File(context.applicationInfo.nativeLibraryDir)
        Log.i(TAG, "loading native libs from ${dir.absolutePath}")
        var coreLoaded = false
        for (name in NATIVE_LIBS) {
            val file = File(dir, "lib$name.so")
            if (!file.exists()) {
                Log.w(TAG, "native lib missing: ${file.absolutePath}")
                continue
            }
            runCatching {
                // Ensure the file is readable + executable; the app's filesDir can be
                // mounted without the execute bit, which breaks System.load().
                file.setReadable(true)
                file.setExecutable(true)
                System.load(file.absolutePath)
                if (name == "citron-android") {
                    coreLoaded = true
                    NativeInput.loadSelf(context)
                }
                Log.i(TAG, "loaded native lib ${file.absolutePath}")
            }.onFailure { e ->
                Log.e(TAG, "failed to load native lib ${file.absolutePath}", e)
            }
        }
        return coreLoaded
    }
}