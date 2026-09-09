package com.mj.assistant.sketch

import org.json.JSONObject

/**
 * A single drawing primitive. Deliberately a small, closed set of simple shapes rather
 * than open-ended path data — this is what a small on-device model has a realistic
 * chance of producing correctly and consistently as JSON. Every field has a safe
 * default so a shape missing a field the model forgot still renders as *something*
 * instead of failing the whole sketch.
 */
data class Shape(
    val type: String,
    val x: Float = 0f, val y: Float = 0f,
    val x2: Float = 0f, val y2: Float = 0f,
    val w: Float = 0f, val h: Float = 0f,
    val r: Float = 0f,
    val points: List<Pair<Float, Float>> = emptyList(),
    val text: String = "",
    val color: String = "#333333",
    val filled: Boolean = false,
    val strokeWidth: Float = 3f
)

data class SketchSpec(
    // Deliberately not read from the model's JSON anywhere in SketchJson.parse below —
    // always this fixed default. Same memory-safety reasoning as DocumentScanner's
    // MAX_IMAGE_DIMENSION cap: letting a model output its own canvas size means a bad
    // reply could ask for something huge. 600x600 ARGB_8888 is a fixed, tiny ~1.4MB.
    val width: Int = 600,
    val height: Int = 600,
    val shapes: List<Shape>
)

/**
 * Deliberately explicit trigger phrases, same reasoning as WebSearch.detectQuery: a
 * small on-device model deciding "does this message want a drawing" on its own isn't
 * reliable, so the decision is made in code. Returns the description to draw (the
 * message with the trigger phrase removed), or null if untriggered.
 */
object SketchTrigger {
    private val PHRASES = listOf(
        "sketch banao", "sketch bana do", "sketch bnao",
        "drawing banao", "drawing bana do", "drawing bnao",
        "banao ek sketch", "banao ek drawing",
        "draw karo", "draw kar do"
    )

    fun detectDescription(message: String): String? {
        val lower = message.lowercase()
        val matched = PHRASES.firstOrNull { lower.contains(it) } ?: return null
        val stripped = lower.replace(matched, "").trim(' ', ':', '-', ',')
        return stripped.ifBlank { message.trim() }
    }
}

/** Detects a follow-up "give me the PDF/file" request for whichever sketch was shown
 * most recently — kept separate from SketchTrigger since this is a different message
 * (a reply to an already-shown sketch, not a new drawing request). */
object SketchExportTrigger {
    private val PHRASES = listOf(
        "pdf banao", "pdf bana do", "pdf mein do", "pdf me do", "pdf do",
        "file do", "file bana do", "zip do", "zip mein do",
        "save karo pdf", "isko pdf"
    )

    fun isExportRequest(message: String): Boolean {
        val lower = message.lowercase()
        return PHRASES.any { lower.contains(it) }
    }
}

/**
 * Turns the local model's raw text reply into a SketchSpec. Small on-device models are
 * NOT reliable at "output only JSON, nothing else" — in practice they often add a
 * sentence before/after, or wrap it in a ```json fence despite being told not to — so
 * this extracts the {...} block rather than trying to JSONObject() the whole raw string,
 * and every per-shape field is read defensively (a malformed single shape is skipped,
 * not a reason to fail the whole sketch).
 */
object SketchJson {
    fun parse(raw: String): Result<SketchSpec> = runCatching {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "model reply had no JSON object in it" }
        val obj = JSONObject(raw.substring(start, end + 1))

        val shapesArray = obj.optJSONArray("shapes")
            ?: throw IllegalStateException("no \"shapes\" array in model reply")

        val shapes = mutableListOf<Shape>()
        for (i in 0 until shapesArray.length()) {
            val s = shapesArray.optJSONObject(i) ?: continue
            val type = s.optString("type", "")
            if (type.isBlank()) continue

            val points = mutableListOf<Pair<Float, Float>>()
            s.optJSONArray("points")?.let { pts ->
                for (j in 0 until pts.length()) {
                    val p = pts.optJSONArray(j) ?: continue
                    if (p.length() >= 2) points += p.optDouble(0).toFloat() to p.optDouble(1).toFloat()
                }
            }

            shapes += Shape(
                type = type,
                x = s.optDouble("x", 0.0).toFloat(),
                y = s.optDouble("y", 0.0).toFloat(),
                x2 = s.optDouble("x2", 0.0).toFloat(),
                y2 = s.optDouble("y2", 0.0).toFloat(),
                w = s.optDouble("w", 0.0).toFloat(),
                h = s.optDouble("h", 0.0).toFloat(),
                r = s.optDouble("r", 0.0).toFloat(),
                points = points,
                text = s.optString("text", ""),
                color = s.optString("color", "#333333").ifBlank { "#333333" },
                filled = s.optBoolean("filled", false),
                strokeWidth = s.optDouble("strokeWidth", 3.0).toFloat()
            )
        }
        require(shapes.isNotEmpty()) { "model reply had a shapes array but every entry was unusable" }
        SketchSpec(shapes = shapes)
    }

    /**
     * The prompt sent to the existing local aiEngine (see the sketch branch inside
     * MainActivity's onSend) — kept here so the schema description and the parser above
     * never drift apart. The worked example is itself a labeled sketch (of a rocket, the
     * user's own example) since small models follow a shown pattern far more reliably
     * than a described one — this is also how the "label every part" requirement is
     * actually communicated, not just stated as a rule.
     */
    fun prompt(description: String): String = """
        Ek simple LABELED line-sketch banao is cheez ka, SIRF JSON format mein — koi
        extra sentence, explanation, ya ```json fence nahi, bas ek JSON object.
        Sirf ye shape types use karo: circle, rect, line, polygon, text.
        Canvas 600x600 hai, (0,0) top-left mein hai.
        ZAROORI: cheez ke har main part ke paas ek chhota "text" label lagao (jaise
        neeche ke rocket example mein "nose cone", "body", "fin", "engine") — sirf
        drawing nahi, har part ka naam bhi likhna hai.

        Example (ek rocket ka labeled sketch):
        {"shapes":[
          {"type":"polygon","points":[[280,120],[320,120],[300,60]],"color":"#333333","filled":false},
          {"type":"text","x":330,"y":90,"text":"nose cone","color":"#333333"},
          {"type":"rect","x":270,"y":120,"w":60,"h":180,"color":"#333333","filled":false},
          {"type":"text","x":340,"y":210,"text":"body","color":"#333333"},
          {"type":"polygon","points":[[270,260],[230,320],[270,300]],"color":"#333333","filled":false},
          {"type":"text","x":190,"y":325,"text":"fin","color":"#333333"},
          {"type":"circle","x":300,"y":315,"r":15,"color":"#cc3333","filled":true},
          {"type":"text","x":322,"y":320,"text":"engine","color":"#333333"}
        ]}

        Ab isi tarah, HAR PART LABEL karke, isko banao: $description
    """.trimIndent()
}
