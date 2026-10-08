package com.winlator.cmod.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

// Top-left corner triangle (hypotenuse runs from the top-right corner to the bottom-left corner).
private val TopLeftTriangle =
    GenericShape { size, _ ->
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(0f, size.height)
        close()
    }

@Composable
internal fun RetroConsoleRibbon(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(36.dp)) {
        // Triangle background, clipped to the corner shape.
        Box(
            Modifier
                .matchParentSize()
                .clip(TopLeftTriangle)
                .background(Color(0xD9090C10)),
        )
        // Label drawn on top, horizontal, anchored to the top-left corner.
        Text(
            text = label,
            color = Color(0xFFE6EDF3),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 3.dp),
        )
    }
}
