package com.winlator.cmod.feature.library

import android.content.Context
import android.net.Uri
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.display.lsfg.LosslessScaling
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object LosslessAutoImport {
    const val STEAM_APP_ID = 993090

    const val RESULT_READY = 0
    const val RESULT_IMPORTED = 1
    const val RESULT_UPDATED = 2
    const val RESULT_NOT_OWNED = 3
    const val RESULT_NOT_FOUND = 4
    const val RESULT_FAILED = 5

    private const val DLL_NAME = "Lossless.dll"
    private const val INSTALL_DIR_NAME = "Lossless Scaling"

    // Bundled with the APK so frame generation works on a fresh install with no Steam copy and no
    // user-provided Lossless.dll. Imported automatically when a container or game is created.
    private const val ASSET_DLL_PATH = "lsfg/Lossless.dll"

    // The native shader cache build is not reentrant; serialize it so concurrent callers (container
    // creation, game-settings open, launch) never translate the DLL simultaneously.
    private val importLock = Any()

    class Outcome(val result: Int, val sourceName: String)

    fun isOwned(): Boolean {
        val licensed = runCatching { SteamService.getPkgInfoOf(STEAM_APP_ID) != null }.getOrDefault(false)
        if (licensed) return true
        return runCatching { SteamService.getInstalledApp(STEAM_APP_ID) != null }.getOrDefault(false)
    }

    fun findDll(context: Context): File? {
        val candidates = LinkedHashSet<File>()
        for (dir in steamCandidateDirs()) {
            val dll = File(dir, DLL_NAME)
            if (dll.isFile && dll.canRead()) candidates += dll
        }
        runCatching { LosslessScaling.findInContainers(ContainerManager(context).containers) }
            .getOrDefault(emptyList())
            .forEach { candidates += it }

        return candidates.maxWithOrNull(
            compareBy<File> { LosslessScaling.variantRank(LosslessScaling.dllVariant(it)) }
                .thenBy { it.length() },
        )
    }

    fun sync(context: Context): Outcome {
        synchronized(importLock) {
            val dll = findDll(context)
            if (dll == null) {
                // No Steam or in-container copy: fall back to the DLL bundled in the APK assets.
                importFromAssets(context)?.let { return it }
                return Outcome(
                    if (LosslessScaling.isInstalled(context)) RESULT_READY else RESULT_NOT_FOUND,
                    "",
                )
            }

            val name = dll.parentFile?.name.orEmpty()
            val installed = LosslessScaling.isInstalled(context)
            if (installed && !LosslessScaling.isCacheStale(context, dll)) {
                return Outcome(RESULT_READY, name)
            }

            val status = LosslessScaling.installFrom(context, dll)
            if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, name)
            return Outcome(if (installed) RESULT_UPDATED else RESULT_IMPORTED, name)
        }
    }

    fun importFrom(context: Context, uri: Uri): Outcome {
        synchronized(importLock) {
            val status = LosslessScaling.installFrom(context, uri)
            if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, "")
            return Outcome(RESULT_IMPORTED, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
        }
    }

    fun importFrom(context: Context, dll: File): Outcome {
        synchronized(importLock) {
            val name = dll.parentFile?.name?.takeIf { it.isNotBlank() } ?: dll.name
            val status = LosslessScaling.installFrom(context, dll)
            if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, name)
            return Outcome(RESULT_IMPORTED, name)
        }
    }

    /**
     * Imports the Lossless.dll shipped inside the APK at [ASSET_DLL_PATH]. Returns null when the
     * asset is absent (e.g. a build that strips it), letting callers fall through to NOT_FOUND.
     * No-op and returns READY when shaders are already installed, so repeated calls are cheap.
     */
    fun importFromAssets(context: Context): Outcome? {
        return runCatching {
            val assetStream: InputStream = context.assets.open(ASSET_DLL_PATH)
            if (LosslessScaling.isInstalled(context)) return Outcome(RESULT_READY, "")
            val staged = File(context.cacheDir, "lsfg/Lossless.asset.staged")
            staged.parentFile?.mkdirs()
            assetStream.use { input ->
                FileOutputStream(staged).use { output -> input.copyTo(output) }
            }
            importFrom(context, staged)
        }.getOrNull()
    }

    private fun steamCandidateDirs(): List<File> {
        val dirs = LinkedHashSet<File>()

        runCatching { SteamService.getInstalledApp(STEAM_APP_ID)?.installPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs += File(it) }

        runCatching { SteamService.getAppDirPath(STEAM_APP_ID) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs += File(it) }

        runCatching { SteamService.allInstallPaths }
            .getOrDefault(emptyList())
            .forEach { base -> if (base.isNotBlank()) dirs += File(base, INSTALL_DIR_NAME) }

        runCatching { SteamService.defaultAppInstallPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs += File(it, INSTALL_DIR_NAME) }

        return dirs.toList()
    }
}
