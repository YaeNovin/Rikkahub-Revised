package me.rerere.rikkahub.utils

import android.content.Context
import me.rerere.common.android.DiagnosticClient
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R

/** Capture the emitting build, rather than the build later viewing an old log. */
internal fun diagnosticClientInfo(context: Context) = DiagnosticClient(
    name = context.getString(R.string.app_name),
    packageName = context.packageName,
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE.toLongOrNull(),
    buildType = BuildConfig.BUILD_TYPE,
)
