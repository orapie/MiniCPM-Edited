package com.example.minicpm_v_demo.harness.data

import java.io.File

fun harnessAssetsDir(): File {
    val candidates = listOf(
        File("app/src/main/assets/harness"),
        File("src/main/assets/harness"),
    )
    return candidates.firstOrNull { it.isDirectory }
        ?: error("Cannot locate test harness assets directory")
}
