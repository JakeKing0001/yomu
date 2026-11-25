package yomu.panels

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.reader.ReaderStudioState

@Composable
fun PanelPickerSheet(
    studio: ReaderStudioState,
    collection: PanelCollection,
    mangaId: Long,
    title: String,
    chapter: String,
    pageNumber: Int,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Pair<Bitmap, List<Rect>>?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) { page = studio.pageForPicking() }
    DisposableEffect(Unit) { onDispose { page?.first?.recycle() } }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(MR.strings.yomu_panels_pick_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(MR.strings.yomu_panels_pick_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val loaded = page
            if (loaded == null) {
                Box(modifier = Modifier.height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val (bitmap, panels) = loaded
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                val accent = MaterialTheme.colorScheme.primary
                val saveArea: (Rect) -> Unit = saveArea@{ area ->
                    if (isSaving) return@saveArea
                    isSaving = true
                    scope.launch {
                        collection.save(bitmap, area, mangaId, title, chapter, pageNumber)
                        context.toast(MR.strings.yomu_panels_saved)
                        onDismissRequest()
                    }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ratio = bitmap.width / bitmap.height.toFloat()
                    val height = min(maxWidth / ratio, MAX_PICKER_HEIGHT.dp)
                    Canvas(
                        modifier = Modifier
                            .size(height * ratio, height)
                            .pointerInput(panels) {
                                detectTapGestures { tap ->
                                    val point = Offset(tap.x / size.width, tap.y / size.height)
                                    panels.firstOrNull { it.contains(point) }?.let(saveArea)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        dragStart = start
                                        dragEnd = start
                                    },
                                    onDrag = { change, _ -> dragEnd = change.position },
                                    onDragCancel = {
                                        dragStart = null
                                        dragEnd = null
                                    },
                                    onDragEnd = {
                                        val from = dragStart
                                        val to = dragEnd
                                        dragStart = null
                                        dragEnd = null
                                        if (from != null && to != null) {
                                            val area = Rect(
                                                (minOf(from.x, to.x) / size.width).coerceIn(0f, 1f),
                                                (minOf(from.y, to.y) / size.height).coerceIn(0f, 1f),
                                                (maxOf(from.x, to.x) / size.width).coerceIn(0f, 1f),
                                                (maxOf(from.y, to.y) / size.height).coerceIn(0f, 1f),
                                            )
                                            if (area.width > MIN_AREA && area.height > MIN_AREA) saveArea(area)
                                        }
                                    },
                                )
                            },
                    ) {
                        drawImage(
                            image = image,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.High,
                        )
                        panels.forEach { area ->
                            val topLeft = Offset(area.left * size.width, area.top * size.height)
                            val box = Size(area.width * size.width, area.height * size.height)
                            drawRoundRect(
                                color = accent.copy(alpha = 0.14f),
                                topLeft = topLeft,
                                size = box,
                                cornerRadius = CornerRadius(10f),
                            )
                            drawRoundRect(
                                color = accent,
                                topLeft = topLeft,
                                size = box,
                                cornerRadius = CornerRadius(10f),
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                        val from = dragStart
                        val to = dragEnd
                        if (from != null && to != null) {
                            val topLeft = Offset(minOf(from.x, to.x), minOf(from.y, to.y))
                            val box = Size(kotlin.math.abs(from.x - to.x), kotlin.math.abs(from.y - to.y))
                            drawRect(color = Color.White.copy(alpha = 0.22f), topLeft = topLeft, size = box)
                            drawRect(color = Color.White, topLeft = topLeft, size = box, style = Stroke(2.dp.toPx()))
                        }
                        if (isSaving) drawRect(Color.Black.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

private const val MAX_PICKER_HEIGHT = 620
private const val MIN_AREA = 0.04f
