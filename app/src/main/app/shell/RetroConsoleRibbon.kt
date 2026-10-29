package com.winlator.cmod.app.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest

internal val retroLibrarySystemIds = mutableStateOf<Map<Int, String>>(emptyMap())

internal fun libraryBadgeLabel(
    appId: Int,
    isCustom: Boolean,
): String? {
    val systemId = retroLibrarySystemIds.value[appId]
    if (systemId != null) {
        return com.winlator.cmod.feature.retro.RetroSystems
            .fromId(systemId)
            ?.badgeLabel
            ?: systemId
    }
    return if (isCustom) "PC" else null
}

// Full-width top band showing the platform name. The gradient starts from a dominant
// color sampled from the card's artwork (like the reference library UI) and fades to
// transparent, so the label tints toward the cover art while staying readable.
@Composable
internal fun RetroConsoleRibbon(
    label: String,
    artworkModel: Any?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sampled by produceState(Color(0xD9090C10), artworkModel) {
        value =
            if (artworkModel == null) {
                vividColorFromLabel(label)
            } else {
                try {
                    context.extractDominantColor(artworkModel) ?: vividColorFromLabel(label)
                } catch (_: Exception) {
                    vividColorFromLabel(label)
                }
            }
    }
    val topColor by animateColorAsState(
        targetValue = sampled,
        animationSpec = tween(300),
        label = "ribbonTopColor",
    )
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(22.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(topColor, topColor.copy(alpha = 0f)),
                    ),
                ),
    ) {
        Text(
            text = label,
            color = Color(0xFFE6EDF3),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp),
        )
    }
}

// Build a vivid, clearly-hued color from the label so the band stays visible even when
// artwork color sampling fails (offline / no cover). Deterministic per platform.
private fun vividColorFromLabel(label: String): Color {
    val hash = label.fold(0) { acc, c -> acc * 31 + c.code }
    val hue = (((hash % 360) + 360) % 360).toFloat()
    val hsv = floatArrayOf(hue, 0.65f, 0.55f)
    return Color(android.graphics.Color.HSVToColor(0xE6, hsv))
}

// Sample the artwork's pixels and pick the dominant *vivid* hue (bucketed by 10°), so the
// band reads as art-colored instead of a muddy average. Returns null on any failure.
private suspend fun android.content.Context.extractDominantColor(model: Any): Color? {
    val request =
        ImageRequest
            .Builder(this)
            .data(model)
            .size(40)
            .allowHardware(false)
            .build()
    val drawable = imageLoader.execute(request).drawable ?: return null
    val bitmap = drawable.toBitmap()
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    // Count pixels per 10° hue bucket, but only those that are reasonably saturated and
    // mid-bright (skip near-black, near-white, and gray pixels).
    val buckets = IntArray(36)
    var rAll = 0L; var gAll = 0L; var bAll = 0L; var nAll = 0L
    val hsv = FloatArray(3)
    for (px in pixels) {
        if ((px ushr 24) and 0xFF < 128) continue
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        rAll += r; gAll += g; bAll += b; nAll++
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val s = hsv[1]; val v = hsv[2]
        if (s >= 0.25f && v >= 0.2f && v <= 0.9f) {
            buckets[(hsv[0] / 10f).toInt().coerceIn(0, 35)]++
        }
    }
    if (nAll == 0L) return null
    val best = buckets.indices.maxByOrNull { buckets[it] } ?: 0
    // Use the dominant hue with a fixed vivid saturation/brightness for a clean band.
    val out = floatArrayOf(best * 10f + 5f, 0.7f, 0.5f)
    return Color(android.graphics.Color.HSVToColor(0xE6, out))
}
