package com.winlator.cmod.feature.retro

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.winlator.cmod.feature.retro.RetroShortcuts
import com.winlator.cmod.runtime.container.Shortcut
import org.citron.citron_emu.wn.CitronConsts
import java.io.File

// Launches a Nintendo Switch game in the embedded :switch process, mirroring
// DolphinEmbedLaunch for GameCube/Wii. The actual render/audio/input all happen
// in libcitron-android.so; this only builds the Intent and hands over the ROM.
object CitronEmbedLaunch {
    // Matches "[<16-hex>]" title ids in file names, identical to upstream Citron's
    // ExternalContentHelper so we add only the right game's DLC / update files.
    private val TITLE_ID = Regex("\\[([0-9A-Fa-f]{16})]")

    private fun titleIdOf(fileName: String): Long? =
        TITLE_ID.find(fileName)?.groupValues?.get(1)?.toULongOrNull(16)?.toLong()

    /** The application a title ID belongs to: an update is the base plus 0x800, DLC is one band up. */
    private fun applicationOf(id: Long): Long =
        if ((id and 0x1000L) != 0L) (id xor 0x1000L) and 0xFFFL.inv() else id and 0xFFFL.inv()

    private fun isAddon(id: Long): Boolean = (id and 0x1FFFL) != 0L

    fun isSupported(shortcut: Shortcut): Boolean =
        RetroShortcuts.systemForShortcut(shortcut)?.id == RetroSystems.SWITCH.id

    fun launch(context: Context, shortcut: Shortcut) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            Toast.makeText(
                context,
                "Nintendo Switch emulation requires Android 11 (API 30) or newer",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val rom = File(RetroShortcuts.romPath(shortcut))
        if (!rom.isFile) {
            Toast.makeText(
                context,
                context.getString(com.winlator.cmod.R.string.retro_rom_missing),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // Prefer a content:// uri so ROMs on external volumes / SAF are reachable
        // from the :switch process. libcitron-android.so accepts content:// in run().
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}${CitronConsts.FILE_PROVIDER_AUTHORITY_SUFFIX}",
                rom,
            )
        } catch (e: IllegalArgumentException) {
            android.net.Uri.fromFile(rom)
        }

        // Collect content:// URIs for the update / DLC files belonging to this game,
        // mirroring upstream Citron's ExternalContentHelper.registerSiblings. We match by
        // title id (same application, addon type) and walk the ROM's folder recursively
        // (updates/DLC are often in sub-directories), so unrelated files are ignored. The
        // :switch process hands these to the core before boot so the game can mount its
        // Add-On Content. A same-folder (depth 0) fallback also grabs NSP/XCI files that
        // carry no bracketed title id (e.g. scene-named "Brotato Update.nsp").
        val siblingUris = runCatching {
            val ownApp = titleIdOf(rom.name)?.let { applicationOf(it) }
            val auth = "${context.packageName}${CitronConsts.FILE_PROVIDER_AUTHORITY_SUFFIX}"
            val found = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            fun addUri(f: File) {
                val uri = try {
                    FileProvider.getUriForFile(context, auth, f).toString()
                } catch (e: IllegalArgumentException) {
                    return
                }
                if (seen.add(uri)) found.add(uri)
            }
            fun walk(dir: File?, depth: Int) {
                if (dir == null || !dir.isDirectory || depth > 2) return
                for (f in dir.listFiles().orEmpty()) {
                    if (f.isDirectory) {
                        walk(f, depth + 1)
                        continue
                    }
                    if (!f.isFile) continue
                    val ext = f.extension.lowercase()
                    if (ext != "nsp" && ext != "nsz" && ext != "xci" && ext != "nsx") continue
                    val id = titleIdOf(f.name)
                    if (id != null) {
                        if (!isAddon(id)) continue
                        if (ownApp != null && applicationOf(id) != ownApp) continue
                        addUri(f)
                    } else if (depth == 0 && f != rom) {
                        // Same-folder NSP/XCI without a bracketed title id is very
                        // likely a sibling update/DLC of this game.
                        addUri(f)
                    }
                }
            }
            walk(rom.parentFile, 0)
            Log.i("CitronEmbedLaunch", "found ${found.size} sibling DLC/update for ${rom.name}")
            found
        }.getOrElse { emptyList() }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(context, "org.citron.citron_emu.wn.WnCitronActivity")
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(CitronConsts.EXTRA_ROM_PATH, rom.absolutePath)
            putExtra(CitronConsts.EXTRA_GAME_NAME, shortcut.getExtra("custom_name", shortcut.name))
            putExtra(CitronConsts.EXTRA_GAME_ICON, shortcut.getExtra("customCoverArtPath", ""))
            putExtra(CitronConsts.EXTRA_SHORTCUT_PATH, shortcut.file.absolutePath)
            putExtra(CitronConsts.EXTRA_CONTAINER_ID, shortcut.container.id)
            putStringArrayListExtra(CitronConsts.EXTRA_ROM_SIBLINGS, ArrayList(siblingUris))
            putExtra(
                CitronConsts.EXTRA_HUD,
                shortcut.getExtra(RetroShortcuts.KEY_HUD, "0") == "1",
            )
            RetroFrameGen.writeInto(context, this, shortcut, RetroSystems.SWITCH.id)
        }

        runCatching { context.startActivity(intent) }
    }
}
