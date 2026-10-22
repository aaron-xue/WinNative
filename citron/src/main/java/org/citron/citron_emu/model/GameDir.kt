package org.citron.citron_emu.model

// Minimal stub matching the JNI surface that libcitron-android.so expects
// (see src/common/android/id_cache.cpp). The C++ side only reads the
// (String;Z)V constructor + the uriString/deepScan fields.
data class GameDir(
    val uriString: String,
    var deepScan: Boolean
)
