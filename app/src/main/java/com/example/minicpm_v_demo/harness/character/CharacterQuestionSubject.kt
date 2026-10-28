package com.example.minicpm_v_demo.harness.character

/** Conservative subject check before expanding a question into the speaker's own profile. */
object CharacterQuestionSubject {
    fun isSelfOrigin(question: String): Boolean {
        val compact = question.trim().replace(Regex("[\\s？?！!。,.，]"), "")
        if ("自己" in compact) return true
        if (Regex("(?:你|您)(?:知道|了解|听说|听过|认为).*(?:来历|来自|出身|从哪里|从何)").containsMatchIn(compact)) return false
        if (Regex("你的(?:同学|朋友|师父|父亲|母亲|妻子|丈夫|老师|搭档|孩子|亲人|同伴|对手).*(?:来历|来自|出身|从哪里|从何)").containsMatchIn(compact)) return false
        return "你" in compact || "您" in compact || compact in setOf(
            "来自哪里", "从哪里来", "从何而来", "来历是什么", "什么来历", "出身哪里",
        )
    }
}
