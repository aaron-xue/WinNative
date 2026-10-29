package org.citron.citron_emu.applets.keyboard

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.core.view.ViewCompat
import java.io.Serializable
import org.citron.citron_emu.NativeLibrary

@Keep
object SoftwareKeyboard {

    lateinit var data: KeyboardData
    val dataLock = Object()

    @Volatile
    private var inlineConfig: KeyboardConfig? = null

    @Volatile
    var isNormalActive = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun executeNormalImpl(config: KeyboardConfig) {
        val activity = NativeLibrary.sEmulationActivity.get() as? Activity ?: return

        data = KeyboardData(SwkbdResult.Cancel.ordinal, "")

        lateinit var viewRef: SoftwareKeyboardView
        viewRef = SoftwareKeyboardView(activity, config) { result, text ->
            data = KeyboardData(result, text)
            activity.runOnUiThread {
                val decor = activity.window.decorView as ViewGroup
                decor.removeView(viewRef)
                isNormalActive = false
            }
            synchronized(dataLock) { dataLock.notifyAll() }
        }

        activity.runOnUiThread {
            val decor = activity.window.decorView as ViewGroup
            decor.addView(
                viewRef,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            isNormalActive = true
        }
    }

    private fun executeInlineImpl(config: KeyboardConfig) {
        val activity = NativeLibrary.sEmulationActivity.get() as? Activity ?: return
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?: return

        inlineConfig = config

        activity.runOnUiThread {
            val editText = EditText(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.TRANSPARENT)
                setCursorVisible(false)
                isFocusableInTouchMode = true
                isFocusable = true
                layoutParams = FrameLayout.LayoutParams(1, 1).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                }
                if (!config.initial_text.isNullOrEmpty()) {
                    setText(config.initial_text)
                    setSelection(config.initial_text!!.length)
                }
                inputType = getEditorInputType(config)
                if (config.max_text_length > 0) {
                    filters = arrayOf(InputFilter.LengthFilter(config.max_text_length))
                }
            }
            root.addView(editText)

            editText.requestFocus()
            val imm =
                editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.restartInput(editText)
            @Suppress("DEPRECATION")
            imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)

            startKeyboardDismissPolling(root, editText)
        }
    }

    private fun startKeyboardDismissPolling(root: ViewGroup, editText: View) {
        val delayMs = 500L
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val insets = ViewCompat.getRootWindowInsets(root)
                val isKeyboardVisible = insets?.isVisible(WindowInsets.Type.ime()) == true
                if (isKeyboardVisible) {
                    mainHandler.postDelayed(this, delayMs)
                    return
                }
                root.post { root.removeView(editText) }
                NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_BACK)
                clearInlineConfig()
            }
        }, delayMs)
    }

    fun getInlineInitialText(): String = inlineConfig?.initial_text.orEmpty()

    fun getInlineInitialCursorPosition(): Int = inlineConfig?.initial_cursor_position ?: 0

    fun getInlineEditorInputType(): Int =
        inlineConfig?.let(::getEditorInputType) ?: InputType.TYPE_CLASS_TEXT

    fun getEditorInputType(config: KeyboardConfig): Int {
        val passwordEnabled = config.password_mode == SwkbdPasswordMode.Enabled.ordinal
        return when (config.type) {
            SwkbdType.NumberPad.ordinal -> {
                InputType.TYPE_CLASS_NUMBER or
                    if (passwordEnabled) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0
            }
            else -> {
                InputType.TYPE_CLASS_TEXT or
                    if (passwordEnabled) {
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_TEXT_VARIATION_NORMAL
                    }
            }
        }
    }

    fun clearInlineConfig() {
        inlineConfig = null
    }

    val isInlineActive: Boolean get() = inlineConfig != null

    fun cancelNormal() {
        if (!isNormalActive) return
        isNormalActive = false
        data = KeyboardData(SwkbdResult.Cancel.ordinal, "")
        synchronized(dataLock) { dataLock.notifyAll() }
        val activity = NativeLibrary.sEmulationActivity.get() as? Activity ?: return
        activity.runOnUiThread {
            val decor = activity.window.decorView as? ViewGroup ?: return@runOnUiThread
            for (i in decor.childCount - 1 downTo 0) {
                if (decor.getChildAt(i) is SoftwareKeyboardView) {
                    decor.removeViewAt(i)
                }
            }
        }
    }

    @JvmStatic
    fun executeNormal(config: KeyboardConfig): KeyboardData {
        NativeLibrary.sEmulationActivity.get()?.let { a ->
            (a as? Activity)?.runOnUiThread { executeNormalImpl(config) }
        }

        synchronized(dataLock) {
            dataLock.wait()
        }
        return data
    }

    @JvmStatic
    fun executeInline(config: KeyboardConfig) {
        NativeLibrary.sEmulationActivity.get()?.let { a ->
            (a as? Activity)?.runOnUiThread { executeInlineImpl(config) }
        }
    }

    enum class SwkbdType {
        Normal,
        NumberPad,
        QWERTY,
        Unknown,
        SimplifiedChinese,
        TraditionalChinese,
        Korean
    }

    enum class SwkbdPasswordMode {
        Disabled,
        Enabled
    }

    enum class SwkbdResult {
        Ok,
        Cancel
    }

    @Keep
    data class KeyboardConfig(
        var ok_text: String? = null,
        var header_text: String? = null,
        var sub_text: String? = null,
        var guide_text: String? = null,
        var initial_text: String? = null,
        var left_optional_symbol_key: Short = 0,
        var right_optional_symbol_key: Short = 0,
        var max_text_length: Int = 0,
        var min_text_length: Int = 0,
        var initial_cursor_position: Int = 0,
        var type: Int = 0,
        var password_mode: Int = 0,
        var text_draw_type: Int = 0,
        var key_disable_flags: Int = 0,
        var use_blur_background: Boolean = false,
        var enable_backspace_button: Boolean = false,
        var enable_return_button: Boolean = false,
        var disable_cancel_button: Boolean = false
    ) : Serializable

    @Keep
    data class KeyboardData(var result: Int, var text: String)
}