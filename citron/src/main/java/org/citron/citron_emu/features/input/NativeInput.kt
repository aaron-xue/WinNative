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
import android.content.Context
import android.util.Log
import java.io.File

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

    /**
     * Handles button input events during input mapping (controller configuration dialog).
     * Uses GUID+port for device identification; the input mapper validates the GUID
     * against registered controllers before accepting the mapping.
     * @param guid 32 character hexadecimal string consisting of the controller's PID+VID.
     * @param port Port determined by controller connection order.
     * @param buttonId The Android Keycode corresponding to this event.
     * @param action Mask identifying which action is happening (button pressed down, or button released).
     */
    external fun onGamePadButtonEvent(
        guid: String,
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

    /**
     * Handles axis movement events during input mapping (controller configuration dialog).
     * Uses GUID+port for device identification; the input mapper validates the GUID
     * against registered controllers before accepting the mapping.
     * @param guid 32 character hexadecimal string consisting of the controller's PID+VID.
     * @param port Port determined by controller connection order.
     * @param axis The axis ID.
     * @param value Value along the given axis.
     */
    external fun onGamePadAxisEvent(guid: String, port: Int, axis: Int, value: Float)

    /**
     * Handles motion events from the phone's own sensors (gyroscope + accelerometer).
     * @param port Port determined by controller connection order
     * @param deltaTimestamp Time delta in microseconds since last event
     * @param xGyro The value of the x-axis for the gyroscope.
     * @param yGyro The value of the y-axis for the gyroscope.
     * @param zGyro The value of the z-axis for the gyroscope.
     * @param xAccel The value of the x-axis for the accelerometer.
     * @param yAccel The value of the y-axis for the accelerometer.
     * @param zAccel The value of the z-axis for the accelerometer.
     */
    external fun onDeviceMotionEvent(
        port: Int,
        deltaTimestamp: Long,
        xGyro: Float,
        yGyro: Float,
        zGyro: Float,
        xAccel: Float,
        yAccel: Float,
        zAccel: Float,
    )

    /**
     * Handles motion events from a physical gamepad's built-in sensors.
     */
    external fun onGamePadMotionEvent(
        guid: String,
        port: Int,
        deltaTimestamp: Long,
        xGyro: Float,
        yGyro: Float,
        zGyro: Float,
        xAccel: Float,
        yAccel: Float,
        zAccel: Float,
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
     * Reloads all input devices from the currently loaded Settings::values.players into HID Core.
     * Must be called after [setButtonParam] / [setStickParam] so the native HID driver
     * re-reads the updated button/stick mappings.
     */
    external fun reloadInputDevices()

    /**
     * Checks if a controller is connected.
     * @param playerIndex Index of the player to check.
     * @return Whether the player is connected.
     */
    external fun getIsConnected(playerIndex: Int): Boolean

    /**
     * Tells the input subsystem to start listening for inputs to map.
     * @param type Type of input to map as shown by the int property in each [InputType].
     */
    external fun beginMapping(type: Int)

    /**
     * Gets an input's [ParamPackage] as a serialized string. Used for input verification before mapping.
     * Must be run after [beginMapping] and before [stopMapping].
     */
    external fun getNextInput(): String

    /**
     * Tells the input subsystem to stop listening for inputs to map.
     */
    external fun stopMapping()

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
     * Sets the mapping param for a specific button.
     * @param playerIndex Index of the player to set params for.
     * @param button The [NativeButton] to set params for.
     * @param param A [ParamPackage] to set.
     */
    fun setButtonParam(playerIndex: Int, button: NativeButton, param: ParamPackage) =
        setButtonParamImpl(playerIndex, button.int, param.serialize())

    private external fun setButtonParamImpl(playerIndex: Int, buttonId: Int, param: String)

    /**
     * Gets the params for a specific stick.
     * @param playerIndex Index of the player to get params from.
     * @param stick The [NativeAnalog] to get params for.
     * @return A [ParamPackage] representing a player's specific stick.
     */
    fun getStickParam(playerIndex: Int, stick: NativeAnalog): ParamPackage =
        ParamPackage(getStickParamImpl(playerIndex, stick.int))

    private external fun getStickParamImpl(playerIndex: Int, stickId: Int): String

    /**
     * Sets the params for a specific stick.
     * @param playerIndex Index of the player to set params for.
     * @param stick The [NativeAnalog] to set params for.
     * @param param A [ParamPackage] to set.
     */
    fun setStickParam(playerIndex: Int, stick: NativeAnalog, param: ParamPackage) =
        setStickParamImpl(playerIndex, stick.int, param.serialize())

    private external fun setStickParamImpl(playerIndex: Int, stickId: Int, param: String)

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

    const val Player2Device = 1
    const val Player3Device = 2
    const val Player4Device = 3
    const val Player5Device = 4
    const val Player6Device = 5
    const val Player7Device = 6
    const val Player8Device = 7
    const val ConsoleDevice = 8

    fun getSupportedStyleTags(playerIndex: Int): List<NpadStyleIndex> =
        getSupportedStyleTagsImpl(playerIndex).map { NpadStyleIndex.from(it) }

    private external fun getSupportedStyleTagsImpl(playerIndex: Int): IntArray

    fun setStyleIndex(playerIndex: Int, style: NpadStyleIndex) =
        setStyleIndexImpl(playerIndex, style.int)

    private external fun setStyleIndexImpl(playerIndex: Int, styleIndex: Int)

    fun isController(params: ParamPackage): Boolean = isControllerImpl(params.serialize())

    private external fun isControllerImpl(params: String): Boolean

    fun getButtonName(param: ParamPackage): ButtonName =
        ButtonName.from(getButtonNameImpl(param.serialize()))

    private external fun getButtonNameImpl(param: String): Int

    external fun loadInputProfiles()

    external fun getInputProfileNames(): Array<String>

    external fun isProfileNameValid(name: String): Boolean

    external fun createProfile(name: String, playerIndex: Int): Boolean

    external fun deleteProfile(name: String, playerIndex: Int): Boolean

    external fun loadProfile(name: String, playerIndex: Int): Boolean

    external fun saveProfile(name: String, playerIndex: Int): Boolean

    external fun resetControllerMappings(playerIndex: Int)

    /**
     * Binds libcitron-android.so into this object's ClassLoader so the external
     * methods above resolve. The core lib is loaded by CitronBootstrap into the
     * host ClassLoader; if NativeInput lives in a different (feature/plugin)
     * ClassLoader, those externals would throw "No implementation found" without
     * this explicit binding.
     */
    fun loadSelf(context: Context) {
        val dir = findLibDir(context)
        val file = File(dir, "libcitron-android.so")
        if (!file.exists()) {
            Log.w("NativeInput", "loadSelf: $file missing - NativeInput JNI will not resolve")
            return
        }
        file.setReadable(true)
        file.setExecutable(true)
        val path = file.absolutePath
        val loader = NativeInput::class.java.classLoader
        Log.i("NativeInput", "loadSelf: binding core lib into loader=$loader")
        runCatching {
            val runtime = Runtime.getRuntime()
            val m = Runtime::class.java.getDeclaredMethod(
                "load0", ClassLoader::class.java, String::class.java
            ).also { it.isAccessible = true }
            m.invoke(runtime, loader, path)
            Log.i("NativeInput", "loadSelf: load0 succeeded - NativeInput CL now owns the lib")
        }.onFailure { e ->
            Log.w("NativeInput", "loadSelf: load0 failed ($e), trying System.load", e)
            runCatching { System.load(path) }
                .onSuccess { Log.i("NativeInput", "loadSelf: System.load fallback succeeded") }
                .onFailure { Log.e("NativeInput", "loadSelf: System.load fallback also failed", it) }
        }
    }

    private fun findLibDir(context: Context): File {
        val root = File(context.filesDir, "retro/bundle")
        val candidates = listOf(
            File(root, "data/citron/jniLibs"),
            File(root, "citron/jniLibs"),
            File(root, "data/citron/lib"),
        )
        for (c in candidates) {
            if (File(c, "libcitron-android.so").exists()) return c
        }
        if (root.isDirectory) {
            root.walkTopDown().maxDepth(5)
                .firstOrNull { it.name == "libcitron-android.so" }
                ?.parentFile
                ?.let { return it }
        }
        return File(context.applicationInfo.nativeLibraryDir)
    }
}