package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import io.pebbletemplates.pebble.template.PebbleTemplate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.rikkahub.utils.toLocalTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import kotlin.time.toJavaInstant

data class MessageTemplateValidation(
    val errorMessage: String? = null,
    val preservesMessage: Boolean = false,
) {
    val isValid: Boolean get() = errorMessage == null && preservesMessage
}

class TemplateTransformer(
    private val engine: PebbleEngine,
) : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = engine.getTemplate(ctx.assistant.id.toString())
        val workspaceRepository = runCatching { get<WorkspaceRepository>() }.getOrNull()
        val workspaceId = ctx.assistant.workspaceId?.toString()
        val workspace = if (workspaceRepository != null && workspaceId != null) {
            workspaceRepository.getById(workspaceId)
        } else {
            null
        }
        val variables = PromptVariableResolutionContext(
            settings = ctx.settings,
            model = ctx.model,
            assistant = ctx.assistant,
            workspace = workspace,
            workspaceCwd = ctx.workspaceCwd,
            context = ctx.context,
        ).resolvePromptVariables()
        return renderMessages(template, messages, variables)
    }

    fun transformWithTemplate(
        templateSource: String,
        messages: List<UIMessage>,
        variables: Map<String, String> = emptyMap(),
    ): List<UIMessage> = renderMessages(
        template = engine.getLiteralTemplate(templateSource),
        messages = messages,
        variables = variables,
    )

    fun validate(templateSource: String): MessageTemplateValidation {
        val sentinel = "__RIKKAHUB_MESSAGE_CONTENT__"
        return runCatching {
            val rendered = transformWithTemplate(
                templateSource = templateSource,
                messages = listOf(UIMessage.user(sentinel)),
            ).single().toText()
            MessageTemplateValidation(preservesMessage = sentinel in rendered)
        }.getOrElse { error ->
            MessageTemplateValidation(
                errorMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun renderMessages(
        template: PebbleTemplate,
        messages: List<UIMessage>,
        variables: Map<String, String> = emptyMap(),
    ): List<UIMessage> {
        val timeZone = TimeZone.currentSystemDefault()
        return messages.map { message ->
            // 使用消息本身的发送时间而不是当前时间, 保证多次请求时渲染结果稳定, 不破坏 prompt 缓存
            val createdAt = message.createdAt.toInstant(timeZone).toJavaInstant()
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            template.evaluate(
                                result,
                                buildMap {
                                    putAll(variables)
                                    // Message-specific values always win over the
                                    // common resolver aliases.
                                    put("message", part.text)
                                    put("role", message.role.name.lowercase())
                                    put("time", createdAt.toLocalTime())
                                    put("date", createdAt.toLocalDate())
                                },
                            )
                            part.copy(
                                text = result.toString()
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        return StringReader(content)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
