package com.winlator.cmod.feature.shortcuts

import android.util.Log
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
    )

    class ImportException(override val message: String) : Exception(message)

    suspend fun resolve(
        gameFile: File,
        artworkDir: File,
    ): Parsed = withContext(Dispatchers.IO) {
        require(gameFile.isFile) { ImportException("文件不存在: ${gameFile.absolutePath}") }
        if (gameFile.extension.lowercase() != "game") {
            throw ImportException("不支持的文件格式: ${gameFile.name}")
        }

        val importDir = File(gameFile.parentFile, ".wn-import-${UUID.randomUUID()}")
        try {
            importDir.mkdirs()
            runCatching {
                ArchiveExtractor.extract(gameFile, importDir, {}, { true })
            }.onFailure { e ->
                Log.e(TAG, "extract failed", e)
                throw ImportException("文件解包失败: ${e.message ?: "unknown"}")
            }

            val manifest = File(importDir, "manifests.json")
            if (!manifest.isFile) throw ImportException("安装包缺少 manifests.json")

            val json = runCatching { JSONObject(manifest.readText()) }.getOrElse {
                throw ImportException("manifests.json 解析失败: ${it.message}")
            }

            val name = json.optString("name").takeIf { it.isNotBlank() }
                ?: throw ImportException("manifest.name 不能为空")
            val exeRaw = json.optString("exe").takeIf { it.isNotBlank() }
                ?: throw ImportException("manifest.exe 不能为空")

            val baseDir = gameFile.parentFile!!
            val exeFile = resolvePath(baseDir, exeRaw)
            if (!exeFile.isFile) {
                throw ImportException("manifest.exe 路径不存在: $exeRaw → ${exeFile.absolutePath}")
            }

            val coverFile: File? = json.optString("cover").ifBlank { null }?.let { coverRaw ->
                resolvePath(importDir, coverRaw).takeIf { it.isFile }?.let { src ->
                    val destDir = File(artworkDir, UUID.randomUUID().toString()).apply { mkdirs() }
                    val dest = File(destDir, "cover${src.extension.let { if (it.isBlank()) ".png" else ".$it" }}")
                    runCatching { src.copyTo(dest, overwrite = true) }.getOrNull()
                }
            }

            Parsed(name = name.trim(), exeFile = exeFile, coverFile = coverFile)
        } finally {
            runCatching { importDir.deleteRecursively() }
        }
    }

    private fun resolvePath(root: File, raw: String): File =
        if (raw.startsWith("/")) File(raw) else File(root, raw)

    private const val TAG = "GamePackageImporter"
}