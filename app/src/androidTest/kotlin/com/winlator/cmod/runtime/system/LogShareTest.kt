package com.winlator.cmod.runtime.system

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LogShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A token of the shape Steam's connection log prints, with nothing real in it. */
    private val token =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFZERTQSJ9.eyJpc3MiOiJ0ZXN0Iiwic3ViIjoiMCJ9.bm90YXJlYWxzaWduYXR1cmU"

    @Test
    fun theAppsOwnLogsGoOutAsTheyAre() {
        val log = File(LogManager.getLogsDir(context, false), "log-share-test.txt")
        try {
            log.parentFile?.mkdirs()
            log.writeText("kept $token\n")
            val shared = ByteArrayOutputStream()
            LogManager.copyShareable(context, log, shared)
            assertEquals("kept $token\n", shared.toString("UTF-8"))
        } finally {
            log.delete()
        }
    }
}