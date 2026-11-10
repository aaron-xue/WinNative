package org.ppsspp.ppsspp

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One-time native bootstrap for the :psp process. Replaces the APK-bundled jniLibs
 * approach. The full PPSSPP native runtime (libppsspp_jni.so) is NOT bundled into the APK;
 * it ships inside the Retro-Consoles bundle under `data/ppsspp/jniLibs/`
 * and is extracted on install to `<filesDir>/retro/bundle/data/ppsspp/jniLibs/`.
 * We load the .so file directly from that extracted path instead of relying on
 * `applicationInfo.nativeLibraryDir`.
 *
 * Unlike PS2, PPSSPP has no transitive .so dependencies, so this is a simple single-lib load.
 */
object PpssppBootstrap {
    private const val TAG = "PpssppBootstrap"

    private fun findNativeLibDir(context: Context): File? {
        val root = File(context.filesDir, "retro/bundle")
        val candidates = listOf(
            File(root, "data/ppsspp/jniLibs"),
            File(root, "ppsspp/jniLibs"),
        )
        for (c in candidates) {
            if (File(c, "libppsspp_jni.so").exists()) return c
        }
        if (root.isDirectory) {
            root.walkTopDown().maxDepth(5)
                .firstOrNull { it.name == "libppsspp_jni.so" }
                ?.parentFile
                ?.let { return it }
        }
        return null
    }

    /**
     * True when the Retro-Consoles bundle is installed AND its extracted jniLibs
     * actually contains the PPSSPP native runtime. Pure file check — nothing is loaded.
     */
    fun arePspLibsAvailable(context: Context): Boolean {
        val dir = findNativeLibDir(context) ?: return false
        return File(dir, "libppsspp_jni.so").exists()
    }

    @Volatile
    private var initialized = false

    /**
     * Ensures the PPSSPP native lib is loaded. Must be called before any NativeApp
     * method that touches native code, and before PpssppActivity's static initializer
     * tries to call System.loadLibrary.
     *
     * Returns true if libppsspp_jni.so is loaded and ready.
     */
    fun ensure(context: Context): Boolean {
        if (initialized) return true
        val appCtx = context.applicationContext

        val dir = findNativeLibDir(appCtx)
        if (dir == null) {
            Log.e(TAG, "native lib dir not found — install the Retro-Consoles bundle (data/ppsspp/jniLibs)")
            return false
        }

        Log.i(TAG, "loading native libs from ${dir.absolutePath}")

        val libFile = File(dir, "libppsspp_jni.so")
        if (!libFile.exists()) {
            Log.e(TAG, "libppsspp_jni.so missing: ${libFile.absolutePath}")
            return false
        }

        runCatching {
            libFile.setReadable(true)
            libFile.setExecutable(true)
            System.load(libFile.absolutePath)
            Log.i(TAG, "loaded ${libFile.absolutePath}")
            initialized = true
        }.onFailure { e ->
            Log.e(TAG, "failed to load ${libFile.absolutePath}", e)
        }

        return initialized
    }

    /**
     * Returns the extracted bundle assets dir for PPSSPP
     * (`data/ppsspp/assets` inside the Retro-Consoles bundle). This directory is passed
     * straight to NativeApp.init(); the patched native runtime registers a DirectoryReader
     * for it, so assets are served directly from disk (no zip/APK packaging).
     */
    fun findPspAssetsDir(context: Context): File? {
        val root = File(context.filesDir, "retro/bundle")
        val candidates = listOf(
            File(root, "data/ppsspp/assets"),
            File(root, "ppsspp/assets"),
        )
        for (c in candidates) {
            if (c.isDirectory) return c
        }
        if (root.isDirectory) {
            root.walkTopDown().maxDepth(5)
                .firstOrNull { it.name == "ppsspp" && it.isDirectory }
                ?.let { File(it, "assets") }
                ?.takeIf { it.isDirectory }
                ?.let { return it }
        }
        return null
    }
}