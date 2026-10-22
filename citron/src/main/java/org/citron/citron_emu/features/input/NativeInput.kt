// Thin JNI bridge for Citron's input driver. Only the methods needed by the
// WinNative embedded launcher are declared; the package/class MUST stay
// `org.citron.citron_emu.features.input.NativeInput` to match the
// `Java_org_citron_citron_1emu_features_input_NativeInput_*` symbols exported by
// libcitron-android.so. Signatures copied from
// D:/workspace/emulator/src/android/app/src/main/java/org/citron/citron_emu/features/input/NativeInput.kt
package org.citron.citron_emu.features.input

import org.citron.citron_emu.features.input.model.NativeAnalog
import org.citron.citron_emu.features.input.model.NativeButton
import org.citron.citron_emu.features.input.model.NpadStyleIndex

object NativeInput {
    object ButtonState {
        const val RELEASED = 0
        const val PRESSED = 1
    }

    const val Player1Device = 0

    // buttonId is the Android KeyEvent keycode (KEYCODE_BUTTON_A, etc.).
    external fun onGamePadButtonEventByPortTimed(
        port: Int,
        buttonId: Int,
        action: Int,
        dispatchStartNs: Long,
    )

    external fun onGamePadButtonEventByPort(
        port: Int,
        buttonId: Int,
        action: Int,
    )

    // axes/values are parallel arrays of Android MotionEvent axis ids + floats.
    external fun onGamePadAxisEventByPort(
        port: Int,
        axes: IntArray,
        values: FloatArray,
        count: Int,
    )

    external fun resetGamePadInputState()

    // --- Raw touchscreen passthrough (mechanism B) ---
    // Forwards finger coordinates to the emulated Switch touchscreen device, so
    // touch-native games see real touches. Coordinates are in pixels relative to
    // the overlay/surface (the native side scales them to the touch panel).
    // Signatures copied from
    // D:/workspace/emulator/src/android/app/src/main/java/org/citron/citron_emu/features/input/NativeInput.kt

    /** @param fingerId Stable per-finger id (use the Android pointer id). */
    external fun onTouchPressed(fingerId: Int, xAxis: Float, yAxis: Float)

    external fun onTouchMoved(fingerId: Int, xAxis: Float, yAxis: Float)

    external fun onTouchReleased(fingerId: Int)

    // Registers an on-screen/overlay input device so that onGamePadButtonEventByPortTimed /
    // onGamePadAxisEventByPort have a target at the given port. Without this, button and
    // axis events for an unregistered port are silently dropped by the native driver.
    external fun registerController(device: CitronInputDevice)

    external fun clearRegisteredControllers()

    // --- Overlay (VirtualGamepad) input paths, used by the on-screen InputOverlay ---
    // These mirror the upstream emulator so the same overlay code runs unmodified.
    // `button`/`stick` are the citron NativeButton / NativeAnalog enums.
    fun onOverlayButtonEvent(port: Int, button: NativeButton, action: Int) =
        onOverlayButtonEventImpl(port, button.int, action)

    private external fun onOverlayButtonEventImpl(port: Int, buttonId: Int, action: Int)

    fun onOverlayJoystickEvent(port: Int, stick: NativeAnalog, xAxis: Float, yAxis: Float) =
        onOverlayJoystickEventImpl(port, stick.int, xAxis, yAxis)

    private external fun onOverlayJoystickEventImpl(port: Int, stickId: Int, xAxis: Float, yAxis: Float)

    /** Enumerates connected Android input device ids (as strings). */
    external fun getInputDevices(): Array<String>

    /** Returns the controller style (handheld vs pro/etc.) for the given port index. */
    external fun getStyleIndex(index: Int): NpadStyleIndex

    /** Safe variant for the overlay so a missing native symbol can't crash input. */
    fun getStyleIndexSafe(index: Int): NpadStyleIndex =
        runCatching { getStyleIndex(index) }.getOrDefault(NpadStyleIndex.None)
}
