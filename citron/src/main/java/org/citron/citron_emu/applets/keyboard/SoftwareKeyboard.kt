package org.citron.citron_emu.applets.keyboard

// Minimal stub matching the JNI surface expected by libcitron-android.so
// (software_keyboard.cpp InitJNI caches executeNormal(KeyboardConfig):
// KeyboardData and executeInline(KeyboardConfig): void). Real software
// keyboard UI is not yet wired; the stubs let the so load and, if a game
// opens the inline/normal keyboard applet, it proceeds with empty input.
object SoftwareKeyboard {
    @JvmStatic
    fun executeNormal(config: KeyboardConfig): KeyboardData =
        KeyboardData(result = "", ok = true, cancelled = false)

    @JvmStatic
    fun executeInline(config: KeyboardConfig) {}

    class KeyboardConfig {
        var maxTextLength: Int = 0
        var hint: String? = null
        var submitText: String? = null
        var isPassword: Boolean = false
        var allowLetters: Boolean = false
        var allowNumber: Boolean = false
        var text: String? = null
    }

    class KeyboardData(
        var result: String = "",
        var ok: Boolean = false,
        var cancelled: Boolean = false
    )
}
