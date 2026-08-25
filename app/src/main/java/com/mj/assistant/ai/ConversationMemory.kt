package com.mj.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent memory that is deliberately independent from chat-screen state.
 *
 * Important facts and relationships are not tied to a chat session, History
 * tab, or Activity instance. Closing/reopening the app or starting a new chat
 * therefore does not erase them. Only an explicit memory clear can erase them.
 */
class ConversationMemory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var turnsCache: MutableList<JSONObject>? = null
    private var factsCache: MutableList<Fact>? = null

    data class Fact(val key: String, val value: String, val updatedAt: Long)

    fun remember(key: String, value: String) {
        val cleanKey = key.trim().take(120)
        val cleanValue = value.trim().take(500)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return

        val facts = readFacts().toMutableList()
        facts.removeAll { it.key.equals(cleanKey, ignoreCase = true) }
        facts.add(Fact(cleanKey, cleanValue, System.currentTimeMillis()))
        // Long-term facts/relationships are deliberately retained for months
        // and years instead of being evicted like short-term chat turns.
        writeFacts(facts.takeLast(MAX_FACTS))
    }

    fun find(key: String): Fact? =
        readFacts().lastOrNull { it.key.equals(key.trim(), ignoreCase = true) }

    fun allFacts(): List<Fact> = readFacts()

    fun addRelationship(relation: String, description: String) {
        remember("relationship:$relation", description)
    }

    fun relationships(): List<Fact> =
        readFacts().filter { it.key.startsWith("relationship:", ignoreCase = true) }

    /** Short/episodic memory. This is separate from long-term facts. */
    fun addRecentTurn(user: String, assistant: String) {
        val turns = readTurns().toMutableList()
        turns.add(
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("user", user.take(700))
                .put("assistant", assistant.take(700))
        )
        writeTurns(turns.takeLast(MAX_TURNS))
    }

    fun recentTurns(limit: Int = 12): List<Pair<String, String>> =
        readTurns().takeLast(limit).map {
            it.optString("user") to it.optString("assistant")
        }

    /** Find older conversational turns that share meaningful words with a new message. */
    fun findRelevantTurns(input: String, limit: Int = 6): List<Pair<String, String>> {
        val tokens = input.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
        if (tokens.isEmpty()) return emptyList()

        return readTurns().map { item ->
            val user = item.optString("user")
            val itemTokens = user.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .toSet()
            val score = tokens.count { it in itemTokens }
            Triple(score, item.optLong("time"), user to item.optString("assistant"))
        }.filter { it.first > 0 }
            .sortedWith(compareByDescending<Triple<Int, Long, Pair<String, String>>> { it.first }
                .thenByDescending { it.second })
            .take(limit)
            .map { it.third }
    }

    /** Clears only persisted memory when the user explicitly asks for it. */
    fun clearMemory() {
        turnsCache = null
        factsCache = null
        prefs.edit().remove(KEY_FACTS).remove(KEY_TURNS).commit()
    }

    private fun readFacts(): List<Fact> {
        factsCache?.let { return it }
        val raw = prefs.getString(KEY_FACTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val loaded = buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(Fact(o.optString("key"), o.optString("value"), o.optLong("time")))
                }
            }
            factsCache = loaded.toMutableList()
            loaded
        }.getOrDefault(emptyList())
    }

    private fun writeFacts(facts: List<Fact>) {
        val array = JSONArray()
        facts.forEach {
            array.put(
                JSONObject()
                    .put("key", it.key)
                    .put("value", it.value)
                    .put("time", it.updatedAt)
            )
        }
        factsCache = facts.toMutableList()
        // commit() is intentional for explicit memory: once MJ says it remembered
        // something, the data is synchronously persisted before the turn ends.
        prefs.edit().putString(KEY_FACTS, array.toString()).commit()
    }

    private fun readTurns(): List<JSONObject> {
        // Fix (Bug 2): the cached fast-path used to stringify and re-parse
        // every cached item on every call, which defeated the point of
        // caching and got worse as the turn count grew toward MAX_TURNS.
        // Callers here (recentTurns/findRelevantTurns) only ever read fields
        // off these objects, and writeTurns() already makes its own fresh
        // snapshot before assigning turnsCache, so it's safe to hand back the
        // cached objects directly instead of round-tripping through JSON.
        turnsCache?.let { cached -> return cached }
        val raw = prefs.getString(KEY_TURNS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val loaded = buildList {
                for (i in 0 until array.length()) add(array.getJSONObject(i))
            }
            turnsCache = loaded.toMutableList()
            loaded
        }.getOrDefault(emptyList())
    }

    private fun writeTurns(turns: List<JSONObject>) {
        val snapshot = turns.map { JSONObject(it.toString()) }.toMutableList()
        turnsCache = snapshot
        val array = JSONArray()
        snapshot.forEach { array.put(it) }
        // Recent conversation is not an explicit long-term fact. Persist it
        // asynchronously so a large history write never holds up the visible reply.
        prefs.edit().putString(KEY_TURNS, array.toString()).apply()
    }

    companion object {
        private val STOP_WORDS = setOf(
            "hai", "haan", "hmm", "mujhe", "mera", "meri", "mere", "tum", "tumhe",
            "aap", "kya", "kaise", "kaisa", "ke", "ki", "ko", "se", "me", "main",
            "aur", "bhi", "to", "ye", "wo", "woh", "ek", "the", "thi", "tha", "ho",
            "kar", "karo", "bata", "batao", "please"
        )
        private const val PREFS = "mj_memory"
        private const val KEY_FACTS = "facts"
        private const val KEY_TURNS = "turns"
        private const val MAX_FACTS = 500
        private const val MAX_TURNS = 1000
    }
}
