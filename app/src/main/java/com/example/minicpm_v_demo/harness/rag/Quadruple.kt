package com.example.minicpm_v_demo.harness.rag

// 角色 RAG 编译用它一并返回参考正文、来源、筛选决定和稳定引用。
/** Minimal four-value result for the prompt compiler; Kotlin only provides Pair and Triple. */
internal data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
