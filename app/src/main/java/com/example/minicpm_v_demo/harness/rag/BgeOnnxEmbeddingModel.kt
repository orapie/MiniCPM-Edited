package com.example.minicpm_v_demo.harness.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Lazily loads the bundle-specific BGE query encoder; no network or fallback. */
class BgeOnnxEmbeddingModel(
    private val modelFile: File,
    private val vocabularyFile: File,
    private val queryInstruction: String,
) : EmbeddingModel, AutoCloseable {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var tokenizer: BgeWordPieceTokenizer? = null
    private var closed = false

    @Synchronized
    override fun embed(texts: List<String>): List<DoubleArray> {
        check(!closed) { "BGE encoder is closed" }
        val tok = tokenizer ?: BgeWordPieceTokenizer.fromFile(vocabularyFile).also { tokenizer = it }
        val active = session ?: createSession().also { session = it }
        return texts.map { query ->
            val ids = tok.encode(queryInstruction + query)
            val shape = longArrayOf(1L, ids.size.toLong())
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { input ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { mask ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                        active.run(mapOf("input_ids" to input, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val output = result[0].value as Array<FloatArray>
                            require(output.size == 1 && output[0].size == 512) { "BGE output dimension mismatch" }
                            val values = output[0].map { it.toDouble() }.toDoubleArray()
                            require(values.all { it.isFinite() }) { "BGE output is not finite" }
                            val norm = sqrt(values.sumOf { it * it })
                            require(norm > 0.0 && kotlin.math.abs(norm - 1.0) < 0.001) { "BGE output is not normalized" }
                            values
                        }
                    }
                }
            }
        }
    }

    private fun createSession(): OrtSession {
        require(modelFile.isFile && vocabularyFile.isFile) { "BGE model assets are unavailable" }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            return environment.createSession(modelFile.absolutePath, options)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
        tokenizer = null
    }
}
