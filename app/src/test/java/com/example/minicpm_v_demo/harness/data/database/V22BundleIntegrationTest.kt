package com.example.minicpm_v_demo.harness.data.database

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V22BundleIntegrationTest {
    @Test fun generatedV22BundleValidatesAndRejectsTamperedSourceProjection() {
        val repo = (if (File("settings.gradle.kts").isFile) File(".") else File("..")).canonicalFile
        val temporary = Files.createTempDirectory("v22-bundle-test").toFile()
        try {
            val root = File(temporary, "database")
            databaseTestSource().rootDir.copyRecursively(root, overwrite = true)
            fun python(vararg args: String) {
                val process = ProcessBuilder("python3", *args).directory(repo).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.waitFor())
            }
            python("scripts/prepare_database_bundle.py", "--source", root.absolutePath, "--dataset-version", "v22-integration-test")
            val bundle = DatabaseBundleValidator.validate(DatabaseDataPaths(root))
            assertEquals(536, bundle.candidateFactCount)
            assertEquals(3, bundle.authorizedFactCount)
            assertEquals("hash_ngram", bundle.manifest.embedding["type"])
            assertTrue(bundle.paths.resolve(bundle.manifest.paths.getValue("semantic_index")).isFile)
            val semanticCopy = File(temporary, "bad-semantic")
            val edgeCopy = File(temporary, "bad-edge")
            root.copyRecursively(semanticCopy)
            root.copyRecursively(edgeCopy)
            // Rehash the outer files to verify semantic validation, rather than
            // merely detecting a stale top-level file checksum.
            val mutate = """
import json,sys,hashlib
from pathlib import Path
sys.path.insert(0,'scripts')
from prepare_database_bundle import fingerprint
root=Path(sys.argv[1]); p=root/'runtime/android/scene_candidates.json'
x=json.loads(p.read_text());x['evidence'][0]['source_projection']['line_spans'][0]['start'] += 1
p.write_text(json.dumps(x,ensure_ascii=False,sort_keys=True,indent=2)+'\n')
m=root/'manifest.json'; v=json.loads(m.read_text())
next(i for i in v['files'] if i['path']=='runtime/android/scene_candidates.json')['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
v['dataset_fingerprint']=fingerprint(v)
m.write_text(json.dumps(v,ensure_ascii=False,sort_keys=True,indent=2)+'\n')
""".trimIndent()
            python("-c", mutate, root.absolutePath)
            val error = runCatching { DatabaseBundleValidator.validate(DatabaseDataPaths(root)) }.exceptionOrNull()
            assertNotNull("Tampered source projection must be rejected", error)
            assertTrue("Unexpected failure: $error", error!!.message.orEmpty().contains("projection mismatch"))
            val tamper = """
import json,sys,hashlib
from pathlib import Path
sys.path.insert(0,'scripts')
from prepare_database_bundle import fingerprint
root=Path(sys.argv[1]); relative=sys.argv[2]; mode=sys.argv[3]; p=root/relative
x=json.loads(p.read_text())
if mode=='semantic': x['model_revision']='wrong'
if mode=='edge': x['knowledge_edges'][0]['authorization_status']='forged'
p.write_text(json.dumps(x,ensure_ascii=False,sort_keys=True,indent=2)+'\n')
m=root/'manifest.json'; v=json.loads(m.read_text())
next(i for i in v['files'] if i['path']==relative)['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
v['dataset_fingerprint']=fingerprint(v)
m.write_text(json.dumps(v,ensure_ascii=False,sort_keys=True,indent=2)+'\n')
""".trimIndent()
            python("-c", tamper, semanticCopy.absolutePath, "knowledge_base_v2/index/semantic_index_manifest.json", "semantic")
            val semanticError = runCatching { DatabaseBundleValidator.validate(DatabaseDataPaths(semanticCopy)) }.exceptionOrNull()
            assertTrue("Unexpected failure: $semanticError", semanticError?.message.orEmpty().contains("semantic metadata mismatch"))
            python("-c", tamper, edgeCopy.absolutePath, "runtime/android/scene_candidates.json", "edge")
            val edgeError = runCatching { DatabaseBundleValidator.validate(DatabaseDataPaths(edgeCopy)) }.exceptionOrNull()
            assertTrue("Unexpected failure: $edgeError", edgeError?.message.orEmpty().contains("Invalid V2.2 source edge"))
        } finally {
            temporary.deleteRecursively()
        }
    }
}
