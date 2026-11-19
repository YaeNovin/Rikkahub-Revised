package me.rerere.rikkahub.web

import android.content.Context
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings

internal fun Settings.withSharedWebServices(native: Settings): Settings = copy(
    providers = native.providers,
    searchServices = native.searchServices,
    searchCommonOptions = native.searchCommonOptions,
    searchServiceSelected = native.searchServiceSelected,
    mcpServers = native.mcpServers,
)

/** A persistent Web profile. Only provider/search/MCP definitions are shared. */
class WebSettings(context: Context, scope: AppScope, native: SettingsStore) {
    val store = SettingsStore(context, scope, native)
}
