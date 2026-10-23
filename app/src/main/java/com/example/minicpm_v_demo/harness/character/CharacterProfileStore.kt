package com.example.minicpm_v_demo.harness.character

/**
 * Local, evidence-linked profile retrieval for one character card.
 *
 * Profile records are derived from the reviewed card at runtime so existing
 * cards remain backward compatible.  This is deliberately separate from V2
 * story claims: profile records are trusted self-description, not time-varying
 * story knowledge.
 */
class CharacterProfileStore {
    fun retrieve(
        card: CharacterCard,
        userInput: String,
        analysis: CharacterQueryAnalysis,
        limit: Int,
        preferredRecordId: String? = null,
    ): ProfileRetrieval {
        require(limit >= 0) { "limit must not be negative" }
        val index = indexFor(card)
        if (limit == 0) return ProfileRetrieval(emptyList(), 0, 0)
        val preferredFacet = preferredRecordId?.let { id -> index.records.firstOrNull { it.id == id }?.facet }
        val allowedFacets = preferredFacet?.let(::setOf) ?: allowedFacets(analysis)
        val queryTerms = ProfileTerms.terms(userInput)
        val matchedIds = queryTerms.flatMapTo(linkedSetOf()) { term -> index.recordIdsByTerm[term].orEmpty() }
        val candidates = index.records
            .asSequence()
            .filter { record -> record.facet in allowedFacets }
            .map { record ->
                val matchedTerms = queryTerms.intersect(index.termsByRecordId.getValue(record.id))
                ProfileMatch(
                    record = record,
                    matchedTerms = matchedTerms,
                    score = facetBoost(record.facet, analysis) +
                        matchedTerms.size.toDouble() / maxOf(1, queryTerms.size) +
                        (if (record.id in matchedIds) 0.25 else 0.0) +
                        (if (preferredRecordId != null && record.id != preferredRecordId) 2.0 else 0.0),
                )
            }
            .sortedWith(compareByDescending<ProfileMatch> { it.score }.thenBy { it.record.id })
            .toList()
        return ProfileRetrieval(
            matches = candidates.take(limit),
            candidateCount = candidates.size,
            lexicalMatchCount = candidates.count { it.matchedTerms.isNotEmpty() },
        )
    }

    fun allRecords(card: CharacterCard): List<ProfileRecord> = indexFor(card).records

    private fun indexFor(card: CharacterCard): ProfileIndex {
        val records = buildRecords(card)
        val termsByRecordId = records.associate { record ->
            record.id to ProfileTerms.terms(record.label + record.statement + record.keywords.joinToString(" "))
        }
        val idsByTerm = mutableMapOf<String, MutableSet<String>>()
        termsByRecordId.forEach { (id, terms) -> terms.forEach { term -> idsByTerm.getOrPut(term) { linkedSetOf() } += id } }
        return ProfileIndex(records, termsByRecordId, idsByTerm)
    }

    private fun buildRecords(card: CharacterCard): List<ProfileRecord> {
        val identity = card.identity
        val records = mutableListOf<ProfileRecord>()
        records += ProfileRecord(
            id = "profile_identity",
            facet = ProfileFacet.IDENTITY,
            label = "你的身份",
            statement = "${identity.name}。${identity.worldviewPosition}",
            keywords = listOf(identity.name) + identity.aliases + listOf("身份", "是谁", "名字"),
            evidenceRefs = evidenceFor(card, "identity_core.worldview_position"),
        )
        records += atomize(card, ProfileFacet.BACKGROUND, "background", "你的来历", identity.background, listOf("来历", "出身", "来自", "过去"))
        records += atomize(card, ProfileFacet.MOTIVATION, "core_motivations", "你的动机与目标", identity.coreMotivations, listOf("动机", "目的", "想要", "最想", "想去", "计划"))
        records += atomize(card, ProfileFacet.VALUES, "values", "你的立场与原则", identity.values, listOf("原则", "立场", "底线", "选择"))
        records += atomize(card, ProfileFacet.TRAITS, "psychological_traits", "你的性情与习惯", identity.psychologicalTraits, listOf("性格", "习惯", "喜欢", "讨厌"))
        records += atomize(card, ProfileFacet.BEHAVIOR, "behavior_rules", "你的行事准则", identity.behaviorRules, listOf("行事", "如何做", "判断", "应对"))
        return records
    }

    private fun atomize(
        card: CharacterCard,
        facet: ProfileFacet,
        cardKey: String,
        label: String,
        statements: List<String>,
        keywords: List<String>,
    ): List<ProfileRecord> = statements.mapIndexed { index, statement ->
        val evidenceKey = "identity_core.$cardKey[$index]"
        ProfileRecord(
            id = "profile_${facet.name.lowercase()}_$index",
            facet = facet,
            label = label,
            statement = statement,
            keywords = keywords,
            evidenceRefs = evidenceFor(card, evidenceKey, "identity_core.$cardKey"),
        )
    }

    private fun evidenceFor(card: CharacterCard, vararg keys: String): List<String> = keys.asSequence()
        .mapNotNull(card.profileEvidenceRefs::get)
        .firstOrNull()
        .orEmpty()

    private fun allowedFacets(analysis: CharacterQueryAnalysis): Set<ProfileFacet> = buildSet {
        if (analysis.hasIntent(CharacterQueryIntent.IDENTITY)) add(ProfileFacet.IDENTITY)
        if (analysis.hasIntent(CharacterQueryIntent.ORIGIN)) add(ProfileFacet.BACKGROUND)
        if (analysis.hasIntent(CharacterQueryIntent.PREFERENCE) || analysis.hasIntent(CharacterQueryIntent.MOTIVATION)) {
            add(ProfileFacet.MOTIVATION)
            add(ProfileFacet.VALUES)
        }
        if (analysis.hasIntent(CharacterQueryIntent.VALUES)) add(ProfileFacet.VALUES)
        if (analysis.hasIntent(CharacterQueryIntent.TRAITS)) add(ProfileFacet.TRAITS)
        if (isEmpty() && analysis.requests(CharacterKnowledgeSurface.PROFILE)) addAll(ProfileFacet.entries)
    }

    private fun facetBoost(facet: ProfileFacet, analysis: CharacterQueryAnalysis): Double = when (facet) {
        ProfileFacet.IDENTITY -> if (analysis.hasIntent(CharacterQueryIntent.IDENTITY)) 3.0 else 0.0
        ProfileFacet.BACKGROUND -> if (analysis.hasIntent(CharacterQueryIntent.ORIGIN)) 3.0 else 0.0
        ProfileFacet.MOTIVATION -> if (
            analysis.hasIntent(CharacterQueryIntent.PREFERENCE) || analysis.hasIntent(CharacterQueryIntent.MOTIVATION)
        ) 3.0 else 0.0
        ProfileFacet.VALUES -> if (analysis.hasIntent(CharacterQueryIntent.VALUES)) 3.0 else 0.5
        ProfileFacet.TRAITS -> if (analysis.hasIntent(CharacterQueryIntent.TRAITS)) 3.0 else 0.0
        ProfileFacet.BEHAVIOR -> 0.0
    }
}

data class ProfileRetrieval(
    val matches: List<ProfileMatch>,
    val candidateCount: Int,
    val lexicalMatchCount: Int,
)

data class ProfileMatch(
    val record: ProfileRecord,
    val matchedTerms: Set<String>,
    val score: Double,
)

data class ProfileRecord(
    val id: String,
    val facet: ProfileFacet,
    val label: String,
    val statement: String,
    val keywords: List<String>,
    val evidenceRefs: List<String>,
)

enum class ProfileFacet { IDENTITY, BACKGROUND, MOTIVATION, VALUES, TRAITS, BEHAVIOR }

private data class ProfileIndex(
    val records: List<ProfileRecord>,
    val termsByRecordId: Map<String, Set<String>>,
    val recordIdsByTerm: Map<String, Set<String>>,
)

private object ProfileTerms {
    fun terms(text: String): Set<String> {
        val normalized = text.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，。！？、：；“”‘’（）《》【】,.!?;:'\\\"()\\[\\]]"), "")
        val chinese = Regex("[\\u3400-\\u9fff]").findAll(normalized).joinToString("") { it.value }
        return buildSet {
            Regex("[a-z0-9_]{2,}").findAll(normalized).forEach { add(it.value) }
            for (index in 0 until maxOf(0, chinese.length - 1)) add(chinese.substring(index, index + 2))
            for (index in 0 until maxOf(0, chinese.length - 2)) add(chinese.substring(index, index + 3))
        }
    }
}
