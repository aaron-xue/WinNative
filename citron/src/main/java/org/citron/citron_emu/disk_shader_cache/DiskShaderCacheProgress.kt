package org.citron.citron_emu.disk_shader_cache

import org.citron.citron_emu.wn.CitronHost

// Minimal stub matching the JNI surface expected by libcitron-android.so
// (id_cache.cpp caches the static loadProgress(III)V method and the
// LoadCallbackStage inner class).
object DiskShaderCacheProgress {
    @JvmStatic
    fun loadProgress(stage: Int, progress: Int, max: Int) {
        CitronHost.onShaderCacheProgress?.invoke(stage, progress, max)
    }

    enum class LoadCallbackStage {
        Prepare, Build, Complete
    }
}
