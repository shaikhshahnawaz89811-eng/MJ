package com.mj.assistant.ai

/** Keeps native model memory predictable on phones: only one GGUF model may be loaded at a time. */
object ModelRuntimeCoordinator {
    private var owner: Any? = null

    @Synchronized
    fun tryClaim(candidate: Any): Boolean {
        val current = owner
        return when {
            current == null -> { owner = candidate; true }
            current === candidate -> true
            else -> false
        }
    }

    @Synchronized
    fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }
}
