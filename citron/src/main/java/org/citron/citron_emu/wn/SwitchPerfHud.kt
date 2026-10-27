package org.citron.citron_emu.wn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.citron.citron_emu.NativeLibrary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import kotlin.math.roundToInt

// In-game performance HUD for the embedded Switch emulator, styled and operated exactly like the
// host app's FrameRating badge (com.winlator.cmod.runtime.display.ui.FrameRating): a small,
// draggable badge showing FPS / frametime / GPU / CPU / RAM / battery(+temp) / API / clock,
// plus a mini FPS graph. Tap cycles the layout (horizontal/vertical x with/without backdrop),
// long-press opens a 3x3 anchor grid to pin it to a screen edge, drag moves it freely.
//
// Rendered onto its own SurfaceView with setZOrderOnTop(true) + TRANSLUCENT so it always sits
// above the emulated game SurfaceView and lets the game show through the transparent areas.

class SwitchPerfHudView(context: Context) :
    SurfaceView(context),
    SurfaceHolder.Callback {

    @Volatile private var surfaceReady = false

    // ── FrameRating palette (matched so the HUD looks like FrameRating) ──
    private val C_FPS = 0xFF76FF03.toInt()   // green
    private val C_GPU = 0xFFE040FB.toInt()   // purple
    private val C_CPU = 0xFFEEEEEE.toInt()   // white
    private val C_RAM = 0xFF90CAF9.toInt()   // blue
    private val C_BAT = 0xFFEF5350.toInt()   // red
    private val C_TEMP = 0xFFFFAB91.toInt()  // orange
    private val C_TIME = 0xFF0092FF.toInt()  // blue
    private val C_SEP = 0xFFFFFFFF.toInt()   // separator " | " / labels (white)
    private val BACKDROP_BASE_COLOR = 0xA6000000.toInt()
    private val SEP = " | "

    // Shared paints for the horizontal strip: 10sp bold monospace with a black shadow, like FrameRating.
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = Typeface.MONOSPACE
        setShadowLayer(1f, 1f, 1f, 0xFF000000.toInt())
    }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = Typeface.MONOSPACE
        color = C_SEP
        setShadowLayer(1f, 1f, 1f, 0xFF000000.toInt())
    }

    // display modes cycle like FrameRating: 0 = horizontal, 1 = horizontal + backdrop,
    // 2 = vertical, 3 = vertical + backdrop. Tapping the HUD advances through all four.
    private var displayMode = 1
    private val MODE_COUNT = 4

    // anchor positions (mirror FrameRating)
    private val ANCHOR_NONE = -1
    private val ANCHOR_TOP_LEFT = 0
    private val ANCHOR_TOP_CENTER = 1
    private val ANCHOR_TOP_RIGHT = 2
    private val ANCHOR_BOTTOM_LEFT = 3
    private val ANCHOR_BOTTOM_CENTER = 4
    private val ANCHOR_BOTTOM_RIGHT = 5
    private val ANCHOR_LEFT_CENTER = 6
    private val ANCHOR_RIGHT_CENTER = 7
    private var currentAnchor = ANCHOR_TOP_CENTER

    private val prefs: SharedPreferences
    private var drawCount = 0

    // live metrics
    private var fps = 0f
    private var frametimeMs = 0f
    private var gpuLoad = -1
    private var cpuPercent = -1
    private var ramPercent = -1
    private var batteryWatts = -1f
    private var tempC = -1
    private var renderer = "Vulkan"
    private var outputFps = 0f

    // HUD toggles/style mirrored from the SAME stores the Switch (citron) settings dialog writes.
    // For the embedded Switch core the HUD tab is RetroPs2HudSection, which persists:
    //   - element on/off flags (FPS/GPU/CPU/RAM/battery/temp/GRAPH/cpuTemp) -> "ARMSX2" prefs,
    //     key "wn.ps2.hud.elements" (CSV of 9 booleans; GRAPH = index 7)
    //   - style (alpha/scale/bg_*) -> "ARMSX2" prefs ("wn.ps2.hud.alpha" etc.)
    //   - frametimeNumeric / dualBattery -> global default prefs ("hud_frametime_numeric" etc.)
    // The :switch process can't import com.winlator.cmod, but it shares the same app data dir, so we
    // open those exact SharedPreferences by name and read the same keys. This keeps the in-game badge
    // in sync with the user's configuration (e.g. hiding the chart).
    // Element indices: 0 FPS, 1 renderer/API, 2 GPU, 3 CPU, 4 RAM, 5 battery, 6 temp, 7 GRAPH, 8 cpuTemp.
    private var hudElements = BooleanArray(9) { it != 8 }
    private var hudAlpha = 1f
    private var hudScale = 1f
    private var bgDecoupled = false
    private var bgAlpha = (BACKDROP_BASE_COLOR ushr 24).toFloat() / 255f
    private var frametimeNumeric = false
    private var dualBattery = false
    private var backdropColor = BACKDROP_BASE_COLOR
    private var ps2Prefs: android.content.SharedPreferences? = null
    private var globalPrefs: android.content.SharedPreferences? = null
    private val hudPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null &&
                (key.startsWith("wn.ps2.hud") || key.startsWith("retro_hud") ||
                    key == "hud_frametime_numeric" || key == "hud_dual_series_battery")
            ) {
                loadHudSettings()
                drawHud()
            }
        }

    // mini graph history
    private val fpsHistory = FloatArray(64)
    private var fpsHistoryCount = 0
    private var fpsHistoryPos = 0

    // touch state
    private var activePointerId = -1
    private var dX = 0f
    private var dY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var longPressFired = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isDragging && activePointerId != -1) {
            longPressFired = true
            showPositionMenu()
        }
    }
    private var positionPopup: PopupWindow? = null

    private val touchListener = View.OnTouchListener { view, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                dX = view.x - ev.rawX
                dY = view.y - ev.rawY
                downRawX = ev.rawX
                downRawY = ev.rawY
                downTime = System.currentTimeMillis()
                isDragging = false
                longPressFired = false
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(longPressRunnable, 500)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId != -1) {
                    val dx = kotlin.math.abs(ev.rawX - downRawX)
                    val dy = kotlin.math.abs(ev.rawY - downRawY)
                    if (dx > 20 || dy > 20) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging && !longPressFired) {
                        view.x = ev.rawX + dX
                        view.y = ev.rawY + dY
                        clampToParentBounds(view)
                    }
                    true
                } else false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (activePointerId != -1) {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!longPressFired) {
                        when {
                            !isDragging && elapsed < 400 -> cycleDisplayMode()
                            isDragging -> {
                                clampToParentBounds(view)
                                persistPosition(view.x, view.y)
                                currentAnchor = ANCHOR_NONE
                                prefs.edit().putInt("hud_anchor", ANCHOR_NONE).apply()
                            }
                        }
                    }
                    activePointerId = -1
                    true
                } else false
            }
            else -> false
        }
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        prefs = context.getSharedPreferences("citron_hud", Context.MODE_PRIVATE)
        displayMode = prefs.getInt("hud_display_mode", 1)
        currentAnchor = prefs.getInt("hud_anchor", ANCHOR_TOP_CENTER)
        setOnTouchListener(touchListener)
        // Mirror the Switch HUD settings: element flags + style live in the "ARMSX2" prefs
        // (written by RetroPs2HudSection), frametimeNumeric/dualBattery in the global default prefs.
        ps2Prefs = context.getSharedPreferences("ARMSX2", Context.MODE_PRIVATE)
        globalPrefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        loadHudSettings()
        ps2Prefs?.registerOnSharedPreferenceChangeListener(hudPrefsListener)
        globalPrefs?.registerOnSharedPreferenceChangeListener(hudPrefsListener)
        post { applyStoredPosition() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) post { applyStoredPosition() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = computeLayout()
        setMeasuredDimension(w, h)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        post { applyStoredPosition() }
        drawHud()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = drawHud()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        ps2Prefs?.unregisterOnSharedPreferenceChangeListener(hudPrefsListener)
        globalPrefs?.unregisterOnSharedPreferenceChangeListener(hudPrefsListener)
        dismissPositionPopup()
    }

    // ── public API (called by the controller on the sampler thread) ──
    fun setData(
        fps: Float,
        frametimeMs: Float,
        gpuLoad: Int,
        cpuPercent: Int,
        ramPercent: Int,
        batteryWatts: Float,
        tempC: Int,
        renderer: String,
        outputFps: Float,
    ) {
        this.fps = fps
        this.frametimeMs = frametimeMs
        this.gpuLoad = gpuLoad
        this.cpuPercent = cpuPercent
        this.ramPercent = ramPercent
        this.batteryWatts = batteryWatts
        this.tempC = tempC
        this.renderer = renderer
        this.outputFps = outputFps
        fpsHistory[fpsHistoryPos] = fps
        fpsHistoryPos = (fpsHistoryPos + 1) % fpsHistory.size
        if (fpsHistoryCount < fpsHistory.size) fpsHistoryCount++
        drawHud()
    }

    // Mirror the Switch HUD settings the way RetroPs2HudSection persists them:
    // element flags + style live in the "ARMSX2" prefs; frametimeNumeric/dualBattery in the
    // global default prefs. We fall back to the global "retro_hud_*" keys for resilience.
    private fun loadHudSettings() {
        val ps2 = ps2Prefs
        val global = globalPrefs
        val baseA = (BACKDROP_BASE_COLOR ushr 24).toFloat() / 255f

        val csv = ps2?.getString("wn.ps2.hud.elements", null) ?: global?.getString("retro_hud_elements", null)
        hudElements = if (!csv.isNullOrBlank()) {
            val parts = csv.split(',')
            BooleanArray(9) { i -> if (i < parts.size) parts[i] == "1" || parts[i].equals("true", true) else i != 8 }
        } else {
            BooleanArray(9) { it != 8 }
        }

        val ps2Alpha = ps2?.getFloat("wn.ps2.hud.alpha", -1f) ?: -1f
        hudAlpha = if (ps2Alpha >= 0f) ps2Alpha else (global?.getFloat("retro_hud_alpha", 1f) ?: 1f)
        hudAlpha = hudAlpha.coerceIn(0.1f, 1f)

        val ps2Scale = ps2?.getFloat("wn.ps2.hud.scale", -1f) ?: -1f
        hudScale = if (ps2Scale >= 0f) ps2Scale else (global?.getFloat("retro_hud_scale", 1f) ?: 1f)
        hudScale = hudScale.coerceIn(0.3f, 2f)

        bgDecoupled = ps2?.getBoolean("wn.ps2.hud.bg_decoupled", false) ?: false
        val ps2BgAlpha = ps2?.getFloat("wn.ps2.hud.bg_alpha", -1f) ?: -1f
        bgAlpha = if (ps2BgAlpha >= 0f) ps2BgAlpha else (global?.getFloat("retro_hud_bg_alpha", baseA) ?: baseA)

        frametimeNumeric = global?.getBoolean("hud_frametime_numeric", false) ?: false
        dualBattery = global?.getBoolean("hud_dual_series_battery", false) ?: false

        val a = if (bgDecoupled) bgAlpha else (hudAlpha * baseA).coerceIn(0f, 1f)
        backdropColor = (BACKDROP_BASE_COLOR and 0x00FFFFFF) or
            ((a * 255f).toInt().coerceIn(0, 255) shl 24)
        // Apply to the view itself.
        this.alpha = hudAlpha
        this.scaleX = hudScale
        this.scaleY = hudScale
    }

    // ── drawing ──
    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // Integer-dp convenience overload so call sites read dp(6) instead of dp(6f).
    private fun dp(v: Int): Float = dp(v.toFloat())

    private sealed class Seg {
        data class Text(val label: String, val value: String, val color: Int) : Seg()
        object Graph : Seg()
    }

    // Build the horizontal strip items in priority order. Each metric becomes "LABEL value" on one
    // line; "主机" (index 1) is intentionally omitted (removed from settings globally).
    private fun buildSegs(): List<Seg> {
        val list = mutableListOf<Seg>()
        if (hudElements[0]) list.add(Seg.Text("FPS", fps.toInt().toString(), C_FPS))
        if (hudElements[2]) list.add(Seg.Text("GPU", gpuText(), C_GPU))
        if (hudElements[3]) list.add(Seg.Text("CPU", cpuText(), C_CPU))
        if (hudElements[4]) list.add(Seg.Text("RAM", ramText(), C_RAM))
        if (hudElements[5]) list.add(Seg.Text("BAT", batText(), C_BAT))
        if (hudElements[6] && tempC >= 0) list.add(Seg.Text("TEMP", "${tempC}°", C_TEMP))
        if (hudElements[0]) list.add(Seg.Text("TIME", timeText(), C_TIME))
        if (hudElements[7]) list.add(Seg.Graph)
        if (list.isEmpty()) list.add(Seg.Text("FPS", fps.toInt().toString(), C_FPS))
        return list
    }

    private fun isHorizontal() = displayMode == 0 || displayMode == 1

    // Sizing for both orientations. Horizontal: a single row of "LABEL value" tokens joined by
    // " | " separators. Vertical: each token on its own row (separators hidden, like FrameRating).
    private fun computeLayout(): Pair<Int, Int> {
        val segs = buildSegs()
        val pad = dp(4).toInt()
        val sepW = sepPaint.measureText(SEP).toInt() + dp(2).toInt()
        val graphW = dp(50).toInt()
        val textH = dp(14).toInt()
        val rowGap = dp(2).toInt()
        if (isHorizontal()) {
            var w = pad * 2
            var first = true
            for (s in segs) {
                if (!first) w += sepW
                first = false
                w += when (s) {
                    is Seg.Graph -> graphW
                    is Seg.Text -> sepPaint.measureText(s.label).toInt() + dp(2).toInt() +
                        stripPaint.measureText(s.value).toInt() + dp(2).toInt()
                }
            }
            return Pair(w, pad * 2 + textH)
        }
        var maxW = 0
        var h = pad * 2
        var first = true
        for (s in segs) {
            if (!first) h += rowGap
            first = false
            val wSeg = when (s) {
                is Seg.Graph -> graphW
                is Seg.Text -> sepPaint.measureText(s.label).toInt() + dp(2).toInt() +
                    stripPaint.measureText(s.value).toInt()
            }
            maxW = maxOf(maxW, wSeg)
            h += textH
        }
        return Pair(pad * 2 + maxW, h)
    }

    private fun drawHud() {
        if (!surfaceReady) return
        val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
        try {
            // Clear with CLEAR blend (not the default SRC), so the alpha channel is fully reset on
            // every buffer of the double-buffered translucent surface. A plain drawColor(0) leaves
            // stale pixels in the second buffer on some GPU drivers, which makes the fast-changing
            // FPS value bleed through as faint "ghost" numbers under the current one.
            @Suppress("DEPRECATION")
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            if (canvas.width <= 0 || canvas.height <= 0) return
            val segs = buildSegs()
            val pad = dp(4)
            val sepW = sepPaint.measureText(SEP).toInt() + dp(2).toInt()
            val graphW = dp(50).toInt()
            val textH = dp(14)
            if (displayMode == 1 || displayMode == 3) {
                val bg = Paint().apply { color = backdropColor; style = Paint.Style.FILL }
                canvas.drawRoundRect(
                    RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
                    dp(8), dp(8), bg,
                )
            }
            val fm = stripPaint.fontMetrics
            val textHeight = fm.descent - fm.ascent
            if (isHorizontal()) {
                val baseY = pad + (textH - textHeight) / 2f - fm.ascent
                val graphY = pad + (textH - dp(14)) / 2f
                var x = pad
                var first = true
                for (s in segs) {
                    if (!first) {
                        canvas.drawText(SEP, x, baseY, sepPaint)
                        x += sepW
                    }
                    first = false
                    when (s) {
                        is Seg.Text -> {
                            // White label, then the colored value, on the same line: "GPU 99%".
                            canvas.drawText(s.label, x, baseY, sepPaint)
                            x += sepPaint.measureText(s.label).toInt() + dp(2).toInt()
                            stripPaint.color = s.color
                            canvas.drawText(s.value, x, baseY, stripPaint)
                            x += stripPaint.measureText(s.value).toInt() + dp(2).toInt()
                        }
                        is Seg.Graph -> {
                            drawGraph(canvas, x.toInt(), graphY.toInt(), graphW, dp(14).toInt())
                            x += graphW
                        }
                    }
                }
            } else {
                // Vertical: each token on its own row, separators hidden (like FrameRating).
                val rowH = textH + dp(2)
                var y = pad
                for (s in segs) {
                    when (s) {
                        is Seg.Text -> {
                            val baseY = y + (textH - textHeight) / 2f - fm.ascent
                            canvas.drawText(s.label, pad, baseY, sepPaint)
                            val labelW = sepPaint.measureText(s.label).toInt() + dp(2).toInt()
                            stripPaint.color = s.color
                            canvas.drawText(s.value, pad + labelW, baseY, stripPaint)
                        }
                        is Seg.Graph -> {
                            drawGraph(canvas, pad.toInt(), y.toInt(), graphW, dp(14).toInt())
                        }
                    }
                    y += rowH
                }
            }
            if (drawCount < 8) {
                drawCount++
                Log.i("SwitchPerfHud", "drawHud #$drawCount w=${canvas.width} h=${canvas.height} mode=$displayMode")
            }
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    private fun drawGraph(c: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val bg = Paint().apply { color = 0x22000000.toInt(); style = Paint.Style.FILL }
        c.drawRoundRect(
            RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()),
            dp(4), dp(4), bg,
        )
        if (fpsHistoryCount > 1) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = C_FPS; strokeWidth = dp(1.5f); style = Paint.Style.STROKE
            }
            val maxFps = 120f
            val n = fpsHistoryCount
            val stepX = w.toFloat() / (n - 1).coerceAtLeast(1)
            val path = Path()
            for (i in 0 until n) {
                val idx = (fpsHistoryPos - n + i + fpsHistory.size) % fpsHistory.size
                val v = fpsHistory[idx].coerceIn(0f, maxFps)
                val px = x + i * stepX
                val py = y + h - (v / maxFps) * h
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, p)
        }
    }

    private fun gpuText() = if (gpuLoad >= 0) "$gpuLoad%" else "N/A"
    private fun cpuText() = if (cpuPercent >= 0) "$cpuPercent%" else "N/A"
    private fun ramText() = if (ramPercent >= 0) "$ramPercent%" else "N/A"
    private fun batText() =
        if (batteryWatts >= 0f) "%.1fW".format(if (dualBattery) batteryWatts * 2f else batteryWatts) else "N/A"
    private fun timeText(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // ── display mode / position ──
    private fun cycleDisplayMode() {
        displayMode = (displayMode + 1) % MODE_COUNT
        prefs.edit().putInt("hud_display_mode", displayMode).apply()
        requestLayout()
        drawHud()
    }

    private fun applyStoredPosition() {
        if (currentAnchor != ANCHOR_NONE) applyAnchor(currentAnchor) else clampToParentBounds(this)
    }

    private fun clampToParentBounds(v: View) {
        val p = v.parent as? View ?: return
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return
        val maxX = (p.width - v.width).coerceAtLeast(0)
        val maxY = (p.height - v.height).coerceAtLeast(0)
        v.x = v.x.coerceIn(0f, maxX.toFloat())
        v.y = v.y.coerceIn(0f, maxY.toFloat())
    }

    private fun applyAnchor(anchor: Int) {
        val p = parent as? View ?: return
        if (p.width <= 0 || p.height <= 0 || width <= 0 || height <= 0) {
            post { applyAnchor(anchor) }
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val x = when (anchor) {
            ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_LEFT, ANCHOR_LEFT_CENTER -> 0f
            ANCHOR_TOP_CENTER, ANCHOR_BOTTOM_CENTER -> (p.width - w) / 2f
            ANCHOR_TOP_RIGHT, ANCHOR_BOTTOM_RIGHT, ANCHOR_RIGHT_CENTER -> (p.width - w)
            else -> x
        }
        val y = when (anchor) {
            ANCHOR_TOP_LEFT, ANCHOR_TOP_CENTER, ANCHOR_TOP_RIGHT -> 0f
            ANCHOR_LEFT_CENTER, ANCHOR_RIGHT_CENTER -> (p.height - h) / 2f
            ANCHOR_BOTTOM_LEFT, ANCHOR_BOTTOM_CENTER, ANCHOR_BOTTOM_RIGHT -> (p.height - h)
            else -> y
        }
        setX(x)
        setY(y)
        currentAnchor = anchor
        prefs.edit().putInt("hud_anchor", anchor).apply()
    }

    private fun persistPosition(x: Float, y: Float) {
        prefs.edit().putBoolean("hud_has_position", true).putFloat("hud_pos_x", x)
            .putFloat("hud_pos_y", y).apply()
    }

    // ── long-press anchor menu ──
    private fun showPositionMenu() {
        if (!isAttachedToWindow) return
        dismissPositionPopup()
        val ctx = context
        val edge = 0x55333333.toInt()
        val textColor = 0xFFE0E0E0.toInt()
        val bg = GradientDrawable().apply {
            setColor(0xCC1E1E24.toInt()); cornerRadius = dp(10); setStroke(dp(1).toInt(), edge)
        }
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(5).toInt(), dp(5).toInt(), dp(5).toInt(), dp(5).toInt())
            elevation = dp(8)
        }
        val header = TextView(ctx).apply {
            text = "位置"; setTextColor(textColor); alpha = 0.7f; textSize = 10f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4).toInt(), dp(1).toInt(), dp(4).toInt(), dp(4).toInt())
        }
        menu.addView(header)

        val grid = GridLayout(ctx).apply { columnCount = 3; rowCount = 3 }
        val cellSize = dp(36).toInt()
        val m = dp(1).toInt()
        val cells = listOf(
            ANCHOR_TOP_LEFT to "↖",
            ANCHOR_TOP_CENTER to "↑",
            ANCHOR_TOP_RIGHT to "↗",
            ANCHOR_LEFT_CENTER to "←",
            ANCHOR_NONE to "",
            ANCHOR_RIGHT_CENTER to "→",
            ANCHOR_BOTTOM_LEFT to "↙",
            ANCHOR_BOTTOM_CENTER to "↓",
            ANCHOR_BOTTOM_RIGHT to "↘",
        )
        for ((anchor, glyph) in cells) {
            val lp = GridLayout.LayoutParams().apply {
                width = cellSize; height = cellSize; setMargins(m, m, m, m)
            }
            if (anchor == ANCHOR_NONE) {
                val ph = View(ctx); ph.layoutParams = lp; grid.addView(ph); continue
            }
            val cell = TextView(ctx).apply {
                text = glyph; textSize = 18f; setTextColor(textColor); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(0x22FFFFFF.toInt()); cornerRadius = dp(8) }
                isClickable = true; isFocusable = true
                setOnClickListener { applyAnchor(anchor); dismissPositionPopup() }
            }
            cell.layoutParams = lp
            grid.addView(cell)
        }
        menu.addView(grid)

        val popup = PopupWindow(
            menu,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        popup.setOutsideTouchable(true)
        popup.setBackgroundDrawable(ColorDrawable(0))
        positionPopup = popup

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val pW = menu.measuredWidth
        val pH = menu.measuredHeight
        val parent = parent as? View
        val parentW = parent?.width ?: pW
        val parentH = parent?.height ?: pH
        val hudCx = (x + width / 2).toInt()
        val hudCy = (y + height / 2).toInt()
        val px = (hudCx - pW / 2).coerceIn(dp(8).toInt(), (parentW - pW - dp(8).toInt()).coerceAtLeast(0))
        val py = (hudCy - pH / 2).coerceIn(dp(8).toInt(), (parentH - pH - dp(8).toInt()).coerceAtLeast(0))
        popup.showAtLocation(parent ?: this, Gravity.NO_GRAVITY, px, py)
    }

    private fun dismissPositionPopup() {
        positionPopup?.dismiss()
        positionPopup = null
    }
}

// Owns the HUD view lifecycle and a background sampler that refreshes the gauges every 500ms.
class SwitchPerfHudController(private val context: Context, private val root: ViewGroup) {
    private val view = SwitchPerfHudView(context)
    private val thread = HandlerThread("SwitchPerfHud")
    private lateinit var handler: Handler
    private var active = false

    private var batteryReceiver: BroadcastReceiver? = null
    private var voltageMv = -1
    private var currentUa = Int.MIN_VALUE
    private var tempTenths = 0

    private var sampleCount = 0
    private var diagnosed = false

    fun start() {
        if (active) return
        active = true
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        view.isClickable = true
        view.isFocusable = true
        // Attach to the top-level decorView (above `root`'s subtree). The badge sizes itself
        // (WRAP_CONTENT) and is positioned via setX/setY, so it only covers its own bounds — but
        // being the last child of decorView keeps it BOTH visually on top (setZOrderOnTop) AND the
        // topmost touch target, so the game's own SurfaceView can't swallow badge gestures.
        val decor = (context as? Activity)?.window?.decorView as? ViewGroup ?: root
        decor.addView(view, decor.childCount)
        Log.i("SwitchPerfHud", "HUD view attached to decorView (children=${decor.childCount})")
        view.visibility = View.GONE
        registerBattery()
        thread.start()
        handler = Handler(thread.looper)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            sample()
            if (active) handler.postDelayed(this, 500)
        }
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { handler.removeCallbacksAndMessages(null) }
        thread.quitSafely()
        unregisterBattery()
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
    }

    private fun sample() {
        diagnoseOnce()
        val running = runCatching { NativeLibrary.isRunning() }.getOrDefault(false)
        val stats = runCatching { NativeLibrary.getPerfStats() }.getOrDefault(DoubleArray(0))
        val fps = if (running && stats.size >= 4) stats[1].toFloat() else 0f
        val frametime = if (running && stats.size >= 4) stats[2].toFloat() else 0f
        val outputFps = if (running && stats.size >= 4) stats[0].toFloat() else 0f
        val gpu = if (running) readGpuLoad() else -1
        val cpu = if (running) readCpuPercent() else -1
        val ram = readRamPercent()
        val batteryW = computeBatteryWatts()
        val temp = if (tempTenths > 0) tempTenths / 10 else -1
        // Only show once emulation is live; before that the loading screen owns the foreground.
        // View mutations must run on the UI thread — sample() executes on the sampler HandlerThread,
        // so the visibility toggle is posted via the attached view's handler.
        val visible = running
        view.post { view.visibility = if (visible) View.VISIBLE else View.GONE }
        if (sampleCount < 20) {
            sampleCount++
            Log.i(
                "SwitchPerfHud",
                "sample#$sampleCount running=$running visible=$visible fps=$fps gpu=$gpu cpu=$cpu ram=$ram " +
                    "bat=${"%.2f".format(batteryW)}W temp=$temp viewVis=${view.visibility}",
            )
        }
        view.setData(fps, frametime, gpu, cpu, ram, batteryW, temp, "Vulkan", outputFps)
    }

    // One-time probe of every candidate GPU sysfs node so a still-missing reading is easy to diagnose
    // (e.g. the emulator process living in a namespace that hides host /sys nodes).
    private fun diagnoseOnce() {
        if (diagnosed) return
        diagnosed = true
        val paths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/mali0/device/utilisation",
            "/sys/kernel/gpu/gpu_busy",
            "/proc/mtk_mali/utilization",
        )
        for (p in paths) {
            try {
                val f = File(p)
                val content = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull()
                Log.i("SwitchPerfHud", "gpuPath $p exists=${f.exists()} readable=${f.canRead()} content=${content ?: "<none>"}")
            } catch (e: Exception) {
                Log.i("SwitchPerfHud", "gpuPath $p err=${e.message}")
            }
        }
        Log.i("SwitchPerfHud", "cpu cores=$totalCores clkPerSec=$clkTicksPerSec uid=${Process.myUid()} battery vMv=$voltageMv ua=$currentUa tempT=$tempTenths")
    }

    // ── GPU load (sysfs, same paths citron/yuzu HUDs use) ──────────────────────────────
    private var lastGoodGpuLoad = -1
    private fun readGpuLoad(): Int {
        val v = tryGpuLoad()
        if (v >= 0) { lastGoodGpuLoad = v; return v }
        // Hold the last good reading through transient sysfs failures (like the Mango HUD).
        return if (lastGoodGpuLoad >= 0) lastGoodGpuLoad else -1
    }

    private fun tryGpuLoad(): Int {
        val busy = readFirstMatching("/sys/class/kgsl/kgsl-3d0/gpubusy") { line ->
            val p = line.split(Regex("\\s+"))
            if (p.size >= 2) {
                val b = p[0].toLongOrNull()
                val i = p[1].toLongOrNull()
                if (b != null && i != null && (b + i) > 0) ((b * 100) / (b + i)).toInt() else null
            } else null
        }
        if (busy >= 0) return busy

        val pct = readFirstMatching(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/mali0/device/utilisation",
            "/sys/kernel/gpu/gpu_busy",
        ) { line -> line.trim().toIntOrNull()?.coerceIn(0, 100) }
        if (pct >= 0) return pct

        try {
            val f = File("/proc/mtk_mali/utilization")
            f.bufferedReader().useLines { lines ->
                for (l in lines) {
                    val idx = l.indexOf("ACTIVE=")
                    if (idx >= 0) {
                        val v = l.substring(idx + 7).trim().toIntOrNull()
                        if (v != null) return v.coerceIn(0, 100)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return -1
    }

    private fun readFirstMatching(vararg paths: String, parse: (String) -> Int?): Int {
        for (path in paths) {
            try {
                // Do NOT gate on File.canRead(): on Android /sys and /proc nodes commonly report
                // canRead()==false while still opening and reading fine. Just try to read them.
                // TRIM: kgsl gpubusy returns a value with LEADING whitespace (e.g. "  55761 1003373");
                // without trim, split() keeps an empty first element and the parse fails.
                val line = File(path).bufferedReader().use { it.readLine() }?.trim() ?: continue
                parse(line)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return -1
    }

    // ── CPU% across all processes of this app's UID (faithful port of app's CPUStatus) ──
    // Summing every /proc/<pid>/stat for our UID (not just /proc/self) is what makes the
    // percentage match Winlator's own HUD, since the emulation work spans multiple PIDs/threads.
    private val clkTicksPerSec =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toInt() }.getOrDefault(100).coerceAtLeast(1)
    private val totalCores = readTotalCores()
    private var prevCpuMap: HashMap<Int, Long>? = null
    private var prevCpuNanos = 0L
    private var lastGoodCpuPercent = -1

    private fun readTotalCores(): Int {
        return runCatching {
            val s = File("/sys/devices/system/cpu/possible").readText().trim()
            val dash = s.indexOf('-')
            val n = if (dash >= 0) s.substring(dash + 1).toInt() + 1 else s.toInt() + 1
            if (n > 0) n else Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }.getOrDefault(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    }

    private fun readCpuPercent(): Int {
        val sample = readAppCpuSample() ?: return lastGoodCpuPercent
        val (cur, nanos) = sample
        var result = -1
        val prev = prevCpuMap
        if (prev != null && prevCpuNanos > 0) {
            var deltaTicks = 0L
            for ((pid, ticks) in cur) {
                val old = prev[pid]
                if (old != null && ticks >= old) deltaTicks += ticks - old
            }
            val deltaSec = (nanos - prevCpuNanos) / 1e9
            if (deltaSec > 0) {
                val pct = 100.0 * deltaTicks / (deltaSec * clkTicksPerSec * totalCores)
                result = pct.coerceIn(0.0, 100.0).roundToInt()
            }
        }
        prevCpuMap = cur
        prevCpuNanos = nanos
        if (result >= 0) { lastGoodCpuPercent = result; return result }
        return if (lastGoodCpuPercent >= 0) lastGoodCpuPercent else -1
    }

    private fun readAppCpuSample(): Pair<HashMap<Int, Long>, Long>? {
        val names = runCatching { File("/proc").list() }.getOrNull() ?: return null
        val myUid = Process.myUid()
        val cur = HashMap<Int, Long>()
        for (name in names) {
            if (name.isEmpty() || !name[0].isDigit()) continue
            val pid = name.toIntOrNull() ?: continue
            val dir = "/proc/$name"
            try {
                if (Os.stat(dir).st_uid != myUid) continue
            } catch (_: Exception) {
                continue
            }
            try {
                val line = File("$dir/stat").bufferedReader().use { it.readLine() } ?: continue
                val close = line.lastIndexOf(')')
                if (close < 0) continue
                val f = line.substring(close + 2).trim().split(Regex("\\s+"))
                if (f.size < 13) continue
                val u = f[11].toLongOrNull() ?: continue
                val s = f[12].toLongOrNull() ?: continue
                cur[pid] = u + s
            } catch (_: Exception) {
            }
        }
        return Pair(cur, SystemClock.elapsedRealtimeNanos())
    }

    // ── RAM% ─────────────────────────────────────────────────────────────────────────
    private fun readRamPercent(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (mi.totalMem <= 0) -1 else (((mi.totalMem - mi.availMem) * 100) / mi.totalMem).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    // ── Battery watts / temperature ──────────────────────────────────────────────────
    private fun registerBattery() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching { parseBattery(context.registerReceiver(null, filter)) }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { parseBattery(it) }
            }
        }
        runCatching { context.registerReceiver(batteryReceiver, filter) }
    }

    private fun parseBattery(intent: Intent?) {
        if (intent == null) return
        voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val bm =
            runCatching { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }.getOrNull()
        // Prefer instantaneous current; fall back to the averaged property, then the broadcast extra.
        currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        if (currentUa == Int.MIN_VALUE || currentUa == 0) {
            currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: Int.MIN_VALUE
        }
        if (currentUa == Int.MIN_VALUE || currentUa == 0) {
            val ex = intent.getIntExtra("current_now", Int.MIN_VALUE)
            if (ex != Int.MIN_VALUE) currentUa = ex
        }
    }

    // Watts = voltage(V) * current(A). Voltage comes from the broadcast (EXTRA_VOLTAGE, mV->V);
    // current is queried LIVE from BATTERY_PROPERTY_CURRENT_NOW (µA->A) every sample so the reading
    // tracks real draw instead of freezing on the last broadcast (this is exactly how FrameRating does it).
    // NOTE: BATTERY_PROPERTY_VOLTAGE_NOW does not exist on BatteryManager, so voltage is always taken
    // from the broadcast's EXTRA_VOLTAGE, matching FrameRating.
    private fun computeBatteryWatts(): Float {
        val bm =
            runCatching { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }.getOrNull()
                ?: return -1f
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == 0) return -1f
        val amps = kotlin.math.abs(ua) / 1_000_000.0
        if (voltageMv <= 0) return -1f
        val volts = voltageMv / 1000.0
        return (volts * amps).toFloat()
    }

    private fun unregisterBattery() {
        runCatching { batteryReceiver?.let { context.unregisterReceiver(it) } }
        batteryReceiver = null
    }
}
