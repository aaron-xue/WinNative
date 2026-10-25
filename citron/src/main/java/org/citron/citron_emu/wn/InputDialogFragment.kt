// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Faithful port of upstream citron's InputDialogFragment (the "press a button to map"
// popup). The upstream version is wired into a Fragment/ViewModel settings tree that
// WinNative does not have, so the controller-mapping plumbing is adapted to call
// NativeInput directly, but the capture flow (beginMapping -> onGamePadButtonEvent /
// onGamePadAxisEvent -> getNextInput -> stopMapping, deriving GUID/port from the event's
// own device), the dialog structure, and the animated button/stick indicators are
// copied as-is from the upstream.

package org.citron.citron_emu.wn

import android.app.Dialog
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import org.citron.citron_emu.R
import org.citron.citron_emu.features.input.NativeInput
import org.citron.citron_emu.features.input.model.InputType
import org.citron.citron_emu.features.input.model.NativeAnalog
import org.citron.citron_emu.features.input.model.NativeButton
import org.citron.citron_emu.utils.InputHandler
import org.citron.citron_emu.utils.NativeConfig
import org.citron.citron_emu.utils.ParamPackage

class InputDialogFragment : DialogFragment() {
    private var inputAccepted = false
    private var playerIndex = 0
    private var button: NativeButton? = null
    private var analog: NativeAnalog? = null
    private var analogDirection: String? = null
    private var dialogTitle = ""
    private var onSaved: (() -> Unit)? = null
    private var rootView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playerIndex = it.getInt(ARG_PLAYER, 0)
            button = if (it.containsKey(ARG_BUTTON)) NativeButton.from(it.getInt(ARG_BUTTON)) else null
            analog = if (it.containsKey(ARG_ANALOG)) NativeAnalog.from(it.getInt(ARG_ANALOG)) else null
            analogDirection = it.getString(ARG_DIR)
            dialogTitle = it.getString(ARG_TITLE, "映射")
        }
        InputHandler.updateControllerData()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val themedCtx = android.view.ContextThemeWrapper(requireContext(), R.style.Theme_WnCitronLoading)
        val view: View = LayoutInflater.from(themedCtx).inflate(R.layout.dialog_mapping, null)
        rootView = view

        val playAnimation = { twoDirections: Boolean ->
            val stickAnim: AnimatedVectorDrawable
            val buttonAnim: AnimatedVectorDrawable
            view.findViewById<View>(R.id.image_stick_animation).apply {
                val res = if (twoDirections) {
                    R.drawable.stick_two_direction_anim
                } else {
                    R.drawable.stick_one_direction_anim
                }
                setBackgroundResource(res)
                stickAnim = background as AnimatedVectorDrawable
            }
            view.findViewById<View>(R.id.image_button_animation).apply {
                setBackgroundResource(R.drawable.button_anim)
                buttonAnim = background as AnimatedVectorDrawable
            }
            stickAnim.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    buttonAnim.start()
                }
            })
            buttonAnim.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    stickAnim.start()
                }
            })
            stickAnim.start()
        }

        val builder = AlertDialog.Builder(themedCtx)
            .setPositiveButton(android.R.string.cancel) { _, _ -> NativeInput.stopMapping() }
            .setView(view)

        when {
            analog != null -> {
                builder.setTitle(getString(R.string.map_control, dialogTitle))
                    .setMessage(R.string.stick_map_description)
                playAnimation(true)
            }
            button != null -> {
                if (button == NativeButton.DUp || button == NativeButton.DDown ||
                    button == NativeButton.DLeft || button == NativeButton.DRight
                ) {
                    builder.setTitle(getString(R.string.map_dpad_direction, dialogTitle))
                } else {
                    builder.setTitle(getString(R.string.map_control, dialogTitle))
                }
                builder.setMessage(R.string.button_map_description)
                playAnimation(false)
            }
        }

        return builder.create()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = rootView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
        view.setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) v.requestFocus() }
        dialog?.setOnKeyListener { _, _, keyEvent -> onKeyEvent(keyEvent) }
        view.setOnGenericMotionListener { _, motionEvent -> onMotionEvent(motionEvent) }
        NativeInput.beginMapping(if (analog != null) InputType.Stick.int else InputType.Button.int)
    }

    private fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeInput.ButtonState.PRESSED
            KeyEvent.ACTION_UP -> NativeInput.ButtonState.RELEASED
            else -> return false
        }
        val controllerData =
            InputHandler.androidControllers[event.device.controllerNumber] ?: return false
        NativeInput.onGamePadButtonEvent(
            controllerData.getGUID(),
            controllerData.getPort(),
            event.keyCode,
            action,
        )
        onInputReceived(event.device)
        return true
    }

    private fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        val controllerData =
            InputHandler.androidControllers[event.device.controllerNumber] ?: return false
        event.device.motionRanges.forEach {
            NativeInput.onGamePadAxisEvent(
                controllerData.getGUID(),
                controllerData.getPort(),
                it.axis,
                event.getAxisValue(it.axis),
            )
        }
        onInputReceived(event.device)
        return true
    }

    private fun onInputReceived(device: InputDevice) {
        val params = ParamPackage(NativeInput.getNextInput())
        if (params.has("engine") && isInputAcceptable(params) && !inputAccepted) {
            inputAccepted = true
            setResult(params, device)
        }
    }

    private fun setResult(params: ParamPackage, device: InputDevice) {
        NativeInput.stopMapping()
        params.set("display", "${device.name} ${params.get("port", 0)}")
        when {
            analog != null -> {
                var analogParam = NativeInput.getStickParam(playerIndex, analog!!)
                analogParam = adjustAnalogParam(params, analogParam, analogDirection ?: "up")
                analogParam.set("invert_y", "-")
                NativeInput.setStickParam(playerIndex, analog!!, analogParam)
            }
            button != null -> {
                if ((button == NativeButton.DUp || button == NativeButton.DLeft) &&
                    params.has("axis")
                ) {
                    params.set("invert", "-")
                }
                NativeInput.setButtonParam(playerIndex, button!!, params)
            }
        }
        NativeConfig.saveGlobalConfig()
        NativeInput.reloadInputDevices()
        onSaved?.invoke()
        dismiss()
    }

    private fun adjustAnalogParam(
        inputParam: ParamPackage,
        analogParam: ParamPackage,
        buttonName: String,
    ): ParamPackage {
        if (inputParam.has("axis_x") && inputParam.has("axis_y")) {
            return inputParam
        }
        if (!analogParam.has("engine") || analogParam.has("axis_x") || analogParam.has("axis_y")) {
            analogParam.clear()
            analogParam.set("engine", "analog_from_button")
        }
        analogParam.set(buttonName, inputParam.serialize())
        return analogParam
    }

    private fun isInputAcceptable(params: ParamPackage): Boolean {
        return params.has("engine")
    }

    companion object {
        private const val ARG_PLAYER = "player"
        private const val ARG_BUTTON = "button"
        private const val ARG_ANALOG = "analog"
        private const val ARG_DIR = "dir"
        private const val ARG_TITLE = "title"

        fun newInstance(
            playerIndex: Int,
            button: NativeButton? = null,
            analog: NativeAnalog? = null,
            analogDirection: String? = null,
            title: String,
            onSaved: (() -> Unit)? = null,
        ): InputDialogFragment {
            val f = InputDialogFragment()
            f.onSaved = onSaved
            f.arguments = Bundle().apply {
                putInt(ARG_PLAYER, playerIndex)
                if (button != null) putInt(ARG_BUTTON, button.int)
                if (analog != null) putInt(ARG_ANALOG, analog.int)
                putString(ARG_DIR, analogDirection)
                putString(ARG_TITLE, title)
            }
            return f
        }
    }
}