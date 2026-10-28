package com.winlator.cmod.feature.retro

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

// Per-game in-game save data manager for the Citron (Switch) core.
//
// Citron stores every game's save under
//   <extFiles>/citron/.../save/0000000000000000/00000000000000000000000000000000/<TITLE_ID>/
// where <TITLE_ID> is the 16-char uppercase hex title id. There is no native
// API in this prebuilt libcitron-android.so to enumerate saves, so we walk the
// filesystem directly. Saves are plain files, so backup/restore/delete are
// pure copy operations on external storage (accessible from the main process).
//
// Backups are ZIP archives written to WinNative/saves/switch/ on the external storage root
// (WinNative/saves/switch/<titleId>_<timestamp>.zip) so they survive app
// uninstall/reinstall. Each zip bundles the save files plus a wn_game.json
// carrying the game name, and a shared names.json (titleId -> name map) is kept
// alongside so the save <-> game-name correspondence is preserved across
// reinstalls (the titleId is intrinsic to the game and never changes).
object CitronSaveManager {
    private val TITLE_ID_RE = Regex("^[0-9A-Fa-f]{16}$")
    private val USER_MARKER = "00000000000000000000000000000000"
    private val SPACE_MARKER = "0000000000000000"

    // Metadata entry stored inside every backup zip.
    private const val META_ENTRY = "wn_game.json"

    data class CitronSaveEntry(
        val titleId: String,
        val folder: File,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    private fun citronBase(context: Context): File =
        File(context.getExternalFilesDir(null), "citron")

    // Backups live in WinNative/saves/switch/ at the external storage root — a "switch"
    // subfolder of the shared WinNative/saves tree (which also holds container save
    // exports) — so they are easy to find and survive uninstall/reinstall. Writing there
    // needs All-files access on Android 11+ (guarded by the caller).
    private fun backupRoot(context: Context): File {
        val externalRoot = android.os.Environment.getExternalStorageDirectory()
        return File(externalRoot, "WinNative/saves/switch")
    }

    private fun namesFile(context: Context): File = File(backupRoot(context), "names.json")

    // Every directory that holds per-game save folders. There can be several save
    // spaces / user ids under the NAND, and different titles may live under different
    // ones (e.g. a game played under a non-default profile). Collecting only the first
    // match made some games' saves invisible, so we aggregate all of them.
    private fun findContainers(context: Context): List<File> {
        val base = citronBase(context)
        val out = LinkedHashSet<File>()
        listOf(
            File(base, "nand/user/save/$SPACE_MARKER/$USER_MARKER"),
            File(base, "nand/user/user/save/$SPACE_MARKER/$USER_MARKER"),
            File(base, "user/save/$SPACE_MARKER/$USER_MARKER"),
            File(base, "save/$SPACE_MARKER/$USER_MARKER"),
        ).forEach { if (it.isDirectory) out.add(it) }
        // Walk for any .../save/<16-hex space id>/<user id> dir anywhere under the citron
        // tree. The user id is 32 hex chars on real profiles (16 in some cases), so only the
        // space id is pinned to 16 hex — requiring BOTH to be 16 hex is what hid every
        // non-default profile's saves.
        fun walk(dir: File, depth: Int) {
            if (depth > 10 || !dir.isDirectory) return
            if (TITLE_ID_RE.matches(dir.parentFile?.name ?: "") &&
                dir.parentFile?.parentFile?.name == "save"
            ) {
                out.add(dir)
            }
            dir.listFiles()?.forEach { child -> if (child.isDirectory) walk(child, depth + 1) }
        }
        walk(base, 0)
        return out.toList()
    }

    fun listSaves(context: Context): List<CitronSaveEntry> {
        // Aggregate per-title save folders across ALL containers. The same title can have a
        // save under several user ids (e.g. an empty default-profile one plus the real one),
        // so keep the folder with the most content (tie-break: most recently modified).
        val byId = HashMap<String, File>()
        val containers = findContainers(context)
        containers.forEach { container ->
            container.listFiles()?.forEach { f ->
                if (!f.isDirectory || !TITLE_ID_RE.matches(f.name)) return@forEach
                val key = f.name.uppercase(Locale.ROOT)
                val prev = byId[key]
                if (prev == null ||
                    childCount(f) > childCount(prev) ||
                    (childCount(f) == childCount(prev) && f.lastModified() > prev.lastModified())
                ) {
                    byId[key] = f
                }
            }
        }
        // Fallback: any 16-hex dir that looks like a save folder (has user/meta).
        if (byId.isEmpty()) {
            scanGameSaves(citronBase(context)).forEach {
                byId[it.name.uppercase(Locale.ROOT)] = it
            }
        }
        Log.i("CitronSaveManager", "listSaves: ${containers.size} container(s), titles=${byId.keys}")
        dumpTitleIdDirs(context)
        return byId.values.map { f ->
            CitronSaveEntry(
                titleId = f.name.uppercase(Locale.ROOT),
                folder = f,
                sizeBytes = dirSize(f),
                lastModified = f.lastModified(),
            )
        }.sortedBy { it.titleId }
    }

    private fun childCount(dir: File): Int =
        runCatching { dir.listFiles()?.size ?: 0 }.getOrDefault(0)

    // Diagnostic: log every 16-hex directory anywhere under the citron data root (with its
    // relative path and child count). Installed titles, saves, temp/cache areas all use a
    // 16-hex title id, so this pinpoints where a missing game's data actually lives.
    private fun dumpTitleIdDirs(context: Context) {
        val base = citronBase(context)
        val sb = StringBuilder()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || !dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                if (!f.isDirectory) return@forEach
                if (TITLE_ID_RE.matches(f.name)) {
                    val count = runCatching { f.listFiles()?.size ?: 0 }.getOrDefault(0)
                    sb.append(f.absolutePath.removePrefix(base.absolutePath))
                        .append("  [$count entries]\n")
                }
                walk(f, depth + 1)
            }
        }
        walk(base, 0)
        Log.i("CitronSaveManager", "titleId dirs under citron:\n${if (sb.isEmpty()) "(none)" else sb}")
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

    /**
     * Backs up [titleId]'s live save into WinNative/saves/switch/<titleId>_<timestamp>.zip.
     * The zip bundles the save files plus a wn_game.json carrying [gameName] so the game name
     * travels with the backup. Returns the zip file, or null when there is no such save or the
     * public directory could not be created (e.g. All-files access not granted).
     */
    fun backup(context: Context, titleId: String, gameName: String?): File? {
        val entry = listSaves(context).firstOrNull { it.titleId.equals(titleId, true) } ?: return null
        val id = entry.titleId
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val root = backupRoot(context)
        if (!root.mkdirs() && !root.isDirectory) return null
        // Flat layout: the titleId is embedded in the zip file name (no per-title folder).
        val dest = File(root, "${id}_$stamp.zip")
        val name = gameName?.takeIf { it.isNotBlank() } ?: gameNameFor(context, id)
        return if (zipBackup(entry.folder, dest, id, name)) dest else null
    }

    /** Backup zips for a title, newest first (flat dir; file name starts with "<titleId>_"). */
    fun listBackups(context: Context, titleId: String): List<File> {
        val prefix = titleId.uppercase(Locale.ROOT) + "_"
        return backupRoot(context).listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("zip", true) && it.name.startsWith(prefix) }
            .sortedByDescending { it.name }
    }

    /** Reads the game name stored inside a backup zip (its wn_game.json), if present. */
    fun readBackupName(backup: File): String? = runCatching {
        var name: String? = null
        ZipInputStream(backup.inputStream().buffered()).use { zis ->
            var ze = zis.nextEntry
            while (ze != null && name == null) {
                if (ze.name == META_ENTRY) {
                    name = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        .optString("name").takeIf { it.isNotBlank() }
                }
                zis.closeEntry()
                ze = zis.nextEntry
            }
        }
        name
    }.getOrNull()

    fun latestBackupName(context: Context, titleId: String): String? =
        listBackups(context, titleId).firstOrNull()?.let { readBackupName(it) }

    /**
     * titleId -> name map persisted in Download (written by the :switch process on boot via
     * GameMetadata). Survives reinstall so live saves can still be labelled by game name.
     */
    fun readNames(context: Context): Map<String, String> = runCatching {
        val f = namesFile(context)
        if (!f.isFile) {
            emptyMap()
        } else {
            val obj = JSONObject(f.readText())
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }
    }.getOrElse { emptyMap() }

    /**
     * Best-effort human name for a save: persisted names.json, else the newest backup's
     * metadata, else the raw titleId.
     */
    fun gameNameFor(context: Context, titleId: String): String {
        val key = titleId.uppercase(Locale.ROOT)
        readNames(context)[key]?.let { return it }
        latestBackupName(context, titleId)?.let { return it }
        return titleId
    }

    /**
     * Extracts a backup zip's save files back into the live NAND save folder. If the game has
     * no save folder yet (e.g. the app was reinstalled, the NAND was wiped and the user id
     * changed), one is created under the current save container — the zip holds plain files
     * keyed only by titleId, so it is portable across reinstalls. For the running game to
     * actually pick the save up, launch that game once first so its folder is created under
     * the current profile.
     */
    fun restore(context: Context, titleId: String, backup: File): Boolean {
        val target = listSaves(context).firstOrNull { it.titleId.equals(titleId, true) }?.folder
            ?: createSaveFolder(context, titleId)
            ?: return false
        target.deleteRecursively()
        target.mkdirs()
        return runCatching {
            ZipInputStream(backup.inputStream().buffered()).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    if (!ze.isDirectory && ze.name != META_ENTRY) {
                        val out = File(target, ze.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    ze = zis.nextEntry
                }
            }
            true
        }.getOrDefault(false)
    }

    // Destination folder for a title that has no save yet: the first existing save container
    // (so it lands under the CURRENT profile's user id), else the canonical default path.
    private fun createSaveFolder(context: Context, titleId: String): File? {
        val container = findContainers(context).firstOrNull()
            ?: File(citronBase(context), "nand/user/save/$SPACE_MARKER/$USER_MARKER")
        val dest = File(container, titleId.uppercase(Locale.ROOT))
        return if (dest.mkdirs() || dest.isDirectory) dest else null
    }

    fun delete(titleId: String, context: Context): Boolean {
        val entry = listSaves(context).firstOrNull { it.titleId.equals(titleId, true) } ?: return false
        return entry.folder.deleteRecursively()
    }

    private fun zipBackup(srcDir: File, zipFile: File, titleId: String, name: String): Boolean =
        runCatching {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                val meta = JSONObject().put("titleId", titleId).put("name", name).toString()
                zos.putNextEntry(ZipEntry(META_ENTRY))
                zos.write(meta.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    // Zip entry names always use '/'; normalize just in case.
                    zos.putNextEntry(ZipEntry(f.relativeTo(srcDir).path.replace('\\', '/')))
                    f.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        }.getOrDefault(false)

    /**
     * Exports all Switch saves as a single combined ZIP to WinNative/saves/switch/.
     * Each game's save is stored in its own folder named by titleId, with a wn_game.json
     * per game carrying the human-readable name. Returns the zip file, or null if there
     * are no saves to export or the output directory cannot be created.
     */
    fun exportAllSaves(context: Context): File? {
        val saves = listSaves(context)
        if (saves.isEmpty()) return null
        val root = backupRoot(context)
        if (!root.mkdirs() && !root.isDirectory) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val dest = File(root, "switch_saves_$stamp.zip")
        return if (runCatching {
            ZipOutputStream(dest.outputStream().buffered()).use { zos ->
                saves.forEach { save ->
                    val name = gameNameFor(context, save.titleId)
                    val prefix = "${save.titleId}/"
                    val meta = JSONObject().put("titleId", save.titleId).put("name", name).toString()
                    zos.putNextEntry(ZipEntry(prefix + META_ENTRY))
                    zos.write(meta.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    save.folder.walkTopDown().filter { it.isFile }.forEach { f ->
                        zos.putNextEntry(ZipEntry(prefix + f.relativeTo(save.folder).path.replace('\\', '/')))
                        f.inputStream().buffered().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        }.getOrDefault(false)) dest else null
    }

    /**
     * Imports all saves from a combined ZIP archive produced by [exportAllSaves].
     * Each top-level folder whose name matches a 16-char hex titleId is restored as
     * that game's save under the current NAND profile. Returns the count of
     * successfully imported games.
     */
    fun importAllSaves(context: Context, zipFile: File): Int {
        val byTitle = mutableMapOf<String, MutableList<Pair<String, ByteArray>>>()
        runCatching {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    if (!ze.isDirectory) {
                        val parts = ze.name.split('/', limit = 2)
                        if (parts.size >= 2 && TITLE_ID_RE.matches(parts[0])) {
                            val tid = parts[0].uppercase(Locale.ROOT)
                            val rel = parts[1]
                            if (rel != META_ENTRY) {
                                byTitle.getOrPut(tid) { mutableListOf() }.add(rel to zis.readBytes())
                            }
                        }
                    }
                    zis.closeEntry()
                    ze = zis.nextEntry
                }
            }
        }
        var count = 0
        byTitle.forEach { (titleId, files) ->
            val target = createSaveFolder(context, titleId) ?: return@forEach
            runCatching {
                files.forEach { (path, data) ->
                    val out = File(target, path)
                    out.parentFile?.mkdirs()
                    out.writeBytes(data)
                }
                count++
            }
        }
        return count
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble() / 1024.0
        var i = 0
        while (size >= 1024 && i < units.lastIndex) {
            size /= 1024
            i++
        }
        return "%.1f %s".format(Locale.ROOT, size, units[i])
    }

    fun formatDate(ms: Long): String {
        if (ms <= 0L) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
    }
}