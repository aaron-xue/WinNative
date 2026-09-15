package com.winlator.cmod.runtime.content.component

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-at-a-time handoff between [ComponentInstaller] (running on a background thread) and the
 * XServer session that is launched to run an installer / regsvr32 inside the container.
 *
 * The installer thread calls [begin] then [await]; the session, when its dependency guest program
 * terminates, calls [complete] with the exit code (see XServerDisplayActivity terminationCallback).
 */
object DependencyInstallBridge {
    private val lock = Any()

    @Volatile private var latch: CountDownLatch? = null

    @Volatile private var exitCode: Int = -1

    @Volatile var installStatusText: String? = null

    /**
     * Human-readable label for the msiexec standard action currently running (already localised by
     * the caller), shown in front of the percentage when one is available.
     */
    @Volatile private var installActionText: String? = null

    /**
     * Install progress in 0..100 reported by the guest installer (currently: parsed out of the
     * msiexec verbose log), or -1 when the installer does not report a percentage and the UI
     * should fall back to its elapsed-time readout.
     */
    @Volatile private var installProgress: Int = -1

    @JvmStatic
    fun updateStatus(text: String?) {
        installStatusText = text
    }

    @JvmStatic
    fun getStatus(): String? {
        return installStatusText
    }

    @JvmStatic
    fun updateAction(text: String?) {
        installActionText = text
    }

    @JvmStatic
    fun getAction(): String? {
        return installActionText
    }

    @JvmStatic
    fun updateProgress(percent: Int) {
        installProgress = percent
    }

    @JvmStatic
    fun getProgress(): Int {
        return installProgress
    }

    fun begin() {
        synchronized(lock) {
            latch = CountDownLatch(1)
            exitCode = -1
            installProgress = -1
            installActionText = null
        }
    }

    /** Called by the boot session when the dependency program terminates. */
    @JvmStatic
    fun complete(code: Int) {
        synchronized(lock) {
            val l = latch ?: return
            if (l.count == 0L) return
            exitCode = code
            l.countDown()
        }
    }

    /** Blocks up to [timeoutMs]; returns the program's exit code, or null on timeout. */
    fun await(timeoutMs: Long): Int? {
        val l = synchronized(lock) { latch } ?: return null
        val finished = l.await(timeoutMs, TimeUnit.MILLISECONDS)
        return synchronized(lock) {
            latch = null
            if (finished) exitCode else null
        }
    }
}