package me.rerere.rikkahub.data.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal suspend fun waitForNetworkBeforeRetry(
    context: Context,
    retryDelayMillis: Long,
    remainingDurationMillis: Long,
) {
    val boundedDelay = retryDelayMillis.coerceAtMost(remainingDurationMillis)
    if (boundedDelay > 0L) delay(boundedDelay)
    val networkWaitMillis = (remainingDurationMillis - boundedDelay)
        .coerceAtLeast(0L)
        .coerceAtMost(MAX_NETWORK_RECOVERY_WAIT_MILLIS)
    if (networkWaitMillis == 0L) return

    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
    if (connectivityManager.hasValidatedInternet()) return

    withTimeoutOrNull(networkWaitMillis) {
        suspendCancellableCoroutine { continuation ->
            lateinit var callback: ConnectivityManager.NetworkCallback

            fun complete() {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                if (continuation.isActive) continuation.resume(Unit)
            }

            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities.hasValidatedInternet()) complete()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasValidatedInternet()) complete()
                }
            }

            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { complete() }
            if (connectivityManager.hasValidatedInternet()) complete()
        }
    }
}

private fun ConnectivityManager.hasValidatedInternet(): Boolean =
    getNetworkCapabilities(activeNetwork).hasValidatedInternet()

private fun NetworkCapabilities?.hasValidatedInternet(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private const val MAX_NETWORK_RECOVERY_WAIT_MILLIS = 15_000L
