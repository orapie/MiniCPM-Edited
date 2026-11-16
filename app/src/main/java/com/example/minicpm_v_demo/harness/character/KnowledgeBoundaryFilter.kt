package com.example.minicpm_v_demo.harness.character

/** 根据角色知识范围、事件可见性和剧情时间线审核旧版事件，并为每个候选记录准入结果。 */
class KnowledgeBoundaryFilter {
    fun filter(
        candidates: List<RetrievedEvent>,
        npc: CharacterCard,
        storyCutoff: String,
        events: List<StoryEvent>,
        query: String,
    ): Pair<List<RetrievedEvent>, List<RetrievalDecision>> {
        val indexes = events.associate { it.eventId to it.timeIndex }
        val cutoffIndex = indexes[storyCutoff] ?: error("unknown story cutoff: $storyCutoff")
        val decisions = mutableListOf<RetrievalDecision>()
        val allowed = mutableListOf<RetrievedEvent>()

        if (MetaTopics.any { it.lowercase() in query.lowercase() }) {
            for (candidate in candidates) {
                decisions += RetrievalDecision(candidate.event.eventId, false, "meta_or_author_topic", candidate.score)
            }
            return allowed to decisions
        }

        for (candidate in candidates) {
            val event = candidate.event
            val knowledge = event.knowledgeByCharacter[npc.npcId]
            var isAllowed = true
            var reason = "authorized"
            when {
                event.eventId !in npc.knowledgeScope.worldFactRefs -> {
                    isAllowed = false
                    reason = "not_in_npc_knowledge_scope"
                }
                event.timeIndex > cutoffIndex -> {
                    isAllowed = false
                    reason = "future_event"
                }
                knowledge == null -> {
                    isAllowed = false
                    reason = "no_knowledge_source"
                }
                indexes[knowledge.availableAfter] == null || indexes.getValue(knowledge.availableAfter) > cutoffIndex -> {
                    isAllowed = false
                    reason = "not_yet_learned"
                }
                npc.npcId !in event.visibility && "public" !in event.visibility -> {
                    isAllowed = false
                    reason = "visibility_denied"
                }
            }
            decisions += RetrievalDecision(event.eventId, isAllowed, reason, candidate.score)
            if (isAllowed) allowed += candidate
        }
        return allowed to decisions
    }

    private companion object {
        val MetaTopics = listOf("作者", "创作意图", "文学评论", "读者", "这篇小说", "本章", "章节", "角色卡", "系统提示", "prompt")
    }
}

