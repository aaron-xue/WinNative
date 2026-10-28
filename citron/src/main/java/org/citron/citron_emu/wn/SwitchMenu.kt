package org.citron.citron_emu.wn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.abs
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.R

// Switch-specific in-emulator side menu, drawn on its own SurfaceView with
// setZOrderOnTop(true) + TRANSLUCENT — the same reliable-visibility trick used by
// SwitchPerfHud. The Compose RetroDrawerMenu does not reliably appear over the
// emulated game's SurfaceView, so citron renders its own menu here instead.
//
// Style / content / interaction are copied from the reference citron emulator's
// in-game menu: res/menu/menu_in_game.xml + header_in_game.xml + the NavigationView
// in fragment_emulation.xml. I.e. a left Material navigation drawer with a
// game-title header and the same items in the same order. Opened by WnCitronActivity's
// back / guide-button handling (which also pauses emulation); closed by back, a tap
// outside the panel, or selecting Exit.
class SwitchMenuView(context: Context, private val activity: Activity) :
    SurfaceView(context), SurfaceHolder.Callback {

    data class Item(
        val label: String,
        val danger: Boolean = false,
        val iconResId: Int = 0,
        var run: () -> Unit = {},
    )

    @Volatile private var surfaceReady = false
    @Volatile var visible = false
    private var selected = 0
    private var scrollY = 0f
    private var axisDir = 0
    // The menu opens while emulation is paused, so the Pause entry shows "Resume" (matching the
    // reference, whose menu_pause_emulation label flips to Resume when running).
    private var paused = true

    // Tracks whether the on-screen controller overlay is currently visible, so the menu
    // can show the matching icon (ic_controller_off / ic_controller) and label.
    var inputOverlayVisible = true

    // Slide animation: 0 = panel at rest, -1 = fully off-screen to the left.
    private var slideOffset = 0f
    private var openAnimator: ValueAnimator? = null
    private var closeAnimator: ValueAnimator? = null

    // Batch redraws to once per vsync so touch scrolling stays smooth.
    private var drawScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        drawScheduled = false
        performDraw()
    }

    // Called when the menu wants to close itself (tap outside / Exit). The Activity wires this
    // to its own closeMenu() so the menuShown flag and pause state stay consistent.
    var onRequestClose: (() -> Unit)? = null
    // Toggles the on-screen gamepad (Input Overlay). Wired by the Activity.
    var onToggleInputOverlay: (() -> Unit)? = null
    private var gameTitle: String? = null

    // Items, in the exact order of res/menu/menu_in_game.xml. Labels come from strings.xml.
    private val items = listOf(
        Item(context.getString(R.string.menu_resume), iconResId = R.drawable.ic_pause),        // menu_pause_emulation
        Item(context.getString(R.string.menu_settings), iconResId = R.drawable.ic_settings),      // menu_settings
        Item(context.getString(R.string.menu_controls), iconResId = R.drawable.ic_controller),      // menu_controls
        Item(context.getString(R.string.menu_show_controller), iconResId = R.drawable.ic_controller), // menu_overlay_controls
        Item(context.getString(R.string.menu_amiibo), iconResId = R.drawable.ic_nfc),        // menu_amiibo
        Item(context.getString(R.string.menu_exit), danger = true, iconResId = R.drawable.ic_exit), // menu_exit
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val notSupported = { toast(context.getString(R.string.menu_not_supported)) }

    private val touchListener = View.OnTouchListener { _, ev ->
        if (!visible) return@OnTouchListener false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                lastY = ev.y
                downIdx = rowAt(ev.y)
                dragAccum = 0f
                dragFromDown = 0f
                moved = false
                drawMenu()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY
                lastY = ev.y
                dragAccum += dy
                dragFromDown += dy
                if (abs(dragFromDown) > dp(8f)) {
                    moved = true
                    pressedIdx = -1
                    drawMenu()
                }
                if (maxScroll() > 0f) {
                    // List overflows the screen → dragging scrolls the content.
                    scrollY -= dy
                    clampScroll()
                    drawMenu()
                } else if (abs(dragAccum) >= rowH() / 2f) {
                    // Everything fits → a vertical drag moves the selection (swipe to navigate).
                    // Don't show pressed highlight during swipe – only update the logical selection.
                    selected = (selected + (if (dragAccum >= 0) 1 else -1) + items.size) % items.size
                    ensureVisible(selected)
                    dragAccum = 0f
                    drawMenu()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                // Tap (no drag): activate the row if inside the panel; close if outside it.
                // Tapping the header area keeps the menu open.
                if (!moved) {
                    val l = panelLeft()
                    val inPanel = ev.x >= l && ev.x <= l + panelWidth()
                    if (inPanel) {
                        if (downIdx >= 0) {
                            selected = downIdx
                            activate(items[downIdx])
                            pressedIdx = downIdx
                            drawMenu() // refresh transient highlight / dynamic labels
                            removeCallbacks(clearPressRunnable)
                            postDelayed(clearPressRunnable, 120)
                        }
                    } else {
                        // Tap outside the panel closes the drawer.
                        onRequestClose?.invoke()
                    }
                }
                true
            }
            else -> true
        }
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setBackgroundColor(0)
        isClickable = false
        isFocusable = false
        setOnTouchListener(touchListener)

        // Pause/Resume, Input Overlay (toggles the on-screen gamepad) and Exit are
        // functional in the embedded shell. The remaining items mirror the reference menu but have
        // no equivalent host UI here, so they surface a hint toast.
        items[0].run = { togglePause() }
        items[1].run = { CitronHost.openSettings?.invoke(activity) }
        items[3].run = {
            inputOverlayVisible = !inputOverlayVisible
            onToggleInputOverlay?.invoke() ?: notSupported()
        }
        items[5].run = { onRequestClose?.invoke(); activity.finish() }
        items[2].run = notSupported
        items[4].run = notSupported
    }

    fun setGameTitle(title: String?) {
        gameTitle = title?.takeIf { it.isNotBlank() }
        drawMenu()
    }

    private fun dp(v: Float) =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics,
        )

    fun open() {
        cancelPendingDraw()
        closeAnimator?.cancel()
        openAnimator?.cancel()
        visible = true
        isClickable = true
        pressedIdx = -1
        paused = true
        selected = 0
        scrollY = 0f
        // If not interrupting a close animation, force the panel off-screen so it always slides in.
        if (closeAnimator == null) slideOffset = -1f
        openAnimator = ValueAnimator.ofFloat(slideOffset, 0f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                slideOffset = it.animatedValue as Float
                requestDraw()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slideOffset = 0f
                    openAnimator = null
                    requestDraw()
                }
            })
            start()
        }
    }

    fun close() {
        cancelPendingDraw()
        openAnimator?.cancel()
        closeAnimator?.cancel()
        isClickable = false
        closeAnimator = ValueAnimator.ofFloat(slideOffset, -1f).apply {
            duration = 200
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                slideOffset = it.animatedValue as Float
                requestDraw()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slideOffset = -1f
                    closeAnimator = null
                    visible = false
                    performDraw()
                }
            })
            start()
        }
    }

    private fun togglePause() {
        paused = !paused
        runCatching {
            if (paused) NativeLibrary.pauseEmulation() else NativeLibrary.unpauseEmulation()
        }
        drawMenu()
    }

    fun handleKey(keyCode: Int, action: Int): Boolean {
        if (!visible) return false
        if (action != KeyEvent.ACTION_DOWN) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveSel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveSel(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                activate(items[selected])
                pressedIdx = selected
                drawMenu()
                removeCallbacks(clearPressRunnable)
                postDelayed(clearPressRunnable, 120)
                return true
            }
            KeyEvent.KEYCODE_BACK -> { onRequestClose?.invoke(); return true }
            else -> return true
        }
    }

    fun handleAxis(x: Float, y: Float): Boolean {
        if (!visible) return false
        val dir = when {
            y > 0.6f -> 1
            y < -0.6f -> -1
            else -> 0
        }
        if (dir != 0 && dir != axisDir) { moveSel(dir); axisDir = dir }
        else if (dir == 0) axisDir = 0
        return true
    }

    private fun moveSel(d: Int) {
        selected = (selected + d + items.size) % items.size
        ensureVisible(selected)
        pressedIdx = selected
        drawMenu()
        removeCallbacks(clearPressRunnable)
        postDelayed(clearPressRunnable, 120)
    }

    private fun areaTop() = dp(80f) + dp(4f)
    private fun rowH() = dp(48f)
    private fun panelWidth() = dp(280f)

    // Left edge of the panel: flush to the screen, shifted right only by the camera/notch
    // safe inset so the menu hugs the left edge while never drawing under the cutout.
    private fun panelLeft(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = rootWindowInsets?.displayCutout
            if (cutout != null) return cutout.safeInsetLeft.toFloat()
        }
        return 0f
    }

    // How far the list can scroll (0 when everything fits on screen).
    private fun maxScroll(): Float {
        val total = items.size * rowH()
        val avail = height - areaTop()
        return if (total - avail > 0f) total - avail else 0f
    }

    private fun clampScroll() {
        val m = maxScroll()
        if (scrollY < 0f) scrollY = 0f
        if (scrollY > m) scrollY = m
    }

    // Keep item i within the visible content area by adjusting scrollY.
    private fun ensureVisible(i: Int) {
        val top = areaTop()
        val rh = rowH()
        val avail = height - top
        val rowTop = i * rh - scrollY
        scrollY = when {
            rowTop < 0f -> i * rh
            rowTop + rh > avail -> (i * rh + rh - avail).coerceAtLeast(0f)
            else -> scrollY
        }
        clampScroll()
    }

    private fun activate(item: Item) = item.run.invoke()

    private fun toast(msg: String) = activity.runOnUiThread {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawMenu()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = drawMenu()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        cancelPendingDraw()
        openAnimator?.cancel()
        closeAnimator?.cancel()
        openAnimator = null
        closeAnimator = null
    }

    // Resolve the host app's palette at runtime so the menu follows the current app theme.
    // The activity runs with a system fullscreen theme and the application theme is light, so
    // neither carries the dark tokens directly; instead we read the host's AppTheme.Dark style
    // attributes via a ContextThemeWrapper. citron is a library merged into the host package, so
    // the style/attr names resolve from the host's resources. The emulator shell is always dark.
    private data class Palette(
        val bg: Int,
        val text: Int,
        val textSel: Int,
        val selBg: Int,
        val divider: Int,
        val danger: Int,
        val accent: Int,
    )

    private var paletteCache: Palette? = null

    private fun themeColor(attrName: String, androidAttr: Boolean, fallback: Int): Int {
        val res = resources
        val pkg = context.packageName
        val styleId = res.getIdentifier("AppTheme.Dark", "style", pkg)
        if (styleId == 0) return fallback
        val ctw = ContextThemeWrapper(context.applicationContext, styleId)
        val attrId = if (androidAttr) {
            res.getIdentifier(attrName, "attr", "android")
        } else {
            res.getIdentifier(attrName, "attr", pkg)
        }
        if (attrId == 0) return fallback
        val tv = TypedValue()
        if (!ctw.theme.resolveAttribute(attrId, tv, true)) return fallback
        return if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            tv.data
        } else {
            fallback
        }
    }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) < 140f
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun palette(): Palette {
        paletteCache?.let { return it }
        // Read from the host's dark theme; fall back to dark literals if unresolved.
        val bg = themeColor("colorBackground", true, 0xFF1C1C1C.toInt())
        val accent = themeColor("colorAccent", false, 0xFF80D8FF.toInt())
        val danger = themeColor("colorError", false, 0xFFCF6679.toInt())
        // Text: high contrast against the surface; selected text uses the accent.
        val text = if (isDark(bg)) 0xFFE1E1E1.toInt() else 0xFF212121.toInt()
        val p = Palette(
            bg = bg,
            text = text,
            textSel = accent,
            selBg = withAlpha(accent, 0x33),
            divider = withAlpha(text, 0x33),
            danger = danger,
            accent = accent,
        )
        paletteCache = p
        return p
    }

    private fun requestDraw() {
        if (!drawScheduled) {
            drawScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun cancelPendingDraw() {
        if (drawScheduled) {
            drawScheduled = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    private fun performDraw() {
        if (!surfaceReady) return
        val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
        try {
            @Suppress("DEPRECATION")
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            if (!visible) return

            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            val panelW = panelWidth()
            val left = panelLeft()
            val pal = palette()

            val offset = slideOffset * panelW

            // Dim the game behind the drawer, fading with the slide.
            val dimAlpha = ((1f + slideOffset) * 0x40).toInt().coerceIn(0, 0x40)
            canvas.drawRect(0f, 0f, w, h, Paint().apply { color = dimAlpha shl 24 })

            // Panel background offset by the slide amount.
            canvas.drawRect(left + offset, 0f, left + offset + panelW, h, Paint().apply { color = pal.bg })

            // Header
            val headerH = dp(80f)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = dp(20f)
            paint.color = pal.text
            val hfm = paint.fontMetrics
            val title = gameTitle ?: activity.getString(R.string.wn_loading_title_fallback)
            val hBase = dp(40f) - (hfm.descent - hfm.ascent) / 2f - hfm.ascent
            canvas.drawText(title, left + offset + dp(16f), hBase, paint)
            canvas.drawLine(left + offset, headerH, left + offset + panelW, headerH, Paint().apply { color = pal.divider; strokeWidth = 1f })

            // Items
            val rh = rowH()
            val top = areaTop()
            paint.textSize = dp(15f)
            val fm = paint.fontMetrics
            val textH = fm.descent - fm.ascent
            canvas.save()
            canvas.clipRect(left + offset, top, left + offset + panelW, h)
            for (i in items.indices) {
                val rowTop = top + i * rh - scrollY
                if (rowTop + rh < top || rowTop > h) continue
                val label = if (i == 0) {
                    context.getString(if (paused) R.string.menu_resume else R.string.menu_pause)
                } else if (i == 4) {
                    context.getString(if (inputOverlayVisible) R.string.menu_hide_controller else R.string.menu_show_controller)
                } else {
                    items[i].label
                }
                if (i == pressedIdx) {
                    canvas.drawRoundRect(
                        RectF(left + offset + dp(4f), rowTop + dp(4f), left + offset + panelW - dp(4f), rowTop + rh - dp(4f)),
                        dp(16f), dp(16f),
                        Paint().apply { color = pal.selBg },
                    )
                }
                paint.color = when {
                    i == pressedIdx -> pal.textSel
                    items[i].danger -> pal.danger
                    else -> pal.text
                }
                val iconRes = when {
                    i == 0 && paused -> R.drawable.ic_play
                    i == 4 && inputOverlayVisible -> R.drawable.ic_controller_off
                    else -> items[i].iconResId
                }
                val iconD = getIconDrawable(iconRes, paint.color)
                if (iconD != null) {
                    val ix = left + offset + dp(16f)
                    val iy = rowTop + (rh - iconSize) / 2f
                    iconD.setBounds(ix.toInt(), iy.toInt(), (ix + iconSize).toInt(), (iy + iconSize).toInt())
                    iconD.draw(canvas)
                }
                val textX = if (iconD != null) left + offset + dp(16f) + iconSize + iconGap else left + offset + dp(16f)
                val base = rowTop + (rh - textH) / 2f - fm.ascent
                canvas.drawText(label, textX, base, paint)
            }
            canvas.restore()
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    // Backward-compatible entry point: batch into the next vsync frame.
    private fun drawMenu() = requestDraw()

    private fun rowAt(y: Float): Int {
        val top = areaTop()
        if (y < top) return -1
        val idx = ((y - top + scrollY) / rowH()).toInt()
        return if (idx in items.indices) idx else -1
    }

    private var downY = 0f
    private var downIdx = -1
    private var lastY = 0f
    private var dragAccum = 0f
    private var dragFromDown = 0f
    private var moved = false

    // Transient "pressed" highlight index (cleared shortly after a tap/confirm), so the menu
    // shows click feedback instead of a permanently fixed selection.
    private var pressedIdx = -1
    // Cache drawable ConstantState per icon resource so we can cheaply obtain fresh,
    // independently tintable instances without re-inflating vector XML each frame.
    private val iconStates = mutableMapOf<Int, android.graphics.drawable.Drawable.ConstantState>()
    private val iconSize = dp(24f).toInt()
    private val iconGap = dp(12f)

    private fun getIconDrawable(resId: Int, color: Int): android.graphics.drawable.Drawable? {
        if (resId == 0) return null
        var state = iconStates[resId]
        if (state == null) {
            val cs = AppCompatResources.getDrawable(context, resId)?.constantState
            if (cs != null) {
                iconStates[resId] = cs
                state = cs
            }
        }
        state ?: return null
        val d = state.newDrawable().mutate()
        DrawableCompat.setTint(d, color)
        return d
    }

    private val clearPressRunnable = Runnable {
        pressedIdx = -1
        if (visible) drawMenu()
    }
}