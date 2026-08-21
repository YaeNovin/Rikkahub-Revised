package me.rerere.rikkahub.data.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderFailureKind
import me.rerere.ai.provider.isRetryableProviderFailure
import me.rerere.ai.provider.providerFailureKind
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Tracks network transitions for the lifetime of one provider operation. */
internal class NetworkRecoveryCoordinator(context: Context) : Closeable {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val networkVersion = AtomicLong(0L)
    private val validatedInternet = MutableStateFlow(
        connectivityManager?.hasValidatedInternet() == true,
    )
    private val networkChanges = MutableStateFlow(0L)
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetworkState()

        override fun onLosing(network: Network, maxMsToLive: Int) = updateNetworkState()

        override fun onLost(network: Network) = updateNetworkState()

        override fun onUnavailable() = updateNetworkState()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            updateNetworkState(networkCapabilities.hasValidatedInternet())
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetworkState()

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
            updateNetworkState()
    }

    init {
        callbackRegistered = connectivityManager?.let { manager ->
            runCatching {
                manager.registerDefaultNetworkCallback(callback)
                true
            }.getOrDefault(false)
        } ?: false
    }

    fun snapshot(): Long = networkVersion.get()

    /**
     * Known transient provider failures remain retryable. An otherwise unknown transport failure
     * is retried only when Android reports that the default network changed or lost validation.
     */
    suspend fun shouldRetry(error: Throwable, attemptNetworkVersion: Long): Boolean {
        if (error.isRetryableProviderFailure()) return true
        if (error.providerFailureKind() != ProviderFailureKind.UNKNOWN) return false
        val transportFailure = error is CancellationException || error.hasIOExceptionCause()
        if (!transportFailure) return false
        if (networkVersion.get() != attemptNetworkVersion || !hasValidatedInternet()) return true
        if (!callbackRegistered || connectivityManager == null) return true

        // OkHttp can report an aborted stream before Android delivers the matching network event.
        return withTimeoutOrNull(NETWORK_TRANSITION_GRACE_MILLIS) {
            networkChanges.first { it != attemptNetworkVersion }
            true
        } == true || !hasValidatedInternet()
    }

    suspend fun awaitNetworkAndBackoff(
        retryDelayMillis: Long,
        remainingDurationMillis: Long,
    ) {
        if (remainingDurationMillis <= 0L) return
        if (!callbackRegistered || connectivityManager == null) {
            delay(retryDelayMillis.coerceAtMost(remainingDurationMillis))
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + remainingDurationMillis.coerceAtMost(Long.MAX_VALUE - startedAt)
        val backoffDelay = retryDelayMillis.coerceAtMost(remainingDurationMillis)
        val backoffReadyAt = startedAt + backoffDelay.coerceAtMost(Long.MAX_VALUE - startedAt)

        while (true) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remaining == 0L) return

            if (!hasValidatedInternet()) {
                val recovered = withTimeoutOrNull(remaining) {
                    validatedInternet.first { it }
                    true
                } ?: false
                if (!recovered) return
            }

            val stableVersion = networkVersion.get()
            val now = SystemClock.elapsedRealtime()
            val waitMillis = maxOf(
                (backoffReadyAt - now).coerceAtLeast(0L),
                NETWORK_STABILITY_MILLIS,
            ).coerceAtMost((deadline - now).coerceAtLeast(0L))
            if (waitMillis > 0L) delay(waitMillis)

            if (hasValidatedInternet() && networkVersion.get() == stableVersion) return
        }
    }

    override fun close() {
        if (!callbackRegistered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        callbackRegistered = false
    }

    private fun updateNetworkState(validatedOverride: Boolean? = null) {
        val version = networkVersion.incrementAndGet()
        validatedInternet.value = validatedOverride
            ?: (connectivityManager?.hasValidatedInternet() == true)
        networkChanges.value = version
    }

    private fun hasValidatedInternet(): Boolean {
        val current = connectivityManager?.hasValidatedInternet() == true
        if (validatedInternet.value != current) validatedInternet.value = current
        return current
    }
}

private fun Throwable.hasIOExceptionCause(): Boolean =
    generateSequence(this) { it.cause }.take(16).any { it is IOException }

private fun ConnectivityManager.hasValidatedInternet(): Boolean =
    getNetworkCapabilities(activeNetwork).hasValidatedInternet()

private fun NetworkCapabilities?.hasValidatedInternet(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private const val NETWORK_STABILITY_MILLIS = 750L
private const val NETWORK_TRANSITION_GRACE_MILLIS = 500L
