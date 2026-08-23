package com.mj.assistant.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalAIEngine : AIEngine {
    override suspend fun reply(input: String): String {
        val q = input.trim().lowercase(Locale.getDefault())
        return when {
            q.contains("assalam") || q == "salam" ->
                "Wa Alaikum Assalam 😊 Main MJ hoon. Boliye, kya karna hai?"
            q.contains("kaise ho") || q.contains("how are you") ->
                "Alhamdulillah, main bilkul ready hoon. Aap bataiye kya karna hai?"
            q.contains("time") || q.contains("samay") || q.contains("kitne baje") ->
                "Abhi ${SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())} ho rahe hain."
            q.contains("date") || q.contains("tarikh") || q.contains("aaj ki tareekh") ->
                "Aaj ${SimpleDateFormat("dd MMMM yyyy", Locale("hi", "IN")).format(Date())} hai."
            q.contains("torch") && (q.contains("on") || q.contains("chala")) ->
                "Ji, torch on kar deti hoon. Real device-control module next stage mein connect hoga."
            q.contains("torch") && (q.contains("off") || q.contains("band")) ->
                "Ji, torch off kar deti hoon. Real device-control module next stage mein connect hoga."
            q.contains("tumhara naam") || q.contains("aapka naam") ->
                "Mera naam MJ hai. Aap mujhe MJ keh sakte hain."
            q.contains("hello") || q.contains("hi") || q.contains("hey") ->
                "Hello 😊 Boliye, main sun rahi hoon."
            else ->
                "Ji, samajh gayi. Abhi basic local chat module chal raha hai. Qwen brain connect hone ke baad main is baat ko aur achhe se samajh kar jawab dungi."
        }
    }
}
