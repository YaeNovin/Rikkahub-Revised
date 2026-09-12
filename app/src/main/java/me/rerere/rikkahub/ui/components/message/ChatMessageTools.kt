package me.rerere.rikkahub.ui.components.message

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.AskUserInteraction
import me.rerere.ai.ui.AskUserNumbers
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val ASK_USER_TOOL_NAME = "ask_user"

private fun askUserConditionAnswers(
    questions: List<AskUserProtocol.Question>,
    answers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): Map<String, JsonElement> = buildMap {
    questions.forEach { question ->
        when (question.selectionType) {
            AskUserProtocol.SelectionType.MULTI -> {
                multiAnswers[question.id]?.takeIf { it.isNotEmpty() }?.let { values ->
                    put(question.id, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                }
            }
            AskUserProtocol.SelectionType.SLIDER,
            AskUserProtocol.SelectionType.RATING -> {
                answers[question.id]?.toDoubleOrNull()?.let { put(question.id, JsonPrimitive(it)) }
            }
            AskUserProtocol.SelectionType.CONFIRM -> {
                answers[question.id]?.toBooleanStrictOrNull()?.let { put(question.id, JsonPrimitive(it)) }
            }
            else -> answers[question.id]?.let { put(question.id, JsonPrimitive(it)) }
        }
    }
}

@Composable
internal fun AskUserOptionLabel(
    question: AskUserProtocol.Question,
    value: String,
) {
    val details = question.optionDetails.firstOrNull { it.value == value }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (details?.imageUrl?.isNotBlank() == true) {
            ZoomableAsyncImage(
                model = details.imageUrl,
                contentDescription = details.label,
                modifier = Modifier.size(24.dp),
            )
        }
        if (details?.icon?.isNotBlank() == true) {
            Text(details.icon, style = MaterialTheme.typography.labelMedium)
        }
        Column {
            Text(
                text = details?.label?.ifBlank { value } ?: value,
                style = MaterialTheme.typography.labelSmall,
            )
            if (details?.badge?.isNotBlank() == true) {
                Text(
                    text = details.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun formatAskUserNumber(value: Double): String =
    AskUserNumbers.format(value)

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolCancel: ((toolCallId: String, reason: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(
            tool = tool,
            loading = loading,
            onToolApproval = onToolApproval,
            onToolAnswer = onToolAnswer,
            onToolCancel = onToolCancel,
        )
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        onClick = if (context.content != null || isPending || images.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                renderer.Preview(
                    context = context,
                    onDismissRequest = { showResult = false },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
    onToolCancel: ((toolCallId: String, reason: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val isCancelled = tool.approvalState is ToolApprovalState.Cancelled
    val isExpired = tool.approvalState is ToolApprovalState.Expired
    val arguments = tool.inputAsJson()
    val parsedRequest = remember(tool.toolCallId, tool.input) {
        AskUserProtocol.parseRequest(arguments)
    }
    val questions = parsedRequest.getOrNull()?.questions.orEmpty()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val writer = LocalAskUserDraftWriter.current
    val draftScope = LocalAskUserDraftScope.current
    val restored = remember(draftScope, tool.toolCallId, tool.input, tool.approvalState) {
        AskUserInteraction.displayAnswers(tool.input, tool.metadata, tool.approvalState)
    }
    var expanded by remember(draftScope, tool.toolCallId, tool.input) { mutableStateOf(true) }
    // Retain geometry across lifecycle changes; an unchanged layout need not be measured
    // again on resume. Expansion/lifecycle gate whether a question is actually displayed.
    var visibleBounds by remember(draftScope, tool.toolCallId, tool.input) { mutableStateOf(emptySet<String>()) }
    var clock by remember(draftScope, tool.toolCallId) { mutableStateOf(askUserClock(context)) }
    val parseError = parsedRequest.exceptionOrNull()?.message
        // Gemini may send the function name before its arguments. Do not show the transient
        // missing-questions message while the same streamed tool call is still being assembled.
        ?.takeUnless { loading && it == "ask_user arguments must include questions" }

    // Keep input state isolated per tool call. This also prevents a streamed
    // replacement of the arguments from retaining answers for another request.
    val answers = remember(draftScope, tool.toolCallId, tool.input, tool.approvalState) {
        mutableStateMapOf<String, String>().also { values ->
            questions.forEach { question ->
                if (question.selectionType != AskUserProtocol.SelectionType.CONFIRM) question.defaultValue?.let { values[question.id] = it }
                (restored[question.id] as? JsonPrimitive)?.let { values[question.id] = it.content }
            }
        }
    }
    val multiAnswers = remember(draftScope, tool.toolCallId, tool.input, tool.approvalState) {
        mutableStateMapOf<String, Set<String>>().also { values ->
            questions.forEach { question ->
                if (question.defaultValues.isNotEmpty()) {
                    values[question.id] = question.defaultValues.toSet()
                }
                (restored[question.id] as? kotlinx.serialization.json.JsonArray)?.let { saved ->
                    values[question.id] = saved.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                }
            }
        }
    }

    val conditionAnswers = AskUserProtocol.visibleAnswers(AskUserProtocol.Request(questions),
        if (isAnswered) restored else askUserConditionAnswers(questions, answers, multiAnswers))
    val visibleQuestions = questions.filter { question ->
        AskUserProtocol.isQuestionVisible(question, conditionAnswers)
    }
    val visibleQuestionIds = visibleQuestions.mapTo(hashSetOf(), AskUserProtocol.Question::id)
    LaunchedEffect(visibleQuestionIds) {
        answers.keys.toList().filterNot(visibleQuestionIds::contains).forEach(answers::remove)
        multiAnswers.keys.toList().filterNot(visibleQuestionIds::contains).forEach(multiAnswers::remove)
    }
    val displayedQuestions = if (expanded && isPending && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        visibleBounds.intersect(visibleQuestionIds)
    } else emptySet()

    LaunchedEffect(draftScope, tool.toolCallId, isPending, lifecycleState) {
        while (isPending && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            clock = askUserClock(context)
            delay(1000)
        }
    }
    fun currentDraft() = buildJsonObject {
        answers.forEach { (id, value) ->
            if (questions.firstOrNull { it.id == id }?.selectionType == AskUserProtocol.SelectionType.CONFIRM) {
                value.toBooleanStrictOrNull()?.let { put(id, it) }
            } else put(id, value)
        }
        multiAnswers.forEach { (id, values) ->
            if (values.isNotEmpty()) put(id, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
    }
    val draftSnapshot by rememberUpdatedState(currentDraft() to displayedQuestions)
    LaunchedEffect(draftScope, tool.toolCallId, tool.input, isPending) {
        if (!isPending) return@LaunchedEffect
        snapshotFlow { draftSnapshot }.distinctUntilChanged().debounce(150).collect { (draft, displayed) ->
            writer?.invoke(tool.toolCallId, tool.input, draft, displayed)
        }
    }
    DisposableEffect(draftScope, tool.toolCallId, tool.input, isPending, lifecycle) {
        fun flush() { if (isPending) writer?.invoke(tool.toolCallId, tool.input, currentDraft(), emptySet()) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); flush() }
    }
    LaunchedEffect(clock, tool.metadata, visibleQuestionIds, isPending) {
        if (!isPending) return@LaunchedEffect
        val pendingAt = (tool.metadata?.get(AskUserProtocol.PENDING_AT_METADATA_KEY) as? JsonPrimitive)?.longOrNull
        if (pendingAt != null && clock.wall - pendingAt >= AskUserProtocol.APPROVAL_TIMEOUT_MILLIS) {
            onToolApproval?.invoke(tool.toolCallId, false, "Tool approval expired")
            return@LaunchedEffect
        }
        val expired = visibleQuestions.filter { it.hasConfirmationCountdown() && answers[it.id] != "false" && AskUserInteraction.timedOut(tool.input, tool.metadata, it.id, clock) }
        expired.forEach { answers[it.id] = "false" }
        if (expired.isNotEmpty() && expired.size == visibleQuestions.size) {
            onToolApproval?.invoke(tool.toolCallId, false, "Confirmation timed out and was rejected")
        }
    }
    val submitPayload = buildJsonObject {
        put("answers", JsonObject(currentDraft().filterKeys(visibleQuestionIds::contains)))
    }.toString()
    val answerValid = parsedRequest.getOrNull()?.let { request ->
        if (AskUserInteraction.isCurrent(tool.input, tool.metadata)) AskUserInteraction.submit(tool.input, request, tool.metadata, submitPayload, clock).isSuccess
        else AskUserProtocol.validateAnswer(request, submitPayload).isSuccess
    } == true

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (parseError != null) {
                    Text(
                        text = stringResource(R.string.chat_message_tool_ask_invalid_request) +
                            ": " + parseError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isCancelled) {
                    Text(
                        text = (tool.approvalState as ToolApprovalState.Cancelled).reason
                            .ifBlank { stringResource(R.string.chat_message_tool_ask_cancel_reason) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isExpired) {
                    Text(
                        text = (tool.approvalState as ToolApprovalState.Expired).reason
                            .ifBlank { stringResource(R.string.chat_message_tool_ask_expired_reason) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDenied) {
                    Text(
                        text = (tool.approvalState as ToolApprovalState.Denied).reason
                            .ifBlank { stringResource(R.string.chat_message_tool_denied) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                visibleQuestions.forEach { q ->
                    key(q.id) {
                    DisposableEffect(draftScope, tool.toolCallId, tool.input, q.id) {
                        onDispose { visibleBounds = visibleBounds - q.id }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (q.description.isNotBlank()) {
                            Text(
                                text = q.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                AskUserProtocol.SelectionType.SINGLE -> {
                                    AskUserOptions(q, setOfNotNull(answers[q.id])) { answers[q.id] = it }
                                }
                                AskUserProtocol.SelectionType.MULTI -> {
                                    AskUserOptions(q, multiAnswers[q.id].orEmpty()) { option ->
                                        val current = multiAnswers[q.id].orEmpty().toMutableSet()
                                        if (!current.remove(option)) current.add(option)
                                        multiAnswers[q.id] = current
                                    }
                                }
                                AskUserProtocol.SelectionType.TEXT -> {
                                    // Text (default): optional option chips + free text input
                                    AskUserOptions(q, setOfNotNull(answers[q.id])) { answers[q.id] = it }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = {
                                            answers[q.id] = it.take(q.maxLength ?: AskUserProtocol.MAX_TEXT_ANSWER_LENGTH)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        placeholder = q.placeholder.takeIf(String::isNotBlank)?.let { hint ->
                                            { Text(hint) }
                                        },
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                                AskUserProtocol.SelectionType.SLIDER -> {
                                    val minimum = q.minValue ?: 0.0
                                    val maximum = q.maxValue ?: 1.0
                                    val step = q.step ?: 1.0
                                    val current = answers[q.id]?.toDoubleOrNull()
                                        ?.takeIf { it.isFinite() }
                                        ?.coerceIn(minimum, maximum) ?: minimum
                                    Slider(
                                        value = ((current - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f),
                                        onValueChange = { raw ->
                                            answers[q.id] = AskUserNumbers.snapFraction(minimum, maximum, step, raw)
                                        },
                                        valueRange = 0f..1f,
                                        steps = 0,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(value = answers[q.id].orEmpty(), onValueChange = { answers[q.id] = it.take(64) },
                                        placeholder = { Text("输入精确数值") }, singleLine = true,
                                        supportingText = { Text("${formatAskUserNumber(minimum)}～${formatAskUserNumber(maximum)}，步长 ${formatAskUserNumber(step)}") },
                                        modifier = Modifier.fillMaxWidth())
                                    Text(
                                        text = buildString {
                                            append(formatAskUserNumber(current))
                                            if (q.unit.isNotBlank()) append(" ").append(q.unit)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                AskUserProtocol.SelectionType.RATING -> {
                                    val minimum = q.minValue ?: 1.0
                                    val maximum = q.maxValue ?: 5.0
                                    val step = q.step ?: 1.0
                                    val current = answers[q.id]?.toDoubleOrNull()
                                        ?.coerceIn(minimum, maximum) ?: minimum
                                    val count = (((maximum - minimum) / step).roundToInt() + 1)
                                        .coerceIn(1, 10)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        repeat(count) { index ->
                                            val value = (minimum + index * step).coerceAtMost(maximum)
                                            TextButton(
                                                onClick = { answers[q.id] = formatAskUserNumber(value) },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Text(
                                                    text = if (answers[q.id] != null && value <= current + step / 2) "★" else "☆",
                                                    color = if (answers[q.id] != null && value <= current + step / 2) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    if (q.unit.isNotBlank()) {
                                        Text(
                                            text = "${formatAskUserNumber(current)} ${q.unit}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                AskUserProtocol.SelectionType.CONFIRM -> {
                                    val selected = answers[q.id]?.toBooleanStrictOrNull()
                                    val remaining = AskUserInteraction.remainingMillis(tool.input, tool.metadata, q.id, clock)
                                    val timedOut = q.hasConfirmationCountdown() && AskUserInteraction.timedOut(tool.input, tool.metadata, q.id, clock)
                                    val dangerColor = MaterialTheme.colorScheme.error
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (q.danger) Modifier.padding(4.dp) else Modifier
                                            ),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                                                val bounds = coordinates.boundsInWindow()
                                                if (bounds.width > 0 && bounds.height > 0 && bounds.top < view.height && bounds.bottom > 0 &&
                                                    bounds.left < view.width && bounds.right > 0) {
                                                    visibleBounds = visibleBounds + q.id
                                                } else visibleBounds = visibleBounds - q.id
                                            },
                                        ) {
                                            FilledTonalButton(
                                                onClick = { answers[q.id] = "true"; writer?.invoke(tool.toolCallId, tool.input, currentDraft(), displayedQuestions) },
                                                enabled = !q.hasConfirmationCountdown() || (remaining != null && remaining > 0),
                                                colors = if (q.danger) {
                                                    ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                } else {
                                                    ButtonDefaults.filledTonalButtonColors()
                                                },
                                            ) {
                                                if (selected == true) Icon(HugeIcons.Tick01, null, modifier = Modifier.size(16.dp))
                                                Text(stringResource(R.string.chat_message_tool_ask_confirm_yes))
                                            }
                                            FilledTonalButton(
                                                onClick = { answers[q.id] = "false"; writer?.invoke(tool.toolCallId, tool.input, currentDraft(), displayedQuestions) },
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = if (selected == false) {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                    },
                                                ),
                                            ) {
                                                Text(stringResource(R.string.chat_message_tool_ask_confirm_no))
                                            }
                                        }
                                        if (q.hasConfirmationCountdown()) {
                                            Text(
                                                text = if (timedOut) "已超时，视为拒绝" else if (remaining == null) "正在准备确认…"
                                                else if (remaining <= 0 && selected != null) "已记录选择，可提交" else stringResource(
                                                    R.string.chat_message_tool_ask_countdown,
                                                    ((remaining.coerceAtLeast(0) + 999) / 1000).toInt(),
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if ((remaining ?: Long.MAX_VALUE) <= 5000) dangerColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                AskUserProtocol.SelectionType.DATE -> {
                                    val context = LocalContext.current
                                    val current = answers[q.id]
                                    FilledTonalButton(
                                        onClick = {
                                            val calendar = Calendar.getInstance()
                                            current?.split('-')?.takeIf { it.size == 3 }?.let { parts ->
                                                parts[0].toIntOrNull()?.let { calendar.set(Calendar.YEAR, it) }
                                                parts[1].toIntOrNull()?.minus(1)?.let { calendar.set(Calendar.MONTH, it) }
                                                parts[2].toIntOrNull()?.let { calendar.set(Calendar.DAY_OF_MONTH, it) }
                                            }
                                            DatePickerDialog(
                                                context,
                                                { _, year, month, day ->
                                                    answers[q.id] = String.format(
                                                        Locale.US,
                                                        "%04d-%02d-%02d",
                                                        year,
                                                        month + 1,
                                                        day,
                                                    )
                                                },
                                                calendar.get(Calendar.YEAR),
                                                calendar.get(Calendar.MONTH),
                                                calendar.get(Calendar.DAY_OF_MONTH),
                                            ).show()
                                        },
                                    ) {
                                        Text(current ?: stringResource(R.string.chat_message_tool_ask_select_date))
                                    }
                                }
                                AskUserProtocol.SelectionType.TIME -> {
                                    val context = LocalContext.current
                                    val current = answers[q.id]
                                    FilledTonalButton(
                                        onClick = {
                                            val calendar = Calendar.getInstance()
                                            current?.split(':')?.takeIf { it.size >= 2 }?.let { parts ->
                                                parts[0].toIntOrNull()?.let { calendar.set(Calendar.HOUR_OF_DAY, it) }
                                                parts[1].toIntOrNull()?.let { calendar.set(Calendar.MINUTE, it) }
                                            }
                                            TimePickerDialog(
                                                context,
                                                { _, hour, minute ->
                                                    answers[q.id] = String.format(Locale.US, "%02d:%02d", hour, minute)
                                                },
                                                calendar.get(Calendar.HOUR_OF_DAY),
                                                calendar.get(Calendar.MINUTE),
                                                true,
                                            ).show()
                                        },
                                    ) {
                                        Text(current ?: stringResource(R.string.chat_message_tool_ask_select_time))
                                    }
                                }
                            }
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answerValue = restored[q.id]
                            val answerText = when (answerValue) {
                                is kotlinx.serialization.json.JsonArray -> answerValue.joinToString(", ") {
                                    it.jsonPrimitive.contentOrNull.orEmpty()
                                }
                                is JsonPrimitive -> answerValue.contentOrNull
                                else -> null
                            } ?: "—"
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    if (submitPayload.length > AskUserProtocol.MAX_ANSWER_LENGTH) Text(
                        "回答总长度过大，请缩短后再提交；超出限制的草稿无法保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onToolCancel != null) {
                            TextButton(
                                onClick = {
                                    onToolCancel(tool.toolCallId, "Cancelled by user")
                                },
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                        FilledTonalButton(
                            onClick = {
                                onToolAnswer(tool.toolCallId, submitPayload)
                            },
                            enabled = visibleQuestions.isNotEmpty() && answerValid,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.chat_message_tool_submit),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
