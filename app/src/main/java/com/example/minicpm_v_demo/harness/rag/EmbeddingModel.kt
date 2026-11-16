package com.example.minicpm_v_demo.harness.rag

/** 定义批量文本到向量的接口，让同一向量索引可以配合不同编码实现。 */
interface EmbeddingModel {
    fun embed(texts: List<String>): List<DoubleArray>
}

fun cosine(left: DoubleArray, right: DoubleArray): Double {
    require(left.size == right.size) { "Embedding dimensions do not match" }
    val size = left.size
    var sum = 0.0
    for (index in 0 until size) {
        sum += left[index] * right[index]
    }
    return sum
}

fun normalize(vector: DoubleArray): DoubleArray {
    var squared = 0.0
    for (value in vector) {
        squared += value * value
    }
    if (squared == 0.0) return vector
    val norm = kotlin.math.sqrt(squared)
    for (index in vector.indices) {
        vector[index] = vector[index] / norm
    }
    return vector
}

