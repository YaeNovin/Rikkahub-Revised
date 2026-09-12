package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.model.withChatComposerMaterial
import me.rerere.rikkahub.data.ai.DEFAULT_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.DEFAULT_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.DEFAULT_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.withQuickMessageIds
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

internal fun Settings.withUpdatedAssistant(
    assistantId: Uuid,
    fn: (Assistant) -> Assistant,
): Settings = copy(
    assistants = assistants.map { assistant ->
        if (assistant.id == assistantId) fn(assistant) else assistant
    }
)

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            me.rerere.rikkahub.data.datastore.migration.ChatComposerAppearanceMigration(),
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    private val settingsUpdateMutex = Mutex()

    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val ADVANCED_APPEARANCE_SETTING = stringPreferencesKey("advanced_appearance_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val ENABLE_GENERATION_RETRY = booleanPreferencesKey("enable_generation_retry")
        val GENERATION_RETRY_MAX_RETRIES = intPreferencesKey("generation_retry_max_retries")
        val GENERATION_RETRY_INTERVAL_SECONDS = intPreferencesKey("generation_retry_interval_seconds")
        val GENERATION_RETRY_MAX_DURATION_SECONDS = intPreferencesKey("generation_retry_max_duration_seconds")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val ENABLE_TITLE_GENERATION = booleanPreferencesKey("enable_title_generation")
        val TITLE_MAX_LENGTH = intPreferencesKey("title_max_length")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val SUGGESTION_COUNT = intPreferencesKey("suggestion_count")
        val SUGGESTION_MAX_LENGTH = intPreferencesKey("suggestion_max_length")
        val SUGGESTION_STYLE = stringPreferencesKey("suggestion_style")
        val SUGGESTION_INSERT_MODE = stringPreferencesKey("suggestion_insert_mode")
        val SUGGESTION_DISPLAY_MODE = stringPreferencesKey("suggestion_display_mode")
        val SUGGESTION_OPTIONS = stringPreferencesKey("suggestion_options")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val IMAGE_GENERATION_PAGE_MODEL = stringPreferencesKey("image_generation_page_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val MEMORY_EXTRACTION_MODEL = stringPreferencesKey("memory_extraction_model")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val LOREBOOK_TOTAL_BUDGET = intPreferencesKey("lorebook_total_budget")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")
        val EXTENSION_MANAGEMENT_MODE = stringPreferencesKey("extension_management_mode")
        val QUICK_MESSAGE_SORT_MODE = stringPreferencesKey("quick_message_sort_mode")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            val chatImageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]
                ?.let { Uuid.parse(it) }
                ?: Uuid.random()
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                enableTitleGeneration = preferences[ENABLE_TITLE_GENERATION] != false,
                titleMaxLength = (preferences[TITLE_MAX_LENGTH] ?: 24).coerceIn(8, 80),
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                suggestionCount = (preferences[SUGGESTION_COUNT] ?: 5).coerceIn(1, 10),
                suggestionMaxLength = (preferences[SUGGESTION_MAX_LENGTH] ?: 80).coerceIn(10, 200),
                suggestionStyle = decodeChatSuggestionStyle(preferences[SUGGESTION_STYLE]),
                suggestionInsertMode = decodeSuggestionInsertMode(preferences[SUGGESTION_INSERT_MODE]),
                suggestionDisplayMode = decodeChatSuggestionDisplayMode(preferences[SUGGESTION_DISPLAY_MODE]),
                suggestionOptions = preferences[SUGGESTION_OPTIONS]?.let {
                    runCatching { JsonInstant.decodeFromString<me.rerere.rikkahub.data.model.SuggestionOptions>(it) }.getOrNull()
                } ?: me.rerere.rikkahub.data.model.SuggestionOptions(),
                imageGenerationModelId = chatImageGenerationModelId,
                imageGenerationPageModelId = preferences[IMAGE_GENERATION_PAGE_MODEL]
                    ?.let { Uuid.parse(it) }
                    ?: chatImageGenerationModelId,
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                memoryExtractionModelId = preferences[MEMORY_EXTRACTION_MODEL]
                    ?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() },
                embeddingModelId = preferences[EMBEDDING_MODEL]
                    ?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() },
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                enableGenerationRetry = preferences[ENABLE_GENERATION_RETRY] ?: true,
                generationRetryMaxRetries = preferences[GENERATION_RETRY_MAX_RETRIES]
                    ?.coerceIn(MIN_GENERATION_RETRY_COUNT, MAX_GENERATION_RETRY_COUNT)
                    ?: DEFAULT_GENERATION_RETRY_COUNT,
                generationRetryInitialIntervalSeconds = preferences[GENERATION_RETRY_INTERVAL_SECONDS]
                    ?.coerceIn(
                        MIN_GENERATION_RETRY_INTERVAL_SECONDS,
                        MAX_GENERATION_RETRY_INTERVAL_SECONDS,
                    ) ?: DEFAULT_GENERATION_RETRY_INTERVAL_SECONDS,
                generationRetryMaxDurationSeconds = preferences[GENERATION_RETRY_MAX_DURATION_SECONDS]
                    ?.coerceIn(
                        MIN_GENERATION_RETRY_DURATION_SECONDS,
                        MAX_GENERATION_RETRY_DURATION_SECONDS,
                    ) ?: DEFAULT_GENERATION_RETRY_DURATION_SECONDS,
                displaySetting = decodeDisplaySetting(preferences[DISPLAY_SETTING]),
                advancedAppearanceSetting = decodeAdvancedAppearanceSetting(
                    preferences[ADVANCED_APPEARANCE_SETTING]
                ),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebookTotalTokenBudget = (preferences[LOREBOOK_TOTAL_BUDGET] ?: 0).coerceIn(0, 1000000),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                extensionManagementMode = decodeExtensionManagementMode(
                    preferences[EXTENSION_MANAGEMENT_MODE]
                ),
                quickMessageSortMode = decodeQuickMessageSortMode(
                    preferences[QUICK_MESSAGE_SORT_MODE]
                ),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    val quickMessageIds = assistant.quickMessageIds.filter { id ->
                        id in validQuickMessageIds
                    }.toSet()
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = quickMessageIds,
                        quickMessageGroups = assistant.quickMessageGroups
                            .distinctBy { it.id }
                            .map { group ->
                                group.copy(
                                    quickMessageIds = group.quickMessageIds.intersect(quickMessageIds)
                                )
                            },
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) = settingsUpdateMutex.withLock {
        updateUnlocked(settings)
    }

    private suspend fun updateUnlocked(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[ENABLE_GENERATION_RETRY] = settings.enableGenerationRetry
            preferences[GENERATION_RETRY_MAX_RETRIES] = settings.generationRetryMaxRetries
                .coerceIn(MIN_GENERATION_RETRY_COUNT, MAX_GENERATION_RETRY_COUNT)
            preferences[GENERATION_RETRY_INTERVAL_SECONDS] = settings.generationRetryInitialIntervalSeconds
                .coerceIn(
                    MIN_GENERATION_RETRY_INTERVAL_SECONDS,
                    MAX_GENERATION_RETRY_INTERVAL_SECONDS,
                )
            preferences[GENERATION_RETRY_MAX_DURATION_SECONDS] = settings.generationRetryMaxDurationSeconds
                .coerceIn(
                    MIN_GENERATION_RETRY_DURATION_SECONDS,
                    MAX_GENERATION_RETRY_DURATION_SECONDS,
                )
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            preferences[ADVANCED_APPEARANCE_SETTING] =
                JsonInstant.encodeToString(settings.advancedAppearanceSetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            preferences[ENABLE_TITLE_GENERATION] = settings.enableTitleGeneration
            preferences[TITLE_MAX_LENGTH] = settings.titleMaxLength.coerceIn(8, 80)
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            preferences[SUGGESTION_COUNT] = settings.suggestionCount.coerceIn(1, 10)
            preferences[SUGGESTION_MAX_LENGTH] = settings.suggestionMaxLength.coerceIn(10, 200)
            preferences[SUGGESTION_STYLE] = settings.suggestionStyle.name
            preferences[SUGGESTION_INSERT_MODE] = settings.suggestionInsertMode.name
            preferences[SUGGESTION_DISPLAY_MODE] = settings.suggestionDisplayMode.name
            preferences[SUGGESTION_OPTIONS] = JsonInstant.encodeToString(settings.suggestionOptions)
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[IMAGE_GENERATION_PAGE_MODEL] = settings.imageGenerationPageModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            settings.memoryExtractionModelId?.let {
                preferences[MEMORY_EXTRACTION_MODEL] = it.toString()
            } ?: preferences.remove(MEMORY_EXTRACTION_MODEL)
            settings.embeddingModelId?.let {
                preferences[EMBEDDING_MODEL] = it.toString()
            } ?: preferences.remove(EMBEDDING_MODEL)
            preferences[COMPRESS_PROMPT] = settings.compressPrompt

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[LOREBOOK_TOTAL_BUDGET] = settings.lorebookTotalTokenBudget.coerceIn(0, 1000000)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[EXTENSION_MANAGEMENT_MODE] = settings.extensionManagementMode.name
            preferences[QUICK_MESSAGE_SORT_MODE] = settings.quickMessageSortMode.name
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
        }
    }

    suspend fun update(fn: (Settings) -> Settings) = settingsUpdateMutex.withLock {
        updateUnlocked(fn(settingsFlow.value))
    }

    suspend fun updateDisplaySetting(
        fn: (DisplaySetting) -> DisplaySetting,
    ) = settingsUpdateMutex.withLock {
        val currentSettings = settingsFlow.value
        if (currentSettings.init) {
            dataStore.edit { preferences ->
                val current = decodeDisplaySetting(preferences[DISPLAY_SETTING])
                val updated = fn(current)
                if (updated != current) {
                    preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(updated)
                }
            }
            return@withLock
        }
        val updated = fn(currentSettings.displaySetting)
        if (updated == currentSettings.displaySetting) return@withLock

        dataStore.edit { preferences ->
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(updated)
        }
        settingsFlow.value = settingsFlow.value.copy(displaySetting = updated)
    }

    /**
     * Updates only appearance preferences so rapid UI adjustments cannot overwrite
     * assistant, provider, model, or other unrelated settings with an older snapshot.
     */
    suspend fun updateAdvancedAppearance(
        fn: (AdvancedAppearanceSetting) -> AdvancedAppearanceSetting,
    ) = settingsUpdateMutex.withLock {
        val currentSettings = settingsFlow.value
        if (currentSettings.init) {
            dataStore.edit { preferences ->
                val current = decodeAdvancedAppearanceSetting(
                    preferences[ADVANCED_APPEARANCE_SETTING]
                )
                val updated = fn(current)
                if (updated != current) {
                    preferences[ADVANCED_APPEARANCE_SETTING] = JsonInstant.encodeToString(updated)
                }
            }
            return@withLock
        }
        val updated = fn(currentSettings.advancedAppearanceSetting)
        if (updated == currentSettings.advancedAppearanceSetting) return@withLock

        dataStore.edit { preferences ->
            preferences[ADVANCED_APPEARANCE_SETTING] = JsonInstant.encodeToString(updated)
        }
        settingsFlow.value = settingsFlow.value.copy(advancedAppearanceSetting = updated)
    }

    /** Composer material spans two records: commit them together, including when
     * the settings flow is still loading, without rewriting unrelated preferences. */
    internal suspend fun updateComposerMaterial(material: me.rerere.rikkahub.data.model.ChatComposerMaterial) = settingsUpdateMutex.withLock {
        val current = settingsFlow.value
        var savedDisplay = current.displaySetting
        var savedAppearance = current.advancedAppearanceSetting
        dataStore.edit { preferences ->
            val display = decodeDisplaySetting(preferences[DISPLAY_SETTING])
            val appearance = decodeAdvancedAppearanceSetting(preferences[ADVANCED_APPEARANCE_SETTING])
            savedDisplay = display.withChatComposerMaterial(material)
            savedAppearance = appearance.withChatComposerMaterial(material)
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(savedDisplay)
            preferences[ADVANCED_APPEARANCE_SETTING] = JsonInstant.encodeToString(savedAppearance)
        }
        if (!current.init) settingsFlow.value = settingsFlow.value.copy(displaySetting = savedDisplay, advancedAppearanceSetting = savedAppearance)
    }

    suspend fun updateAssistant(assistantId: Uuid) = settingsUpdateMutex.withLock {
        val currentSettings = settingsFlow.value
        if (!currentSettings.init) {
            settingsFlow.value = currentSettings.copy(assistantId = assistantId)
        }
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantConfig(
        assistantId: Uuid,
        fn: (Assistant) -> Assistant,
    ) {
        update { settings ->
            settings.withUpdatedAssistant(assistantId, fn)
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.withQuickMessageIds(quickMessageIds).copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun recordQuickMessageUse(
        quickMessageId: Uuid,
        usedAt: Long = System.currentTimeMillis(),
    ) {
        update { settings ->
            settings.copy(
                quickMessages = settings.quickMessages.map { quickMessage ->
                    if (quickMessage.id == quickMessageId) {
                        quickMessage.copy(
                            useCount = if (quickMessage.useCount == Long.MAX_VALUE) {
                                Long.MAX_VALUE
                            } else {
                                quickMessage.useCount + 1
                            },
                            lastUsedAt = usedAt,
                        )
                    } else {
                        quickMessage
                    }
                }
            )
        }
    }

    suspend fun deleteQuickMessage(quickMessageId: Uuid) {
        update { settings ->
            settings.copy(
                quickMessages = settings.quickMessages.filterNot { it.id == quickMessageId },
                assistants = settings.assistants.map { assistant ->
                    assistant.withQuickMessageIds(assistant.quickMessageIds - quickMessageId)
                },
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val enableGenerationRetry: Boolean = true,
    val generationRetryMaxRetries: Int = DEFAULT_GENERATION_RETRY_COUNT,
    val generationRetryInitialIntervalSeconds: Int = DEFAULT_GENERATION_RETRY_INTERVAL_SECONDS,
    val generationRetryMaxDurationSeconds: Int = DEFAULT_GENERATION_RETRY_DURATION_SECONDS,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val advancedAppearanceSetting: AdvancedAppearanceSetting = AdvancedAppearanceSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val enableTitleGeneration: Boolean = true,
    val titleMaxLength: Int = 24,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val imageGenerationPageModelId: Uuid = imageGenerationModelId,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionCount: Int = 5,
    val suggestionMaxLength: Int = 80,
    val suggestionStyle: ChatSuggestionStyle = ChatSuggestionStyle.BALANCED,
    val suggestionInsertMode: SuggestionInsertMode = SuggestionInsertMode.REPLACE,
    val suggestionDisplayMode: ChatSuggestionDisplayMode = ChatSuggestionDisplayMode.AUTO,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val suggestionOptions: me.rerere.rikkahub.data.model.SuggestionOptions = me.rerere.rikkahub.data.model.SuggestionOptions(),
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val memoryExtractionModelId: Uuid? = null,
    val embeddingModelId: Uuid? = null,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val lorebookTotalTokenBudget: Int = 0,
    val quickMessages: List<QuickMessage> = emptyList(),
    val extensionManagementMode: ExtensionManagementMode = ExtensionManagementMode.NORMAL,
    val quickMessageSortMode: QuickMessageSortMode = QuickMessageSortMode.DEFAULT,
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ExtensionManagementMode {
    NORMAL,
    ENTERTAINMENT,
}

@Serializable
enum class QuickMessageSortMode {
    DEFAULT,
    RECENT,
    FREQUENT,
}

@Serializable
enum class ChatSuggestionStyle {
    BALANCED,
    FOLLOW_UP,
    ACTIONABLE,
    CONCISE,
    ROLEPLAY,
}

@Serializable
enum class SuggestionInsertMode {
    REPLACE,
    APPEND,
}

@Serializable
enum class ChatSuggestionDisplayMode {
    AUTO,
    COMPACT,
    TWO_ROW,
    RICH,
}

internal fun decodeExtensionManagementMode(raw: String?): ExtensionManagementMode =
    runCatching { ExtensionManagementMode.valueOf(raw.orEmpty()) }
        .getOrDefault(ExtensionManagementMode.NORMAL)

internal fun decodeQuickMessageSortMode(raw: String?): QuickMessageSortMode =
    runCatching { QuickMessageSortMode.valueOf(raw.orEmpty()) }
        .getOrDefault(QuickMessageSortMode.DEFAULT)

internal fun decodeChatSuggestionStyle(raw: String?): ChatSuggestionStyle =
    runCatching { ChatSuggestionStyle.valueOf(raw.orEmpty()) }
        .getOrDefault(ChatSuggestionStyle.BALANCED)

internal fun decodeSuggestionInsertMode(raw: String?): SuggestionInsertMode =
    runCatching { SuggestionInsertMode.valueOf(raw.orEmpty()) }
        .getOrDefault(SuggestionInsertMode.REPLACE)

internal fun decodeChatSuggestionDisplayMode(raw: String?): ChatSuggestionDisplayMode =
    runCatching { ChatSuggestionDisplayMode.valueOf(raw.orEmpty()) }
        .getOrDefault(ChatSuggestionDisplayMode.AUTO)

const val MIN_GLOBAL_BACKGROUND_BLUR_RADIUS = 4f
const val MAX_GLOBAL_BACKGROUND_BLUR_RADIUS = 40f
const val MIN_NAVIGATION_GLASS_BLUR_RADIUS = 4f
const val MAX_NAVIGATION_GLASS_BLUR_RADIUS = 32f
const val MIN_LIQUID_GLASS_BLUR_RADIUS = 0f
const val MAX_LIQUID_GLASS_BLUR_RADIUS = 24f
const val MIN_CHAT_TEXT_LINE_HEIGHT_RATIO = 1.25f
const val MAX_CHAT_TEXT_LINE_HEIGHT_RATIO = 1.8f
const val MIN_CHAT_PARAGRAPH_SPACING_RATIO = 0.35f
const val MAX_CHAT_PARAGRAPH_SPACING_RATIO = 1f

internal fun decodeDisplaySetting(raw: String?): DisplaySetting =
    runCatching {
        JsonInstant.decodeFromString<DisplaySetting>(raw ?: "{}")
    }.getOrDefault(DisplaySetting())

internal fun decodeAdvancedAppearanceSetting(raw: String?): AdvancedAppearanceSetting =
    runCatching {
        JsonInstant.decodeFromString<AdvancedAppearanceSetting>(raw ?: "{}")
    }.getOrDefault(AdvancedAppearanceSetting())

@Serializable
data class AdvancedAppearanceSetting(
    val textColorMode: TextColorMode = TextColorMode.AUTO_CLEAR,
    val liquidGlass: me.rerere.rikkahub.data.model.LiquidGlassSettings = me.rerere.rikkahub.data.model.LiquidGlassSettings(),
    val enableGlobalBackground: Boolean = false,
    val globalBackground: String? = null,
    val globalBackgroundOpacity: Float = 1f,
    val applyGlobalBackgroundToChat: Boolean = false,
    val applyGlobalBackgroundToFullscreenPreview: Boolean = true,
    val globalBackgroundBlurRadius: Float = 20f,
    val pageLiquidGlassBlurRadius: Float = 0f,
    val pageSurfaceOpacity: Float = 0.68f,
    val pageSurfaceStyle: BackgroundSurfaceStyle = BackgroundSurfaceStyle.TRANSLUCENT,
    val overlaySurfaceStyle: BackgroundSurfaceStyle = BackgroundSurfaceStyle.OPAQUE,
    val overlaySurfaceOpacity: Float = 0.82f,
    val overlaySurfaceBlurRadius: Float = 20f,
    val overlayLiquidGlassBlurRadius: Float = 0f,
    val enableNavigationGlass: Boolean = true,
    val navigationSurfaceStyle: BackgroundSurfaceStyle = BackgroundSurfaceStyle.LIQUID_GLASS,
    val navigationGlassOpacity: Float = 0.72f,
    val navigationGlassBlurRadius: Float = 24f,
    val navigationLiquidGlassBlurRadius: Float = 0f,
    val enableChatDockGlass: Boolean = false,
    val chatDockGlassOpacity: Float = 0.58f,
    val chatDockGlassBlurRadius: Float = 0f,
    val chatBubbleStyle: ChatBubbleStyle = ChatBubbleStyle.FROSTED,
    val enableChatTextReadability: Boolean = true,
    val chatTextLineHeightRatio: Float = 1.5f,
    val chatParagraphSpacingRatio: Float = 0.65f,
    val richContentStyle: RichContentStyle = RichContentStyle.TRANSLUCENT,
    val richContentSurfaceOpacity: Float = 0.62f,
    /** Chat suggestions share the rich-content surface, with a separate mobile density control. */
    val enableChatSuggestionSurface: Boolean = true,
    val chatSuggestionSurfaceOpacity: Float = 0.62f,
    val chatSuggestionBorderOpacity: Float = 0.72f,
    val chatSuggestionMaxHeight: Float = 128f,
    val inspirationAppearance: me.rerere.rikkahub.data.model.InspirationAppearance = me.rerere.rikkahub.data.model.InspirationAppearance(),
    val enableAutoAccent: Boolean = false,
    val autoAccentColorArgb: Long? = null,
    /** Palette style used when a generated theme is built from a seed color. */
    val colorStyle: AppearanceColorStyle = AppearanceColorStyle.TONAL_SPOT,
    /** Material dynamic color contrast adjustment, in the range -1..1. */
    val colorContrast: Float = 0f,
    val enableInputPerformanceEffects: Boolean = true,
    val enableTopBarPerformanceEffects: Boolean = true,
    val enableNavigationPerformanceEffects: Boolean = true,
    val enableChatDockPerformanceEffects: Boolean = true,
    val enableBubblePerformanceEffects: Boolean = true,
    val enableRichContentPerformanceEffects: Boolean = true,
    val enableGradientPerformanceEffects: Boolean = true,
    val gradientRendererMode: GradientRendererMode = GradientRendererMode.AUTO,
    val respectSystemReducedMotion: Boolean = true,
)

@Serializable
enum class GradientRendererMode {
    @SerialName("auto")
    AUTO,

    @SerialName("agsl")
    AGSL,

    @SerialName("kotlin")
    KOTLIN,
}

@Serializable
enum class TextColorMode {
    @SerialName("theme") THEME,
    @SerialName("auto_clear") AUTO_CLEAR,
    @SerialName("app_background") APP_BACKGROUND,
    @SerialName("system_wallpaper") SYSTEM_WALLPAPER,
}

@Serializable
enum class AppearanceColorStyle {
    @SerialName("tonal_spot")
    TONAL_SPOT,

    @SerialName("neutral")
    NEUTRAL,

    @SerialName("vibrant")
    VIBRANT,

    @SerialName("expressive")
    EXPRESSIVE,

    @SerialName("monochrome")
    MONOCHROME,
}

@Serializable
enum class BackgroundSurfaceStyle {
    @SerialName("opaque")
    OPAQUE,

    @SerialName("translucent")
    TRANSLUCENT,

    @SerialName("frosted")
    FROSTED,

    @SerialName("liquid_glass")
    LIQUID_GLASS,
}

fun AdvancedAppearanceSetting.normalizedChatTextLineHeightRatio(): Float =
    chatTextLineHeightRatio.coerceIn(
        MIN_CHAT_TEXT_LINE_HEIGHT_RATIO,
        MAX_CHAT_TEXT_LINE_HEIGHT_RATIO,
    )

fun AdvancedAppearanceSetting.normalizedChatParagraphSpacingRatio(): Float =
    chatParagraphSpacingRatio.coerceIn(
        MIN_CHAT_PARAGRAPH_SPACING_RATIO,
        MAX_CHAT_PARAGRAPH_SPACING_RATIO,
    )

@Serializable
enum class ChatBubbleStyle {
    @SerialName("frosted")
    FROSTED,

    @SerialName("outlined")
    OUTLINED,

    @SerialName("liquid_glass")
    LIQUID_GLASS,
}

@Serializable
enum class RichContentStyle {
    @SerialName("translucent")
    TRANSLUCENT,

    @SerialName("outlined")
    OUTLINED,
}

fun Settings.isGlobalBackgroundActive(): Boolean =
    advancedAppearanceSetting.enableGlobalBackground &&
        !advancedAppearanceSetting.globalBackground.isNullOrBlank()

fun Settings.isFullscreenPreviewBackgroundActive(): Boolean =
    isGlobalBackgroundActive() && advancedAppearanceSetting.applyGlobalBackgroundToFullscreenPreview &&
        advancedAppearanceSetting.pageSurfaceStyle != BackgroundSurfaceStyle.OPAQUE

fun Settings.configuredAssistantBackgroundCount(): Int =
    assistants.count { assistant ->
        !assistant.background.isNullOrBlank() || assistant.useGradientBackground
    }

data class ResolvedChatBackground(
    val background: String?,
    val opacity: Float,
    val blurRadius: Float,
    val useGradientBackground: Boolean,
    val usesGlobalBackground: Boolean,
    val gradientAnimation: Boolean = true,
    val gradientSpeed: Float = 1f,
    val gradientFollowTheme: Boolean = false,
    val gradientPreset: GradientBackgroundPreset = GradientBackgroundPreset.CLASSIC,
    val gradientCustomColors: GradientBackgroundCustomColors = GradientBackgroundCustomColors(),
    val gradientIntensity: Float = 1f,
    val gradientMotionScale: Float = 1f,
    val gradientBlobCount: Int = 4,
    val gradientSoftness: Float = 1f,
    val gradientAngle: Float = 0f,
    val gradientVignette: Float = 0f,
) {
    val isActive: Boolean
        get() = !background.isNullOrBlank() || useGradientBackground
}

fun Settings.isGlobalBackgroundAppliedToChat(): Boolean =
    advancedAppearanceSetting.applyGlobalBackgroundToChat && isGlobalBackgroundActive()

fun Settings.resolveChatBackground(): ResolvedChatBackground {
    val appearance = advancedAppearanceSetting
    if (isGlobalBackgroundAppliedToChat()) {
        val blurRadius = when (appearance.pageSurfaceStyle) {
            BackgroundSurfaceStyle.FROSTED -> appearance.globalBackgroundBlurRadius.coerceIn(
                MIN_GLOBAL_BACKGROUND_BLUR_RADIUS,
                MAX_GLOBAL_BACKGROUND_BLUR_RADIUS,
            )

            BackgroundSurfaceStyle.LIQUID_GLASS -> appearance.pageLiquidGlassBlurRadius.coerceIn(
                MIN_LIQUID_GLASS_BLUR_RADIUS,
                MAX_LIQUID_GLASS_BLUR_RADIUS,
            )

            BackgroundSurfaceStyle.OPAQUE,
            BackgroundSurfaceStyle.TRANSLUCENT -> 0f
        }
        return ResolvedChatBackground(
            background = appearance.globalBackground,
            opacity = appearance.globalBackgroundOpacity.coerceIn(0f, 1f),
            blurRadius = blurRadius,
            useGradientBackground = false,
            usesGlobalBackground = true,
        )
    }

    val assistant = getCurrentAssistant()
    return ResolvedChatBackground(
        background = assistant.background,
        opacity = assistant.backgroundOpacity.coerceIn(0f, 1f),
        blurRadius = assistant.backgroundBlurRadius.coerceIn(0f, MAX_GLOBAL_BACKGROUND_BLUR_RADIUS),
        useGradientBackground = assistant.useGradientBackground,
        gradientAnimation = assistant.gradientBackgroundAnimation,
        gradientSpeed = assistant.gradientBackgroundSpeed,
        gradientFollowTheme = assistant.gradientBackgroundFollowTheme,
        gradientPreset = assistant.gradientBackgroundPreset,
        gradientCustomColors = assistant.gradientBackgroundCustomColors,
        gradientIntensity = assistant.gradientBackgroundIntensity,
        gradientMotionScale = assistant.gradientBackgroundMotionScale,
        gradientBlobCount = assistant.gradientBackgroundBlobCount.coerceIn(0, 4),
        gradientSoftness = assistant.gradientBackgroundSoftness.coerceIn(0.55f, 1.5f),
        gradientAngle = assistant.gradientBackgroundAngle.coerceIn(-180f, 180f),
        gradientVignette = assistant.gradientBackgroundVignette.coerceIn(0f, 1f),
        usesGlobalBackground = false,
    )
}

fun Settings.hasActiveChatBackground(): Boolean = resolveChatBackground().isActive

fun Settings.isNavigationGlassActive(): Boolean =
    advancedAppearanceSetting.enableNavigationPerformanceEffects &&
    advancedAppearanceSetting.enableNavigationGlass &&
        advancedAppearanceSetting.navigationSurfaceStyle != BackgroundSurfaceStyle.OPAQUE &&
        hasActiveChatBackground()

fun Settings.isChatDockGlassActive(): Boolean =
    advancedAppearanceSetting.enableChatDockPerformanceEffects &&
        advancedAppearanceSetting.enableChatDockGlass && hasActiveChatBackground()

fun Settings.isEnhancedChatBubbleActive(): Boolean =
    advancedAppearanceSetting.enableBubblePerformanceEffects &&
        displaySetting.showAssistantBubble && hasActiveChatBackground()

fun Settings.isChatInputGlassActive(): Boolean =
    advancedAppearanceSetting.enableInputPerformanceEffects &&
        displaySetting.enableBlurEffect && hasActiveChatBackground()

fun Settings.chatInputContainerOpacity(): Float =
    displaySetting.inputSurfaceOpacity.coerceIn(0f, 1f)

fun Settings.chatInputContainerBlurRadius(): Float =
    displaySetting.inputBlurRadius.coerceAtLeast(0f)

fun Settings.isAutoAccentActive(): Boolean =
    isGlobalBackgroundActive() &&
        advancedAppearanceSetting.enableAutoAccent &&
        advancedAppearanceSetting.autoAccentColorArgb != null

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val backgroundGenerationProtection: Boolean = true,
    val generationWakeLock: Boolean = true,
    val notificationContentPreview: Boolean = false,
    val liveUpdateIntervalSeconds: Int = 2,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val inputBlurRadius: Float = 12f,
    val inputSurfaceOpacity: Float = 0.55f,
    val enableTopBarBlur: Boolean = true,
    val topBarBlurRadius: Float = 20f,
    val topBarSurfaceOpacity: Float = 0.65f,
    // Keep a new chat blank until the user explicitly enables starter cards.
    val showInspirationCards: Boolean = false,
    val inspirationSettings: me.rerere.rikkahub.data.model.InspirationSettings = me.rerere.rikkahub.data.model.InspirationSettings(),
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.ATTACHMENTS,
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        TOKENS,
        ATTACHMENTS,
        WORKSPACE,
        /** Kept only to read configurations written by versions before backup scopes existed. */
        @Deprecated("Use ATTACHMENTS")
        FILES,
    }

    companion object {
        val selectableBackupItems = listOf(
            BackupItem.DATABASE,
            BackupItem.TOKENS,
            BackupItem.ATTACHMENTS,
            BackupItem.WORKSPACE,
        )
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun Settings.resolveBackgroundChatModel(preferredId: Uuid?): Model? {
    val chatModels = providers
        .filter { it.enabled }
        .flatMap { it.models }
        .filter { it.type == me.rerere.ai.provider.ModelType.CHAT }
    return preferredId?.let { id -> chatModels.firstOrNull { it.id == id } }
        ?: chatModels.firstOrNull { it.id == fastModelId }
        ?: chatModels.firstOrNull { it.id == chatModelId }
        ?: chatModels.firstOrNull()
}

fun Settings.resolveEmbeddingModel(preferredId: Uuid? = embeddingModelId): Model? {
    // A provider can be disabled after a model was selected. Do not keep routing
    // background indexing/retrieval to a disabled endpoint; choose an active
    // embedding provider instead.
    val embeddingModels = providers
        .filter { it.enabled }
        .flatMap { it.models }
        .filter { it.type == me.rerere.ai.provider.ModelType.EMBEDDING }
    return preferredId?.let { id -> embeddingModels.firstOrNull { it.id == id } }
        ?: embeddingModels.firstOrNull()
}

/**
 * Resolves the small, non-interactive model used by the background memory extractor.
 * A configured model wins; fast/chat defaults are only fallbacks for older settings.
 */
fun Settings.resolveMemoryExtractionModel(preferredId: Uuid? = memoryExtractionModelId): Model? {
    if (preferredId?.let(::findModelById)?.type == me.rerere.ai.provider.ModelType.EMBEDDING) return null
    val chatModels = providers.filter { it.enabled }.flatMap { it.models }
        .filter { it.type == me.rerere.ai.provider.ModelType.CHAT }
    return preferredId?.let { id -> chatModels.firstOrNull { it.id == id } }
        ?: chatModels.firstOrNull { it.id == fastModelId }
        ?: chatModels.firstOrNull { it.id == chatModelId }
        ?: chatModels.firstOrNull()
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
