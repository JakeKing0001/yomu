package yomu.year

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.navigation.LocalNavigator
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.io.File
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class YearViewModel(
    private val getHistory: GetHistory,
    private val historyRepository: HistoryRepository,
    private val getManga: GetManga,
) : ViewModel() {

    private val _summary = MutableStateFlow<YearSummary?>(null)
    val summary: StateFlow<YearSummary?> = _summary.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // The history list keeps only each series' latest chapter, so the full rows are read per series.
            val ids = getHistory.subscribe("").first().map { it.mangaId }.distinct()
            val rows = historyRepository.getHistoryByMangaIds(ids)
            val events = rows.flatMap { (mangaId, history) ->
                val manga = getManga.await(mangaId) ?: return@flatMap emptyList()
                history.mapNotNull { row ->
                    val readAt = row.readAt?.toEpochMilliseconds() ?: return@mapNotNull null
                    ReadEvent(mangaId, manga.title, manga.genre.orEmpty(), readAt, row.readDuration)
                }
            }
            val zone = ZoneId.systemDefault()
            _summary.value = YearInReview.summarize(events, LocalDate.now(zone).year, zone)
        }
    }
}

@Composable
fun YearScreen() {
    val navigator = LocalNavigator.current
    val viewModel = metroViewModel<YearViewModel>()
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.yomu_year_title),
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val loaded = summary
        if (loaded == null) {
            LoadingScreen()
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loaded.chapters == 0) {
                Text(
                    text = stringResource(MR.strings.yomu_year_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }
            YearHero(loaded)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                YearStat(formatHours(loaded.minutes), stringResource(MR.strings.yomu_year_hours), Modifier.weight(1f))
                YearStat(loaded.series.toString(), stringResource(MR.strings.yomu_year_series), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                YearStat(loaded.activeDays.toString(), stringResource(MR.strings.yomu_year_days), Modifier.weight(1f))
                YearStat(
                    loaded.longestStreak.toString(),
                    stringResource(MR.strings.yomu_year_streak),
                    Modifier.weight(1f),
                )
            }
            MonthsCard(loaded)
            RankingCard(loaded)
            ShareYearButton(loaded)
        }
    }
}

@Composable
private fun YearHero(summary: YearSummary) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.45f), Color(0xFF12131A))))
            .padding(22.dp),
    ) {
        Text(
            text = stringResource(MR.strings.yomu_year_heading, summary.year),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = summary.chapters.toString(),
            color = Color.White,
            fontSize = 68.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = stringResource(MR.strings.yomu_year_chapters),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        summary.readerType?.let { type ->
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(readerTypeLabel(type)),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun YearStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthsCard(summary: YearSummary) {
    val locale = Locale.getDefault()
    var selected by remember(summary) { mutableStateOf(summary.busiestMonth ?: 0) }
    val peak = summary.months.max().coerceAtLeast(1)
    val monthName = Month.of(selected + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) }
    val accent = MaterialTheme.colorScheme.primary
    val tableText = summary.months.mapIndexed { index, count ->
        "${Month.of(index + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)}: $count"
    }.joinToString(", ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(MR.strings.yomu_year_months),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = stringResource(MR.strings.yomu_year_month_value, monthName, summary.months[selected]),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = tableText },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            summary.months.forEachIndexed { index, count ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { selected = index },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (count > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .fillMaxHeight((count / peak.toFloat()).coerceAtLeast(0.04f))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(if (index == selected) accent else accent.copy(alpha = 0.42f)),
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = Month.of(index + 1).getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingCard(summary: YearSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(MR.strings.yomu_year_top),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        summary.topSeries.forEachIndexed { index, (title, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (summary.topGenres.isNotEmpty()) {
            Text(
                text = stringResource(MR.strings.yomu_year_genres, summary.topGenres.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShareYearButton(summary: YearSummary) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val heading = stringResource(MR.strings.yomu_year_heading, summary.year)
    val chapters = stringResource(MR.strings.yomu_year_chapters)
    val hours = stringResource(MR.strings.yomu_year_hours)
    val series = stringResource(MR.strings.yomu_year_series)
    val type = summary.readerType?.let { stringResource(readerTypeLabel(it)) }
    Button(
        onClick = {
            scope.launch {
                val file = withContext(Dispatchers.Default) {
                    renderCard(context, summary, heading, chapters, hours, series, type)
                }
                context.startActivity(file.getUriCompat(context).toShareIntent(context, "image/jpeg"))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(stringResource(MR.strings.yomu_year_share))
    }
}

private fun readerTypeLabel(type: ReaderType) = when (type) {
    ReaderType.Night -> MR.strings.yomu_year_type_night
    ReaderType.Morning -> MR.strings.yomu_year_type_morning
    ReaderType.Afternoon -> MR.strings.yomu_year_type_afternoon
    ReaderType.Evening -> MR.strings.yomu_year_type_evening
}

private fun formatHours(minutes: Long): String {
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h"
}

private fun renderCard(
    context: Context,
    summary: YearSummary,
    heading: String,
    chaptersLabel: String,
    hoursLabel: String,
    seriesLabel: String,
    typeLabel: String?,
): File {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val background = Paint().apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xFF2F7DF6.toInt(), 0xFF173A78.toInt(), 0xFF0B0B0F.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

    fun text(size: Float, bold: Boolean = false, alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        this.alpha = alpha
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    val margin = 84f
    canvas.drawText(heading, margin, 170f, text(52f, bold = true))
    canvas.drawText(summary.chapters.toString(), margin, 430f, text(240f, bold = true))
    canvas.drawText(chaptersLabel, margin, 510f, text(50f))
    canvas.drawText(formatHours(summary.minutes), margin, 680f, text(92f, bold = true))
    canvas.drawText(hoursLabel, margin, 740f, text(38f, alpha = 210))
    canvas.drawText(summary.series.toString(), width / 2f + 30f, 680f, text(92f, bold = true))
    canvas.drawText(seriesLabel, width / 2f + 30f, 740f, text(38f, alpha = 210))

    var y = 870f
    summary.topSeries.forEachIndexed { index, (title, _) ->
        val paint = text(50f, bold = index == 0)
        val line = TextUtils.ellipsize("${index + 1}. $title", paint, width - margin * 2, TextUtils.TruncateAt.END)
        canvas.drawText(line, 0, line.length, margin, y, paint)
        y += 78f
    }
    typeLabel?.let { canvas.drawText(it, margin, height - 170f, text(44f, bold = true)) }
    canvas.drawText("Yomu", margin, height - 90f, text(44f, alpha = 220))

    val file = File(File(context.cacheDir, "yomu_share").apply { mkdirs() }, "year_${summary.year}.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
    bitmap.recycle()
    return file
}
