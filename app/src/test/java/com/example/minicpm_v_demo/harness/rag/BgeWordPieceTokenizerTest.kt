package com.example.minicpm_v_demo.harness.rag

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BgeWordPieceTokenizerTest {
    @Test fun tokenIdsMatchPinnedModelGolden() {
        val root = (if (File("settings.gradle.kts").isFile) File(".") else File("..")).canonicalFile
        val tokenizer = BgeWordPieceTokenizer.fromFile(File(root, "database/embedding/bge-small-zh-v1.5/vocab.txt"))
        val golden = SimpleJsonParser(File(root, "scripts/fixtures/semantic_v2_2/bge_query_golden.json").readText())
            .parse().asJsonObject("golden")
        val prefix = golden.stringValue("query_instruction")
        for (item in golden["cases"].asJsonArray("cases")) {
            val row = item.asJsonObject("case")
            val ids = row["token_ids"].asJsonArray("ids").map { (it as Double).toLong() }.toLongArray()
            assertArrayEquals("query=${row.stringValue("query")}", ids, tokenizer.encode(prefix + row.stringValue("query")))
        }
    }
}
