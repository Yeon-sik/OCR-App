package com.pricetrace.receiptocr.pricetrace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class PriceObservationHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

internal data class PriceObservationHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface PriceObservationHttpTransport {
    suspend fun execute(request: PriceObservationHttpRequest): PriceObservationHttpResponse
}

internal class HttpsPriceObservationHttpTransport : PriceObservationHttpTransport {
    override suspend fun execute(request: PriceObservationHttpRequest): PriceObservationHttpResponse =
        withContext(Dispatchers.IO) {
            require(request.url.startsWith("https://", ignoreCase = true)) {
                "Only HTTPS PriceTrace endpoints are allowed"
            }
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                request.headers.forEach(::setRequestProperty)
                if (request.body != null) doOutput = true
            }
            try {
                request.body?.toByteArray(StandardCharsets.UTF_8)?.let { bytes ->
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                PriceObservationHttpResponse(status, stream?.use(::readLimited).orEmpty())
            } finally {
                connection.disconnect()
            }
        }

    private fun readLimited(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("PriceTrace response is too large")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
