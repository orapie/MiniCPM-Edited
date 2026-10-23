package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File

/**
 * Loads time-bounded current-state assets.  These are intentionally separate
 * from CharacterCard: a historical mention of an item is never proof that the
 * character currently carries it.
 */
class CharacterStateLoader(
    private val stateDir: File,
    events: List<StoryEvent>,
    private val knownCharacterIds: Set<String> = emptySet(),
) {
    private val cutoffOrder = events.associate { it.eventId to it.timeIndex }
    private val documents = mutableMapOf<String, CharacterStateDocument?>()

    fun currentState(characterId: String, cutoff: String): TimedCharacterState? {
        require(cutoff in cutoffOrder) { "Unknown state cutoff: $cutoff" }
        val document = loadCharacterStateOrNull(characterId) ?: return null
        return document.states.singleOrNull { state -> isActiveAt(state, cutoff) }
    }

    fun loadCharacterStateOrNull(characterId: String): CharacterStateDocument? =
        documents.getOrPut(characterId) {
            val file = File(stateDir, "$characterId.json")
            if (!file.isFile) return@getOrPut null
            parseDocument(file, characterId)
        }

    private fun parseDocument(file: File, expectedCharacterId: String): CharacterStateDocument {
        val root = SimpleJsonParser(file.readText(Charsets.UTF_8)).parse().asJsonObject("character state")
        require(root.stringValue("schema_version") == "1.0") { "Unsupported character state schema" }
        val characterId = root.stringValue("character_id")
        require(characterId == expectedCharacterId) {
            "Character state file ${file.name} declares $characterId instead of $expectedCharacterId"
        }
        require(knownCharacterIds.isEmpty() || characterId in knownCharacterIds) {
            "Unknown character_id in state asset: $characterId"
        }
        val states = root["states"].asJsonArray("states").mapIndexed { index, raw ->
            val state = raw.asJsonObject("states[$index]")
            require(state.stringList("evidence_refs").isNotEmpty()) {
                "State evidence_refs must not be empty in states[$index]"
            }
            TimedCharacterState(
                stateId = state.stringValue("state_id"),
                validFrom = state.stringValue("valid_from"),
                validTo = state["valid_to"] as? String,
                scene = state.stringValue("scene"),
                questState = state.stringValue("quest_state"),
                currentGoal = state.stringValue("current_goal"),
                possessions = state["possessions"].asJsonArray("states[$index].possessions").mapIndexed { itemIndex, rawItem ->
                    val item = rawItem.asJsonObject("states[$index].possessions[$itemIndex]")
                    require(item.stringList("evidence_refs").isNotEmpty()) {
                        "Possession evidence_refs must not be empty in states[$index].possessions[$itemIndex]"
                    }
                    CurrentPossession(
                        itemId = item.stringValue("item_id"),
                        name = item.stringValue("name"),
                        status = PossessionStatus.fromWire(item.stringValue("status")),
                        description = item.stringValue("description"),
                        keywords = item.stringList("keywords"),
                    )
                },
            )
        }
        validate(characterId, states)
        return CharacterStateDocument(characterId, states)
    }

    private fun validate(characterId: String, states: List<TimedCharacterState>) {
        require(states.map { it.stateId }.distinct().size == states.size) {
            "Duplicate state_id in $characterId state asset"
        }
        states.forEach { state ->
            require(state.validFrom in cutoffOrder) { "Unknown valid_from ${state.validFrom} in ${state.stateId}" }
            state.validTo?.let { validTo ->
                require(validTo in cutoffOrder) { "Unknown valid_to $validTo in ${state.stateId}" }
                require(cutoffOrder.getValue(validTo) >= cutoffOrder.getValue(state.validFrom)) {
                    "Invalid state interval in ${state.stateId}"
                }
            }
            require(state.scene.isNotBlank() && state.questState.isNotBlank() && state.currentGoal.isNotBlank()) {
                "State ${state.stateId} must retain non-blank current context"
            }
            require(state.possessions.map { it.itemId }.distinct().size == state.possessions.size) {
                "Duplicate or contradictory possession item_id in ${state.stateId}"
            }
            require(state.possessions.all { it.name.isNotBlank() && it.description.isNotBlank() && it.keywords.isNotEmpty() }) {
                "Possessions in ${state.stateId} must have name, description and keywords"
            }
        }
        for (index in states.indices) {
            for (otherIndex in 0 until index) {
                require(!intervalsOverlap(states[index], states[otherIndex])) {
                    "Overlapping current-state intervals in $characterId: ${states[otherIndex].stateId}, ${states[index].stateId}"
                }
            }
        }
    }

    private fun isActiveAt(state: TimedCharacterState, cutoff: String): Boolean {
        val current = cutoffOrder.getValue(cutoff)
        val starts = cutoffOrder.getValue(state.validFrom)
        val ends = state.validTo?.let(cutoffOrder::getValue) ?: Int.MAX_VALUE
        return current in starts..ends
    }

    private fun intervalsOverlap(first: TimedCharacterState, second: TimedCharacterState): Boolean {
        val firstStart = cutoffOrder.getValue(first.validFrom)
        val firstEnd = first.validTo?.let(cutoffOrder::getValue) ?: Int.MAX_VALUE
        val secondStart = cutoffOrder.getValue(second.validFrom)
        val secondEnd = second.validTo?.let(cutoffOrder::getValue) ?: Int.MAX_VALUE
        return firstStart <= secondEnd && secondStart <= firstEnd
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        this[key].asJsonArray(key).map { it as? String ?: error("$key must contain strings") }
}
