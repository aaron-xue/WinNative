package org.citron.citron_emu.wn

import android.util.Log
import org.citron.citron_emu.NativeLibrary

// When a Switch game is launched, the core only loads the single ROM passed to
// NativeLibrary.run(). Any DLC / update NSPs sitting next to it in the same
// folder are NOT registered in the NAND, so games that query Add-On Content at
// startup abort with the "OpenDataStorageByDataId AOC miss" panic.
//
// The base game NSP is installed into the NAND via NativeLibrary.installFileToNand
// so the title is registered. Sibling DLC / update NSPs are registered with the
// VFS as external content so the game mounts them as Add-On Content at boot.
//
// Only NSP / NSZ files are supported.
object CitronInstaller {
    private const val TAG = "WnCitronInstall"

    // Native up-call signature is Function2.invoke(Object,Object); keep it as a
    // plain (Double, Double) -> Boolean lambda.
    private val progressCallback: (max: Double, progress: Double) -> Boolean = { _, _ -> true }

    data class Result(
        val found: Int,
        val installed: Int,
        val failed: Int,
        val skipped: Int,
    )

    // Core InstallResult enum: Success = 0, Overwrite = 1, Failure = 2.
    // Only 0 and 1 mean the content ended up registered in the NAND.
    private fun isSuccess(rc: Int): Boolean = rc == 0 || rc == 1

    fun install(targets: List<String>): Result {
        Log.i(TAG, "install request: ${targets.size} target(s)")
        var installed = 0
        var failed = 0
        for (t in targets) {
            Log.i(TAG, "install -> $t")
            val rc =
                runCatching {
                    NativeLibrary.installFileToNand(t, progressCallback)
                }.fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        Log.e(TAG, "install threw for $t", e)
                        -1
                    },
                )
            if (isSuccess(rc)) {
                installed++
                Log.i(TAG, "installed OK (rc=$rc): $t")
            } else {
                failed++
                Log.e(TAG, "install returned rc=$rc: $t")
            }
        }
        Log.i(TAG, "install done: installed=$installed failed=$failed")
        return Result(targets.size, installed, failed, 0)
    }

    // Mirrors upstream Citron's ExternalContentHelper.registerSiblings: register
    // sibling DLC / update files with the VFS so the game can mount them at boot.
    fun registerExternal(targets: List<String>) {
        runCatching { NativeLibrary.clearExternalContentFiles() }
        for (t in targets) {
            Log.i(TAG, "register external -> $t")
            runCatching { NativeLibrary.addExternalContentFile(t) }
                .onFailure { Log.e(TAG, "addExternalContentFile failed for $t", it) }
        }
    }
}
