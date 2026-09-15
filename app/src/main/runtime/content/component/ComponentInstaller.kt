package com.winlator.cmod.runtime.content.component

import android.content.Context
import android.content.Intent
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.content.Downloader
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Executes a HuggingFace component manifest (the `.yml` recipe) into a specific [Container].
 * Mirrors the step semantics of the reference dependency installer.
 *
 * Phase-3 scope: download -> archive_extract -> copy_dll/copy_file -> override_dll + registry.
 * Verbs that require a running Wine process (install_exe/msi, register_dll/regsvr32, CAB extraction,
 * font registration, set_windows) throw [InstallException] so the UI can report them clearly.
 *
 * Run on a background thread.
 */
class ComponentInstaller(
    private val context: Context,
    private val container: Container,
    private val componentName: String,
    private val manifestYaml: String,
    private val listener: Listener,
    private val skipDownload: Boolean = false,
) {
    interface Listener {
        fun onStatus(text: String)

        /** [fraction] in 0..1, or negative for indeterminate. */
        fun onProgress(fraction: Float)
    }

    class InstallException(
        message: String,
    ) : Exception(message)

    private val componentDir = File(context.cacheDir, "wn-components/$componentName")
    private val workingDir = File(componentDir, "installed") // "temp/" + extraction scratch
    private val driveC = File(container.rootDir, ".wine/drive_c")
    private val userReg = File(container.rootDir, ".wine/user.reg")
    private val systemReg = File(container.rootDir, ".wine/system.reg")

    /** Last percentage forwarded to the UI, so the log tailer only emits on change. */
    @Volatile private var lastReportedPercent: Int = -1

    @Suppress("UNCHECKED_CAST")
    fun run() {
        if (!driveC.isDirectory) {
            throw InstallException(context.getString(R.string.comp_install_error_container_not_setup))
        }
        componentDir.mkdirs()
        workingDir.mkdirs()

        val doc = Yaml().load<Map<String, Any?>>(manifestYaml)
            ?: throw InstallException(context.getString(R.string.comp_install_error_empty_manifest))
        val steps = (doc["Steps"] as? List<Map<String, Any?>>)
            ?: throw InstallException(context.getString(R.string.comp_install_error_no_steps))

        try {
            checkCancel()
            if (!skipDownload) {
                download(steps)
            }
            checkCancel()
            extractArchives(steps)
            checkCancel()
            runSteps(steps)
            markInstalled()
        } finally {
            File(driveC, "wn-install").deleteRecursively()
            workingDir.deleteRecursively()
        }
    }

    // Abort if the worker thread was interrupted (the install sheet was closed).
    private fun checkCancel() {
        if (Thread.currentThread().isInterrupted) throw InstallException(context.getString(R.string.comp_install_error_cancelled))
    }

    private fun markInstalled() {
        val dir = File(container.rootDir, INSTALLED_DIR)
        dir.mkdirs()
        try {
            File(dir, componentName).writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.w(TAG, "couldn't write installed marker for $componentName", e)
        }
    }

    // ---- phase 1: download the referenced files ----
    private fun download(steps: List<Map<String, Any?>>) {
        val dlActions = setOf("download_archive", "install_exe", "install_msi", "cab_extract", "archive_extract")
        val toDownload = steps.filter { (it["action"] as? String) in dlActions && it["url"] is String }
        toDownload.forEachIndexed { i, step ->
            checkCancel()
            val url = (step["url"] as String).replace("huggingface.co", "hf-mirror.com")
            val name = (step["rename"] as? String) ?: (step["file_name"] as String)
            val dst = File(componentDir, name)
            val checksum = (step["file_checksum"] as? String)?.lowercase(Locale.ROOT)
            if (dst.isFile && dst.length() > 0 && (checksum == null || md5(dst) == checksum)) {
                return@forEachIndexed // already cached + verified
            }
            listener.onStatus(context.getString(R.string.comp_install_status_downloading, name, i + 1, toDownload.size))
            listener.onProgress(0f)
            val ok =
                Downloader.downloadFile(url, dst) { done, total ->
                    if (total > 0) listener.onProgress(done.toFloat() / total)
                }
            if (!ok) throw InstallException(context.getString(R.string.comp_install_error_download_failed, name))
            if (checksum != null && md5(dst) != checksum) {
                throw InstallException(context.getString(R.string.comp_install_error_checksum_mismatch, name))
            }
        }
    }

    // ---- phase 2: extract archives into the working dir ----
    private fun extractArchives(steps: List<Map<String, Any?>>) {
        for (step in steps) {
            checkCancel()
            when (step["action"] as? String) {
                "archive_extract" -> {
                    val name = (step["rename"] as? String) ?: (step["file_name"] as String)
                    val src = File(componentDir, name)
                    val outDir = File(workingDir, name.substringBeforeLast("."))
                    outDir.mkdirs()
                    listener.onStatus(context.getString(R.string.comp_install_status_extracting, name))
                    listener.onProgress(-1f)
                    when {
                        name.endsWith(".zip") -> extractZip(src, outDir)
                        name.endsWith(".tar.xz") || name.endsWith(".xz") ->
                            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, src, outDir)) {
                                throw InstallException(context.getString(R.string.comp_install_error_extract_failed, name))
                            }
                        name.endsWith(".tar.zst") || name.endsWith(".zst") || name.endsWith(".tzst") ->
                            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, src, outDir)) {
                                throw InstallException(context.getString(R.string.comp_install_error_extract_failed, name))
                            }
                        else -> throw InstallException(context.getString(R.string.comp_install_error_unsupported_format, name))
                    }
                }

                "cab_extract" -> {
                    val name = (step["rename"] as? String) ?: (step["file_name"] as String)
                    val dest = resolveDest(step["dest"] as? String ?: "temp/")
                    listener.onStatus(context.getString(R.string.comp_install_status_extracting, name))
                    listener.onProgress(-1f)
                    extractCab(resolveSource(name), dest, null)
                }

                "get_from_cab" -> {
                    val source = step["source"] as? String ?: continue
                    val pattern = step["file_name"] as? String
                    val dest = resolveDest(step["dest"] as? String ?: "temp/")
                    listener.onStatus(context.getString(R.string.comp_install_status_extracting_cab))
                    listener.onProgress(-1f)
                    for (cab in resolveCabSources(source)) extractCab(cab, dest, pattern)
                }
            }
        }
    }

    private fun extractZip(
        zip: File,
        outDir: File,
    ) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(outDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zis.copyTo(o) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Runs the bionic-native cabextract shipped in the system image, blocking until it finishes. */
    private fun extractCab(
        cab: File,
        destDir: File,
        pattern: String?,
    ) {
        if (!cab.isFile) throw InstallException(context.getString(R.string.comp_install_error_cab_not_found, cab.name))
        destDir.mkdirs()
        val rootDir = ImageFs.find(context).rootDir
        val cabextract = File(rootDir, "usr/bin/cabextract")
        if (!cabextract.isFile) throw InstallException(context.getString(R.string.comp_install_error_cabextract_missing))
        FileUtils.chmod(cabextract, "755".toInt(8))

        val args = ArrayList<String>()
        args.add(cabextract.absolutePath)
        if (pattern != null) {
            args.add("-F")
            args.add(pattern)
        }
        args.add("-d")
        args.add(destDir.absolutePath)
        args.add(cab.absolutePath)

        val pb = ProcessBuilder(args)
        pb.environment()["LD_LIBRARY_PATH"] = File(rootDir, "usr/lib").absolutePath + ":/system/lib64"
        val devNull = File("/dev/null")
        pb.redirectOutput(ProcessBuilder.Redirect.to(devNull))
        pb.redirectError(ProcessBuilder.Redirect.to(devNull))
        val proc = pb.start()
        try {
            val exit = proc.waitFor()
            if (exit != 0) throw InstallException(context.getString(R.string.comp_install_error_cab_extract_failed, exit, cab.name))
        } catch (e: InterruptedException) {
            proc.destroy()
            throw InstallException(context.getString(R.string.comp_install_error_cancelled))
        } catch (e: java.io.IOException) {
            throw InstallException(context.getString(R.string.comp_install_error_cabextract_run, e.message))
        }
    }

    /** Resolves a get_from_cab source, expanding a `*` glob in the filename to the matching cab files. */
    private fun resolveCabSources(source: String): List<File> {
        if (!source.contains("*")) return listOf(resolveSource(source))
        val resolved = resolveSource(source)
        val parent = resolved.parentFile ?: return emptyList()
        val nameGlob = globToRegex(resolved.name)
        return parent.listFiles()?.filter { it.isFile && nameGlob.matches(it.name) } ?: emptyList()
    }

    // ---- phase 3: apply the install steps ----
    private fun runSteps(steps: List<Map<String, Any?>>) {
        listener.onStatus(context.getString(R.string.comp_install_status_installing_into, container.name))
        listener.onProgress(-1f)
        for (step in steps) {
            checkCancel()
            when (val action = step["action"] as? String) {
                "download_archive", "archive_extract" -> {} // already handled
                "copy_dll", "copy_file", "link_dir" -> copyFiles(step)
                "override_dll" -> overrideDll(step)
                "set_register_key" -> setRegisterKey(step)
                "delete_dlls" -> deleteDlls(step)
                "install_exe" -> runInstaller(step, isMsi = false)
                "install_msi" -> runInstaller(step, isMsi = true)
                "register_font" -> registerFont(step)
                "replace_font" -> replaceFont(step)
                "install_fonts", "install_cab_fonts" -> installFonts(step)
                "register_dll" -> registerDlls(step)
                "uninstall" -> Log.w(TAG, "skipping uninstall for $componentName (installer handles replacement)")
                "set_windows", "use_windows" ->
                    throw InstallException("Needs '$action' — not supported yet.")
                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyFiles(step: Map<String, Any?>) {
        val name = step["file_name"] as? String ?: return
        val srcDir = resolveSource(step["url"] as? String ?: return)
        val dstDir = resolveDest(step["dest"] as? String ?: return)
        dstDir.mkdirs()
        if (name.contains("*")) {
            val regex = globToRegex(name)
            val files = srcDir.listFiles() ?: throw InstallException(context.getString(R.string.comp_install_error_source_not_found, srcDir))
            var copied = 0
            for (f in files) {
                if (f.isFile && regex.matches(f.name)) {
                    if (!FileUtils.copy(f, File(dstDir, f.name))) throw InstallException(context.getString(R.string.comp_install_error_copy_failed, f.name))
                    copied++
                }
            }
            if (copied == 0) Log.w(TAG, "copy: no files matched '$name' in $srcDir")
        } else {
            val src = File(srcDir, name)
            if (!src.exists()) throw InstallException(context.getString(R.string.comp_install_error_missing_file, name))
            if (!FileUtils.copy(src, File(dstDir, name))) throw InstallException(context.getString(R.string.comp_install_error_copy_failed, name))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun overrideDll(step: Map<String, Any?>) {
        WineRegistryEditor(userReg).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            val bundle = step["bundle"] as? List<Map<String, Any?>>
            if (bundle != null) {
                for (b in bundle) {
                    val dll = (b["value"] ?: continue).toString()
                    val data = (b["data"] ?: "native,builtin").toString()
                    reg.setStringValue(DLL_OVERRIDES, dll, data)
                }
            } else {
                val dll = (step["dll"] ?: return@use).toString()
                val type = (step["type"] ?: "native,builtin").toString()
                reg.setStringValue(DLL_OVERRIDES, dll, type)
            }
        }
    }

    private fun setRegisterKey(step: Map<String, Any?>) {
        val key = step["key"] as? String ?: return
        val value = step["value"] as? String ?: return
        val hklm = key.startsWith("HKLM", ignoreCase = true)
        val regFile = if (hklm) systemReg else userReg
        val subKey = key.substringAfter('\\').trimStart('\\')
        WineRegistryEditor(regFile).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            when (step["type"] as? String) {
                "REG_DWORD" -> {
                    val data = step["data"]
                    val intVal = (data as? Int) ?: data?.toString()?.toIntOrNull() ?: 0
                    reg.setDwordValue(subKey, value, intVal)
                }
                "REG_SZ" -> reg.setStringValue(subKey, value, (step["data"] ?: "").toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deleteDlls(step: Map<String, Any?>) {
        val dir = resolveDest(step["dest"] as? String ?: return)
        val dlls = step["dlls"] as? List<String> ?: return
        for (d in dlls) File(dir, d).delete()
    }

    private fun registerFont(step: Map<String, Any?>) {
        val name = step["name"] as? String ?: return
        val file = step["file"] as? String ?: return
        WineRegistryEditor(systemReg).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            reg.setStringValue("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", name, file)
        }
    }

    private fun replaceFont(step: Map<String, Any?>) {
        val font = step["font"] as? String ?: return
        val replacement = (step["replace"] as? List<*>)?.firstOrNull()?.toString() ?: return
        WineRegistryEditor(userReg).use { reg ->
            reg.setCreateKeyIfNotExist(true)
            reg.setStringValue("Software\\Wine\\Fonts\\Replacements", font, replacement)
        }
    }

    private fun installFonts(step: Map<String, Any?>) {
        val srcDir = resolveDest(step["url"] as? String ?: return)
        val fonts = step["fonts"] as? List<*> ?: return
        val fontsDir = File(driveC, "windows/Fonts")
        fontsDir.mkdirs()
        for (f in fonts) {
            val fn = f?.toString() ?: continue
            val src = File(srcDir, fn)
            if (src.isFile) FileUtils.copy(src, File(fontsDir, fn))
        }
    }

    /** Stages the installer into the container and runs it inside Wine (via the boot session), waiting. */
    private fun runInstaller(
        step: Map<String, Any?>,
        isMsi: Boolean,
    ) {
        val fileName = (step["rename"] as? String) ?: (step["file_name"] as String)
        val src = File(componentDir, fileName)
        if (!src.isFile) throw InstallException(context.getString(R.string.comp_install_error_installer_not_found, fileName))
        val stageDir = File(driveC, "wn-install")
        stageDir.mkdirs()
        val staged = File(stageDir, fileName)
        if (!FileUtils.copy(src, staged)) throw InstallException(context.getString(R.string.comp_install_error_stage_installer, fileName))

        val winPath = "C:\\wn-install\\$fileName"
        val arguments = (step["arguments"] as? String).orEmpty()
        listener.onStatus(context.getString(R.string.comp_install_status_running_installer, fileName))
        listener.onProgress(-1f)

        val bootExe: String
        val bootArgs: String
        var progressLog: File? = null
        if (isMsi) {
            // /L*V is the verbose log; we tail it while msiexec runs to pull real "Progress:" values
            // out instead of showing a bare elapsed-time counter.
            val logFile = File(stageDir, MSI_LOG_NAME)
            logFile.delete()
            progressLog = logFile
            bootExe = "C:\\windows\\system32\\msiexec.exe"
            bootArgs =
                "/i \"$winPath\" /L*V \"C:\\wn-install\\$MSI_LOG_NAME\" ${arguments.ifBlank { "/quiet" }} /norestart"
                    .trim()
        } else {
            bootExe = winPath
            bootArgs = arguments
        }
        DependencyInstallBridge.updateStatus(context.getString(R.string.comp_install_status_running_installer, fileName))
        val code = launchInContainerAndWait(bootExe, bootArgs, progressLog)
        DependencyInstallBridge.updateStatus(null)
        if (code != 0 && code != 3010 && code != 143) throw InstallException(context.getString(R.string.comp_install_error_installer_exit_code, code, fileName))
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerDlls(step: Map<String, Any?>) {
        val dlls = (step["dlls"] as? List<String>)?.filter { it.isNotBlank() } ?: return
        if (dlls.isEmpty()) return
        listener.onStatus(context.getString(R.string.comp_install_status_registering_dlls, dlls.size))
        listener.onProgress(-1f)
        val batch = dlls.joinToString(" & ") { "regsvr32 /s $it" }
        val code = launchInContainerAndWait("C:\\windows\\system32\\cmd.exe", "/c \"$batch\"")
        // regsvr32 /s exit codes are unreliable; the DLLs are already placed + overridden, so don't hard-fail.
        if (code != 0) Log.w(TAG, "register_dll batch for $componentName exited $code")
    }

    /** Boots the container with the given Wine program (gated dependency mode) and blocks for its exit code. */
    private fun launchInContainerAndWait(
        bootExe: String,
        bootArgs: String,
        progressLog: File? = null,
    ): Int {
        if (SessionKeepAliveService.isSessionActive() && SessionKeepAliveService.getActiveEnvironment() != null) {
            throw InstallException(context.getString(R.string.comp_install_error_session_active))
        }
        DependencyInstallBridge.begin()
        val intent =
            Intent(context, XServerDisplayActivity::class.java).apply {
                putExtra("container_id", container.id)
                putExtra("boot_exe", bootExe)
                putExtra("boot_exe_args", bootArgs)
                putExtra("is_dependency_installer", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val stopTailer = progressLog?.let { startProgressTailer(it) }
        context.startActivity(intent)
        return try {
            DependencyInstallBridge.await(INSTALL_TIMEOUT_MS)
                ?: throw InstallException(context.getString(R.string.comp_install_error_timeout))
        } finally {
            stopTailer?.run()
            DependencyInstallBridge.updateProgress(-1)
            DependencyInstallBridge.updateAction(null)
        }
    }

    /** Localised label for an msiexec action; falls back to the raw action name. */
    private fun actionLabel(name: String): String {
        val res = MSI_ACTION_LABEL[name.uppercase(Locale.ROOT)] ?: return name
        return try {
            context.getString(res)
        } catch (e: Exception) {
            name
        }
    }

    /**
     * Watches [log] (written by msiexec inside the container) on a background thread and forwards
     * the newest parsed percentage to the UI. Returns a handle that stops the thread.
     */
    private fun startProgressTailer(log: File): Runnable {
        val stop = AtomicBoolean(false)
        val tracker = MsiProgressTracker()
        val thread =
            thread(start = true, isDaemon = true, name = "msi-progress-tail") {
                var lastActionName: String? = null
                while (!stop.get()) {
                    val percent = tracker.poll(log)
                    val action = tracker.actionName
                    if (action != lastActionName) {
                        lastActionName = action
                        DependencyInstallBridge.updateAction(action?.let { actionLabel(it) })
                    }
                    if (percent != null && percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        DependencyInstallBridge.updateProgress(percent)
                        listener.onProgress(percent / 100f)
                    }
                    try {
                        Thread.sleep(PROGRESS_POLL_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        return Runnable {
            stop.set(true)
            thread.interrupt()
        }
    }

    /**
     * Reads the tail of the msiexec log and returns the most recent progress percentage, or null if
     * the log has no parsable `Progress:` entry yet.
     *
     * Windows Installer writes progress either as a plain `Progress: 42` (already a percentage) or
     * as `Progress: <done>, <total>` (counts, e.g. bytes or ticks).
     */
    private fun readProgressPercent(log: File): Int? {
        val length =
            try {
                if (!log.isFile) return null
                log.length()
            } catch (e: Exception) {
                return null
            }
        if (length <= 0) return null
        val size = minOf(length, PROGRESS_TAIL_BYTES).toInt()
        var from = length - size
        // Keep UTF-16 code units aligned in case the log is written as UTF-16LE.
        if (from % 2L != 0L) from -= 1
        val bytes = ByteArray((length - from).toInt())
        try {
            java.io.RandomAccessFile(log, "r").use { raf ->
                raf.seek(from)
                var offset = 0
                while (offset < bytes.size) {
                    val n = raf.read(bytes, offset, bytes.size - offset)
                    if (n < 0) break
                    offset += n
                }
            }
        } catch (e: Exception) {
            return null
        }

        var result: Int? = null
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
            val text =
                try {
                    String(bytes, charset)
                } catch (e: Exception) {
                    continue
                }
            val match = PROGRESS_REGEX.findAll(text).lastOrNull() ?: continue
            val first = match.groupValues[1].toLongOrNull() ?: continue
            val total = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
            val percent =
                when {
                    total != null && total > 0 -> first * 100 / total
                    first in 0L..100L -> first
                    else -> continue
                }
            result = percent.coerceIn(0L, 100L).toInt()
        }
        return result
    }

    /**
     * Incremental scanner for the msiexec verbose log.
     *
     * Wine's msiexec never writes a numeric `Progress:` value, so the ring is driven by how far the
     * standard-action sequence has advanced (`Action ended <time>: <Name>. Return value 1.`). The
     * file is scanned incrementally because later stages (file lists) push earlier action lines out
     * of any fixed-size tail window.
     */
    private inner class MsiProgressTracker {
        private var offset = 0L
        private var percent = -1
        private var stageEndedAt = 0L

        /** Raw name of the action currently running, or the one that just finished. */
        @Volatile var actionName: String? = null

        fun poll(log: File): Int? {
            // Real percentages (Windows msiexec) always win over the action-based estimate.
            readProgressPercent(log)?.let { return it }
            val length =
                try {
                    if (!log.isFile) return current()
                    log.length()
                } catch (e: Exception) {
                    return current()
                }
            if (length > offset) {
                val chunk = minOf(length - offset, MSI_SCAN_CHUNK_BYTES).toInt()
                val bytes = ByteArray(chunk)
                val read =
                    try {
                        java.io.RandomAccessFile(log, "r").use { raf ->
                            raf.seek(offset)
                            var off = 0
                            while (off < chunk) {
                                val n = raf.read(bytes, off, chunk - off)
                                if (n < 0) break
                                off += n
                            }
                            off
                        }
                    } catch (e: Exception) {
                        return current()
                    }
                val text = String(bytes, 0, read, Charsets.UTF_8)
                for (m in MSI_ACTION_LINE_REGEX.findAll(text)) {
                    val name = m.groupValues[2]
                    val key = name.uppercase(Locale.ROOT)
                    if (m.groupValues[1] == "start") {
                        // INSTALL is the outer action; surfacing it would announce completion at once.
                        if (key != "INSTALL") actionName = name
                        continue
                    }
                    val target = MSI_ACTION_PROGRESS[key]
                    if (target != null && target > percent) {
                        percent = target
                        stageEndedAt = System.currentTimeMillis()
                        actionName = name
                    }
                }
                offset = length
            }
            return current()
        }

        /** Creeps towards the next action's value so long-running stages keep moving. */
        private fun current(): Int? {
            if (percent < 0) return null
            val next = MSI_ACTION_PROGRESS.values.filter { it > percent }.minOrNull() ?: 100
            val room = (next - percent).coerceAtLeast(0)
            if (room == 0) return percent
            val creep =
                ((System.currentTimeMillis() - stageEndedAt).toDouble() / MSI_STAGE_CREEP_MS * room)
                    .coerceIn(0.0, room * 0.9)
            return (percent + creep).toInt().coerceIn(0, 100)
        }
    }

    // ---- path templates ----
    // q(): where a source file currently lives (downloads in componentDir, extracted under temp/=workingDir).
    private fun resolveSource(path: String): File {
        if (path.startsWith("temp/")) return File(workingDir, path.removePrefix("temp/"))
        val inComp = File(componentDir, path)
        return if (inComp.exists()) inComp else File(workingDir, path)
    }

    // l(): where a file should land. win32->syswow64, win64->system32, windows/->drive_c/windows, temp/->workingDir.
    private fun resolveDest(dest: String): File =
        when {
            dest.startsWith("temp/") -> File(workingDir, dest.removePrefix("temp/"))
            dest.startsWith("windows/") -> File(driveC, dest)
            dest == "win32" || dest.startsWith("win32/") ->
                File(File(driveC, "windows/syswow64"), dest.removePrefix("win32").trimStart('/'))
            dest == "win64" || dest.startsWith("win64/") ->
                File(File(driveC, "windows/system32"), dest.removePrefix("win64").trimStart('/'))
            else -> File(dest)
        }

    private fun globToRegex(glob: String): Regex {
        val p =
            buildString {
                for (c in glob) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> if (c.isLetterOrDigit() || c == '_' || c == '-') append(c) else append("\\$c")
                    }
                }
            }
        return Regex("^$p$", RegexOption.IGNORE_CASE)
    }

    private fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ComponentInstaller"
        private const val DLL_OVERRIDES = "Software\\Wine\\DllOverrides"
        private const val INSTALL_TIMEOUT_MS = 20L * 60L * 1000L
        private const val INSTALLED_DIR = ".wn-components"
        private const val MSI_LOG_NAME = "msi-install.log"
        private const val PROGRESS_POLL_MS = 400L
        private const val PROGRESS_TAIL_BYTES = 8192L

        /** `Progress: 42`, `Progress: 1234, 5678` — case-insensitive, as Wine may upper-case it. */
        private val PROGRESS_REGEX =
            Regex("""Progress:\s*(\d+)(?:\s*,\s*(\d+))?""", RegexOption.IGNORE_CASE)

        /**
         * `Action start 4:12:33: InstallFiles.` / `Action ended 4:12:33: InstallFiles. Return value 1.`
         * — Wine's msiexec log. Group 1 is the verb, group 2 the action name.
         */
        private val MSI_ACTION_LINE_REGEX =
            Regex("""Action (start|ended) \d+:\d+:\d+:\s*([A-Za-z0-9_.]+)\.""")

        /**
         * Percentage to show once the given standard action has finished. Wine writes no numeric
         * progress, so the standard-action sequence is the only real signal available.
         */
        private val MSI_ACTION_PROGRESS =
            mapOf(
                "COSTINITIALIZE" to 2,
                "FILECOST" to 4,
                "ISOLATECOMPONENTS" to 5,
                "COSTFINALIZE" to 8,
                "MIGRATEFEATURESTATES" to 9,
                "INSTALLVALIDATE" to 10,
                "INSTALLINITIALIZE" to 12,
                "ALLOCATEREGISTRYSPACE" to 13,
                "PROCESSCOMPONENTS" to 14,
                "UNPUBLISHCOMPONENTS" to 15,
                "MSIUNPUBLISHASSEMBLIES" to 15,
                "UNPUBLISHFEATURES" to 15,
                "STOPSERVICES" to 16,
                "DELETESERVICES" to 16,
                "UNREGISTERCOMPLUS" to 16,
                "SELFUNREGMODULES" to 17,
                "UNREGISTERTYPELIBRARIES" to 17,
                "REMOVEODBC" to 17,
                "UNREGISTERFONTS" to 17,
                "REMOVEREGISTRYVALUES" to 18,
                "UNREGISTERCLASSINFO" to 18,
                "UNREGISTEREXTENSIONINFO" to 18,
                "UNREGISTERPROGIDINFO" to 18,
                "UNREGISTERMIMEINFO" to 18,
                "REMOVEINIVALUES" to 18,
                "REMOVESHORTCUTS" to 18,
                "REMOVEENVIRONMENTSTRINGS" to 18,
                "REMOVEDUPLICATEFILES" to 18,
                "REMOVEFILES" to 19,
                "REMOVEFOLDERS" to 20,
                "CREATEFOLDERS" to 22,
                "MOVEFILES" to 24,
                "INSTALLFILES" to 75,
                "PATCHFILES" to 78,
                "DUPLICATEFILES" to 80,
                "BINDIMAGE" to 81,
                "CREATESHORTCUTS" to 84,
                "REGISTERCLASSINFO" to 85,
                "REGISTEREXTENSIONINFO" to 85,
                "REGISTERPROGIDINFO" to 86,
                "REGISTERMIMEINFO" to 86,
                "REGISTERTYPELIBRARIES" to 88,
                "SELFREGMODULES" to 91,
                "REGISTERCOMPLUS" to 92,
                "INSTALLODBC" to 92,
                "REGISTERFONTS" to 93,
                "INSTALLREGISTRYVALUES" to 95,
                "REGISTERPRODUCT" to 96,
                "PUBLISHCOMPONENTS" to 96,
                "MSIPUBLISHASSEMBLIES" to 97,
                "PUBLISHFEATURES" to 97,
                "PUBLISHPRODUCT" to 98,
                "INSTALLEXECUTE" to 98,
                "INSTALLEXECUTEAGAIN" to 98,
                "STARTSERVICES" to 98,
                "INSTALLFINALIZE" to 99,
                "REMOVEEXISTINGPRODUCTS" to 99,
                "REGISTERUSER" to 99,
                "SCHEDULEREBOOT" to 99,
                "INSTALL" to 100,
            )

        /** Standard-action name -> localised label shown in front of the percentage. */
        private val MSI_ACTION_LABEL =
            mapOf(
                "COSTINITIALIZE" to R.string.comp_install_action_costing,
                "FILECOST" to R.string.comp_install_action_costing,
                "ISOLATECOMPONENTS" to R.string.comp_install_action_costing,
                "COSTFINALIZE" to R.string.comp_install_action_costing,
                "MIGRATEFEATURESTATES" to R.string.comp_install_action_validating,
                "INSTALLVALIDATE" to R.string.comp_install_action_validating,
                "INSTALLINITIALIZE" to R.string.comp_install_action_initializing,
                "ALLOCATEREGISTRYSPACE" to R.string.comp_install_action_initializing,
                "PROCESSCOMPONENTS" to R.string.comp_install_action_components,
                "UNPUBLISHCOMPONENTS" to R.string.comp_install_action_components,
                "MSIUNPUBLISHASSEMBLIES" to R.string.comp_install_action_components,
                "UNPUBLISHFEATURES" to R.string.comp_install_action_components,
                "REMOVEFILES" to R.string.comp_install_action_cleanup_files,
                "REMOVEDUPLICATEFILES" to R.string.comp_install_action_cleanup_files,
                "REMOVEFOLDERS" to R.string.comp_install_action_cleanup_folders,
                "CREATEFOLDERS" to R.string.comp_install_action_create_folders,
                "MOVEFILES" to R.string.comp_install_action_move_files,
                "INSTALLFILES" to R.string.comp_install_action_install_files,
                "PATCHFILES" to R.string.comp_install_action_patch_files,
                "DUPLICATEFILES" to R.string.comp_install_action_duplicate_files,
                "BINDIMAGE" to R.string.comp_install_action_bind_image,
                "CREATESHORTCUTS" to R.string.comp_install_action_shortcuts,
                "REGISTERTYPELIBRARIES" to R.string.comp_install_action_type_libraries,
                "SELFREGMODULES" to R.string.comp_install_action_modules,
                "REGISTERCOMPLUS" to R.string.comp_install_action_modules,
                "INSTALLODBC" to R.string.comp_install_action_modules,
                "INSTALLREGISTRYVALUES" to R.string.comp_install_action_registry,
                "REGISTERCLASSINFO" to R.string.comp_install_action_registry,
                "REGISTEREXTENSIONINFO" to R.string.comp_install_action_registry,
                "REGISTERPROGIDINFO" to R.string.comp_install_action_registry,
                "REGISTERMIMEINFO" to R.string.comp_install_action_registry,
                "REGISTERFONTS" to R.string.comp_install_action_fonts,
                "REGISTERPRODUCT" to R.string.comp_install_action_product,
                "PUBLISHCOMPONENTS" to R.string.comp_install_action_publish,
                "MSIPUBLISHASSEMBLIES" to R.string.comp_install_action_publish,
                "PUBLISHFEATURES" to R.string.comp_install_action_publish,
                "PUBLISHPRODUCT" to R.string.comp_install_action_publish,
                "STARTSERVICES" to R.string.comp_install_action_services,
                "STOPSERVICES" to R.string.comp_install_action_services,
                "DELETESERVICES" to R.string.comp_install_action_services,
                "INSTALLEXECUTE" to R.string.comp_install_action_services,
                "INSTALLEXECUTEAGAIN" to R.string.comp_install_action_services,
                "INSTALLFINALIZE" to R.string.comp_install_action_finalizing,
                "REMOVEEXISTINGPRODUCTS" to R.string.comp_install_action_finalizing,
                "REGISTERUSER" to R.string.comp_install_action_finalizing,
                "SCHEDULEREBOOT" to R.string.comp_install_action_finalizing,
                "INSTALL" to R.string.comp_install_action_complete,
            )

        /** Max bytes decoded per poll; the rest is picked up on the next pass. */
        private const val MSI_SCAN_CHUNK_BYTES = 512L * 1024L

        /** Time it takes for the creeping fill to cross a whole stage gap. */
        private const val MSI_STAGE_CREEP_MS = 15_000L

        /** Names of components already installed into [container] (persisted per container). */
        @JvmStatic
        fun installedComponents(container: Container): Set<String> {
            val dir = File(container.rootDir, INSTALLED_DIR)
            return dir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
        }
    }
}