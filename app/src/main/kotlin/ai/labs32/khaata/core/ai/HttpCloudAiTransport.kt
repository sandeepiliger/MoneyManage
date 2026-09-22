package ai.labs32.khaata.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CloudAiTransport] over the platform's own HttpURLConnection.
 *
 * One small POST per question does not justify a networking library in the APK. HTTPS is
 * enforced by [CloudAiConfig] before a request can be built, and neither the body nor the reply
 * is ever logged -- both describe the user's finances.
 */
@Singleton
class HttpCloudAiTransport @Inject constructor() : CloudAiTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String): CloudAiResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.doOutput = true
                connection.useCaches = false
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    // A reply is a few hundred characters; cap what is read so a misbehaving
                    // endpoint cannot stream megabytes into memory.
                    val buffer = CharArray(MAX_RESPONSE_CHARS)
                    var total = 0
                    while (total < buffer.size) {
                        val read = reader.read(buffer, total, buffer.size - total)
                        if (read < 0) break
                        total += read
                    }
                    String(buffer, 0, total)
                }.orEmpty()
                CloudAiResponse(status, text)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000
        const val MAX_RESPONSE_CHARS = 64 * 1024
    }
}
