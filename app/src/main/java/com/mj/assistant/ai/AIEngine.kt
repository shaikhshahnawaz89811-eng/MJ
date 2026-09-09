package com.mj.assistant.ai

interface AIEngine {
    suspend fun reply(input: String): String
}
