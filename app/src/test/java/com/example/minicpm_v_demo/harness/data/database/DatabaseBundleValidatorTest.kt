package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.HashEmbeddingModel
import com.example.minicpm_v_demo.harness.rag.VectorIndex
import com.example.minicpm_v_demo.harness.session.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Gradle runs JVM tests in app/; only these two explicit repository roots are allowed. */
fun databaseTestSource(): DatabaseDataPaths {
    val repository = if (File("settings.gradle.kts").isFile) File(".") else File("..")
    require(File(repository, "settings.gradle.kts").isFile) { "Explicit repository root not found" }
    return DatabaseDataPaths.fromRepositoryRoot(repository)
}

class DatabaseBundleValidatorTest {
    @Test
    fun preparedDatabaseHasCrossLanguageFingerprintAndSeparateCandidateAuthorizationCounts() {
        val bundle = DatabaseBundleValidator.validate(databaseTestSource())
        assertEquals("three_body_2", bundle.manifest.datasetId)
        assertEquals("luo_ji", bundle.manifest.defaultCharacterId)
        assertEquals(5, bundle.characterIds.size)
        assertEquals(125, bundle.candidateFactCount)
        assertEquals(3, bundle.authorizedFactCount)
        assertTrue(bundle.manifest.supports("vector"))
        assertFalse(bundle.manifest.supports("v1"))
        assertFalse(bundle.manifest.supports("timed_state"))
        assertTrue(bundle.manifest.supports("v2"))
    }

    @Test
    fun axiomsAreAvailableOnlyAfterTheyWereHeardAndOnlyToReviewedCharacter() {
        val bundle = DatabaseBundleValidator.validate(databaseTestSource())
        assertEquals(setOf("reviewed-luo-ji-classmate", "reviewed-luo-ji-profession"),
            bundle.authorizedFactsAt("luo_ji", "evt-001").map { it.claimId }.toSet())
        val axiom = bundle.authorizedFactsAt("luo_ji", "evt-002")
            .single { it.claimId == "reviewed-luo-ji-civilization-axioms" }
        assertEquals("reported", axiom.acquisitionMethod)
        assertEquals(47L, axiom.acquiredSequence)
        assertTrue(axiom.statement.contains("叶文洁向罗辑提出"))
        assertTrue(bundle.authorizedFactsAt("shi_qiang", "evt-125").isEmpty())
        assertTrue(bundle.authorizedFacts.none { it.claimId.startsWith("claim-evt-") })
    }

    @Test
    fun pathsRejectTraversalAbsoluteWindowsAndEscapingSymlinks() {
        val temp = Files.createTempDirectory("database-path-test").toFile()
        try {
            val paths = DatabaseDataPaths(File(temp, "database").apply { mkdirs() })
            listOf("../character/a", "/tmp/a", "C:/old/a", "x\\y", "a/../b", "a//b", "./x").forEach { path ->
                fails("Unsafe") { paths.resolve(path) }
            }
            val outside = File(temp, "outside").apply { mkdirs() }
            Files.createSymbolicLink(File(paths.rootDir, "link").toPath(), outside.toPath())
            fails("escapes") { paths.resolve("link/a") }
            assertEquals(File(paths.rootDir, "characters/luo_ji.json"), paths.resolve("characters/luo_ji.json"))
        } finally {
            // Remove the link first: cleanup must not traverse a linked directory.
            File(temp, "database/link").delete()
            temp.deleteRecursively()
        }
    }

    @Test
    fun missingDatabaseDoesNotSearchLegacySiblings() {
        val temp = Files.createTempDirectory("database-no-fallback-test").toFile()
        try {
            File(temp, "character/manifest.json").apply { requireNotNull(parentFile).mkdirs(); writeText("{}") }
            File(temp, "harness/rag/index.json").apply { requireNotNull(parentFile).mkdirs(); writeText("{}") }
            fails("Missing database file") { DatabaseBundleValidator.validate(DatabaseDataPaths.fromRepositoryRoot(temp)) }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun rejectsTamperedControlledFileEvenWhenLegacySourceExists() = withCopy { paths ->
        File(paths.rootDir, "characters/luo_ji.json").appendText(" ")
        fails("file/hash mismatch") { DatabaseBundleValidator.validate(paths) }
    }

    @Test
    fun rejectsExportFromDifferentDatasetEvenWithUpdatedFileHash() = withCopy { paths ->
        val file = paths.resolve("runtime/android/knowledge_fallback.json")
        file.writeText(file.readText().replace("\"dataset_id\": \"three_body_2\"", "\"dataset_id\": \"other\""))
        refreshFileHash(paths, "runtime/android/knowledge_fallback.json")
        fails("identity/source hash") { DatabaseBundleValidator.validate(paths) }
    }

    @Test
    fun rejectsSceneRelevancePromotedIntoAuthorizedExport() = withCopy { paths ->
        val file = paths.resolve("runtime/android/knowledge_fallback.json")
        file.writeText(file.readText().replace("\"acquisition_method\": \"direct\"",
            "\"acquisition_method\": \"character_scene_relevance\""))
        refreshFileHash(paths, "runtime/android/knowledge_fallback.json")
        fails("Unsafe knowledge grant") { DatabaseBundleValidator.validate(paths) }
    }

    @Test
    fun rejectsEditedReviewQuoteEvenWhenManifestHashesAreRefreshed() = withCopy { paths ->
        val file = paths.resolve("reviewed/atomic_facts.json")
        file.writeText(file.readText().replace("我是罗辑，杨冬的高中同学", "我是罗辑，杨冬的大学同学"))
        refreshFileHash(paths, "reviewed/atomic_facts.json")
        fails("source quote mismatch") { DatabaseBundleValidator.validate(paths) }
    }

    @Test
    fun rejectsCapabilityThatHasNoDeclaredArtifact() = withCopy { paths ->
        val manifest = DatabaseBundleManifest.parse(paths.manifestFile.readText())
            .let { it.copy(paths = it.paths - "vector_index") }
        rewriteManifest(paths, manifest)
        fails("Missing database path: vector_index") { DatabaseBundleValidator.validate(paths) }
    }

    @Test
    fun generatedVectorMatchesKotlinEmbeddingAndRetrievesTheReviewedProfession() {
        val paths = databaseTestSource()
        val manifest = DatabaseBundleValidator.validate(paths).manifest
        val file = paths.resolve(manifest.paths.getValue("vector_index"))
        val root = SimpleJsonParser(file.readText()).parse().asJsonObject("index")
        val item = root["items"].asJsonArray("items").map { it.asJsonObject("item") }
            .single { it["chunk"].asJsonObject("chunk")["id"] == "reviewed-luo-ji-profession" }
        val chunk = item["chunk"].asJsonObject("chunk")
        val generated = item["embedding"].asJsonArray("embedding").map { it as Double }.toDoubleArray()
        assertArrayEquals(HashEmbeddingModel().embedOne(chunk["text"] as String), generated, 1e-12)
        val results = VectorIndex.load(file).search("大学社会学", HashEmbeddingModel(), 128)
        assertEquals(128, results.size)
        assertEquals("reviewed-luo-ji-profession", results.first().chunk.id)
        assertEquals("true", results.first().chunk.metadata["authorization_required"])
        assertEquals("three_body_2", results.first().chunk.metadata["dataset_id"])
    }

    @Test
    fun rejectsWrongVectorDimensionEvenAfterFileHashRefresh() = withCopy { paths ->
        val file = paths.resolve("runtime/android/vector/index.json")
        val root = SimpleJsonParser(file.readText()).parse().asJsonObject("index").toMutableMap()
        val items = root["items"].asJsonArray("items").toMutableList()
        val item = items.first().asJsonObject("item").toMutableMap()
        item["embedding"] = listOf(1.0)
        items[0] = item
        root["items"] = items
        file.writeText(jsonObject(root))
        refreshFileHash(paths, "runtime/android/vector/index.json")
        fails("dimension/value") { DatabaseBundleValidator.validate(paths) }
    }

    private fun withCopy(block: (DatabaseDataPaths) -> Unit) {
        val temp = Files.createTempDirectory("database-contract-test").toFile()
        try {
            val source = databaseTestSource()
            val paths = DatabaseDataPaths(File(temp, "database").apply { mkdirs() })
            val manifest = DatabaseBundleManifest.parse(source.manifestFile.readText())
            (manifest.files.keys + "manifest.json").forEach { relative ->
                val target = paths.resolve(relative)
                requireNotNull(target.parentFile).mkdirs()
                source.resolve(relative).copyTo(target)
            }
            block(paths)
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun refreshFileHash(paths: DatabaseDataPaths, relative: String) {
        val manifest = DatabaseBundleManifest.parse(paths.manifestFile.readText())
        val hash = MessageDigest.getInstance("SHA-256").digest(paths.resolve(relative).readBytes())
            .joinToString("") { "%02x".format(it) }
        rewriteManifest(paths, manifest.copy(files = manifest.files + (relative to hash)))
    }

    private fun rewriteManifest(paths: DatabaseDataPaths, manifest: DatabaseBundleManifest) {
        val root = SimpleJsonParser(paths.manifestFile.readText()).parse().asJsonObject("manifest").toMutableMap()
        root["capabilities"] = manifest.capabilities
        root["paths"] = manifest.paths
        root["files"] = manifest.files.map { (path, hash) -> mapOf("path" to path, "sha256" to hash) }
        root["dataset_fingerprint"] = manifest.computeFingerprint()
        paths.manifestFile.writeText(jsonObject(root))
    }

    private fun fails(message: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected failure containing '$message', got $error", error?.message?.contains(message) == true)
    }
}
