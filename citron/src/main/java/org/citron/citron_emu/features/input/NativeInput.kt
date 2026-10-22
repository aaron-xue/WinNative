// Thin JNI bridge for Citron's input driver. Only the methods needed by the
// WinNative embedded launcher are declared; the package/class MUST stay
// `org.citron.citron_emu.features.input.NativeInput` to match the
// `Java_org_citron_citron_1emu_features_input_NativeInput_*` symbols exported by
// libcitron-android.so. Signatures copied from
// D:/workspace/emulator/src/android/app/src/main/java/org/citron/citron_emu/features/input/NativeInput.kt
package org.citron.citron_emu.features.input

import org.citron.citron_emu.features.input.model.ButtonName
import org.citron.citron_emu.features.input.model.InputType
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

    /**
     * Auto-maps a player's inputs to the given device (mirrors upstream citron so a
     * detected physical gamepad is actually bound to the emulated Player 1). Without
     * this, `onGamePadButtonEventByPort` injects into the native GamePad device but
     * the emulated controller has no mapping to read from it, so the gamepad is dead.
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

    // --- Input mapping / configuration (ported from upstream citron so the embedded
    // launcher can offer full controller setup: device-per-player binding, per-button
    // and per-stick rebinding, connect/disconnect and reload). These mirror the
    // Java_org_citron_citron_1emu_features_input_NativeInput_* symbols exported by
    // libcitron-android.so. ---

    /** Gameplay-free capture path used while binding: send a button by device GUID. */
    external fun onGamePadButtonEvent(guid: String, port: Int, buttonId: Int, action: Int)

    /** Gameplay-free capture path used while binding: send an axis by device GUID. */
    external fun onGamePadAxisEvent(guid: String, port: Int, axis: Int, value: Float)

    /** Tells the input subsystem to start listening for inputs to map. */
    external fun beginMapping(type: Int)

    /** Reads a captured input as a serialized ParamPackage. Call between begin/stopMapping. */
    external fun getNextInput(): String

    /** Tells the input subsystem to stop listening for inputs to map. */
    external fun stopMapping()

    fun getButtonParam(playerIndex: Int, button: NativeButton): ParamPackage =
        ParamPackage(getButtonParamImpl(playerIndex, button.int))

    private external fun getButtonParamImpl(playerIndex: Int, buttonId: Int): String

    fun setButtonParam(playerIndex: Int, button: NativeButton, param: ParamPackage) =
        setButtonParamImpl(playerIndex, button.int, param.serialize())

    private external fun setButtonParamImpl(playerIndex: Int, buttonId: Int, param: String)

    fun getStickParam(playerIndex: Int, stick: NativeAnalog): ParamPackage =
        ParamPackage(getStickParamImpl(playerIndex, stick.int))

    private external fun getStickParamImpl(playerIndex: Int, stickId: Int): String

    fun setStickParam(playerIndex: Int, stick: NativeAnalog, param: ParamPackage) =
        setStickParamImpl(playerIndex, stick.int, param.serialize())

    private external fun setStickParamImpl(playerIndex: Int, stickId: Int, param: String)

    fun getButtonName(param: ParamPackage): ButtonName =
        ButtonName.from(getButtonNameImpl(param.serialize()))

    private external fun getButtonNameImpl(param: String): Int

    fun getSupportedStyleTags(playerIndex: Int): List<NpadStyleIndex> =
        getSupportedStyleTagsImpl(playerIndex).map { NpadStyleIndex.from(it) }

    private external fun getSupportedStyleTagsImpl(playerIndex: Int): IntArray

    fun setStyleIndex(playerIndex: Int, style: NpadStyleIndex) =
        setStyleIndexImpl(playerIndex, style.int)

    private external fun setStyleIndexImpl(playerIndex: Int, styleIndex: Int)

    fun isController(params: ParamPackage): Boolean = isControllerImpl(params.serialize())

    private external fun isControllerImpl(params: String): Boolean

    external fun getIsConnected(playerIndex: Int): Boolean

    /**
     * Connects/disconnects a controller and keeps connection order intact. Mirrors
     * upstream: connecting player N also connects players 0..N; disconnecting N
     * keeps players 0..N-1 connected.
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

    external fun resetControllerMappings(playerIndex: Int)

    /** Reloads all input devices from Settings::values.players into HID Core. */
    external fun reloadInputDevices()
}
