package com.example.minicpm_v_demo.harness.character

/** Immutable names for one bound turn. Ambiguous names must not trigger enforcement. */
data class RoleplayCharacterIdentity(
    val name: String,
    val aliases: List<String> = emptyList(),
) {
    companion object {
        private val genericNames = setOf("我", "你", "他", "她", "它", "角色", "玩家", "先生", "女士", "老师", "师父", "师傅")

        /** 从当前角色卡提取用于输出检查的姓名和别名，并剔除通用称谓及与其他角色重名的称呼。 */
        fun fromCards(characterId: String, cards: List<CharacterCard>): RoleplayCharacterIdentity {
            val current = cards.firstOrNull { it.npcId == characterId } ?: return RoleplayCharacterIdentity("")
            fun normalized(name: String) = name.trim()
            val otherNames = cards.asSequence().filter { it.npcId != characterId }
                .flatMap { (listOf(it.identity.name) + it.identity.aliases).asSequence() }
                .map(::normalized).filter(String::isNotEmpty).toSet()
            fun eligible(value: String): Boolean {
                val name = normalized(value)
                return name.length >= 2 && name !in genericNames && name !in otherNames
            }
            val name = current.identity.name.takeIf(::eligible)?.trim().orEmpty()
            val aliases = current.identity.aliases.filter(::eligible).map(::normalized)
                .filter { it != name }.distinct()
            return RoleplayCharacterIdentity(name, aliases)
        }
    }
}
