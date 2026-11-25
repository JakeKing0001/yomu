package yomu.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.presentation.glass.GlassIconButton
import yomu.presentation.glass.GlassStyles
import yomu.presentation.glass.LocalGlassBackdrop
import yomu.presentation.glass.liquidGlass
import yomu.reader.YomuIcons
import kotlin.math.abs

private const val SWIPE_THRESHOLD = 0.3f

@Composable
fun DiscoverScreen() {
    val navigator = LocalNavigator.current
    val viewModel = metroViewModel<ForYouViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val top = state.recommendations.firstOrNull()?.manga
    val next = state.recommendations.getOrNull(1)?.manga
    val backdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offsetX = remember(top?.id) { Animatable(0f) }
    val offsetY = remember(top?.id) { Animatable(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val leave: (Boolean) -> Unit = { liked ->
            top?.let { manga ->
                scope.launch {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    offsetX.animateTo(if (liked) widthPx * 1.5f else -widthPx * 1.5f, tween(190))
                    if (liked) viewModel.like(manga) else viewModel.dismiss(manga)
                }
            }
        }

        // The glass controls sit outside this layer: glass must never sample itself.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 62.dp, bottom = 104.dp),
        ) {
            if (top == null) {
                Text(
                    text = stringResource(MR.strings.yomu_deck_finished),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                next?.let { manga ->
                    key(manga.id) {
                        DeckCover(
                            manga = manga,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 0.94f
                                    scaleY = 0.94f
                                    translationY = 18.dp.toPx()
                                    alpha = 0.7f
                                },
                        )
                    }
                }

                key(top.id) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = offsetX.value
                                translationY = offsetY.value
                                rotationZ = offsetX.value / widthPx * 14f
                            }
                            .pointerInput(top.id) {
                                detectDragGestures(
                                    onDragEnd = {
                                        when {
                                            offsetX.value > widthPx * SWIPE_THRESHOLD -> leave(true)
                                            offsetX.value < -widthPx * SWIPE_THRESHOLD -> leave(false)
                                            else -> scope.launch {
                                                val settle = spring<Float>(0.7f, Spring.StiffnessMediumLow)
                                                launch { offsetX.animateTo(0f, settle) }
                                                offsetY.animateTo(0f, settle)
                                            }
                                        }
                                    },
                                ) { change, drag ->
                                    change.consume()
                                    scope.launch { offsetX.snapTo(offsetX.value + drag.x) }
                                    scope.launch { offsetY.snapTo(offsetY.value + drag.y * 0.4f) }
                                }
                            }
                            .clickable(interactionSource = null, indication = null) {
                                navigator.push(MangaRoute(top.id, fromSource = true))
                            },
                    ) {
                        DeckCover(manga = top, modifier = Modifier.fillMaxSize())
                        val progress = (offsetX.value / (widthPx * SWIPE_THRESHOLD)).coerceIn(-1f, 1f)
                        if (abs(progress) > 0.05f) {
                            Stamp(
                                text = stringResource(
                                    if (progress > 0) MR.strings.yomu_deck_like else MR.strings.yomu_deck_skip,
                                ),
                                color = if (progress > 0) MaterialTheme.colorScheme.primary else Color(0xFFFF8A8A),
                                modifier = Modifier
                                    .align(if (progress > 0) Alignment.TopStart else Alignment.TopEnd)
                                    .padding(20.dp)
                                    .graphicsLayer {
                                        alpha = abs(progress)
                                        rotationZ = if (progress > 0) -12f else 12f
                                    },
                            )
                        }
                    }
                }
            }
        }

        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = navigator::pop) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(MR.strings.action_cancel),
                    )
                }
                Text(
                    text = stringResource(MR.strings.yomu_deck_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }

            if (top != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckButton(YomuIcons.Stop, stringResource(MR.strings.yomu_deck_skip), Color.White, 64.dp) {
                        leave(false)
                    }
                    DeckButton(
                        YomuIcons.Heart,
                        stringResource(MR.strings.yomu_deck_like),
                        MaterialTheme.colorScheme.primary,
                        72.dp,
                    ) { leave(true) }
                }
            }
        }
    }
}

@Composable
private fun DeckCover(manga: Manga, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(26.dp))) {
        AsyncImage(
            model = manga,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        0.74f to Color.Black.copy(alpha = 0.62f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = manga.title,
                color = Color.White,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            manga.genre?.take(4)?.takeIf { it.isNotEmpty() }?.let { genres ->
                Text(
                    text = genres.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            manga.description?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.Black,
        fontSize = 20.sp,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun DeckButton(icon: ImageVector, description: String, tint: Color, size: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .liquidGlass(CircleShape, GlassStyles.Control)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.44f))
    }
}
