package yomu.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private class AskTurn(
    val question: String,
    val answer: MangaAnswer? = null,
    val error: StringResource? = null,
)

/** Answers only from pages already read, so a question can never spoil what comes next. */
@Composable
fun ReaderAskSheet(
    client: RecapClient,
    memory: ReadingMemory,
    mangaId: Long,
    chapterNumber: Double,
    pageIndex: Int,
    catchUp: suspend (onProgress: (Int, Int) -> Unit) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<AskTurn>() }
    var question by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<RecapProgress?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(turns.size, isWorking) { scroll.animateScrollTo(scroll.maxValue) }

    val send: () -> Unit = send@{
        val asked = question.trim()
        if (asked.length < MIN_QUESTION || isWorking) return@send
        question = ""
        isWorking = true
        progress = null
        scope.launch {
            val turn = try {
                catchUp { done, total -> progress = RecapProgress.Reading(done, total) }
                val chapters = memory.chaptersUpTo(mangaId, chapterNumber, pageIndex)
                AskTurn(asked, answer = client.ask(chapters, asked) { progress = it })
            } catch (e: RecapClient.RecapException) {
                AskTurn(asked, error = e.reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Question failed" }
                AskTurn(asked, error = MR.strings.yomu_summary_error_generic)
            }
            turns += turn
            isWorking = false
        }
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    text = stringResource(MR.strings.yomu_ask_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(MR.strings.yomu_ask_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (turns.isEmpty() && !isWorking) {
                    Text(
                        text = stringResource(MR.strings.yomu_ask_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                }
                turns.forEach { turn -> AskBubbles(turn) }
                if (isWorking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = when (val step = progress) {
                                is RecapProgress.Reading ->
                                    stringResource(MR.strings.yomu_summary_progress_reading, step.done, step.total)
                                is RecapProgress.Waiting ->
                                    stringResource(MR.strings.yomu_summary_progress_waiting, step.seconds)
                                else -> stringResource(MR.strings.yomu_ask_working)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(MAX_QUESTION) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(MR.strings.yomu_ask_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = send,
                    enabled = question.trim().length >= MIN_QUESTION && !isWorking,
                ) {
                    Icon(YomuIcons.Send, contentDescription = stringResource(MR.strings.yomu_ask_send))
                }
            }
        }
    }
}

@Composable
private fun AskBubbles(turn: AskTurn) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = turn.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = turn.answer?.text
                    ?: stringResource(turn.error ?: MR.strings.yomu_ask_not_found),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            turn.answer?.quotes?.forEach { quote ->
                Text(
                    text = stringResource(
                        MR.strings.yomu_ask_quote,
                        RecapText.chapterLabel(quote.chapter),
                        quote.text,
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MIN_QUESTION = 3
private const val MAX_QUESTION = 200
