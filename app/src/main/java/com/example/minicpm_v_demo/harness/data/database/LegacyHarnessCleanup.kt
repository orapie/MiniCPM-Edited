package com.example.minicpm_v_demo.harness.data.database

import java.io.File
import java.security.MessageDigest

/** Removes only byte-identical files shipped by the old APK, after a validated database is active. */
internal class LegacyHarnessCleanup(
    private val filesDir: File,
    private val knownFiles: Map<String, String> = shippedHashes,
) {
    fun run(databaseReady: Boolean) {
        if (!databaseReady) return
        val parent = filesDir.canonicalFile
        val root = File(parent, "harness").absoluteFile
        val marker = File(parent, ".legacy-harness-cleanup-v1").absoluteFile
        if (marker.canonicalFile != marker || marker.exists()) return
        if (root.canonicalFile != root) return
        val dirs = mutableSetOf<File>()
        knownFiles.forEach { (relative, expected) ->
            val parts = relative.split('/')
            require(parts.all { it.isNotEmpty() && it != "." && it != ".." } && '\\' !in relative)
            val file = File(root, relative).absoluteFile
            var parent = file.parentFile
            while (parent != null && parent != root) { dirs.add(parent); parent = parent.parentFile }
            // canonical equality rejects symlinks in any component, including the file itself.
            if (file.canonicalFile == file && file.isFile && sha256(file) == expected) {
                check(file.delete()) { "Could not remove verified legacy asset" }
            }
        }
        dirs.sortedByDescending { it.path.length }.forEach { dir ->
            if (dir.canonicalFile == dir && dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete()
        }
        marker.writeText("complete\n")
    }

    companion object {
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        // Path and hash only: no old content is packaged or used as knowledge.
        private val shippedHashes: Map<String, String> = """
            agent/ab_cases.json	fdc459e60790916da961129f80b7672238b6325aa8605ecd83ad9836ce09a757
            agent/multiturn_cases.json	8c72ce52b0180ee0a0d85348f19ea2b7396bfe1ceec9c3b40016a995153f7a74
            agent/scenarios.json	7866ce822c9f400251deed4a294e18807bcd683d8bba997df768e7060f840727
            characters/characters/README.md	fdc9e40349c4ac28182f946126d6a25119501da6495b31e3a57fd320117d801f
            characters/characters/bai_junyi.json	329a63013a1689ea941c2b365a93fe7e6209a996e2b3b4120bb058ff71b5a356
            characters/characters/jiang_qing.json	7b80c3c4a938622c69c569cf753bf1b04a16095673140d66e755c38909a28d53
            characters/characters/li_jiangqun.json	a116332891fbb0d9067ec102e24fc596c8a4431f6cd003e66a0ed5e6972cd2e0
            characters/characters/lu_jiangxian.json	86dbae828c819f727b85f174a899ed2b4a61d55dc4eb198f5e14ee84a13277a3
            characters/characters/qing_yudian.json	a3da9eca541e6ffc36494b9f95ae3d9af038c7dd5667938f5ba3143b6dfdcddb
            characters/characters/xuan_an.json	b0c15ec2e77a569f255dd52e2f2e715457add2d89e34efefab624af42d102292
            characters/knowledge/v1/.asset_hashes	ca86d3abd686bfba625a7b85f1df15d66b63369c65e47b52a40f02f30cc08a7b
            characters/knowledge/v1/claims.jsonl	f73443a5a2774d7db7b93affed4b592409115f2b3ae3ca222e369b692d31b6b2
            characters/knowledge/v1/manifest.json	a06638627bdca297ec87065e04ea4c8f66244338b16931d4cd9d3b7aa53da777
            characters/knowledge/v1/relations.jsonl	d238531c1196ce97109b8b6c5b19fd6aefce5a594561112c26b0d9a10cd6b11e
            characters/knowledge/v2/.asset_hashes	0c27ec6531fe5df61ac3ad7f0fd43696a80b088e8252deb3cdf35d3fc0cf7af3
            characters/knowledge/v2/character_knowledge_v2.sqlite3	fc0f2b2f647a99df4bc5d08ca112e84471cf04e6db797916a21b4017e29472a2
            characters/knowledge/v2/knowledge_fallback.json	69dc3ac084e94e8031e646c484a71cdd6b5af74b5d834d9c76bdf654436b3c2b
            characters/knowledge/v2/manifest.json	828cca90d32d7f5219c76334d3c234d1dc62822c682dac0aae48ee433dd049ab
            characters/prompts/README.md	d9209c90901f7c4d0eb3235a9aabc78f8890dc6108310e0e4d60e6ae4d027da3
            characters/prompts/character_extraction.prompt	ee91664b0a966a1dd43990e42922c89381972debecac11c0ac5a81c36a4e8b3f
            characters/prompts/roleplay_system.prompt	7294f562e685740301c9c640b2324e1f17fff0952236a992aaf99d7728f0caa6
            characters/schemas/README.md	e5d43f59143f8202934d5701fb0b7a7eecad5ebe6400b8dceb3940659503662d
            characters/schemas/character_state_schema.json	131ff0b6ce4cc0b18d20646ce3895de511f68621ae8d146c31e137a6703e0d4e
            characters/schemas/npc_schema.json	e2d03ac53471d73d6f2f23c4a3a2474ef9ae493044de637d2e32a2a005ed594e
            characters/schemas/story_event_schema.json	483669a0c8d392162d86a4dab07e03ffa604b8bdc44813932938bdee1f867b25
            characters/state/lu_jiangxian.json	990219093318fb57a84bf9d26d264759292f0c48a952dddb54d501340f80935f
            characters/story/README.md	ff1aed3c1addcc75778a4589000eb4c1a81276a92cee43b3b7272510ccb2936a
            characters/story/story_events.jsonl	0e4de9a770fd833abd4d552247c42bb670603a17b1f192d8a78cadf62f1ebd93
            fixtures/showcase_questions.json	e12fe7c44fde09b1307f61f96d16ccff0462990493a27fd93fab3e006ff6ea46
            manifest.json	65540205055958505f55f4e8de74813a70ed58e432a1a7a921da44b038388f23
            rag/documents/novel_test/1453.txt	7510f4ed9208890219b5b823ac9ed912de60ca3902f13100eda12f404323d6bf
            rag/documents/novel_test/1454.txt	2f4931c2da04bb2ba7060e4e032556b465cb8fb6a8370220e07874bbda1918cd
            rag/documents/novel_test/1455.txt	70dac777d4f4941bdc14fa827d46ba00604699d1148832f8f830adad7457e565
            rag/documents/novel_test/1456.txt	9e83fc4e37c55e1315e7a6707902499ae5c473879d983a954e7497ac0c41a13b
            rag/documents/novel_test/1457.txt	cf476041d6888a155917e39be4c16f444410ab79e91a390aaa6ebf7e1c0d62f3
            rag/documents/novel_test/1458.txt	353547903e0699ba703f675754a64d91df67c98bd38ce1ad0721f798c7dd6ef3
            rag/documents/novel_test/qa.json	0e39c193d1e57ee3cd2209a5a34bddf8d5813317ef6f17c032e84aad54250183
            rag/documents_manifest.json	6566ed2b488b06df8a511168f98fb12e58b520a6914e2f9592709365e24a3806
            rag/index.json	c01a302df2e3677ebbf32bac069ce48d66d054c385fd24095da9faf3ca4330ae
            roles/default.json	ef2895c23b86295ce0d2b6d8e145d717535f21430ded0139157126201dbd9029
            roles/xuan_an.json	b2816440e963eaa04d3823a7d803f7dd85126007ef112b15b78873b0052f6978
        """.trimIndent().lineSequence().filter { it.isNotBlank() }.associate { line ->
            val (path, hash) = line.split('\t'); path to hash
        }
    }
}
