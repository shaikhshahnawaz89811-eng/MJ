package com.mj.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the VISIBLE chat transcript (both user and assistant messages),
 * independent from [ConversationMemory]'s facts/turns store.
 *
 * Phase 3 fix: previously the visible `messages` list in MJApp() lived only
 * in Compose `remember { }` state, so it reset to just the welcome message
 * on every process death / app relaunch, even though the AI's own long-term
 * memory survived. This store closes that gap using the same
 * SharedPreferences+JSON-blob pattern ConversationMemory already uses
 * successfully in this exact build (no Room/KSP dependency added, since this
 * project has no annotation-processor toolchain and no Gradle wrapper, and
 * adding one could not be verified from this sandbox).
 *
 * Reads/writes are plain data classes; UI-only concerns (animation flags)
 * stay in MainActivity's ChatMessage and are not persisted.
 */
class ChatHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class PersistedMessage(
        val id: Long,
        val text: String,
        val fromUser: Boolean,
        val actionType: String? = null,
        val actionEnabled: Boolean = false,
        // Document Scan -> OCR -> PDF (see MJ_OCR_PDF_SCAN_PLAN.pdf): absolute path
        // under context.filesDir to a generated scan PDF, not the PDF bytes — same
        // "store the path, not base64" reasoning as the rest of this file.
        val attachmentPath: String? = null,
        // Tavily web search (see com.mj.assistant.search): remote image URLs from a
        // search reply, stored as plain strings (an external link, not a local file).
        val imageUrls: List<String> = emptyList(),
        // Sketch feature (see com.mj.assistant.sketch): absolute path under
        // context.filesDir to the sketch's saved PNG, shown inline in the chat bubble.
        val sketchImagePath: String? = null,
        // Plain "attach as photo" flow (see DocumentScanner.saveAttachedImage): absolute
        // path under context.filesDir to a photo the USER sent as-is, no OCR/PDF
        // involved. Set on the user's own message, never the assistant's — mirrors
        // sketchImagePath's convention but on the other side of the conversation.
        val userImagePath: String? = null
    )

    fun load(): List<PersistedMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        PersistedMessage(
                            id = o.optLong("id"),
                            text = o.optString("text"),
                            fromUser = o.optBoolean("fromUser"),
                            actionType = o.optString("actionType").ifBlank { null },
                            actionEnabled = o.optBoolean("actionEnabled"),
                            attachmentPath = o.optString("attachmentPath").ifBlank { null },
                            imageUrls = o.optJSONArray("imageUrls")?.let { arr ->
                                buildList { for (j in 0 until arr.length()) add(arr.optString(j)) }
                            } ?: emptyList(),
                            sketchImagePath = o.optString("sketchImagePath").ifBlank { null },
                            userImagePath = o.optString("userImagePath").ifBlank { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Full-list save, capped, async (`.apply()`) so it never blocks the sender's UI turn. */
    fun save(messages: List<PersistedMessage>) {
        val capped = messages.takeLast(MAX_MESSAGES)
        val array = JSONArray()
        capped.forEach { m ->
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("text", m.text)
                    .put("fromUser", m.fromUser)
                    .put("actionType", m.actionType ?: "")
                    .put("actionEnabled", m.actionEnabled)
                    .put("attachmentPath", m.attachmentPath ?: "")
                    .put("imageUrls", JSONArray(m.imageUrls))
                    .put("sketchImagePath", m.sketchImagePath ?: "")
                    .put("userImagePath", m.userImagePath ?: "")
            )
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_MESSAGES).commit()
    }

    companion object {
        private const val PREFS = "mj_chat_history"
        private const val KEY_MESSAGES = "messages"
        private const val MAX_MESSAGES = 400
    }
}
