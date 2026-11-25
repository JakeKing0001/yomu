package tachiyomi.presentation.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.annotation.ExperimentalCoilApi
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import mihon.presentation.widget.di.PresentationWidgetGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.components.LockedWidget
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius

@HasMemberInjections
class ContinueReadingGlanceWidget : GlanceAppWidget() {

    @Inject private lateinit var getHistory: GetHistory

    @Inject private lateinit var preferences: SecurityPreferences

    override val sizeMode = SizeMode.Single

    private class Last(val history: HistoryWithRelations, val cover: Bitmap?)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.metroGraph<PresentationWidgetGraph>().inject(this)
        val locked = preferences.useAuthenticator.get()
        val container = GlanceModifier
            .fillMaxSize()
            .background(Surface)
            .appWidgetBackground()
            .appWidgetBackgroundRadius()

        provideContent {
            if (locked) {
                LockedWidget(foreground = Foreground, modifier = container)
                return@provideContent
            }
            val flow = remember {
                getHistory.subscribe("").map { history ->
                    history.firstOrNull()?.let { Last(it, loadCover(context, it)) }
                }
            }
            val last by flow.collectAsState(initial = null)
            Content(last, container)
        }
    }

    @Composable
    private fun Content(last: Last?, modifier: GlanceModifier) {
        val context = LocalContext.current
        if (last == null) {
            val intent = Intent(context, Class.forName(MAIN_ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Box(modifier = modifier.clickable(actionStartActivity(intent)), contentAlignment = Alignment.Center) {
                Text(
                    text = context.stringResource(MR.strings.yomu_widget_empty),
                    style = TextStyle(color = Foreground),
                )
            }
            return
        }

        val history = last.history
        val intent = Intent()
            .setClassName(context, READER_ACTIVITY)
            .putExtra("manga", history.mangaId)
            .putExtra("chapter", history.chapterId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // https://issuetracker.google.com/issues/238793260
            .addCategory(history.chapterId.toString())

        Row(
            modifier = modifier
                .padding(10.dp)
                .clickable(actionStartActivity(intent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            last.cover?.let {
                Image(
                    provider = ImageProvider(it),
                    contentDescription = history.title,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier
                        .size(width = 74.dp, height = 110.dp)
                        .cornerRadius(12.dp),
                )
                Spacer(GlanceModifier.width(14.dp))
            }
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.stringResource(MR.strings.yomu_widget_continue),
                    style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    text = history.title,
                    style = TextStyle(color = Foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
                if (history.chapterNumber >= 0) {
                    Text(
                        text = context.stringResource(
                            MR.strings.yomu_widget_chapter,
                            history.chapterNumber.toString().removeSuffix(".0"),
                        ),
                        style = TextStyle(color = Secondary, fontSize = 13.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun loadCover(context: Context, history: HistoryWithRelations): Bitmap? {
        return withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context)
                .data(history.coverData)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .precision(Precision.EXACT)
                .size(74.dpToPx, 110.dpToPx)
                .scale(Scale.FILL)
                .build()
            context.imageLoader.execute(request).image?.asDrawable(context.resources)?.toBitmap()
        }
    }

    private companion object {
        const val MAIN_ACTIVITY = "eu.kanade.tachiyomi.ui.main.MainActivity"
        const val READER_ACTIVITY = "eu.kanade.tachiyomi.ui.reader.ReaderActivity"
        val Surface = ColorProvider(day = Color(0xFF14161C), night = Color(0xFF14161C))
        val Foreground = ColorProvider(day = Color.White, night = Color.White)
        val Secondary = ColorProvider(day = Color(0xFFD5D8E0), night = Color(0xFFD5D8E0))
        val Accent = ColorProvider(day = Color(0xFF8CB5FF), night = Color(0xFF8CB5FF))
    }
}

class ContinueReadingGlanceReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget
        get() = ContinueReadingGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetManager.onWidgetEnabled(glanceAppWidget)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetManager.onWidgetDisabled(glanceAppWidget)
    }
}
