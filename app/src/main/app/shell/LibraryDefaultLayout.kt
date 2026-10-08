package com.winlator.cmod.app.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.input.ControllerHelper
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val DefaultBlack = Color.Black
private val DefaultAccent = Color(0xFF1A9FFF)
private val DefaultAccentGlow = Color(0xFF58A6FF)
private val DefaultTextPrimary = Color(0xFFF0F4FF)
private val DefaultTextSecondary = Color(0xFF93A6BC)

private const val PLAYTIME_PREFS = "playtime_stats"

/**
 * The "Default" library layout.
 *
 * Mirrors the game launch screen: the focused game's hero art fills the screen,
 * its name / source / playtime and its actions sit over the art, and every
 * installed game is a poster card in a snap-scrolling strip along the bottom.
 *
 * Focus is owned by the activity (`libraryFocusIndex`), so this view only has to
 * scroll the strip to [focusIndex] and report a swipe that settles elsewhere.
 */
@Composable
internal fun UnifiedActivity.LibraryDefaultLayout(
    items: List<SteamApp>,
    focusIndex: Int,
    playtimeRefreshKey: Int = 0,
    gogByPseudoId: Map<Int, GOGGame> = emptyMap(),
    epicByPseudoId: Map<Int, EpicGame> = emptyMap(),
    customArtworkPathByAppId: Map<Int, String> = emptyMap(),
    customIconPathByAppId: Map<Int, String> = emptyMap(),
    customListPathByAppId: Map<Int, String> = emptyMap(),
    customCarouselPathByAppId: Map<Int, String> = emptyMap(),
    customHeroPathByAppId: Map<Int, String> = emptyMap(),
    iconRefreshKey: Int = 0,
    artworkCacheRefreshKey: Int = 0,
    isControllerActive: Boolean = false,
    immersiveBackgroundVisible: Boolean = false,
    onClick: (Int, SteamApp) -> Unit = { _, _ -> },
    onLongClick: (Int, SteamApp) -> Unit = { _, _ -> },
    onDetails: (Int, SteamApp) -> Unit = { _, _ -> },
    onSettings: (Int, SteamApp) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playtimePrefs = remember(context) { context.getSharedPreferences(PLAYTIME_PREFS, android.content.Context.MODE_PRIVATE) }
    val safeIndex = focusIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val focusedApp = items.getOrNull(safeIndex)
    val focusedGogGame = focusedApp?.let { gogByPseudoId[it.id] }
    val focusedEpicGame = focusedApp?.let { epicByPseudoId[it.id] }

    // With the immersive background on, the activity already paints the focused game full-screen.
    // Decoding it again here would crop the same art differently and show a seam under the top bar.
    val heroModel =
        remember(focusedApp?.id, artworkCacheRefreshKey, customHeroPathByAppId, immersiveBackgroundVisible) {
            if (immersiveBackgroundVisible) {
                null
            } else {
                focusedApp?.let {
                    defaultHeroModel(context, it, gogByPseudoId, epicByPseudoId, customHeroPathByAppId)
                }
            }
        }
    val heroRequest =
        remember(heroModel, context) {
            heroModel?.let { model ->
                ImageRequest
                    .Builder(context)
                    .data(model)
                    .crossfade(250)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
        }

    val playtimeText =
        remember(focusedApp?.id, playtimeRefreshKey) {
            focusedApp?.let { defaultPlaytimeLabel(playtimePrefs, it) }
        }
    val sourceLabel =
        when {
            focusedApp == null -> ""
            focusedGogGame != null -> "GOG"
            focusedApp.id >= 2000000000 -> "EPIC"
            focusedApp.id < 0 -> "CUSTOM"
            else -> "STEAM"
        }
    val studioText =
        remember(focusedApp?.id, focusedGogGame?.id, focusedEpicGame?.id) {
            focusedApp?.let {
                listOfNotNull(
                    when {
                        focusedGogGame != null -> focusedGogGame.developer.takeIf { d -> d.isNotBlank() }
                        focusedEpicGame != null -> focusedEpicGame.developer.takeIf { d -> d.isNotBlank() }
                        else -> it.developer.takeIf { d -> d.isNotBlank() }
                    },
                    defaultReleaseYear(it.releaseDate),
                ).joinToString("  ·  ")
            }
        }

    fun playFocused(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
    ) {
        val containerManager = ContainerManager(context)
        when {
            app.id < 0 -> launchCustomGame(context, containerManager, app.name)
            gogGame != null -> launchGogGame(context, containerManager, gogGame)
            app.id >= 2000000000 -> epicGame?.let { launchEpicGame(context, containerManager, it) }
            else -> launchSteamGame(context, containerManager, app)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val rowHeight = (maxHeight * 0.36f).coerceIn(140.dp, 280.dp)
        val cardWidth = rowHeight * 0.70f

        // ── Hero backdrop ──
        if (immersiveBackgroundVisible) {
            // The activity already paints the focused game full-screen; drawing the art again here
            // would crop it differently and show a seam under the top bar. Only anchor the strip.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to Color.Transparent,
                                    0.52f to Color.Transparent,
                                    0.78f to DefaultBlack.copy(alpha = 0.34f),
                                    1.0f to DefaultBlack.copy(alpha = 0.78f),
                                ),
                        ),
                    ),
            )
        } else {
            if (heroRequest != null) {
                AsyncImage(
                    model = heroRequest,
                    contentDescription = focusedApp?.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(DefaultAccent.copy(alpha = 0.30f), CardDark, BgDark),
                                radius = 980f,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SportsEsports,
                        contentDescription = null,
                        tint = DefaultTextPrimary.copy(alpha = 0.16f),
                        modifier = Modifier.size(120.dp),
                    )
                }
            }

            // Readability scrims over the art; the top stop matches the header colour so the
            // content edge does not read as a seam when immersive mode is off.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to BgDark,
                                    0.18f to BgDark.copy(alpha = 0.52f),
                                    0.46f to Color.Transparent,
                                    0.72f to Color.Transparent,
                                    1.0f to DefaultBlack.copy(alpha = 0.92f),
                                ),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to DefaultBlack.copy(alpha = 0.64f),
                                    0.44f to Color.Transparent,
                                    1.0f to DefaultBlack.copy(alpha = 0.26f),
                                ),
                        ),
                    ),
            )
        }

        // ── Foreground ──
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 10.dp),
        ) {
            if (focusedApp != null) {
                Column(
                    modifier = Modifier.widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        focusedApp.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = DefaultTextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sourceLabel.isNotBlank()) DefaultChip(sourceLabel)
                        if (!playtimeText.isNullOrBlank()) DefaultChip(playtimeText)
                    }

                    if (!studioText.isNullOrBlank()) {
                        Text(
                            studioText,
                            color = DefaultTextPrimary.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DefaultActionPill(
                            icon = Icons.Outlined.PlayArrow,
                            label = stringResource(R.string.library_games_play),
                            emphasized = true,
                        ) { playFocused(focusedApp, focusedGogGame, focusedEpicGame) }
                        DefaultActionPill(
                            icon = Icons.Outlined.Info,
                            label = stringResource(R.string.library_games_details),
                        ) { onDetails(safeIndex, focusedApp) }
                        DefaultActionPill(
                            icon = Icons.Outlined.Settings,
                            label = stringResource(R.string.common_ui_settings),
                        ) { onSettings(safeIndex, focusedApp) }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            DefaultSectionLabel(count = items.size)
            Spacer(Modifier.height(8.dp))
            DefaultGamesRow(
                items = items,
                focusIndex = safeIndex,
                rowHeight = rowHeight,
                cardWidth = cardWidth,
                gogByPseudoId = gogByPseudoId,
                epicByPseudoId = epicByPseudoId,
                customArtworkPathByAppId = customArtworkPathByAppId,
                customIconPathByAppId = customIconPathByAppId,
                customListPathByAppId = customListPathByAppId,
                customCarouselPathByAppId = customCarouselPathByAppId,
                customHeroPathByAppId = customHeroPathByAppId,
                iconRefreshKey = iconRefreshKey,
                artworkCacheRefreshKey = artworkCacheRefreshKey,
                isControllerActive = isControllerActive,
                onClick = onClick,
                onLongClick = onLongClick,
            )

            if (isControllerActive) {
                Spacer(Modifier.height(8.dp))
                DefaultControllerHints()
            }
        }
    }
}

/** Horizontal strip of poster cards: a one-row variant of the GRID_4 layout. Focus is owned by the
 *  activity (libraryFocusIndex) and the strip scrolls to follow it — never the other way around. */
@Composable
private fun UnifiedActivity.DefaultGamesRow(
    items: List<SteamApp>,
    focusIndex: Int,
    rowHeight: Dp,
    cardWidth: Dp,
    gogByPseudoId: Map<Int, GOGGame>,
    epicByPseudoId: Map<Int, EpicGame>,
    customArtworkPathByAppId: Map<Int, String>,
    customIconPathByAppId: Map<Int, String>,
    customListPathByAppId: Map<Int, String>,
    customCarouselPathByAppId: Map<Int, String>,
    customHeroPathByAppId: Map<Int, String>,
    iconRefreshKey: Int,
    artworkCacheRefreshKey: Int,
    isControllerActive: Boolean,
    onClick: (Int, SteamApp) -> Unit,
    onLongClick: (Int, SteamApp) -> Unit,
) {
    val leftPadding = 22.dp
    val listState = rememberLazyListState()

    // Focus is owned by the activity (libraryFocusIndex, moved with the d-pad). Mirror GRID_4: the
    // strip scrolls to keep the focused card in view, and never reports a new focus from scrolling.
    LaunchedEffect(focusIndex, items.size) {
        if (focusIndex in items.indices) {
            listState.animateScrollToItem(focusIndex)
        }
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // The foreground Column already insets both edges by leftPadding, so don't add a second
        // left inset (that would push the strip further right than the title/cards above). Keep the
        // padding on the right so the trailing gap stays as the list's breathing room.
        contentPadding = PaddingValues(start = 0.dp, end = leftPadding),
        modifier = Modifier.fillMaxWidth().height(rowHeight),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, app ->
            GameCapsule(
                app = app,
                gogGame = gogByPseudoId[app.id],
                epicGame = epicByPseudoId[app.id],
                iconRefreshKey = iconRefreshKey,
                artworkCacheRefreshKey = artworkCacheRefreshKey,
                isFocusedOverride = index == focusIndex,
                isControllerActive = isControllerActive,
                customArtworkPath = customArtworkPathByAppId[app.id] ?: customCarouselPathByAppId[app.id],
                customIconPath = customIconPathByAppId[app.id],
                customListPath = customListPathByAppId[app.id],
                customCarouselPath = customCarouselPathByAppId[app.id],
                customHeroPath = customHeroPathByAppId[app.id],
                useLibraryCapsule = true,
                showTitle = false,
                onClick = { onClick(index, app) },
                onLongClick = { onLongClick(index, app) },
                modifier = Modifier.width(cardWidth).height(rowHeight),
            )
        }
    }
}

@Composable
private fun DefaultSectionLabel(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(DefaultAccentGlow),
        )
        Text(
            stringResource(R.string.library_games_all_games, count),
            color = DefaultTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DefaultChip(label: String) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = DefaultTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DefaultActionPill(
    icon: ImageVector,
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "defaultPillScale",
    )
    val shape = remember { RoundedCornerShape(12.dp) }
    val background =
        if (emphasized) {
            Brush.horizontalGradient(
                colors =
                    listOf(
                        Color(0xFF00B4D8).copy(alpha = 0.42f),
                        DefaultAccent.copy(alpha = 0.42f),
                        Color(0xFF7B2FF7).copy(alpha = 0.42f),
                    ),
            )
        } else {
            Brush.horizontalGradient(
                colors = listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.10f)),
            )
        }

    Row(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(shape)
                .background(background)
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0.0f to Color.White.copy(alpha = 0.22f),
                                0.5f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.12f),
                            ),
                    ),
                ).border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                .controllerFocusGlow(cornerRadius = 12.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun DefaultControllerHints() {
    val isPS = ControllerHelper.isPlayStationController()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        DefaultHint(if (isPS) "✕" else "A", stringResource(R.string.library_games_play))
        Spacer(Modifier.width(16.dp))
        DefaultHint(if (isPS) "□" else "X", stringResource(R.string.library_games_details))
        Spacer(Modifier.width(16.dp))
        DefaultHint(if (isPS) "△" else "Y", stringResource(R.string.common_ui_settings))
    }
}

@Composable
private fun DefaultHint(
    badge: String,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ControllerBadge(badge, compact = true)
        Text(
            label,
            color = DefaultTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun defaultHeroModel(
    context: android.content.Context,
    app: SteamApp,
    gogByPseudoId: Map<Int, GOGGame>,
    epicByPseudoId: Map<Int, EpicGame>,
    customHeroPathByAppId: Map<Int, String>,
): Any? {
    val customHero =
        customHeroPathByAppId[app.id]
            ?.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it) }
            ?.takeIf { it.isFile }
    if (customHero != null) return customHero

    val gogGame = gogByPseudoId[app.id]
    val epicGame = epicByPseudoId[app.id]
    val ref =
        StoreArtworkCache.heroRef(app, gogGame, epicGame)
            ?: StoreArtworkCache.primaryRef(
                app,
                gogGame,
                epicGame,
                useLibraryCapsule = false,
                listMode = false,
            )
    return StoreArtworkCache.imageModel(context, ref)
}

private fun defaultPlaytimeLabel(
    playtimePrefs: android.content.SharedPreferences,
    app: SteamApp,
): String? {
    val key =
        when {
            app.id < 0 -> "custom_${app.id}"
            app.id >= 2000000000 -> app.name
            else -> app.name.replace(LIBRARY_NAME_SANITIZE_REGEX, "")
        }
    val millis = playtimePrefs.getLong("${key}_playtime", 0L)
    return if (millis > 0L) defaultFormatPlaytime(millis) else null
}

private fun defaultFormatPlaytime(playtimeMillis: Long): String {
    val totalMinutes = (playtimeMillis / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun defaultReleaseYear(releaseDateEpochSeconds: Long): String? =
    if (releaseDateEpochSeconds <= 0L) {
        null
    } else {
        SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(releaseDateEpochSeconds * 1000L))
    }
