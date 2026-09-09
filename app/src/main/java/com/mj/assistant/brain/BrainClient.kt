package com.mj.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Outcome of a single call to the AI Brain. Deliberately does NOT include a
 * "trust this" path — even [Success] only hands back a parsed-but-unvalidated
 * [BrainResponse]; validation happens in [BrainActionRegistry] afterwards.
 */
sealed class BrainCallResult {
    data class Success(val response: BrainResponse) : BrainCallResult()
    data class SchemaError(val reason: String) : BrainCallResult()
    object NotConfigured : BrainCallResult()
    object ConnectionFailed : BrainCallResult()
    object TimedOut : BrainCallResult()
    data class HttpError(val code: Int) : BrainCallResult()
    object EmptyResponse : BrainCallResult()
    data class Unknown(val message: String?) : BrainCallResult()
}

/**
 * Thin, dependency-free client for POST /v1/command (no OkHttp/Retrofit —
 * this project avoids adding Gradle dependencies it can't verify a build
 * for; see ChatHistoryStore's comment for the same rationale). Uses plain
 * HttpURLConnection + org.json, both already part of the Android platform.
 */
class BrainClient(private val secureStore: BrainSecureStore) {
    suspend fun sendCommand(request: BrainRequest): BrainCallResult = withContext(Dispatchers.IO) {
        val apiKey = secureStore.getApiKey()
        if (!secureStore.isEnabled() || apiKey.isNullOrBlank()) {
            return@withContext BrainCallResult.NotConfigured
        }
        var connection: HttpURLConnection? = null
        try {
            val url = URL(secureStore.getBaseUrl())
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Authorization header is set from decrypted memory only — never
                // interpolated into a log line anywhere in this class.
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = request.toJson().toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                return@withContext BrainCallResult.HttpError(code)
            }
            if (code !in 200..299) {
                return@withContext BrainCallResult.HttpError(code)
            }

            val stream = connection.inputStream
            val raw = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (raw.isBlank()) return@withContext BrainCallResult.EmptyResponse

            return@withContext when (val parsed = BrainJson.parseResponse(raw)) {
                is BrainParseResult.Ok -> BrainCallResult.Success(parsed.response)
                is BrainParseResult.SchemaError -> BrainCallResult.SchemaError(parsed.reason)
            }
        } catch (_: SocketTimeoutException) {
            return@withContext BrainCallResult.TimedOut
        } catch (_: IOException) {
            // Covers "AI Brain is unavailable" (connection refused, no route,
            // DNS/host down, etc.) — the orchestrator turns this into the
            // existing safe local fallback, never a crash.
            return@withContext BrainCallResult.ConnectionFailed
        } catch (e: Exception) {
            return@withContext BrainCallResult.Unknown(e.message)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}
