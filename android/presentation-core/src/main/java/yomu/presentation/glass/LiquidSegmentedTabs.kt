package yomu.presentation.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt

/** Segmented control whose droplet tracks the pager's scroll position exactly. */
@Composable
fun LiquidSegmentedTabs(
    titles: List<String>,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by remember(pagerState) {
        derivedStateOf {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt()
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SegmentedHeight),
    ) {
        val insetPx = with(LocalDensity.current) { SegmentPadding.toPx() }
        val segmentPx = (constraints.maxWidth - insetPx * 2) / titles.size
        val segment = with(LocalDensity.current) { segmentPx.toDp() }

        val barBackdrop = rememberLayerBackdrop()
        Box(
            Modifier
                .fillMaxSize()
                .liquidGlass(GlassCapsule, GlassStyles.Control, exportedBackdrop = barBackdrop),
        )
        val dropletLayer: GraphicsLayerScope.() -> Unit = remember(pagerState, segmentPx, insetPx) {
            {
                val offset = pagerState.currentPageOffsetFraction
                val travel = abs(offset).coerceAtMost(0.5f) * 2f
                translationX = insetPx + (pagerState.currentPage + offset) * segmentPx
                scaleX = 1f + (if (travel > 1f) 2f - travel else travel) * 0.08f
                scaleY = 1f - (if (travel > 1f) 2f - travel else travel) * 0.05f
            }
        }
        Box(
            Modifier
                .padding(vertical = SegmentPadding)
                .width(segment)
                .fillMaxHeight()
                .liquidGlass(GlassCapsule, GlassStyles.Droplet, dropletLayer, barBackdrop),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SegmentPadding),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            titles.forEachIndexed { index, title ->
                Box(
                    modifier = Modifier
                        .width(segment)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        color = if (selected == index) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f)
                        },
                        fontSize = 14.sp,
                        fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

private val SegmentedHeight = 42.dp
private val SegmentPadding = 3.dp
