package org.citron.citron_emu.applets.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

import org.citron.citron_emu.R

class SoftwareKeyboardView(
    context: Context,
    private val config: SoftwareKeyboard.KeyboardConfig,
    private val onResult: (Int, String) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val dpScale: Float = resources.displayMetrics.density

    private val colorBg = Color.argb(140, 0, 0, 0)
    private val colorSurface = Color.argb(255, 45, 45, 52)
    private val colorOnSurface = Color.argb(255, 230, 230, 235)
    private val colorOutline = Color.argb(120, 200, 200, 210)
    private val colorOutlineFocus = Color.argb(255, 187, 134, 252)
    private val colorInputBg = Color.argb(16, 255, 255, 255)
    private val colorOnInput = Color.argb(255, 230, 230, 235)
    private val colorHint = Color.argb(130, 200, 200, 210)
    private val colorError = Color.argb(255, 207, 102, 121)
    private val colorPrimary = Color.argb(255, 187, 134, 252)
    private val colorOnSurfaceSecondary = Color.argb(200, 200, 200, 210)
    private val colorDivider = Color.argb(30, 255, 255, 255)

    private val bgPaint = Paint().apply { color = colorBg }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorSurface
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOnSurface
        textSize = dp(20f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorHint
        textSize = dp(14f)
        typeface = Typeface.DEFAULT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val inputBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInputBg
    }
    private val inputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val inputTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOnInput
        textSize = dp(16f)
        typeface = Typeface.DEFAULT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorHint
        textSize = dp(16f)
        typeface = Typeface.DEFAULT
    }
    private val cursorPaint = Paint().apply {
        color = colorPrimary
        strokeWidth = dp(2f)
    }
    private val dividerPaint = Paint().apply { color = colorDivider; strokeWidth = dp(1f) }
    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnCancelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOnSurfaceSecondary
        textSize = dp(16f)
        typeface = Typeface.DEFAULT
    }
    private val btnConfirmTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorPrimary
        textSize = dp(16f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorError
        textSize = dp(12f)
        typeface = Typeface.DEFAULT
    }

    private var surfaceReady = false
    @Volatile private var inputText = ""
    @Volatile private var isFocused = false
    private var frameCount = 0
    private var pressState = 0

    private val okText = config.ok_text?.takeIf(String::isNotEmpty) ?: context.getString(R.string.swkbd_ok)
    private val cancelText = context.getString(R.string.swkbd_cancel)
    private val title = config.header_text?.takeIf(String::isNotEmpty) ?: context.getString(R.string.swkbd_title)
    private val subText = config.sub_text?.takeIf(String::isNotEmpty)
    private val guideText = config.guide_text?.takeIf(String::isNotEmpty) ?: ""

    private var panelW = 0f
    private var panelH = 0f
    private var panelX = 0f
    private var panelY = 0f
    private val panelRadius = dp(28f)

    private val okRect = RectF()
    private val cancelRect = RectF()
    private val inputRect = RectF()

    private var inputOutlineColor = colorOutline

    private var errorText: String? = null

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        isFocusableInTouchMode = true
        isFocusable = true
        inputText = config.initial_text.orEmpty()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        layoutRects()
        drawKeyboard()
        post {
            requestFocus()
            isFocused = true
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        layoutRects()
        drawKeyboard()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun layoutRects() {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val pad = dp(16f)
        val dialogPad = dp(16f)
        val titleH = dp(28f) + if (subText != null) dp(20f) else 0f
        val inputH = dp(56f)
        val errorH = if (errorText != null) dp(18f) + dp(4f) else 0f
        val btnAreaH = dp(52f)

        panelW = w.coerceAtMost(dp(360f)) - dialogPad * 2
        panelH = pad + titleH + pad + inputH + errorH + pad + btnAreaH
        panelX = (w - panelW - dialogPad * 2) / 2f
        panelY = (h - panelH) / 2f

        val contentX = panelX + dialogPad + pad
        val inputTop = panelY + pad + titleH + pad
        inputRect.set(contentX - dp(4f), inputTop, contentX + panelW - pad * 2 + dp(4f), inputTop + inputH)

        val btnTop = panelY + panelH - btnAreaH
        val halfW = panelW / 2f

        cancelRect.set(panelX + dialogPad, btnTop,
            panelX + dialogPad + halfW, btnTop + btnAreaH)
        okRect.set(panelX + dialogPad + halfW, btnTop,
            panelX + dialogPad + panelW, btnTop + btnAreaH)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = SoftwareKeyboard.getEditorInputType(config)
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = inputText.length
        outAttrs.initialSelEnd = inputText.length
        return KeyboardInputConnection(this, outAttrs)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    fun handleKeyPress(text: String) {
        errorText = null
        if (text.length == 1 && text[0] == '\n') {
            handleEnter()
            return
        }
        if (config.max_text_length > 0 && inputText.length >= config.max_text_length) return
        inputText += text
        layoutRects()
        drawKeyboard()
    }

    fun handleKeyDelete() {
        errorText = null
        if (inputText.isNotEmpty()) {
            inputText = inputText.substring(0, inputText.length - 1)
            drawKeyboard()
        }
    }

    private fun handleEnter() {
        if (config.enable_return_button) {
            if (config.max_text_length > 0 && inputText.length >= config.max_text_length) return
            inputText += "\n"
            drawKeyboard()
        } else {
            trySubmit()
        }
    }

    private fun trySubmit() {
        val minLen = config.min_text_length
        if (minLen > 0 && inputText.length < minLen) {
            errorText = context.getString(R.string.swkbd_min_length_error, minLen)
            layoutRects()
            drawKeyboard()
            return
        }
        submit()
    }

    private fun submit() {
        isFocused = false
        val im = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        im.hideSoftInputFromWindow(windowToken, 0)
        onResult(SoftwareKeyboard.SwkbdResult.Ok.ordinal, inputText)
    }

    private fun cancel() {
        isFocused = false
        val im = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        im.hideSoftInputFromWindow(windowToken, 0)
        onResult(SoftwareKeyboard.SwkbdResult.Cancel.ordinal, "")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                when {
                    okRect.contains(x, y) -> pressState = 2
                    cancelRect.contains(x, y) -> pressState = 1
                    inputRect.contains(x, y) -> {
                        requestFocus()
                        isFocused = true
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                    else -> {
                        isFocused = false
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(windowToken, 0)
                    }
                }
                drawKeyboard()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val x = event.x
                val y = event.y
                when {
                    pressState == 2 && okRect.contains(x, y) -> trySubmit()
                    pressState == 1 && cancelRect.contains(x, y) -> cancel()
                }
                pressState = 0
                drawKeyboard()
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            handleEnter()
            return true
        }
        val ch = event.unicodeChar
        if (ch != 0) {
            handleKeyPress(ch.toChar().toString())
        } else if (keyCode == KeyEvent.KEYCODE_DEL) {
            handleKeyDelete()
        }
        return true
    }

    fun drawKeyboard() {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        try {
            drawFrame(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFrame(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        frameCount++

        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (panelW <= 0) layoutRects()

        val dialogX = panelX
        val dialogY = panelY
        val dialogW = panelW + dp(16f) * 2

        canvas.drawRoundRect(RectF(dialogX, dialogY, dialogX + dialogW, dialogY + panelH), panelRadius, panelRadius, surfacePaint)

        val pad = dp(16f)
        val contentX = dialogX + dp(16f) + pad

        var curY = dialogY + pad

        canvas.drawText(title, contentX, curY + dp(22f), titlePaint)
        curY += dp(28f)

        if (subText != null) {
            canvas.drawText(subText!!, contentX, curY + dp(16f), subTitlePaint)
            curY += dp(20f)
        }

        curY += pad

        val inputLeft = contentX - dp(4f)
        val inputTop = curY
        val inputRight = inputLeft + (panelW - pad * 2 + dp(8f))
        val inputBottom = curY + dp(56f)

        val strokeColor = if (errorText != null) colorError
            else if (isFocused) colorOutlineFocus
            else inputOutlineColor

        inputStrokePaint.color = strokeColor
        inputBgPaint.color = colorInputBg

        val cornerRadius = dp(4f)
        canvas.drawRoundRect(RectF(inputLeft, inputTop, inputRight, inputBottom), cornerRadius, cornerRadius, inputBgPaint)
        canvas.drawRoundRect(RectF(inputLeft, inputTop, inputRight, inputBottom), cornerRadius, cornerRadius, inputStrokePaint)

        val innerPad = dp(12f)
        val textX = inputLeft + innerPad

        if (guideText.isNotEmpty() && inputText.isEmpty()) {
            labelPaint.color = if (isFocused) colorOutlineFocus else colorOutline
            canvas.drawText(guideText, textX, inputTop + dp(6f) + dp(10f), labelPaint)
        }

        var textY: Float
        if (inputText.isNotEmpty()) {
            if (guideText.isNotEmpty()) {
                labelPaint.color = colorOutlineFocus
                canvas.drawText(guideText, textX, inputTop + dp(6f) + dp(10f), labelPaint)
            }
            val textCenter = inputTop + inputBottom
            textY = (textCenter - (inputTextPaint.descent() + inputTextPaint.ascent()) / 2) / 2f
            if (guideText.isNotEmpty()) {
                textY = inputTop + dp(32f) + (dp(56f) - dp(32f) - (inputTextPaint.descent() - inputTextPaint.ascent())) / 2f
            }
            canvas.drawText(inputText, textX, textY, inputTextPaint)

            val showCursor = isFocused && (frameCount % 60 < 30)
            if (showCursor) {
                val cursorX = textX + inputTextPaint.measureText(inputText) + dp(1f)
                canvas.drawLine(
                    cursorX, inputTop + dp(14f),
                    cursorX, inputBottom - dp(14f),
                    cursorPaint
                )
            }
        } else {
            textY = (inputTop + inputBottom - (hintPaint.descent() + hintPaint.ascent())) / 2f
            canvas.drawText(guideText.ifEmpty { context.getString(R.string.swkbd_hint) }, textX, textY, hintPaint)
        }

        if (errorText != null) {
            canvas.drawText(errorText!!, inputLeft, inputBottom + dp(4f) + dp(14f), errorPaint)
        }

        val dividerY = panelY + panelH - dp(52f)
        canvas.drawLine(dialogX + dp(16f), dividerY, dialogX + dialogW - dp(16f), dividerY, dividerPaint)

        val btnTop = dividerY
        val btnH = dp(52f)
        val halfW = panelW / 2f
        val midX = dialogX + dp(16f) + halfW

        canvas.drawLine(midX, btnTop + dp(14f), midX, btnTop + btnH - dp(14f), dividerPaint)

        val cancelPressed = pressState == 1
        val okPressed = pressState == 2

        val btnRectL = RectF(dialogX + dp(16f), btnTop, midX, btnTop + btnH)
        val btnRectR = RectF(midX, btnTop, dialogX + dp(16f) + panelW, btnTop + btnH)

        btnBgPaint.color = if (cancelPressed) Color.argb(30, 255, 255, 255) else Color.TRANSPARENT
        canvas.drawRect(btnRectL, btnBgPaint)
        btnBgPaint.color = if (okPressed) Color.argb(30, 255, 255, 255) else Color.TRANSPARENT
        canvas.drawRect(btnRectR, btnBgPaint)

        val cancelTextW = btnCancelTextPaint.measureText(cancelText)
        canvas.drawText(cancelText, btnRectL.centerX() - cancelTextW / 2f,
            btnRectL.centerY() - (btnCancelTextPaint.descent() + btnCancelTextPaint.ascent()) / 2f, btnCancelTextPaint)

        val okTextW = btnConfirmTextPaint.measureText(okText)
        canvas.drawText(okText, btnRectR.centerX() - okTextW / 2f,
            btnRectR.centerY() - (btnConfirmTextPaint.descent() + btnConfirmTextPaint.ascent()) / 2f, btnConfirmTextPaint)

        postInvalidateDelayed(500)
    }

    private fun dp(value: Float): Float = value * dpScale
}

class KeyboardInputConnection(
    private val view: SoftwareKeyboardView,
    outAttrs: EditorInfo,
) : android.view.inputmethod.BaseInputConnection(view, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.toString()?.let { view.handleKeyPress(it) }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) view.handleKeyDelete()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            view.onKeyDown(event.keyCode, event)
        }
        return true
    }
}