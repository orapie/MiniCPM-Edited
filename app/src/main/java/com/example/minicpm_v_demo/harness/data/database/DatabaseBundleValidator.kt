package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File
import java.security.MessageDigest

/** Read-only boundary for portable bundles. It does not switch the app's active data source. */
object DatabaseBundleValidator {
    /** 交叉校验清单、文件摘要、来源证据和审核结果，构建运行时可使用的数据包。 */
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
        val sourceSchema = source.stringValue("schema_version")
        require(sourceSchema in setOf("2.0.0", "2.2.0") && source.stringValue("knowledge_base_version") == "v2") {
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
        validateExportIdentity(candidates, manifest, databaseHash, cutoffs, sourceSchema)
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
        if (sourceSchema == "2.2.0") {
            val edgeRows = candidates["knowledge_edges"].asJsonArray("source edges").map { it.asJsonObject("source edge") }
            require(edgeRows.map { it.stringValue("knowledge_edge_id") }.toSet().size == edgeRows.size) { "Duplicate V2.2 source edge" }
            edgeRows.forEach { edge ->
                val proof = proofsById[edge.stringValue("evidence_id")] ?: error("Unknown V2.2 edge evidence")
                val event = edge.stringValue("acquired_at_event")
                require(proof.stringValue("claim_id") == edge.stringValue("claim_id") && event in cutoffs &&
                    edge.number("acquired_sequence") in 1.0..cutoffs.getValue(event).toDouble() &&
                    edge.stringValue("authorization_status") in setOf("approved", "candidate") &&
                    edge.stringValue("belief_status") in setOf("believed", "known", "unreviewed") &&
                    edge.stringValue("acquisition_method").isNotBlank() &&
                    edge.stringValue("authorization_basis").isNotBlank() &&
                    edge.stringValue("source_verification_status").isNotBlank()) { "Invalid V2.2 source edge" }
            }
        }
        val documents = if (sourceSchema == "2.2.0") {
            val rows = required("source_corpus").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                .map { SimpleJsonParser(it).parse().asJsonObject("source document") }
            val docs = rows.associateBy { it.stringValue("source_id") }
            require(docs.size == rows.size && docs.isNotEmpty()) { "Invalid V2.2 source documents" }
            docs.mapValues { (_, doc) ->
                val text = doc.stringValue("text")
                require(sha256(text.toByteArray(Charsets.UTF_8)) == doc.stringValue("sha256")) { "V2.2 source document hash mismatch" }
                text.split("\n")
            }
        } else emptyMap()
        if (sourceSchema == "2.2.0") {
            val semantic = readObject(required("semantic_index"))
            require(source["semantic_index"].asJsonObject("semantic index") == semantic &&
                semantic.stringValue("model_name").isNotBlank() && semantic.stringValue("model_revision").isNotBlank() &&
                semantic.number("dimension") == 512.0 && semantic.stringValue("dtype") == "float32_le" &&
                semantic.stringValue("pooling") == "cls" && semantic["normalized"] == true &&
                semantic.number("document_count").toInt() == proofRows.size) { "V2.2 semantic metadata mismatch" }
            val indexPayload = listOf(semantic.stringValue("model_name"), semantic.stringValue("model_revision"),
                semantic.number("dimension").toInt().toString(), semantic.stringValue("content_template"),
                semantic.stringValue("content_sha256")).joinToString("\u001f")
            val expectedIndex = "semantic-" + sha256(indexPayload.toByteArray(Charsets.UTF_8)).take(16)
            require(semantic.stringValue("index_id") == expectedIndex) { "V2.2 semantic index identity mismatch" }
        }
        proofRows.forEach { proof ->
            val parent = parentsById[proof.stringValue("claim_id")] ?: error("Unknown evidence claim")
            val statement = parent.stringValue("statement")
            require(sha256(statement.toByteArray(Charsets.UTF_8)) == proof.stringValue("sha256") &&
                proof.number("line_end") >= proof.number("line_start") &&
                statement.lines().size == (proof.number("line_end") - proof.number("line_start") + 1).toInt()) {
                "Candidate evidence hash/range mismatch"
            }
            if (sourceSchema == "2.2.0") {
                val projection = proof["source_projection"].asJsonObject("source projection")
                val spans = projection["line_spans"].asJsonArray("source spans").map { raw ->
                    val span = raw.asJsonObject("source span")
                    V22SourceProjection.Span(span.number("line").toInt(), span.number("start").toInt(), span.number("end").toInt())
                }
                val declared = V22SourceProjection.Result(projection.stringValue("text"), projection.stringValue("text_sha256"), spans)
                require(declared.text == statement && declared.textSha256 == proof.stringValue("sha256") &&
                    projection.number("line_start") == proof.number("line_start") &&
                    projection.number("line_end") == proof.number("line_end") &&
                    projection.stringValue("original_quote_sha256") == proof.stringValue("source_quote_sha256") &&
                    proof.stringValue("source_quote") == proof.stringValue("quote")) { "V2.2 projection identity mismatch" }
                V22SourceProjection.verifyDeclared(documents[proof.stringValue("source_id")] ?: error("Missing V2.2 document"),
                    proof.number("line_start").toInt(), proof.number("line_end").toInt(), proof.stringValue("source_quote"),
                    proof.stringValue("source_quote_sha256"), declared, projection.stringValue("offset_unit"),
                    projection.stringValue("projection"))
            }
        }
        val bgePaths = setOf("bge_model", "bge_vocab", "bge_tokenizer", "bge_model_manifest", "bge_index")
        if (manifest.paths.keys.any { it in bgePaths }) {
            require(sourceSchema == "2.2.0" && bgePaths.all { it in manifest.paths }) { "Incomplete BGE bundle" }
            val semantic = readObject(required("semantic_index"))
            val model = readObject(required("bge_model_manifest"))
            require(model.stringValue("schema_version") == "bge-onnx-v1" &&
                model.stringValue("model_name") == semantic.stringValue("model_name") &&
                model.stringValue("model_revision") == semantic.stringValue("model_revision") &&
                model.number("dimensions") == 512.0 && model.number("max_length") == 512.0 &&
                model["lowercase"] == true && model["normalized"] == true &&
                model.stringValue("pooling") == "cls" &&
                model.stringValue("query_instruction") == semantic.stringValue("query_instruction") &&
                model.stringValue("model_onnx_sha256") == sha256(required("bge_model")) &&
                model.stringValue("vocab_sha256") == sha256(required("bge_vocab")) &&
                model.stringValue("tokenizer_json_sha256") == sha256(required("bge_tokenizer"))) { "BGE model contract mismatch" }
            val bge = readObject(required("bge_index"))
            require(bge.stringValue("schema_version") == "1.1" &&
                bge.stringValue("dataset_id") == manifest.datasetId && bge.stringValue("dataset_version") == manifest.datasetVersion &&
                bge.stringValue("source_database_sha256") == databaseHash &&
                bge.stringValue("semantic_index_id") == semantic.stringValue("index_id") &&
                bge.stringValue("model_name") == semantic.stringValue("model_name") &&
                bge.stringValue("model_revision") == semantic.stringValue("model_revision") && bge.number("dimension") == 512.0) {
                "BGE index identity mismatch"
            }
            val entries = bge["items"].asJsonArray("BGE items")
            require(entries.size == proofRows.size) { "BGE evidence count mismatch" }
            val seenBge = mutableSetOf<String>()
            entries.forEach { raw ->
                val entry = raw.asJsonObject("BGE item")
                val chunk = entry["chunk"].asJsonObject("BGE chunk")
                val id = chunk.stringValue("id")
                val proof = proofsById[id] ?: error("Unknown BGE evidence")
                val parent = parentsById.getValue(proof.stringValue("claim_id"))
                val metadata = chunk["metadata"].asJsonObject("BGE metadata")
                val vector = entry["embedding"].asJsonArray("BGE embedding")
                require(seenBge.add(id) && chunk.stringValue("text") == parent.stringValue("statement") &&
                    chunk.stringValue("source_path") == "database/${manifest.paths.getValue("v2_database")}" &&
                    chunk.number("start") == proof.number("line_start") && chunk.number("end") == proof.number("line_end") &&
                    metadata["dataset_id"] == manifest.datasetId && metadata["dataset_version"] == manifest.datasetVersion &&
                    metadata["claim_id"] == proof.stringValue("claim_id") && metadata["parent_claim_id"] == proof.stringValue("claim_id") &&
                    metadata["evidence_id"] == id && metadata["record_kind"] == "scene_candidate" &&
                    metadata["authorization_required"] == "true" && metadata["source_database_sha256"] == databaseHash &&
                    vector.size == 512 && vector.all { it is Double && it.isFinite() } &&
                    kotlin.math.abs(vector.sumOf { (it as Double) * it } - 1.0) < 0.00001) { "Invalid BGE evidence vector" }
            }
        }
        val runtime = readObject(required("runtime_v2"))
        validateExportIdentity(runtime, manifest, databaseHash, cutoffs, sourceSchema)
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
        if (sourceSchema == "2.2.0") {
            val prior = readObject(required("prior_reviews"))
            require(prior.stringValue("dataset_id") == manifest.datasetId && prior.stringValue("schema_version") == "1.0") {
                "V2.2 prior review identity mismatch"
            }
            val priorRows = prior["facts"].asJsonArray("prior reviews").map { it.asJsonObject("prior review") }
            val priorById = priorRows.associateBy { it.stringValue("claim_id") }
            require(priorById.size == priorRows.size && priorById.keys == reviewsById.keys) { "V2.2 prior review inventory mismatch" }
            reviewRows.forEach { review ->
                val original = priorById.getValue(review.stringValue("claim_id"))
                val migration = review["migration"].asJsonObject("review migration")
                val ignored = setOf("parent_claim_id", "evidence_id", "migration")
                require(review.filterKeys { it !in ignored } == original.filterKeys { it !in ignored } &&
                    migration.stringValue("old_parent_claim_id") == original.stringValue("parent_claim_id") &&
                    migration.stringValue("old_evidence_id") == original.stringValue("evidence_id") &&
                    migration.stringValue("new_parent_claim_id") == review.stringValue("parent_claim_id") &&
                    migration.stringValue("new_evidence_id") == review.stringValue("evidence_id") &&
                    migration.stringValue("source_sqlite_sha256") == databaseHash &&
                    migration.stringValue("method") == "unique_exact_prior_review_quote_and_source_lines" &&
                    migration.stringValue("activation_basis") == "unchanged_prior_review_exact_quote_v1" &&
                    migration.stringValue("human_independent_review") == "pending" &&
                    migration["requires_independent_review_before_activation"] == false &&
                    migration["knowledge_basis_preserved"] == true) { "V2.2 prior review migration mismatch" }
            }
        }
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
        return ValidatedDatabaseBundle(paths, manifest, characterIds.toSet(), cutoffs, authorized,
            parentIds.size, parentIds.toSet(), documents.mapValues { (_, lines) -> lines.size })
    }

    private fun validateExportIdentity(
        value: Map<String, Any?>,
        manifest: DatabaseBundleManifest,
        hash: String,
        cutoffs: Map<String, Long>,
        sourceSchema: String,
    ) {
        require(value.stringValue("schema_version") == sourceSchema && value.stringValue("dataset_id") == manifest.datasetId &&
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
    val candidateFactIds: Set<String> = emptySet(),
    val sourceLineCounts: Map<String, Int> = emptyMap(),
) {
    val authorizedFactCount: Int get() = authorizedFacts.size

    /** 按角色和剧情截止点返回通过审核且在该节点已经获知的事实。 */
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
