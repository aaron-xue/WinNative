package org.citron.citron_emu.wn

import android.content.Context
import android.util.Log
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.utils.NativeConfig
import java.io.File

// One-time native bootstrap for the :switch process. Replaces Citron's own
// CitronApplication.onCreate(). The JNI surface (libcitron-android.so + its 7
// FFmpeg and 4 adrenotools-hook dependencies) must already be present in
// applicationInfo.nativeLibraryDir (drop them under
// citron/src/main/jniLibs/arm64-v8a/ for the APK build, or via the
// Retro-Consoles bundle for release).
object CitronBootstrap {
    private const val TAG = "WnCitron"
    private const val USER_DIR_NAME = "citron"

    @Volatile
    private var initialized = false

    fun ensure(context: Context) {
        val appCtx = context.applicationContext
        NativeLibrary.appContext = appCtx
        if (!initialized) {
            System.loadLibrary("citron-android")
            initialized = true
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
        // 2. load global config (creates default config.ini if absent)
        NativeConfig.initializeGlobalConfig()
        // 3. load prod.keys / title.keys (no-op if the user has not supplied them)
        NativeLibrary.reloadKeys()
        // 4. bring up the HLE system / services
        NativeLibrary.initializeSystem(false)
        // 5. GPU driver. Pass the native library dir so adrenotools can find its
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
}
