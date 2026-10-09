package com.winlator.cmod.feature.shortcuts

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.winlator.cmod.R
import com.winlator.cmod.shared.io.ArchiveExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

object GamePackageImporter {

    data class Parsed(
        val name: String,
        val exeFile: File,
        val coverFile: File?,
        val shortcutFile: File?,
    )

    /** Import failure described by a string resource, so the UI layer can display it localised. */
    class ImportException(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList(),
        cause: Throwable? = null,
    ) : Exception(null, cause) {
        fun localized(context: Context): String = context.getString(resId, *args.toTypedArray())
    }

    suspend fun resolve(
        gameFile: File,
        artworkDir: File,
    ): Parsed = withContext(Dispatchers.IO) {
        if (!gameFile.isFile) {
            throw ImportException(
                R.string.library_games_game_package_error_file_missing,
                listOf(gameFile.absolutePath),
            )
        }
        if (gameFile.extension.lowercase() != "game") {
            throw ImportException(
                R.string.library_games_game_package_error_unsupported,
                listOf(gameFile.name),
            )
        }

        val importDir = File(gameFile.parentFile, ".wn-import-${UUID.randomUUID()}")
        try {
            importDir.mkdirs()
            runCatching {
                ArchiveExtractor.extract(gameFile, importDir, {}, { true })
            }.onFailure { e ->
                Log.e(TAG, "extract failed", e)
                throw ImportException(
                    R.string.library_games_game_package_error_extract,
                    listOf(e.message ?: e.javaClass.simpleName),
                    e,
                )
            }

            val manifest = File(importDir, "manifests.json")
            if (!manifest.isFile) {
                throw ImportException(R.string.library_games_game_package_error_manifest_missing)
            }

            val json = runCatching { JSONObject(manifest.readText()) }.getOrElse { e ->
                throw ImportException(
                    R.string.library_games_game_package_error_manifest_parse,
                    listOf(e.message ?: e.javaClass.simpleName),
                    e,
                )
            }

            val name = json.optString("name").takeIf { it.isNotBlank() }
                ?: throw ImportException(R.string.library_games_game_package_error_name_empty)
            val exeRaw = json.optString("exe").takeIf { it.isNotBlank() }
                ?: throw ImportException(R.string.library_games_game_package_error_exe_empty)

            val baseDir = gameFile.parentFile!!
            val exeFile = resolvePath(baseDir, exeRaw)
            if (!exeFile.isFile) {
                throw ImportException(
                    R.string.library_games_game_package_error_exe_missing,
                    listOf(exeRaw, exeFile.absolutePath),
                )
            }

            val coverFile: File? = extractArt(json, "cover", importDir, artworkDir, "cover")
            val shortcutFile: File? = extractArt(json, "shortcut", importDir, artworkDir, "shortcut")

            Parsed(name = name.trim(), exeFile = exeFile, coverFile = coverFile, shortcutFile = shortcutFile)
        } finally {
            runCatching { importDir.deleteRecursively() }
        }
    }

    /** Extract an artwork file referenced by [key] in the manifest into a stable artwork dir. */
    private fun extractArt(
        json: JSONObject,
        key: String,
        importDir: File,
        artworkDir: File,
        baseName: String,
    ): File? =
        json.optString(key).ifBlank { null }?.let { raw ->
            resolvePath(importDir, raw).takeIf { it.isFile }?.let { src ->
                val destDir = File(artworkDir, UUID.randomUUID().toString()).apply { mkdirs() }
                val dest = File(destDir, "$baseName${src.extension.let { if (it.isBlank()) ".png" else ".$it" }}")
                runCatching { src.copyTo(dest, overwrite = true) }.getOrNull()
            }
        }

    private fun resolvePath(root: File, raw: String): File =
        if (raw.startsWith("/")) File(raw) else File(root, raw)

    private const val TAG = "GamePackageImporter"
}
