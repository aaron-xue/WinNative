package com.winlator.cmod.feature.retro

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Per-game in-game save data manager for the Citron (Switch) core.
//
// Citron stores every game's save under
//   <extFiles>/citron/.../save/0000000000000000/00000000000000000000000000000000/<TITLE_ID>/
// where <TITLE_ID> is the 16-char uppercase hex title id. There is no native
// API in this prebuilt libcitron-android.so to enumerate saves, so we walk the
// filesystem directly. Saves are plain files, so backup/restore/delete are
// pure copy operations on external storage (accessible from the main process).
object CitronSaveManager {
    private val TITLE_ID_RE = Regex("^[0-9A-Fa-f]{16}$")
    private val USER_MARKER = "00000000000000000000000000000000"
    private val SPACE_MARKER = "0000000000000000"

    data class CitronSaveEntry(
        val titleId: String,
        val folder: File,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    private fun citronBase(context: Context): File =
        File(context.getExternalFilesDir(null), "citron")

    // Locate the container directory that holds all per-game save folders.
    private fun findContainer(context: Context): File? {
        val base = citronBase(context)
        val candidates = listOf(
            File(base, "nand/user/save/$SPACE_MARKER/$USER_MARKER"),
            File(base, "nand/user/user/save/$SPACE_MARKER/$USER_MARKER"),
            File(base, "user/save/$SPACE_MARKER/$USER_MARKER"),
            File(base, "save/$SPACE_MARKER/$USER_MARKER"),
        )
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        return searchMarker(base, USER_MARKER, 0, 8)
    }

    private fun searchMarker(dir: File, marker: String, depth: Int, maxDepth: Int): File? {
        if (depth > maxDepth || !dir.isDirectory) return null
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                if (child.name == marker) return child
                if (depth < maxDepth) {
                    searchMarker(child, marker, depth + 1, maxDepth)?.let { return it }
                }
            }
        }
        return null
    }

    fun listSaves(context: Context): List<CitronSaveEntry> {
        val container = findContainer(context)
        val dirs = if (container != null) {
            container.listFiles().orEmpty().filter {
                it.isDirectory && TITLE_ID_RE.matches(it.name)
            }
        } else {
            scanGameSaves(citronBase(context))
        }
        return dirs.map {
            CitronSaveEntry(
                titleId = it.name.uppercase(Locale.ROOT),
                folder = it,
                sizeBytes = dirSize(it),
                lastModified = it.lastModified(),
            )
        }.sortedBy { it.titleId }
    }

    // Fallback scan: any 16-hex directory that contains a "user" or "meta" subdir.
    private fun scanGameSaves(root: File): List<File> {
        val out = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || !dir.isDirectory) return
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    if (TITLE_ID_RE.matches(child.name) &&
                        (File(child, "user").isDirectory || File(child, "meta").isDirectory)
                    ) {
                        out.add(child)
                    } else {
                        walk(child, depth + 1)
                    }
                }
            }
        }
        walk(root, 0)
        return out
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        runCatching {
            dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        return total
    }

    fun backup(context: Context, titleId: String): File? {
        val entry = listSaves(context).firstOrNull { it.titleId.equals(titleId, true) } ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val dest = File(citronBase(context), "save_backups/$titleId/$stamp")
        dest.deleteRecursively()
        copyDir(entry.folder, dest)
        return dest
    }

    fun listBackups(context: Context, titleId: String): List<File> {
        val root = File(citronBase(context), "save_backups/$titleId")
        return root.listFiles().orEmpty().filter { it.isDirectory }
            .sortedByDescending { it.name }
    }

    fun restore(context: Context, titleId: String, backup: File): Boolean {
        val entry = listSaves(context).firstOrNull { it.titleId.equals(titleId, true) } ?: return false
        entry.folder.deleteRecursively()
        copyDir(backup, entry.folder)
        return true
    }

    fun delete(titleId: String, context: Context): Boolean {
        val entry = listSaves(context).firstOrNull { it.titleId.equals(titleId, true) } ?: return false
        return entry.folder.deleteRecursively()
    }

    private fun copyDir(src: File, dst: File) {
        dst.mkdirs()
        src.listFiles()?.forEach { child ->
            val target = File(dst, child.name)
            if (child.isDirectory) copyDir(child, target)
            else runCatching { child.copyTo(target, overwrite = true) }
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble() / 1024.0
        var i = 0
        while (size >= 1024 && i < units.lastIndex) {
            size /= 1024.0
            i++
        }
        return "%.1f %s".format(Locale.ROOT, size, units[i])
    }

    fun formatDate(ms: Long): String {
        if (ms <= 0L) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
    }
}
