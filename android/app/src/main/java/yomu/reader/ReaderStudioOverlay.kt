package yomu.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.presentation.glass.GlassCapsule
import yomu.presentation.glass.GlassStyles
import yomu.presentation.glass.groupedCard
import yomu.presentation.glass.liquidGlass
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val PANEL_FIT = 0.96f
private const val MAX_ZOOM = 5f
private const val SPOTLIGHT_ALPHA = 0.9f
private const val DOUBLE_TAP_MS = 260L

/** Draws the processed page, flying a camera from one panel to the next. */
@Composable
fun ReaderStudioCanvas(
    state: ReaderStudioState,
    rightToLeft: Boolean,
    onToggleMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame = state.frame ?: return
    val target = state.currentPanel ?: ReaderStudioState.FULL_PAGE
    val camera = remember(frame) { Animatable(target, Rect.VectorConverter) }
    var zoom by remember(frame) { mutableFloatStateOf(1f) }
    var pan by remember(frame) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    var pendingTap by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(frame, target) {
        zoom = 1f
        pan = Offset.Zero
        camera.animateTo(target, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(frame, rightToLeft) {
                val swipeDistance = 56.dp.toPx()
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = Offset.Zero
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) {
                            multiTouch = true
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            pan += event.calculatePan()
                        } else {
                            val change = pressed.first().positionChange()
                            moved += change
                            if (zoom > 1.01f) pan += change
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (zoom <= 1.01f) {
                        zoom = 1f
                        pan = Offset.Zero
                    }
                    when {
                        multiTouch -> Unit
                        moved.getDistance() < touchSlop -> {
                            if (pendingTap?.isActive == true) {
                                pendingTap?.cancel()
                                if (zoom > 1f) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                } else {
                                    zoom = 2.2f
                                    pan = (Offset(size.width / 2f, size.height / 2f) - down.position) * 2.2f
                                }
                            } else {
                                val x = down.position.x / size.width
                                pendingTap = scope.launch {
                                    delay(DOUBLE_TAP_MS)
                                    if (zoom > 1f) return@launch
                                    when {
                                        x < 0.32f -> if (rightToLeft) state.forward() else state.backward()
                                        x > 0.68f -> if (rightToLeft) state.backward() else state.forward()
                                        else -> onToggleMenu()
                                    }
                                }
                            }
                        }
                        zoom > 1f -> Unit
                        abs(moved.x) > swipeDistance && abs(moved.x) > abs(moved.y) -> {
                            if ((moved.x < 0) != rightToLeft) state.forward() else state.backward()
                        }
                        abs(moved.y) > swipeDistance -> {
                            if (moved.y < 0) state.forward() else state.backward()
                        }
                    }
                }
            },
    ) {
        val image = frame.image
        val view = camera.value
        val source = Rect(
            view.left * image.width,
            view.top * image.height,
            view.right * image.width,
            view.bottom * image.height,
        )
        val isWholePage = frame.panels.size == 1
        val fit = min(size.width / source.width, size.height / source.height) * if (isWholePage) 1f else PANEL_FIT
        val scale = fit * zoom
        val left = size.width / 2f - source.center.x * scale + pan.x
        val top = size.height / 2f - source.center.y * scale + pan.y

        drawRect(Color.Black)
        withTransform({
            translate(left, top)
            scale(scale, scale, Offset.Zero)
        }) {
            drawImage(
                image = image,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(image.width, image.height),
                filterQuality = FilterQuality.High,
            )
            if (!isWholePage) {
                val scrim = Color.Black.copy(alpha = SPOTLIGHT_ALPHA)
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                drawRect(scrim, Offset.Zero, Size(w, source.top))
                drawRect(scrim, Offset(0f, source.bottom), Size(w, h - source.bottom))
                drawRect(scrim, Offset(0f, source.top), Size(source.left, source.height))
                drawRect(scrim, Offset(source.right, source.top), Size(w - source.right, source.height))
            }
        }
    }
}

/** Status chips and the auto-scroll remote, floating over the page. */
@Composable
fun BoxScope.ReaderStudioChrome(
    state: ReaderStudioState,
    voice: ReaderVoice,
    menuVisible: Boolean,
    onOpenSummary: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.welcomeBackDays != null && !menuVisible,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(RoundedCornerShape(24.dp), GlassStyles.Bar)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(MR.strings.yomu_welcome_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    text = stringResource(MR.strings.yomu_welcome_days, state.welcomeBackDays ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.86f),
                )
            }
            Text(
                text = stringResource(MR.strings.yomu_welcome_action),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .clip(GlassCapsule)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        state.dismissWelcomeBack()
                        onOpenSummary()
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                RemoteButton(YomuIcons.Stop, stringResource(MR.strings.yomu_welcome_dismiss)) {
                    state.dismissWelcomeBack()
                }
            }
        }
    }

    AnimatedVisibility(
        visible = voice.isOn && !menuVisible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 52.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(RoundedCornerShape(26.dp), GlassStyles.Bar)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val caption = voice.caption
            Text(
                text = when {
                    voice.isPreparing -> stringResource(MR.strings.yomu_voice_preparing)
                    caption != null -> caption
                    else -> " "
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    RemoteButton(YomuIcons.Minus, stringResource(MR.strings.yomu_studio_slower)) {
                        voice.changeRate(-0.1f)
                    }
                    Text(
                        text = "${(voice.rate * 10).roundToInt() / 10f}×",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(38.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    RemoteButton(YomuIcons.Plus, stringResource(MR.strings.yomu_studio_faster)) {
                        voice.changeRate(0.1f)
                    }
                    RemoteButton(
                        if (voice.isPaused) YomuIcons.Play else YomuIcons.Pause,
                        stringResource(MR.strings.yomu_studio_pause),
                        voice::togglePause,
                    )
                    RemoteButton(YomuIcons.Stop, stringResource(MR.strings.yomu_studio_stop), voice::stop)
                }
            }
        }
    }

    if (state.isWorking) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (menuVisible) 76.dp else 12.dp)
                .height(38.dp)
                .liquidGlass(GlassCapsule, GlassStyles.Bar)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.aiProgress?.let {
                    stringResource(MR.strings.yomu_studio_ai_working, (it * 100).roundToInt())
                } ?: stringResource(MR.strings.yomu_studio_working),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }

    val panelCount = state.frame?.panels?.size ?: 0
    AnimatedVisibility(
        visible = state.isOverlayVisible && panelCount > 1 && !menuVisible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 30.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(panelCount) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == state.panelIndex) 8.dp else 5.dp)
                        .align(Alignment.CenterVertically)
                        .clip(CircleShape)
                        .liquidGlass(
                            CircleShape,
                            GlassStyles.Control,
                            tintOverride = if (index == state.panelIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.45f)
                            },
                        ),
                )
            }
        }
    }

    AnimatedVisibility(
        visible = state.autoScroll && !menuVisible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 14.dp, bottom = 56.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .liquidGlass(GlassCapsule, GlassStyles.Bar)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                RemoteButton(YomuIcons.Minus, MR.strings.yomu_studio_slower.let { stringResource(it) }) {
                    state.changeSpeed(-0.2f)
                }
                Text(
                    text = "${(state.speed * 10).roundToInt() / 10f}×",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(38.dp),
                    maxLines = 1,
                )
                RemoteButton(YomuIcons.Plus, stringResource(MR.strings.yomu_studio_faster)) {
                    state.changeSpeed(0.2f)
                }
                RemoteButton(
                    if (state.autoScrollPaused) YomuIcons.Play else YomuIcons.Pause,
                    stringResource(MR.strings.yomu_studio_pause),
                ) { state.autoScrollPaused = !state.autoScrollPaused }
                RemoteButton(YomuIcons.Stop, stringResource(MR.strings.yomu_studio_stop)) {
                    state.toggleAutoScroll()
                }
            }
        }
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ReaderStudioSheet(
    state: ReaderStudioState,
    onDismissRequest: () -> Unit,
    onOpenSummary: () -> Unit,
    voice: ReaderVoice,
    onVoiceStarted: () -> Unit,
    onOpenAsk: () -> Unit,
    onSavePanel: () -> Unit,
    onFindOtherSource: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = stringResource(MR.strings.yomu_studio_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 28.dp, bottom = 12.dp),
            )
            StudioRow(
                icon = YomuIcons.Panels,
                title = stringResource(MR.strings.yomu_studio_panels),
                subtitle = stringResource(MR.strings.yomu_studio_panels_summary),
                checked = state.panelMode,
                first = true,
                onToggle = state::togglePanelMode,
            )
            StudioRow(
                icon = YomuIcons.Translate,
                title = stringResource(MR.strings.yomu_studio_translate),
                subtitle = stringResource(MR.strings.yomu_studio_translate_summary),
                checked = state.translate,
                onToggle = state::toggleTranslate,
            )
            StudioRow(
                icon = YomuIcons.Enhance,
                title = stringResource(MR.strings.yomu_studio_enhance),
                subtitle = stringResource(MR.strings.yomu_studio_enhance_summary),
                checked = state.enhance,
                onToggle = state::toggleEnhance,
            )
            if (state.enhance) {
                StudioRow(
                    icon = YomuIcons.Studio,
                    title = stringResource(MR.strings.yomu_studio_ai),
                    subtitle = stringResource(MR.strings.yomu_studio_ai_summary),
                    checked = state.aiEnhance,
                    onToggle = state::toggleAiEnhance,
                )
            }
            StudioRow(
                icon = YomuIcons.AutoScroll,
                title = stringResource(MR.strings.yomu_studio_autoscroll),
                subtitle = stringResource(MR.strings.yomu_studio_autoscroll_summary),
                checked = state.autoScroll,
                onToggle = {
                    state.toggleAutoScroll()
                    if (state.autoScroll) onDismissRequest()
                },
            )
            StudioRow(
                icon = YomuIcons.Ambient,
                title = stringResource(MR.strings.yomu_studio_ambient),
                subtitle = stringResource(MR.strings.yomu_studio_ambient_summary),
                checked = state.ambientEnabled,
                onToggle = state::toggleAmbient,
            )
            StudioRow(
                icon = YomuIcons.Memory,
                title = stringResource(MR.strings.yomu_studio_memory),
                subtitle = stringResource(MR.strings.yomu_studio_memory_summary),
                checked = state.memoryEnabled,
                onToggle = state::toggleMemory,
            )
            StudioRow(
                icon = YomuIcons.Voice,
                title = stringResource(MR.strings.yomu_voice_title),
                subtitle = stringResource(MR.strings.yomu_voice_summary),
                checked = voice.isOn,
                last = true,
                onToggle = {
                    voice.toggle()
                    if (voice.isOn) onVoiceStarted()
                },
            )
            Spacer(Modifier.height(12.dp))
            StudioAction(
                YomuIcons.Studio,
                stringResource(MR.strings.yomu_summary_action),
                first = true,
                onClick = onOpenSummary,
            )
            StudioAction(YomuIcons.Ask, stringResource(MR.strings.yomu_ask_title), onClick = onOpenAsk)
            StudioAction(YomuIcons.Bookmark, stringResource(MR.strings.yomu_panels_save), onClick = onSavePanel)
            StudioAction(
                YomuIcons.Swap,
                stringResource(MR.strings.yomu_backup_title),
                last = true,
                onClick = onFindOtherSource,
            )
        }
    }
}

@Composable
private fun StudioAction(
    icon: ImageVector,
    title: String,
    first: Boolean = false,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .groupedCard(first = first, last = last)
            .clickable(onClick = onClick)
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StudioRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    first: Boolean = false,
    last: Boolean = false,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .groupedCard(first = first, last = last)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
