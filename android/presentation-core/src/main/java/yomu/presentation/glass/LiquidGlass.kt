package yomu.presentation.glass

import android.app.ActivityManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/** What glass surfaces refract; each [tachiyomi.presentation.core.components.material.Scaffold] provides its content. */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Space the floating tab bar covers at the bottom of the screen. */
val LocalFloatingBarInset = compositionLocalOf { 0.dp }

val GlassCapsule: Shape = Capsule()

@Immutable
data class GlassStyle(
    val blur: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val chromatic: Boolean,
    val darkTint: Color,
    val lightTint: Color,
    val rimAlpha: Float,
    val shadowRadius: Dp,
    val shadowAlpha: Float,
)

object GlassStyles {
    val Bar = GlassStyle(
        blur = 6.dp,
        refractionHeight = 20.dp,
        refractionAmount = 38.dp,
        chromatic = false,
        darkTint = Color(0x7A16181E),
        lightTint = Color(0x8CFFFFFF),
        rimAlpha = 0.9f,
        shadowRadius = 22.dp,
        shadowAlpha = 0.42f,
    )
    val Control = GlassStyle(
        blur = 5.dp,
        refractionHeight = 11.dp,
        refractionAmount = 22.dp,
        chromatic = true,
        darkTint = Color(0x6B1B1D24),
        lightTint = Color(0x80FFFFFF),
        rimAlpha = 0.8f,
        shadowRadius = 12.dp,
        shadowAlpha = 0.3f,
    )
    val Droplet = GlassStyle(
        blur = 0.dp,
        refractionHeight = 14.dp,
        refractionAmount = 30.dp,
        chromatic = true,
        darkTint = Color(0x24FFFFFF),
        lightTint = Color(0x4DFFFFFF),
        rimAlpha = 1f,
        shadowRadius = 6.dp,
        shadowAlpha = 0.18f,
    )
}

private val hasGlassCapableOs: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Low-RAM devices skip the GPU effects so scrolling stays smooth. */
@Composable
private fun rememberGlassEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        hasGlassCapableOs && !context.getSystemService(ActivityManager::class.java).isLowRamDevice
    }
}

@Composable
private fun isDarkSurface() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Refracts the content behind it through a lens with a lit rim. Falls back to a tinted surface
 * below Android 12 and to blur only below Android 13, where runtime shaders are unavailable.
 */
val LocalGlassAmbient = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun Modifier.liquidGlass(
    shape: Shape = GlassCapsule,
    style: GlassStyle = GlassStyles.Control,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    tintOverride: Color? = null,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier {
    val dark = isDarkSurface()
    val ambient = LocalGlassAmbient.current
    val baseTint = if (dark) style.darkTint else style.lightTint
    val tint =
        tintOverride ?: if (ambient.isSpecified) ambient.copy(alpha = 0.26f).compositeOver(baseTint) else baseTint

    if (backdrop == null || !rememberGlassEnabled()) {
        val fallback = when {
            tintOverride != null -> tintOverride.copy(alpha = 1f)
            style == GlassStyles.Droplet -> tint
            dark -> Color(0xF01C1E25)
            else -> Color(0xF2F6F6F8)
        }
        val rim = Color.White.copy(alpha = if (dark) 0.14f else 0.5f)
        val base = if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier
        return this
            .then(base)
            .shadow(style.shadowRadius / 2, shape, clip = false)
            .clip(shape)
            .background(fallback)
            .border(0.5.dp, rim, shape)
    }

    val glass = remember(backdrop, shape, style, tint, dark, layerBlock, exportedBackdrop) {
        val highlight = Highlight(
            width = 0.75.dp,
            blurRadius = 0.5.dp,
            alpha = style.rimAlpha,
            style = HighlightStyle.Default(
                color = Color.White.copy(alpha = if (dark) 0.5f else 0.85f),
                angle = 52f,
                falloff = 1.4f,
            ),
        )
        val shadow = Shadow(
            radius = style.shadowRadius,
            offset = DpOffset(0.dp, style.shadowRadius / 4),
            color = Color.Black.copy(alpha = if (dark) style.shadowAlpha else style.shadowAlpha * 0.45f),
        )
        val innerShadow = InnerShadow(
            radius = 7.dp,
            offset = DpOffset(0.dp, 2.dp),
            color = Color.Black.copy(alpha = if (dark) 0.16f else 0.05f),
        )
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(style.blur.toPx())
                lens(
                    refractionHeight = style.refractionHeight.toPx(),
                    refractionAmount = style.refractionAmount.toPx(),
                    depthEffect = true,
                    chromaticAberration = style.chromatic,
                )
            },
            highlight = { highlight },
            shadow = { shadow },
            innerShadow = { innerShadow },
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = { drawRect(tint) },
        )
    }
    return this.then(glass)
}

/** Softly blurs and fades what scrolls under a top bar, like iOS's scroll edge effect. */
@Composable
fun Modifier.glassScrollEdge(
    backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
    val background = MaterialTheme.colorScheme.background
    val fade = remember(background) {
        Brush.verticalGradient(
            0f to background.copy(alpha = 0.94f),
            0.6f to background.copy(alpha = 0.66f),
            0.86f to background.copy(alpha = 0.1f),
            1f to background.copy(alpha = 0f),
        )
    }
    if (backdrop == null || !rememberGlassEnabled()) {
        return this.background(fade)
    }
    val edge = remember(backdrop, fade) {
        val mask = Brush.verticalGradient(
            0f to Color.Black,
            0.64f to Color.Black,
            0.86f to Color.Black.copy(alpha = 0.16f),
            1f to Color.Transparent,
        )
        val onBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { draw ->
            draw()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
        Modifier.drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = { blur(6.dp.toPx(), TileMode.Mirror) },
            onDrawBackdrop = onBackdrop,
            onDrawSurface = { drawRect(fade) },
        )
    }
    return this.then(edge)
}

/** True inside a [GlassHeader], whose single scroll edge replaces the one each app bar draws. */
val LocalInGlassHeader = compositionLocalOf { false }

/** Stacks a toolbar with its tabs or filters under one shared scroll edge. */
@Composable
fun GlassHeader(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.glassScrollEdge())
            CompositionLocalProvider(LocalInGlassHeader provides true) {
                Column(content = content)
            }
        },
    ) { measurables, constraints ->
        val header = measurables[1].measure(constraints.copy(minHeight = 0))
        val edge = measurables[0].measure(
            Constraints.fixed(header.width, header.height + GlassHeaderFade.roundToPx()),
        )
        layout(header.width, header.height) {
            edge.place(0, 0)
            header.place(0, 0)
        }
    }
}

private val GlassHeaderFade = 14.dp

/** Row of an inset grouped list; consecutive rows read as one rounded card. */
@Composable
fun Modifier.groupedCard(first: Boolean, last: Boolean): Modifier {
    val container = MaterialTheme.colorScheme.surfaceContainer
    val separator = MaterialTheme.colorScheme.outlineVariant
    val shape = remember(first, last) {
        RoundedCornerShape(
            topStart = if (first) GroupedRadius else 0.dp,
            topEnd = if (first) GroupedRadius else 0.dp,
            bottomStart = if (last) GroupedRadius else 0.dp,
            bottomEnd = if (last) GroupedRadius else 0.dp,
        )
    }
    return this
        .padding(horizontal = 14.dp)
        .clip(shape)
        .background(container)
        .drawWithContent {
            drawContent()
            if (!last) {
                val inset = 56.dp.toPx()
                val y = size.height - 0.5.dp.toPx()
                drawLine(separator, Offset(inset, y), Offset(size.width, y), 0.5.dp.toPx())
            }
        }
}

private val GroupedRadius = 18.dp

/** Lets what's behind a dialog window show through, blurred by the system compositor. */
@Composable
fun rememberSheetGlassColor(): Color {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val view = LocalView.current
    val density = LocalDensity.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val blurEnabled = remember(window) {
        window != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            window.windowManager.isCrossWindowBlurEnabled
    }
    DisposableEffect(window, blurEnabled) {
        if (blurEnabled && window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = with(density) { 26.dp.roundToPx() }
            }
        }
        onDispose {}
    }
    return if (blurEnabled) base.copy(alpha = 0.8f) else base
}

/** Presses swell the glass with a spring, the way iOS controls respond to touch. */
@Composable
fun rememberGlassPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 1.1f,
): GraphicsLayerScope.() -> Unit {
    val scale = remember { Animatable(1f) }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        scale.animateTo(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = spring(
                dampingRatio = if (pressed) Spring.DampingRatioNoBouncy else 0.55f,
                stiffness = if (pressed) 1400f else 520f,
            ),
        )
    }
    return remember(scale) {
        {
            scaleX = scale.value
            scaleY = scale.value
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = GlassControlSize,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource)
    Box(
        modifier = modifier
            .size(size)
            .liquidGlass(GlassCapsule, GlassStyles.Control, press)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground.copy(
                alpha = if (enabled) 1f else 0.38f,
            ),
            content = content,
        )
    }
}

/** Groups toolbar actions in one capsule, and draws nothing while it has no actions to show. */
@Composable
fun GlassActionGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    var hasContent by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(GlassControlSize)
            .then(if (hasContent) Modifier.liquidGlass(GlassCapsule, GlassStyles.Control) else Modifier)
            .onSizeChanged { hasContent = it.width > 0 },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalMinimumInteractiveComponentSize provides 42.dp,
        ) {
            content()
        }
    }
}

/** Primary action in accent-tinted glass; collapses to its icon while [expanded] is false. */
@Composable
fun GlassProminentButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, pressedScale = 1.06f)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .height(ProminentHeight)
            .liquidGlass(
                shape = GlassCapsule,
                style = GlassStyles.Control,
                layerBlock = press,
                tintOverride = accent.copy(alpha = 0.86f),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 520f))
            .padding(horizontal = if (expanded) 20.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
            icon()
            if (expanded) {
                Text(
                    text = text,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
    }
}

val GlassControlSize = 44.dp
private val ProminentHeight = 52.dp
