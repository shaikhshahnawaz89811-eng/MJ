package com.mj.assistant.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small offline conversational engine used until the real Qwen runtime is plugged in. */
class LocalAIEngine : AIEngine {
    private var turn = 0

    override suspend fun reply(input: String): String {
        val q = input.trim().lowercase(Locale.getDefault())
        turn++

        return when {
            q.contains("assalam") || q == "salam" -> when (turn % 2) {
                0 -> "Wa Alaikum Assalam 😊 Boliye, main sun rahi hoon."
                else -> "Wa Alaikum Assalam. Ji, boliye."
            }

            q.contains("kaise ho") || q.contains("how are you") ->
                if (turn % 2 == 0) "Alhamdulillah, main achhi hoon. Aap bataiye."
                else "Alhamdulillah, bilkul theek. Kya karna hai?"

            q.contains("time") || q.contains("samay") || q.contains("kitne baje") ->
                "Abhi ${SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())} ho rahe hain."

            q.contains("date") || q.contains("tarikh") || q.contains("aaj ki tareekh") ->
                "Aaj ${SimpleDateFormat("dd MMMM yyyy", Locale("hi", "IN")).format(Date())} hai."

            q.contains("tumhara naam") || q.contains("aapka naam") ->
                "Main MJ hoon. 😊"

            q == "hello" || q == "hi" || q == "hey" -> when (turn % 3) {
                0 -> "Hello 😊 Boliye."
                1 -> "Ji, main sun rahi hoon."
                else -> "Hello. Kya karna hai?"
            }

            q.contains("thank") || q.contains("shukriya") ->
                "Khushi hui. 😊"

            q == "ok" || q == "okay" || q == "theek hai" ->
                "Ji."

            q.contains("india ki capital") || q.contains("bharat ki rajdhani") ->
                "India ki capital New Delhi hai."

            q.contains("help") || q.contains("madad") ->
                "Ji, bataiye kya chahiye. Main help karti hoon."

            else -> when (turn % 3) {
                0 -> "Ji, samajh gayi. Thoda aur bataiye."
                1 -> "Haan ji. Aap kya karwana chahte hain?"
                else -> "Ji, boliye. Main sun rahi hoon."
            }
        }
    }
}
