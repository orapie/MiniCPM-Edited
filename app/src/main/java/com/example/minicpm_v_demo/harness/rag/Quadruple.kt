package com.example.minicpm_v_demo.harness.rag

/** Minimal four-value result for the prompt compiler; Kotlin only provides Pair and Triple. */
internal data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
