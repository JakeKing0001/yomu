package yomu.presentation.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
class LiquidTab(
    val label: String,
    val icon: Painter,
    val badge: (@Composable () -> Unit)? = null,
)

/**
 * Floating glass tab bar. The droplet follows the finger across tabs and stretches with its
 * velocity; its motion is read only in the draw phase so dragging never recomposes the bar.
 */
@Composable
fun LiquidTabBar(
    tabs: List<LiquidTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val position = remember { Animatable(selectedIndex.toFloat()) }
    val swell = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        if (!dragging) position.animateTo(selectedIndex.toFloat(), DropletSpring)
    }

    val highlightedIndex by remember(tabs.size) {
        derivedStateOf { position.value.roundToInt().coerceIn(0, tabs.lastIndex) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TabBarHeight),
    ) {
        val insetPx = with(LocalDensity.current) { TabBarPadding.toPx() }
        val tabWidthPx = (constraints.maxWidth - insetPx * 2) / tabs.size
        val tabWidth = with(LocalDensity.current) { tabWidthPx.toDp() }

        // The droplet refracts the bar's finished glass, so the blur is only computed once.
        val barBackdrop = rememberLayerBackdrop()
        Box(
            Modifier
                .fillMaxSize()
                .liquidGlass(GlassCapsule, GlassStyles.Bar, exportedBackdrop = barBackdrop),
        )

        val dropletLayer: GraphicsLayerScope.() -> Unit = remember(tabWidthPx, insetPx) {
            {
                val stretch = (abs(position.velocity) * 0.035f).coerceAtMost(0.28f)
                val pressed = swell.value
                translationX = insetPx + position.value * tabWidthPx
                scaleX = 1f + stretch + pressed * 0.1f
                scaleY = 1f - stretch * 0.45f + pressed * 0.14f
            }
        }
        Box(
            Modifier
                .padding(vertical = TabBarPadding)
                .width(tabWidth)
                .fillMaxHeight()
                .liquidGlass(GlassCapsule, GlassStyles.Droplet, dropletLayer, barBackdrop),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TabBarPadding)
                .selectableGroup()
                .pointerInput(tabs.size, tabWidthPx) {
                    fun indexAt(x: Float) = ((x / tabWidthPx).toInt()).coerceIn(0, tabs.lastIndex)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scope.launch { swell.animateTo(1f, spring(1f, 1500f)) }

                        var lastTick = indexAt(down.position.x)
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }

                        if (slop != null) {
                            dragging = true
                            horizontalDrag(slop.id) { change ->
                                change.consume()
                                val target = (change.position.x / tabWidthPx - 0.5f)
                                    .coerceIn(-0.12f, tabs.lastIndex + 0.12f)
                                scope.launch { position.snapTo(target) }
                                val tick = indexAt(change.position.x)
                                if (tick != lastTick) {
                                    lastTick = tick
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                            dragging = false
                            if (lastTick != currentSelected) currentOnSelect(lastTick)
                            scope.launch { position.animateTo(lastTick.toFloat(), DropletSpring) }
                        }
                        scope.launch { swell.animateTo(0f, spring(0.55f, 480f)) }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                LiquidTabItem(
                    tab = tab,
                    highlighted = highlightedIndex == index,
                    onClick = {
                        if (index != currentSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        currentOnSelect(index)
                    },
                    modifier = Modifier.width(tabWidth),
                )
            }
        }
    }
}

@Composable
private fun LiquidTabItem(
    tab: LiquidTab,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
        },
        animationSpec = spring(stiffness = 900f),
        label = "tabColor",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = highlighted,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                painter = tab.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
            tab.badge?.let { badge ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 18.dp),
                ) { badge() }
            }
        }
        Text(
            text = tab.label,
            color = color,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

val TabBarHeight = 66.dp
private val TabBarPadding = 4.dp
private val DropletSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 420f)
