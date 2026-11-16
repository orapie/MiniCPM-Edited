package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.data.database.ValidatedDatabaseBundle

/** Filter source-verified references by cutoff; this does not grant character knowledge. */
internal object CharacterReferenceFilter {
    data class Result(val allowed: List<SearchResult>, val rejected: List<RagContextDecision>)

    /** 逐项验证向量候选在当前角色与剧情截止点下能否作为原文参考，并保留拒绝原因。 */
    fun filter(results: List<SearchResult>, bundle: ValidatedDatabaseBundle?, character: String, cutoff: String): Result {
        val sequence = bundle?.cutoffs?.get(cutoff)
        val authorized = if (bundle != null && sequence != null && character in bundle.characterIds)
            bundle.authorizedFactsAt(character, cutoff).mapTo(mutableSetOf()) { it.claimId } else emptySet()
        val allowed = mutableListOf<SearchResult>()
        val rejected = mutableListOf<RagContextDecision>()
        results.forEach { result ->
            val chunk = result.chunk
            val metadata = chunk.metadata
            val claim = metadata["claim_id"].orEmpty()
            val expectedSource = bundle?.manifest?.paths?.get("v2_database")?.let { "database/$it" }
            val reason = when {
                bundle == null || sequence == null || character !in bundle.characterIds -> "reference_time_unknown"
                metadata["dataset_id"] != bundle.manifest.datasetId || chunk.sourcePath != expectedSource -> "reference_source_unverified"
                chunk.start < 1 || chunk.end < chunk.start -> "reference_time_unknown"
                metadata["record_kind"] == "scene_candidate" && claim !in bundle.candidateFactIds -> "reference_parent_unknown"
                metadata["record_kind"] == "reviewed_fact" && claim !in authorized -> "reference_fact_not_authorized"
                metadata["record_kind"] !in setOf("scene_candidate", "reviewed_fact") -> "reference_kind_unknown"
                chunk.end.toLong() > sequence -> "reference_after_or_crosses_cutoff"
                else -> null
            }
            if (reason == null) allowed += result else rejected += RagContextDecision(
                source = chunk.sourcePath, start = chunk.start, end = chunk.end, score = round6(result.score),
                decision = "dropped", reason = reason, originalChars = chunk.text.length, selectedChars = 0,
                estimatedOriginalTokens = 0, estimatedSelectedTokens = 0, chunkId = chunk.id,
                parentClaimId = metadata["parent_claim_id"] ?: claim,
            )
        }
        return Result(allowed, rejected)
    }
}
