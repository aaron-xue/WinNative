package com.armsx2.wn

import android.content.Context
import android.util.Log
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File

/**
 * One-time native bootstrap for the :ps2 process. Replaces the APK-bundled jniLibs
 * approach. The full native set (libemucore_4k/16k + librashader_capi + shaderc +
 * SPIRV-Tools + ANGLE EGL/GLESv2) is NOT bundled into the APK; it ships inside the
 * Retro-Consoles bundle under `data/armsx2/jniLibs/` and is extracted on install to
 * `<filesDir>/retro/bundle/data/armsx2/jniLibs/`. We load the .so files directly
 * from that extracted path instead of relying on `applicationInfo.nativeLibraryDir`.
 *
 * The load order matters: shader/SPIRV dependencies must be loaded before
 * librashader_capi and emucore.
 */
object Armsx2Bootstrap {
    private const val TAG = "Armsx2Bootstrap"

    /**
     * Native libs shipped inside the Retro-Consoles bundle under data/armsx2/jniLibs.
     * Loaded in dependency-topological order:
     *   1. SPIRV-Tools-shared  (no deps outside this set)
     *   2. shaderc_shared      (depends on SPIRV-Tools-shared)
     *   3. librashader_capi    (depends on shaderc_shared + SPIRV-Tools-shared)
     *   4. EGL_angle           (ANGLE EGL)
     *   5. GLESv2_angle        (ANGLE GLESv2)
     *   6. emucore_4k / emucore_16k (depends on all of the above)
     */
    private val DEPENDENCY_LIBS = listOf(
        "SPIRV-Tools-shared",
        "shaderc_shared",
        "librashader_capi",
        "EGL_angle",
        "GLESv2_angle",
    )

    private fun findNativeLibDir(context: Context): File? {
        val root = File(context.filesDir, "retro/bundle")
        val candidates = listOf(
            File(root, "data/armsx2/jniLibs"),
            File(root, "armsx2/jniLibs"),
        )
        for (c in candidates) {
            if (File(c, "liblibrashader_capi.so").exists()) return c
        }
        if (root.isDirectory) {
            root.walkTopDown().maxDepth(5)
                .firstOrNull { it.name == "liblibrashader_capi.so" }
                ?.parentFile
                ?.let { return it }
        }
        return null
    }

    /**
     * True when the Retro-Consoles bundle is installed AND its extracted jniLibs
     * actually contains the PS2 native runtime. Pure file check — nothing is loaded.
     */
    fun arePs2LibsAvailable(context: Context): Boolean {
        val dir = findNativeLibDir(context) ?: return false
        val emucoreName = NativeApp.selectNativeLibraryNameStatic(context)
        return DEPENDENCY_LIBS.all { File(dir, "lib${it}.so").exists() } &&
                File(dir, "lib${emucoreName}.so").exists()
    }

    @Volatile
    private var initialized = false

    /**
     * Ensures all PS2 native libs are loaded. Must be called before any NativeApp
     * method that touches native code. Idempotent — skips if already initialized.
     *
     * Returns true if the emucore library is loaded and ready.
     */
    fun ensure(context: Context): Boolean {
        if (initialized) return true
        val appCtx = context.applicationContext

        val dir = findNativeLibDir(appCtx)
        if (dir == null) {
            Log.e(TAG, "native lib dir not found — install the Retro-Consoles bundle (data/armsx2/jniLibs)")
            return false
        }

        Log.i(TAG, "loading native libs from ${dir.absolutePath}")

        // 1. Load dependency libs first
        for (name in DEPENDENCY_LIBS) {
            val file = File(dir, "lib${name}.so")
            if (!file.exists()) {
                Log.w(TAG, "native lib missing: ${file.absolutePath}")
                continue
            }
            runCatching {
                file.setReadable(true)
                file.setExecutable(true)
                System.load(file.absolutePath)
                Log.i(TAG, "loaded ${file.absolutePath}")
            }.onFailure { e ->
                Log.e(TAG, "failed to load ${file.absolutePath}", e)
            }
        }

        // 2. Load emucore (4k or 16k depending on page size)
        val emucoreName = NativeApp.selectNativeLibraryNameStatic(appCtx)
        val emucoreFile = File(dir, "lib${emucoreName}.so")
        if (emucoreFile.exists()) {
            runCatching {
                emucoreFile.setReadable(true)
                emucoreFile.setExecutable(true)
                System.load(emucoreFile.absolutePath)
                NativeApp.markNativeBinaryAvailable()
                Log.i(TAG, "loaded ${emucoreFile.absolutePath}")
                initialized = true
            }.onFailure { e ->
                NativeApp.markNativeBinaryAbsent()
                Log.e(TAG, "failed to load ${emucoreFile.absolutePath}", e)
            }
        } else {
            NativeApp.markNativeBinaryAbsent()
            Log.e(TAG, "emucore lib missing: ${emucoreFile.absolutePath}")
        }

        return initialized
    }
}