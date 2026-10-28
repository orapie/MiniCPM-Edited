package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File
import java.security.MessageDigest

/** Read-only boundary for portable bundles. It does not switch the app's active data source. */
object DatabaseBundleValidator {
    fun validate(paths: DatabaseDataPaths): ValidatedDatabaseBundle {
        val manifest = DatabaseBundleManifest.parse(readObjectText(paths.manifestFile))
        require(manifest.schemaVersion == "1.0") { "Unsupported database bundle schema" }
        require(manifest.authorizationPolicy == DatabaseBundleManifest.AUTHORIZATION_POLICY) {
            "Unsupported database authorization policy"
        }
        require(manifest.datasetId.matches(Regex("[a-z0-9_-]+")) && manifest.datasetVersion.isNotBlank()) {
            "Invalid database identity"
        }
        require(manifest.capabilities.keys == setOf("vector", "v1", "v2", "timed_state")) { "Unknown/missing capabilities" }
        require(manifest.computeFingerprint() == manifest.datasetFingerprint) { "Database fingerprint mismatch" }
        require(manifest.files.isNotEmpty()) { "Empty database file manifest" }
        manifest.files.forEach { (relative, hash) ->
            require(relative != "manifest.json" && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid file hash entry" }
            val file = paths.resolve(relative)
            require(file.isFile && sha256(file) == hash) { "Database file/hash mismatch: $relative" }
        }
        manifest.paths.forEach { (_, relative) -> paths.resolve(relative) }

        fun required(name: String): File {
            val relative = manifest.paths[name] ?: error("Missing database path: $name")
            require(relative in manifest.files) { "Unhashed database path: $name" }
            return paths.resolve(relative).also { require(it.isFile) { "Missing database file: $name" } }
        }
        val characterRoot = manifest.paths["characters"] ?: error("Missing characters path")
        val charactersDir = paths.resolve(characterRoot)
        require(charactersDir.isDirectory) { "Missing database characters" }
        val characterFiles = charactersDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        require(characterFiles.isNotEmpty()) { "Empty database characters" }
        val cards = characterFiles.map { file ->
            require("$characterRoot/${file.name}" in manifest.files) { "Unhashed character file" }
            readObject(file)
        }
        val characterIds = cards.map { it.stringValue("npc_id") }
        require(characterIds.distinct().size == characterIds.size && manifest.defaultCharacterId in characterIds) {
            "Invalid/duplicate/default character ID"
        }
        val eventRows = required("story_events").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            .map { SimpleJsonParser(it).parse().asJsonObject("event") }
        val eventIds = eventRows.map { it.stringValue("event_id") }
        require(eventIds.distinct().size == eventIds.size && eventIds.isNotEmpty()) { "Missing/duplicate events" }

        if (manifest.supports("v1")) {
            required("v1_manifest")
            error("V1 bundle validation is not implemented in Phase 0")
        }
        if (manifest.supports("timed_state")) {
            required("timed_state")
            error("Timed-state bundle validation is not implemented in Phase 0")
        }
        require(manifest.supports("v2")) { "Phase 0 requires a V2 bundle" }
        val source = readObject(required("v2_manifest"))
        require(source.stringValue("schema_version") == "2.0.0" && source.stringValue("knowledge_base_version") == "v2") {
            "Invalid V2 source schema"
        }
        val cutoffs = source["cutoffs"].asJsonObject("cutoffs").mapValues { (_, raw) ->
            raw.asJsonObject("cutoff").number("sequence").toLong()
        }
        require(cutoffs.keys == eventIds.toSet() && manifest.defaultStoryCutoff in cutoffs) { "Event / cutoff mismatch" }
        cards.forEach { card ->
            val scope = card["knowledge_scope"].asJsonObject("knowledge_scope")
            require(scope.stringValue("story_cutoff") in cutoffs) { "Unknown character cutoff" }
            require(scope["world_fact_refs"].asJsonArray("world_fact_refs").all { it in cutoffs }) { "Unknown character fact" }
        }
        val defaultCard = cards.single { it.stringValue("npc_id") == manifest.defaultCharacterId }
        require(defaultCard["knowledge_scope"].asJsonObject("scope").stringValue("story_cutoff") == manifest.defaultStoryCutoff) {
            "Default character cutoff mismatch"
        }
        val databaseHash = sha256(required("v2_database"))
        val candidates = readObject(required("candidate_v2"))
        validateExportIdentity(candidates, manifest, databaseHash, cutoffs)
        require(candidates.stringValue("usage") == "candidate_only_not_character_knowledge") { "Unsafe candidate usage" }
        val parentRows = candidates["claims"].asJsonArray("candidate claims").map { it.asJsonObject("claim") }
        val parentIds = parentRows.map { it.stringValue("claim_id") }
        val parentsById = parentRows.associateBy { it.stringValue("claim_id") }
        require(parentIds.distinct().size == parentIds.size) { "Duplicate candidate claims" }
        parentRows.forEach { require(it.stringValue("event_id") in cutoffs) { "Unknown candidate event" } }
        candidates["knowledge_edges"].asJsonArray("candidate edges").forEach { raw ->
            val edge = raw.asJsonObject("candidate edge")
            require(edge.stringValue("character_id") in characterIds && edge.stringValue("claim_id") in parentIds) {
                "Invalid candidate edge"
            }
        }
        val proofRows = candidates["evidence"].asJsonArray("candidate evidence").map { it.asJsonObject("evidence") }
        val proofsById = proofRows.associateBy { it.stringValue("evidence_id") }
        require(proofsById.size == proofRows.size) { "Duplicate evidence ID" }
        proofRows.forEach { proof ->
            val parent = parentsById[proof.stringValue("claim_id")] ?: error("Unknown evidence claim")
            val statement = parent.stringValue("statement")
            require(sha256(statement.toByteArray(Charsets.UTF_8)) == proof.stringValue("sha256") &&
                proof.number("line_end") >= proof.number("line_start") &&
                statement.lines().size == (proof.number("line_end") - proof.number("line_start") + 1).toInt()) {
                "Candidate evidence hash/range mismatch"
            }
        }
        val runtime = readObject(required("runtime_v2"))
        validateExportIdentity(runtime, manifest, databaseHash, cutoffs)
        require(runtime.stringValue("authorization_policy") == manifest.authorizationPolicy) { "Runtime policy mismatch" }
        val facts = runtime["claims"].asJsonArray("authorized claims").map { it.asJsonObject("authorized claim") }
        val factsById = facts.associateBy { it.stringValue("claim_id") }
        require(facts.isNotEmpty() && factsById.size == facts.size) { "Missing/duplicate authorized claims" }
        val reviews = readObject(required("reviewed_facts"))
        require(reviews.stringValue("dataset_id") == manifest.datasetId && reviews.stringValue("schema_version") == "1.0") {
            "Reviews identity mismatch"
        }
        val reviewRows = reviews["facts"].asJsonArray("reviews").map { it.asJsonObject("review") }
        val reviewsById = reviewRows.associateBy { it.stringValue("claim_id") }
        require(reviewsById.size == reviewRows.size && reviewsById.keys == factsById.keys) { "Review / export ID mismatch" }
        facts.forEach { fact ->
            val review = reviewsById.getValue(fact.stringValue("claim_id"))
            val proof = proofsById[fact.stringValue("evidence_id")] ?: error("Unknown reviewed evidence")
            val parent = parentsById.getValue(fact.stringValue("parent_claim_id"))
            require(fact.stringValue("statement").length in 1..120 && fact.stringValue("claim_scope") == "story") {
                "Non-atomic authorized statement"
            }
            require(fact.stringValue("parent_claim_id") in parentIds && fact.stringValue("event_id") in cutoffs) {
                "Invalid authorized parent/event"
            }
            require(fact.stringValue("source_tier") == "canon" && fact.stringValue("canon_status") == "accepted" &&
                fact.stringValue("verification_status") == "source_span_verified") { "Unsupported source policy" }
            val audit = fact["authorization_review"].asJsonObject("authorization review")
            require(audit.stringValue("review_method") == "dialogue_context_inspection" &&
                audit.stringValue("reviewed_by").isNotBlank() && audit.stringValue("knowledge_basis").length >= 10) {
                "Missing explicit epistemic review"
            }
            require(review.stringValue("statement") == fact.stringValue("statement") &&
                review.stringValue("parent_claim_id") == fact.stringValue("parent_claim_id") &&
                review.stringValue("evidence_id") == fact.stringValue("evidence_id") &&
                review.number("line_end") == fact.number("valid_from_sequence")) { "Review / authorized fact mismatch" }
            require(proof.stringValue("claim_id") == parent.stringValue("claim_id") &&
                parent.stringValue("event_id") == fact.stringValue("event_id") &&
                review.number("line_start") >= proof.number("line_start") &&
                review.number("line_end") >= review.number("line_start") &&
                review.number("line_end") <= proof.number("line_end")) { "Reviewed evidence range mismatch" }
            val lines = parent.stringValue("statement").lines()
            val offset = proof.number("line_start").toInt()
            val quote = lines.subList(review.number("line_start").toInt() - offset,
                review.number("line_end").toInt() - offset + 1).joinToString("\n").trim()
            require(quote == review.stringValue("quote")) { "Reviewed source quote mismatch" }
        }
        val edges = runtime["knowledge_edges"].asJsonArray("authorized edges").map { it.asJsonObject("edge") }
        require(edges.map { it.stringValue("claim_id") }.toSet() == factsById.keys && edges.size == facts.size) {
            "Authorized facts / edges mismatch"
        }
        edges.forEach { edge ->
            val id = edge.stringValue("claim_id")
            val fact = factsById.getValue(id)
            val review = reviewsById.getValue(id)
            require(edge.stringValue("character_id") in characterIds &&
                edge.stringValue("character_id") == review.stringValue("character_id") &&
                edge.stringValue("belief_status") == "believed" &&
                edge.stringValue("acquisition_method") in setOf("direct", "witnessed", "reported", "inferred") &&
                edge.stringValue("acquisition_method") == review.stringValue("acquisition_method")) { "Unsafe knowledge grant" }
            require(edge.stringValue("acquired_at_event") == fact.stringValue("event_id") &&
                edge.number("acquired_sequence") == fact.number("valid_from_sequence") &&
                edge.number("acquired_sequence") <= cutoffs.getValue(fact.stringValue("event_id"))) { "Unsafe acquisition time" }
        }
        if (manifest.supports("vector")) {
            DatabaseVectorContractValidator.validate(readObject(required("vector_index")), manifest,
                databaseHash, parentRows, proofRows, facts, reviewRows)
        } else {
            require(manifest.embedding.isEmpty() && "vector_index" !in manifest.paths) { "Vector artifact/capability mismatch" }
        }
        val authorized = edges.map { edge ->
            val fact = factsById.getValue(edge.stringValue("claim_id"))
            DatabaseAuthorizedFact(fact.stringValue("claim_id"), fact.stringValue("statement"),
                edge.stringValue("character_id"), edge.number("acquired_sequence").toLong(),
                edge.stringValue("acquisition_method"))
        }
        return ValidatedDatabaseBundle(paths, manifest, characterIds.toSet(), cutoffs, authorized, parentIds.size)
    }

    private fun validateExportIdentity(
        value: Map<String, Any?>,
        manifest: DatabaseBundleManifest,
        hash: String,
        cutoffs: Map<String, Long>,
    ) {
        require(value.stringValue("schema_version") == "2.0.0" && value.stringValue("dataset_id") == manifest.datasetId &&
            value.stringValue("dataset_version") == manifest.datasetVersion && value.stringValue("source_database_sha256") == hash) {
            "Export identity/source hash mismatch"
        }
        val exportedCutoffs = value["cutoffs"].asJsonObject("exported cutoffs").mapValues { (_, raw) ->
            raw.asJsonObject("cutoff").number("sequence").toLong()
        }
        require(exportedCutoffs == cutoffs) { "Export cutoff mismatch" }
    }

    private fun readObjectText(file: File): String {
        require(file.isFile) { "Missing database file: $file" }
        return file.readText(Charsets.UTF_8)
    }
    private fun readObject(file: File): Map<String, Any?> =
        SimpleJsonParser(readObjectText(file)).parse().asJsonObject(file.name)
    private fun Map<String, Any?>.number(key: String): Double =
        (this[key] as? Double)?.also { require(it.isFinite() && it >= 0 && it % 1.0 == 0.0) { "Invalid $key" } }
            ?: error("Missing numeric $key")
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

data class ValidatedDatabaseBundle(
    val paths: DatabaseDataPaths,
    val manifest: DatabaseBundleManifest,
    val characterIds: Set<String>,
    val cutoffs: Map<String, Long>,
    val authorizedFacts: List<DatabaseAuthorizedFact>,
    val candidateFactCount: Int,
) {
    val authorizedFactCount: Int get() = authorizedFacts.size

    fun authorizedFactsAt(characterId: String, storyCutoff: String): List<DatabaseAuthorizedFact> {
        require(characterId in characterIds) { "Unknown character in current database" }
        val sequence = cutoffs[storyCutoff] ?: error("Unknown cutoff in current database")
        return authorizedFacts.filter { it.characterId == characterId && it.acquiredSequence <= sequence }
    }
}

data class DatabaseAuthorizedFact(
    val claimId: String,
    val statement: String,
    val characterId: String,
    val acquiredSequence: Long,
    val acquisitionMethod: String,
)
