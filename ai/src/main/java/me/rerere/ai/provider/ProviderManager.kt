package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient
import me.rerere.ai.provider.providers.openai.miniMaxVideoSetting

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(client: OkHttpClient, context: Context) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
    }

    fun supports(setting: ProviderSetting, capability: ProviderCapability): Boolean =
        getProviderByType(setting).supports(capability)

    fun imageGenerationConstraints(
        setting: ProviderSetting,
        model: Model,
    ): ImageGenerationConstraints = getProviderByType(setting)
        .imageGenerationConstraints(setting, model)

    fun videoGenerationConstraints(
        setting: ProviderSetting,
        model: Model,
    ): VideoGenerationConstraints {
        val videoSetting = setting.miniMaxVideoSetting(model) ?: setting
        return getProviderByType(videoSetting).videoGenerationConstraints(videoSetting, model)
    }

    suspend fun createVideoGenerationTask(
        setting: ProviderSetting,
        params: VideoGenerationParams,
    ): VideoGenerationTaskSnapshot {
        val videoSetting = setting.miniMaxVideoSetting(params.model) ?: setting
        return getProviderByType(videoSetting).createVideoGenerationTask(videoSetting, params)
    }

    suspend fun getVideoGenerationTask(
        setting: ProviderSetting,
        model: Model,
        taskId: String,
    ): VideoGenerationTaskSnapshot {
        val videoSetting = setting.miniMaxVideoSetting(model) ?: setting
        return getProviderByType(videoSetting).getVideoGenerationTask(videoSetting, model, taskId)
    }

    suspend fun cancelVideoGenerationTask(
        setting: ProviderSetting,
        model: Model,
        taskId: String,
    ): Boolean = getProviderByType(setting)
        .cancelVideoGenerationTask(setting, model, taskId)
}
