// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.citron.citron_emu.utils

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.jvm.Volatile
import org.citron.citron_emu.features.input.CitronInputOverlayDevice
import org.citron.citron_emu.features.input.CitronPhysicalDevice
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.utils.Log
import org.citron.citron_emu.utils.NativeConfig
import org.citron.citron_emu.utils.ParamPackage

/**
 * Routes Android input-device (physical gamepad) key/motion events into the emulated
 * controller, and registers detected controllers as Android controllers. Adapted from the
 * upstream emulator: external gamepad event dispatch uses the non-timed native variants so
 * we don't depend on the (debug-only) *Timed symbols.
 */
object InputHandler {
    @Volatile var androidControllers = mapOf<Int, CitronPhysicalDevice>()
    @Volatile var registeredControllers = mutableListOf<ParamPackage>()
    private val controllerStates = mutableMapOf<Int, ControllerInputState>()
    private var changedAxesScratch = IntArray(16)
    private var changedValuesScratch = FloatArray(16)

    /** Returns whether Android currently exposes a physical game controller. */
    fun hasPhysicalController(): Boolean = InputDevice.getDeviceIds().any { deviceId ->
        InputDevice.getDevice(deviceId)?.isGameController() == true
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            return true
        }

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeInput.ButtonState.PRESSED
            KeyEvent.ACTION_UP -> NativeInput.ButtonState.RELEASED
            else -> return false
        }

        val cn = event.device.controllerNumber
        var controllerData = androidControllers[cn]
        if (controllerData == null) {
            Log.debug("WnInput: key ${event.keyCode} cn=$cn no match, refreshing device list")
            updateControllerData()
            controllerData = androidControllers[cn]
                ?: androidControllers.values.firstOrNull { it.getDeviceId() == event.deviceId }
            if (controllerData == null) {
                Log.warning("WnInput: key ${event.keyCode} dev=${event.deviceId} cn=$cn NOT MAPPED (device not registered)")
                return false
            }
        }

        val inputState = controllerStates.getOrPut(cn) {
            ControllerInputState()
        }
        if (inputState.getButtonState(event.keyCode) == action) {
            return true
        }
        inputState.setButtonState(event.keyCode, action)

        Log.debug("WnInput: key ${event.keyCode} dev=${event.deviceId} cn=$cn port=${controllerData.getPort()} action=$action -> onGamePadButtonEventByPort")
        NativeInput.onGamePadButtonEventByPort(
            controllerData.getPort(),
            event.keyCode,
            action
        )
        return true
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val controllerNumber = device.controllerNumber
        var controllerData = androidControllers[controllerNumber]
        if (controllerData == null) {
            Log.debug("WnInput: motion cn=$controllerNumber no match, refreshing device list")
            updateControllerData()
            controllerData = androidControllers[controllerNumber]
                ?: androidControllers.values.firstOrNull { it.getDeviceId() == device.id }
            if (controllerData == null) return false
        }

        val axes = controllerData.getAxesForSource(event.source)
        if (axes.isEmpty()) {
            return true
        }

        val inputState = controllerStates.getOrPut(controllerNumber) {
            ControllerInputState()
        }
        ensureAxisScratchCapacity(axes.size)
        var changedCount = 0

        axes.forEach { axis ->
            val value = event.getAxisValue(axis)
            if (inputState.setAxisValueIfChanged(axis, value)) {
                changedAxesScratch[changedCount] = axis
                changedValuesScratch[changedCount] = value
                changedCount++
            }
        }

        if (changedCount > 0) {
            NativeInput.onGamePadAxisEventByPort(
                controllerData.getPort(),
                changedAxesScratch,
                changedValuesScratch,
                changedCount
            )
        }
        return true
    }

    fun releaseAllInputs() {
        NativeInput.resetGamePadInputState()
        controllerStates.clear()
    }

    private fun ensureAxisScratchCapacity(size: Int) {
        if (changedAxesScratch.size >= size) {
            return
        }

        changedAxesScratch = IntArray(size)
        changedValuesScratch = FloatArray(size)
    }

    fun getDevices(): Map<Int, CitronPhysicalDevice> {
        val deviceIds = InputDevice.getDeviceIds()
        val inputSettings = NativeConfig.getInputSettings(true)
        val gameDevices = mutableListOf<InputDevice>()
        for (id in deviceIds) {
            val dev = InputDevice.getDevice(id) ?: continue
            // A real gamepad must expose at least one analog stick axis. Android/MIUI also
            // registers purely virtual devices (e.g. "uinput-xiaomi", which reports a GAMEPAD
            // source and even a non-zero vendor GUID but has NO axes) as controllers; those
            // steal port 0 / Player 1 and cannot actually drive the game, so keep them out.
            if (dev.isGameController() && dev.motionRanges.isNotEmpty()) {
                gameDevices.add(dev)
            }
        }
        val gameControllerDeviceIds = mutableMapOf<Int, CitronPhysicalDevice>()
        gameDevices.forEachIndexed { index, dev ->
            val key = dev.controllerNumber
            if (!gameControllerDeviceIds.contains(key)) {
                val useVibrator = inputSettings.getOrNull(index)?.useSystemVibrator ?: false
                gameControllerDeviceIds[key] = CitronPhysicalDevice(dev, index, useVibrator)
                Log.info(
                    "WnCitron: gamepad #$key -> port $index: ${dev.name} " +
                        "(sources=0x${dev.sources.toString(16)}, guid=${dev.getGUID()}, " +
                        "axes=[${dev.motionRanges.joinToString { r -> r.axis.toString() }}])",
                )
            }
        }
        return gameControllerDeviceIds
    }

    fun updateControllerData() {
        releaseAllInputs()
        NativeInput.clearRegisteredControllers()
        androidControllers = getDevices()
        Log.info(
            "WnCitron: detected ${androidControllers.size} physical gamepad(s): " +
                androidControllers.values.joinToString { "${it.getName()} (port ${it.getPort()})" },
        )
        androidControllers.forEach {
            NativeInput.registerController(it.value)
        }

        // Register the input overlay on a dedicated port so player-1 vibrations work and
        // onOverlayButtonEvent routes to the right place.
        NativeInput.registerController(CitronInputOverlayDevice(androidControllers.isEmpty(), 100))
        registeredControllers.clear()
        NativeInput.getInputDevices().forEach {
            registeredControllers.add(ParamPackage(it))
        }
        registeredControllers.sortBy { it.get("port", 0) }
    }

    fun InputDevice.getGUID(): String = String.format("%016x%016x", productId, vendorId)

    private fun InputDevice.isGameController(): Boolean =
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private class ControllerInputState {
        private var buttonStates = IntArray(256) { UNKNOWN_BUTTON_STATE }
        private var axisValues = FloatArray(64)
        private var axisSeen = BooleanArray(64)

        fun getButtonState(button: Int): Int {
            if (button < 0 || button >= buttonStates.size) {
                return UNKNOWN_BUTTON_STATE
            }
            return buttonStates[button]
        }

        fun setButtonState(button: Int, state: Int) {
            if (button < 0) {
                return
            }
            ensureButtonCapacity(button)
            buttonStates[button] = state
        }

        fun setAxisValueIfChanged(axis: Int, value: Float): Boolean {
            if (axis < 0) {
                return false
            }
            ensureAxisCapacity(axis)

            if (axisSeen[axis] && axisValues[axis].toRawBits() == value.toRawBits()) {
                return false
            }

            axisSeen[axis] = true
            axisValues[axis] = value
            return true
        }

        private fun ensureButtonCapacity(button: Int) {
            if (button < buttonStates.size) {
                return
            }

            buttonStates = buttonStates.copyOf(button + 1).also {
                it.fill(UNKNOWN_BUTTON_STATE, buttonStates.size, it.size)
            }
        }

        private fun ensureAxisCapacity(axis: Int) {
            if (axis < axisValues.size) {
                return
            }

            val newSize = axis + 1
            axisValues = axisValues.copyOf(newSize)
            axisSeen = axisSeen.copyOf(newSize)
        }

        companion object {
            private const val UNKNOWN_BUTTON_STATE = -1
        }
    }
}
