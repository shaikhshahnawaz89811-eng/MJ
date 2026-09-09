package com.mj.assistant.search

import org.json.JSONObject

/**
 * A single Tavily search request. search_depth is fixed at "basic" (1 API credit per
 * call) rather than "advanced" (2 credits) — the free tier is 1,000 credits/month, and
 * basic depth's plain snippets are already enough for the local model to answer from;
 * advanced's extra depth would just spend the monthly quota twice as fast for a use
 * case (a phone assistant's chat replies) that doesn't need it.
 */
data class TavilySearchRequest(
    val query: String,
    val includeImages: Boolean = false,
    val maxResults: Int = 5
) {
    fun toJson(): JSONObject = JSONObject()
        .put("query", query)
        .put("search_depth", "basic")
        .put("max_results", maxResults)
        .put("include_answer", true)
        .put("include_images", includeImages)
}

data class TavilyImage(val url: String, val description: String?)

data class TavilyResultItem(val title: String, val url: String, val content: String)

data class TavilySearchResponse(
    /** Tavily's own LLM-generated summary (include_answer=true) — often enough by
     * itself, with [results] kept as backup/citation material. */
    val answer: String?,
    val results: List<TavilyResultItem>,
    val images: List<TavilyImage>
)

sealed class TavilyParseResult {
    data class Ok(val response: TavilySearchResponse) : TavilyParseResult()
    data class SchemaError(val reason: String) : TavilyParseResult()
}

object TavilyJson {
    fun parseResponse(raw: String): TavilyParseResult {
        if (raw.isBlank()) return TavilyParseResult.SchemaError("empty response body")
        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return TavilyParseResult.SchemaError("invalid JSON: ${e.message}")
        }

        val answer = obj.optString("answer", "").ifBlank { null }

        val results = mutableListOf<TavilyResultItem>()
        obj.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val content = item.optString("content", "")
                if (content.isNotBlank()) {
                    results += TavilyResultItem(
                        title = item.optString("title", ""),
                        url = item.optString("url", ""),
                        content = content
                    )
                }
            }
        }

        // Defensive about shape here since it isn't pinned down to one form in Tavily's
        // own docs: with include_image_descriptions left off (this app never sets it),
        // entries may come back as plain URL strings instead of {url, description}
        // objects — handle both rather than assuming one.
        // Also only ever keeps plain http(s) links — this is external, untrusted API
        // output, and these URLs eventually reach an ACTION_VIEW Intent (see
        // MainActivity's onOpenImage), so a scheme other than http/https is dropped
        // here already, not just re-checked right before the Intent launch.
        val images = mutableListOf<TavilyImage>()
        obj.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) {
                val (url, description) = when (val item = arr.opt(i)) {
                    is JSONObject -> item.optString("url", "") to item.optString("description", "").ifBlank { null }
                    is String -> item to null
                    else -> "" to null
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    images += TavilyImage(url, description)
                }
            }
        }

        if (answer == null && results.isEmpty()) {
            return TavilyParseResult.SchemaError("empty response: no answer and no results")
        }
        return TavilyParseResult.Ok(TavilySearchResponse(answer = answer, results = results, images = images))
    }
}
