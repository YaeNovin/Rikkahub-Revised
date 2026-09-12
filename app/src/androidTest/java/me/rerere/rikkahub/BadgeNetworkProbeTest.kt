package me.rerere.rikkahub

import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import me.rerere.rikkahub.ui.components.richtext.IntrinsicSvgDecoderFactory
import me.rerere.rikkahub.ui.components.richtext.toInlineImageBitmap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** Opt-in device probe; no model requests, chat data or credentials are involved. */
class BadgeNetworkProbeTest {
    @Test fun publicBadgeFetchDecodeAndPreviewPixels() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("badgeNetworkProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = GlobalContext.get().get<OkHttpClient>().newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
        val loader = ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }))
        }.build()
        try {
            for ((index, url) in listOf(
                "https://img.shields.io/badge/build-passing-brightgreen",
                "https://badgen.net/badge/build/passing/green",
                "https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white",
                "https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white",
            ).withIndex()) {
                val result = loader.execute(ImageRequest.Builder(context).data(url).size(Size.ORIGINAL)
                    .decoderFactory(IntrinsicSvgDecoderFactory).build())
                assertTrue("$url: ${(result as? ErrorResult)?.throwable?.stackTraceToString()}", result is SuccessResult)
                val image = requireNotNull(result.image)
                assertTrue("$url unexpected dimensions ${image.width} x ${image.height}", image.width > 20 && image.height in 12..40)
                val bitmap = image.toInlineImageBitmap(image.width * 4, image.height * 4)
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    if (index < 2) assertTrue("Badge has no green fill", pixels.any { android.graphics.Color.green(it) > 100 && android.graphics.Color.green(it) > android.graphics.Color.red(it) * 1.3 })
                    assertTrue("Badge text is missing: $url", pixels.count { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) > 200 && android.graphics.Color.blue(it) > 200 } > 50)
                } finally { bitmap.recycle() }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val gpu = renderBadgeOnHardwareCanvas(image)
                    try {
                        val pixels = IntArray(gpu.width * gpu.height)
                        gpu.getPixels(pixels, 0, gpu.width, 0, 0, gpu.width, gpu.height)
                        assertTrue("GPU text is missing: $url", pixels.count { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) > 200 && android.graphics.Color.blue(it) > 200 } > 50)
                        if (index == 2) {
                            val logo = gpu.getPixel(30 + (11 * 3.5f).toInt(), 40 + (14 * 3.5f).toInt())
                            assertTrue("GPU Kotlin logo is missing", android.graphics.Color.red(logo) > 230 && android.graphics.Color.green(logo) > 230)
                        }
                        saveBadgeProbeBitmap(context, gpu, "style-$index-gpu")
                    } finally { gpu.recycle() }
                }
            }
        } finally { loader.shutdown() }
    }
}
