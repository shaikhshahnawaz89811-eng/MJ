package com.mj.assistant.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** Outcome of one call to Tavily. Mirrors BrainCallResult's shape/reasoning exactly —
 * every real-world failure (no key, no signal, slow network, bad key, rate-limited) has
 * its own case so the caller can give an honest, specific reply instead of a crash or a
 * generic "something went wrong." */
sealed class TavilyCallResult {
    data class Success(val response: TavilySearchResponse) : TavilyCallResult()
    data class SchemaError(val reason: String) : TavilyCallResult()
    object NotConfigured : TavilyCallResult()
    object ConnectionFailed : TavilyCallResult()
    object TimedOut : TavilyCallResult()
    data class HttpError(val code: Int) : TavilyCallResult()
    object EmptyResponse : TavilyCallResult()
    data class Unknown(val message: String?) : TavilyCallResult()
}

/**
 * Thin, dependency-free Tavily client — plain HttpURLConnection + org.json, both
 * already part of the Android platform, same as BrainClient. This is MJ's first
 * feature that needs the open internet rather than only the phone itself (OCR, the
 * local Qwen models, and the loopback Brain are all otherwise fully on-device); a
 * search API call is inherently a request to a server somewhere, so unlike everything
 * else in this app there is no fully-offline way to do this.
 *
 * Free tier: 1,000 API credits/month, no card required to sign up at app.tavily.com —
 * a "basic" search (what this client always sends, see TavilySearchRequest) costs 1
 * credit, so that's up to ~1,000 searches/month for one person before Tavily would ever
 * ask for payment. Verified against Tavily's own docs and pricing page, September 2026.
 */
class TavilyClient(private val secureStore: TavilySecureStore) {
    suspend fun search(request: TavilySearchRequest): TavilyCallResult = withContext(Dispatchers.IO) {
        val apiKey = secureStore.getApiKey()
        if (!secureStore.isEnabled() || apiKey.isNullOrBlank()) {
            return@withContext TavilyCallResult.NotConfigured
        }
        var connection: HttpURLConnection? = null
        try {
            val url = URL(SEARCH_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Set from decrypted memory only — never interpolated into a log line
                // anywhere in this class, same rule as BrainClient's Authorization header.
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = request.toJson().toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext TavilyCallResult.HttpError(code)
            }

            val raw = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (raw.isBlank()) return@withContext TavilyCallResult.EmptyResponse

            return@withContext when (val parsed = TavilyJson.parseResponse(raw)) {
                is TavilyParseResult.Ok -> TavilyCallResult.Success(parsed.response)
                is TavilyParseResult.SchemaError -> TavilyCallResult.SchemaError(parsed.reason)
            }
        } catch (_: SocketTimeoutException) {
            return@withContext TavilyCallResult.TimedOut
        } catch (_: IOException) {
            // No signal, DNS failure, airplane mode, etc. — the one failure mode this
            // client has that the (loopback-only) BrainClient never really hits.
            return@withContext TavilyCallResult.ConnectionFailed
        } catch (e: Exception) {
            return@withContext TavilyCallResult.Unknown(e.message)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val SEARCH_URL = "https://api.tavily.com/search"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}

/**
 * Deliberately explicit trigger phrases rather than guessing intent from words like
 * "latest"/"current" (those show up in plenty of messages that don't want a real web
 * search). This matches the honest limit already flagged for this app's on-device
 * models: reliably deciding "does this need a search" on its own is a judgment call a
 * small local model isn't good at yet, so the decision is made deterministically in
 * code instead. Extend this list rather than trying to make it "smart."
 */
object WebSearch {
    private val TRIGGER_PHRASES = listOf(
        "search karo", "search kar do", "search kijiye", "search kro",
        "net pe search", "net par search", "net se search",
        "google karo", "google kar do", "google kro",
        "internet se dhundo", "internet pe dhundo", "internet par dhundo",
        "web se dhundo", "web pe dhundo", "web search", "net se dhundo", "net pe dhundo"
    )

    fun detectQuery(message: String): String? {
        val lower = message.lowercase()
        return if (TRIGGER_PHRASES.any { lower.contains(it) }) message.trim() else null
    }

    /** Builds the prompt handed to the existing local aiEngine — same "reuse the model
     * already loaded, don't add a second AI" approach as DocumentScanner.cleanupPrompt. */
    fun buildPrompt(query: String, response: TavilySearchResponse): String = buildString {
        append("Neeche aaj ke web search results hain. Sirf inhi ke aadhar par, ")
        append("user ke sawaal ka seedha jawab Hinglish mein do — koi cheez apni taraf se mat jodo jo results mein na ho.\n\n")
        append("Sawaal: $query\n\n")
        response.answer?.let { append("Summary: $it\n\n") }
        if (response.results.isNotEmpty()) {
            append("Sources:\n")
            response.results.take(5).forEachIndexed { i, r ->
                append("${i + 1}. ${r.title}: ${r.content}\n")
            }
        }
    }
}
