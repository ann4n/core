package org.simpmusic.aiservice

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

class VocalRemoverException(
    message: String,
) : Exception(message)

/**
 * Removes vocals through MVSEP's API (https://mvsep.com/en/full_api): upload the song, poll the
 * job, download the instrumental. A free MVSEP account gets 50 separations a day, one at a time.
 *
 * The song is uploaded rather than linked because a googlevideo URL only works from the IP that
 * resolved it, so MVSEP's servers could not fetch it.
 */
class VocalRemover(
    // A generous socket timeout: MVSEP answers an upload only once it has stored the whole file.
    private val client: HttpClient = HttpClient { install(HttpTimeout) { socketTimeoutMillis = 120_000 } },
) {
    /** Starts a separation and returns the job's hash. */
    suspend fun createJob(
        apiToken: String,
        audio: ByteArray,
        fileName: String,
    ): String {
        val response =
            client.submitFormWithBinaryData(
                url = "$BASE_URL/separation/create",
                formData =
                    formData {
                        append("api_token", apiToken)
                        append("sep_type", SEP_TYPE_BS_ROFORMER)
                        append("output_format", OUTPUT_FORMAT_M4A)
                        append(
                            "audiofile",
                            audio,
                            Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"") },
                        )
                    },
            )
        val json = response.jsonOrNull()
        if (json?.get("success")?.jsonPrimitive?.booleanOrNull != true) throw VocalRemoverException(errorMessage(json, response))
        return json["data"]?.jsonObject?.get("hash")?.jsonPrimitive?.contentOrNull
            ?: throw VocalRemoverException("MVSEP returned no job hash")
    }

    /**
     * Polls the job until it is done and returns the instrumental's URL. [onStatus] gets MVSEP's
     * status while the job waits ("waiting", "processing", "distributing", "merging").
     */
    suspend fun awaitInstrumentalUrl(
        hash: String,
        onStatus: (String) -> Unit,
    ): String {
        while (true) {
            val response = client.get("$BASE_URL/separation/get") { parameter("hash", hash) }
            val json = response.jsonOrNull() ?: throw VocalRemoverException(errorMessage(null, response))
            when (val status = json["status"]?.jsonPrimitive?.contentOrNull) {
                "done" -> return instrumentalUrl(json)
                "failed", "not_found" -> throw VocalRemoverException(errorMessage(json, response))
                else -> onStatus(status ?: "waiting")
            }
            delay(POLL_INTERVAL)
        }
    }

    /**
     * Reads a googlevideo stream URL in 5 MiB ranges, the chunk size the Android player uses,
     * since googlevideo throttles one long un-ranged request.
     */
    suspend fun fetchStream(url: String): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var start = 0L
        while (true) {
            val response = client.get(url) { header(HttpHeaders.Range, "bytes=$start-${start + STREAM_CHUNK - 1}") }
            if (response.status.value == 416) break // asked past the end of a file that is a whole number of chunks
            if (!response.status.isSuccess()) throw VocalRemoverException("Downloading the song failed: HTTP ${response.status.value}")
            val chunk = response.bodyAsBytes()
            chunks += chunk
            start += chunk.size
            // 200 means the server ignored the range and sent everything.
            if (response.status.value == 200 || chunk.size < STREAM_CHUNK) break
        }
        val audio = ByteArray(start.toInt())
        var offset = 0
        chunks.forEach {
            it.copyInto(audio, offset)
            offset += it.size
        }
        return audio
    }

    suspend fun download(url: String): ByteArray {
        val response = client.get(url)
        if (!response.status.isSuccess()) throw VocalRemoverException("Downloading the instrumental failed: HTTP ${response.status.value}")
        return response.bodyAsBytes()
    }

    private suspend fun HttpResponse.jsonOrNull(): JsonObject? = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()

    private fun errorMessage(
        json: JsonObject?,
        response: HttpResponse,
    ): String =
        json
            ?.get("data")
            ?.let { runCatching { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?: if (response.status.value == 401) "Invalid MVSEP API token" else "MVSEP request failed: HTTP ${response.status.value}"

    companion object {
        private const val BASE_URL = "https://mvsep.com/api"

        // BS Roformer (vocals, instrumental), on MVSEP's default model for it, which MVSEP keeps current.
        private const val SEP_TYPE_BS_ROFORMER = "40"

        // m4a is AAC in MP4, which the Android player's extractors already read; its mp3 default is not.
        private const val OUTPUT_FORMAT_M4A = "3"
        private val POLL_INTERVAL = 5.seconds
        private const val STREAM_CHUNK = 5L * 1024 * 1024

        /** The non-vocal stem of a finished job: MVSEP names it "Instrumental" or "Other" depending on the model. */
        internal fun instrumentalUrl(done: JsonObject): String =
            done["data"]
                ?.jsonObject
                ?.get("files")
                ?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { !it["type"]?.jsonPrimitive?.contentOrNull.equals("vocals", ignoreCase = true) }
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw VocalRemoverException("MVSEP returned no instrumental")
    }
}
