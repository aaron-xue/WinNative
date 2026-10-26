package org.citron.citron_emu

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.citron.citron_emu.model.Patch
import org.citron.citron_emu.wn.CitronHost
import java.io.File
import java.lang.ref.WeakReference

// Thin bridge onto libcitron-android.so. The class name / package MUST stay
// `org.citron.citron_emu.NativeLibrary` so the so's `Java_org_citron_citron_1emu_NativeLibrary_*`
// symbols resolve (standard JNI lazy linking). Native methods are resolved by
// name from the so; the static methods below are the ones libcitron-android.so's
// JNI_OnLoad / RegisterCallbacks / SoftwareKeyboard::InitJNI cache and call into.

object NativeLibrary {
    // Application context used by the filesystem callbacks (e.g. resolving
    // content:// URIs via ContentResolver). Set from CitronBootstrap.ensure().
    var appContext: Context? = null

    // ---- lifecycle / error callbacks (called by C++) ----
    @JvmStatic
    fun onEmulationStarted() {
        CitronHost.onEmulationStarted?.invoke()
    }

    @JvmStatic
    fun onEmulationStopped(status: Int) {
        CitronHost.onEmulationStopped?.invoke(status)
    }

    @JvmStatic
    fun onProgramChanged(programIndex: Int) {
        CitronHost.onProgramChanged?.invoke(programIndex)
    }

    @JvmStatic
    fun exitEmulationActivity(resultCode: Int) {
        CitronHost.onExitEmulationActivity?.invoke(resultCode)
    }

    // ---- filesystem callbacks (called by C++) ----
    // The core opens game files via content:// URIs handed over by the host app,
    // so these callbacks resolve them through ContentResolver.
    private fun isContent(path: String) = Uri.parse(path).scheme == "content"

    @JvmStatic
    fun getParentDirectory(path: String): String =
        if (isContent(path)) {
            val i = path.lastIndexOf('/')
            if (i > 0) path.substring(0, i) else path
        } else {
            File(path).parent ?: path
        }

    @JvmStatic
    fun getFilename(path: String): String {
        if (isContent(path)) {
            val uri = Uri.parse(path)
            appContext?.contentResolver?.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) return it.getString(0) }
        }
        return File(path).name
    }

    @JvmStatic
    fun getSize(path: String): Long {
        if (isContent(path)) {
            val uri = Uri.parse(path)
            appContext?.contentResolver?.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { if (it.moveToFirst()) return it.getLong(0) }
            appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { return it.statSize }
        }
        return runCatching { File(path).length() }.getOrDefault(0L)
    }

    @JvmStatic
    fun isDirectory(path: String): Boolean =
        if (isContent(path)) false else runCatching { File(path).isDirectory }.getOrDefault(false)

    @JvmStatic
    fun exists(path: String): Boolean = runCatching {
        if (isContent(path)) {
            appContext?.contentResolver?.openFileDescriptor(Uri.parse(path), "r")?.use { true }
                ?: false
        } else {
            File(path).exists()
        }
    }.getOrDefault(false)

    @JvmStatic
    fun openContentUri(path: String, mode: String): Int = runCatching {
        val uri = Uri.parse(path)
        if (uri.scheme == "content") {
            // Resolve a content:// URI to a real file descriptor via the
            // ContentResolver (the core cannot open content URIs directly).
            val pfd = appContext?.contentResolver?.openFileDescriptor(uri, mode)
            pfd?.detachFd() ?: -1
        } else {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.parseMode(mode)).detachFd()
        }
    }.getOrDefault(-1)

    var sEmulationActivity: WeakReference<Any?> = WeakReference(null)
    fun setEmulationActivity(a: Any?) {
        sEmulationActivity = WeakReference(a)
    }

    fun clearEmulationActivity() {
        sEmulationActivity.clear()
    }

    enum class CoreError {
        ErrorSystemFiles,
        ErrorSavestate,
        ErrorUnknown
    }

    // ---- native methods (resolved lazily from libcitron-android.so) ----
    external fun setAppDirectory(directory: String)

    // The native installer reports progress through a Kotlin function-type
    // callback: it up-calls `invoke(Object, Object): Object` (i.e. a Function2
    // with `(Double, Double) -> Boolean`). Do NOT replace this with a custom
    // SAM interface — the .so only knows how to call `Function2.invoke`, so any
    // other type throws NoSuchMethodError and every install fails.
    external fun installFileToNand(filename: String, callback: (max: Double, progress: Double) -> Boolean): Int

    external fun doesUpdateMatchProgram(programId: String, updatePath: String): Boolean

    external fun initializeGpuDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?
    )

    external fun reloadKeys(): Boolean

    external fun initializeSystem(reload: Boolean)

    /**
     * VFS-relative root of the default profile's game saves, e.g.
     * "/user/save/0000000000000000/<user id>" (or "/user/save/account/<user id>" when
     * [future] is true). The path is relative to the NAND directory.
     */
    external fun getDefaultProfileSaveDataRoot(future: Boolean): String

    external fun run(path: String?, programIndex: Int, frontendInitiated: Boolean)

    external fun surfaceChanged(surf: android.view.Surface?)

    external fun surfaceDestroyed()

    external fun unpauseEmulation()

    external fun pauseEmulation()

    external fun stopEmulation()

    external fun isRunning(): Boolean

    external fun clearShaderCache(): Int

    external fun isPaused(): Boolean

    external fun connectToRoom(
        nickname: String,
        host: String,
        port: Int,
        password: String
    ): Boolean

    external fun hostRoom(
        nickname: String,
        name: String,
        description: String,
        port: Int,
        password: String,
        maxPlayers: Int
    ): Boolean

    external fun getRoomConnectionState(): Int

    external fun getRoomLastError(): Int

    external fun getMultiplayerErrorValues(): IntArray

    external fun getMaxRoomChatMessageBytes(): Int

    external fun getRoomInfo(): Array<String>

    external fun getRoomMembers(): Array<String>

    external fun drainRoomEvents(): Array<String>

    external fun sendRoomChatMessage(message: String): Boolean

    external fun isHostingRoom(): Boolean

    external fun leaveRoom()

    external fun closeRoom()

    external fun getPerfStats(): DoubleArray

    external fun getShadersBuilding(): Int

    external fun getCpuBackend(): String

    external fun getGpuDriver(): String

    external fun applySettings()

    external fun logSettings()

    external fun logDeviceInfo()

    external fun submitInlineKeyboardText(text: String?)

    external fun replaceInlineKeyboardText(text: String?, cursor_position: Int)

    external fun submitInlineKeyboardInput(key_code: Int)

    external fun initializeEmptyUserDirectory()

    external fun getAppletLaunchPath(id: Long): String

    external fun setCurrentAppletId(appletId: Int)

    external fun setCabinetMode(cabinetMode: Int)

    external fun isFirmwareAvailable(): Boolean

    external fun getPatchesForFile(path: String, programId: String): Array<Patch>?

    external fun getCheatsForFile(path: String, programId: String): Array<Patch>?

    external fun setCheatEnabled(buildId: String, source: String, name: String, enabled: Boolean)

    external fun disableCheatsForAddon(programId: String, addonName: String)

    external fun reloadCheats(programId: String): Boolean

    external fun addCheat(programId: String, title: String, code: String): Int

    external fun removeBaseContent(programId: String): Boolean

    // Whether the given base program ID has DLC / update installed in NAND. Used
    // only as a post-install diagnostic; callers must guard with runCatching
    // because the native signature is assumed from yuzu's upstream interface.
    external fun hasInstalledDLC(programId: String): Boolean

    external fun hasInstalledUpdate(programId: String): Boolean

    // Registers an external content file (DLC / update NSP/NSZ) with the
    // emulator's VFS so the running game can mount its Add-On Content at boot
    // without permanently installing it into the NAND. This is the mechanism the
    // upstream Citron app uses (ExternalContentHelper.registerSiblings) and is what
    // makes AOC available when the base ROM is launched from a content:// URI.
    external fun addExternalContentFile(path: String)

    external fun clearExternalContentFiles()
}
