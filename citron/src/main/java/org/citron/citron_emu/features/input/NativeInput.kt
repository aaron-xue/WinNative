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
import org.citron.citron_emu.utils.ParamPackage

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

    /**
     * Returns the [NpadStyleIndex] for the given port. The JNI symbol actually exported by
     * libcitron-android.so (built from the upstream emulator source) is `getStyleIndexImpl`;
     * the public `getStyleIndex` is a thin Kotlin wrapper. Declaring it directly as `external`
     * would look for a non-existent `..._getStyleIndex__I` symbol and throw `No implementation
     * found`.
     */
    fun getStyleIndex(playerIndex: Int): NpadStyleIndex =
        NpadStyleIndex.from(getStyleIndexImpl(playerIndex))

    private external fun getStyleIndexImpl(playerIndex: Int): Int

    /** Safe variant for the overlay so a missing native symbol can't crash input. */
    fun getStyleIndexSafe(index: Int): NpadStyleIndex =
        runCatching { getStyleIndex(index) }.getOrDefault(NpadStyleIndex.None)

    /**
     * Reads a button's current mapping param for a player. Used by the host to detect whether
     * Player 1 already has a binding (so we don't overwrite a user-configured controller).
     */
    fun getButtonParam(playerIndex: Int, button: NativeButton): ParamPackage =
        ParamPackage(getButtonParamImpl(playerIndex, button.int))

    private external fun getButtonParamImpl(playerIndex: Int, buttonId: Int): String

    /**
     * Auto-maps a player's inputs to the given device. Mirrors upstream citron so a detected
     * controller (or the on-screen overlay) is actually bound to the emulated Player 1; without
     * this the native GamePad device exists but the emulated controller has no mapping to read
     * from, so the gamepad (virtual or physical) is dead until the first input "wakes" it.
     */
    fun updateMappingsWithDefault(
        playerIndex: Int,
        deviceParams: ParamPackage,
        displayName: String,
    ) = updateMappingsWithDefaultImpl(playerIndex, deviceParams.serialize(), displayName)

    private external fun updateMappingsWithDefaultImpl(
        playerIndex: Int,
        deviceParams: String,
        displayName: String,
    )

    /**
     * Connects/disconnects a controller and keeps connection order intact. Mirrors upstream:
     * connecting player N also connects players 0..N; disconnecting N keeps players 0..N-1
     * connected. Used here to ensure Player 1 is live immediately after auto-mapping.
     */
    fun connectControllers(playerIndex: Int, connected: Boolean = true) {
        val connectedControllers = mutableListOf<Boolean>().apply {
            if (connected) {
                for (i in 0 until 8) add(i <= playerIndex)
            } else {
                for (i in 0 until 8) add(i < playerIndex)
            }
        }
        connectControllersImpl(connectedControllers.toBooleanArray())
    }

    private external fun connectControllersImpl(connected: BooleanArray)
}
