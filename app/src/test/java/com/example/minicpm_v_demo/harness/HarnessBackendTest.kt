package com.example.minicpm_v_demo.harness

import com.example.minicpm_v_demo.LlamaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessBackendTest {
    @Test
    fun structuredRequestKeepsSystemAndUserOnSeparateRuntimePaths() = runBlocking {
        val backend = RecordingBackend()

        val output = backend.sendChatPrompt(
            HarnessChatRequest(
                systemPrompt = "system-policy",
                userPrompt = "user-question",
            ),
            predictLength = 128,
        ).toList()

        assertEquals(
            listOf("clear", "system:system-policy", "user:user-question:128"),
            backend.calls,
        )
        assertEquals(listOf("answer"), output)
    }

    @Test
    fun userOnlyRequestDoesNotResetOrInventSystemPrompt() = runBlocking {
        val backend = RecordingBackend()

        backend.sendChatPrompt(
            HarnessChatRequest(userPrompt = "plain-question"),
            predictLength = 64,
        ).toList()

        assertEquals(listOf("user:plain-question:64"), backend.calls)
    }

    @Test
    fun requestRejectsBlankRoleContent() {
        assertThrows(IllegalArgumentException::class.java) {
            HarnessChatRequest(userPrompt = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessChatRequest(userPrompt = "question", systemPrompt = " ")
        }
    }

    @Test
    fun defaultBackendTokenPreflightDoesNotChangeRequestOrdering() = runBlocking {
        val backend = RecordingBackend()

        backend.sendChatPrompt(
            HarnessChatRequest(systemPrompt = "policy", userPrompt = "question"),
            predictLength = 32,
        ).toList()

        assertEquals(listOf("clear", "system:policy", "user:question:32"), backend.calls)
    }

    private class RecordingBackend : HarnessBackend {
        override val state: StateFlow<LlamaState> = MutableStateFlow(LlamaState.ModelReady)
        val calls = mutableListOf<String>()

        override suspend fun loadModel(modelPath: String) = Unit
        override suspend fun unloadModel() = Unit
        override suspend fun setSystemPrompt(prompt: String) {
            calls += "system:$prompt"
        }

        override suspend fun clearContext() {
            calls += "clear"
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            calls += "user:$message:$predictLength"
            emit("answer")
        }

        override fun cancelGeneration() = Unit
        override fun resetToInitialized() = Unit
        override fun destroy() = Unit
    }
}
